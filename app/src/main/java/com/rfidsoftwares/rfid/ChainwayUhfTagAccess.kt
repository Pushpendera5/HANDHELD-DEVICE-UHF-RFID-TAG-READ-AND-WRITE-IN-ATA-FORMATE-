
package com.rfidsoftwares.rfid

import android.content.Context
import com.rfidsoftwares.controller.epcupdate.UpdateEpcFormat
import java.util.Locale

/**
 * Best-effort tag access wrapper (read/write/lock) for Chainway UHF SDK.
 *
 * Uses reflection to find common method names so it compiles portably without hard requirement on SDK JARs.
 */
class ChainwayUhfTagAccess {
  enum class MemoryBank(val bankId: Int) {
    RESERVED(0),
    EPC(1),
    TID(2),
    USER(3),
  }

  data class TagSnapshot(
    val epc: String,
    val rssi: Int?,
  )

  private var reader: Any? = null

  fun init(context: Context): Boolean {
    return try {
      val clazz = Class.forName("com.rscja.deviceapi.RFIDWithUHFUART")
      val r = clazz.getMethod("getInstance").invoke(null) ?: return false
      reader = r
      val ok = initReader(r, context)
      if (!ok) {
        try {
          clazz.getMethod("free").invoke(r)
        } catch (_: Exception) {
        }
        reader = null
        return false
      }

      try {
        clazz.getMethod("setEPCMode").invoke(r)
      } catch (_: Exception) {
      }
      applyStoredUhfConfigBestEffort(r, context)
      true
    } catch (_: Exception) {
      reader = null
      false
    }
  }

