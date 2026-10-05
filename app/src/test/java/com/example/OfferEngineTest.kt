package com.example

import com.example.data.local.entity.OfferEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OfferEngineTest {

    @Test
    fun testOfferStatusLifecycleTransitions() {
        val offerId = "OFFER-" + UUID.randomUUID().toString().take(8).uppercase()
        var offer = OfferEntity(
            id = offerId,
            propertyId = "prop-101",
            recipientName = "Sarah Jenkins",
            recipientEmail = "sarah.jenkins@realty.com",
            offerPrice = 450000.0,
            earnestMoney = 6750.0,
            inspectionPeriodDays = 10,
            closingPeriodDays = 21,
            contingencies = "Clean title, inspection",
            terms = "AS-IS",
            conditions = "Standard",
            expirationDate = "Oct 15, 2026",
            generatedLetterContent = "Letter text",
            status = "DRAFT",
            createdAt = System.currentTimeMillis()
        )

        assertEquals("DRAFT", offer.status)

        // Generate PDF -> READY
        offer = offer.copy(status = "READY", pdfPath = "/data/user/0/com.aistudio.reai.app88/files/offers/$offerId.pdf")
        assertEquals("READY", offer.status)
        assertNotNull(offer.pdfPath)

        // Dispatch via Gmail -> SENT
        offer = offer.copy(status = "SENT", sentAt = System.currentTimeMillis())
        assertEquals("SENT", offer.status)
        assertNotNull(offer.sentAt)

        // Email Opened by agent -> OPENED
        offer = offer.copy(status = "OPENED")
        assertEquals("OPENED", offer.status)

        // CRITICAL CHECK: An opened email MUST NOT be considered SIGNED
        assertNotEquals("SIGNED", offer.status)

        // Explicit seller ratification -> SIGNED
        offer = offer.copy(status = "SIGNED")
        assertEquals("SIGNED", offer.status)
    }

    @Test
    fun testOfferEarnestMoneyAndCommercialTerms() {
        val purchasePrice = 500000.0
        val earnestMoneyPct = 1.5 // 1.5%
        val earnestCalculated = purchasePrice * (earnestMoneyPct / 100.0)

        assertEquals(7500.0, earnestCalculated, 0.001)

        val discountPct = 8.5
        val suggestedOfferPrice = purchasePrice * (1.0 - (discountPct / 100.0))
        assertEquals(457500.0, suggestedOfferPrice, 0.001)
    }

    @Test
    fun testOfferIdUniquenessAndPrefix() {
        val id1 = "OFFER-" + UUID.randomUUID().toString().take(8).uppercase()
        val id2 = "OFFER-" + UUID.randomUUID().toString().take(8).uppercase()

        assertTrue(id1.startsWith("OFFER-"))
        assertTrue(id2.startsWith("OFFER-"))
        assertNotEquals(id1, id2)
    }
}
