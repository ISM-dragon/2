package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.GmailConfigurationEntity
import com.example.domain.gmail.EmailRetryPolicy
import com.example.domain.gmail.GmailFailureKind
import com.example.domain.gmail.GmailMimeBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GmailMimeAndRetryPolicyTest {

    @Test
    fun mimeEncodesUnicodeHeadersAndUsesValidUtf8TransferEncoding() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31)
        val mime = GmailMimeBuilder.build(
            config = GmailConfigurationEntity(
                accountEmail = "sender@example.com",
                senderName = "Acquisitions 東京",
                signature = "Regards,\nمريم"
            ),
            recipientEmail = "agent@example.com",
            recipientName = "Zoë 🏠",
            subject = "Offer — € 1,200",
            htmlBody = "<p>Proposal — 東京 🏡</p>",
            pdfBytes = pdf,
            idempotencyKey = "offer-send-v1:offer-123",
            nowMillis = 1_790_000_000_000L
        )
        val raw = String(mime, StandardCharsets.US_ASCII)
        val headers = raw.substringBefore("\r\n\r\n")

        assertTrue(headers.contains("Subject: =?UTF-8?B?"))
        assertTrue(headers.contains("=?UTF-8?B?")) // encoded From/To display names
        assertFalse("Raw headers must remain ASCII", headers.any { it.code > 0x7f })
        assertFalse("Unicode display text must not be inserted verbatim in a header", headers.contains("東京"))
        assertTrue(headers.contains("Message-ID: <offer-"))
        assertTrue(headers.contains("X-Offer-Idempotency-Key:"))
        assertTrue(raw.contains("Content-Type: text/html; charset=UTF-8\r\nContent-Transfer-Encoding: base64"))
        assertTrue(raw.contains("Content-Type: application/pdf; name=\"offer.pdf\""))
        assertTrue(raw.endsWith("--${boundary(headers)}--\r\n"))

        val textPart = raw.substringAfter("--${boundary(headers)}\r\n")
            .substringAfter("\r\n\r\n")
            .substringBefore("\r\n--${boundary(headers)}")
        val decodedText = String(Base64.decode(textPart, Base64.DEFAULT), StandardCharsets.UTF_8)
        assertTrue(decodedText.contains("Proposal — 東京 🏡"))
        assertTrue(decodedText.contains("Regards,<br>مريم"))

        val pdfPart = raw.substringAfter("Content-Type: application/pdf; name=\"offer.pdf\"\r\n")
            .substringAfter("\r\n\r\n")
            .substringBefore("\r\n--${boundary(headers)}--")
        assertArrayEquals(pdf, Base64.decode(pdfPart, Base64.DEFAULT))
    }

    @Test
    fun mimeBuilderRejectsHeaderInjection() {
        val base = GmailConfigurationEntity(accountEmail = "sender@example.com")
        val pdf = byteArrayOf(1)

        val subjectFailure = runCatching {
            GmailMimeBuilder.build(
                config = base,
                recipientEmail = "agent@example.com",
                recipientName = "Agent",
                subject = "Offer\r\nBcc: attacker@example.com",
                htmlBody = "<p>Offer</p>",
                pdfBytes = pdf,
                idempotencyKey = "key-1"
            )
        }.exceptionOrNull()
        assertTrue(subjectFailure is IllegalArgumentException)

        val nameFailure = runCatching {
            GmailMimeBuilder.build(
                config = base,
                recipientEmail = "agent@example.com",
                recipientName = "Agent\nBcc: attacker@example.com",
                subject = "Offer",
                htmlBody = "<p>Offer</p>",
                pdfBytes = pdf,
                idempotencyKey = "key-1"
            )
        }.exceptionOrNull()
        assertTrue(nameFailure is IllegalArgumentException)
    }

    @Test
    fun onlyValidPdfsInsideThePrivateOffersDirectoryCanBeAttached() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offersDirectory = File(context.filesDir, "offers").apply { mkdirs() }
        val allowed = File(offersDirectory, "valid.pdf").apply { writeText("%PDF-1.7\n") }
        val outside = File(context.cacheDir, "not-an-offer.pdf").apply { writeText("%PDF-1.7\n") }
        val invalid = File(offersDirectory, "invalid.pdf").apply { writeText("not a PDF") }

        try {
            assertTrue(GmailMimeBuilder.isApprovedOfferPdf(context, allowed))
            assertFalse(GmailMimeBuilder.isApprovedOfferPdf(context, outside))
            assertFalse(GmailMimeBuilder.isApprovedOfferPdf(context, invalid))
        } finally {
            allowed.delete()
            outside.delete()
            invalid.delete()
        }
    }

    @Test
    fun retryPolicyRetriesOnlyDefiniteRateLimitRejections() {
        val now = 1_800_000_000_000L
        assertEquals(
            now + EmailRetryPolicy.BASE_BACKOFF_MILLIS,
            EmailRetryPolicy.nextAttemptAt(GmailFailureKind.RETRYABLE_REJECTED, 1, now)
        )
        assertEquals(
            now + 120_000L,
            EmailRetryPolicy.nextAttemptAt(GmailFailureKind.RETRYABLE_REJECTED, 1, now, 120_000L)
        )
        assertNull(EmailRetryPolicy.nextAttemptAt(GmailFailureKind.RETRYABLE_REJECTED, 5, now))
        assertNull(EmailRetryPolicy.nextAttemptAt(GmailFailureKind.DELIVERY_UNKNOWN, 1, now))
        assertNull(EmailRetryPolicy.nextAttemptAt(GmailFailureKind.PERMANENT_REJECTED, 1, now))
        assertNull(EmailRetryPolicy.nextAttemptAt(GmailFailureKind.AUTHENTICATION, 1, now))
    }

    private fun boundary(headers: String): String =
        Regex("boundary=\"([^\"]+)\"").find(headers)?.groupValues?.get(1)
            ?: error("MIME boundary not found")
}
