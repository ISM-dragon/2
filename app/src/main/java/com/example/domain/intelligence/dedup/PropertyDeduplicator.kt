package com.example.domain.intelligence.dedup

import com.example.data.local.dao.PropertyDao
import com.example.data.local.entity.PropertyEntity
import com.example.domain.intelligence.model.CanonicalProperty
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class MatchResult(
    val isMatch: Boolean,
    val existingProperty: PropertyEntity? = null,
    val matchType: String? = null // "APN", "EXACT_ADDRESS", "COORDINATES", "FUZZY_ADDRESS", "NONE"
)

class PropertyDeduplicator(
    private val propertyDao: PropertyDao
) {
    suspend fun findExistingMatch(canonical: CanonicalProperty): MatchResult {
        val allProps = propertyDao.getAllPropertiesList()
        val normalizedCandidateAddress = normalizeAddress(canonical.address)

        // 1. Match by exact normalized address
        for (prop in allProps) {
            if (normalizeAddress(prop.address) == normalizedCandidateAddress) {
                return MatchResult(true, prop, "EXACT_ADDRESS")
            }
        }

        // 2. Match by coordinates proximity (within 35 meters)
        if (canonical.latitude != null && canonical.longitude != null) {
            for (prop in allProps) {
                val distanceMeters = calculateHaversineDistanceMeters(
                    canonical.latitude, canonical.longitude,
                    prop.latitude, prop.longitude
                )
                if (distanceMeters <= 35.0) {
                    return MatchResult(true, prop, "COORDINATES")
                }
            }
        }

        // 3. Fallback fuzzy address matching (matching street number, street name, and zip code)
        for (prop in allProps) {
            if (isFuzzyAddressMatch(prop.address, canonical.address, prop.zipCode, canonical.zipCode)) {
                return MatchResult(true, prop, "FUZZY_ADDRESS")
            }
        }

        return MatchResult(false, null, "NONE")
    }

    private fun normalizeAddress(raw: String): String {
        return raw.lowercase()
            .replace(".", "")
            .replace(",", "")
            .replace(Regex("""\bstreet\b"""), "st")
            .replace(Regex("""\bavenue\b"""), "ave")
            .replace(Regex("""\bboulevard\b"""), "blvd")
            .replace(Regex("""\broad\b"""), "rd")
            .replace(Regex("""\bdrive\b"""), "dr")
            .replace(Regex("""\blane\b"""), "ln")
            .replace(Regex("""\bcourt\b"""), "ct")
            .replace(Regex("""\bnorth\b"""), "n")
            .replace(Regex("""\bsouth\b"""), "s")
            .replace(Regex("""\beast\b"""), "e")
            .replace(Regex("""\bwest\b"""), "w")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isFuzzyAddressMatch(addr1: String, addr2: String, zip1: String, zip2: String): Boolean {
        if (zip1.isNotBlank() && zip2.isNotBlank() && zip1.take(5) != zip2.take(5)) {
            return false
        }
        val n1 = normalizeAddress(addr1)
        val n2 = normalizeAddress(addr2)

        val tokens1 = n1.split(" ").filter { it.length > 1 }.toSet()
        val tokens2 = n2.split(" ").filter { it.length > 1 }.toSet()

        if (tokens1.isEmpty() || tokens2.isEmpty()) return false
        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        val jaccard = intersection.toDouble() / union.toDouble()

        return jaccard >= 0.75
    }

    private fun calculateHaversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
