package com.example.automation

import com.example.domain.automation.FailureClassifier
import com.example.domain.automation.FailureKind
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Retryable vs terminal failure classification. Wrong classification is expensive in both
 * directions: retrying a deterministic defect burns cycles, dropping a transient error loses work.
 */
class FailureClassifierTest {

    @Test
    fun `network and io failures are retryable`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(IOException("socket closed")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(SocketTimeoutException("timeout")))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(UnknownHostException("api.example.com")))
        assertTrue(FailureClassifier.isRetryable(IOException("disk i/o error")))
    }

    @Test
    fun `http 5xx and throttling are retryable while 4xx needs an operator`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(httpError(500)))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(httpError(503)))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(httpError(429)))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(httpError(408)))
        assertEquals(FailureKind.BLOCKED, FailureClassifier.classify(httpError(401)))
        assertEquals(FailureKind.BLOCKED, FailureClassifier.classify(httpError(403)))
    }

    @Test
    fun `deterministic defects are terminal`() {
        assertEquals(FailureKind.TERMINAL, FailureClassifier.classify(IllegalArgumentException("bad input")))
        assertEquals(FailureKind.TERMINAL, FailureClassifier.classify(IllegalStateException("property does not exist locally")))
        assertEquals(FailureKind.TERMINAL, FailureClassifier.classify(FileNotFoundException("/tmp/offer.pdf")))
        assertFalse(FailureClassifier.isRetryable(IllegalArgumentException("bad input")))
    }

    @Test
    fun `cancellation is never retried`() {
        assertEquals(FailureKind.CANCELLED, FailureClassifier.classify(CancellationException("stopped by operator")))
        assertFalse(FailureClassifier.isRetryable(CancellationException("stopped")))
    }

    @Test
    fun `unknown failures default to a bounded retry`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(null))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException()))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(RuntimeException("something odd happened")))
    }

    @Test
    fun `blocking configuration problems are reported as blocked`() {
        assertEquals(
            FailureKind.BLOCKED,
            FailureClassifier.classifyMessage("Gmail account is not connected with a valid authorized OAuth token.")
        )
        assertEquals(
            FailureKind.BLOCKED,
            FailureClassifier.classifyMessage("Offer PDF contract document is missing or corrupted on disk.")
        )
        assertEquals(
            FailureKind.BLOCKED,
            FailureClassifier.classifyMessage("Recipient email 'nope' is not a valid email address.")
        )
        assertEquals(FailureKind.BLOCKED, FailureClassifier.classify(RuntimeException("Recipient email address is blank.")))
    }

    @Test
    fun `transient messages are retryable and deterministic ones are terminal`() {
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classifyMessage("Connection reset by peer"))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classifyMessage("Request timed out after 30s"))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classifyMessage("Gmail API rate limit exceeded"))
        assertEquals(FailureKind.TERMINAL, FailureClassifier.classifyMessage("Offer purchase price is non-positive (0.0)."))
    }

    @Test
    fun `wrapped causes are inspected`() {
        val wrapped = RuntimeException("wrapped", IOException("reset by peer"))
        assertEquals(FailureKind.RETRYABLE, FailureClassifier.classify(wrapped))
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody()))
}
