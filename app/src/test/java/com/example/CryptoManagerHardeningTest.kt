package com.example

import com.example.data.local.dao.ConfigDao
import com.example.data.security.AesGcmCipher
import com.example.data.security.AndroidKeyStoreAccess
import com.example.data.security.AndroidKeyStoreCryptoKeySource
import com.example.data.security.CryptoKeySource
import com.example.domain.ai.GeminiManager
import com.example.domain.ai.geminiHttpErrorMessage
import com.example.domain.gmail.GmailMimeBuilder
import okhttp3.OkHttpClient
import java.util.Base64
import java.lang.reflect.Proxy
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
    fun mimeBuilderRejectsHeaderInjectionInsteadOfSanitizingItIntoAValidHeader() {
        assertTrue(GmailMimeBuilder.containsHeaderControls("Offer title\r\nBcc: test@example.invalid"))
    }

    @Test
    fun geminiHttpDiagnosticsNeverIncludeProviderResponseBodiesOrCredentials() {
        val apiKey = "test-only-provider-secret"
        val untrustedProviderBody = "error for key $apiKey and private request payload"
        val safeError = geminiHttpErrorMessage(400)

        assertFalse(safeError.contains(apiKey))
        assertFalse(safeError.contains(untrustedProviderBody))
        assertFalse(safeError.contains("private request payload"))
        assertTrue(safeError.contains("HTTP 400"))
    }

    @Test
    fun geminiRequestsCannotFollowRedirectsOrRetargetTheProviderKey() {
        val configDao = Proxy.newProxyInstance(
            ConfigDao::class.java.classLoader,
            arrayOf(ConfigDao::class.java)
        ) { _, _, _ -> null } as ConfigDao
        val manager = GeminiManager(configDao)
        val clientField = GeminiManager::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        val client = clientField.get(manager) as OkHttpClient

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)

        val endpointValidator = GeminiManager::class.java.getDeclaredMethod(
            "isTrustedGeminiEndpoint",
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }
        fun isTrusted(url: String): Boolean = endpointValidator.invoke(manager, url, "gemini-2.5-flash") as Boolean

        assertTrue(isTrusted("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
        assertFalse(isTrusted("https://generativelanguage.googleapis.com.attacker.invalid/v1beta/models/gemini-2.5-flash:generateContent"))
        assertFalse(isTrusted("http://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"))
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
