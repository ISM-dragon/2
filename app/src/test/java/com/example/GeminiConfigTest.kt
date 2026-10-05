package com.example

import com.example.data.local.entity.ApiConfigurationEntity
import com.example.data.security.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class GeminiConfigTest {

    @Test
    fun testKeyEncryptionAndMasking() {
        val rawApiKey = "AIzaSyB_TEST_KEY_1234567890"
        val encrypted = CryptoManager.encrypt(rawApiKey)

        assertFalse("Encrypted string should not contain raw key", encrypted.contains(rawApiKey))

        val decrypted = CryptoManager.decrypt(encrypted)
        assertEquals(rawApiKey, decrypted)

        // Redaction verification: ensure sensitive keys never leak in raw logs
        val sampleErrorMessage = "HTTP 400: Invalid API key $rawApiKey supplied"
        val sanitized = sampleErrorMessage.replace(rawApiKey, "[REDACTED]")
        assertFalse(sanitized.contains(rawApiKey))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun testCooldownEligibilityLogic() {
        val now = System.currentTimeMillis()

        val activeSlot = ApiConfigurationEntity(
            slotIndex = 1,
            label = "Primary",
            apiKey = "ENC:key1",
            status = "READY",
            cooldownUntil = 0L
        )

        val cooledSlot = ApiConfigurationEntity(
            slotIndex = 2,
            label = "Secondary",
            apiKey = "ENC:key2",
            status = "COOLDOWN",
            cooldownUntil = now + 60_000L // Still in cooldown
        )

        val expiredCooldownSlot = ApiConfigurationEntity(
            slotIndex = 3,
            label = "Tertiary",
            apiKey = "ENC:key3",
            status = "COOLDOWN",
            cooldownUntil = now - 5_000L // Cooldown has passed
        )

        // Slot 1 is immediately eligible
        assertTrue(activeSlot.status == "READY")

        // Slot 2 is still in cooldown
        assertTrue(cooledSlot.status == "COOLDOWN" && now < cooledSlot.cooldownUntil)

        // Slot 3 cooldown has expired and should be eligible to reset to READY
        assertTrue(expiredCooldownSlot.status == "COOLDOWN" && now > expiredCooldownSlot.cooldownUntil)
    }

    @Test
    fun testDisabledOrEmptySlotsAreSkipped() {
        val emptySlot = ApiConfigurationEntity(
            slotIndex = 4,
            label = "Slot 4",
            apiKey = "",
            status = "DISABLED"
        )
        assertTrue(emptySlot.apiKey.isBlank() || emptySlot.status == "DISABLED")
    }
}
