package com.example.urlintelligence

import com.example.urlintelligence.url.JobUrlRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests: job records are written before validation, so the stored form of a user URL
 * must never retain userinfo or credential-like query values.
 */
class JobUrlRedactorTest {

    @Test
    fun `userinfo is replaced by a fixed marker`() {
        val stored = JobUrlRedactor.redact("https://listing-user:fakePassword@www.zillow.com/homedetails/1_zpid/")

        assertEquals("https://REDACTED@www.zillow.com/homedetails/1_zpid/", stored)
        assertFalse(stored.contains("listing-user"))
        assertFalse(stored.contains("fakePassword"))
    }

    @Test
    fun `userinfo without a password is also removed`() {
        val stored = JobUrlRedactor.redact("https://someone@www.zillow.com/homedetails/1_zpid/")

        assertFalse(stored.contains("someone"))
        assertTrue(stored.startsWith("https://REDACTED@www.zillow.com/"))
    }

    @Test
    fun `credential-like query values are redacted but names are kept`() {
        val stored = JobUrlRedactor.redact(
            "https://www.zillow.com/homedetails/1_zpid/?token=fakeQueryValue&listing_id=123&api_key=fakeKey"
        )

        assertEquals(
            "https://www.zillow.com/homedetails/1_zpid/?token=REDACTED&listing_id=123&api_key=REDACTED",
            stored
        )
        assertFalse(stored.contains("fakeQueryValue"))
        assertFalse(stored.contains("fakeKey"))
    }

    @Test
    fun `sensitive values inside the fragment are not query parameters`() {
        val input = "https://www.zillow.com/homedetails/1_zpid/#token=kept"

        assertEquals(input, JobUrlRedactor.redact(input))
    }

    @Test
    fun `an at sign in the query is not mistaken for userinfo`() {
        val input = "https://www.zillow.com/homedetails/1_zpid/?contact=agent@example.test"

        assertEquals(input, JobUrlRedactor.redact(input))
    }

    @Test
    fun `benign listing urls pass through unchanged`() {
        val input = "https://www.zillow.com/homedetails/12345678_zpid/?utm_source=share&page=2"

        assertEquals(input, JobUrlRedactor.redact(input))
    }

    @Test
    fun `non url input never throws`() {
        listOf("", "not a url", "://", "?token=x", "https://", "https://@/?").forEach { raw ->
            JobUrlRedactor.redact(raw)
        }
    }
}
