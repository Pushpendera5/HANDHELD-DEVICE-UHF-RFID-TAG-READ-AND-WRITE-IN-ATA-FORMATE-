
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ChainwayUhfReaderGateway
import com.rfidsoftwares.rfid.ChainwayUhfTagAccess
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent

/**
 * Driver implementation for Chainway C72 handheld RFID readers.
 */
class ChainwayC72Driver : RfidReaderDriver {

    override val driverId: String = "chainway_c72"
    override val displayName: String = "Chainway C72"

    private val gateway = ChainwayUhfReaderGateway()
    private val tagAccess = ChainwayUhfTagAccess()
    private var isInitialized = false

    override fun init(context: Context): Boolean {
        val gOk = gateway.init(context)
        val tOk = tagAccess.init(context)
        isInitialized = gOk || tOk
        return isInitialized
    }

    override fun release(): Boolean {
        val gOk = gateway.free()
        val tOk = tagAccess.free()
        isInitialized = false
        return gOk || tOk
    }

    override fun startInventory(): Boolean {
        return gateway.startInventory()
    }

    override fun stopInventory(): Boolean {
        return gateway.stopInventory()
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        return gateway.readBufferedTagEvents()
    }

    override fun readMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        len: Int,
        pwdHex: String?
    ): String? {
        val chainwayBank = when (bank) {
            MemoryBank.RESERVED -> ChainwayUhfTagAccess.MemoryBank.RESERVED
            MemoryBank.EPC -> ChainwayUhfTagAccess.MemoryBank.EPC
            MemoryBank.TID -> ChainwayUhfTagAccess.MemoryBank.TID
            MemoryBank.USER -> ChainwayUhfTagAccess.MemoryBank.USER
        }
        return tagAccess.readMemory(
            epc = epc,
            bank = chainwayBank,
            wordPtr = ptr,
            wordCount = len,
            accessPasswordHex = pwdHex
        )
    }

    override fun writeMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        dataHex: String,
        pwdHex: String?
    ): Boolean {
        val chainwayBank = when (bank) {
            MemoryBank.RESERVED -> ChainwayUhfTagAccess.MemoryBank.RESERVED
            MemoryBank.EPC -> ChainwayUhfTagAccess.MemoryBank.EPC
            MemoryBank.TID -> ChainwayUhfTagAccess.MemoryBank.TID
            MemoryBank.USER -> ChainwayUhfTagAccess.MemoryBank.USER
        }
        return tagAccess.writeMemory(
            epc = epc,
            bank = chainwayBank,
            wordPtr = ptr,
            dataHex = dataHex,
            accessPasswordHex = pwdHex
        )
    }

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        return tagAccess.setLockState(
            epc = epc,
            accessPasswordHex = accessPasswordHex,
            lock = lock
        )
    }

    override fun setPower(powerDbm: Int): Boolean {
        return gateway.setPower(powerDbm)
    }

    override fun getPower(): Int? {
        return gateway.readDiagnosticsSummary().powerDbm
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        return gateway.readDiagnosticsSummary()
    }
}
