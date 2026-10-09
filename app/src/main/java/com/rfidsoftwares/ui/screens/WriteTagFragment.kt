
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.common.sound.SoundBeeper
import com.rfidsoftwares.rfid.driver.MemoryBank
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.driver.RfidReaderDriver
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.Executors

class WriteTagFragment : BaseScreenFragment(), com.rfidsoftwares.ui.HardwareKeyHandler {
    override fun screenTitle(): String = "Write Tag"
    override fun screenSubtitle(): String? = null
    override fun allowOfflinePanel(): Boolean = false

    private var bg = Executors.newSingleThreadExecutor()
    private var driver: RfidReaderDriver? = null
    private var isDriverReady = false

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_write_tag, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        val epcInput: TextInputEditText = view.findViewById(R.id.writeTagEpcInput)
        val pwdInput: TextInputEditText = view.findViewById(R.id.writeTagAccessPwdInput)
        val bankInput: TextInputEditText = view.findViewById(R.id.writeTagBankInput)
        val ptrInput: TextInputEditText = view.findViewById(R.id.writeTagWordPtrInput)
        val dataInput: TextInputEditText = view.findViewById(R.id.writeTagDataInput)
        val newPwdInput: TextInputEditText = view.findViewById(R.id.writeTagNewAccessPwdInput)
        val statusText: TextView = view.findViewById(R.id.writeTagStatusText)

        val scanBtn: MaterialButton = view.findViewById(R.id.writeTagScanButton)
        val writeBtn: MaterialButton = view.findViewById(R.id.writeTagWriteButton)
        val setPwdBtn: MaterialButton = view.findViewById(R.id.writeTagSetPwdButton)
        val lockBtn: MaterialButton = view.findViewById(R.id.writeTagLockButton)
        val unlockBtn: MaterialButton = view.findViewById(R.id.writeTagUnlockButton)

        bankInput.setText("EPC")
        ptrInput.setText("2")

        val initialEpc = arguments?.getString("target_epc") ?: arguments?.getString("epc")
        if (!initialEpc.isNullOrBlank()) {
            epcInput.setText(initialEpc)
        }

        bankInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val b = s?.toString()?.trim()?.uppercase()
                if (b == "EPC") {
                    if (ptrInput.text.isNullOrBlank() || ptrInput.text.toString() == "0") ptrInput.setText("2")
                } else if (b == "USER") {
                    if (ptrInput.text.isNullOrBlank() || ptrInput.text.toString() == "2") ptrInput.setText("0")
                }
            }
        })
        statusText.text = "Initializing reader hardware..."

        // Eager Hardware Reader Initialization on Fragment Entry
        bg.execute {
            val d = RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId)
            driver = d
            val ok = try {
                d.init(requireContext().applicationContext)
            } catch (e: Exception) {
                false
            }
            isDriverReady = ok
            view.post {
                if (ok) {
                    statusText.text = "Reader ready (${d.displayName})."
                } else {
                    statusText.text = "Hardware init failed (${d.displayName})."
                }
            }
        }

        val modeRadioGroup: RadioGroup = view.findViewById(R.id.writeModeRadioGroup)
        val containerDirectHex: LinearLayout = view.findViewById(R.id.containerDirectHex)
        val containerAtaSpec2000: LinearLayout = view.findViewById(R.id.containerAtaSpec2000)
        val radioAtaSpec2000: RadioButton = view.findViewById(R.id.radioAtaSpec2000)

        val writeAtaCageRadioGroup: RadioGroup = view.findViewById(R.id.writeAtaCageRadioGroup)
        val writeAtaRadioMfr: RadioButton = view.findViewById(R.id.writeAtaRadioMfr)
        val writeAtaRadioSpl: RadioButton = view.findViewById(R.id.writeAtaRadioSpl)
        val writeAtaCageInput: TextInputEditText = view.findViewById(R.id.writeAtaCageInput)
        val writeAtaPnoInput: TextInputEditText = view.findViewById(R.id.writeAtaPnoInput)
        val writeAtaSerInput: TextInputEditText = view.findViewById(R.id.writeAtaSerInput)
        val writeAtaDmfInput: TextInputEditText = view.findViewById(R.id.writeAtaDmfInput)
        val writeAtaExpInput: TextInputEditText = view.findViewById(R.id.writeAtaExpInput)
        val writeAtaLotInput: TextInputEditText = view.findViewById(R.id.writeAtaLotInput)
        val writeAtaDescInput: TextInputEditText = view.findViewById(R.id.writeAtaDescInput)
        val writeAtaQtyInput: TextInputEditText = view.findViewById(R.id.writeAtaQtyInput)
        val writeAtaUomInput: TextInputEditText = view.findViewById(R.id.writeAtaUomInput)
        val writeAtaCondInput: TextInputEditText = view.findViewById(R.id.writeAtaCondInput)
        val writeAtaCertInput: TextInputEditText = view.findViewById(R.id.writeAtaCertInput)
        val writeAtaTraceInput: TextInputEditText = view.findViewById(R.id.writeAtaTraceInput)
        val writeAtaBtnSetToday: MaterialButton = view.findViewById(R.id.writeAtaBtnSetToday)
        val writeAtaPreviewText: TextView = view.findViewById(R.id.writeAtaPreviewText)

        val updateAtaPreview = {
            val isMfr = writeAtaRadioMfr.isChecked
            val cage = writeAtaCageInput.text?.toString()?.trim().orEmpty()
            val pno = writeAtaPnoInput.text?.toString()?.trim().orEmpty()
            val ser = writeAtaSerInput.text?.toString()?.trim().orEmpty()
            val dmf = writeAtaDmfInput.text?.toString()?.trim().orEmpty()
            val exp = writeAtaExpInput.text?.toString()?.trim().orEmpty()
            val lot = writeAtaLotInput.text?.toString()?.trim().orEmpty()
            val desc = writeAtaDescInput.text?.toString()?.trim().orEmpty()
            val qty = writeAtaQtyInput.text?.toString()?.trim().orEmpty()
            val uom = writeAtaUomInput.text?.toString()?.trim().orEmpty()
            val cond = writeAtaCondInput.text?.toString()?.trim().orEmpty()
            val cert = writeAtaCertInput.text?.toString()?.trim().orEmpty()
            val trace = writeAtaTraceInput.text?.toString()?.trim().orEmpty()
            if (cage.isBlank() && pno.isBlank() && ser.isBlank()) {
                writeAtaPreviewText.text = "(Enter TEI values above to see format preview)"
            } else {
                val record = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.AtaBirthRecord(
                    cageCode = cage, isMfr = isMfr, partNumber = pno, serialNumber = ser,
                    dmfDate = dmf, expDate = exp, lotNumber = lot, description = desc,
                    quantity = qty, unitOfMeasure = uom, conditionCode = cond,
                    certReference = cert, traceability = trace
                )
                val payload = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.encodeBirthRecord(record)
                val display = payload.rawEnvelope
                    .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.RS, "[RS]")
                    .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.GS, "[GS]")
                    .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.EOT, "[EOT]")
                writeAtaPreviewText.text = display
            }
        }

        val populateAtaFields = { record: com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.AtaBirthRecord ->
            if (record.isMfr) writeAtaRadioMfr.isChecked = true else writeAtaRadioSpl.isChecked = true
            writeAtaCageInput.setText(record.cageCode)
            writeAtaPnoInput.setText(record.partNumber)
            writeAtaSerInput.setText(record.serialNumber)
            writeAtaDmfInput.setText(record.dmfDate)
            writeAtaExpInput.setText(record.expDate)
            writeAtaLotInput.setText(record.lotNumber)
            writeAtaDescInput.setText(record.description)
            writeAtaQtyInput.setText(record.quantity)
            writeAtaUomInput.setText(record.unitOfMeasure)
            writeAtaCondInput.setText(record.conditionCode)
            writeAtaCertInput.setText(record.certReference)
            writeAtaTraceInput.setText(record.traceability)
            updateAtaPreview()
        }

        val ataWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateAtaPreview() }
        }

        writeAtaCageInput.addTextChangedListener(ataWatcher)
        writeAtaPnoInput.addTextChangedListener(ataWatcher)
        writeAtaSerInput.addTextChangedListener(ataWatcher)
        writeAtaDmfInput.addTextChangedListener(ataWatcher)
        writeAtaExpInput.addTextChangedListener(ataWatcher)
        writeAtaLotInput.addTextChangedListener(ataWatcher)
        writeAtaDescInput.addTextChangedListener(ataWatcher)
        writeAtaQtyInput.addTextChangedListener(ataWatcher)
        writeAtaUomInput.addTextChangedListener(ataWatcher)
        writeAtaCondInput.addTextChangedListener(ataWatcher)
        writeAtaCertInput.addTextChangedListener(ataWatcher)
        writeAtaTraceInput.addTextChangedListener(ataWatcher)
        writeAtaCageRadioGroup.setOnCheckedChangeListener { _, _ -> updateAtaPreview() }
        updateAtaPreview()

        writeAtaBtnSetToday.setOnClickListener {
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            writeAtaDmfInput.setText(today)
        }

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioAtaSpec2000) {
                containerDirectHex.visibility = View.GONE
                containerAtaSpec2000.visibility = View.VISIBLE
                statusText.text = "ATA Spec 2000 Mode: Enter Life Vest TEIs (CAGE, PNO, SER, DMF) and tap Write."
            } else {
                containerDirectHex.visibility = View.VISIBLE
                containerAtaSpec2000.visibility = View.GONE
                statusText.text = "Standard Mode: Enter HEX data and tap Write."
            }
        }

        scanBtn.setOnClickListener {
            statusText.text = "Scanning & reading tag memory..."
            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post { statusText.text = "Reader init failed: Check connection." }
                    return@execute
                }

                val snap = runCatching {
                    d.startInventory()
                    Thread.sleep(350)
                    val tags = d.readBufferedTagEvents()
                    d.stopInventory()
                    tags.maxByOrNull { it.rssi ?: -999 } ?: tags.firstOrNull()
                }.getOrNull()

                val epc = snap?.epc
                if (epc.isNullOrBlank()) {
                    view.post { statusText.text = "No tag found. Move closer and try again." }
                    return@execute
                }

                val pwd = pwdInput.text?.toString()?.trim()

                // Read USER memory bank (Bank 3) to retrieve full ATA Birth Record if present
                val userHex = try {
                    d.readMemoryBank(epc = epc, bank = MemoryBank.USER, ptr = 0, len = 64, pwdHex = pwd)
                } catch (_: Exception) { null }

                val fullRecord = if (!userHex.isNullOrBlank()) {
                    com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeBirthRecordFull(userHex)
                } else null

                val epcIdentity = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeEpcIdentity(epc)

                view.post {
                    if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                        SoundBeeper.playBeep()
                    }
                    epcInput.setText(epc)
                    dataInput.setText(userHex ?: epc)

                    if (fullRecord != null && fullRecord.isValidAtaSpec2000) {
                        populateAtaFields(fullRecord)
                        statusText.text = "Target tag loaded: All ATA fields populated. Edit & Write."
                    } else if (epcIdentity != null) {
                        writeAtaRadioMfr.isChecked = true
                        writeAtaCageInput.setText(epcIdentity.first)
                        writeAtaSerInput.setText(epcIdentity.second)
                        updateAtaPreview()
                        statusText.text = "Aviation EPC loaded: CAGE=${epcIdentity.first}, SER=${epcIdentity.second} populated. Edit & Write."
                    } else {
                        val ascii = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.hexToString(epc).trim()
                        if (ascii.length >= 4 && ascii.all { it.isLetterOrDigit() || it == '-' }) {
                            writeAtaSerInput.setText(ascii)
                            updateAtaPreview()
                        }
                        statusText.text = "Target tag scanned (EPC=$epc). Fill in ATA fields & Write."
                    }
                }
            }
        }

        val btnVerifyTagData: MaterialButton = view.findViewById(R.id.btnVerifyTagData)
        val cardVerifiedTagData: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.cardVerifiedTagData)
        val tvVerifiedHeader: TextView = view.findViewById(R.id.tvVerifiedHeader)
        val tvVerifiedContent: TextView = view.findViewById(R.id.tvVerifiedContent)

        fun performTagVerification() {
            statusText.text = "Inspecting tag memory banks (EPC, TID, USER)..."
            cardVerifiedTagData.visibility = View.VISIBLE
            tvVerifiedHeader.text = "Inspecting Tag..."
            tvVerifiedContent.text = "Reading EPC, TID, and USER memory..."

            val pwd = pwdInput.text?.toString()?.trim()

            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post {
                        statusText.text = "Reader init failed: Check connection."
                        tvVerifiedContent.text = "Hardware init failed."
                    }
                    return@execute
                }

                // Scan nearby tag to get current EPC
                d.startInventory()
                Thread.sleep(350)
                val tags = d.readBufferedTagEvents()
                d.stopInventory()

                val nearestTag = tags.firstOrNull()
                val currentEpc = nearestTag?.epc ?: epcInput.text?.toString()?.trim()

                // Read TID bank (Bank 2)
                val tidHex = try {
                    d.readMemoryBank(epc = currentEpc, bank = MemoryBank.TID, ptr = 0, len = 6, pwdHex = pwd)
                } catch (_: Exception) { null }

                // Read User memory bank (Bank 3)
                val userHex = try {
                    d.readMemoryBank(epc = currentEpc, bank = MemoryBank.USER, ptr = 0, len = 32, pwdHex = pwd)
                } catch (_: Exception) { null }

                val fullRec = if (!userHex.isNullOrBlank()) {
                    com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeBirthRecordFull(userHex)
                } else null

                val ataDecoded = if (!userHex.isNullOrBlank()) {
                    com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeBirthRecord(userHex)
                } else null

                val epcIdentity = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeEpcIdentity(currentEpc)

                view.post {
                    if (fullRec != null && fullRec.isValidAtaSpec2000) {
                        populateAtaFields(fullRec)
                    } else if (epcIdentity != null && writeAtaCageInput.text.isNullOrBlank()) {
                        writeAtaRadioMfr.isChecked = true
                        writeAtaCageInput.setText(epcIdentity.first)
                        writeAtaSerInput.setText(epcIdentity.second)
                        updateAtaPreview()
                    }
                    cardVerifiedTagData.visibility = View.VISIBLE
                    if (currentEpc.isNullOrBlank() && tidHex.isNullOrBlank() && userHex.isNullOrBlank()) {
                        tvVerifiedHeader.text = "No Tag Detected"
                        tvVerifiedContent.text = "No tag within range."
                        statusText.text = "No tag detected."
                    } else if (ataDecoded != null && ataDecoded.isValidAtaSpec2000) {
                        tvVerifiedHeader.text = "ATA Spec 2000 Record"
                        tvVerifiedContent.text = StringBuilder()
                            .append("Tag EPC : ").append(currentEpc ?: "N/A").append("\n")
                            .append("Serial  : ").append(ataDecoded.serialNumber ?: epcIdentity?.second ?: "-").append("\n")
                            .append("Part No : ").append(ataDecoded.partNumber ?: "-").append("\n")
                            .append("CAGE    : ").append(ataDecoded.cageType ?: "MFR").append(" ").append(ataDecoded.cageCode ?: epcIdentity?.first ?: "-").append("\n")
                            .append("Date    : ").append(ataDecoded.dmfDate ?: "-").append("\n\n")
                            .append("Raw Envelope:\n").append(ataDecoded.rawEnvelope
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.RS, "[RS]")
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.GS, "[GS]")
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.EOT, "[EOT]"))
                            .toString()
                        statusText.text = "Record verified."
                    } else if (epcIdentity != null) {
                        tvVerifiedHeader.text = "ATA Spec 2000 EPC"
                        tvVerifiedContent.text = StringBuilder()
                            .append("Tag EPC : ").append(currentEpc ?: "N/A").append("\n")
                            .append("Serial  : ").append(epcIdentity.second).append("\n")
                            .append("CAGE    : MFR ").append(epcIdentity.first).append("\n")
                            .append("Part No : -\n")
                            .append("Date    : -\n")
                            .append("TID     : ").append(tidHex ?: "N/A").append("\n")
                            .append("USER    : ").append(if (!userHex.isNullOrBlank()) userHex else "-")
                            .toString()
                        statusText.text = "EPC verified: CAGE=${epcIdentity.first}, SER=${epcIdentity.second}"
                    } else {
                        tvVerifiedHeader.text = "Tag Memory"
                        tvVerifiedContent.text = StringBuilder()
                            .append("Tag EPC : ").append(currentEpc ?: "N/A").append("\n")
                            .append("TID     : ").append(tidHex ?: "N/A").append("\n")
                            .append("USER    : ").append(if (!userHex.isNullOrBlank()) userHex else "-")
                            .toString()
                        statusText.text = "Memory read complete."
                    }

                    val scrollView = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.writeTagScrollView)
                    scrollView?.post {
                        scrollView.smoothScrollTo(0, cardVerifiedTagData.top)
                    }

                    if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                        SoundBeeper.playBeep()
                    }
                }
            }
        }

        btnVerifyTagData.setOnClickListener {
            performTagVerification()
        }

        writeBtn.setOnClickListener {
            val isAtaMode = radioAtaSpec2000.isChecked

            if (isAtaMode) {
                val isMfr = writeAtaRadioMfr.isChecked
                val cage = writeAtaCageInput.text?.toString()?.trim().orEmpty()
                val pno = writeAtaPnoInput.text?.toString()?.trim().orEmpty()
                val ser = writeAtaSerInput.text?.toString()?.trim().orEmpty()
                val dmf = writeAtaDmfInput.text?.toString()?.trim().orEmpty()
                val exp = writeAtaExpInput.text?.toString()?.trim().orEmpty()
                val lot = writeAtaLotInput.text?.toString()?.trim().orEmpty()
                val desc = writeAtaDescInput.text?.toString()?.trim().orEmpty()
                val qty = writeAtaQtyInput.text?.toString()?.trim().orEmpty()
                val uom = writeAtaUomInput.text?.toString()?.trim().orEmpty()
                val cond = writeAtaCondInput.text?.toString()?.trim().orEmpty()
                val cert = writeAtaCertInput.text?.toString()?.trim().orEmpty()
                val trace = writeAtaTraceInput.text?.toString()?.trim().orEmpty()

                if (cage.isBlank() || pno.isBlank() || ser.isBlank()) {
                    statusText.text = "Enter CAGE Code, Part Number, and Serial."
                    return@setOnClickListener
                }

                val targetEpc = epcInput.text?.toString()?.trim()
                val record = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.AtaBirthRecord(
                    cageCode = cage, isMfr = isMfr, partNumber = pno, serialNumber = ser,
                    dmfDate = dmf, expDate = exp, lotNumber = lot, description = desc,
                    quantity = qty, unitOfMeasure = uom, conditionCode = cond,
                    certReference = cert, traceability = trace
                )
                val payload = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.encodeBirthRecord(record)
                statusText.text = if (!targetEpc.isNullOrBlank()) {
                    "Targeting tag [$targetEpc] for ATA Spec 2000 write..."
                } else {
                    "Encoding & writing ATA Spec 2000 Birth Record..."
                }

                bg.execute {
                    val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                    if (!isDriverReady) {
                        isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                    }
                    if (!isDriverReady) {
                        view.post { statusText.text = "Reader init failed: Check connection." }
                        return@execute
                    }

                    val targetToUse = targetEpc.takeIf { !it.isNullOrBlank() }
                    val epcHex = payload.suggestedEpcHex

                    // 1. Write Aviation EPC (CAGE + Serial) to EPC Memory (Bank 1, Offset 2)
                    val epcOk = d.writeMemoryBank(
                        epc = targetToUse,
                        bank = MemoryBank.EPC,
                        ptr = 2,
                        dataHex = epcHex,
                        pwdHex = null
                    )

                    // 2. Attempt to write ISO 15434 Record to USER Memory (Bank 3, Offset 0)
                    // (Some RFID tags like Monza R6 only have EPC memory and 0 bytes of USER memory)
                    val newTarget = if (epcOk) epcHex else targetToUse
                    val userOk = try {
                        d.writeMemoryBank(
                            epc = newTarget,
                            bank = MemoryBank.USER,
                            ptr = 0,
                            dataHex = payload.userMemoryHex,
                            pwdHex = null
                        )
                    } catch (_: Exception) { false }

                    view.post {
                        if (epcOk || userOk) {
                            val msg = when {
                                epcOk && userOk -> "Tag programmed successfully (EPC + USER memory)."
                                epcOk -> "Tag EPC programmed successfully (Tag chip has no/limited USER memory)."
                                else -> "USER memory updated."
                            }
                            statusText.text = msg
                            if (epcOk) {
                                epcInput.setText(epcHex)
                            }
                            if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                                SoundBeeper.playBeep()
                            }
                            // Auto-inspect to display verified data card immediately
                            performTagVerification()
                        } else {
                            statusText.text = "Write failed: Tag rejected write or moved out of range."
                        }
                    }
                }
            } else {
                // Standard HEX Write
                statusText.text = "Writing to memory bank..."
                val bank = parseBank(bankInput.text?.toString())
                val wordPtr = ptrInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val dataHex = dataInput.text?.toString()?.trim().orEmpty()
                val epc = epcInput.text?.toString()?.trim()
                val pwd = pwdInput.text?.toString()?.trim()

                bg.execute {
                    val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                    if (!isDriverReady) {
                        isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                    }
                    if (!isDriverReady) {
                        view.post { statusText.text = "Reader init failed: Check connection." }
                        return@execute
                    }

                    val (ok, msg) = runCatching {
                        val res = d.writeMemoryBank(
                            epc = epc,
                            bank = bank,
                            ptr = wordPtr,
                            dataHex = dataHex,
                            pwdHex = pwd,
                        )
                        if (res) {
                            true to "Write successful."
                        } else {
                            val reason = if (bank == MemoryBank.USER) {
                                "Write failed: USER bank not writable."
                            } else {
                                "Write failed: Tag rejected write."
                            }
                            false to reason
                        }
                    }.getOrElse {
                        false to "Write failed: ${it.message ?: it.javaClass.simpleName}"
                    }

                    view.post {
                        statusText.text = msg
                        if (ok && UhfConfigPrefs.load(requireContext()).soundEnabled) {
                            SoundBeeper.playBeep()
                        }
                    }
                }
            }
        }

        setPwdBtn.setOnClickListener {
            statusText.text = "Setting access password..."
            val epc = epcInput.text?.toString()?.trim()
            val newPwd = newPwdInput.text?.toString()?.trim().orEmpty()
            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post { statusText.text = "Reader init failed: Check connection." }
                    return@execute
                }

                val (ok, msg) = runCatching {
                    val res = d.writeMemoryBank(
                        epc = epc,
                        bank = MemoryBank.RESERVED,
                        ptr = 2,
                        dataHex = newPwd,
                        pwdHex = null
                    )
                    if (res) true to "Access password updated." else false to "Failed to set access password."
                }.getOrElse {
                    false to "Failed to set password: ${it.message ?: it.javaClass.simpleName}"
                }
                view.post {
                    statusText.text = msg
                    if (ok && UhfConfigPrefs.load(requireContext()).soundEnabled) {
                        SoundBeeper.playBeep()
                    }
                }
            }
        }

        lockBtn.setOnClickListener {
            statusText.text = "Locking tag..."
            val epc = epcInput.text?.toString()?.trim()
            val pwd = pwdInput.text?.toString()?.trim()
            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post { statusText.text = "Reader init failed: Check connection." }
                    return@execute
                }

                val (ok, msg) = runCatching {
                    val res = d.setLockState(epc = epc, accessPasswordHex = pwd, lock = true)
                    if (res) true to "Lock command sent." else false to "Lock failed (unsupported or rejected)."
                }.getOrElse {
                    false to "Lock failed: ${it.message ?: it.javaClass.simpleName}"
                }
                view.post {
                    statusText.text = msg
                    if (ok && UhfConfigPrefs.load(requireContext()).soundEnabled) {
                        SoundBeeper.playBeep()
                    }
                }
            }
        }

        unlockBtn.setOnClickListener {
            statusText.text = "Unlocking tag..."
            val epc = epcInput.text?.toString()?.trim()
            val pwd = pwdInput.text?.toString()?.trim()
            bg.execute {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    view.post { statusText.text = "Reader init failed: Check connection." }
                    return@execute
                }

                val (ok, msg) = runCatching {
                    val res = d.setLockState(epc = epc, accessPasswordHex = pwd, lock = false)
                    if (res) true to "Unlock command sent." else false to "Unlock failed (unsupported or rejected)."
                }.getOrElse {
                    false to "Unlock failed: ${it.message ?: it.javaClass.simpleName}"
                }
                view.post {
                    statusText.text = msg
                    if (ok && UhfConfigPrefs.load(requireContext()).soundEnabled) {
                        SoundBeeper.playBeep()
                    }
                }
            }
        }
    }

    override fun onHardwareKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!com.rfidsoftwares.hardware.HardwareTriggerHelper.isTriggerKey(keyCode)) return false
        if (event.repeatCount > 0) return true
        activity?.runOnUiThread {
            view?.findViewById<MaterialButton>(R.id.writeTagScanButton)?.performClick()
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = { pressed ->
            if (pressed) {
                activity?.runOnUiThread {
                    view?.findViewById<MaterialButton>(R.id.writeTagScanButton)?.performClick()
                }
            }
        }
    }

    override fun onPause() {
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = null
        super.onPause()
    }

    override fun onDestroyView() {
        try {
            driver?.release()
        } catch (_: Exception) {
        }
        driver = null
        isDriverReady = false
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }

    private fun parseBank(raw: String?): MemoryBank {
        return when (raw?.trim()?.uppercase()) {
            "RESERVED" -> MemoryBank.RESERVED
            "EPC" -> MemoryBank.EPC
            "TID" -> MemoryBank.TID
            "USER" -> MemoryBank.USER
            else -> MemoryBank.USER
        }
    }
}
