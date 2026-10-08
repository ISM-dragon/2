package com.example.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportFitterTest {

    private val austin = GeoPoint(30.2672, -97.7431)
    private val houston = GeoPoint(29.7604, -95.3698)
    private val dallas = GeoPoint(32.7767, -96.7970)

    private fun pin(id: String, p: GeoPoint) = MapPin(id = id, point = p)

    @Test
    fun emptyDatasetHasNoViewport() {
        assertNull(ViewportFitter.fit(emptyList()))
    }

    @Test
    fun singlePinUsesTheSinglePointZoomAndIsCentered() {
        val viewport = ViewportFitter.fit(listOf(pin("a", austin)))!!
        assertEquals(austin, viewport.center)
        assertEquals(ViewportFitter.SINGLE_POINT_ZOOM, viewport.zoom, 0.0)
        assertEquals(0.0, viewport.bounds!!.latitudeSpanDegrees, 0.0)
    }

    @Test
    fun fittedViewportContainsEveryPin() {
        val pins = listOf(pin("austin", austin), pin("houston", houston), pin("dallas", dallas))
        val viewport = ViewportFitter.fit(pins)!!
        assertTrue(pins.all { it.point in viewport.bounds!! })
    }

    @Test
    fun widerSpreadYieldsLowerZoom() {
        val tight = ViewportFitter.fit(listOf(pin("a", austin), pin("b", GeoPoint(30.2862, -97.7394))))!!
        val wide = ViewportFitter.fit(listOf(pin("a", austin), pin("c", houston)))!!
        assertTrue(wide.zoom < tight.zoom)
    }

    @Test
    fun zoomIsClampedToMaxZoom() {
        val nearIdentical = listOf(
            pin("a", austin),
            pin("b", GeoPoint(austin.latitude + 1e-9, austin.longitude + 1e-9))
        )
        val viewport = ViewportFitter.fit(nearIdentical, maxZoom = 18.0)!!
        assertEquals(18.0, viewport.zoom, 0.0)
    }

    @Test
    fun largerPaddingNeverIncreasesZoom() {
        val pins = listOf(pin("a", austin), pin("b", dallas))
        val tight = ViewportFitter.fit(pins, paddingFraction = 0.0)!!
        val padded = ViewportFitter.fit(pins, paddingFraction = 0.3)!!
        assertTrue(padded.zoom < tight.zoom)
    }

    @Test
    fun wholeWorldFitsAtZoomZeroOnASquareViewport() {
        val viewport = ViewportFitter.fitBounds(
            GeoBoundingBox(-85.0, -180.0, 85.0, 180.0),
            viewportWidthPx = 256.0,
            viewportHeightPx = 256.0,
            paddingFraction = 0.0
        )
        assertEquals(0.0, viewport.zoom, 0.05)
    }

    @Test
    fun fitIsDeterministicForTheSameInput() {
        val pins = listOf(pin("a", austin), pin("b", houston), pin("c", dallas))
        assertEquals(ViewportFitter.fit(pins), ViewportFitter.fit(pins.reversed()))
    }

    @Test
    fun fitRadiusKeepsTheRequestedCenterAndCoversTheCircle() {
        val viewport = ViewportFitter.fitRadius(austin, radiusMiles = 2.0)
        assertEquals(austin, viewport.center)
        val north = GeoPoint(viewport.bounds!!.north, austin.longitude)
        assertEquals(2.0, austin.distanceMilesTo(north), 1e-6)
    }

    @Test
    fun integerZoomFloorsTheComputedZoom() {
        val viewport = MapViewport(austin, 13.87, null)
        assertEquals(13, ViewportFitter.integerZoom(viewport))
        assertEquals(0, ViewportFitter.integerZoom(MapViewport(austin, 0.4, null)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPaddingIsRejected() {
        ViewportFitter.fit(listOf(pin("a", austin)), paddingFraction = 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveViewportSizeIsRejected() {
        ViewportFitter.fit(listOf(pin("a", austin)), viewportWidthPx = 0.0)
    }
}
