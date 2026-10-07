package com.example.domain.property

/**
 * Deduplication architecture.
 *
 * A property can be reported by several sources (MLS, wholesaler, county records, manual entry).
 * The pipeline resolves identity in strictly increasing order of fuzziness:
 *
 *  1. **EXACT_SOURCE_RECORD** - `(sourceId, externalId)` already exists in `property_provenance`.
 *     Handled by the importer before this engine runs; re-importing a record refreshes it.
 *  2. **CANONICAL_KEY** - normalized street + unit + city + state + ZIP5 match an existing row.
 *     This is the primary cross-source key and is enforced by a UNIQUE index once claimed.
 *  3. **PARCEL_APN** - the county Assessor Parcel Number matches (strongest non-address signal).
 *  4. **MLS_NUMBER** - the same listing id was seen before (catches address typos inside a feed).
 *  5. **FUZZY_ADDRESS** - house number + street tokens + ZIP + geo proximity + size/bedrooms.
 *     Only accepted above [PropertyDeduplicator.FUZZY_THRESHOLD]; unit mismatches are rejected
 *     outright so that two condos in the same building never collapse into one property.
 *
 * Rows migrated from v2 have `canonicalKey = NULL` until the reconcile pass claims them, so a match
 * against such a row returns [DedupDecision.claimCanonicalKey] = true: the importer then writes the
 * canonical identity onto the legacy row instead of creating a duplicate.
 */
enum class DedupStrategy {
    EXACT_SOURCE_RECORD,
    CANONICAL_KEY,
    PARCEL_APN,
    MLS_NUMBER,
    FUZZY_ADDRESS,
    NONE
}

/** What the importer must do with the incoming record. */
enum class DedupAction {
    INSERT_NEW,
    UPDATE_EXISTING,
    MERGE_INTO_EXISTING
}

data class DedupDecision(
    val strategy: DedupStrategy,
    val action: DedupAction,
    val matchedPropertyId: String?,
    val confidence: Double,
    /** True when the matched row has no canonical key yet and must be claimed. */
    val claimCanonicalKey: Boolean,
    val reason: String
) {
    val matched: Boolean get() = matchedPropertyId != null

    companion object {
        fun insertNew(reason: String) = DedupDecision(
            strategy = DedupStrategy.NONE,
            action = DedupAction.INSERT_NEW,
            matchedPropertyId = null,
            confidence = 0.0,
            claimCanonicalKey = false,
            reason = reason
        )
    }
}

/** Minimal identity payload the dedup engine needs; built from the entity by `PropertyMapper`. */
data class DedupInput(
    val propertyId: String?,
    val canonicalKey: String?,
    val normalizedAddress: String,
    val unit: String = "",
    val city: String = "",
    val stateCode: String = "",
    val zip5: String = "",
    val apn: String = "",
    val mlsNumber: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val bedrooms: Int = 0,
    val livingAreaSqFt: Int = 0,
    val propertyType: UsPropertyType = UsPropertyType.OTHER
)

object PropertyDeduplicator {

    /** Minimum fuzzy score accepted as "same property". */
    const val FUZZY_THRESHOLD = 0.72

    fun decide(incoming: DedupInput, candidates: List<DedupInput>): DedupDecision {
        val key = incoming.canonicalKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            val byKey = candidates.firstOrNull { it.canonicalKey == key }
            if (byKey?.propertyId != null) {
                return DedupDecision(
                    strategy = DedupStrategy.CANONICAL_KEY,
                    action = DedupAction.UPDATE_EXISTING,
                    matchedPropertyId = byKey.propertyId,
                    confidence = 1.0,
                    claimCanonicalKey = false,
                    reason = "canonical key match"
                )
            }
        }

        val apn = incoming.apn.takeIf { it.isNotBlank() }
        if (apn != null) {
            val byApn = candidates.firstOrNull { it.apn.isNotBlank() && it.apn == apn }
            if (byApn?.propertyId != null) {
                return DedupDecision(
                    strategy = DedupStrategy.PARCEL_APN,
                    action = DedupAction.MERGE_INTO_EXISTING,
                    matchedPropertyId = byApn.propertyId,
                    confidence = 0.95,
                    claimCanonicalKey = byApn.canonicalKey.isNullOrBlank(),
                    reason = "APN $apn match"
                )
            }
        }

