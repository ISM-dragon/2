package com.example.domain.automation

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.RealEstateAiApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Durable execution vehicle of the automation engine.
 *
 * The worker owns exactly one cycle. Because the cycle lease, the job state machine and the
 * idempotency ledger all live in the database, a process death simply means WorkManager re-delivers
 * the work and the engine reconciles from durable state - no long-running job depends on a
 * process-local coroutine scope.
 */
class AutomationCycleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? RealEstateAiApp ?: return ListenableWorker.Result.success()
        val engine = app.automationEngine

        // Operator intent is authoritative and persisted: a stale work request must not resurrect
        // automation that was stopped or killed in the meantime.
        if (!engine.shouldRunScheduledWork()) {
            return ListenableWorker.Result.success()
        }

        val trigger = inputData.getString(KEY_TRIGGER) ?: CycleTrigger.WORKER
        val outcome = try {
            engine.executeCycle(CycleRequest(trigger = trigger, workerRunAttempt = runAttemptCount))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return if (FailureClassifier.classify(error) == FailureKind.RETRYABLE) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.failure()
            }
        }

        return when (outcome) {
            // Offline cycles are not failures: retry with backoff until connectivity returns.
            is CycleOutcome.Completed -> if (outcome.stats.pausedOffline) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success()
            }
            // Lease contention right after a crash: come back shortly and reclaim the work.
            is CycleOutcome.Skipped -> if (outcome.retryAfterMs != null) {
                ListenableWorker.Result.retry()
            } else {
                ListenableWorker.Result.success()
            }
            is CycleOutcome.Killed -> ListenableWorker.Result.success()
            is CycleOutcome.Retryable -> ListenableWorker.Result.retry()
            is CycleOutcome.Terminal -> ListenableWorker.Result.failure()
        }
    }

    companion object {
        const val KEY_TRIGGER = "automation_cycle_trigger"
        const val UNIQUE_CYCLE_WORK = "automation_cycle_now"
        const val UNIQUE_PERIODIC_WORK = "automation_periodic_scan"
        const val TAG = "automation-cycle"
    }
}

/**
 * WorkManager-backed [AutomationWorkScheduler].
 *
 * - immediate cycles use `KEEP` semantics on a unique name, so tapping "start" twice never stacks
 *   parallel cycles;
 * - the periodic scan is updated in place when the operator changes the scan interval;
 * - stop / kill switch cancels both unique works.
 */
class WorkManagerAutomationScheduler(context: Context) : AutomationWorkScheduler {

    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext)

    override val isPersistent: Boolean = true

    override fun enqueueImmediateCycle(reason: String) {
        val request = OneTimeWorkRequestBuilder<AutomationCycleWorker>()
            .setInputData(workDataOf(AutomationCycleWorker.KEY_TRIGGER to reason))
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(AutomationCycleWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(
            AutomationCycleWorker.UNIQUE_CYCLE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun schedulePeriodic(intervalMinutes: Int) {
        val minutes = intervalMinutes.coerceAtLeast(MIN_PERIODIC_MINUTES)
        val request = PeriodicWorkRequestBuilder<AutomationCycleWorker>(minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(AutomationCycleWorker.KEY_TRIGGER to CycleTrigger.PERIODIC))
            .setConstraints(networkConstraints())
            .addTag(AutomationCycleWorker.TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            AutomationCycleWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override fun cancelAll(reason: String) {
        workManager.cancelUniqueWork(AutomationCycleWorker.UNIQUE_CYCLE_WORK)
        workManager.cancelUniqueWork(AutomationCycleWorker.UNIQUE_PERIODIC_WORK)
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private companion object {
        const val MIN_PERIODIC_MINUTES = 15L
    }
}
