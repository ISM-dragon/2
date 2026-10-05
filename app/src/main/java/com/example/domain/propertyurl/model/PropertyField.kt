package com.example.domain.propertyurl.model

/**
 * Canonical, source-agnostic field set for a property listing.
 *
 * Adapters emit facts for these fields (raw or already typed); the canonical mapper converts,
 * validates and merges them into a [CanonicalProperty] where every value carries [Provenance].
 */
enum class PropertyField(val criticality: FieldCriticality) {

    // --- Identity ---------------------------------------------------------------
    ADDRESS_LINE_1(FieldCriticality.IDENTITY),
    ADDRESS_LINE_2(FieldCriticality.OPTIONAL),
    UNIT_NUMBER(FieldCriticality.OPTIONAL),
    CITY(FieldCriticality.IDENTITY),
    STATE(FieldCriticality.IDENTITY),
    POSTAL_CODE(FieldCriticality.IDENTITY),
    COUNTY(FieldCriticality.OPTIONAL),
    COUNTRY_CODE(FieldCriticality.OPTIONAL),

    // --- Geography --------------------------------------------------------------
    LATITUDE(FieldCriticality.LOCATION),
    LONGITUDE(FieldCriticality.LOCATION),

    // --- Pricing ----------------------------------------------------------------
    PRICE_AMOUNT(FieldCriticality.ESSENTIAL),
    PRICE_CURRENCY(FieldCriticality.OPTIONAL),
    PRICE_PER_SQFT(FieldCriticality.ENRICHMENT),
    HOA_FEE_MONTHLY(FieldCriticality.ENRICHMENT),
    ANNUAL_TAX_AMOUNT(FieldCriticality.ENRICHMENT),

    // --- Physical attributes ----------------------------------------------------
    PROPERTY_TYPE(FieldCriticality.OPTIONAL),
    BEDROOMS(FieldCriticality.ESSENTIAL),
    BATHROOMS(FieldCriticality.ESSENTIAL),
    LIVING_AREA_SQFT(FieldCriticality.ESSENTIAL),
    LOT_SIZE_SQFT(FieldCriticality.ENRICHMENT),
    YEAR_BUILT(FieldCriticality.OPTIONAL),
    STORIES(FieldCriticality.ENRICHMENT),
    GARAGE_SPACES(FieldCriticality.ENRICHMENT),

    // --- Listing metadata -------------------------------------------------------
    LISTING_TITLE(FieldCriticality.OPTIONAL),
    DESCRIPTION(FieldCriticality.OPTIONAL),
    LISTING_STATUS(FieldCriticality.OPTIONAL),
    MLS_NUMBER(FieldCriticality.ENRICHMENT),
    LISTED_AT(FieldCriticality.ENRICHMENT),
    DAYS_ON_MARKET(FieldCriticality.ENRICHMENT),
    IMAGE_URLS(FieldCriticality.ENRICHMENT),
    AGENT_NAME(FieldCriticality.ENRICHMENT),
    BROKER_NAME(FieldCriticality.ENRICHMENT),

    // --- Source-issued identifiers ---------------------------------------------
    SOURCE_LISTING_ID(FieldCriticality.ENRICHMENT);

    val isCritical: Boolean
        get() = criticality == FieldCriticality.IDENTITY || criticality == FieldCriticality.ESSENTIAL

    val isMultiValued: Boolean
        get() = this == IMAGE_URLS
}

/** How important a field is when deciding between full success, partial success and failure. */
enum class FieldCriticality {
    /** Address components: without them the record cannot be attached to a property. */
    IDENTITY,
    /** Deal-breaking numbers (price, beds/baths/sqft, external id). */
    ESSENTIAL,
    /** Coordinates. */
    LOCATION,
    /** Nice-to-have analytics inputs. */
    ENRICHMENT,
    /** Everything else. */
    OPTIONAL
}

/** Strongly typed canonical value. */
sealed interface FieldValue {

    data class Text(val value: String) : FieldValue

    data class Decimal(val value: Double) : FieldValue

    data class WholeNumber(val value: Int) : FieldValue

    data class Flag(val value: Boolean) : FieldValue

    data class TextList(val value: List<String>) : FieldValue

    /** Absolute instant in epoch milliseconds (UTC). */
    data class Timestamp(val epochMillis: Long) : FieldValue

    data class GeoPoint(val latitude: Double, val longitude: Double) : FieldValue

    fun asText(): String? = when (this) {
        is Text -> value
        is Decimal -> trimTrailingZero(value)
        is WholeNumber -> value.toString()
        is Flag -> value.toString()
        is TextList -> value.joinToString(", ")
        is Timestamp -> epochMillis.toString()
        is GeoPoint -> "$latitude,$longitude"
    }

    fun asDecimal(): Double? = when (this) {
        is Decimal -> value
        is WholeNumber -> value.toDouble()
        is Text -> value.trim().toDoubleOrNull()
        else -> null
    }

    fun asWholeNumber(): Int? = when (this) {
        is WholeNumber -> value
        is Decimal -> if (value == Math.floor(value)) value.toInt() else null
        is Text -> value.trim().toIntOrNull()
        else -> null
    }

    fun asFlag(): Boolean? = when (this) {
        is Flag -> value
        is Text -> value.trim().lowercase().let { if (it == "true" || it == "yes" || it == "1") true else if (it == "false" || it == "no" || it == "0") false else null }
        else -> null
    }

    fun asTextList(): List<String> = when (this) {
        is TextList -> value
        is Text -> listOf(value)
        else -> emptyList()
    }

    fun asTimestamp(): Long? = when (this) {
        is Timestamp -> epochMillis
        else -> null
    }

    private fun trimTrailingZero(d: Double): String =
        if (d == Math.floor(d) && d.isFinite()) d.toLong().toString() else d.toString()
}

/** A canonical value plus the provenance describing exactly where it came from. */
data class SourcedField(
    val value: FieldValue,
    val provenance: Provenance
) {
    /** Display helper honouring the layer's "never fabricate" rule: returns null for empty text. */
    fun display(): String? = value.asText()?.takeIf { it.isNotBlank() }
}

/** Field-level quality signals collected during import; surfaced to UI/telemetry, never thrown. */
data class ImportWarning(
    val code: WarningCode,
    val message: String,
    val field: PropertyField? = null,
    val sourceId: String? = null,
    val detail: String? = null
) {
    enum class WarningCode {
        MISSING_CRITICAL_FIELD,
        MISSING_RECOMMENDED_FIELD,
        FIELD_CONFLICT,
        VALUE_REJECTED_BY_GUARD,
        VALUE_COERCED,
        PARTIAL_PARSE,
        FALLBACK_PARSER_USED,
        PARSER_SCHEMA_DRIFT,
        ANTI_BOT_PAGE_DETECTED,
        CONSENT_WALL_DETECTED,
        PAYWALL_DETECTED,
        SOURCE_NOT_IMPLEMENTED,
        STALE_CACHE_REUSED,
        TRACKING_PARAMETERS_STRIPPED,
        DUPLICATE_SUPPRESSED,
        DERIVED_FIELD,
        UNSUPPORTED_DOCUMENT_TYPE,
        DRY_RUN_NO_FETCH
    }
}