        val mls = incoming.mlsNumber.takeIf { it.isNotBlank() }
        if (mls != null) {
            val byMls = candidates.firstOrNull { it.mlsNumber.isNotBlank() && it.mlsNumber == mls }
            if (byMls?.propertyId != null) {
                return DedupDecision(
                    strategy = DedupStrategy.MLS_NUMBER,
                    action = DedupAction.MERGE_INTO_EXISTING,
                    matchedPropertyId = byMls.propertyId,
                    confidence = 0.9,
                    claimCanonicalKey = byMls.canonicalKey.isNullOrBlank(),
                    reason = "MLS $mls match"
                )
            }
        }

        val scored = candidates
            .filter { it.propertyId != null }
            .map { it to similarity(incoming, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull()
        if (best != null && best.second >= FUZZY_THRESHOLD) {
            return DedupDecision(
                strategy = DedupStrategy.FUZZY_ADDRESS,
                action = DedupAction.MERGE_INTO_EXISTING,
                matchedPropertyId = best.first.propertyId,
                confidence = best.second,
                claimCanonicalKey = best.first.canonicalKey.isNullOrBlank(),
                reason = "fuzzy address match (${"%.2f".format(best.second)})"
            )
        }

        return DedupDecision.insertNew(
            reason = scored.firstOrNull()?.let { "best fuzzy score ${"%.2f".format(it.second)} below threshold" }
                ?: "no candidate matched"
        )
    }

    /**
     * Weighted similarity in 0..1: address identity 0.50, ZIP/city 0.15, geo 0.15, living area 0.10,
     * bedrooms 0.05, property type 0.05. Returns 0.0 for unit or classification mismatches.
     */
    fun similarity(a: DedupInput, b: DedupInput): Double {
        if (a.unit.isNotBlank() && b.unit.isNotBlank() && a.unit != b.unit) return 0.0

        val numberA = houseNumber(a.normalizedAddress)
        val numberB = houseNumber(b.normalizedAddress)
        val tokensA = streetTokens(a.normalizedAddress)
        val tokensB = streetTokens(b.normalizedAddress)
        val tokenScore = jaccard(tokensA, tokensB)
        var score = if (numberA.isNotEmpty() && numberA == numberB) {
            0.5 + 0.5 * tokenScore
        } else {
            tokenScore * 0.5
        }
        score *= 0.50

        score += when {
            a.zip5.isNotBlank() && a.zip5 == b.zip5 -> 0.15
            a.city.isNotBlank() && a.city == b.city && a.stateCode == b.stateCode -> 0.05
            else -> 0.0
        }

        val distance = UsPropertyNormalizer.distanceMiles(a.latitude, a.longitude, b.latitude, b.longitude)
        score += when {
            distance <= 0.05 -> 0.15
            distance <= 0.20 -> 0.08
            else -> 0.0
        }

        if (a.livingAreaSqFt > 0 && b.livingAreaSqFt > 0) {
            val ratio = kotlin.math.abs(a.livingAreaSqFt - b.livingAreaSqFt).toDouble() /
                maxOf(a.livingAreaSqFt, b.livingAreaSqFt).toDouble()
            score += 0.10 * (1.0 - ratio).coerceIn(0.0, 1.0)
        }
        if (a.bedrooms > 0 && b.bedrooms > 0 && a.bedrooms == b.bedrooms) score += 0.05
        if (a.propertyType != UsPropertyType.OTHER && a.propertyType == b.propertyType) score += 0.05
        return score.coerceIn(0.0, 1.0)
    }

    private fun houseNumber(value: String): String =
        value.trim().substringBefore(' ').takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }.orEmpty()

    private fun streetTokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.all { ch -> ch.isDigit() } }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.count { it in b }.toDouble()
        val union = (a + b).size.toDouble()
        return intersection / union
    }
}

/**
 * Field level merge policy used when two sources describe the same property.
 *
 * "Volatile" fields (price, status, MLS id, valuation) follow the more authoritative source: the one
 * with the lower `property_sources.priority` value. "Identity" fields (APN, structure facts) are
 * sticky - the first non blank value wins and later sources only fill gaps, so a sloppy feed cannot
 * silently rewrite the legal identity of a property.
 */
object PropertyMergePolicy {

    data class MergeOutcome(
        val merged: CanonicalProperty,
        val conflictingFields: List<String>
    )

