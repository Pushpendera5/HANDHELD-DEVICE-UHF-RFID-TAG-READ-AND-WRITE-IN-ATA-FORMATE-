
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import java.util.Locale

/**
 * Driver implementation for Seuic AUTOID9U handheld RFID readers.
 *
 * Uses reflective invocations on `com.seuic.uhf.UHFService` so the app compiles portably
 * without forcing Seuic SDK JARs into every build target.
 */
class SeuicAutoid9uDriver : RfidReaderDriver {

    override val driverId: String = "seuic_autoid9u"
    override val displayName: String = "Seuic AUTOID9U"

    private var uhfService: Any? = null
    private var isScanning = false

    override fun init(context: Context): Boolean {
        return try {
            val serviceClass = Class.forName("com.seuic.uhf.UHFService")
            val getInstanceMethod = serviceClass.getMethod("getInstance")
            uhfService = getInstanceMethod.invoke(null)
            
            val openMethod = serviceClass.methods.firstOrNull { it.name == "open" }
            val ok = if (openMethod != null) {
                if (openMethod.parameterCount == 1) openMethod.invoke(uhfService, context) as? Boolean ?: true
                else openMethod.invoke(uhfService) as? Boolean ?: true
            } else true
            ok
        } catch (_: Exception) {
            uhfService = null
            false
        }
    }

    override fun release(): Boolean {
        val s = uhfService ?: return false
        return try {
            val closeMethod = s.javaClass.getMethod("close")
            closeMethod.invoke(s)
            uhfService = null
            isScanning = false
            true
        } catch (_: Exception) {
            uhfService = null
            false
        }
    }

    override fun startInventory(): Boolean {
        val s = uhfService ?: return false
        return try {
            val m = s.javaClass.methods.firstOrNull { it.name == "startScan" || it.name == "inventory" }
            m?.invoke(s)
            isScanning = true
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val s = uhfService ?: return false
        return try {
            val m = s.javaClass.methods.firstOrNull { it.name == "stopScan" || it.name == "stopInventory" }
            m?.invoke(s)
            isScanning = false
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        val s = uhfService ?: return emptyList()
        return try {
            val getTagMethod = s.javaClass.methods.firstOrNull { 
                it.name == "getTag" || it.name == "readTag" || it.name == "getInventoryTag"
            } ?: return emptyList()

            val tag = getTagMethod.invoke(s) ?: return emptyList()
            val epcMethod = tag.javaClass.methods.firstOrNull { it.name == "getEpc" || it.name == "getEPC" }
            val epc = (epcMethod?.invoke(tag) as? String)?.trim()?.uppercase(Locale.US) ?: return emptyList()

            val rssiMethod = tag.javaClass.methods.firstOrNull { it.name == "getRssi" || it.name == "getRSSI" }
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
        val s = uhfService ?: return null
        return try {
            val readMethod = s.javaClass.methods.firstOrNull { it.name == "readTagMemory" || it.name == "readData" }
            val pwd = pwdHex ?: "00000000"
            val result = readMethod?.invoke(s, pwd, bank.bankId, ptr, len)
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
        val s = uhfService ?: return false
        return try {
            val writeMethod = s.javaClass.methods.firstOrNull { it.name == "writeTagMemory" || it.name == "writeData" }
            val pwd = pwdHex ?: "00000000"
            (writeMethod?.invoke(s, pwd, bank.bankId, ptr, dataHex) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        val s = uhfService ?: return false
        return try {
            val lockMethod = s.javaClass.methods.firstOrNull { it.name == "lockTag" || it.name == "setLock" }
            val pwd = accessPasswordHex ?: "00000000"
            (lockMethod?.invoke(s, pwd, if (lock) 1 else 0) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setPower(powerDbm: Int): Boolean {
        val s = uhfService ?: return false
        return try {
            val setPowerMethod = s.javaClass.methods.firstOrNull { it.name == "setPower" && it.parameterCount == 1 }
            setPowerMethod?.invoke(s, powerDbm)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getPower(): Int? {
        val s = uhfService ?: return null
        return try {
            val getPowerMethod = s.javaClass.methods.firstOrNull { it.name == "getPower" && it.parameterCount == 0 }
            (getPowerMethod?.invoke(s) as? Number)?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        val power = getPower()
        return ReaderDiagnosticsSummary(
            sdkReady = uhfService != null,
            readerOpen = uhfService != null,
            powerDbm = power,
            regionOrFrequency = "Seuic UHF",
            batteryNote = "Seuic Reader Ready",
            detailLine = "Seuic AUTOID9U Driver"
        )
    }
}
