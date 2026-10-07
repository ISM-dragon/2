package com.example.domain.identity

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun IdentityMatchMethod.displayName(): String = when (this) {
    IdentityMatchMethod.APN -> "APN/parcel ID"
    IdentityMatchMethod.NORMALIZED_ADDRESS -> "normalized address"
    IdentityMatchMethod.COORDINATES -> "coordinates"
    IdentityMatchMethod.PROVIDER_LISTING_ID -> "same-provider listing ID"
    IdentityMatchMethod.FUZZY_ADDRESS -> "fuzzy address"
    IdentityMatchMethod.NONE -> "no identity signal"
    IdentityMatchMethod.CONFLICT -> "conflicting identity signals"
}

/** Tunable thresholds for approximate identity signals. */
data class DeduplicationConfig(
    /** Coordinates inside this radius can identify a property automatically, absent conflicts. */
    val coordinateMatchRadiusMeters: Double = 20.0,
    /** Coordinates inside this larger radius are a review-only possible match. */
    val coordinatePossibleMatchRadiusMeters: Double = 150.0,
    /** A larger coordinate discrepancy alongside an exact signal is reported as a conflict. */
    val conflictingCoordinateRadiusMeters: Double = 1_000.0,
    /** Fuzzy addresses at or above this similarity are review-only candidates. */
    val fuzzyAddressThreshold: Double = 0.84
) {
    init {
        require(coordinateMatchRadiusMeters.isFinite() && coordinateMatchRadiusMeters >= 0.0)
        require(
            coordinatePossibleMatchRadiusMeters.isFinite() &&
                coordinatePossibleMatchRadiusMeters >= coordinateMatchRadiusMeters
        )
        require(
            conflictingCoordinateRadiusMeters.isFinite() &&
                conflictingCoordinateRadiusMeters >= coordinatePossibleMatchRadiusMeters
        )
        require(fuzzyAddressThreshold.isFinite() && fuzzyAddressThreshold in 0.0..1.0)
    }
}

/**
 * Resolves a provider identity against known canonical property identities.
 *
 * Signals are evaluated in the requested order: APN/parcel ID, normalized address, coordinates,
 * same-provider listing ID, then fuzzy address. Exact signals are cross-checked before a match is
 * returned: if they point at different records, or an exact link contradicts another known
 * identifier, the result is [DeduplicationStatus.CONFLICT]. Fuzzy and nearby-coordinate evidence
 * is never used to merge automatically.
 */
