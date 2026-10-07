package com.example.urlintelligence.model

import com.example.urlintelligence.provenance.ProvenanceMap
import com.example.urlintelligence.provenance.VerificationSummary

/** Physical type of the asset, normalized across sources. */
enum class CanonicalPropertyType {
    SINGLE_FAMILY,
    MULTI_FAMILY,
    CONDO,
    TOWNHOUSE,
    APARTMENT,
    LAND,
    MANUFACTURED,
    COMMERCIAL,
    UNKNOWN
}

/** Listing lifecycle, normalized across sources. */
enum class CanonicalListingStatus {
    FOR_SALE,
    FOR_RENT,
    PENDING,
    SOLD,
    AUCTION,
    OFF_MARKET,
    UNKNOWN
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

data class CanonicalAddress(
    val line1: String? = null,
    val unit: String? = null,
    val city: String? = null,
    val stateOrProvince: String? = null,
    val postalCode: String? = null,
    val countryCode: String = "US",
    val geo: GeoPoint? = null
) {
    /** One-line, human-readable address used for titles and dedup heuristics. */
    val formatted: String
        get() = buildList {
            line1?.let { value -> add(value) }
            unit?.let { value -> add("Unit $value") }
            city?.let { value -> add(value) }
            stateOrProvince?.let { value -> add(value) }
            postalCode?.let { value -> add(value) }
        }.joinToString(", ")

    /** Stable identity key used to detect the same physical property across sources. */
    val locationKey: String?
        get() {
            val street = line1?.trim()?.lowercase() ?: return null
            val zip = postalCode?.trim()?.lowercase().orEmpty()
            if (street.isBlank() || zip.isBlank()) return null
            return "$street|$zip"
        }
}

/**
 * Field-level quality report attached to every canonical property.
 *
 * Drives partial-success handling: a property with [missingRequired] fields is still
 * returned to the caller, but flagged, so downstream (qualification/financial engines)
 * can decide whether the data is good enough.
 */
data class CompletenessReport(
    val present: Set<PropertyField>,
    val missingRequired: Set<PropertyField>,
    val missingOptional: Set<PropertyField>,
    val score: Double
) {
    val isComplete: Boolean
        get() = missingRequired.isEmpty()

    companion object {
        const val REQUIRED_WEIGHT: Double = 0.7
        const val OPTIONAL_WEIGHT: Double = 0.3

        fun compute(
            present: Set<PropertyField>,
            required: Set<PropertyField> = PropertyField.REQUIRED_FIELDS,
            optional: Set<PropertyField> = PropertyField.OPTIONAL_FIELDS
        ): CompletenessReport {
            val missingRequired = required - present
            val missingOptional = optional - present
            val requiredScore =
                if (required.isEmpty()) 1.0
                else (required.size - missingRequired.size).toDouble() / required.size.toDouble()
            val optionalScore =
                if (optional.isEmpty()) 1.0
                else (optional.size - missingOptional.size).toDouble() / optional.size.toDouble()
            val score =
                (requiredScore * REQUIRED_WEIGHT + optionalScore * OPTIONAL_WEIGHT).coerceIn(0.0, 1.0)
            return CompletenessReport(present, missingRequired, missingOptional, score)
        }
    }
}

/**
 * The single normalized property shape every source adapter must produce.
 *
 * All money values are USD doubles (the domain here is US residential real estate);
 * every non-derived field has an entry in [provenance].
 */
data class CanonicalProperty(
    val canonicalId: String,
    val sourceId: String,
    val sourcePropertyId: String?,
    val canonicalUrl: String,
    val resolvedUrl: String,
    val address: CanonicalAddress,
    val propertyType: CanonicalPropertyType = CanonicalPropertyType.UNKNOWN,
    val listingStatus: CanonicalListingStatus = CanonicalListingStatus.UNKNOWN,
    val listPriceUsd: Double? = null,
    val bedrooms: Double? = null,
    val bathrooms: Double? = null,
    val livingAreaSqFt: Int? = null,
    val lotSizeSqFt: Int? = null,
    val yearBuilt: Int? = null,
    val description: String? = null,
    val primaryImageUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val estimatedMonthlyRentUsd: Double? = null,
    val annualPropertyTaxUsd: Double? = null,
    val hoaMonthlyFeeUsd: Double? = null,
    val daysOnMarket: Int? = null,
    val listedAtEpochMillis: Long? = null,
    val mlsId: String? = null,
    val parcelId: String? = null,
    val fetchedAtEpochMillis: Long = 0L,
    val completeness: CompletenessReport,
    val provenance: ProvenanceMap,
    val warnings: List<String> = emptyList(),
    /**
     * How well this record is verified: parser-verified (a versioned parser matched the
     * document) vs live-fetch-verified (the document was fetched from the provider now).
     * Never claims more than was actually performed.
     */
    val verification: VerificationSummary = VerificationSummary.NONE,
    /** Digest of the extracted content, used for property-level idempotency. */
    val contentDigest: String = "",
    /** `parserId@version` values that produced fields on this record. */
    val parserVersions: List<String> = emptyList()
) {

    /** True only when a live fetch against the provider produced data for this record. */
    val isLiveVerified: Boolean
        get() = verification.liveVerified

    /** True when a versioned parser matched the source document. */
    val isParserVerified: Boolean
        get() = verification.parserVerified

    val pricePerSqFtUsd: Double?
        get() {
            val price = listPriceUsd ?: return null
            val area = livingAreaSqFt ?: return null
            if (price <= 0.0 || area <= 0) return null
            return price / area.toDouble()
        }

    val hasCoreIdentity: Boolean
        get() = sourcePropertyId != null || address.locationKey != null

    fun field(field: PropertyField): FieldValue {
        return when (field) {
            PropertyField.ADDRESS_LINE1 -> FieldValue.Str(address.line1)
            PropertyField.UNIT -> FieldValue.Str(address.unit)
            PropertyField.CITY -> FieldValue.Str(address.city)
            PropertyField.STATE -> FieldValue.Str(address.stateOrProvince)
            PropertyField.POSTAL_CODE -> FieldValue.Str(address.postalCode)
            PropertyField.COUNTRY -> FieldValue.Str(address.countryCode)
            PropertyField.LATITUDE -> FieldValue.Num(address.geo?.latitude)
            PropertyField.LONGITUDE -> FieldValue.Num(address.geo?.longitude)
            PropertyField.PROPERTY_TYPE -> FieldValue.Str(propertyType.name)
            PropertyField.LISTING_STATUS -> FieldValue.Str(listingStatus.name)
            PropertyField.LIST_PRICE -> FieldValue.Num(listPriceUsd)
            PropertyField.BEDROOMS -> FieldValue.Num(bedrooms)
            PropertyField.BATHROOMS -> FieldValue.Num(bathrooms)
            PropertyField.LIVING_AREA_SQFT -> FieldValue.Num(livingAreaSqFt?.toDouble())
            PropertyField.LOT_SIZE_SQFT -> FieldValue.Num(lotSizeSqFt?.toDouble())
            PropertyField.YEAR_BUILT -> FieldValue.Num(yearBuilt?.toDouble())
            PropertyField.DESCRIPTION -> FieldValue.Str(description)
            PropertyField.PRIMARY_IMAGE_URL -> FieldValue.Str(primaryImageUrl)
            PropertyField.IMAGE_URLS -> FieldValue.Str(imageUrls.joinToString(",").takeIf { it.isNotEmpty() })
            PropertyField.ESTIMATED_MONTHLY_RENT -> FieldValue.Num(estimatedMonthlyRentUsd)
            PropertyField.ANNUAL_TAX_AMOUNT -> FieldValue.Num(annualPropertyTaxUsd)
            PropertyField.HOA_MONTHLY_FEE -> FieldValue.Num(hoaMonthlyFeeUsd)
            PropertyField.DAYS_ON_MARKET -> FieldValue.Num(daysOnMarket?.toDouble())
            PropertyField.LISTED_AT_EPOCH_MILLIS -> FieldValue.Num(listedAtEpochMillis?.toDouble())
            PropertyField.MLS_ID -> FieldValue.Str(mlsId)
            PropertyField.PARCEL_ID -> FieldValue.Str(parcelId)
        }
    }

    sealed class FieldValue {
        data class Str(val value: String?) : FieldValue()
        data class Num(val value: Double?) : FieldValue()

        fun isPresent(): Boolean = when (this) {
            is Str -> !value.isNullOrBlank()
            is Num -> value != null
        }
    }
}
