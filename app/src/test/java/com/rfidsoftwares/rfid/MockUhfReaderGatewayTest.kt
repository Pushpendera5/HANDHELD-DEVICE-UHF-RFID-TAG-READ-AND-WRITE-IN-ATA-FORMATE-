package com.rfidsoftwares.rfid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8: mock UHF gateway diagnostics and structure (no assets required for these assertions).
 */
class MockUhfReaderGatewayTest {

    @Test
    fun readDiagnosticsSummary_idleAndActiveStates() {
        val g = MockUhfReaderGateway()
        val idle = g.readDiagnosticsSummary()
        assertTrue(idle.sdkReady)
        assertFalse(idle.readerOpen)
        assertEquals(30, idle.powerDbm)
        assertEquals("MOCK", idle.regionOrFrequency)

        g.startInventory()
        val active = g.readDiagnosticsSummary()
        assertTrue(active.readerOpen)
        assertTrue(active.detailLine.contains("active"))
    }
}
