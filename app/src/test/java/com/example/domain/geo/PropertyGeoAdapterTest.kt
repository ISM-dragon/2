package com.example.domain.geo

import com.example.data.local.entity.PropertyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyGeoAdapterTest {

    private fun property(
        id: String,
        latitude: Double,
        longitude: Double,
        dealScore: Int = 50,
        isSavedDeal: Boolean = false,
        status: String = "Active",
        price: Double = 450_000.0
    ) = PropertyEntity(
        id = id,
        sourceType = "ON_MARKET",
        title = "Listing $id",
        address = "100 Congress Ave",
        city = "Austin",
        state = "TX",
        zipCode = "78701",
        latitude = latitude,
        longitude = longitude,
        price = price,
        propertyType = "Single Family",
        bedrooms = 3,
        bathrooms = 2.0,
        squareFeet = 1800,
        yearBuilt = 1998,
        lotSizeSqFt = 6000,
        description = "",
        status = status,
        primaryImageUrl = "",
        scannedAt = 0L,
        isSaved = false,
        isSavedDeal = isSavedDeal,
        dealScore = dealScore
    )

    @Test
    fun mapsCoordinatesAndMetadataOntoThePin() {
        val pin = PropertyGeoAdapter.toPin(property("p1", 30.2672, -97.7431))!!
        assertEquals("p1", pin.id)
        assertEquals(GeoPoint(30.2672, -97.7431), pin.point)
        assertEquals("100 Congress Ave", pin.label)
        assertEquals("Austin, TX", pin.subLabel)
        assertEquals(450_000.0, pin.priceUsd!!, 0.0)
        assertEquals(50, pin.dealScore)
        assertEquals("ON_MARKET", pin.attributes[PropertyGeoAdapter.ATTR_SOURCE_TYPE])
        assertEquals("78701", pin.attributes[PropertyGeoAdapter.ATTR_ZIP])
    }

    @Test
    fun rowsWithoutUsableCoordinatesAreDroppedNotFabricated() {
        assertNull(PropertyGeoAdapter.toPin(property("nulls", 0.0, 0.0)))
        assertNull(PropertyGeoAdapter.toPin(property("nan", Double.NaN, -97.0)))
        assertNull(PropertyGeoAdapter.toPin(property("oob", 95.0, -97.0)))
    }

    @Test
    fun emptyInputProducesEmptyPinList() {
        assertTrue(PropertyGeoAdapter.toPins(emptyList()).isEmpty())
    }

    @Test
    fun toPinsSkipsInvalidRowsAndSortsById() {
        val pins = PropertyGeoAdapter.toPins(
            listOf(
                property("b", 30.2862, -97.7394),
                property("bad", 0.0, 0.0),
                property("a", 30.2672, -97.7431)
            )
        )
        assertEquals(listOf("a", "b"), pins.map { it.id })
    }

    @Test
    fun emphasisIsDerivedDeterministicallyFromDomainData() {
        assertEquals(
            MapPinEmphasis.PRIMARY,
            PropertyGeoAdapter.toPin(property("t", 30.2672, -97.7431), MapPinRole.TARGET)!!.emphasis
        )
        assertEquals(
            MapPinEmphasis.HIGHLIGHTED,
            PropertyGeoAdapter.toPin(property("h", 30.2672, -97.7431, dealScore = 88))!!.emphasis
        )
        assertEquals(
            MapPinEmphasis.MUTED,
            PropertyGeoAdapter.toPin(
                property("m", 30.2672, -97.7431, status = "Off-Market")
            )!!.emphasis
        )
        assertEquals(
            MapPinEmphasis.NORMAL,
            PropertyGeoAdapter.toPin(property("n", 30.2672, -97.7431))!!.emphasis
        )
    }

    @Test
    fun nonPositivePriceIsOmittedRatherThanGuessed() {
        assertNull(PropertyGeoAdapter.toPin(property("free", 30.2672, -97.7431, price = 0.0))!!.priceUsd)
    }
}
