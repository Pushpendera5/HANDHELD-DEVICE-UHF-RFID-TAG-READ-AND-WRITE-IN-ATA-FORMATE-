
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent

enum class MemoryBank(val bankId: Int) {
    RESERVED(0),
    EPC(1),
    TID(2),
    USER(3),
}

/**
 * Universal Hardware Driver interface for handheld RFID readers (Chainway C72, Seuic AUTOID9U, Zebra, Mock).
 */
interface RfidReaderDriver {
    val driverId: String
    val displayName: String

    fun init(context: Context): Boolean
    fun release(): Boolean

    fun startInventory(): Boolean
    fun stopInventory(): Boolean
    fun readBufferedTagEvents(): List<TagEvent>

    fun readMemoryBank(epc: String?, bank: MemoryBank, ptr: Int, len: Int, pwdHex: String?): String?
    fun writeMemoryBank(epc: String?, bank: MemoryBank, ptr: Int, dataHex: String, pwdHex: String?): Boolean
    fun setLockState(epc: String?, accessPasswordHex: String?, lock: Boolean): Boolean

    fun setPower(powerDbm: Int): Boolean
    fun getPower(): Int?

    fun readDiagnosticsSummary(): ReaderDiagnosticsSummary
}
