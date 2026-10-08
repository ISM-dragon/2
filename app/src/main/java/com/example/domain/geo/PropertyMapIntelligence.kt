package com.example.domain.geo

/** Tunables for a map scene. All defaults are explicit so output stays reproducible. */
data class MapIntelligenceConfig(
    val compRadiusMiles: Double = 1.0,
    val maxComparables: Int = 25,
    val clusterCellMiles: Double = 0.25,
    val distanceBandsMiles: List<Double> = listOf(0.25, 0.5, 1.0, 3.0),
    val viewportWidthPx: Double = 1024.0,
    val viewportHeightPx: Double = 1024.0,
    val viewportPaddingFraction: Double = 0.12
) {
    init {
        require(compRadiusMiles > 0.0 && compRadiusMiles.isFinite()) { "compRadiusMiles must be > 0" }
        require(maxComparables >= 0) { "maxComparables must be >= 0" }
    }
}

/**
 * Fully computed, renderer agnostic map state: what to pin, how to group it and where to look.
 */
data class PropertyMapScene(
    val target: MapPin?,
    val comparables: List<MapPinDistance>,
    val clusters: List<GeoCluster>,
    val distanceBands: List<GeoDistanceBand>,
    val bounds: GeoBoundingBox?,
    val viewport: MapViewport?
) {
    val isEmpty: Boolean get() = target == null && comparables.isEmpty()

    /** Every rendered pin, target first, then comparables by distance then id. */
    val allPins: List<MapPin>
        get() = listOfNotNull(target) + comparables.map { it.pin }
}

/**
 * Composes the geo primitives into a single deterministic scene for a subject property and its
 * comparables. No map SDK, no I/O, no randomness.
 */
object PropertyMapIntelligence {

    fun buildScene(
        target: MapPin?,
        candidates: Collection<MapPin>,
        config: MapIntelligenceConfig = MapIntelligenceConfig()
    ): PropertyMapScene {
        if (target == null) {
            // Without a subject there is no radius centre; still render candidates coherently.
            val pins = candidates.sortedBy { it.id }
            val bounds = GeoBoundingBox.of(pins.map { it.point })
            return PropertyMapScene(
                target = null,
                comparables = emptyList(),
                clusters = GeoClusterer.cluster(pins, config.clusterCellMiles),
                distanceBands = emptyList(),
                bounds = bounds,
                viewport = bounds?.let {
                    ViewportFitter.fitBounds(
                        it,
                        config.viewportWidthPx,
                        config.viewportHeightPx,
                        config.viewportPaddingFraction
                    )
                }
            )
        }

        val comps = GeoQueryEngine.comparablesWithin(
            target = target,
            candidates = candidates,
            radiusMiles = config.compRadiusMiles,
            maxResults = config.maxComparables
        )
        val pins = listOf(target) + comps.map { it.pin }
        val bounds = GeoBoundingBox.of(pins.map { it.point })
        return PropertyMapScene(
            target = target,
            comparables = comps,
            clusters = GeoClusterer.cluster(pins, config.clusterCellMiles),
            distanceBands = GeoClusterer.groupByDistanceBands(
                center = target.point,
                pins = comps.map { it.pin },
                bandEdgesMiles = config.distanceBandsMiles
            ),
            bounds = bounds,
            viewport = bounds?.let {
                ViewportFitter.fitBounds(
                    it,
                    config.viewportWidthPx,
                    config.viewportHeightPx,
                    config.viewportPaddingFraction
                )
            }
        )
    }
}
