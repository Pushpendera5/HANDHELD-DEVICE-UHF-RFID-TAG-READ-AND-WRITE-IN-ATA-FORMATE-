
package com.rfidsoftwares.rfid.driver

import android.content.Context
import com.rfidsoftwares.rfid.ReaderDiagnosticsSummary
import com.rfidsoftwares.rfid.TagEvent
import com.rfidsoftwares.rfid.TagSource
import java.util.Locale

/**
 * Driver implementation for Zebra RFID readers (MC3300R, TC20/25, RFD8500, RFD40/90).
 *
 * Uses `com.zebra.rfid.api3.Readers` and `RFIDReader` APIs.
 */
class ZebraRfidApi3Driver : RfidReaderDriver {

    override val driverId: String = "zebra_rfidapi3"
    override val displayName: String = "Zebra RFID (MC3300R / RFD8500 / RFD40)"

    private var reader: Any? = null
    private var isScanning = false
    private var appContext: Context? = null
    private var connectedDeviceName: String? = null
    private var connectedTransport: String? = null

    override fun init(context: Context): Boolean {
        appContext = context.applicationContext
        configuredPowerDbm = try {
            com.rfidsoftwares.common.prefs.UhfConfigPrefs.load(context).powerDbm ?: 30
        } catch (_: Exception) {
            30
        }
        return connectInternal()
    }

