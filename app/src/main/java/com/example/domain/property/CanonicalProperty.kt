package com.example.domain.property

/**
 * Canonical property model for the US market.
 *
 * It is intentionally a pure Kotlin model (no Android / Room types) so the deduplication and
 * normalization rules can be unit tested without a device. The Room side stores the same facts on
 * [com.example.data.local.entity.PropertyEntity]; [PropertyMapper] converts between the two.
 */
data class CanonicalAddress(
    val streetAddress: String,
    val unit: String = "",
    val city: String,
    val stateCode: String,
    val zip5: String,
    val zipPlus4: String = "",
    val county: String = "",
    val countyFips: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
) {
    /** Display form used in lists and AI prompt building. */
    val singleLine: String
        get() = buildString {
            append(streetAddress.trim())
            if (unit.isNotBlank()) append(" ").append(unit.trim())
            if (city.isNotBlank()) append(", ").append(city.trim())
            if (stateCode.isNotBlank()) append(", ").append(stateCode.trim())
            if (zip5.isNotBlank()) append(" ").append(zip5)
        }

    /** Address identity without the unit, used by the fuzzy matcher. */
    val streetLineKey: String
        get() = listOf(streetAddress, city, stateCode, zip5)
            .joinToString(" ")
            .trim()
            .lowercase()
}

/** Coarse US property classification (the fine grained MLS sub type is kept next to it). */
enum class UsPropertyType {
    SINGLE_FAMILY,
    MULTI_FAMILY,
    CONDO,
    TOWNHOUSE,
    MOBILE_HOME,
    MANUFACTURED,
    MIXED_USE,
    COMMERCIAL,
    LAND,
    OTHER;

    companion object {
        fun fromRaw(raw: String): UsPropertyType {
            val value = raw.lowercase()
            return when {
                value.contains("condo") || value.contains("co-op") || value.contains("coop") -> CONDO
                value.contains("town") || value.contains("row house") || value.contains("rowhouse") -> TOWNHOUSE
                value.contains("multi") || value.contains("duplex") || value.contains("triplex") ||
                    value.contains("fourplex") || value.contains("quadplex") || value.contains("apartment") -> MULTI_FAMILY
                value.contains("manufactured") -> MANUFACTURED
                value.contains("mobile") -> MOBILE_HOME
                value.contains("mixed") -> MIXED_USE
                value.contains("commercial") || value.contains("office") || value.contains("retail") ||
                    value.contains("industrial") -> COMMERCIAL
                value.contains("land") || value.contains("lot") || value.contains("acre") -> LAND
                value.contains("single") || value.contains("detached") || value.contains("house") -> SINGLE_FAMILY
                else -> OTHER
            }
        }
    }
}

/** Listing lifecycle in US MLS terms. */
enum class UsListingStatus {
    ACTIVE,
    COMING_SOON,
    PENDING,
    UNDER_CONTRACT,
    OFF_MARKET,
    FORECLOSURE,
    AUCTION,
    SOLD,
    WITHDRAWN,
    UNKNOWN;

    companion object {
        fun fromRaw(raw: String): UsListingStatus {
            val value = raw.lowercase()
            return when {
                value.contains("coming") -> COMING_SOON
                value.contains("pending") -> PENDING
                value.contains("contract") -> UNDER_CONTRACT
                value.contains("off") -> OFF_MARKET
                value.contains("foreclos") || value.contains("reo") || value.contains("bank") -> FORECLOSURE
                value.contains("auction") || value.contains("trustee") -> AUCTION
                value.contains("sold") || value.contains("closed") -> SOLD
                value.contains("withdraw") || value.contains("expired") -> WITHDRAWN
                value.contains("active") || value.contains("new") || value.contains("qualified") -> ACTIVE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Canonical property instance. `propertyId` is null until the record is persisted, which is what the
 * import pipeline uses to detect "new canonical property".
 */
data class CanonicalProperty(
    val propertyId: String?,
    val address: CanonicalAddress,
    val apn: String = "",
    val mlsNumber: String = "",
    val listingStatus: UsListingStatus = UsListingStatus.UNKNOWN,
    val propertyType: UsPropertyType = UsPropertyType.OTHER,
    val subType: String = "",
    val bedrooms: Int = 0,
    val fullBathrooms: Int = 0,
    val halfBathrooms: Int = 0,
    val livingAreaSqFt: Int = 0,
    val lotSizeSqFt: Int = 0,
    val yearBuilt: Int = 0,
    val stories: Int = 0,
    val garageSpaces: Int = 0,
    val hasPool: Boolean = false,
    val hoaMonthly: Double = 0.0,
    val listPrice: Double = 0.0,
    val estimatedValue: Double = 0.0,
    val occupied: Boolean = false,
    val sourceId: String? = null,
    val sourceExternalId: String = "",
    val canonicalKey: String? = null,
    val listingStatusUpdatedAt: Long = 0L,
    val lastVerifiedAt: Long = 0L
) {
    val totalBathrooms: Double
        get() = fullBathrooms + halfBathrooms * 0.5

    val pricePerSqFt: Double
        get() = if (livingAreaSqFt > 0 && listPrice > 0) listPrice / livingAreaSqFt else 0.0

    /** Deterministic identity, computed by [UsPropertyNormalizer]. */
    fun key(): String = UsPropertyNormalizer.canonicalKey(address)
}
