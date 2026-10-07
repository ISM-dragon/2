package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small key-source abstraction so the authenticated-encryption behavior can be tested on the JVM.
 * Android production builds always use AndroidKeyStore; the ephemeral source is only for local JVM
 * tests where AndroidKeyStore is unavailable.
 */
internal interface CryptoKeySource {
    fun keyForEncryption(): SecretKey
    fun keyForDecryption(): SecretKey?
}

internal interface AndroidKeyStoreAccess {
    fun readSecretKey(): SecretKey?
    fun createSecretKey()
}

internal class AndroidKeyStoreCryptoKeySource(
    private val androidKeyStore: AndroidKeyStoreAccess = PlatformAndroidKeyStoreAccess()
) : CryptoKeySource {
    @Synchronized
    override fun keyForEncryption(): SecretKey {
        androidKeyStore.readSecretKey()?.let { return it }

        androidKeyStore.createSecretKey()

        // readSecretKey() opens a new KeyStore instance. A KeyStore object loaded before a
        // mutation can hold a stale view on some providers.
        return androidKeyStore.readSecretKey()
            ?: throw GeneralSecurityException("AndroidKeyStore key unavailable after creation")
    }

    override fun keyForDecryption(): SecretKey? = androidKeyStore.readSecretKey()
}

private class PlatformAndroidKeyStoreAccess : AndroidKeyStoreAccess {
    override fun readSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) return null

        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw GeneralSecurityException("AndroidKeyStore entry is not an AES secret key")
        return entry.secretKey
    }

    override fun createSecretKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // Require a provider-generated random IV for every encryption operation.
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "RealEstateAiMasterKey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256
    }
}

internal class InMemoryCryptoKeySource : CryptoKeySource {
    @Volatile
    private var key: SecretKey? = null

    @Synchronized
    override fun keyForEncryption(): SecretKey {
        key?.let { return it }
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    override fun keyForDecryption(): SecretKey? = key
}

internal class AesGcmCipher(private val keySource: CryptoKeySource) {
    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource.keyForEncryption())
        val iv = cipher.iv
        check(iv.size == GCM_IV_LENGTH) { "Unexpected AES-GCM IV length" }

        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return encodeBase64(iv + encryptedBytes)
    }

    /**
     * Returns null when the stored value is malformed, unauthenticated, or its key is unavailable.
     * It deliberately does not create/rotate a key while decrypting: doing so would hide data loss
     * and could make every other value encrypted by the previous key permanently unreadable.
     */
    fun decryptOrNull(encryptedText: String): String? {
        if (encryptedText.isBlank()) return ""

        return try {
            val combined = decodeBase64(encryptedText)
            if (combined.size < GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) return null

            val key = keySource.keyForDecryption() ?: return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            // Fail closed for corrupt ciphertext, invalid GCM tags, unavailable keys, and provider
            // errors. Do not log the ciphertext, key, or exception message.
            null
        }
    }

    private fun encodeBase64(bytes: ByteArray): String = try {
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: RuntimeException) {
        // Android's Base64 class is stubbed in ordinary local JVM tests. Do not call java.util.Base64
        // as a production fallback because Android API 24-25 does not provide that class.
        if (isAndroidRuntime()) throw e
        java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: RuntimeException) {
        // Android's Base64 class is stubbed in ordinary local JVM tests. Invalid Android input is
        // allowed to fail through the caller's fail-closed exception handling.
        if (isAndroidRuntime()) throw e
        java.util.Base64.getDecoder().decode(value)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    }
}

private fun isAndroidRuntime(): Boolean {
    val vmName = System.getProperty("java.vm.name").orEmpty()
    return vmName.contains("Dalvik", ignoreCase = true) ||
        vmName.contains("Android", ignoreCase = true)
}

object CryptoManager {
    private val cipher: AesGcmCipher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AesGcmCipher(if (isAndroidRuntime()) AndroidKeyStoreCryptoKeySource() else InMemoryCryptoKeySource())
    }

    /** Encrypts into an IV + ciphertext/tag Base64 payload using AES-256-GCM. */
    fun encrypt(plainText: String): String = cipher.encrypt(plainText)

    /**
     * Decrypts an authenticated value. Empty plaintext remains supported, but malformed,
     * unauthenticated, or key-inaccessible ciphertext raises [CryptoDecryptionException] instead of
     * being silently presented as an empty (valid) value.
     */
    fun decrypt(encryptedText: String): String =
        decryptOrNull(encryptedText) ?: throw CryptoDecryptionException()

    internal fun decryptOrNull(encryptedText: String): String? = cipher.decryptOrNull(encryptedText)
}

class CryptoDecryptionException internal constructor() :
    GeneralSecurityException("Encrypted value could not be authenticated or decrypted")
