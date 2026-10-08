package com.example.domain.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic geospatial primitives for property/comp map intelligence.
 *
 * Design rules:
 *  - Pure Kotlin, no Android and no map SDK types. Anything rendering related lives in the UI
 *    adapter layer ([com.example.domain.geo.MapViewport] is expressed in plain lat/lng + zoom).
 *  - Every operation is deterministic: identical input always produces identical output, including
 *    ordering (ties are broken by a stable identifier).
 *  - No fabricated geography: nothing here invents coordinates. Invalid or missing coordinates are
 *    rejected via [GeoPoint.orNull] / [GeoPoint.isValidCoordinate] instead of being defaulted.
 */
object Geo {
    /** Mean Earth radius in statute miles (IUGG mean radius 6371.0088 km / 1.609344). */
    const val EARTH_RADIUS_MILES: Double = 3958.7613

    /** Miles per degree of latitude (constant on a spherical Earth). */
    const val MILES_PER_DEGREE_LATITUDE: Double = Math.PI * EARTH_RADIUS_MILES / 180.0

    const val MIN_LATITUDE: Double = -90.0
    const val MAX_LATITUDE: Double = 90.0
    const val MIN_LONGITUDE: Double = -180.0
    const val MAX_LONGITUDE: Double = 180.0
}

/** An immutable WGS84 coordinate. Construction validates the range so no invalid pin can exist. */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    init {
        require(isValidCoordinate(latitude, longitude)) {
            "Invalid coordinate: lat=$latitude lng=$longitude"
        }
    }

    /** Great-circle distance in statute miles using the numerically stable haversine formula. */
    fun distanceMilesTo(other: GeoPoint): Double {
        if (latitude == other.latitude && longitude == other.longitude) return 0.0
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(other.longitude - longitude)
        val sinLat = sin(dLat / 2.0)
        val sinLng = sin(dLng / 2.0)
        val a = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLng * sinLng
        val c = 2.0 * asin(min(1.0, sqrt(a)))
        return Geo.EARTH_RADIUS_MILES * c
    }

    /** Initial bearing in degrees [0, 360) from this point towards [other]. */
    fun initialBearingDegreesTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLng = Math.toRadians(other.longitude - longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    companion object {
        fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in Geo.MIN_LATITUDE..Geo.MAX_LATITUDE &&
                longitude in Geo.MIN_LONGITUDE..Geo.MAX_LONGITUDE

        /**
         * Null-safe factory. Returns `null` for out-of-range, NaN or "null island" (0,0)
         * placeholder coordinates rather than fabricating a location.
         */
        fun orNull(latitude: Double, longitude: Double): GeoPoint? = when {
            !isValidCoordinate(latitude, longitude) -> null
            latitude == 0.0 && longitude == 0.0 -> null
            else -> GeoPoint(latitude, longitude)
        }
    }
}

/**
 * Axis aligned lat/lng rectangle. [west] may be greater than [east] when the box crosses the
 * ±180° antimeridian; [containsLongitude] handles both cases.
 */
