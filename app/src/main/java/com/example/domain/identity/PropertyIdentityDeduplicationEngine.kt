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
    IdentityMatchMethod.MLS_ID -> "MLS ID"
    IdentityMatchMethod.SOURCE_LISTING_ID -> "source listing ID"
    IdentityMatchMethod.SOURCE_URL -> "normalized listing URL"
    IdentityMatchMethod.NORMALIZED_ADDRESS -> "normalized address"
    IdentityMatchMethod.COORDINATES -> "coordinates"
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
    /**
     * Fuzzy addresses at or above this similarity are review-only candidates.
     *
     * 0.80 keeps realistic single-token typos in the review queue (for example
     * "123 Mane St" vs "123 Main St" scores 0.82) while staying far away from any auto-merge:
     * fuzzy evidence can only ever produce [DeduplicationStatus.POSSIBLE_MATCH].
     */
    val fuzzyAddressThreshold: Double = 0.80
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
 * ## Deterministic matching precedence
 *
 * Exact signals are evaluated in this fixed order (strongest first); the first signal that
 * matched the resolved candidate is reported as [DeduplicationResult.matchMethod]:
 *
 *  1. **APN / parcel ID** - the county parcel identifier; identifies the physical parcel and is
 *     independent of any listing or feed.
 *  2. **MLS ID** - the listing number of the originating MLS board; shared by every portal that
 *     syndicates the listing, so it is matched across providers (not namespaced by source).
 *  3. **Source listing ID** - `(source, providerListingId)`; namespaced by source because these
 *     ids are only unique inside one provider. A hit means *the same source record* was imported
 *     before, so the outcome is [DeduplicationStatus.EXACT_MATCH] and re-importing is an
 *     idempotent refresh.
 *  4. **Source URL** - the normalized listing URL (scheme folded to https, host lowercased
 *     without `www.`, tracking query parameters removed, remaining parameters sorted). The URL
 *     host is its namespace: the same normalized URL denotes the same source record even when
 *     adapters label it with different source names, so a hit is also an
 *     [DeduplicationStatus.EXACT_MATCH].
 *  5. **Normalized address** - USPS-style normalization (case, punctuation, directionals,
 *     suffixes, unit designators, appended city/state/ZIP) plus locality compatibility.
 *  6. **Normalized coordinates** - haversine distance inside
 *     [DeduplicationConfig.coordinateMatchRadiusMeters]; Null Island, out-of-range and
 *     non-finite values are treated as missing.
 *
 * Weak signals are never merged automatically:
 *  - coordinates inside [DeduplicationConfig.coordinatePossibleMatchRadiusMeters], and
 *  - fuzzy address similarity at or above [DeduplicationConfig.fuzzyAddressThreshold]
 *
 * produce [DeduplicationStatus.POSSIBLE_MATCH] (review only). A candidate whose known parcel ID
 * differs from the incoming one is excluded from weak-signal matching entirely.
 *
 * ## Conflicts
 *
 * Exact signals are cross-checked before a match is returned. The result is
 * [DeduplicationStatus.CONFLICT] when:
 *  - one exact signal matches several canonical records (an ambiguous identifier), or
 *  - different exact signals point at different canonical records, or
 *  - an exact link to one record contradicts another known identifier of that record
 *    (different parcel ID, hard address/unit conflict, or coordinates further apart than
 *    [DeduplicationConfig.conflictingCoordinateRadiusMeters]).
 *
 * A *different* MLS ID, source listing ID, or URL is deliberately not a contradiction: listings
 * get relisted and re-published under new numbers, while parcel IDs and addresses identify the
 * physical property.
 *
 * ## Explainability
 *
 * Every result carries [DeduplicationResult.evidence]: the ordered trail of each evaluated
 * signal with the value compared, the canonical ids it matched, the ids it contradicted, and a
 * human-readable detail line - plus the [DeduplicationResult.reason] summary. The engine is a
 * pure function of its inputs: identical inputs always produce identical outputs, and candidate
 * iteration order cannot change the decision (inputs are de-duplicated and sorted by canonical
 * id first).
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

        val incomingSignals = IncomingSignals.from(incoming)
        if (canonical.isEmpty()) return newResult(incomingSignals)

        val evidence = canonical.map { candidate -> evaluate(incoming, incomingSignals, candidate) }

        // Exact signals in strict matching precedence (see class KDoc).
        val exactSignals = listOf(
            IdentityMatchMethod.APN to evidence.filter { it.parcelIdMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.MLS_ID to evidence.filter { it.mlsIdMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.SOURCE_LISTING_ID to evidence.filter { it.sourceListingIdMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.SOURCE_URL to evidence.filter { it.sourceUrlMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.NORMALIZED_ADDRESS to evidence.filter { it.addressMatches }.map { it.canonical.canonicalId },
            IdentityMatchMethod.COORDINATES to evidence.filter { it.coordinatesMatch }.map { it.canonical.canonicalId }
        ).map { (method, ids) -> method to ids.distinct().sorted() }

        val activeExactSignals = exactSignals.filter { (_, ids) -> ids.isNotEmpty() }
        val exactCandidateIds = activeExactSignals.flatMap { (_, ids) -> ids }.distinct().sorted()

        val trail = buildEvidenceTrail(
            signals = incomingSignals,
            evidence = evidence,
            exactSignals = exactSignals,
            includeWeakEvidence = exactCandidateIds.isEmpty()
        )

        val ambiguousSignals = activeExactSignals.filter { (_, ids) -> ids.size > 1 }
        if (ambiguousSignals.isNotEmpty() || exactCandidateIds.size > 1) {
            val signalDetails = activeExactSignals.joinToString(separator = "; ") { (method, ids) ->
                "${method.displayName()} identifies ${ids.joinToString(prefix = "[", postfix = "]")}"
            }
            val reason = if (ambiguousSignals.isNotEmpty()) {
                "Conflicting identity evidence: at least one exact signal is ambiguous. $signalDetails."
            } else {
                "Conflicting identity evidence: exact signals point to different canonical properties. $signalDetails."
            }
            return conflict(exactCandidateIds, reason, trail)
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
                        contradictions.joinToString("; ") + ". Review the source data before merging.",
                    trail
                )
            }

            val matchedSignals = activeExactSignals.filter { (_, ids) -> matchedId in ids }.map { it.first }
            val sameRecordSignal = matchedSignals.firstOrNull { it in SAME_RECORD_SIGNALS }
            // The headline signal decides the outcome: a same-record identity (source listing ID /
            // URL) makes this an idempotent re-import; otherwise the strongest matching exact
            // signal (precedence order) identifies the canonical property across sources.
            val method = sameRecordSignal ?: matchedSignals.first()
            val matchedCanonical = matchedEvidence.canonical

            // A same-source record identity (source listing ID or URL) means this exact record was
            // imported before: the re-import is an idempotent refresh, reported as EXACT_MATCH.
            val status = if (sameRecordSignal != null) {
                DeduplicationStatus.EXACT_MATCH
            } else {
                DeduplicationStatus.CANONICAL_MATCH
            }
            val corroboration = matchedSignals.filter { it != method }
            val reason = buildString {
                if (status == DeduplicationStatus.EXACT_MATCH) {
                    append("Same source record re-imported: ")
                    append(method.displayName())
                    append(" already links to canonical property $matchedId; refreshing it is idempotent.")
                } else {
                    append("Matched canonical property $matchedId by ${method.displayName()}.")
                }
                if (corroboration.isNotEmpty()) {
                    append(" Corroborated by ")
                    append(corroboration.joinToString(", ") { it.displayName() })
                    append('.')
                }
            }
            return DeduplicationResult(
                status = status,
                matchMethod = method,
                canonicalIdentity = matchedCanonical,
                candidateCanonicalIds = listOf(matchedId),
                confidence = if (status == DeduplicationStatus.EXACT_MATCH) 1.0 else confidenceFor(method),
                reason = reason,
                evidence = trail
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
                append(" Ambiguous fuzzy evidence is never merged automatically.")
            }
            return DeduplicationResult(
                status = DeduplicationStatus.POSSIBLE_MATCH,
                matchMethod = method,
                candidateCanonicalIds = possibleIds,
                confidence = confidence,
                reason = reason,
                evidence = trail
            )
        }

        return newResult(incomingSignals, trail)
    }

    /** The incoming identity's normalized signal values, computed once per decision. */
    private class IncomingSignals(
        val source: String?,
        val parcelId: String?,
        val mlsId: String?,
        val providerListingId: String?,
        val sourceUrl: String?,
        val streetAddress: String?,
        val coordinates: Coordinates?
    ) {
        companion object {
            fun from(incoming: SourcePropertyIdentity) = IncomingSignals(
                source = normalizeSource(incoming.source),
                parcelId = normalizeParcelId(incoming.parcelId),
                mlsId = normalizeMlsId(incoming.mlsId),
                providerListingId = normalizeProviderListingId(incoming.providerListingId),
                sourceUrl = normalizeSourceUrl(incoming.sourceUrl),
                streetAddress = normalizedStreetAddress(incoming),
                coordinates = coordinatesOf(incoming)
            )
        }
    }

    private fun evaluate(
        incoming: SourcePropertyIdentity,
        signals: IncomingSignals,
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
                    longitude = canonical.longitude,
                    mlsId = canonical.mlsId
                )
            )
            addAll(canonical.sourceIdentities)
        }.distinct()

        val knownParcelIds = observations.mapNotNull { normalizeParcelId(it.parcelId) }.toSet()
        val parcelIdMatches = signals.parcelId != null && signals.parcelId in knownParcelIds
        val parcelIdConflicts = signals.parcelId != null &&
            knownParcelIds.isNotEmpty() &&
            signals.parcelId !in knownParcelIds

        // MLS numbers are shared across portals: matched globally, and a *different* MLS number is
        // not a contradiction because properties get relisted under new numbers.
        val knownMlsIds = observations.mapNotNull { normalizeMlsId(it.mlsId) }.toSet()
        val mlsIdMatches = signals.mlsId != null && signals.mlsId in knownMlsIds

        // Source listing IDs are namespaced by their provider.
        val sourceListingIdMatches = signals.providerListingId != null && signals.source != null &&
            observations.any { observation ->
                normalizeProviderListingId(observation.providerListingId) == signals.providerListingId &&
                    normalizeSource(observation.source) == signals.source
            }

        // Listing URLs are namespaced by their host, which normalizeSourceUrl keeps: the same
        // normalized URL denotes the same source record regardless of adapter labels.
        val sourceUrlMatches = signals.sourceUrl != null &&
            observations.any { normalizeSourceUrl(it.sourceUrl) == signals.sourceUrl }

        val addressMatches = observations.any { addressesMatch(incoming, it) }
        val coordinateDistances = observations.mapNotNull { distanceMeters(signals.coordinates, it) }
        val coordinateDistance = coordinateDistances.minOrNull()
        val coordinatesMatch = coordinateDistance != null &&
            coordinateDistance <= config.coordinateMatchRadiusMeters
        val addressConflicts = allKnownAddressesConflict(incoming, observations)
        val coordinatesConflict = coordinateDistance != null &&
            coordinateDistance > config.conflictingCoordinateRadiusMeters

        val fuzzyScore = observations.maxOfOrNull { addressSimilarity(incoming, it) } ?: 0.0
        val coordinatesPossible = coordinateDistance != null &&
            coordinateDistance > config.coordinateMatchRadiusMeters &&
            coordinateDistance <= config.coordinatePossibleMatchRadiusMeters &&
            !addressConflicts

        return CandidateEvidence(
            canonical = canonical,
            parcelIdMatches = parcelIdMatches,
            parcelIdConflicts = parcelIdConflicts,
            mlsIdMatches = mlsIdMatches,
            sourceListingIdMatches = sourceListingIdMatches,
            sourceUrlMatches = sourceUrlMatches,
            addressMatches = addressMatches,
            addressConflicts = addressConflicts,
            coordinateDistanceMeters = coordinateDistance,
            coordinatesMatch = coordinatesMatch,
            coordinatesPossible = coordinatesPossible,
            coordinatesConflict = coordinatesConflict,
            fuzzyAddressScore = fuzzyScore
        )
    }

    /**
     * Builds the deterministic audit trail: one entry per exact signal the incoming identity
     * actually supplied (in precedence order), followed by the weak-signal summary when any
     * candidate produced review-worthy evidence.
     */
    private fun buildEvidenceTrail(
        signals: IncomingSignals,
        evidence: List<CandidateEvidence>,
        exactSignals: List<Pair<IdentityMatchMethod, List<String>>>,
        includeWeakEvidence: Boolean = true
    ): List<IdentityEvidence> {
        val trail = mutableListOf<IdentityEvidence>()

        fun matchedIds(method: IdentityMatchMethod): List<String> =
            exactSignals.first { it.first == method }.second

        fun addExact(
            method: IdentityMatchMethod,
            suppliedValue: String?,
            contradicted: List<String> = emptyList(),
            noMatchDetail: String
        ) {
            if (suppliedValue == null) return
            val matched = matchedIds(method)
            val detail = when {
                matched.isNotEmpty() ->
                    "${method.displayName()} '$suppliedValue' matched " +
                        matched.joinToString(prefix = "[", postfix = "]")
                contradicted.isNotEmpty() ->
                    "${method.displayName()} '$suppliedValue' matched nothing and contradicts " +
                        contradicted.joinToString(prefix = "[", postfix = "]")
                else -> noMatchDetail
            }
            trail += IdentityEvidence(
                signal = method,
                incomingValue = suppliedValue,
                matchedCanonicalIds = matched,
                contradictedCanonicalIds = contradicted.sorted(),
                detail = detail
            )
        }

        addExact(
            IdentityMatchMethod.APN,
            signals.parcelId,
            contradicted = evidence.filter { it.parcelIdConflicts }.map { it.canonical.canonicalId },
            noMatchDetail = "APN/parcel ID '${signals.parcelId}' matched no known parcel ID"
        )
        addExact(
            IdentityMatchMethod.MLS_ID,
            signals.mlsId,
            noMatchDetail = "MLS ID '${signals.mlsId}' matched no known MLS listing"
        )
        addExact(
            IdentityMatchMethod.SOURCE_LISTING_ID,
            signals.providerListingId?.let { id ->
                signals.source?.let { "$it:$id" } ?: id
            },
            noMatchDetail = "source listing ID '${signals.source?.let { "$it:" }.orEmpty()}" +
                "${signals.providerListingId}' was never imported before"
        )
        addExact(
            IdentityMatchMethod.SOURCE_URL,
            signals.sourceUrl,
            noMatchDetail = "normalized listing URL '${signals.sourceUrl}' was never imported before"
        )
        addExact(
            IdentityMatchMethod.NORMALIZED_ADDRESS,
            signals.streetAddress,
            contradicted = evidence.filter { it.addressConflicts }.map { it.canonical.canonicalId },
            noMatchDetail = "normalized address '${signals.streetAddress}' matched no known address"
        )
        if (signals.coordinates != null) {
            val matched = matchedIds(IdentityMatchMethod.COORDINATES)
            val closest = evidence
                .filter { it.coordinateDistanceMeters != null }
                .minByOrNull { it.coordinateDistanceMeters!! }
            val detail = when {
                matched.isNotEmpty() ->
                    "coordinates ${signals.coordinates.latitude},${signals.coordinates.longitude} are within " +
                        "${formatMeters(config.coordinateMatchRadiusMeters)} of " +
                        matched.joinToString(prefix = "[", postfix = "]")
                closest != null ->
                    "coordinates ${signals.coordinates.latitude},${signals.coordinates.longitude} are " +
                        "${formatMeters(closest.coordinateDistanceMeters!!)} from the closest candidate " +
                        "${closest.canonical.canonicalId} (outside the exact-match radius)"
                else ->
                    "coordinates ${signals.coordinates.latitude},${signals.coordinates.longitude} matched no known coordinates"
            }
            trail += IdentityEvidence(
                signal = IdentityMatchMethod.COORDINATES,
                incomingValue = "${signals.coordinates.latitude},${signals.coordinates.longitude}",
                matchedCanonicalIds = matched,
                contradictedCanonicalIds = evidence.filter { it.coordinatesConflict }
                    .map { it.canonical.canonicalId }.sorted(),
                detail = detail
            )
        }

        // Weak evidence is informative only when nothing exact resolved the identity; a fuzzy
        // row for a candidate that an exact signal already matched would just be noise.
        val weak = if (!includeWeakEvidence) {
            emptyList()
        } else {
            evidence
                .filter { it.coordinatesPossible || it.fuzzyAddressScore >= config.fuzzyAddressThreshold }
                .sortedBy { it.canonical.canonicalId }
        }
        if (weak.isNotEmpty()) {
            trail += IdentityEvidence(
                signal = IdentityMatchMethod.FUZZY_ADDRESS,
                incomingValue = signals.streetAddress
                    ?: signals.coordinates?.let { "${it.latitude},${it.longitude}" }
                    ?: "",
                matchedCanonicalIds = emptyList(),
                detail = "weak evidence (never merged automatically): " + weak.joinToString("; ") {
                    buildString {
                        append(it.canonical.canonicalId)
                        if (it.fuzzyAddressScore >= config.fuzzyAddressThreshold) {
                            append(" fuzzy address ")
                            append(String.format(Locale.ROOT, "%.2f", it.fuzzyAddressScore))
                        }
                        if (it.coordinatesPossible) {
                            if (it.fuzzyAddressScore >= config.fuzzyAddressThreshold) append(',')
                            append(" coordinates ")
                            append(formatMeters(it.coordinateDistanceMeters!!))
                            append(" away")
                        }
                    }
                }
            )
        }

        if (trail.isEmpty()) {
            trail += IdentityEvidence(
                signal = IdentityMatchMethod.NONE,
                incomingValue = "",
                detail = "the incoming identity supplied no usable APN/parcel ID, MLS ID, source " +
                    "listing ID, source URL, normalized address, or coordinates"
            )
        }
        return trail
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

    private fun distanceMeters(
        incomingCoordinates: Coordinates?,
        second: SourcePropertyIdentity
    ): Double? {
        val pointA = incomingCoordinates ?: return null
        val pointB = coordinatesOf(second) ?: return null
        return haversineMeters(pointA.latitude, pointA.longitude, pointB.latitude, pointB.longitude)
    }

    private fun newResult(
        signals: IncomingSignals,
        trail: List<IdentityEvidence> = emptyList()
    ) = DeduplicationResult(
        status = DeduplicationStatus.NEW,
        matchMethod = IdentityMatchMethod.NONE,
        reason = "No APN/parcel ID, MLS ID, source listing ID, source URL, normalized address, " +
            "coordinate, or fuzzy-address candidate matched the supplied canonical properties.",
        evidence = trail.ifEmpty { buildEvidenceTrail(signals, emptyList(), EXACT_SIGNAL_PRECEDENCE.map { it to emptyList() }) }
    )

    private fun conflict(
        canonicalIds: List<String>,
        reason: String,
        trail: List<IdentityEvidence>
    ) = DeduplicationResult(
        status = DeduplicationStatus.CONFLICT,
        matchMethod = IdentityMatchMethod.CONFLICT,
        candidateCanonicalIds = canonicalIds.distinct().sorted(),
        reason = reason,
        evidence = trail
    )

    private fun confidenceFor(method: IdentityMatchMethod): Double = when (method) {
        IdentityMatchMethod.APN -> 0.995
        IdentityMatchMethod.MLS_ID -> 0.99
        IdentityMatchMethod.SOURCE_LISTING_ID -> 0.985
        IdentityMatchMethod.SOURCE_URL -> 0.98
        IdentityMatchMethod.NORMALIZED_ADDRESS -> 0.97
        IdentityMatchMethod.COORDINATES -> 0.92
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
        val mlsIdMatches: Boolean,
        val sourceListingIdMatches: Boolean,
        val sourceUrlMatches: Boolean,
        val addressMatches: Boolean,
        val addressConflicts: Boolean,
        val coordinateDistanceMeters: Double?,
        val coordinatesMatch: Boolean,
        val coordinatesPossible: Boolean,
        val coordinatesConflict: Boolean,
        val fuzzyAddressScore: Double
    )

    private data class Coordinates(val latitude: Double, val longitude: Double)

    companion object {
        private const val ADDRESS_CONTRADICTION_THRESHOLD = 0.70

        /** Signals that identify *the same source record* and therefore yield EXACT_MATCH. */
        private val SAME_RECORD_SIGNALS = setOf(
            IdentityMatchMethod.SOURCE_LISTING_ID,
            IdentityMatchMethod.SOURCE_URL
        )

        /** Exact signal precedence, strongest first (mirrors the class KDoc ladder). */
        private val EXACT_SIGNAL_PRECEDENCE = listOf(
            IdentityMatchMethod.APN,
            IdentityMatchMethod.MLS_ID,
            IdentityMatchMethod.SOURCE_LISTING_ID,
            IdentityMatchMethod.SOURCE_URL,
            IdentityMatchMethod.NORMALIZED_ADDRESS,
            IdentityMatchMethod.COORDINATES
        )

        /** Placeholder strings feeds use for "we have no value"; treated as missing identifiers. */
        private val PLACEHOLDER_VALUES = setOf("NA", "NONE", "NULL", "UNKNOWN", "NOTAVAILABLE", "0")

        /** Query parameters that carry tracking, never listing identity. */
        private val TRACKING_QUERY_PARAMS = setOf(
            "fbclid", "gclid", "igshid", "mc_cid", "mc_eid", "yclid", "msclkid",
            "pk_campaign", "pk_kwd", "rb_clickid", "dclid", "twclid"
        )

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
            if (stripped in PLACEHOLDER_VALUES) return null
            return stripped
        }

        /**
         * MLS/listing numbers: label prefixes removed ("MLS# A10-50837" -> "A1050837"), case and
         * separators folded. Matched across providers because MLS numbers are board-scoped, not
         * portal-scoped.
         */
        private fun normalizeMlsId(value: String?): String? {
            val stripped = value
                ?.trim()
                ?.replace(
                    Regex("(?i)^(?:MLS|LISTING)(?:\\s*(?:ID|NUMBER|NUM|NO\\.?|#))?\\s*[:#-]?\\s*"),
                    ""
                )
                ?.uppercase(Locale.ROOT)
                ?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.isNotBlank() }
                ?: return null
            if (stripped in PLACEHOLDER_VALUES || stripped == "MLS") return null
            return stripped
        }

        private fun normalizeProviderListingId(value: String?): String? = value
            ?.trim()
            ?.replace(Regex("\\s+"), "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it !in setOf("n/a", "none", "null", "unknown") }

        /**
         * Normalizes a listing URL into a deterministic identity form:
         *  - scheme folded to `https` (portals serve the same record over both schemes);
         *  - host lowercased, `www.` and default ports removed - the host is the URL's namespace;
         *  - fragment removed, duplicate slashes collapsed, trailing slash removed;
         *  - tracking parameters (utm_*, fbclid, gclid, ...) removed, remaining parameters sorted;
         *  - everything lowercased so cosmetic casing cannot split one record into two.
         *
         * Returns null for blank, placeholder, or host-less values (missing identifier).
         */
        private fun normalizeSourceUrl(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (trimmed.lowercase(Locale.ROOT) in setOf("n/a", "na", "none", "null", "unknown", "-")) return null

            var rest = trimmed
            val schemeIndex = rest.indexOf("://")
            if (schemeIndex >= 0) {
                val scheme = rest.substring(0, schemeIndex).lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") return null
                rest = rest.substring(schemeIndex + 3)
            }

            val fragmentIndex = rest.indexOf('#')
            if (fragmentIndex >= 0) rest = rest.substring(0, fragmentIndex)

            var query = ""
            val queryIndex = rest.indexOf('?')
            if (queryIndex >= 0) {
                query = rest.substring(queryIndex + 1)
                rest = rest.substring(0, queryIndex)
            }

            val slashIndex = rest.indexOf('/')
            var host = (if (slashIndex >= 0) rest.substring(0, slashIndex) else rest).lowercase(Locale.ROOT)
            var path = if (slashIndex >= 0) rest.substring(slashIndex) else ""

            val portIndex = host.indexOf(':')
            if (portIndex >= 0) {
                val port = host.substring(portIndex + 1)
                host = host.substring(0, portIndex)
                if (port.isNotEmpty() && port != "80" && port != "443") host = "$host:$port"
            }
            if (host.startsWith("www.")) host = host.removePrefix("www.")
            if (!host.contains('.')) return null

            path = path.lowercase(Locale.ROOT)
                .replace(Regex("/+"), "/")
                .trimEnd('/')

            val keptParams = query
                .split('&')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.lowercase(Locale.ROOT) }
                .filter { param ->
                    val key = param.substringBefore('=')
                    key !in TRACKING_QUERY_PARAMS && !key.startsWith("utm_")
                }
                .sorted()

            return buildString {
                append("https://").append(host).append(path)
                keptParams.forEachIndexed { index, param ->
                    append(if (index == 0) '?' else '&').append(param)
                }
            }
        }

        private fun normalizeSource(value: String?): String? =
            value
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
            if (normalized in PLACEHOLDER_VALUES) return null
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
            removeTrailingStateTokens(tokens, state)
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

        /**
         * Removes a trailing state component ("..., Austin, TX" / "..., Texas") without mangling
         * street names that merely resemble state names: "123 Maine St" and "123 Washington Ave"
         * keep their street identity because the state-name equivalence is applied only to the
         * *trailing* tokens being compared, never to the whole address.
         */
        private fun removeTrailingStateTokens(tokens: MutableList<String>, state: List<String>) {
            if (state.isEmpty() || tokens.size <= state.size) return
            val start = tokens.size - state.size
            val tail = tokens.subList(start, tokens.size).map { STATE_NAME_TO_CODE[it] ?: it }
            val expected = state.map { STATE_NAME_TO_CODE[it] ?: it }
            if (tail == expected) {
                repeat(state.size) { tokens.removeAt(tokens.lastIndex) }
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
                        min(current[secondIndex] + 1, previous[secondIndex] + 1),
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

        private fun coordinatesOf(identity: SourcePropertyIdentity): Coordinates? {
            val latitude = identity.latitude ?: return null
            val longitude = identity.longitude ?: return null
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            // Several feeds use the Null Island coordinate as a missing-value sentinel.
            if (latitude == 0.0 && longitude == 0.0) return null
            return Coordinates(latitude, longitude)
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

        /**
         * Street-suffix and directional equivalents. State names are deliberately NOT part of this
         * table: folding them here would rewrite street names such as "Maine St" or "Washington
         * Ave" into "me st" / "wa ave" and could silently merge two different addresses.
         */
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
        }
    }
}
