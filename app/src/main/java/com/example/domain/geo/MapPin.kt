package com.example.domain.geo

/** What a pin represents on the property map. */
enum class MapPinRole {
    /** The subject property currently being analyzed. */
    TARGET,

    /** A comparable sale/listing used to value the target. */
    COMPARABLE,

    /** Any other property in the pipeline (search results, saved deals, ...). */
    PROPERTY
}

/** Visual emphasis hint, derived deterministically from domain data (never from the renderer). */
enum class MapPinEmphasis { PRIMARY, HIGHLIGHTED, NORMAL, MUTED }

/**
 * SDK independent description of a single map marker.
 *
 * [id] must be stable (property id) because every ordering in this package breaks ties by id to
 * stay deterministic.
 */
data class MapPin(
    val id: String,
    val point: GeoPoint,
    val role: MapPinRole = MapPinRole.PROPERTY,
    val label: String = "",
    val subLabel: String = "",
    val priceUsd: Double? = null,
    val dealScore: Int? = null,
    val emphasis: MapPinEmphasis = MapPinEmphasis.NORMAL,
    /** Free-form, renderer agnostic extras (e.g. "sourceType" -> "OFF_MARKET"). */
    val attributes: Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "MapPin id must not be blank" }
        require(dealScore == null || dealScore in 0..100) { "dealScore must be 0..100" }
    }
}

/** A pin paired with its measured distance from a reference point. */
data class MapPinDistance(
    val pin: MapPin,
    val distanceMiles: Double
)
