package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.data.local.entity.OfferEmailSendEntity
import com.example.data.local.entity.OfferEmailSendStatus
import com.example.data.local.entity.OfferEntity
import com.example.data.local.entity.PropertyEntity
import com.example.data.repository.ConfigRepository
import com.example.data.repository.OfferRepository
import com.example.domain.ai.GeminiManager
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailSendResult
import com.example.domain.gmail.GmailSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfferSendIdempotencyTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var configRepository: ConfigRepository
    private lateinit var pdfFile: File
    private val now = AtomicLong(System.currentTimeMillis())

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val property = sampleProperty()
        database.propertyDao().insertProperty(property)
        val offersDirectory = File(context.filesDir, "offers").apply { mkdirs() }
        pdfFile = File(offersDirectory, "offer-${System.nanoTime()}.pdf").apply {
            writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31))
        }
        database.offerDao().insertOffer(sampleOffer(property.id, pdfFile))
        configRepository = ConfigRepository(database.configDao())
        configRepository.saveGmailConfig(
            GmailConfigurationEntity(
                accountEmail = "buyer@example.com",
                senderName = "Acquisitions Team",
                accessToken = "test-access-token",
                refreshToken = "test-refresh-token",
                expiresAt = now.get() + 3_600_000L,
                isConnected = true
            )
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::pdfFile.isInitialized) pdfFile.delete()
    }

    @Test
    fun concurrentSendRequestsUseOneDurableClaimAndOneGmailCall() = runBlocking {
        val sender = QueueSender(
            delayMillis = 200,
            results = listOf(GmailSendResult(success = true, messageId = "gmail-123"))
        )
        val repository = offerRepository(sender)

        val outcomes = coroutineScope {
            (1..2).map {
                async(Dispatchers.IO) { repository.sendOfferDetailed(context, "offer-1") }
            }.awaitAll()
        }

        assertEquals("Concurrent dispatch must invoke Gmail once", 1, sender.callCount.get())
        assertTrue(outcomes.any { it.success })
        val ledger = database.offerDao().getEmailSendForOffer("offer-1")
        assertNotNull(ledger)
        assertEquals(OfferEmailSendStatus.SENT, ledger?.status)
        assertEquals(1, ledger?.attemptCount)
        assertEquals("gmail-123", ledger?.messageId)

        val audit = repository.getAuditTrailForOffer("offer-1").first()
        assertEquals(3, audit.size)
        assertTrue(audit.any { it.eventType == "SEND_RECORD_CREATED" })
        assertTrue(audit.any { it.eventType == "SEND_ATTEMPT_STARTED" })
        assertTrue(audit.any { it.eventType == "EMAIL_SENT" })
        assertEquals("SENT", database.offerDao().getOfferById("offer-1")?.status)
    }

    @Test
    fun ambiguousNetworkOutcomeIsNeverAutomaticallyRetried() = runBlocking {
        val sender = QueueSender(
            results = listOf(
                GmailSendResult(
                    success = false,
                    error = "network timeout",
                    failureKind = GmailFailureKind.DELIVERY_UNKNOWN
                )
            )
        )
        val repository = offerRepository(sender)

        val first = repository.sendOfferDetailed(context, "offer-1")
        val second = repository.sendOfferDetailed(context, "offer-1")

        assertFalse(first.success)
        assertEquals(OfferEmailSendStatus.UNKNOWN, first.status)
        assertFalse(second.success)
        assertEquals(OfferEmailSendStatus.UNKNOWN, second.status)
        assertEquals(1, sender.callCount.get())
        assertEquals(OfferEmailSendStatus.UNKNOWN, database.offerDao().getEmailSendForOffer("offer-1")?.status)
        assertTrue(second.error.orEmpty().contains("automatic retry is disabled"))
    }

    @Test
    fun rateLimitUsesBackoffAndRetriesOnlyAfterItsDueTime() = runBlocking {
        val sender = QueueSender(
            results = listOf(
                GmailSendResult(
                    success = false,
                    error = "rate limited",
                    failureKind = GmailFailureKind.RETRYABLE_REJECTED,
                    httpStatusCode = 429
                ),
                GmailSendResult(success = true, messageId = "gmail-after-backoff")
            )
        )
        val repository = offerRepository(sender)

        val first = repository.sendOfferDetailed(context, "offer-1", maxAttempts = 3)
        assertEquals(OfferEmailSendStatus.RETRYABLE, first.status)
        assertNotNull(first.nextAttemptAt)
        assertEquals(1, sender.callCount.get())

        val tooEarly = repository.sendOfferDetailed(context, "offer-1", maxAttempts = 3)
        assertFalse(tooEarly.attempted)
        assertEquals(1, sender.callCount.get())

        now.set(first.nextAttemptAt!!)
        val retried = repository.sendOfferDetailed(context, "offer-1", maxAttempts = 3)
        assertTrue(retried.success)
        assertEquals("gmail-after-backoff", retried.messageId)
        assertEquals(2, sender.callCount.get())
        assertEquals(2, database.offerDao().getEmailSendForOffer("offer-1")?.attemptCount)
    }

    @Test
    fun declinedOfferIsPreservedAndCannotBeSent() = runBlocking {
        val sender = QueueSender(results = listOf(GmailSendResult(success = true, messageId = "must-not-send")))
        val repository = offerRepository(sender)
        assertTrue(repository.updateStatus("offer-1", "DECLINED"))

        val result = repository.sendOfferDetailed(context, "offer-1")

        assertFalse(result.success)
        assertEquals(OfferEmailSendStatus.BLOCKED, result.status)
        assertEquals(0, sender.callCount.get())
        assertEquals("DECLINED", database.offerDao().getOfferById("offer-1")?.status)
        assertTrue(repository.getAuditTrailForOffer("offer-1").first().any {
            it.eventType == "SEND_BLOCKED"
        })
    }

    @Test
    fun operatorStatusChangeIsDeferredWhileDeliveryIsInFlight() = runBlocking {
        val sender = QueueSender(
            delayMillis = 200,
            results = listOf(GmailSendResult(success = true, messageId = "gmail-while-in-flight"))
        )
        val repository = offerRepository(sender)
        val sending = async(Dispatchers.IO) { repository.sendOfferDetailed(context, "offer-1") }
        sender.callStarted.await()

        assertFalse("Lifecycle change must not race the active send", repository.updateStatus("offer-1", "SIGNED"))
        assertFalse("Deletion must not orphan an active send", repository.deleteOffer("offer-1"))
        assertNotNull(database.offerDao().getOfferById("offer-1"))
        assertEquals("SENDING", database.offerDao().getOfferById("offer-1")?.status)
        assertTrue(sending.await().success)
        assertEquals("SENT", database.offerDao().getOfferById("offer-1")?.status)
        val audit = repository.getAuditTrailForOffer("offer-1").first()
        assertTrue(audit.any { it.eventType == "OFFER_STATUS_UPDATE_DEFERRED" })
        assertTrue(audit.any { it.eventType == "OFFER_DELETION_DEFERRED" })
    }

    @Test
    fun interruptedInFlightSendBecomesUnknownAndIsNotRedispatched() = runBlocking {
        val sender = QueueSender(results = listOf(GmailSendResult(success = true, messageId = "must-not-send")))
        val repository = offerRepository(sender)
        val startedAt = now.get() - OfferRepository.IN_FLIGHT_STALE_AFTER_MILLIS - 1L
        val key = "offer-send-v1:offer-1"
        database.offerDao().insertEmailSendIfAbsent(
            OfferEmailSendEntity(
                idempotencyKey = key,
                offerId = "offer-1",
                status = OfferEmailSendStatus.IN_FLIGHT,
                attemptCount = 1,
                createdAt = startedAt,
                updatedAt = startedAt,
                startedAt = startedAt
            )
        )
        database.offerDao().updateOfferStatus("offer-1", "SENDING", null, null)

        repository.recoverInterruptedSends(now.get())
        val result = repository.sendOfferDetailed(context, "offer-1")

        assertEquals(OfferEmailSendStatus.UNKNOWN, result.status)
        assertEquals(0, sender.callCount.get())
        assertTrue(repository.getAuditTrailForOffer("offer-1").first().any {
            it.eventType == "SEND_OUTCOME_RECOVERED_UNKNOWN"
        })
    }

    private fun offerRepository(sender: GmailSender) = OfferRepository(
        offerDao = database.offerDao(),
        propertyDao = database.propertyDao(),
        configRepository = configRepository,
        geminiManager = GeminiManager(database.configDao()),
        gmailService = sender,
        clock = now::get
    )

    private fun sampleProperty() = PropertyEntity(
        id = "property-1",
        sourceType = "ON_MARKET",
        title = "Test house",
        address = "100 Main Street",
        city = "Austin",
        state = "TX",
        zipCode = "78701",
        latitude = 30.26,
        longitude = -97.74,
        price = 300_000.0,
        propertyType = "Single Family",
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1500,
        yearBuilt = 2015,
        lotSizeSqFt = 5000,
        description = "Test property",
        status = "Active",
        primaryImageUrl = "",
        scannedAt = now.get()
    )

    private fun sampleOffer(propertyId: String, file: File) = OfferEntity(
        id = "offer-1",
        propertyId = propertyId,
        recipientName = "Agent One",
        recipientEmail = "agent@example.com",
        offerPrice = 275_000.0,
        earnestMoney = 4_125.0,
        inspectionPeriodDays = 10,
        closingPeriodDays = 21,
        contingencies = "Inspection",
        terms = "AS-IS",
        conditions = "Clear title",
        expirationDate = "Oct 10, 2026",
        generatedLetterContent = "We are pleased to submit our offer.",
        pdfPath = file.absolutePath,
        status = "READY",
        createdAt = now.get()
    )

    private class QueueSender(
        private val delayMillis: Long = 0,
        results: List<GmailSendResult>
    ) : GmailSender {
        val callCount = AtomicInteger(0)
        val callStarted = CompletableDeferred<Unit>()
        private val queuedResults = ConcurrentLinkedQueue(results)

        override suspend fun sendOfferEmail(
            context: Context,
            recipientEmail: String,
            recipientName: String,
            subject: String,
            htmlBody: String,
            pdfFile: File?,
            idempotencyKey: String?
        ): GmailSendResult {
            callCount.incrementAndGet()
            callStarted.complete(Unit)
            if (delayMillis > 0) delay(delayMillis)
            return queuedResults.poll()
                ?: error("Unexpected extra Gmail send for idempotency key $idempotencyKey")
        }
    }
}
