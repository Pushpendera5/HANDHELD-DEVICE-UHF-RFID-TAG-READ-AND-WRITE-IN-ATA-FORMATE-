
package com.rfidsoftwares.rfid

import android.content.Context
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Chainway UHF gateway wrapper around `RFIDWithUHFUART`.
 *
 * Uses reflective invocations so it compiles portably on systems with or without physical Chainway SDK JARs.
 */
class ChainwayUhfReaderGateway : UhfReaderGateway {

    private var reader: Any? = null

    override fun init(context: Context): Boolean {
        return try {
            val clazz = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
            val getInstance = clazz.getMethod("getInstance")
            val r = getInstance.invoke(null) ?: return false
            reader = r
            val ok = initReader(r, context)
            if (ok) {
                try {
                    clazz.getMethod("setEPCMode").invoke(r)
                } catch (_: Exception) {
                }
                applyStoredUhfConfig(r, context)
            } else {
                try {
                    clazz.getMethod("free").invoke(r)
                } catch (_: Exception) {
                }
                reader = null
            }
            ok
        } catch (_: Exception) {
            reader = null
            false
        }
    }

    private fun initReader(r: Any, context: Context): Boolean {
        return runCatching {
            // 1. Try init(Context) directly
            val methodWithCtx = r.javaClass.methods.firstOrNull {
                it.name == "init" && it.parameterCount == 1 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (methodWithCtx != null) {
                val ok1 = runCatching { (methodWithCtx.invoke(r, context) as? Boolean) == true }.getOrDefault(false)
                if (ok1) return@runCatching true
            }

            // 2. Try no-arg init()
            val noArgMethod = r.javaClass.methods.firstOrNull {
                it.name == "init" && it.parameterCount == 0
            }
            if (noArgMethod != null) {
                val ok2 = runCatching { (noArgMethod.invoke(r) as? Boolean) == true }.getOrDefault(false)
                if (ok2) return@runCatching true
            }

            // 3. Reset port via free() and retry init(context)
            try {
                r.javaClass.getMethod("free").invoke(r)
                Thread.sleep(100)
            } catch (_: Exception) {
            }

            if (methodWithCtx != null) {
                val ok3 = runCatching { (methodWithCtx.invoke(r, context) as? Boolean) == true }.getOrDefault(false)
                if (ok3) return@runCatching true
            }

            // 4. Try init_R2000() as fallback for R2000 chipsets
            val r2000 = r.javaClass.methods.firstOrNull { it.name == "init_R2000" }
            if (r2000 != null) {
                val ok4 = runCatching { (r2000.invoke(r) as? Boolean) == true }.getOrDefault(false)
                if (ok4) return@runCatching true
            }

            false
        }.getOrElse { false }
    }

    override fun startInventory(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "startInventoryTag" }
            (m?.invoke(r) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun stopInventory(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "stopInventory" }
            (m?.invoke(r) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    override fun free(): Boolean {
        val r = reader ?: return false
        return try {
            val m = r.javaClass.methods.firstOrNull { it.name == "free" }
            (m?.invoke(r) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        } finally {
            reader = null
        }
    }

    fun setPower(powerDbm: Int): Boolean {
        val r = reader ?: return false
        return try {
            tryInvokeIntSetter(
                target = r,
                value = powerDbm,
                methodNames = listOf("setPower", "setPowerLevel", "setPowerDbm", "setOutputPower"),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readBufferedTagEvents(): List<TagEvent> {
        val r = reader ?: return emptyList()
        return try {
            val readMethod = r.javaClass.methods.firstOrNull { it.name == "readTagFromBuffer" } ?: return emptyList()
            val info = readMethod.invoke(r) ?: return emptyList()

            val getEpcMethod = info.javaClass.methods.firstOrNull { it.name == "getEPC" || it.name == "getEpc" }
            val epcRaw = getEpcMethod?.invoke(info) as? String
            val epc = epcRaw?.trim()?.uppercase(Locale.US)

            if (epc.isNullOrBlank()) {
                emptyList()
            } else {
                val getRssiMethod = info.javaClass.methods.firstOrNull { it.name == "getRssi" || it.name == "getRSSI" }
                val rawRssi = getRssiMethod?.invoke(info)?.toString()
                val rssi = parseRssi(rawRssi)
                listOf(
                    TagEvent(
                        epc = epc,
                        rssi = rssi,
                        source = TagSource.EPC,
                        seenAt = System.currentTimeMillis(),
                    )
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseRssi(rawRssi: String?): Int? {
        val normalized = rawRssi?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return normalized.toIntOrNull()
            ?: normalized.toDoubleOrNull()?.roundToInt()
            ?: Regex("-?\\d+(\\.\\d+)?").find(normalized)?.value?.toDoubleOrNull()?.roundToInt()
    }

    override fun readDiagnosticsSummary(): ReaderDiagnosticsSummary {
        val sessionReader = reader
        val probe = sessionReader ?: runCatching {
            val clazz = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
            clazz.getMethod("getInstance").invoke(null)
        }.getOrNull()
            ?: return ReaderDiagnosticsSummary(
                sdkReady = false,
                readerOpen = false,
                powerDbm = null,
                regionOrFrequency = null,
                batteryNote = null,
                detailLine = "UHF SDK instance unavailable",
            )

        val (power, region, batteryNote) = reflectPowerRegionBattery(probe)
        val detail = buildString {
            if (sessionReader != null) {
                append("Session active on this gateway (EPC mode)")
            } else {
                append("SDK reachable (no open session on this gateway wrapper)")
            }
            if (power != null) append(" · power≈${power} dBm")
            if (!region.isNullOrBlank()) append(" · $region")
            if (!batteryNote.isNullOrBlank()) append(" · $batteryNote")
        }
        return ReaderDiagnosticsSummary(
            sdkReady = true,
            readerOpen = sessionReader != null,
            powerDbm = power,
            regionOrFrequency = region,
            batteryNote = batteryNote,
            detailLine = detail,
        )
    }

    private fun reflectPowerRegionBattery(r: Any): Triple<Int?, String?, String?> {
        var power: Int? = null
        var region: String? = null
        var battery: String? = null
        try {
            val mPower = r.javaClass.methods.find { it.name == "getPower" && it.parameterCount == 0 }
            val rawPower = mPower?.invoke(r)
            power = (rawPower as? Number)?.toInt()
        } catch (_: Exception) {
        }
        try {
            val mFreq = r.javaClass.methods.find {
                (it.name == "getFrequencyMode" || it.name == "getFreHop" || it.name == "getRegion") &&
                    it.parameterCount == 0
            }
            val fr = mFreq?.invoke(r)
            region = fr?.toString()
        } catch (_: Exception) {
        }
        try {
            val batteryMethods = listOf(
                "getBatteryLevel", "getBattery", "getBatteryPercent", "getBatteryVoltage",
                "getPowerLevel", "getVoltage",
            )
            for (name in batteryMethods) {
                val m = r.javaClass.methods.find { it.name == name && it.parameterCount == 0 } ?: continue
                val v = m.invoke(r) ?: continue
                battery = when (v) {
                    is Number -> "${name}=${v}"
                    else -> "${name}=${v}"
                }
                break
            }
        } catch (_: Exception) {
        }
        return Triple(power, region, battery)
    }

    private fun applyStoredUhfConfig(r: Any, context: Context) {
        val cfg = UhfConfigPrefs.load(context)
        cfg.powerDbm?.let { p ->
            tryInvokeIntSetter(
                target = r,
                value = p,
                methodNames = listOf("setPower", "setPowerLevel", "setPowerDbm", "setOutputPower"),
            )
        }
        cfg.regionCode?.let { region ->
            tryInvokeStringSetter(
                target = r,
                value = region,
                methodNames = listOf("setRegion", "setRegionCode", "setRegulatoryDomain"),
            )
        }
    }

    private fun tryInvokeIntSetter(target: Any, value: Int, methodNames: List<String>) {
        val methods = target.javaClass.methods.filter { it.name in methodNames && it.parameterCount == 1 }
        for (m in methods) {
            val pType = m.parameterTypes.firstOrNull() ?: continue
            try {
                when {
                    pType == Int::class.javaPrimitiveType || pType == Integer::class.java -> m.invoke(target, value)
                    pType == Double::class.javaPrimitiveType || pType == java.lang.Double::class.java -> m.invoke(target, value.toDouble())
                    pType == Float::class.javaPrimitiveType || pType == java.lang.Float::class.java -> m.invoke(target, value.toFloat())
                    else -> continue
                }
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun tryInvokeStringSetter(target: Any, value: String, methodNames: List<String>) {
        val methods = target.javaClass.methods.filter { it.name in methodNames && it.parameterCount == 1 }
        for (m in methods) {
            val pType = m.parameterTypes.firstOrNull() ?: continue
            if (pType != String::class.java) continue
            try {
                m.invoke(target, value)
                return
            } catch (_: Exception) {
            }
        }
    }
}