    private fun connectInternal(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val readersClass = Class.forName("com.zebra.rfid.api3.Readers")
            val transportClass = Class.forName("com.zebra.rfid.api3.ENUM_TRANSPORT")

            val allTransport = try {
                java.lang.Enum.valueOf(transportClass as Class<out Enum<*>>, "ALL")
            } catch (_: Exception) {
                null
            }

            val readersObj = try {
                if (allTransport != null) {
                    val constructor = readersClass.getConstructor(Context::class.java, transportClass)
                    constructor.newInstance(ctx, allTransport)
                } else {
                    readersClass.getConstructor().newInstance()
                }
            } catch (_: Exception) {
                try {
                    readersClass.getConstructor().newInstance()
                } catch (_: Exception) {
                    null
                }
            } ?: return false

            val getAvailableList = readersClass.getMethod("GetAvailableRFIDReaderList")
            val list = getAvailableList.invoke(readersObj) as? List<*>

            if (!list.isNullOrEmpty()) {
                for (device in list) {
                    if (device == null) continue
                    val getRfidReader = device.javaClass.getMethod("getRFIDReader")
                    val r = getRfidReader.invoke(device) ?: continue

                    val isConnectedMethod = r.javaClass.getMethod("isConnected")
                    val isConn = isConnectedMethod.invoke(r) as? Boolean ?: false
                    if (!isConn) {
                        try {
                            val connectMethod = r.javaClass.getMethod("connect")
                            connectMethod.invoke(r)
                        } catch (connEx: Exception) {
                            // Sled may need a second attempt or already in connecting state
                            try {
                                val reconnectMethod = r.javaClass.getMethod("reconnect")
                                reconnectMethod.invoke(r)
                            } catch (_: Exception) {
                                continue
                            }
                        }
                    }

                    reader = r
                    connectedDeviceName = try {
                        val getName = device.javaClass.getMethod("getName")
                        getName.invoke(device) as? String
                    } catch (_: Exception) { null }
                    connectedTransport = try {
                        val getTransport = device.javaClass.getMethod("getTransport")
                        getTransport.invoke(device) as? String
                    } catch (_: Exception) { null }

                    // Apply maximum power and dense tag singulation (Session S1, 1000 tags)
                    configureOptimalInventorySettings(r)
                    return true
                }
            }
            false
        } catch (_: Exception) {
            reader = null
            false
        }
    }

    private var configuredPowerDbm: Int = 30

    private fun applyAntennaPower(r: Any, targetDbm: Int): Boolean {
        return try {
            val config = r.javaClass.getField("Config").get(r)
            val antennas = config.javaClass.getField("Antennas").get(config)
            val caps = r.javaClass.getField("ReaderCapabilities").get(r)
            val getPowers = caps.javaClass.getMethod("getTransmitPowerLevelValues")
            val powers = getPowers.invoke(caps) as? IntArray ?: return false
            if (powers.isEmpty()) return false

            val targetVal = if (powers.last() > 100) targetDbm * 10 else targetDbm
            var bestIdx = 0
            var minDiff = Int.MAX_VALUE
            for (i in powers.indices) {
                val diff = kotlin.math.abs(powers[i] - targetVal)
                if (diff < minDiff) {
                    minDiff = diff
                    bestIdx = i
                }
            }

            val getRfConfig = antennas.javaClass.getMethod("getAntennaRfConfig", Int::class.javaPrimitiveType)
            val rfConfig = getRfConfig.invoke(antennas, 1)
            if (rfConfig != null) {
                val setPowerIdx = rfConfig.javaClass.getMethod("setTransmitPowerIndex", Int::class.javaPrimitiveType)
                setPowerIdx.invoke(rfConfig, bestIdx)
                val setRfConfig = antennas.javaClass.getMethod("setAntennaRfConfig", Int::class.javaPrimitiveType, rfConfig.javaClass)
                setRfConfig.invoke(antennas, 1, rfConfig)
                android.util.Log.d("ZebraDriver", "RF Transmit Power set to $targetDbm dBm (index $bestIdx, val ${powers[bestIdx]})")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("ZebraDriver", "Failed to apply RF Transmit Power: ${e.message}")
            false
        }
    }

    private fun configureOptimalInventorySettings(r: Any) {
        try {
            val config = r.javaClass.getField("Config").get(r)
            val antennas = config.javaClass.getField("Antennas").get(config)

            // 1. Apply user configured RF Transmit Power (or default to 30 dBm)
            applyAntennaPower(r, configuredPowerDbm)

            // 2. High-Density Gen2 Singulation: Session S1 + 1000 Tag Population to prevent packet collisions
            try {
                val getSingulation = antennas.javaClass.getMethod("getSingulationControl", Int::class.javaPrimitiveType)
                val singulation = getSingulation.invoke(antennas, 1)
                if (singulation != null) {
                    val sessionClass = Class.forName("com.zebra.rfid.api3.SESSION")
                    val sessionS1 = sessionClass.getField("SESSION_S1").get(null)
                    val setSession = singulation.javaClass.getMethod("setSession", sessionClass)
                    setSession.invoke(singulation, sessionS1)

                    val setTagPop = singulation.javaClass.getMethod("setTagPopulation", Short::class.javaPrimitiveType)
                    setTagPop.invoke(singulation, 1000.toShort())

                    val setSingulation = antennas.javaClass.getMethod("setSingulationControl", Int::class.javaPrimitiveType, singulation.javaClass)
                    setSingulation.invoke(antennas, 1, singulation)
                }
            } catch (_: Exception) {}

            // 3. Configure Continuous Scan Trigger: Stop Trigger = STOP_TRIGGER_TYPE_IMMEDIATE (Never timeout automatically)
            try {
                val startTriggerClass = Class.forName("com.zebra.rfid.api3.StartTrigger")
                val startTriggerTypeClass = Class.forName("com.zebra.rfid.api3.START_TRIGGER_TYPE")
                val startTrigger = startTriggerClass.getDeclaredConstructor().newInstance()
                val setStartType = startTriggerClass.getMethod("setTriggerType", startTriggerTypeClass)
                val startImmediate = startTriggerTypeClass.getField("START_TRIGGER_TYPE_IMMEDIATE").get(null)
                setStartType.invoke(startTrigger, startImmediate)

                val stopTriggerClass = Class.forName("com.zebra.rfid.api3.StopTrigger")
                val stopTriggerTypeClass = Class.forName("com.zebra.rfid.api3.STOP_TRIGGER_TYPE")
                val stopTrigger = stopTriggerClass.getDeclaredConstructor().newInstance()
                val setStopType = stopTriggerClass.getMethod("setTriggerType", stopTriggerTypeClass)
                val stopImmediate = stopTriggerTypeClass.getField("STOP_TRIGGER_TYPE_IMMEDIATE").get(null)
                setStopType.invoke(stopTrigger, stopImmediate)

                val setStartMethod = config.javaClass.getMethod("setStartTrigger", startTriggerClass)
                setStartMethod.invoke(config, startTrigger)

                val setStopMethod = config.javaClass.getMethod("setStopTrigger", stopTriggerClass)
                setStopMethod.invoke(config, stopTrigger)
                android.util.Log.d("ZebraDriver", "Configured StartTrigger and StopTrigger: STOP_TRIGGER_TYPE_IMMEDIATE (Continuous unbounded)")
            } catch (e: Exception) {
                android.util.Log.w("ZebraDriver", "Failed to set Start/Stop Trigger: ${e.message}")
            }

            // 4. Ensure trigger mode is RFID mode and layout is RFID
            try {
                val triggerModeClass = Class.forName("com.zebra.rfid.api3.ENUM_TRIGGER_MODE")
                val rfidMode = triggerModeClass.getField("RFID_MODE").get(null)
                val setTriggerMode = config.javaClass.getMethod("setTriggerMode", triggerModeClass, Boolean::class.javaPrimitiveType)
                setTriggerMode.invoke(config, rfidMode, true)
                android.util.Log.d("ZebraDriver", "Configured TriggerMode: RFID_MODE")
            } catch (e: Exception) {
                android.util.Log.w("ZebraDriver", "Failed to set TriggerMode: ${e.message}")
            }

            try {
                val keyLayoutClass = Class.forName("com.zebra.rfid.api3.ENUM_NEW_KEYLAYOUT_TYPE")
                val rfidKey = keyLayoutClass.getField("RFID").get(null)
                val setKeylayout = config.javaClass.getMethod("setKeylayoutType", keyLayoutClass, keyLayoutClass)
                setKeylayout.invoke(config, rfidKey, rfidKey)
                android.util.Log.d("ZebraDriver", "Configured KeylayoutType: RFID")
            } catch (e: Exception) {
                android.util.Log.w("ZebraDriver", "Failed to set KeylayoutType: ${e.message}")
            }

            // 5. Attach Sled Hardware Trigger listener to notify app of physical gun trigger pulls
            try {
                val events = r.javaClass.getField("Events").get(r)
                val setHandheldMethod = events.javaClass.getMethod("setHandheldEvent", Boolean::class.javaPrimitiveType)
                setHandheldMethod.invoke(events, true)

                val rfidEventsListenerClass = Class.forName("com.zebra.rfid.api3.RfidEventsListener")
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    rfidEventsListenerClass.classLoader,
                    arrayOf(rfidEventsListenerClass)
                ) { _, method, args ->
                    if (method.name == "eventStatusNotify" && args != null && args.isNotEmpty()) {
                        val rfidStatusEvents = args[0]
                        try {
                            val statusEventData = rfidStatusEvents.javaClass.getField("StatusEventData").get(rfidStatusEvents)
                            if (statusEventData != null) {
                                val getStatusEventType = statusEventData.javaClass.getMethod("getStatusEventType")
                                val statusEventType = getStatusEventType.invoke(statusEventData)?.toString()
                                if (statusEventType?.contains("HANDHELD_TRIGGER") == true) {
                                    val triggerData = statusEventData.javaClass.getField("HandheldTriggerEventData").get(statusEventData)
                                    if (triggerData != null) {
                                        val getHandheldEvent = triggerData.javaClass.getMethod("getHandheldEvent")
                                        val handheldEvent = getHandheldEvent.invoke(triggerData)?.toString()
                                        val isPressed = handheldEvent?.contains("PRESSED") == true
                                        android.util.Log.d("ZebraDriver", "Sled Trigger Event: $handheldEvent (pressed=$isPressed)")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener?.invoke(isPressed)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ZebraDriver", "Error handling status event: ${e.message}")
                        }
                    }
                    null
                }
                val addListener = events.javaClass.getMethod("addEventsListener", rfidEventsListenerClass)
                addListener.invoke(events, proxy)
                android.util.Log.d("ZebraDriver", "Sled Trigger listener attached successfully")
            } catch (e: Exception) {
                android.util.Log.w("ZebraDriver", "Failed to attach sled trigger listener: ${e.message}")
            }
        } catch (_: Exception) {}
    }

    private fun ensureConnected(): Boolean {
        if (isConnected()) return true
        val r = reader
        if (r != null) {
            try {
                val reconnectMethod = r.javaClass.getMethod("reconnect")
                reconnectMethod.invoke(r)
                if (isConnected()) return true
            } catch (_: Exception) {
                // fall through to connectInternal
            }
        }
        return connectInternal()
    }

    private fun isConnected(): Boolean {
        val r = reader ?: return false
        return try {
            val isConnMethod = r.javaClass.getMethod("isConnected")
            (isConnMethod.invoke(r) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun release(): Boolean {
        val r = reader ?: return false
        return try {
            val disconnectMethod = r.javaClass.getMethod("disconnect")
            disconnectMethod.invoke(r)
            reader = null
            isScanning = false
            connectedDeviceName = null
            connectedTransport = null
            true
        } catch (_: Exception) {
            reader = null
            false
        }
    }

    override fun startInventory(): Boolean {
        if (!ensureConnected()) return false
        val r = reader ?: return false
        return try {
            val actions = r.javaClass.getField("Actions").get(r)
            val inventory = actions.javaClass.getField("Inventory").get(actions)
            val performMethod = inventory.javaClass.getMethod("perform")
            performMethod.invoke(inventory)
            isScanning = true
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val r = reader ?: return false
        return try {
            val actions = r.javaClass.getField("Actions").get(r)
            val inventory = actions.javaClass.getField("Inventory").get(actions)
            val stopMethod = inventory.javaClass.getMethod("stop")
            stopMethod.invoke(inventory)
            isScanning = false
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        val r = reader ?: return emptyList()
        return try {
            val actions = r.javaClass.getField("Actions").get(r)
            val getTags = actions.javaClass.getMethod("getReadTags", Int::class.javaPrimitiveType)
            val tags = getTags.invoke(actions, 1000) as? Array<*> ?: return emptyList()

            if (tags.isEmpty()) return emptyList()

            val list = ArrayList<TagEvent>(tags.size)
            for (tag in tags) {
                if (tag == null) continue
                val getTagID = tag.javaClass.getMethod("getTagID")
                var epc = (getTagID.invoke(tag) as? String)?.trim()?.uppercase(Locale.US) ?: ""

                if (epc.isBlank()) {
                    try {
                        val getMemoryBankData = tag.javaClass.getMethod("getMemoryBankData")
                        val mbData = (getMemoryBankData.invoke(tag) as? String)?.trim()?.uppercase(Locale.US)
                        if (!mbData.isNullOrBlank()) {
                            epc = mbData
                        }
                    } catch (_: Exception) {}
                }

                // Never emit a blank or empty EPC string to UI
                if (epc.isBlank()) continue

                val getPeakRSSI = tag.javaClass.getMethod("getPeakRSSI")
                val rssi = (getPeakRSSI.invoke(tag) as? Number)?.toInt()

                list.add(
                    TagEvent(
                        epc = epc,
                        rssi = rssi,
                        source = TagSource.EPC,
                        seenAt = System.currentTimeMillis()
                    )
                )
            }
            list
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
            // Stop inventory if active so reader can perform tag access
            try {
                if (isScanning) {
                    stopInventory()
                    Thread.sleep(150)
                }
            } catch (_: Exception) {}

            var targetEpc = epc?.replace(" ", "")?.trim()
            if (targetEpc.isNullOrBlank()) {
                startInventory()
                Thread.sleep(300)
                val tags = readBufferedTagEvents()
                stopInventory()
                targetEpc = tags.maxByOrNull { it.rssi ?: -999 }?.epc ?: tags.firstOrNull()?.epc
            }
            if (targetEpc.isNullOrBlank()) return null

            val actions = r.javaClass.getField("Actions").get(r)
            val tagAccess = actions.javaClass.getField("TagAccess").get(actions)

            val readParamsClass = Class.forName("com.zebra.rfid.api3.TagAccess\$ReadAccessParams")
            val readParams = readParamsClass.getConstructor(tagAccess.javaClass).newInstance(tagAccess)

            val memBankClass = Class.forName("com.zebra.rfid.api3.MEMORY_BANK")
            val targetBank = when (bank) {
                MemoryBank.RESERVED -> memBankClass.getField("MEMORY_BANK_RESERVED").get(null)
                MemoryBank.EPC -> memBankClass.getField("MEMORY_BANK_EPC").get(null)
                MemoryBank.TID -> memBankClass.getField("MEMORY_BANK_TID").get(null)
                MemoryBank.USER -> memBankClass.getField("MEMORY_BANK_USER").get(null)
            }
            readParamsClass.getMethod("setMemoryBank", memBankClass).invoke(readParams, targetBank)
            readParamsClass.getMethod("setOffset", Int::class.javaPrimitiveType).invoke(readParams, ptr)
            readParamsClass.getMethod("setCount", Int::class.javaPrimitiveType).invoke(readParams, len)

            val pwd = pwdHex?.replace(" ", "")?.trim()?.toLongOrNull(16) ?: 0L
            readParamsClass.getMethod("setAccessPassword", Long::class.javaPrimitiveType).invoke(readParams, pwd)

            val readWaitMethod = tagAccess.javaClass.methods.firstOrNull {
                it.name == "readWait" && it.parameterTypes.size == 3
            } ?: tagAccess.javaClass.methods.firstOrNull {
                it.name == "readWait" && it.parameterTypes.size == 4
            }

            val tagData = if (readWaitMethod?.parameterTypes?.size == 4) {
                readWaitMethod.invoke(tagAccess, targetEpc, readParams, null, false)
            } else {
                readWaitMethod?.invoke(tagAccess, targetEpc, readParams, null)
            } ?: return null

            val getMemoryBankData = tagData.javaClass.getMethod("getMemoryBankData")
            val res = getMemoryBankData.invoke(tagData) as? String
            android.util.Log.d("ZebraDriver", "readMemoryBank bank=$bank epc=$targetEpc data=${res?.take(16)}")
            res
        } catch (ex: Exception) {
            val root = (ex as? java.lang.reflect.InvocationTargetException)?.targetException ?: ex
            android.util.Log.w("ZebraDriver", "readMemoryBank failed: ${root.javaClass.simpleName}: ${root.message}")
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
        val previousPower = getPower()
        return try {
            // Stop inventory if active so reader can perform tag access
            try {
                if (isScanning) {
                    stopInventory()
                    Thread.sleep(150)
                }
            } catch (_: Exception) {}

            // UHF RFID Tag writing requires full RF energy (30 dBm) to power the tag's EEPROM charge pump
            try {
                applyAntennaPower(r, 30)
            } catch (_: Exception) {}

            var targetEpc = epc?.replace(" ", "")?.trim()
            // If no EPC was provided, scan to target the singulated tag nearest to antenna
            if (targetEpc.isNullOrBlank()) {
                startInventory()
                Thread.sleep(350)
                val tags = readBufferedTagEvents()
                stopInventory()
                targetEpc = tags.maxByOrNull { it.rssi ?: -999 }?.epc
                    ?: tags.firstOrNull { it.epc.isNotBlank() }?.epc
            }

            if (targetEpc.isNullOrBlank()) {
                android.util.Log.w("ZebraDriver", "writeMemoryBank: No tag detected in RF field to target.")
                return false
            }

            val actualTarget = targetEpc

            val actions = r.javaClass.getField("Actions").get(r)
            val tagAccess = actions.javaClass.getField("TagAccess").get(actions)

            val writeParamsClass = Class.forName("com.zebra.rfid.api3.TagAccess\$WriteAccessParams")
            val memBankClass = Class.forName("com.zebra.rfid.api3.MEMORY_BANK")
            val targetBank = when (bank) {
                MemoryBank.RESERVED -> memBankClass.getField("MEMORY_BANK_RESERVED").get(null)
                MemoryBank.EPC -> memBankClass.getField("MEMORY_BANK_EPC").get(null)
                MemoryBank.TID -> memBankClass.getField("MEMORY_BANK_TID").get(null)
                MemoryBank.USER -> memBankClass.getField("MEMORY_BANK_USER").get(null)
            }

            // Gen2 standard: EPC data starts at Word 2 (Word 0=CRC, Word 1=PC)
            val effectivePtr = if (bank == MemoryBank.EPC && ptr == 0) 2 else ptr

            // Clean data hex and ensure multiple of 4 hex chars (16-bit words)
            var cleanHex = dataHex.replace(" ", "").trim().uppercase(Locale.US)
            if (cleanHex.isEmpty()) return false
            val rem = cleanHex.length % 4
            if (rem != 0) {
                cleanHex = cleanHex.padStart(cleanHex.length + (4 - rem), '0')
            }

            val isEpcBank = (bank == MemoryBank.EPC && effectivePtr == 2)
            val wordLength = (cleanHex.length / 4).coerceAtLeast(1)
            val wordCount = wordLength.coerceIn(1, 31)
            val pcWordHex = String.format(Locale.US, "%04X", (wordCount shl 11))

            // Access password
            val pwd = pwdHex?.replace(" ", "")?.trim()?.toLongOrNull(16) ?: 0L

            val tagDataClass = Class.forName("com.zebra.rfid.api3.TagData")
            val getOpStatus = tagDataClass.getMethod("getOpStatus")

            // Try different method name variants Zebra SDK uses across firmware versions
            val allMethods = tagAccess.javaClass.methods
            val writeWaitMethod = allMethods.firstOrNull {
                (it.name == "writeWait" || it.name == "Write" || it.name == "write" || it.name == "performWrite") &&
                    it.parameterTypes.size == 4
            } ?: allMethods.firstOrNull {
                (it.name == "writeWait" || it.name == "Write" || it.name == "write" || it.name == "performWrite") &&
                    it.parameterTypes.size >= 3
            }

            if (writeWaitMethod == null) {
                android.util.Log.e("ZebraDriver", "writeWait method NOT FOUND on TagAccess. Bank=$bank")
                return false
            }

            fun doWriteWait(target: String, offset: Int, data: String, lenWords: Int): Boolean {
                return try {
                    val p = writeParamsClass.getConstructor(tagAccess.javaClass).newInstance(tagAccess)
                    writeParamsClass.getMethod("setMemoryBank", memBankClass).invoke(p, targetBank)
                    writeParamsClass.getMethod("setOffset", Int::class.javaPrimitiveType).invoke(p, offset)
                    writeParamsClass.getMethod("setWriteData", String::class.java).invoke(p, data)
                    try {
                        writeParamsClass.getMethod("setWriteDataLength", Int::class.javaPrimitiveType).invoke(p, lenWords)
                    } catch (_: Exception) {}
                    writeParamsClass.getMethod("setAccessPassword", Long::class.javaPrimitiveType).invoke(p, pwd)
                    val td = tagDataClass.getConstructor().newInstance()
                    if (writeWaitMethod.parameterTypes.size == 4) {
                        writeWaitMethod.invoke(tagAccess, target, p, null, td)
                    } else {
                        writeWaitMethod.invoke(tagAccess, target, p, td)
                    }
                    val st = getOpStatus.invoke(td)?.toString()?.uppercase(Locale.US) ?: ""
                    android.util.Log.d("ZebraDriver", "doWriteWait bank=$bank offset=$offset target=$target data=${data.take(16)} opStatus='$st'")
                    st.contains("SUCCESS") || st.isEmpty() || st.contains("0")
                } catch (ex: Exception) {
                    val realEx = (ex as? java.lang.reflect.InvocationTargetException)?.targetException ?: ex
                    android.util.Log.w("ZebraDriver", "doWriteWait bank=$bank offset=$offset failed for target=$target: ${realEx.javaClass.simpleName}: ${realEx.message}")
                    false
                }
            }

            val isSuccess: Boolean
            if (isEpcBank) {
                // Step 1: Write EPC data at Word 2
                val epcOk = doWriteWait(actualTarget, 2, cleanHex, wordLength)
                // Step 2: Set PC word (Word 1) so tag backscatter length matches EXACT user word count
                // If EPC write succeeded, the tag is now identified by cleanHex
                val targetForPc = if (epcOk) cleanHex else actualTarget
                val pcOk = doWriteWait(targetForPc, 1, pcWordHex, 1) || (targetForPc != actualTarget && doWriteWait(actualTarget, 1, pcWordHex, 1))

                isSuccess = epcOk || pcOk
                android.util.Log.d("ZebraDriver", "writeMemoryBank EPC bank: epcOk=$epcOk pcOk=$pcOk (PC=$pcWordHex, wordCount=$wordCount words)")
            } else {
                isSuccess = doWriteWait(actualTarget, effectivePtr, cleanHex, wordLength)
            }

            isSuccess
        } catch (e: Exception) {
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            android.util.Log.e("ZebraDriver", "writeMemoryBank exception: ${root.javaClass.simpleName}: ${root.message}", root)
            false
        } finally {
            // Restore user configured power if it was different
            try {
                if (previousPower != null && previousPower != 30) {
                    applyAntennaPower(r, previousPower)
                }
            } catch (_: Exception) {}
        }
    }

    override fun setLockState(
        epc: String?,
        accessPasswordHex: String?,
        lock: Boolean
    ): Boolean {
        val r = reader ?: return false
        val previousPower = getPower()

        return try {
            // Stop inventory if active so reader can perform tag access
            try {
                if (isScanning) {
                    stopInventory()
                    Thread.sleep(150)
                }
            } catch (_: Exception) {}

            // UHF RFID Tag locking requires full RF energy (30 dBm) to power the tag's EEPROM charge pump
            try {
                applyAntennaPower(r, 30)
            } catch (_: Exception) {}

            val target = if (epc.isNullOrBlank()) {
                val tags = readBufferedTagEvents()
                tags.firstOrNull()?.epc ?: ""
            } else epc.trim()

            if (target.isBlank()) {
                android.util.Log.w("ZebraRfidApi3", "setLockState: target tag EPC is required")
                return false
            }

            val actions = r.javaClass.getField("Actions").get(r)
            val tagAccess = actions.javaClass.getField("TagAccess").get(actions)

            val lockParamsClass = Class.forName("com.zebra.rfid.api3.TagAccess\$LockAccessParams")
            val lockParams = lockParamsClass.getConstructor(tagAccess.javaClass).newInstance(tagAccess)

            val pwd = accessPasswordHex?.replace(" ", "")?.trim()?.toLongOrNull(16) ?: 0L
            lockParamsClass.getMethod("setAccessPassword", Long::class.javaPrimitiveType).invoke(lockParams, pwd)

            val lockPrivilegeClass = Class.forName("com.zebra.rfid.api3.LOCK_PRIVILEGE")
            val lockDataFieldClass = Class.forName("com.zebra.rfid.api3.LOCK_DATA_FIELD")
            val priv = if (lock) {
                lockPrivilegeClass.getField("LOCK_PRIVILEGE_NONE").get(null)
            } else {
                lockPrivilegeClass.getField("LOCK_PRIVILEGE_READ_WRITE").get(null)
            }
            val field = lockDataFieldClass.getField("LOCK_EPC_MEMORY").get(null)
            val setPrivMethod = lockParamsClass.getMethod("setLockPrivilege", lockDataFieldClass, lockPrivilegeClass)
            setPrivMethod.invoke(lockParams, field, priv)

            val lockWaitMethod = tagAccess.javaClass.methods.firstOrNull {
                it.name == "lockWait" && it.parameterTypes.size >= 2
            }
            lockWaitMethod?.invoke(tagAccess, target, lockParams, null)
            android.util.Log.i("ZebraRfidApi3", "setLockState: successfully executed lock=$lock for target=$target")
            true
        } catch (e: Exception) {
            val root = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            android.util.Log.e("ZebraRfidApi3", "setLockState error: ${root.message}", root)
            false
        } finally {
            try {
                if (previousPower != null && previousPower != 30) {
                    applyAntennaPower(r, previousPower)
                }
            } catch (_: Exception) {}
        }
    }

    override fun setPower(powerDbm: Int): Boolean {
        configuredPowerDbm = powerDbm
        val r = reader ?: return true
        return applyAntennaPower(r, powerDbm)
    }

    override fun getPower(): Int? {
        val r = reader ?: return configuredPowerDbm
        return try {
            val config = r.javaClass.getField("Config").get(r)
            val antennas = config.javaClass.getField("Antennas").get(config)
            val getRfConfig = antennas.javaClass.getMethod("getAntennaRfConfig", Int::class.javaPrimitiveType)
            val rfConfig = getRfConfig.invoke(antennas, 1) ?: return configuredPowerDbm
            val getPowerIdx = rfConfig.javaClass.getMethod("getTransmitPowerIndex")
            val idx = (getPowerIdx.invoke(rfConfig) as? Number)?.toInt() ?: return configuredPowerDbm
            val caps = r.javaClass.getField("ReaderCapabilities").get(r)
            val getPowers = caps.javaClass.getMethod("getTransmitPowerLevelValues")
            val powers = getPowers.invoke(caps) as? IntArray ?: return configuredPowerDbm
            if (idx in powers.indices) {
                val rawVal = powers[idx]
                if (rawVal > 100) rawVal / 10 else rawVal
            } else configuredPowerDbm
        } catch (_: Exception) {
            configuredPowerDbm
        }
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        val power = getPower()
        val conn = isConnected()
        val detail = if (conn) {
            val name = connectedDeviceName ?: "Zebra RFID Sled"
            val transport = connectedTransport ?: "Bluetooth"
            "$name ($transport) Connected"
        } else {
            "Zebra RFID Not Connected (Auto-scanning paired Bluetooth sleds...)"
        }
        return ReaderDiagnosticsSummary(
            sdkReady = true,
            readerOpen = conn,
            powerDbm = power,
            regionOrFrequency = "Zebra RFID API3",
            batteryNote = if (conn) "Sled Connected" else "Searching for RFD40/RFD90",
            detailLine = detail
        )
    }
}
