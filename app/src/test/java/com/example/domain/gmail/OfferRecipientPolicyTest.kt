package com.example.domain.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the default offer recipient is a hard-coded placeholder. It must be recognised so an offer
 * is never transmitted to it (manual or automated), and real recipients must not be affected.
 */
class OfferRecipientPolicyTest {

    @Test
    fun `the placeholder recipient is recognised`() {
        assertTrue(OfferRecipientPolicy.isPlaceholder(OfferRecipientPolicy.PLACEHOLDER_EMAIL))
    }

    @Test
    fun `placeholder matching ignores case and surrounding whitespace`() {
        assertTrue(OfferRecipientPolicy.isPlaceholder("  Agent@RealEstateTeam.com "))
    }

    @Test
    fun `real recipients are not treated as placeholders`() {
        assertFalse(OfferRecipientPolicy.isPlaceholder("agent@example.com"))
        assertFalse(OfferRecipientPolicy.isPlaceholder("agent@realestateteam.com.evil.test"))
        assertFalse(OfferRecipientPolicy.isPlaceholder("ops@realestateteam.com"))
    }

    @Test
    fun `missing recipient is not a placeholder`() {
        assertFalse(OfferRecipientPolicy.isPlaceholder(null))
        assertFalse(OfferRecipientPolicy.isPlaceholder(""))
    }

    @Test
    fun `the placeholder constant is stable`() {
        assertEquals("agent@realestateteam.com", OfferRecipientPolicy.PLACEHOLDER_EMAIL)
    }
}
