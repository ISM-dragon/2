package com.example.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoClustererTest {

    private val downtownAustin = GeoPoint(30.2672, -97.7431)

    private fun pin(id: String, lat: Double, lng: Double, emphasis: MapPinEmphasis = MapPinEmphasis.NORMAL) =
        MapPin(id = id, point = GeoPoint(lat, lng), emphasis = emphasis)

    @Test
    fun emptyInputProducesNoClusters() {
        assertTrue(GeoClusterer.cluster(emptyList(), 0.5).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveCellSizeIsRejected() {
        GeoClusterer.cluster(listOf(pin("a", 30.2672, -97.7431)), 0.0)
    }

    @Test
    fun identicalCoordinatesCollapseIntoOneCluster() {
        val pins = listOf(
            pin("b", 30.2672, -97.7431),
            pin("a", 30.2672, -97.7431),
            pin("c", 30.2672, -97.7431)
        )
        val clusters = GeoClusterer.cluster(pins, 0.25)
        assertEquals(1, clusters.size)
        assertEquals(3, clusters.first().size)
        assertEquals(listOf("a", "b", "c"), clusters.first().pins.map { it.id })
        assertEquals(downtownAustin, clusters.first().centroid)
        assertTrue(!clusters.first().isSingle)
    }

    @Test
    fun distantPinsNeverShareACluster() {
        val clusters = GeoClusterer.cluster(
            listOf(
                pin("austin", 30.2672, -97.7431),
                pin("houston", 29.7604, -95.3698),
                pin("dallas", 32.7767, -96.7970)
            ),
            cellSizeMiles = 1.0
        )
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.isSingle })
    }

    @Test
    fun everyPinAppearsExactlyOnceAcrossClusters() {
        val pins = listOf(
            pin("p1", 30.2672, -97.7431),
            pin("p2", 30.2652, -97.7474),
            pin("p3", 30.2862, -97.7394),
            pin("p4", 30.2669, -97.7729),
            pin("p5", 30.5083, -97.6789)
        )
        val ids = GeoClusterer.cluster(pins, 0.5).flatMap { c -> c.pins.map { it.id } }
        assertEquals(pins.size, ids.size)
        assertEquals(pins.map { it.id }.sorted(), ids.sorted())
    }

    @Test
    fun clusteringIsIndependentOfInputOrder() {
        val pins = listOf(
            pin("p1", 30.2672, -97.7431),
            pin("p2", 30.26721, -97.74311),
            pin("p3", 30.2862, -97.7394),
            pin("p4", 30.5083, -97.6789)
        )
        val a = GeoClusterer.cluster(pins, 0.5)
        val b = GeoClusterer.cluster(pins.reversed(), 0.5)
        assertEquals(a.map { it.key to it.pins.map { p -> p.id } }, b.map { it.key to it.pins.map { p -> p.id } })
    }

    @Test
    fun clustersAreOrderedByDescendingSize() {
        val pins = listOf(
            pin("a1", 30.2672, -97.7431),
            pin("a2", 30.26721, -97.74311),
            pin("far", 30.5083, -97.6789)
        )
        val clusters = GeoClusterer.cluster(pins, 0.25)
        assertEquals(listOf(2, 1), clusters.map { it.size })
    }

    @Test
    fun representativeIsTheMostEmphasizedMember() {
        val pins = listOf(
            pin("z-primary", 30.2672, -97.7431, MapPinEmphasis.PRIMARY),
            pin("a-normal", 30.2672, -97.7431, MapPinEmphasis.NORMAL)
        )
        assertEquals("z-primary", GeoClusterer.cluster(pins, 0.25).first().representative.id)
    }

    @Test
    fun clusterBoundsContainAllMembers() {
        val pins = listOf(
            pin("a", 30.2672, -97.7431),
            pin("b", 30.26741, -97.74331)
        )
        val cluster = GeoClusterer.cluster(pins, 1.0).first()
        assertTrue(pins.all { it.point in cluster.bounds })
    }

    @Test
    fun distanceBandsAreAscendingAndPartitionAllPins() {
        val pins = listOf(
            pin("city-hall", 30.2652, -97.7474),   // ~0.29 mi
            pin("ut-tower", 30.2862, -97.7394),    // ~1.33 mi
            pin("mueller", 30.2991, -97.7051),     // ~3.16 mi
            pin("round-rock", 30.5083, -97.6789)   // ~17.09 mi
        )
        val bands = GeoClusterer.groupByDistanceBands(downtownAustin, pins, listOf(0.5, 1.0, 3.0))
        assertEquals(4, bands.size)
        assertEquals(listOf(0.0, 0.5, 1.0, 3.0), bands.map { it.minMiles })
        assertEquals(listOf(0.5, 1.0, 3.0, null), bands.map { it.maxMiles })
        assertEquals(listOf("city-hall"), bands[0].entries.map { it.pin.id })
        assertTrue(bands[1].entries.isEmpty())
        assertEquals(listOf("ut-tower"), bands[2].entries.map { it.pin.id })
        assertEquals(listOf("mueller", "round-rock"), bands[3].entries.map { it.pin.id })
        assertEquals(pins.size, bands.sumOf { it.size })
    }

    @Test
    fun distanceBandsOnEmptyDatasetKeepTheBandStructure() {
        val bands = GeoClusterer.groupByDistanceBands(downtownAustin, emptyList(), listOf(1.0, 5.0))
        assertEquals(3, bands.size)
        assertTrue(bands.all { it.entries.isEmpty() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedBandEdgesAreRejected() {
        GeoClusterer.groupByDistanceBands(downtownAustin, emptyList(), listOf(3.0, 1.0))
    }
}
