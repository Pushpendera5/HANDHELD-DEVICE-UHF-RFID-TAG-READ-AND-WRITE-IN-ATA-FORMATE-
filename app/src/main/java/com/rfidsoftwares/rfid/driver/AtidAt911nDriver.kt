
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import java.util.Locale

/**
 * Driver implementation for ATID AT911N / AT870 / AT388 handheld RFID readers.
 *
 * Uses `com.atid.lib.dev.ATRfidManager` and `ATRfidReader` APIs.
 */
class AtidAt911nDriver : RfidReaderDriver {

    override val driverId: String = "atid_at911n"
    override val displayName: String = "ATID AT911N / AT870"

    private var reader: Any? = null
    private var isScanning = false

    override fun init(context: Context): Boolean {
        return try {
            val mgrClass = Class.forName("com.atid.lib.dev.ATRfidManager")
            val getInstance = mgrClass.getMethod("getInstance")
            val r = getInstance.invoke(null)
            reader = r
            r != null
        } catch (_: Exception) {
            reader = null
            false
        }
    }

    override fun release(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "destroy" || it.name == "close" }
            m?.invoke(r)
            reader = null
            isScanning = false
            true
        } catch (_: Exception) {
            reader = null
            false
        }
    }

    override fun startInventory(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "inventory" || it.name == "start" }
            m?.invoke(r)
            isScanning = true
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "stop" }
            m?.invoke(r)
            isScanning = false
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        val r = reader ?: return emptyList()
        return try {
            val readMethod = r.javaClass.methods.firstOrNull { it.name == "readTag" || it.name == "getTag" }
                ?: return emptyList()
            val tag = readMethod.invoke(r) ?: return emptyList()

            val epcMethod = tag.javaClass.methods.firstOrNull { it.name == "getEpc" || it.name == "getTag" }
            val epc = (epcMethod?.invoke(tag) as? String)?.trim()?.uppercase(Locale.US) ?: return emptyList()

            val rssiMethod = tag.javaClass.methods.firstOrNull { it.name == "getRssi" }
            val rssi = (rssiMethod?.invoke(tag) as? Number)?.toInt()

            listOf(
                TagEvent(
                    epc = epc,
                    rssi = rssi,
                    source = TagSource.EPC,
                    seenAt = System.currentTimeMillis()
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun readMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        len: Int,
        pwdHex: String?
    ): String? {
        val r = reader ?: return null
        return try {
            val readMethod = r.javaClass.methods.firstOrNull { it.name == "readMemory" }
            val pwd = pwdHex ?: "00000000"
            val result = readMethod?.invoke(r, bank.bankId, ptr, len, pwd)
            result?.toString()
        } catch (_: Exception) {
            null
        }
    }

    override fun writeMemoryBank(
        epc: String?,
        bank: MemoryBank,
        ptr: Int,
        dataHex: String,
        pwdHex: String?
    ): Boolean {
        val r = reader ?: return false
        return try {
            val writeMethod = r.javaClass.methods.firstOrNull { it.name == "writeMemory" }
            val pwd = pwdHex ?: "00000000"
            (writeMethod?.invoke(r, bank.bankId, ptr, dataHex, pwd) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        val r = reader ?: return false
        return try {
            val lockMethod = r.javaClass.methods.firstOrNull { it.name == "lockMemory" || it.name == "setLock" }
            val pwd = accessPasswordHex ?: "00000000"
            (lockMethod?.invoke(r, pwd, if (lock) 1 else 0) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setPower(powerDbm: Int): Boolean {
        val r = reader ?: return false
        return try {
            val setPowerMethod = r.javaClass.methods.firstOrNull { it.name == "setPower" && it.parameterCount == 1 }
            setPowerMethod?.invoke(r, powerDbm)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getPower(): Int? {
        val r = reader ?: return null
        return try {
            val getPowerMethod = r.javaClass.methods.firstOrNull { it.name == "getPower" && it.parameterCount == 0 }
            (getPowerMethod?.invoke(r) as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        val power = getPower()
        return ReaderDiagnosticsSummary(
            sdkReady = reader != null,
            readerOpen = reader != null,
            powerDbm = power,
            regionOrFrequency = "ATID UHF",
            batteryNote = "ATID Reader Connected",
            detailLine = "ATID AT911N Driver Active"
        )
    }
}