  fun free(): Boolean {
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

  /**
   * Runs a short inventory window and returns the first EPC observed.
   */
  fun scanNearestTag(timeoutMs: Long = 1800L): TagSnapshot? {
    val r = reader ?: return null
    val started = try {
      val m = r.javaClass.methods.firstOrNull { it.name == "startInventoryTag" }
      (m?.invoke(r) as? Boolean) ?: false
    } catch (_: Exception) {
      false
    }
    if (!started) return null

    val deadline = System.currentTimeMillis() + timeoutMs
    var found: TagSnapshot? = null
    while (System.currentTimeMillis() < deadline) {
      val info: Any? = try {
        val readMethod = r.javaClass.methods.firstOrNull { it.name == "readTagFromBuffer" }
        readMethod?.invoke(r)
      } catch (_: Exception) {
        null
      }
      if (info != null) {
        val epcMethod = info.javaClass.methods.firstOrNull { it.name == "getEPC" || it.name == "getEpc" }
        val epc = (epcMethod?.invoke(info) as? String)?.trim()?.uppercase(Locale.US)
        if (!epc.isNullOrBlank()) {
          val rssiMethod = info.javaClass.methods.firstOrNull { it.name == "getRssi" || it.name == "getRSSI" }
          val rssiStr = rssiMethod?.invoke(info)?.toString()?.trim()
          val rssi = rssiStr?.toIntOrNull()
          found = TagSnapshot(epc = epc, rssi = rssi)
          break
        }
      }
      try {
        Thread.sleep(40)
      } catch (_: InterruptedException) {
        break
      }
    }

    try {
      val mStop = r.javaClass.methods.firstOrNull { it.name == "stopInventory" }
      mStop?.invoke(r)
    } catch (_: Exception) {
    }
    return found
  }

  /**
   * Read memory from a selected tag.
   */
  fun readMemory(
    epc: String?,
    bank: MemoryBank,
    wordPtr: Int,
    wordCount: Int,
    accessPasswordHex: String?,
  ): String? {
    val r = reader ?: return null
    if (wordPtr < 0 || wordCount <= 0) return null

    selectTagBestEffort(r, epc)
    val pwd = normalizePwd(accessPasswordHex)
    val targetEpc = normalizeHexString(epc)

    val readArgs = mutableListOf<Array<Any>>()
    if (targetEpc != null) {
      readArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount, targetEpc, MemoryBank.EPC.bankId, 32, targetEpc.length * 4))
      readArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount, targetEpc, MemoryBank.EPC.bankId, 2, (targetEpc.length / 4).coerceAtLeast(1)))
    }
    readArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount))
    readArgs.add(arrayOf(bank.bankId, wordPtr, wordCount))

    val result: Any? = invokeFirstMatch(
      target = r,
      methodNames = listOf("readData", "readTagData", "read"),
      args = readArgs,
    )
    return normalizeHex(result)
  }

  /**
   * Write memory to a selected tag.
   */
  fun writeMemory(
    epc: String?,
    bank: MemoryBank,
    wordPtr: Int,
    dataHex: String,
    accessPasswordHex: String?,
  ): Boolean {
    val r = reader ?: return false
    if (wordPtr < 0) return false
    val hex = normalizeHexString(dataHex) ?: return false
    if (hex.length % 4 != 0) return false

    selectTagBestEffort(r, epc)
    val pwd = normalizePwd(accessPasswordHex)
    val targetEpc = normalizeHexString(epc)
    val wordCount = hex.length / 4

    val writeArgs = mutableListOf<Array<Any>>()
    if (targetEpc != null) {
      writeArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount, hex, MemoryBank.EPC.bankId, 32, targetEpc.length * 4, targetEpc))
      writeArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount, hex, MemoryBank.EPC.bankId, 2, (targetEpc.length / 4).coerceAtLeast(1), targetEpc))
    }
    writeArgs.add(arrayOf(pwd, bank.bankId, wordPtr, wordCount, hex))
    writeArgs.add(arrayOf(bank.bankId, wordPtr, wordCount, hex))

    val result: Any? = invokeFirstMatch(
      target = r,
      methodNames = listOf("writeData", "writeTagData", "write"),
      args = writeArgs,
    )
    return when (result) {
      is Boolean -> result
      is Int -> result == 0 || result == 1
      else -> false
    }
  }

  fun updateEpc(
    currentEpc: String?,
    newEpcHex: String,
    accessPasswordHex: String?,
    format: UpdateEpcFormat = UpdateEpcFormat.EPC_96,
  ): Boolean {
    val r = reader ?: return false
    val newEpcClean = normalizeHexString(newEpcHex) ?: return false
    if (newEpcClean.length % 4 != 0) return false

    val pwd = normalizePwd(accessPasswordHex)
    val targetEpc = normalizeHexString(currentEpc)

    val wordPtr = 2
    val wordCount = newEpcClean.length / 4

    val directArgs = mutableListOf<Array<Any>>()
    if (targetEpc != null) {
      directArgs.add(arrayOf(pwd, targetEpc, newEpcClean))
      directArgs.add(arrayOf(pwd, MemoryBank.EPC.bankId, wordPtr, wordCount, newEpcClean, targetEpc))
    }
    directArgs.add(arrayOf(pwd, newEpcClean))
    directArgs.add(arrayOf(newEpcClean))

    val directResult = invokeFirstMatch(
      target = r,
      methodNames = listOf("writeEPC", "updateEPC", "setEPC", "writeEpc"),
      args = directArgs,
    )
    val directOk = when (directResult) {
      is Boolean -> directResult
      is Int -> directResult == 0 || directResult == 1
      else -> false
    }
    if (directOk) return true

    return writeMemory(
      epc = currentEpc,
      bank = MemoryBank.EPC,
      wordPtr = wordPtr,
      dataHex = newEpcClean,
      accessPasswordHex = accessPasswordHex,
    )
  }

  fun setAccessPassword(epc: String?, newAccessPasswordHex: String): Boolean {
    return updateAccessPassword(
      epc = epc,
      currentAccessPasswordHex = null,
      newAccessPasswordHex = newAccessPasswordHex,
    )
  }

  fun updateAccessPassword(
    epc: String?,
    currentAccessPasswordHex: String?,
    newAccessPasswordHex: String,
  ): Boolean {
    val pwdHex = normalizeHexString(newAccessPasswordHex) ?: return false
    if (pwdHex.length != 8) return false
    val authPwd = normalizePwd(currentAccessPasswordHex)

    return writeMemory(
      epc = epc,
      bank = MemoryBank.RESERVED,
      wordPtr = 2,
      dataHex = pwdHex,
      accessPasswordHex = authPwd,
    )
  }

  fun setLockState(
    epc: String?,
    accessPasswordHex: String?,
    lock: Boolean,
  ): Boolean {
    val r = reader ?: return false
    val pwd = normalizePwd(accessPasswordHex)
    val lockType = if (lock) "1" else "0"

    val result = invokeFirstMatch(
      target = r,
      methodNames = listOf("lockMem", "setLock", "lockTag", "lock"),
      args = listOf(
        arrayOf(pwd, lockType),
        arrayOf(pwd, (epc ?: ""), lockType),
        arrayOf(lockType),
      )
    )
    return when (result) {
      is Boolean -> result
      is Int -> result == 0 || result == 1
      else -> false
    }
  }

  private fun initReader(r: Any, context: Context): Boolean {
    return runCatching {
      val methodWithCtx = r.javaClass.methods.firstOrNull {
        it.name == "init" && it.parameterCount == 1 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
      }
      if (methodWithCtx != null) {
        val ok1 = runCatching { (methodWithCtx.invoke(r, context) as? Boolean) == true }.getOrDefault(false)
        if (ok1) return@runCatching true
      }

      val noArgMethod = r.javaClass.methods.firstOrNull { it.name == "init" && it.parameterCount == 0 }
      if (noArgMethod != null) {
        val ok2 = runCatching { (noArgMethod.invoke(r) as? Boolean) == true }.getOrDefault(false)
        if (ok2) return@runCatching true
      }

      try {
        r.javaClass.getMethod("free").invoke(r)
        Thread.sleep(100)
      } catch (_: Exception) {
      }

      if (methodWithCtx != null) {
        val ok3 = runCatching { (methodWithCtx.invoke(r, context) as? Boolean) == true }.getOrDefault(false)
        if (ok3) return@runCatching true
      }

      val r2000 = r.javaClass.methods.firstOrNull { it.name == "init_R2000" }
      if (r2000 != null) {
        val ok4 = runCatching { (r2000.invoke(r) as? Boolean) == true }.getOrDefault(false)
        if (ok4) return@runCatching true
      }

      false
    }.getOrElse { false }
  }

  private fun selectTagBestEffort(r: Any, epc: String?) {
    val value = epc?.trim()?.uppercase(Locale.US)
    if (value.isNullOrBlank()) return
    invokeFirstMatch(
      target = r,
      methodNames = listOf("setFilter", "selectEPC", "selectTag", "setSelectParam"),
      args = listOf(
        arrayOf(MemoryBank.EPC.bankId, 32, value.length * 4, value),
        arrayOf(value),
        arrayOf(value.toByteArray()),
      )
    )
  }

  private fun invokeFirstMatch(target: Any, methodNames: List<String>, args: List<Array<Any>>): Any? {
    for (name in methodNames) {
      val candidates = target.javaClass.methods.filter { it.name == name }
      for (m in candidates) {
        for (argSet in args) {
          if (m.parameterCount != argSet.size) continue
          try {
            val result = m.invoke(target, *coerceArgs(m.parameterTypes, argSet))
            if (result is Boolean && !result) continue
            if (result is Int && result != 0 && result != 1) continue
            return result
          } catch (_: Exception) {
          }
        }
      }
    }
    return null
  }

  private fun coerceArgs(paramTypes: Array<Class<*>>, args: Array<Any>): Array<Any?> {
    val out = arrayOfNulls<Any>(args.size)
    for (i in args.indices) {
      val v = args[i]
      val t = paramTypes[i]
      out[i] = when {
        (t == Int::class.javaPrimitiveType || t == Integer::class.java) && v is Number -> v.toInt()
        (t == Boolean::class.javaPrimitiveType || t == java.lang.Boolean::class.java) && v is Boolean -> v
        t == String::class.java -> v.toString()
        t.isInstance(v) -> v
        else -> v
      }
    }
    return out
  }

  private fun normalizePwd(hex: String?): String {
    val normalized = normalizeHexString(hex) ?: "00000000"
    return normalized.padStart(8, '0').takeLast(8)
  }

  private fun normalizeHexString(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return null
    val cleaned = s.replace("0x", "", ignoreCase = true).replace("\\s+".toRegex(), "")
    if (!cleaned.matches(Regex("^[0-9a-fA-F]+$"))) return null
    return cleaned.uppercase(Locale.US)
  }

  private fun normalizeHex(value: Any?): String? {
    return when (value) {
      is String -> normalizeHexString(value)
      is ByteArray -> value.joinToString(separator = "") { b -> "%02X".format(b) }
      else -> null
    }
  }

  private fun applyStoredUhfConfigBestEffort(r: Any, context: Context) {
    val cfg = com.rfidsoftwares.common.prefs.UhfConfigPrefs.load(context)
    cfg.powerDbm?.let { p ->
      invokeFirstMatch(
        target = r,
        methodNames = listOf("setPower", "setPowerLevel", "setPowerDbm", "setOutputPower"),
        args = listOf(
          arrayOf(p),
          arrayOf(p.toDouble()),
          arrayOf(p.toFloat()),
        )
      )
    }
    cfg.regionCode?.let { region ->
      invokeFirstMatch(
        target = r,
        methodNames = listOf("setRegion", "setRegionCode", "setRegulatoryDomain"),
        args = listOf(arrayOf(region))
      )
    }
  }
}
