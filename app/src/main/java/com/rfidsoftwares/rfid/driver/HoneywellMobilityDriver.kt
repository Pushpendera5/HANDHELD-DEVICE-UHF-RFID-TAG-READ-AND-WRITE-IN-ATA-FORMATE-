
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import java.util.Locale

/**
 * Driver implementation for Honeywell Mobility RFID readers (CT40/60, EDA51/52, IH25/40).
 *
 * Uses `com.honeywell.aidc.AidcManager` and Honeywell RFID API service.
 */
class HoneywellMobilityDriver : RfidReaderDriver {

    override val driverId: String = "honeywell_mobility"
    override val displayName: String = "Honeywell Mobility (Dolphin / IH25)"

    private var aidcManager: Any? = null
    private var rfidReader: Any? = null
    private var isScanning = false

    override fun init(context: Context): Boolean {
        return try {
            val clazz = Class.forName("com.honeywell.aidc.AidcManager")
            // Best effort reflective probe for AidcManager
            val mgr = clazz.methods.firstOrNull { it.name == "create" }?.invoke(null, context)
            aidcManager = mgr
            mgr != null
        } catch (_: Exception) {
            aidcManager = null
            false
        }
    }

    override fun release(): Boolean {
        val mgr = aidcManager ?: return false
        return try {
            val closeMethod = mgr.javaClass.getMethod("close")
            closeMethod.invoke(mgr)
            aidcManager = null
            rfidReader = null
            isScanning = false
            true
        } catch (_: Exception) {
            aidcManager = null
            rfidReader = null
            false
        }
    }

    override fun startInventory(): Boolean {
        val r = rfidReader ?: aidcManager ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "startScan" || it.name == "read" || it.name == "claim" }
            m?.invoke(r)
            isScanning = true
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val r = rfidReader ?: aidcManager ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "stopScan" || it.name == "stop" || it.name == "release" }
            m?.invoke(r)
            isScanning = false
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        val r = rfidReader ?: return emptyList()
        return try {
            val readMethod = r.javaClass.methods.firstOrNull { it.name == "readTag" || it.name == "getTag" }
                ?: return emptyList()
            val tag = readMethod.invoke(r) ?: return emptyList()

            val epcMethod = tag.javaClass.methods.firstOrNull { it.name == "getEpc" || it.name == "getTagId" }
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
        val r = rfidReader ?: return null
        return try {
            val readMethod = r.javaClass.methods.firstOrNull { it.name == "readTagMemory" || it.name == "readData" }
            val pwd = pwdHex ?: "00000000"
            val result = readMethod?.invoke(r, pwd, bank.bankId, ptr, len)
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
        val r = rfidReader ?: return false
        return try {
            val writeMethod = r.javaClass.methods.firstOrNull { it.name == "writeTagMemory" || it.name == "writeData" }
            val pwd = pwdHex ?: "00000000"
            (writeMethod?.invoke(r, pwd, bank.bankId, ptr, dataHex) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        val r = rfidReader ?: return false
        return try {
            val lockMethod = r.javaClass.methods.firstOrNull { it.name == "lockTag" || it.name == "setLock" }
            val pwd = accessPasswordHex ?: "00000000"
            (lockMethod?.invoke(r, pwd, if (lock) 1 else 0) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun setPower(powerDbm: Int): Boolean {
        val r = rfidReader ?: return false
        return try {
            val setPowerMethod = r.javaClass.methods.firstOrNull { it.name == "setPower" && it.parameterCount == 1 }
            setPowerMethod?.invoke(r, powerDbm)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getPower(): Int? {
        val r = rfidReader ?: return null
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
            sdkReady = aidcManager != null,
            readerOpen = rfidReader != null || aidcManager != null,
            powerDbm = power,
            regionOrFrequency = "Honeywell AIDC",
            batteryNote = "Honeywell Reader Ready",
            detailLine = "Honeywell Mobility Driver Active"
        )
    }
}
