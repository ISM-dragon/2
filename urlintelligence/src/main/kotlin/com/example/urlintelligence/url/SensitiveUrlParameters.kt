package com.example.urlintelligence.url

import java.net.URLDecoder
import java.util.Locale

/** Credential-like URL query keys. Values are never sent to adapters or retained in job URLs. */
object SensitiveUrlParameters {
    private val exactNames = setOf(
        "token", "access_token", "id_token", "refresh_token", "auth", "authorization",
        "apikey", "api_key", "key", "secret", "signature", "sig", "session", "sessionid",
        "sid", "password", "pwd", "otp", "code", "sharedid", "authid", "credential",
        "email", "phone", "mobile", "user", "username", "user_id", "userid", "customer_id"
    )

    fun isSensitive(name: String): Boolean {
        val decodedRaw = try {
            URLDecoder.decode(name, "UTF-8")
        } catch (_: Exception) {
            name
        }
        val decoded = decodedRaw.lowercase(Locale.US).replace('-', '_')
        return decoded in exactNames ||
            decoded.endsWith("_token") ||
            decoded.endsWith("token") ||
            decoded.endsWith("_key") ||
            decoded.endsWith("secret") ||
            decoded.endsWith("password")
    }
}
