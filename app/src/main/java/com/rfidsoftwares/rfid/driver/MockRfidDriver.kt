
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource

/**
 * Software mock driver for testing without physical RFID hardware attached.
 */
class MockRfidDriver : RfidReaderDriver {

    override val driverId: String = "mock_reader"
    override val displayName: String = "Mock Software RFID Reader"

    private var isScanning = false
    private var simulatedPowerDbm = 30
    private var mockIndex = 0
    private val mockEpcs = listOf(
        "E2801191A000000000001001",
        "E2801191A000000000001002",
        "E2801191A000000000001003",
        "E2801191A000000000001004"
    )

    override fun init(context: Context): Boolean = true

    override fun release(): Boolean {
        isScanning = false
        return true
    }

    override fun startInventory(): Boolean {
        isScanning = true
        return true
    }

    override fun stopInventory(): Boolean {
        isScanning = false
        return true
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        if (!isScanning) return emptyList()
        val epc = mockEpcs[mockIndex % mockEpcs.size]
        mockIndex++
        return listOf(
            TagEvent(
                epc = epc,
                rssi = -60 + (mockIndex % 15),
                source = TagSource.EPC,
                seenAt = System.currentTimeMillis()
            )
        )
    }

    override fun readMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        len: Int,
        pwdHex: String?
    ): String? {
        return when (bank) {
            MemoryBank.RESERVED -> "0000000000000000"
            MemoryBank.EPC -> epc ?: "E2801191A000000000001001"
            MemoryBank.TID -> "E28011052000000012345678"
            MemoryBank.USER -> "000000000000000000000000"
        }
    }

    override fun writeMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        dataHex: String,
        pwdHex: String?
    ): Boolean = true

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean = true

    override fun setPower(powerDbm: Int): Boolean {
        simulatedPowerDbm = powerDbm
        return true
    }

    override fun getPower(): Int = simulatedPowerDbm

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        return ReaderDiagnosticsSummary(
            sdkReady = true,
            readerOpen = true,
            powerDbm = simulatedPowerDbm,
            regionOrFrequency = "US_FCC (Mock)",
            batteryNote = "Mock Battery 100%",
            detailLine = "Mock RFID Driver Active"
        )
    }
}
