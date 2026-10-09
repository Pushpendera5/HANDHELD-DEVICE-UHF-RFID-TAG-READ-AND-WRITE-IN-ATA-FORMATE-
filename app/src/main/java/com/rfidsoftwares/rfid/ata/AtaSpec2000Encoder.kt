package com.rfidsoftwares.rfid.ata

import java.util.Locale

/**
 * ATA Spec 2000 Chapter 9 (AIDC) Birth Record Encoder & Decoder
 * Conforms to ISO/IEC 15434 Syntax Envelope (Format 12) using standard Text Element Identifiers (TEIs).
 * Supports full 12-field ATA record: CAGE, PN, SER, DMF, EXP, QTY, UOM, LOT, DESC, COND, CERT, TRACE
 */
object AtaSpec2000Encoder {

    // Control characters for ISO/IEC 15434 syntax envelope
    const val RS = "\u001E"   // Record Separator (ASCII 30)
    const val GS = "\u001D"   // Group Separator (ASCII 29)
    const val EOT = "\u0004"  // End of Transmission (ASCII 4)
    const val HEADER_PREFIX = "[)>"
    const val FORMAT_12 = "12"

    /** Full 12-field ATA Spec 2000 birth record */
    data class AtaBirthRecord(
        val cageCode: String = "",
        val isMfr: Boolean = true,           // true = MFR, false = SPL
        val partNumber: String = "",
        val serialNumber: String = "",
        val dmfDate: String = "",            // Manufacture Date YYYYMMDD
        val expDate: String = "",            // Expiry Date YYYYMMDD
        val quantity: String = "",
        val unitOfMeasure: String = "",      // EA, FT, KG, etc.
        val lotNumber: String = "",
        val description: String = "",
        val conditionCode: String = "",      // NE=New, SV=Serviceable, RP=Repairable
        val certReference: String = "",
        val traceability: String = "",
        // Read-only fields from tag
        val rawEnvelope: String = "",
        val isValidAtaSpec2000: Boolean = false
    )

    data class AtaEncodedPayload(
        val rawEnvelope: String,
        val userMemoryHex: String,
        val suggestedEpcHex: String
    )

    // Legacy decode result kept for backward compat
    data class AtaDecodedRecord(
        val cageCode: String?,
        val cageType: String?,
        val partNumber: String?,
        val serialNumber: String?,
        val dmfDate: String?,
        val rawEnvelope: String,
        val isValidAtaSpec2000: Boolean
    )

    /**
     * Encodes a full AtaBirthRecord into ISO/IEC 15434 Format 12 Envelope.
     */
    fun encodeBirthRecord(record: AtaBirthRecord): AtaEncodedPayload {
        val cageTei = if (record.isMfr) "MFR" else "SPL"
        val sb = StringBuilder()
        sb.append(HEADER_PREFIX).append(RS).append(FORMAT_12).append(GS)

        // CAGE Code (mandatory)
        if (record.cageCode.isNotBlank())
            sb.append(cageTei).append(record.cageCode.trim().uppercase(Locale.US))

        // Part Number
        if (record.partNumber.isNotBlank())
            sb.append(GS).append("PNO").append(record.partNumber.trim().uppercase(Locale.US))

        // Serial Number
        if (record.serialNumber.isNotBlank())
            sb.append(GS).append("SER").append(record.serialNumber.trim().uppercase(Locale.US))

        // Manufacture Date
        val dmf = record.dmfDate.trim().replace("-", "").replace("/", "")
        if (dmf.isNotBlank())
            sb.append(GS).append("DMF").append(dmf)

        // Expiry Date
        val exp = record.expDate.trim().replace("-", "").replace("/", "")
        if (exp.isNotBlank())
            sb.append(GS).append("EXP").append(exp)

        // Quantity
        if (record.quantity.isNotBlank())
            sb.append(GS).append("QTY").append(record.quantity.trim())

        // Unit of Measure
        if (record.unitOfMeasure.isNotBlank())
            sb.append(GS).append("UOM").append(record.unitOfMeasure.trim().uppercase(Locale.US))

        // Lot / Batch Number
        if (record.lotNumber.isNotBlank())
            sb.append(GS).append("LOT").append(record.lotNumber.trim().uppercase(Locale.US))

        // Description
        if (record.description.isNotBlank())
            sb.append(GS).append("DESC").append(record.description.trim())

        // Condition Code
        if (record.conditionCode.isNotBlank())
            sb.append(GS).append("COND").append(record.conditionCode.trim().uppercase(Locale.US))

        // Certificate Reference
        if (record.certReference.isNotBlank())
            sb.append(GS).append("CERT").append(record.certReference.trim())

        // Traceability
        if (record.traceability.isNotBlank())
            sb.append(GS).append("TRACE").append(record.traceability.trim())

        // Trailer
        sb.append(RS).append(EOT)

        val raw = sb.toString()
        val userHex = stringToHex(raw)

        val epcRaw = (record.cageCode.trim().uppercase(Locale.US) +
                record.serialNumber.trim().uppercase(Locale.US)).take(24)
        val suggestedEpc = stringToHex(epcRaw).take(24).padEnd(24, '0')

        return AtaEncodedPayload(
            rawEnvelope = raw,
            userMemoryHex = userHex,
            suggestedEpcHex = suggestedEpc
        )
    }

