package com.example.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoQueryEngineTest {

    // Real Austin, TX landmarks (published coordinates).
    private val downtownAustin = GeoPoint(30.2672, -97.7431)

    private fun pin(id: String, lat: Double, lng: Double) =
        MapPin(id = id, point = GeoPoint(lat, lng), role = MapPinRole.COMPARABLE)

    private val cityHall = pin("city-hall", 30.2652, -97.7474)       // ~0.29 mi
    private val utTower = pin("ut-tower", 30.2862, -97.7394)         // ~1.33 mi
    private val zilker = pin("zilker", 30.2669, -97.7729)            // ~1.78 mi
    private val mueller = pin("mueller", 30.2991, -97.7051)          // ~3.16 mi
    private val roundRock = pin("round-rock", 30.5083, -97.6789)     // ~17.09 mi
    private val houston = pin("houston", 29.7604, -95.3698)          // ~146.24 mi

    private val all = listOf(houston, roundRock, mueller, zilker, utTower, cityHall)

    @Test
    fun emptyDatasetsReturnEmptyResults() {
        assertTrue(GeoQueryEngine.withinRadius(downtownAustin, 5.0, emptyList()).isEmpty())
        assertTrue(GeoQueryEngine.measureFrom(downtownAustin, emptyList()).isEmpty())
        assertTrue(GeoQueryEngine.nearest(downtownAustin, 10, emptyList()).isEmpty())
        assertTrue(GeoQueryEngine.withinBounds(GeoBoundingBox.around(downtownAustin, 1.0), emptyList()).isEmpty())
    }

    @Test
    fun radiusFilterKeepsOnlyPinsInsideTheCircle() {
        val ids = GeoQueryEngine.withinRadius(downtownAustin, 2.0, all).map { it.pin.id }
        assertEquals(listOf("city-hall", "ut-tower", "zilker"), ids)
    }

    @Test
    fun resultsAreOrderedByAscendingDistance() {
        val distances = GeoQueryEngine.withinRadius(downtownAustin, 20.0, all).map { it.distanceMiles }
        assertEquals(distances.sorted(), distances)
        assertEquals(0.2914, distances.first(), 0.01)
    }

    @Test
    fun orderingIsDeterministicAndIndependentOfInputOrder() {
        val a = GeoQueryEngine.withinRadius(downtownAustin, 200.0, all).map { it.pin.id }
        val b = GeoQueryEngine.withinRadius(downtownAustin, 200.0, all.reversed()).map { it.pin.id }
        val c = GeoQueryEngine.withinRadius(downtownAustin, 200.0, all.shuffled()).map { it.pin.id }
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun equalDistanceTiesAreBrokenByPinId() {
        val north = pin("zz-north", 30.2772, -97.7431)
        val south = pin("aa-south", 30.2572, -97.7431)
        val result = GeoQueryEngine.withinRadius(downtownAustin, 5.0, listOf(north, south))
        assertEquals(listOf("aa-south", "zz-north"), result.map { it.pin.id })
        assertEquals(result[0].distanceMiles, result[1].distanceMiles, 1e-9)
    }

    @Test
    fun radiusBoundaryIsInclusive() {
        val exact = downtownAustin.distanceMilesTo(utTower.point)
        assertEquals(1, GeoQueryEngine.withinRadius(downtownAustin, exact, listOf(utTower)).size)
        assertEquals(
            0,
            GeoQueryEngine.withinRadius(downtownAustin, exact - 1e-6, listOf(utTower)).size
        )
    }

    @Test
    fun zeroRadiusOnlyMatchesTheExactCoordinate() {
        val same = pin("same", downtownAustin.latitude, downtownAustin.longitude)
        val result = GeoQueryEngine.withinRadius(downtownAustin, 0.0, listOf(same, cityHall))
        assertEquals(listOf("same"), result.map { it.pin.id })
        assertEquals(0.0, result.first().distanceMiles, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRadiusIsRejected() {
        GeoQueryEngine.withinRadius(downtownAustin, -1.0, all)
    }

    @Test
    fun nearestRespectsLimitAndHandlesOversizedLimits() {
        assertEquals(
            listOf("city-hall", "ut-tower"),
            GeoQueryEngine.nearest(downtownAustin, 2, all).map { it.pin.id }
        )
        assertEquals(all.size, GeoQueryEngine.nearest(downtownAustin, 99, all).size)
        assertTrue(GeoQueryEngine.nearest(downtownAustin, 0, all).isEmpty())
    }

    @Test
    fun withinBoundsUsesInclusiveEdgesAndStableIdOrdering() {
        val box = GeoBoundingBox.of(listOf(cityHall.point, mueller.point))!!
        val ids = GeoQueryEngine.withinBounds(box, all).map { it.id }
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.contains("city-hall"))
        assertTrue(ids.contains("mueller"))
        assertTrue(!ids.contains("houston"))
    }

    @Test
    fun comparablesExcludeTheTargetItselfAndRespectMaxResults() {
        val target = MapPin("city-hall", cityHall.point, MapPinRole.TARGET)
        val comps = GeoQueryEngine.comparablesWithin(target, all, radiusMiles = 5.0)
        assertTrue(comps.none { it.pin.id == "city-hall" })
        assertEquals(
            2,
            GeoQueryEngine.comparablesWithin(target, all, radiusMiles = 5.0, maxResults = 2).size
        )
        assertTrue(
            GeoQueryEngine.comparablesWithin(target, all, radiusMiles = 5.0, maxResults = 0).isEmpty()
        )
    }

    @Test
    fun radiusQueryWorksAcrossTheAntimeridian() {
        val center = GeoPoint(0.0, 179.95)
        val eastOfLine = pin("east", 0.0, -179.98)
        val farAway = pin("far", 0.0, 100.0)
        val ids = GeoQueryEngine.withinRadius(center, 10.0, listOf(eastOfLine, farAway)).map { it.pin.id }
        assertEquals(listOf("east"), ids)
    }
}
