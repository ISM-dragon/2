package com.example.dealroom

import com.example.ui.screens.dealroom.DealRoomUiState
import com.example.ui.screens.dealroom.FieldCoverage
import com.example.ui.screens.dealroom.ModelInputBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the Deal Room state model.
 *
 * The screen renders stored state only, so an empty database must produce empty state instead of
 * placeholder intelligence: no market values, no rent, no analysis, no deal score, no offer.
 */
class DealRoomStateContractTest {

    @Test
    fun emptyStateCarriesNoFabricatedIntelligence() {
        val state = DealRoomUiState()

        assertNull(state.property)
        assertNull(state.marketData)
        assertNull(state.rentEstimate)
        assertNull(state.taxRecord)
        assertNull(state.financials)
        assertNull(state.aiAnalysis)
        assertNull(state.offer)
        assertNull(state.dynamicFinancials)
        assertTrue(state.intelligence.isEmpty())
        assertTrue(state.provenance.isEmpty())
        assertTrue(state.comps.isEmpty())
        assertTrue(state.timeline.isEmpty())
        assertFalse(state.rentSourced)
    }

    @Test
    fun intelligenceLookupReturnsNullWhenNothingIsStored() {
        val state = DealRoomUiState()
        assertNull(state.intelligenceFor("FLOOD_RISK"))
        assertNull(state.intelligenceFor("WALK_SCORE"))
    }

    @Test
    fun fieldCoverageReportsTrackedMinusSourced() {
        val coverage = FieldCoverage(
            trackedFields = listOf("price", "rent estimate", "flood zone"),
            sourcedFields = listOf("price")
        )

        assertEquals(3, coverage.trackedCount)
        assertEquals(1, coverage.sourcedCount)
        assertEquals(listOf("rent estimate", "flood zone"), coverage.missingFields)
    }

    @Test
    fun modelInputBasisMustStateItsBasisExplicitly() {
        // `sourced` has no default on purpose (see DealRoomViewModel): a Deal Room input is either
        // backed by a stored fact or it says why it is not - the model cannot silently claim either.
        val basis = ModelInputBasis(label = "Rent", sourced = false)

        assertFalse(basis.sourced)
        assertEquals("", basis.sourceDetail)
        assertEquals("", basis.notSourcedReason)

        val sourcedBasis = ModelInputBasis(
            label = "Tax",
            sourced = true,
            sourceDetail = "property_tax_records.annual_amount"
        )
        assertTrue(sourcedBasis.sourced)
        assertEquals("", sourcedBasis.notSourcedReason)
    }
}
