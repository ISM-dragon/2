package com.example.domain.geo

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * SDK independent camera description: where to look and how far out.
 * [zoom] follows the universal Web-Mercator/XYZ tile convention (0 = whole world).
 */
data class MapViewport(
    val center: GeoPoint,
    val zoom: Double,
    val bounds: GeoBoundingBox?
) {
    init {
        require(zoom.isFinite() && zoom >= 0.0) { "zoom must be finite and >= 0" }
    }
}

/**
 * Computes viewports from pin sets. Pure math on a Web-Mercator projection; it produces plain
 * numbers that any map SDK adapter can consume.
 */
object ViewportFitter {

    const val TILE_SIZE_PX: Double = 256.0
    const val MAX_ZOOM: Double = 21.0

    /** Zoom used when a single point (or a degenerate zero-span box) is fitted. */
    const val SINGLE_POINT_ZOOM: Double = 15.0

    /**
     * Fits [pins] into a viewport of [viewportWidthPx] x [viewportHeightPx].
     *
     * @param paddingFraction fraction of each axis reserved as margin, in [0, 0.45).
     * @return `null` when [pins] is empty - callers decide what to show, nothing is fabricated.
     */
    fun fit(
        pins: Collection<MapPin>,
        viewportWidthPx: Double = 1024.0,
        viewportHeightPx: Double = 1024.0,
        paddingFraction: Double = 0.12,
        maxZoom: Double = MAX_ZOOM
    ): MapViewport? {
        val box = GeoBoundingBox.of(pins.map { it.point }) ?: return null
        return fitBounds(box, viewportWidthPx, viewportHeightPx, paddingFraction, maxZoom)
    }

    /** Fits an explicit bounding box. */
    fun fitBounds(
        box: GeoBoundingBox,
        viewportWidthPx: Double = 1024.0,
        viewportHeightPx: Double = 1024.0,
        paddingFraction: Double = 0.12,
        maxZoom: Double = MAX_ZOOM
    ): MapViewport {
        require(viewportWidthPx > 0 && viewportHeightPx > 0) { "viewport size must be > 0" }
        require(paddingFraction >= 0.0 && paddingFraction < 0.45) {
            "paddingFraction must be in [0, 0.45)"
        }
        require(maxZoom > 0 && maxZoom.isFinite()) { "maxZoom must be > 0" }

        val usableWidth = viewportWidthPx * (1.0 - 2.0 * paddingFraction)
        val usableHeight = viewportHeightPx * (1.0 - 2.0 * paddingFraction)

        val latFraction = (mercatorY(box.north) - mercatorY(box.south)).let { max(it, 0.0) }
        val lngFraction = box.longitudeSpanDegrees / 360.0

        val latZoom = zoomFor(usableHeight, latFraction)
        val lngZoom = zoomFor(usableWidth, lngFraction)

        val zoom = when {
            latZoom == null && lngZoom == null -> SINGLE_POINT_ZOOM
            latZoom == null -> lngZoom!!
            lngZoom == null -> latZoom
            else -> min(latZoom, lngZoom)
        }
        return MapViewport(
            center = box.center,
            zoom = min(maxZoom, max(0.0, zoom)),
            bounds = box
        )
    }

    /** Viewport centered on a target that keeps a [radiusMiles] circle fully visible. */
    fun fitRadius(
        center: GeoPoint,
        radiusMiles: Double,
        viewportWidthPx: Double = 1024.0,
        viewportHeightPx: Double = 1024.0,
        paddingFraction: Double = 0.12,
        maxZoom: Double = MAX_ZOOM
    ): MapViewport {
        require(radiusMiles >= 0.0 && radiusMiles.isFinite()) { "radiusMiles must be >= 0" }
        val box = GeoBoundingBox.around(center, radiusMiles)
        val fitted = fitBounds(box, viewportWidthPx, viewportHeightPx, paddingFraction, maxZoom)
        return fitted.copy(center = center)
    }

    /** Normalized Web-Mercator Y in [0, 1] (0 = north pole side). */
    fun mercatorY(latitudeDegrees: Double): Double {
        val clamped = latitudeDegrees.coerceIn(-85.05112878, 85.05112878)
        val s = sin(Math.toRadians(clamped))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private fun zoomFor(availablePx: Double, fraction: Double): Double? {
        if (fraction <= 0.0) return null
        val zoom = ln(availablePx / TILE_SIZE_PX / fraction) / ln(2.0)
        return if (zoom.isFinite()) zoom else null
    }

    /** Integer zoom level a renderer can hand to a tile based SDK. */
    fun integerZoom(viewport: MapViewport): Int = floor(viewport.zoom).toInt().coerceAtLeast(0)
}
