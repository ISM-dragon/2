package com.example.domain.propertyurl.job

import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.url.ResolvedPropertyUrl
import java.security.MessageDigest

/**
 * Idempotency keys.
 *
 * Two levels are used on purpose:
 *  - **listing level** (`source:externalId`): the same house imported twice from the same portal is a
 *    duplicate even if the URL carries different tracking parameters;
 *  - **document level** (normalized URL seed): fallback for unknown sources without a listing id.
 */
object IdempotencyKeys {

    fun forResolved(resolved: ResolvedPropertyUrl): String = resolved.idempotencyKey

    fun forListing(sourceId: String, externalListingId: String): String =
        "${sourceId.lowercase()}:${externalListingId.trim()}"

    fun forDocument(normalizedUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizedUrl.toByteArray(Charsets.UTF_8))
        return "url:" + digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }

    /** Property level key: identical address in the same market is the same physical asset. */
    fun forCanonicalProperty(property: CanonicalProperty, marketGranularity: String = "zip"): String {
        val city = property.city?.lowercase().orEmpty()
        val zip = property.postalCode?.lowercase().orEmpty()
        val state = property.state?.lowercase().orEmpty()
        val material = when (marketGranularity) {
            "city" -> listOf(property.addressLine1, city, state).joinToString("|")
            else -> listOf(property.addressLine1, zip, state).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.lowercase().replace(Regex("[^a-z0-9|]"), "").toByteArray(Charsets.UTF_8))
        return "prop:" + digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }
}

/** How long a previously imported listing may be reused before it is refreshed. */
data class IdempotencyPolicy(
    val reuseWindowMillis: Long = 6 * 60 * 60 * 1000,
    /** Reuse records that were only partially imported (`PARTIAL_SUCCESS`). */
    val reusePartialResults: Boolean = true,
    /** Reuse previously *failed* jobs to short-circuit (avoids hammering a blocked source). */
    val reuseFailedJobsWithinMillis: Long = 0,
    /** When true, a duplicate still produces a fresh job row (audit) but no fetch/parse. */
    val recordSuppressedDuplicates: Boolean = true
) {

    fun isReusable(existing: PropertyImportJob, nowEpochMillis: Long): Boolean {
        if (existing.isTerminal.not()) return true // an in-flight job for the same key is reuse-worthy
        val age = nowEpochMillis - existing.updatedAtEpochMillis
        return when (existing.state) {
            PropertyImportJobState.SUCCEEDED -> age <= reuseWindowMillis
            PropertyImportJobState.PARTIAL_SUCCESS -> reusePartialResults && age <= reuseWindowMillis
            PropertyImportJobState.FETCH_FAILED, PropertyImportJobState.PARSE_FAILED ->
                reuseFailedJobsWithinMillis > 0 && age <= reuseFailedJobsWithinMillis
            PropertyImportJobState.DUPLICATE_SUPPRESSED -> true
            else -> false
        }
    }

    fun findReusable(
        existing: PropertyImportJob?,
        idempotencyKey: String,
        nowEpochMillis: Long,
        forceRefresh: Boolean = false
    ): PropertyImportJob? {
        if (forceRefresh) return null
        val job = existing ?: return null
        if (job.idempotencyKey != idempotencyKey) return null
        return job.takeIf { isReusable(it, nowEpochMillis) }
    }
}
