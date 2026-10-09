package com.rfidsoftwares.rfid.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RfidDriverFactoryTest {

    @Test
    fun availableDrivers_containsExpectedDrivers() {
        val drivers = RfidDriverFactory.availableDrivers
        assertTrue(drivers.any { it is ChainwayC72Driver })
        assertTrue(drivers.any { it is SeuicAutoid9uDriver })
        assertTrue(drivers.any { it is AtidAt911nDriver })
        assertTrue(drivers.any { it is ZebraRfidApi3Driver })
        assertTrue(drivers.any { it is HoneywellMobilityDriver })
        assertTrue(drivers.any { it is MockRfidDriver })
    }

    @Test
    fun createDriver_withExplicitId_resolvesTargetDriver() {
        assertEquals("chainway_c72", RfidDriverFactory.createDriver("chainway_c72").driverId)
        assertEquals("seuic_autoid9u", RfidDriverFactory.createDriver("seuic_autoid9u").driverId)
        assertEquals("atid_at911n", RfidDriverFactory.createDriver("atid_at911n").driverId)
        assertEquals("zebra_rfidapi3", RfidDriverFactory.createDriver("zebra_rfidapi3").driverId)
        assertEquals("honeywell_mobility", RfidDriverFactory.createDriver("honeywell_mobility").driverId)
        assertEquals("mock_reader", RfidDriverFactory.createDriver("mock_reader").driverId)
    }

    @Test
    fun mockDriver_simulatesScanningAndMemoryAccess() {
        val mock = MockRfidDriver()
        assertTrue(mock.init(org.mockito.Mockito.mock(android.content.Context::class.java)))
        assertTrue(mock.startInventory())

        val events = mock.readBufferedTagEvents()
        assertTrue(events.isNotEmpty())
        assertNotNull(events.first().epc)

        val tid = mock.readMemoryBank("E2801191A000000000001001", MemoryBank.TID, 0, 4, null)
        assertNotNull(tid)

        val diag = mock.readDiagnosticsSummary()
        assertTrue(diag.sdkReady)
        assertTrue(diag.readerOpen)
        assertEquals(30, diag.powerDbm)
    }
}
