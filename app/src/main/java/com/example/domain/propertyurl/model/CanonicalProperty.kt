package com.example.domain.propertyurl.model

import java.security.MessageDigest

/**
 * Source-independent property record produced by the Property URL Intelligence layer.
 *
 * Every value in [fields] carries its own [Provenance]; [warnings] records everything that was
 * degraded, coerced or dropped on the way (partial imports are first-class, never hidden).
 */
data class CanonicalProperty(
    val canonicalId: String,
    val primarySourceId: String,
    val sourceListingId: String?,
    val sourceUrl: String,
    val fields: Map<PropertyField, SourcedField>,
    val completeness: CompletenessReport,
    val warnings: List<ImportWarning>,
    val builtAtEpochMillis: Long
) {

    fun sourced(field: PropertyField): SourcedField? = fields[field]

    fun text(field: PropertyField): String? = fields[field]?.value?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    fun decimal(field: PropertyField): Double? = fields[field]?.value?.asDecimal()

    fun wholeNumber(field: PropertyField): Int? = fields[field]?.value?.asWholeNumber()

    fun flag(field: PropertyField): Boolean? = fields[field]?.value?.asFlag()

    fun textList(field: PropertyField): List<String> = fields[field]?.value?.asTextList().orEmpty()

    fun timestamp(field: PropertyField): Long? = fields[field]?.value?.asTimestamp()

    fun provenanceOf(field: PropertyField): Provenance? = fields[field]?.provenance

    // --- Typed conveniences used by UI/repository mapping --------------------------------

    val addressLine1: String? get() = text(PropertyField.ADDRESS_LINE_1)
    val city: String? get() = text(PropertyField.CITY)
    val state: String? get() = text(PropertyField.STATE)
    val postalCode: String? get() = text(PropertyField.POSTAL_CODE)
    val price: Double? get() = decimal(PropertyField.PRICE_AMOUNT)
    val bedrooms: Int? get() = wholeNumber(PropertyField.BEDROOMS)
    val bathrooms: Double? get() = decimal(PropertyField.BATHROOMS)
    val livingAreaSqFt: Int? get() = wholeNumber(PropertyField.LIVING_AREA_SQFT)
    val imageUrls: List<String> get() = textList(PropertyField.IMAGE_URLS)

    fun formattedAddress(): String? {
        val parts = listOfNotNull(
            addressLine1,
            city?.let { c -> state?.let { s -> "$c, $s" } ?: c },
            postalCode
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** Provenance of every field, for audit surfaces ("where did this number come from?"). */
    fun provenanceMap(): Map<PropertyField, Provenance> = fields.mapValues { it.value.provenance }

    fun missingCriticalFields(): Set<PropertyField> = completeness.missingCritical
}

/** Coverage of the canonical field set; drives success / partial-success classification. */
data class CompletenessReport(
    val present: Set<PropertyField>,
    val missing: Set<PropertyField>,
    val missingCritical: Set<PropertyField>,
    val missingRecommended: Set<PropertyField>,
    /** 0..1 weighted coverage (critical fields weigh more). */
    val score: Double
) {
    val isComplete: Boolean get() = missingCritical.isEmpty() && missing.isEmpty()

    /** True when identity + essential fields are all present. */
    val isUsable: Boolean get() = missingCritical.isEmpty()

    companion object {
        fun of(fields: Map<PropertyField, SourcedField>): CompletenessReport {
            val present = fields.keys.toSet()
            val missing = PropertyField.entries.filterNot { present.contains(it) }.toSet()
            val missingCritical = missing.filter { it.isCritical }.toSet()
            val missingRecommended = missing.filter { it.criticality == FieldCriticality.ENRICHMENT }.toSet()

            val totalWeight = PropertyField.entries.sumOf { weight(it) }
            val presentWeight = PropertyField.entries.filter { present.contains(it) }.sumOf { weight(it) }
            val score = if (totalWeight == 0.0) 0.0 else presentWeight / totalWeight

            return CompletenessReport(
                present = present,
                missing = missing,
                missingCritical = missingCritical,
                missingRecommended = missingRecommended,
                score = (Math.round(score * 1000.0) / 1000.0)
            )
        }

        private fun weight(field: PropertyField): Double = when (field.criticality) {
            FieldCriticality.IDENTITY -> 3.0
            FieldCriticality.ESSENTIAL -> 2.5
            FieldCriticality.LOCATION -> 1.0
            FieldCriticality.ENRICHMENT -> 0.6
            FieldCriticality.OPTIONAL -> 0.4
        }
    }
}

/**
 * Deterministic identity for a property so the same house imported from two portals collapses onto
 * one canonical record. Address components are normalized aggressively (case, punctuation, street
 * suffix abbreviations) before hashing.
 */
object CanonicalIds {

    fun fingerprint(
        addressLine1: String?,
        city: String?,
        state: String?,
        postalCode: String?
    ): String {
        val normalized = listOf(addressLine1.orEmpty(), city.orEmpty(), state.orEmpty(), postalCode.orEmpty())
            .joinToString("|") { normalizePart(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(24)
    }

    fun canonicalId(
        addressLine1: String?,
        city: String?,
        state: String?,
        postalCode: String?
    ): String = "cp-" + fingerprint(addressLine1, city, state, postalCode)

    /**
     * Fallback identity when a page carries no usable address (e.g. a listing that only exposed a
     * price). Keeps the record stable across repeated imports of the same URL.
     */
    fun canonicalIdForUrl(sourceId: String, identitySeed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sourceId|$identitySeed".toByteArray(Charsets.UTF_8))
        return "cp-url-" + digest.joinToString("") { byte -> "%02x".format(byte) }.take(20)
    }

    private val STREET_SUFFIX_ALIASES = mapOf(
        "street" to "st", "avenue" to "ave", "boulevard" to "blvd", "drive" to "dr",
        "road" to "rd", "lane" to "ln", "court" to "ct", "circle" to "cir", "place" to "pl",
        "terrace" to "ter", "parkway" to "pkwy", "highway" to "hwy", "trail" to "trl",
        "north" to "n", "south" to "s", "east" to "e", "west" to "w",
        "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw"
    )

    private fun normalizePart(part: String): String {
        val lowered = part.trim().lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (lowered.isEmpty()) return ""
        return lowered.split(' ').joinToString(" ") { token ->
            STREET_SUFFIX_ALIASES[token] ?: token
        }
    }
}