    /** Legacy 4-field encode — kept for backward compat */
    fun encodeBirthRecord(
        cageCode: String,
        isMfr: Boolean = true,
        partNumber: String,
        serialNumber: String,
        dmfDate: String
    ): AtaEncodedPayload = encodeBirthRecord(
        AtaBirthRecord(
            cageCode = cageCode,
            isMfr = isMfr,
            partNumber = partNumber,
            serialNumber = serialNumber,
            dmfDate = dmfDate
        )
    )

    /**
     * Decodes User Memory HEX into a full AtaBirthRecord.
     */
    fun decodeBirthRecordFull(userMemoryHex: String): AtaBirthRecord? {
        val raw = hexToString(userMemoryHex)
        if (raw.isBlank()) return null

        var isAta = false
        var cageCode = ""
        var isMfr = true
        var partNumber = ""
        var serialNumber = ""
        var dmfDate = ""
        var expDate = ""
        var quantity = ""
        var unitOfMeasure = ""
        var lotNumber = ""
        var description = ""
        var conditionCode = ""
        var certReference = ""
        var traceability = ""

        if (raw.contains(HEADER_PREFIX) || raw.contains("MFR") || raw.contains("PNO") || raw.contains("SER")) {
            isAta = true
        }

        val tokens = raw.replace(HEADER_PREFIX, "")
            .replace(RS, " ")
            .replace(EOT, "")
            .split(Regex("[\u001D\\s]+"))

        for (token in tokens) {
            val t = token.trim()
            when {
                t.startsWith("MFR") -> { isMfr = true; cageCode = t.removePrefix("MFR").trim() }
                t.startsWith("SPL") -> { isMfr = false; cageCode = t.removePrefix("SPL").trim() }
                t.startsWith("PNO") -> partNumber = t.removePrefix("PNO").trim()
                t.startsWith("SER") -> serialNumber = t.removePrefix("SER").trim()
                t.startsWith("SEQ") -> serialNumber = t.removePrefix("SEQ").trim()
                t.startsWith("DMF") -> dmfDate = t.removePrefix("DMF").trim()
                t.startsWith("EXP") -> expDate = t.removePrefix("EXP").trim()
                t.startsWith("QTY") -> quantity = t.removePrefix("QTY").trim()
                t.startsWith("UOM") -> unitOfMeasure = t.removePrefix("UOM").trim()
                t.startsWith("LOT") -> lotNumber = t.removePrefix("LOT").trim()
                t.startsWith("DESC") -> description = t.removePrefix("DESC").trim()
                t.startsWith("COND") -> conditionCode = t.removePrefix("COND").trim()
                t.startsWith("CERT") -> certReference = t.removePrefix("CERT").trim()
                t.startsWith("TRACE") -> traceability = t.removePrefix("TRACE").trim()
            }
        }

        return AtaBirthRecord(
            cageCode = cageCode,
            isMfr = isMfr,
            partNumber = partNumber,
            serialNumber = serialNumber,
            dmfDate = dmfDate,
            expDate = expDate,
            quantity = quantity,
            unitOfMeasure = unitOfMeasure,
            lotNumber = lotNumber,
            description = description,
            conditionCode = conditionCode,
            certReference = certReference,
            traceability = traceability,
            rawEnvelope = raw,
            isValidAtaSpec2000 = isAta && (partNumber.isNotBlank() || serialNumber.isNotBlank())
        )
    }

    /**
     * Legacy decode — kept for backward compat with existing dialog code.
     */
    fun decodeBirthRecord(userMemoryHex: String): AtaDecodedRecord? {
        val full = decodeBirthRecordFull(userMemoryHex) ?: return null
        return AtaDecodedRecord(
            cageCode = full.cageCode.ifBlank { null },
            cageType = if (full.isMfr) "MFR" else "SPL",
            partNumber = full.partNumber.ifBlank { null },
            serialNumber = full.serialNumber.ifBlank { null },
            dmfDate = full.dmfDate.ifBlank { null },
            rawEnvelope = full.rawEnvelope,
            isValidAtaSpec2000 = full.isValidAtaSpec2000
        )
    }

    /**
     * Decodes standard Aviation ASCII EPC into CAGE Code and Serial Number.
     */
    fun decodeEpcIdentity(epcHex: String?): Pair<String, String>? {
        if (epcHex.isNullOrBlank()) return null
        val ascii = hexToString(epcHex.replace(" ", "").trim()).trim()
        if (ascii.length >= 6) {
            val cage = ascii.take(5)
            val serial = ascii.drop(5).trim()
            if (cage.all { it.isLetterOrDigit() } && serial.all { it.isLetterOrDigit() || it == '-' }) {
                return cage to serial
            }
        }
        return null
    }

    fun stringToHex(input: String): String {
        val sb = StringBuilder()
        for (ch in input.toCharArray()) {
            sb.append(String.format(Locale.US, "%02X", ch.code and 0xFF))
        }
        while (sb.length % 4 != 0) sb.append("00")
        return sb.toString()
    }

    fun hexToString(hex: String): String {
        val clean = hex.replace(" ", "").trim()
        val sb = StringBuilder()
        var i = 0
        while (i < clean.length - 1) {
            try {
                val b = clean.substring(i, i + 2).toInt(16)
                if (b in 32..126 || b == 0x1E || b == 0x1D || b == 0x04) sb.append(b.toChar())
            } catch (_: Exception) {}
            i += 2
        }
        return sb.toString()
    }
}