class PropertyIdentityDeduplicationEngine(
    private val config: DeduplicationConfig = DeduplicationConfig()
) {
    fun deduplicate(
        incoming: SourcePropertyIdentity,
        canonicalProperties: Collection<CanonicalPropertyIdentity>
    ): DeduplicationResult {
        // Canonical IDs are the result's stable references. De-duplicate repeated input rows and
        // sort so that ties/conflicts are deterministic regardless of collection iteration order.
        val canonical = canonicalProperties
            .distinctBy { it.canonicalId }
            .sortedBy { it.canonicalId }

        if (canonical.isEmpty()) return newResult()

        val evidence = canonical.map { candidate -> evaluate(incoming, candidate) }
        val exactSignals = listOf(
            IdentityMatchMethod.APN to evidence.filter { it.parcelIdMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.NORMALIZED_ADDRESS to evidence.filter { it.addressMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.COORDINATES to evidence.filter { it.coordinatesMatch }.map { it.canonical.canonicalId },
            IdentityMatchMethod.PROVIDER_LISTING_ID to evidence.filter { it.providerListingIdMatches }.map { it.canonical.canonicalId }
        ).map { (method, ids) -> method to ids.distinct().sorted() }
            .filter { (_, ids) -> ids.isNotEmpty() }

        val exactCandidateIds = exactSignals.flatMap { (_, ids) -> ids }.distinct().sorted()
        val ambiguousSignals = exactSignals.filter { (_, ids) -> ids.size > 1 }
        if (ambiguousSignals.isNotEmpty() || exactCandidateIds.size > 1) {
            val signalDetails = exactSignals.joinToString(separator = "; ") { (method, ids) ->
                "${method.displayName()} identifies ${ids.joinToString(prefix = "[", postfix = "]")}"
            }
            val reason = if (ambiguousSignals.isNotEmpty()) {
                "Conflicting identity evidence: at least one exact signal is ambiguous. $signalDetails."
            } else {
                "Conflicting identity evidence: exact signals point to different canonical properties. $signalDetails."
            }
            return conflict(exactCandidateIds, reason)
        }

        if (exactCandidateIds.size == 1) {
            val matchedId = exactCandidateIds.single()
            val matchedEvidence = evidence.single { it.canonical.canonicalId == matchedId }
            val contradictions = buildList {
                if (matchedEvidence.parcelIdConflicts) {
                    add("the incoming APN/parcel ID differs from the known parcel ID")
                }
                if (matchedEvidence.addressConflicts) {
                    add("the known street address, unit, state, or postal code conflicts")
                }
                if (matchedEvidence.coordinatesConflict) {
                    add(
                        "the closest known coordinates are ${formatMeters(matchedEvidence.coordinateDistanceMeters!!)} apart"
                    )
                }
            }
            if (contradictions.isNotEmpty()) {
                return conflict(
                    listOf(matchedId),
                    "An exact identity signal links the listing to $matchedId, but " +
                        contradictions.joinToString("; ") + ". Review the source data before merging."
                )
            }

            val method = exactSignals.first { (_, ids) -> matchedId in ids }.first
            val matchedCanonical = matchedEvidence.canonical
            return DeduplicationResult(
                status = DeduplicationStatus.MATCHED,
                matchMethod = method,
                canonicalIdentity = matchedCanonical,
                candidateCanonicalIds = listOf(matchedId),
                confidence = confidenceFor(method),
                reason = "Matched canonical property $matchedId by ${method.displayName()}."
            )
        }

        // We are now considering only non-exact signals. A different known parcel ID is a strong
        // exclusion for fuzzy/nearby evidence; it becomes a CONFLICT only when an exact signal also
        // links the incoming listing to that record.
        val coordinateCandidates = evidence.filter {
            it.coordinatesPossible && !it.parcelIdConflicts
        }
        val fuzzyCandidates = evidence.filter {
            it.fuzzyAddressScore >= config.fuzzyAddressThreshold && !it.parcelIdConflicts
        }
        val possibleIds = (coordinateCandidates + fuzzyCandidates)
            .map { it.canonical.canonicalId }
            .distinct()
            .sorted()

        if (possibleIds.isNotEmpty()) {
            val bestCoordinate = coordinateCandidates.maxByOrNull {
                coordinateProximityScore(it.coordinateDistanceMeters!!)
            }
            val bestFuzzy = fuzzyCandidates.maxByOrNull { it.fuzzyAddressScore }
            val method = if (bestCoordinate != null) {
                IdentityMatchMethod.COORDINATES
            } else {
                IdentityMatchMethod.FUZZY_ADDRESS
            }
            val confidence = when (method) {
                IdentityMatchMethod.COORDINATES -> coordinateProximityScore(bestCoordinate!!.coordinateDistanceMeters!!)
                IdentityMatchMethod.FUZZY_ADDRESS -> (0.50 + bestFuzzy!!.fuzzyAddressScore * 0.25).coerceIn(0.0, 0.79)
                else -> 0.0
            }
            val reason = buildString {
                append("Possible match; review before merging. ")
                if (bestCoordinate != null) {
                    append(
                        "Coordinates are ${formatMeters(bestCoordinate.coordinateDistanceMeters!!)} from " +
                            "${bestCoordinate.canonical.canonicalId} (within the " +
                            "${formatMeters(config.coordinatePossibleMatchRadiusMeters)} review radius)."
                    )
                }
                if (bestFuzzy != null) {
                    if (bestCoordinate != null) append(' ')
                    append(
                        "Fuzzy address similarity to ${bestFuzzy.canonical.canonicalId} is " +
                            "${String.format(Locale.ROOT, "%.2f", bestFuzzy.fuzzyAddressScore)}."
                    )
                }
                if (possibleIds.size > 1) {
                    append(" Weak signals identify multiple candidates: ")
                    append(possibleIds.joinToString())
                    append('.')
                }
            }
            return DeduplicationResult(
                status = DeduplicationStatus.POSSIBLE_MATCH,
                matchMethod = method,
                candidateCanonicalIds = possibleIds,
                confidence = confidence,
                reason = reason
            )
        }

        return newResult()
    }

    private fun evaluate(
        incoming: SourcePropertyIdentity,
        canonical: CanonicalPropertyIdentity
    ): CandidateEvidence {
        val observations = buildList {
            add(
                SourcePropertyIdentity(
                    source = "",
                    parcelId = canonical.parcelId,
                    address = canonical.address,
                    city = canonical.city,
                    state = canonical.state,
                    postalCode = canonical.postalCode,
                    latitude = canonical.latitude,
                    longitude = canonical.longitude
                )
            )
            addAll(canonical.sourceIdentities)
        }.distinct()

        val incomingParcelId = normalizeParcelId(incoming.parcelId)
        val knownParcelIds = observations.mapNotNull { normalizeParcelId(it.parcelId) }.toSet()
        val parcelIdMatches = incomingParcelId != null && incomingParcelId in knownParcelIds
        val parcelIdConflicts = incomingParcelId != null &&
            knownParcelIds.isNotEmpty() &&
            incomingParcelId !in knownParcelIds

        val addressMatches = observations.any { addressesMatch(incoming, it) }
        val coordinateDistances = observations.mapNotNull { distanceMeters(incoming, it) }
        val coordinateDistance = coordinateDistances.minOrNull()
        val coordinatesMatch = coordinateDistance != null &&
            coordinateDistance <= config.coordinateMatchRadiusMeters
        val addressConflicts = allKnownAddressesConflict(incoming, observations)
        val coordinatesConflict = coordinateDistance != null &&
            coordinateDistance > config.conflictingCoordinateRadiusMeters

        val incomingListingId = normalizeProviderListingId(incoming.providerListingId)
        val incomingSource = normalizeSource(incoming.source)
        val providerListingIdMatches = incomingListingId != null && incomingSource != null &&
            observations.any { observation ->
                normalizeProviderListingId(observation.providerListingId) == incomingListingId &&
                    normalizeSource(observation.source) == incomingSource
            }

        val fuzzyScore = observations.maxOfOrNull { addressSimilarity(incoming, it) } ?: 0.0
        val coordinatesPossible = coordinateDistance != null &&
            coordinateDistance > config.coordinateMatchRadiusMeters &&
            coordinateDistance <= config.coordinatePossibleMatchRadiusMeters &&
            !addressConflicts

        return CandidateEvidence(
            canonical = canonical,
            parcelIdMatches = parcelIdMatches,
            parcelIdConflicts = parcelIdConflicts,
            addressMatches = addressMatches,
            addressConflicts = addressConflicts,
            coordinateDistanceMeters = coordinateDistance,
            coordinatesMatch = coordinatesMatch,
            coordinatesPossible = coordinatesPossible,
            coordinatesConflict = coordinatesConflict,
            providerListingIdMatches = providerListingIdMatches,
            fuzzyAddressScore = fuzzyScore
        )
    }

    private fun allKnownAddressesConflict(
        incoming: SourcePropertyIdentity,
        observations: List<SourcePropertyIdentity>
    ): Boolean {
        val incomingStreet = normalizedStreetAddress(incoming) ?: return false
        val comparable = observations.filter { normalizedStreetAddress(it) != null }
        return comparable.isNotEmpty() && comparable.all { hasHardAddressConflict(incoming, it, incomingStreet) }
    }

    private fun hasHardAddressConflict(
        first: SourcePropertyIdentity,
        second: SourcePropertyIdentity,
        normalizedFirstStreet: String
    ): Boolean {
        if (!locationsCompatible(first, second)) {
            val firstState = normalizeState(first.state)
            val secondState = normalizeState(second.state)
            val firstPostal = normalizePostalCode(first.postalCode)
            val secondPostal = normalizePostalCode(second.postalCode)
            if ((firstState != null && secondState != null && firstState != secondState) ||
                (firstPostal != null && secondPostal != null && firstPostal != secondPostal)
            ) return true
        }

        val secondStreet = normalizedStreetAddress(second) ?: return false
        if (normalizedFirstStreet == secondStreet) return false

        val firstUnit = unitIdentifier(normalizedFirstStreet)
        val secondUnit = unitIdentifier(secondStreet)
        if (firstUnit != null && secondUnit != null && firstUnit != secondUnit) return true

        val firstBase = withoutUnit(normalizedFirstStreet)
        val secondBase = withoutUnit(secondStreet)
        if (firstBase == secondBase) return false // One feed omitted the unit; that is incomplete, not contradictory.

        val firstNumber = houseNumber(firstBase)
        val secondNumber = houseNumber(secondBase)
        if (firstNumber != null && secondNumber != null && firstNumber != secondNumber) return true

        // Treat a small spelling error as weak evidence rather than a contradiction. Substantially
        // different streets linked by an exact APN/listing/coordinate signal need human review.
        return textSimilarity(firstBase, secondBase) < ADDRESS_CONTRADICTION_THRESHOLD
    }

    private fun addressesMatch(first: SourcePropertyIdentity, second: SourcePropertyIdentity): Boolean {
        if (!locationsCompatible(first, second)) return false
        val firstStreet = normalizedStreetAddress(first) ?: return false
        val secondStreet = normalizedStreetAddress(second) ?: return false
        return isSpecificAddress(firstStreet, first) &&
            isSpecificAddress(secondStreet, second) &&
            firstStreet == secondStreet
    }

    private fun addressSimilarity(first: SourcePropertyIdentity, second: SourcePropertyIdentity): Double {
        if (!locationsCompatible(first, second)) return 0.0
        val firstStreet = normalizedStreetAddress(first) ?: return 0.0
        val secondStreet = normalizedStreetAddress(second) ?: return 0.0
        if (!isSpecificAddress(firstStreet, first) || !isSpecificAddress(secondStreet, second)) return 0.0

        val firstUnit = unitIdentifier(firstStreet)
        val secondUnit = unitIdentifier(secondStreet)
        if (firstUnit != null && secondUnit != null && firstUnit != secondUnit) return 0.0

        // If just one feed omitted a unit, compare the shared base address as a review-only fuzzy
        // signal. It is deliberately not an exact address match, so the engine will not merge it.
        val firstComparableStreet = if (firstUnit == null) firstStreet else withoutUnit(firstStreet)
        val secondComparableStreet = if (secondUnit == null) secondStreet else withoutUnit(secondStreet)
        val firstNumber = houseNumber(firstComparableStreet)
        val secondNumber = houseNumber(secondComparableStreet)
        if (firstNumber != null && secondNumber != null && firstNumber != secondNumber) return 0.0
        return textSimilarity(firstComparableStreet, secondComparableStreet)
    }

    private fun locationsCompatible(first: SourcePropertyIdentity, second: SourcePropertyIdentity): Boolean {
        val firstState = normalizeState(first.state)
        val secondState = normalizeState(second.state)
        if (firstState != null && secondState != null && firstState != secondState) return false

        val firstPostal = normalizePostalCode(first.postalCode)
        val secondPostal = normalizePostalCode(second.postalCode)
        if (firstPostal != null && secondPostal != null && firstPostal != secondPostal) return false

        val firstCity = normalizeCity(first.city)
        val secondCity = normalizeCity(second.city)
        if (firstCity != null && secondCity != null && firstCity != secondCity &&
            (firstPostal == null || secondPostal == null || firstPostal != secondPostal)
        ) return false

        return true
    }

    private fun distanceMeters(first: SourcePropertyIdentity, second: SourcePropertyIdentity): Double? {
        val pointA = coordinates(first) ?: return null
        val pointB = coordinates(second) ?: return null
        return haversineMeters(pointA.latitude, pointA.longitude, pointB.latitude, pointB.longitude)
    }

    private fun coordinates(identity: SourcePropertyIdentity): Coordinates? {
        val latitude = identity.latitude ?: return null
        val longitude = identity.longitude ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        // Several feeds use the Null Island coordinate as a missing-value sentinel.
        if (latitude == 0.0 && longitude == 0.0) return null
        return Coordinates(latitude, longitude)
    }

    private fun newResult() = DeduplicationResult(
        status = DeduplicationStatus.NEW,
        matchMethod = IdentityMatchMethod.NONE,
        reason = "No APN/parcel ID, normalized address, coordinate, provider listing ID, or fuzzy-address candidate matched the supplied canonical properties."
    )

    private fun conflict(canonicalIds: List<String>, reason: String) = DeduplicationResult(
        status = DeduplicationStatus.CONFLICT,
        matchMethod = IdentityMatchMethod.CONFLICT,
        candidateCanonicalIds = canonicalIds.distinct().sorted(),
        reason = reason
    )

    private fun confidenceFor(method: IdentityMatchMethod): Double = when (method) {
        IdentityMatchMethod.APN -> 0.995
        IdentityMatchMethod.NORMALIZED_ADDRESS -> 0.98
        IdentityMatchMethod.COORDINATES -> 0.92
        IdentityMatchMethod.PROVIDER_LISTING_ID -> 0.88
        else -> 0.0
    }

    private fun coordinateProximityScore(distanceMeters: Double): Double {
        val radius = config.coordinatePossibleMatchRadiusMeters.coerceAtLeast(1.0)
        return (0.55 + (1.0 - distanceMeters / radius) * 0.20).coerceIn(0.0, 0.75)
    }

    private fun formatMeters(meters: Double): String = when {
        meters < 1.0 -> "less than 1 m"
        meters < 1_000.0 -> "${meters.toInt()} m"
        else -> String.format(Locale.ROOT, "%.2f km", meters / 1_000.0)
    }

    private data class CandidateEvidence(
        val canonical: CanonicalPropertyIdentity,
        val parcelIdMatches: Boolean,
        val parcelIdConflicts: Boolean,
        val addressMatches: Boolean,
        val addressConflicts: Boolean,
        val coordinateDistanceMeters: Double?,
        val coordinatesMatch: Boolean,
        val coordinatesPossible: Boolean,
        val coordinatesConflict: Boolean,
        val providerListingIdMatches: Boolean,
        val fuzzyAddressScore: Double
    )

    private data class Coordinates(val latitude: Double, val longitude: Double)

    companion object {
        private const val ADDRESS_CONTRADICTION_THRESHOLD = 0.70

        private fun normalizeParcelId(value: String?): String? {
            val stripped = value
                ?.trim()
                ?.replace(
                    Regex("(?i)^(?:APN|PARCEL(?:\\s+(?:ID|NUMBER|NO\\.?))?)\\s*[:#-]?\\s*"),
                    ""
                )
                ?.uppercase(Locale.ROOT)
                ?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (stripped in setOf("NA", "N A", "NONE", "NULL", "UNKNOWN", "NOTAVAILABLE", "0")) return null
            return stripped
        }

        private fun normalizeProviderListingId(value: String?): String? = value
            ?.trim()
            ?.replace(Regex("\\s+"), "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it !in setOf("n/a", "none", "null", "unknown") }

        private fun normalizeSource(value: String?): String? = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }

        private fun normalizeCity(value: String?): String? = normalizeText(value)

        private fun normalizeState(value: String?): String? {
            val raw = value?.takeIf { it.isNotBlank() } ?: return null
            val plain = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            if (plain.isBlank()) return null
            val compactState = plain.filter(Char::isLetterOrDigit)
            if (compactState.length == 2 && compactState in STATE_NAME_TO_CODE.values) return compactState
            return STATE_NAME_TO_CODE[plain] ?: normalizeText(plain)
        }

        private fun normalizePostalCode(value: String?): String? {
            val normalized = value
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (normalized in setOf("NA", "NONE", "NULL", "UNKNOWN", "NOTAVAILABLE")) return null
            return if (normalized.all(Char::isDigit) && normalized.length >= 5) {
                normalized.take(5)
            } else {
                normalized
            }
        }

        private fun normalizedStreetAddress(identity: SourcePropertyIdentity): String? {
            val rawAddress = identity.address?.takeIf { it.isNotBlank() } ?: return null
            val tokens = normalizeAddressTokens(rawAddress).toMutableList()
            if (tokens.isEmpty()) return null

            // Providers sometimes append city/state/postal code to the street line. Remove only
            // known trailing location components so the same address compares equally either way.
            val postal = normalizePostalCode(identity.postalCode)
            if (postal != null && tokens.isNotEmpty()) {
                val last = tokens.last()
                if (last.length == 4 && last.all(Char::isDigit) && tokens.size >= 2 && tokens[tokens.lastIndex - 1] == postal) {
                    tokens.removeAt(tokens.lastIndex)
                    tokens.removeAt(tokens.lastIndex)
                } else if (last == postal) {
                    tokens.removeAt(tokens.lastIndex)
                }
            }
            val state = normalizeAddressTokens(identity.state.orEmpty())
            removeTrailingTokens(tokens, state)
            val city = normalizeAddressTokens(identity.city.orEmpty())
            removeTrailingTokens(tokens, city)

            return tokens.joinToString(" ").takeIf { it.isNotBlank() }
        }

        private fun removeTrailingTokens(tokens: MutableList<String>, suffix: List<String>) {
            if (suffix.isEmpty() || tokens.size <= suffix.size) return
            val start = tokens.size - suffix.size
            if (tokens.subList(start, tokens.size) == suffix) {
                repeat(suffix.size) { tokens.removeAt(tokens.lastIndex) }
            }
        }

        private fun isSpecificAddress(street: String, identity: SourcePropertyIdentity): Boolean {
            val tokens = street.split(' ').filter { it.isNotBlank() }
            if (tokens.size < 2) return false
            val hasHouseNumber = houseNumber(street) != null
            val hasLocation = identity.city.isNullOrBlank().not() ||
                identity.state.isNullOrBlank().not() ||
                identity.postalCode.isNullOrBlank().not()
            return hasHouseNumber || hasLocation
        }

        private fun houseNumber(normalizedStreet: String): String? = normalizedStreet
            .split(' ')
            .firstOrNull()
            ?.takeIf { token -> token.any(Char::isDigit) }

        private fun unitIdentifier(normalizedStreet: String): String? {
            val tokens = normalizedStreet.split(' ')
            val unitIndex = tokens.indexOf("unit")
            return tokens.getOrNull(unitIndex + 1)?.takeIf { unitIndex >= 0 }
        }

        private fun withoutUnit(normalizedStreet: String): String {
            val tokens = normalizedStreet.split(' ')
            val unitIndex = tokens.indexOf("unit")
            return if (unitIndex < 0) normalizedStreet else tokens.take(unitIndex).joinToString(" ")
        }

        private fun textSimilarity(first: String, second: String): Double {
            if (first == second) return 1.0
            val longestLength = max(first.length, second.length)
            if (longestLength == 0) return 1.0
            val editSimilarity = 1.0 - levenshteinDistance(first, second).toDouble() / longestLength
            val firstTokens = first.split(' ').filter { it.isNotBlank() }.toSet()
            val secondTokens = second.split(' ').filter { it.isNotBlank() }.toSet()
            val tokenUnion = firstTokens union secondTokens
            val tokenSimilarity = if (tokenUnion.isEmpty()) 1.0 else {
                (firstTokens intersect secondTokens).size.toDouble() / tokenUnion.size
            }
            return max(editSimilarity, tokenSimilarity).coerceIn(0.0, 1.0)
        }

        private fun levenshteinDistance(first: String, second: String): Int {
            if (first.isEmpty()) return second.length
            if (second.isEmpty()) return first.length

            var previous = IntArray(second.length + 1) { it }
            for (firstIndex in first.indices) {
                val current = IntArray(second.length + 1)
                current[0] = firstIndex + 1
                for (secondIndex in second.indices) {
                    val substitutionCost = if (first[firstIndex] == second[secondIndex]) 0 else 1
                    current[secondIndex + 1] = min(
                        min(current[secondIndex] + 1, previous[secondIndex + 1] + 1),
                        previous[secondIndex] + substitutionCost
                    )
                }
                previous = current
            }
            return previous[second.length]
        }

        private fun normalizeText(value: String?): String? {
            val tokens = value?.takeIf { it.isNotBlank() }?.let(::normalizeAddressTokens) ?: return null
            return tokens.joinToString(" ").takeIf { it.isNotBlank() }
        }

        private fun normalizeAddressTokens(value: String): List<String> {
            val plain = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace("#", " unit ")
                .replace(Regex("\\b(?:apartment|apt|suite|ste|unit)\\b"), " unit ")
            val collapsedTokens = plain
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { ADDRESS_TOKEN_EQUIVALENTS[it] ?: it }

            val result = mutableListOf<String>()
            for (token in collapsedTokens) {
                if (token == "unit" && result.lastOrNull() == "unit") continue
                result += token
            }
            return result
        }

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusMeters = 6_371_000.0
            val latitudeDelta = Math.toRadians(lat2 - lat1)
            val longitudeDelta = Math.toRadians(lon2 - lon1)
            val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            return 2 * earthRadiusMeters * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }

        private val STATE_NAME_TO_CODE = mapOf(
            "alabama" to "al", "alaska" to "ak", "arizona" to "az", "arkansas" to "ar",
            "california" to "ca", "colorado" to "co", "connecticut" to "ct", "delaware" to "de",
            "florida" to "fl", "georgia" to "ga", "hawaii" to "hi", "idaho" to "id",
            "illinois" to "il", "indiana" to "in", "iowa" to "ia", "kansas" to "ks",
            "kentucky" to "ky", "louisiana" to "la", "maine" to "me", "maryland" to "md",
            "massachusetts" to "ma", "michigan" to "mi", "minnesota" to "mn", "mississippi" to "ms",
            "missouri" to "mo", "montana" to "mt", "nebraska" to "ne", "nevada" to "nv",
            "new hampshire" to "nh", "new jersey" to "nj", "new mexico" to "nm", "new york" to "ny",
            "north carolina" to "nc", "north dakota" to "nd", "ohio" to "oh", "oklahoma" to "ok",
            "oregon" to "or", "pennsylvania" to "pa", "rhode island" to "ri", "south carolina" to "sc",
            "south dakota" to "sd", "tennessee" to "tn", "texas" to "tx", "utah" to "ut",
            "vermont" to "vt", "virginia" to "va", "washington" to "wa", "west virginia" to "wv",
            "wisconsin" to "wi", "wyoming" to "wy", "district of columbia" to "dc"
        )

        private val ADDRESS_TOKEN_EQUIVALENTS: Map<String, String> = buildMap {
            putAll(
                mapOf(
                    "north" to "n", "south" to "s", "east" to "e", "west" to "w",
                    "northeast" to "ne", "northwest" to "nw", "southeast" to "se", "southwest" to "sw",
                    "street" to "st", "avenue" to "ave", "road" to "rd", "boulevard" to "blvd",
                    "drive" to "dr", "lane" to "ln", "court" to "ct", "circle" to "cir",
                    "parkway" to "pkwy", "highway" to "hwy", "place" to "pl", "terrace" to "ter",
                    "trail" to "trl", "square" to "sq", "crescent" to "cres", "expressway" to "expy",
                    "freeway" to "fwy", "junction" to "jct", "mount" to "mt", "mountain" to "mtn",
                    "center" to "ctr", "centre" to "ctr", "plaza" to "plz", "point" to "pt",
                    "river" to "riv", "valley" to "vly", "vista" to "vis", "harbor" to "hbr",
                    "harbour" to "hbr", "heights" to "hts", "extension" to "ext", "turnpike" to "tpke",
                    "bypass" to "byp", "crossing" to "xing", "grove" to "grv", "village" to "vlg",
                    "alley" to "aly", "brook" to "brk", "creek" to "crk", "estate" to "est",
                    "fort" to "ft", "garden" to "gdn", "gardens" to "gdns", "green" to "grn",
                    "island" to "isl", "lake" to "lk", "mill" to "ml", "park" to "park",
                    "ridge" to "rdg", "spring" to "spg", "station" to "sta", "terr" to "ter"
                )
            )
            putAll(STATE_NAME_TO_CODE)
        }
    }
}
