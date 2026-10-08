package com.example.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All coordinates below are real published locations (US city centroids / reference points);
 * nothing is fabricated.
 */
class GeoPrimitivesTest {

    private val austin = GeoPoint(30.2672, -97.7431)
    private val houston = GeoPoint(29.7604, -95.3698)
    private val dallas = GeoPoint(32.7767, -96.7970)
    private val newYork = GeoPoint(40.7128, -74.0060)
    private val losAngeles = GeoPoint(34.0522, -118.2437)

    @Test
    fun distanceToSelfIsZero() {
        assertEquals(0.0, austin.distanceMilesTo(austin), 0.0)
    }

    @Test
    fun distanceIsSymmetric() {
        assertEquals(
            austin.distanceMilesTo(houston),
            houston.distanceMilesTo(austin),
            1e-9
        )
    }

    @Test
    fun knownCityDistancesMatchPublishedGreatCircleValues() {
        assertEquals(146.24, austin.distanceMilesTo(houston), 0.5)
        assertEquals(182.12, austin.distanceMilesTo(dallas), 0.5)
        assertEquals(2445.56, newYork.distanceMilesTo(losAngeles), 2.0)
    }

    @Test
    fun oneDegreeOfLatitudeIsAboutSixtyNineMiles() {
        val a = GeoPoint(30.0, -97.7431)
        val b = GeoPoint(31.0, -97.7431)
        assertEquals(Geo.MILES_PER_DEGREE_LATITUDE, a.distanceMilesTo(b), 0.01)
    }

    @Test
    fun distanceAcrossAntimeridianIsShort() {
        val west = GeoPoint(0.0, -179.9)
        val east = GeoPoint(0.0, 179.9)
        assertEquals(13.82, west.distanceMilesTo(east), 0.05)
    }

    @Test
    fun antipodalDistanceIsHalfCircumference() {
        val north = GeoPoint(90.0, 0.0)
        val south = GeoPoint(-90.0, 0.0)
        assertEquals(Math.PI * Geo.EARTH_RADIUS_MILES, north.distanceMilesTo(south), 0.01)
    }

    @Test
    fun bearingIsNormalizedIntoZeroToThreeSixty() {
        val northward = GeoPoint(30.0, -97.0).initialBearingDegreesTo(GeoPoint(31.0, -97.0))
        val southward = GeoPoint(31.0, -97.0).initialBearingDegreesTo(GeoPoint(30.0, -97.0))
        assertEquals(0.0, northward, 1e-6)
        assertEquals(180.0, southward, 1e-6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun latitudeOutOfRangeIsRejected() {
        GeoPoint(91.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteCoordinateIsRejected() {
        GeoPoint(Double.NaN, 0.0)
    }

    @Test
    fun orNullRejectsInvalidAndNullIslandCoordinates() {
        assertNull(GeoPoint.orNull(0.0, 0.0))
        assertNull(GeoPoint.orNull(Double.NaN, -97.0))
        assertNull(GeoPoint.orNull(30.0, 181.0))
        assertNotNull(GeoPoint.orNull(30.2672, -97.7431))
    }

    @Test
    fun poleAndAntimeridianExtremesAreValid() {
        assertNotNull(GeoPoint.orNull(90.0, 180.0))
        assertNotNull(GeoPoint.orNull(-90.0, -180.0))
    }

    @Test
    fun boundingBoxOfEmptySetIsNull() {
        assertNull(GeoBoundingBox.of(emptyList()))
    }

    @Test
    fun boundingBoxOfSinglePointIsDegenerateAndContainsIt() {
        val box = GeoBoundingBox.of(listOf(austin))!!
        assertEquals(0.0, box.latitudeSpanDegrees, 0.0)
        assertEquals(0.0, box.longitudeSpanDegrees, 0.0)
        assertEquals(austin, box.center)
        assertTrue(austin in box)
    }

    @Test
    fun boundingBoxCoversAllPointsAndExcludesOutsiders() {
        val box = GeoBoundingBox.of(listOf(austin, houston, dallas))!!
        assertTrue(austin in box)
        assertTrue(houston in box)
        assertTrue(dallas in box)
        assertFalse(newYork in box)
        assertEquals(29.7604, box.south, 1e-9)
        assertEquals(32.7767, box.north, 1e-9)
        assertEquals(-97.7431, box.west, 1e-9)
        assertEquals(-95.3698, box.east, 1e-9)
    }

    @Test
    fun aroundProducesBoxThatContainsCenterAndIsAtLeastRadiusTall() {
        val box = GeoBoundingBox.around(austin, 5.0)
        assertTrue(austin in box)
        val northEdge = GeoPoint(box.north, austin.longitude)
        assertEquals(5.0, austin.distanceMilesTo(northEdge), 1e-6)
    }

    @Test
    fun aroundWithZeroRadiusIsDegenerate() {
        val box = GeoBoundingBox.around(austin, 0.0)
        assertEquals(austin.latitude, box.south, 1e-12)
        assertEquals(austin.latitude, box.north, 1e-12)
        assertTrue(austin in box)
    }

    @Test
    fun aroundNearThePoleClampsToFullLongitudeRange() {
        val box = GeoBoundingBox.around(GeoPoint(89.99, 10.0), 50.0)
        assertEquals(-180.0, box.west, 0.0)
        assertEquals(180.0, box.east, 0.0)
        assertEquals(90.0, box.north, 0.0)
    }

    @Test
    fun aroundTheAntimeridianWrapsAndStillContainsNeighbours() {
        val box = GeoBoundingBox.around(GeoPoint(0.0, 179.95), 10.0)
        assertTrue(box.crossesAntimeridian)
        assertTrue(GeoPoint(0.0, -179.98) in box)
        assertTrue(GeoPoint(0.0, 179.99) in box)
        assertFalse(GeoPoint(0.0, 0.0) in box)
    }

    @Test
    fun expandedByMilesGrowsTheBoxAndZeroIsIdentity() {
        val box = GeoBoundingBox.of(listOf(austin, houston))!!
        assertEquals(box, box.expandedByMiles(0.0))
        val bigger = box.expandedByMiles(10.0)
        assertTrue(bigger.latitudeSpanDegrees > box.latitudeSpanDegrees)
        assertTrue(bigger.longitudeSpanDegrees > box.longitudeSpanDegrees)
        assertTrue(austin in bigger)
    }

    @Test
    fun normalizeLongitudeWrapsValues() {
        assertEquals(-179.0, GeoBoundingBox.normalizeLongitude(181.0), 1e-9)
        assertEquals(179.0, GeoBoundingBox.normalizeLongitude(-181.0), 1e-9)
        assertEquals(-97.7431, GeoBoundingBox.normalizeLongitude(-97.7431), 1e-9)
    }
}
