package com.example.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyMapIntelligenceTest {

    private fun pin(id: String, lat: Double, lng: Double, role: MapPinRole = MapPinRole.COMPARABLE) =
        MapPin(id = id, point = GeoPoint(lat, lng), role = role)

    private val target = pin("target", 30.2672, -97.7431, MapPinRole.TARGET)
    private val candidates = listOf(
        pin("round-rock", 30.5083, -97.6789),  // ~17.09 mi - outside 1 mi radius
        pin("city-hall", 30.2652, -97.7474),   // ~0.29 mi
        pin("zilker", 30.2669, -97.7729),      // ~1.78 mi - outside 1 mi radius
        pin("ut-tower", 30.2862, -97.7394)     // ~1.33 mi - outside 1 mi radius
    )

    @Test
    fun emptyTargetAndEmptyCandidatesProduceAnEmptyScene() {
        val scene = PropertyMapIntelligence.buildScene(null, emptyList())
        assertTrue(scene.isEmpty)
        assertTrue(scene.comparables.isEmpty())
        assertTrue(scene.clusters.isEmpty())
        assertNull(scene.bounds)
        assertNull(scene.viewport)
    }

    @Test
    fun sceneWithoutTargetStillClustersAndFitsCandidates() {
        val scene = PropertyMapIntelligence.buildScene(null, candidates)
        assertNull(scene.target)
        assertTrue(scene.comparables.isEmpty())
        assertEquals(candidates.size, scene.clusters.sumOf { it.size })
        assertTrue(candidates.all { it.point in scene.bounds!! })
        assertEquals(scene.bounds, scene.viewport!!.bounds)
    }

    @Test
    fun comparablesAreRadiusFilteredAndOrdered() {
        val scene = PropertyMapIntelligence.buildScene(
            target,
            candidates,
            MapIntelligenceConfig(compRadiusMiles = 1.5)
        )
        assertEquals(listOf("city-hall", "ut-tower"), scene.comparables.map { it.pin.id })
        assertEquals(listOf("target", "city-hall", "ut-tower"), scene.allPins.map { it.id })
    }

    @Test
    fun maxComparablesCapsTheClosestOnes() {
        val scene = PropertyMapIntelligence.buildScene(
            target,
            candidates,
            MapIntelligenceConfig(compRadiusMiles = 25.0, maxComparables = 2)
        )
        assertEquals(listOf("city-hall", "ut-tower"), scene.comparables.map { it.pin.id })
    }

    @Test
    fun sceneIsDeterministicAcrossInputOrderings() {
        val a = PropertyMapIntelligence.buildScene(target, candidates)
        val b = PropertyMapIntelligence.buildScene(target, candidates.reversed())
        assertEquals(a, b)
    }

    @Test
    fun targetWithNoNearbyCompsStillRendersTheTarget() {
        val scene = PropertyMapIntelligence.buildScene(
            target,
            emptyList(),
            MapIntelligenceConfig(compRadiusMiles = 1.0)
        )
        assertTrue(!scene.isEmpty)
        assertEquals(listOf("target"), scene.allPins.map { it.id })
        assertEquals(target.point, scene.viewport!!.center)
        assertTrue(scene.distanceBands.all { it.entries.isEmpty() })
    }

    @Test
    fun boundsAndClustersCoverEveryRenderedPin() {
        val scene = PropertyMapIntelligence.buildScene(
            target,
            candidates,
            MapIntelligenceConfig(compRadiusMiles = 25.0)
        )
        assertEquals(scene.allPins.size, scene.clusters.sumOf { it.size })
        assertTrue(scene.allPins.all { it.point in scene.bounds!! })
    }

    @Test
    fun distanceBandsPartitionTheComparables() {
        val scene = PropertyMapIntelligence.buildScene(
            target,
            candidates,
            MapIntelligenceConfig(compRadiusMiles = 25.0)
        )
        assertEquals(scene.comparables.size, scene.distanceBands.sumOf { it.size })
        assertEquals(
            listOf("round-rock"),
            scene.distanceBands.last().entries.map { it.pin.id }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidConfigIsRejected() {
        MapIntelligenceConfig(compRadiusMiles = 0.0)
    }
}