data class GeoBoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite()) {
            "Bounding box must be finite"
        }
        require(south <= north) { "south ($south) must be <= north ($north)" }
        require(south >= Geo.MIN_LATITUDE && north <= Geo.MAX_LATITUDE) { "latitude out of range" }
        require(west >= Geo.MIN_LONGITUDE && west <= Geo.MAX_LONGITUDE) { "west out of range" }
        require(east >= Geo.MIN_LONGITUDE && east <= Geo.MAX_LONGITUDE) { "east out of range" }
    }

    val crossesAntimeridian: Boolean get() = west > east

    val latitudeSpanDegrees: Double get() = north - south

    val longitudeSpanDegrees: Double
        get() = if (crossesAntimeridian) (360.0 - west + east) else (east - west)

    val center: GeoPoint
        get() {
            val lat = (south + north) / 2.0
            val lng = if (!crossesAntimeridian) {
                (west + east) / 2.0
            } else {
                Companion.normalizeLongitude(west + longitudeSpanDegrees / 2.0)
            }
            return GeoPoint(lat, lng)
        }

    fun containsLatitude(latitude: Double): Boolean = latitude in south..north

    fun containsLongitude(longitude: Double): Boolean =
        if (crossesAntimeridian) longitude >= west || longitude <= east
        else longitude in west..east

    operator fun contains(point: GeoPoint): Boolean =
        containsLatitude(point.latitude) && containsLongitude(point.longitude)

    /** Grows the box by [miles] on every side, clamped to valid latitudes. */
    fun expandedByMiles(miles: Double): GeoBoundingBox {
        require(miles >= 0.0 && miles.isFinite()) { "miles must be >= 0" }
        if (miles == 0.0) return this
        val dLat = miles / Geo.MILES_PER_DEGREE_LATITUDE
        val newSouth = max(Geo.MIN_LATITUDE, south - dLat)
        val newNorth = min(Geo.MAX_LATITUDE, north + dLat)
        val widestLat = max(abs(newSouth), abs(newNorth))
        val dLng = longitudeDeltaForMiles(miles, widestLat)
        if (dLng >= 180.0) {
            return GeoBoundingBox(newSouth, Geo.MIN_LONGITUDE, newNorth, Geo.MAX_LONGITUDE)
        }
        return GeoBoundingBox(
            south = newSouth,
            west = Companion.normalizeLongitude(west - dLng),
            north = newNorth,
            east = Companion.normalizeLongitude(east + dLng)
        )
    }

    companion object {
        /** Smallest box containing all [points], or `null` for an empty input. */
        fun of(points: Collection<GeoPoint>): GeoBoundingBox? {
            if (points.isEmpty()) return null
            var south = Geo.MAX_LATITUDE
            var north = Geo.MIN_LATITUDE
            var west = Geo.MAX_LONGITUDE
            var east = Geo.MIN_LONGITUDE
            for (p in points) {
                south = min(south, p.latitude)
                north = max(north, p.latitude)
                west = min(west, p.longitude)
                east = max(east, p.longitude)
            }
            return GeoBoundingBox(south, west, north, east)
        }

        /**
         * Conservative square box around [center] with half-height/half-width of [radiusMiles].
         * Used as a cheap pre-filter before exact haversine distance checks.
         */
        fun around(center: GeoPoint, radiusMiles: Double): GeoBoundingBox {
            require(radiusMiles >= 0.0 && radiusMiles.isFinite()) { "radiusMiles must be >= 0" }
            val dLat = radiusMiles / Geo.MILES_PER_DEGREE_LATITUDE
            val south = max(Geo.MIN_LATITUDE, center.latitude - dLat)
            val north = min(Geo.MAX_LATITUDE, center.latitude + dLat)
            val widestLat = max(abs(south), abs(north))
            val dLng = longitudeDeltaForMiles(radiusMiles, widestLat)
            if (dLng >= 180.0) {
                return GeoBoundingBox(south, Geo.MIN_LONGITUDE, north, Geo.MAX_LONGITUDE)
            }
            return GeoBoundingBox(
                south = south,
                west = normalizeLongitude(center.longitude - dLng),
                north = north,
                east = normalizeLongitude(center.longitude + dLng)
            )
        }

        /** Longitude degrees covered by [miles] at the given latitude; 180 near the poles. */
        fun longitudeDeltaForMiles(miles: Double, atLatitudeDegrees: Double): Double {
            val cosLat = cos(Math.toRadians(atLatitudeDegrees))
            if (cosLat <= 1e-9) return 180.0
            val delta = miles / (Geo.MILES_PER_DEGREE_LATITUDE * cosLat)
            return min(180.0, delta)
        }

        /** Wraps a longitude into [-180, 180]. */
        fun normalizeLongitude(longitude: Double): Double {
            if (longitude in Geo.MIN_LONGITUDE..Geo.MAX_LONGITUDE) return longitude
            var lng = (longitude + 180.0) % 360.0
            if (lng < 0) lng += 360.0
            return lng - 180.0
        }
    }
}
