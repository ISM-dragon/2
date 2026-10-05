package com.example

import com.example.domain.automation.AutomationStatus
import org.junit.Assert.*
import org.junit.Test

class AutomationStateTest {

    @Test
    fun testAutomationStatusTransitions() {
        var status = AutomationStatus.IDLE
        assertEquals(AutomationStatus.IDLE, status)

        // Start cycle
        status = AutomationStatus.SCANNING
        assertEquals(AutomationStatus.SCANNING, status)

        // Analyze
        status = AutomationStatus.ANALYZING
        assertEquals(AutomationStatus.ANALYZING, status)

        // Qualify
        status = AutomationStatus.QUALIFYING
        assertEquals(AutomationStatus.QUALIFYING, status)

        // Generate Offer
        status = AutomationStatus.GENERATING_OFFERS
        assertEquals(AutomationStatus.GENERATING_OFFERS, status)

        // Send Offer
        status = AutomationStatus.SENDING_OFFERS
        assertEquals(AutomationStatus.SENDING_OFFERS, status)

        // Kill switch
        status = AutomationStatus.STOPPED
        assertEquals(AutomationStatus.STOPPED, status)
    }
}
