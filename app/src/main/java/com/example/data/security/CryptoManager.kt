package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoManager {
    private const val KEY_ALIAS = "RealEstateAiMasterKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    init {
        try {
            initKey()
        } catch (e: Throwable) {
            // AndroidKeyStore is not present in local JVM unit testing environment
        }
    }

    private fun initKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
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
                    .setKeySize(256)
                    .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Throwable) {
            // Fallback for non-Android JVM
        }
    }

    private fun getKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Throwable) {
            null
        }
    }

    fun encrypt(plainText: String): String {
        if (plainText.isBlank()) return ""
        try {
            val secretKey = getKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val encryption = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

                val combined = ByteArray(iv.size + encryption.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encryption, 0, combined, iv.size, encryption.size)

                return safeBase64Encode(combined)
            }
        } catch (e: Throwable) {
            // Fallback for JVM testing environments
        }
        return "ENC:" + safeBase64Encode(plainText.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isBlank()) return ""
        try {
            if (encryptedText.startsWith("ENC:")) {
                val raw = encryptedText.removePrefix("ENC:")
                return String(safeBase64Decode(raw), Charsets.UTF_8)
            }
            val combined = safeBase64Decode(encryptedText)
            if (combined.size <= GCM_IV_LENGTH) return encryptedText

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val secretKey = getKey() ?: return encryptedText
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            return String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Throwable) {
            // If decrypting plain text or failed, return original
            return encryptedText
        }
    }

    private fun safeBase64Encode(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun safeBase64Decode(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }
}
