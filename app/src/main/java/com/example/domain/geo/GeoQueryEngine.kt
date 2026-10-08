package com.example.domain.geo

/**
 * Deterministic radius / bounding-box queries over map pins.
 *
 * Ordering contract for every result list: ascending distance, then ascending pin id. This makes
 * output stable across runs and independent of the input collection's iteration order.
 */
object GeoQueryEngine {

    private val BY_DISTANCE_THEN_ID: Comparator<MapPinDistance> =
        compareBy<MapPinDistance> { it.distanceMiles }.thenBy { it.pin.id }

    /** Distance from [center] to every pin, deterministically ordered. */
    fun measureFrom(center: GeoPoint, pins: Collection<MapPin>): List<MapPinDistance> =
        pins.map { MapPinDistance(it, center.distanceMilesTo(it.point)) }
            .sortedWith(BY_DISTANCE_THEN_ID)

    /**
     * Pins whose great-circle distance from [center] is `<= radiusMiles` (inclusive boundary).
     * A bounding box pre-filter is applied first; it is conservative, so it never drops a pin that
     * the exact haversine test would keep.
     */
    fun withinRadius(
        center: GeoPoint,
        radiusMiles: Double,
        pins: Collection<MapPin>
    ): List<MapPinDistance> {
        require(radiusMiles.isFinite() && radiusMiles >= 0.0) { "radiusMiles must be >= 0" }
        if (pins.isEmpty()) return emptyList()
        val box = GeoBoundingBox.around(center, radiusMiles)
        return pins.asSequence()
            .filter { it.point in box }
            .map { MapPinDistance(it, center.distanceMilesTo(it.point)) }
            .filter { it.distanceMiles <= radiusMiles }
            .sortedWith(BY_DISTANCE_THEN_ID)
            .toList()
    }

    /** The [limit] closest pins to [center], deterministically ordered. */
    fun nearest(center: GeoPoint, limit: Int, pins: Collection<MapPin>): List<MapPinDistance> {
        require(limit >= 0) { "limit must be >= 0" }
        if (limit == 0 || pins.isEmpty()) return emptyList()
        return measureFrom(center, pins).take(limit)
    }

    /** Pins inside [box] (inclusive edges), ordered by id for determinism. */
    fun withinBounds(box: GeoBoundingBox, pins: Collection<MapPin>): List<MapPin> =
        pins.filter { it.point in box }.sortedBy { it.id }

    /**
     * Comparables for a target, filtered by radius and capped at [maxResults].
     * The target itself (matched by id) is never returned as its own comp.
     */
    fun comparablesWithin(
        target: MapPin,
        candidates: Collection<MapPin>,
        radiusMiles: Double,
        maxResults: Int = Int.MAX_VALUE
    ): List<MapPinDistance> {
        require(maxResults >= 0) { "maxResults must be >= 0" }
        if (maxResults == 0) return emptyList()
        val usable = candidates.filter { it.id != target.id }
        val within = withinRadius(target.point, radiusMiles, usable)
        return if (maxResults == Int.MAX_VALUE) within else within.take(maxResults)
    }
}
