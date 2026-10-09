package com.rfidsoftwares.controller.epcupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateEpcSessionPlannerTest {
    @Test
    fun epc96PadsSerialAfterPrefix() {
        val planner = UpdateEpcSessionPlanner(
            UpdateEpcConfig.parse(
                prefixHex = "020103",
                serialText = "245",
                format = UpdateEpcFormat.EPC_96,
            )
        )

        assertEquals("020103000000000000000245", planner.nextEpc())
    }

    @Test
    fun epc128PadsSerialAfterPrefix() {
        val planner = UpdateEpcSessionPlanner(
            UpdateEpcConfig.parse(
                prefixHex = "020103",
                serialText = "1",
                format = UpdateEpcFormat.EPC_128,
            )
        )

        assertEquals("02010300000000000000000000000001", planner.nextEpc())
    }

    @Test
    fun markUpdatedTracksOldAndNewEpcsForCurrentSessionOnly() {
        val planner = UpdateEpcSessionPlanner(
            UpdateEpcConfig.parse(
                prefixHex = "020103",
                serialText = "1",
                format = UpdateEpcFormat.EPC_96,
            )
        )
        val newEpc = planner.nextEpc()

        assertFalse(planner.shouldIgnore("E280699500005000FEF3B548"))
        planner.markUpdated("E280699500005000FEF3B548", newEpc)

        assertTrue(planner.shouldIgnore("E280699500005000FEF3B548"))
        assertTrue(planner.shouldIgnore(newEpc))
        assertEquals("2", planner.nextSerialText())
    }

    @Test
    fun ignoresEpcsAlreadyUsingPrefix() {
        val planner = UpdateEpcSessionPlanner(
            UpdateEpcConfig.parse(
                prefixHex = "020103",
                serialText = "1",
                format = UpdateEpcFormat.EPC_96,
            )
        )

        assertTrue(planner.shouldIgnore("020103000000000000000245"))
    }
}
