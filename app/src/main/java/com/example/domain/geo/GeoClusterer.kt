package com.example.domain.geo

import kotlin.math.abs
import kotlin.math.floor

/** A deterministic group of pins that a renderer may draw as one marker. */
data class GeoCluster(
    /** Stable key derived from the grid cell, e.g. "c:12:-41". */
    val key: String,
    /** Arithmetic centroid of the member pins (never a fabricated location). */
    val centroid: GeoPoint,
    /** Members ordered by pin id. */
    val pins: List<MapPin>,
    val bounds: GeoBoundingBox
) {
    val size: Int get() = pins.size
    val isSingle: Boolean get() = pins.size == 1

    /** Representative pin: the most emphasized member, ties broken by id. */
    val representative: MapPin
        get() = pins.minWithOrNull(
            compareBy<MapPin> { it.emphasis.ordinal }.thenBy { it.id }
        ) ?: pins.first()
}

/**
 * Grid based clustering in degrees. Chosen over centroid-chaining algorithms because the result
 * depends only on coordinates and cell size - never on input ordering or iteration order - which
 * keeps map output reproducible and testable.
 */
object GeoClusterer {

    /**
     * @param cellSizeMiles approximate cell edge length in miles; must be > 0.
     * @return clusters ordered by descending size, then by first pin id.
     */
    fun cluster(pins: Collection<MapPin>, cellSizeMiles: Double): List<GeoCluster> {
        require(cellSizeMiles > 0.0 && cellSizeMiles.isFinite()) { "cellSizeMiles must be > 0" }
        if (pins.isEmpty()) return emptyList()

        val latStep = cellSizeMiles / Geo.MILES_PER_DEGREE_LATITUDE
        val buckets = LinkedHashMap<String, MutableList<MapPin>>()
        for (pin in pins) {
            val latIndex = floor(pin.point.latitude / latStep).toLong()
            // Longitude cells widen towards the poles so that cells stay ~square in miles.
            val lngStep = GeoBoundingBox.longitudeDeltaForMiles(
                cellSizeMiles,
                atLatitudeDegrees = (latIndex.toDouble() + 0.5) * latStep
            ).coerceAtLeast(1e-9)
            val lngIndex = floor(pin.point.longitude / lngStep).toLong()
            buckets.getOrPut("c:$latIndex:$lngIndex") { mutableListOf() }.add(pin)
        }

        return buckets.entries
            .map { (key, members) ->
                val ordered = members.sortedBy { it.id }
                GeoCluster(
                    key = key,
                    centroid = centroidOf(ordered.map { it.point }),
                    pins = ordered,
                    bounds = GeoBoundingBox.of(ordered.map { it.point })!!
                )
            }
            .sortedWith(compareByDescending<GeoCluster> { it.size }.thenBy { it.pins.first().id })
    }

    /**
     * Groups comparables by distance band from a target, e.g. [0.5, 1.0, 3.0] produces
     * "0 - 0.5 mi", "0.5 - 1 mi", "1 - 3 mi" and "3+ mi". Bands are emitted in ascending order and
     * empty bands are preserved so UI tables stay stable.
     */
    fun groupByDistanceBands(
        center: GeoPoint,
        pins: Collection<MapPin>,
        bandEdgesMiles: List<Double>
    ): List<GeoDistanceBand> {
        require(bandEdgesMiles.isNotEmpty()) { "bandEdgesMiles must not be empty" }
        require(bandEdgesMiles.all { it.isFinite() && it > 0.0 }) { "band edges must be > 0" }
        require(bandEdgesMiles.zipWithNext().all { (a, b) -> a < b }) {
            "band edges must be strictly ascending"
        }

        val measured = GeoQueryEngine.measureFrom(center, pins)
        val bands = ArrayList<GeoDistanceBand>(bandEdgesMiles.size + 1)
        var lower = 0.0
        for (edge in bandEdgesMiles) {
            val lo = lower
            // First band is closed on both ends ([0, edge]); later bands are (lo, edge].
            bands += GeoDistanceBand(
                minMiles = lo,
                maxMiles = edge,
                entries = measured.filter { m ->
                    val aboveLower = if (lo == 0.0) m.distanceMiles >= 0.0 else m.distanceMiles > lo
                    aboveLower && m.distanceMiles <= edge
                }
            )
            lower = edge
        }
        val last = lower
        bands += GeoDistanceBand(
            minMiles = last,
            maxMiles = null,
            entries = measured.filter { it.distanceMiles > last }
        )
        return bands
    }

    private fun centroidOf(points: List<GeoPoint>): GeoPoint {
        if (points.size == 1) return points.first()
        val lat = points.sumOf { it.latitude } / points.size
        val anchor = points.first().longitude
        // Average longitudes relative to an anchor so clusters spanning ±180° stay correct.
        val lngOffset = points.sumOf { shortestLongitudeDelta(anchor, it.longitude) } / points.size
        return GeoPoint(lat, GeoBoundingBox.normalizeLongitude(anchor + lngOffset))
    }

    private fun shortestLongitudeDelta(from: Double, to: Double): Double {
        var d = to - from
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return if (abs(d) == 180.0) 180.0 else d
    }
}

/** One distance band of comparables around a target. [maxMiles] is `null` for the open last band. */
data class GeoDistanceBand(
    val minMiles: Double,
    val maxMiles: Double?,
    val entries: List<MapPinDistance>
) {
    val size: Int get() = entries.size
}