    fun merge(
        existing: CanonicalProperty,
        existingSourcePriority: Int,
        incoming: CanonicalProperty,
        incomingSourcePriority: Int
    ): MergeOutcome {
        val incomingWins = incomingSourcePriority <= existingSourcePriority
        val conflicts = mutableListOf<String>()

        fun sticky(existingValue: String, incomingValue: String, field: String): String {
            if (existingValue.isNotBlank() && incomingValue.isNotBlank() && existingValue != incomingValue) {
                conflicts += field
            }
            return existingValue.ifBlank { incomingValue }
        }

        fun volatile(existingValue: String, incomingValue: String, field: String): String {
            if (existingValue.isNotBlank() && incomingValue.isNotBlank() && existingValue != incomingValue) {
                conflicts += field
            }
            return if (incomingWins) incomingValue.ifBlank { existingValue } else existingValue.ifBlank { incomingValue }
        }

        fun <T> volatileValue(existingValue: T, incomingValue: T, empty: T, field: String): T {
            if (existingValue != empty && incomingValue != empty && existingValue != incomingValue) {
                conflicts += field
            }
            return if (incomingWins) {
                if (incomingValue != empty) incomingValue else existingValue
            } else {
                if (existingValue != empty) existingValue else incomingValue
            }
        }

        val merged = existing.copy(
            address = existing.address.copy(
                streetAddress = sticky(existing.address.streetAddress, incoming.address.streetAddress, "streetAddress"),
                unit = sticky(existing.address.unit, incoming.address.unit, "unit"),
                city = sticky(existing.address.city, incoming.address.city, "city"),
                stateCode = sticky(existing.address.stateCode, incoming.address.stateCode, "stateCode"),
                zip5 = sticky(existing.address.zip5, incoming.address.zip5, "zip5"),
                zipPlus4 = sticky(existing.address.zipPlus4, incoming.address.zipPlus4, "zipPlus4"),
                county = sticky(existing.address.county, incoming.address.county, "county"),
                countyFips = sticky(existing.address.countyFips, incoming.address.countyFips, "countyFips"),
                latitude = if (existing.address.latitude != 0.0) existing.address.latitude else incoming.address.latitude,
                longitude = if (existing.address.longitude != 0.0) existing.address.longitude else incoming.address.longitude
            ),
            apn = sticky(existing.apn, incoming.apn, "apn"),
            subType = sticky(existing.subType, incoming.subType, "subType"),
            bedrooms = volatileValue(existing.bedrooms, incoming.bedrooms, 0, "bedrooms"),
            fullBathrooms = volatileValue(existing.fullBathrooms, incoming.fullBathrooms, 0, "fullBathrooms"),
            halfBathrooms = volatileValue(existing.halfBathrooms, incoming.halfBathrooms, 0, "halfBathrooms"),
            livingAreaSqFt = volatileValue(existing.livingAreaSqFt, incoming.livingAreaSqFt, 0, "livingAreaSqFt"),
            lotSizeSqFt = volatileValue(existing.lotSizeSqFt, incoming.lotSizeSqFt, 0, "lotSizeSqFt"),
            yearBuilt = volatileValue(existing.yearBuilt, incoming.yearBuilt, 0, "yearBuilt"),
            stories = volatileValue(existing.stories, incoming.stories, 0, "stories"),
            garageSpaces = volatileValue(existing.garageSpaces, incoming.garageSpaces, 0, "garageSpaces"),
            hasPool = existing.hasPool || incoming.hasPool,
            hoaMonthly = volatileValue(existing.hoaMonthly, incoming.hoaMonthly, 0.0, "hoaMonthly"),
            listPrice = volatileValue(existing.listPrice, incoming.listPrice, 0.0, "listPrice"),
            estimatedValue = volatileValue(existing.estimatedValue, incoming.estimatedValue, 0.0, "estimatedValue"),
            mlsNumber = volatile(existing.mlsNumber, incoming.mlsNumber, "mlsNumber"),
            listingStatus = if (incomingWins && incoming.listingStatus != UsListingStatus.UNKNOWN) {
                incoming.listingStatus
            } else {
                existing.listingStatus
            },
            sourceId = existing.sourceId ?: incoming.sourceId,
            canonicalKey = existing.canonicalKey ?: incoming.canonicalKey,
            lastVerifiedAt = maxOf(existing.lastVerifiedAt, incoming.lastVerifiedAt),
            listingStatusUpdatedAt = maxOf(existing.listingStatusUpdatedAt, incoming.listingStatusUpdatedAt)
        )
        return MergeOutcome(merged, conflicts.distinct())
    }
}
