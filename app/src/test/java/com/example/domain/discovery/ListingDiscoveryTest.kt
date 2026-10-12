package com.example.domain.discovery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ListingDiscoveryTest {
    private class Fake : ListingProvider {
        override val id = "test"
        var received: DiscoveryQuery? = null
        var receivedToken: String? = null
        override fun getProviderCapabilities() = ProviderCapabilities(true, setOf("city", "postalCode", "price"), false)
        override fun getCoverageMetadata() = "Test fixture"
        override suspend fun searchProperties(query: DiscoveryQuery, continuationToken: String?): ListingPage {
            received = query
            receivedToken = continuationToken
            return ListingPage(listOf(ListingObservation(id, "1", sourceRetrievedAt = 100), ListingObservation(id, "1", sourceRetrievedAt = 100)), "next")
        }
        override suspend fun fetchPropertyDetails(providerPropertyId: String) = null
        override suspend fun fetchListingUpdates(syncCursor: String?) = ListingPage(emptyList(), null)
    }

    @Test fun blankLocationIsNationwideAndPagesDeduplicate() = runBlocking {
        val fake = Fake()
        val discovery = ListingDiscovery(listOf(fake))
        val first = discovery.page(fake, DiscoveryQuery())
        assertEquals("Nationwide", fake.received!!.scope)
        assertNull(fake.received!!.stateCode)
        assertEquals(1, first.observations.size)
        discovery.page(fake, DiscoveryQuery(), first.continuationToken)
        assertEquals("next", fake.receivedToken)
    }

    @Test fun stateCityAndZipArePassedWithoutGuessingLocation() = runBlocking {
        val fake = Fake()
        ListingDiscovery(listOf(fake)).page(fake, DiscoveryQuery(stateCode = " tx ", city = "Austin", postalCode = "78701"))
        assertEquals("TX", fake.received!!.stateCode)
        assertEquals("Austin", fake.received!!.city)
        assertEquals("78701", fake.received!!.postalCode)
    }

    @Test fun unsupportedFilterFailsClosed() = runBlocking {
        val fake = Fake()
        try {
            ListingDiscovery(listOf(fake)).page(fake, DiscoveryQuery(propertyType = "HOUSE"))
            fail("unsupported filter must not be silently discarded")
        } catch (_: IllegalArgumentException) { }
        assertNull(fake.received)
    }
}
