package com.example.domain.propertyurl.job

/**
 * Persistence port for import jobs.
 *
 * The layer never talks to Room/SharedPreferences directly: the Android app can back this port with
 * a Room DAO, a file, or (for tests and previews) the in-memory implementation below. Keeping it a
 * port is also what keeps this module free of Android imports.
 */
interface PropertyImportJobStore {

    suspend fun save(job: PropertyImportJob)

    suspend fun findById(jobId: String): PropertyImportJob?

    /** Most recent job for an idempotency key, in-flight or finished. */
    suspend fun findLatestByKey(idempotencyKey: String): PropertyImportJob?

    suspend fun recent(limit: Int = 50): List<PropertyImportJob>

    /** @return number of removed jobs (retention policy). */
    suspend fun deleteOlderThan(epochMillis: Long): Int

    suspend fun count(): Int
}

/**
 * Thread-safe in-memory store.
 *
 * Bounded on purpose: a mobile process must not accumulate unbounded job history. Eviction is
 * oldest-first and never evicts in-flight jobs.
 */
class InMemoryPropertyImportJobStore(
    private val maxJobs: Int = 500,
    private val retainTerminalJobs: Int = 200
) : PropertyImportJobStore {

    private val lock = Any()
    private val byId = LinkedHashMap<String, PropertyImportJob>()
    private val byKey = LinkedHashMap<String, MutableList<String>>()

    override suspend fun save(job: PropertyImportJob) {
        synchronized(lock) {
            if (!byId.containsKey(job.jobId)) {
                byKey.getOrPut(job.idempotencyKey) { mutableListOf() }.add(job.jobId)
            }
            byId[job.jobId] = job
            evictIfNeeded()
        }
    }

    override suspend fun findById(jobId: String): PropertyImportJob? = synchronized(lock) { byId[jobId] }

    override suspend fun findLatestByKey(idempotencyKey: String): PropertyImportJob? = synchronized(lock) {
        byKey[idempotencyKey]
            ?.mapNotNull { byId[it] }
            ?.maxByOrNull { it.updatedAtEpochMillis }
    }

    override suspend fun recent(limit: Int): List<PropertyImportJob> = synchronized(lock) {
        byId.values.sortedByDescending { it.updatedAtEpochMillis }.take(limit)
    }

    override suspend fun deleteOlderThan(epochMillis: Long): Int = synchronized(lock) {
        val toRemove = byId.values.filter { it.isTerminal && it.updatedAtEpochMillis < epochMillis }.map { it.jobId }
        toRemove.forEach { id ->
            byId.remove(id)?.let { job ->
                byKey[job.idempotencyKey]?.remove(id)
                if (byKey[job.idempotencyKey]?.isEmpty() == true) byKey.remove(job.idempotencyKey)
            }
        }
        toRemove.size
    }

    override suspend fun count(): Int = synchronized(lock) { byId.size }

    fun clear() = synchronized(lock) {
        byId.clear()
        byKey.clear()
    }

    private fun evictIfNeeded() {
        if (byId.size <= maxJobs) return
        val terminal = byId.values
            .filter { it.isTerminal }
            .sortedBy { it.updatedAtEpochMillis }
        val excess = byId.size - maxJobs
        terminal.take(excess.coerceAtMost(terminal.size - retainTerminalJobs).coerceAtLeast(0)).forEach { job ->
            byId.remove(job.jobId)
            byKey[job.idempotencyKey]?.remove(job.jobId)
        }
    }
}
