package com.example

import com.example.automation.FakeAutomationDao
import com.example.domain.automation.AutomationAuditLogger
import com.example.domain.propertyurl.util.Redaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationAuditSecurityTest {
    @Test
    fun auditLogsRedactProviderSecretsAndPersonalContactData() = runBlocking {
        val dao = FakeAutomationDao()
        val logger = AutomationAuditLogger(dao)
        val oauthToken = "ya29.test-only-oauth-token-value"
        val apiKey = "AIza0123456789abcdefghijklmnopqrstuvwx"
        val email = "private.person@example.invalid"

        logger.error(
            tag = "SECURITY_TEST",
            message = "Authorization: Bearer $oauthToken x-goog-api-key=$apiKey recipient=$email"
        )

        val persisted = dao.logMessages().single()
        assertFalse(persisted.contains(oauthToken))
        assertFalse(persisted.contains(apiKey))
        assertFalse(persisted.contains(email))
        assertTrue(persisted.contains(Redaction.MASK))
    }
}
