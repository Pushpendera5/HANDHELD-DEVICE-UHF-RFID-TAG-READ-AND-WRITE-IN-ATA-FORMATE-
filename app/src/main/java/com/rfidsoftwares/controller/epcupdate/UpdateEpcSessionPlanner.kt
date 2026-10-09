
package com.rfidsoftwares.controller.epcupdate

import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class UpdateEpcFormat(val label: String, val totalHexChars: Int, val pcWordHex: String) {
    EPC_96("EPC-96", 24, "3000"),
    EPC_128("EPC-128", 32, "4000"),
}

data class UpdateEpcConfig(
    val prefixHex: String,
    val nextSerial: BigInteger,
    val format: UpdateEpcFormat,
) {
    init {
        require(prefixHex.isNotBlank()) { "Prefix is required." }
        require(prefixHex.matches(HEX_REGEX)) { "Prefix must be HEX only." }
        require(prefixHex.length < format.totalHexChars) {
            "Prefix is too long for ${format.label}."
        }
        require(nextSerial >= BigInteger.ZERO) { "Serial must be zero or greater." }
    }

    val normalizedPrefix: String = prefixHex.trim().uppercase(Locale.US)

    companion object {
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

        fun parse(prefixHex: String, serialText: String, format: UpdateEpcFormat): UpdateEpcConfig {
            val prefix = prefixHex.trim().replace("\\s+".toRegex(), "").uppercase(Locale.US)
            val serial = serialText.trim()
            require(serial.matches(Regex("^\\d+$"))) { "Serial must be numeric." }
            return UpdateEpcConfig(
                prefixHex = prefix,
                nextSerial = BigInteger(serial),
                format = format,
            )
        }
    }
}

class UpdateEpcSessionPlanner(config: UpdateEpcConfig) {
    private val updatedEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    var config: UpdateEpcConfig = config
        private set

    fun nextEpc(): String {
        val serial = config.nextSerial.toString()
        val remaining = config.format.totalHexChars - config.normalizedPrefix.length
        require(serial.length <= remaining) {
            "Serial is too large for ${config.format.label} with this prefix."
        }
        return config.normalizedPrefix + serial.padStart(remaining, '0')
    }

    fun nextSerialText(): String = config.nextSerial.toString()

    fun shouldIgnore(epcHex: String): Boolean {
        val epc = normalize(epcHex)
        if (epc.isBlank()) return true
        if (updatedEpcs.contains(epc)) return true
        return epc.startsWith(config.normalizedPrefix)
    }

    fun markUpdated(oldEpcHex: String, newEpcHex: String) {
        val oldEpc = normalize(oldEpcHex)
        val newEpc = normalize(newEpcHex)
        if (oldEpc.isNotBlank()) updatedEpcs.add(oldEpc)
        if (newEpc.isNotBlank()) updatedEpcs.add(newEpc)
        config = config.copy(nextSerial = config.nextSerial + BigInteger.ONE)
    }

    fun seenCount(): Int = updatedEpcs.size

    private fun normalize(value: String): String =
        value.trim().replace("\\s+".toRegex(), "").uppercase(Locale.US)
}
