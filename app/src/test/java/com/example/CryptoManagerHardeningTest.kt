package com.example

import com.example.data.security.AesGcmCipher
import com.example.data.security.AndroidKeyStoreAccess
import com.example.data.security.AndroidKeyStoreCryptoKeySource
import com.example.data.security.CryptoKeySource
import com.example.domain.ai.sanitizeGeminiError
import com.example.domain.gmail.sanitizeMimeHeader
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoManagerHardeningTest {
    @Test
    fun aesGcmUsesFreshNoncesAndRoundTrips() {
        val crypto = AesGcmCipher(TestKeySource())
        val first = crypto.encrypt("test-only-secret")
        val second = crypto.encrypt("test-only-secret")

        assertNotEquals(first, second)
        assertEquals("test-only-secret", crypto.decryptOrNull(first))
        assertEquals("test-only-secret", crypto.decryptOrNull(second))
    }

    @Test
    fun malformedTruncatedAndTamperedCiphertextFailClosed() {
        val crypto = AesGcmCipher(TestKeySource())
        val valid = crypto.encrypt("test-only-secret")
        val tamperedBytes = Base64.getDecoder().decode(valid)
        tamperedBytes[tamperedBytes.lastIndex] = (tamperedBytes.last().toInt() xor 0x01).toByte()
        val tampered = Base64.getEncoder().encodeToString(tamperedBytes)
        val tooShort = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        assertNull(crypto.decryptOrNull("not-valid-base64"))
        assertNull(crypto.decryptOrNull(tooShort))
        assertNull(crypto.decryptOrNull(tampered))
        assertEquals("test-only-secret", crypto.decryptOrNull(valid))
    }

    @Test
    fun androidKeyStoreSourceReloadsAfterCreatingMissingAlias() {
        var storedKey: SecretKey? = null
        var readCount = 0
        var createCount = 0
        val source = AndroidKeyStoreCryptoKeySource(object : AndroidKeyStoreAccess {
            override fun readSecretKey(): SecretKey? {
                readCount++
                return storedKey
            }

            override fun createSecretKey() {
                createCount++
                storedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        })

        val createdKey = source.keyForEncryption()

        assertEquals(2, readCount)
        assertEquals(1, createCount)
        assertSame(createdKey, source.keyForDecryption())
    }

    @Test
    fun missingKeyDoesNotRefreshDuringDecryptButCanBeCreatedForNewWrites() {
        val source = TestKeySource()
        val crypto = AesGcmCipher(source)
        val oldCiphertext = crypto.encrypt("old-value")
        val keysCreatedBeforeLoss = source.keysCreated
        source.forgetKey()

        assertNull(crypto.decryptOrNull(oldCiphertext))
        assertEquals("Decrypt must not silently replace a missing key", keysCreatedBeforeLoss, source.keysCreated)

        val newCiphertext = crypto.encrypt("new-value")
        assertEquals(keysCreatedBeforeLoss + 1, source.keysCreated)
        assertEquals("new-value", crypto.decryptOrNull(newCiphertext))
        assertNull(crypto.decryptOrNull(oldCiphertext))
    }

    @Test
    fun providerExceptionsDuringDecryptAreContainedWithoutLogging() {
        val source = object : CryptoKeySource {
            override fun keyForEncryption(): SecretKey = error("not used")
            override fun keyForDecryption(): SecretKey? = throw IllegalStateException("provider failure")
        }
        val crypto = AesGcmCipher(source)
        val ciphertext = Base64.getEncoder().encodeToString(ByteArray(28) { 1 })

        assertNull(crypto.decryptOrNull(ciphertext))
    }

    @Test
    fun mimeHeadersCannotIntroduceAdditionalLines() {
        val sanitized = sanitizeMimeHeader("Offer title\r\nBcc: test@example.invalid")

        assertFalse(sanitized.contains('\r'))
        assertFalse(sanitized.contains('\n'))
        assertEquals("Offer title Bcc: test@example.invalid", sanitized)
    }

    @Test
    fun geminiFailureMessagesRedactTheConfiguredKey() {
        val apiKey = "test-only-gemini-key"
        val sanitized = sanitizeGeminiError("Upstream error for key $apiKey", apiKey)

        assertFalse(sanitized.contains(apiKey))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    private class TestKeySource : CryptoKeySource {
        private var key: SecretKey? = null
        var keysCreated: Int = 0
            private set

        override fun keyForEncryption(): SecretKey {
            key?.let { return it }
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
                key = it
                keysCreated++
            }
        }

        override fun keyForDecryption(): SecretKey? = key

        fun forgetKey() {
            key = null
        }
    }
}
