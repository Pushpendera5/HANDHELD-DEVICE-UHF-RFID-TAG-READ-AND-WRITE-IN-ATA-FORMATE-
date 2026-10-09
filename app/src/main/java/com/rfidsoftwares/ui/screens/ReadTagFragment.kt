
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.rfidsoftwares.R
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.driver.RfidReaderDriver
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ReadTagFragment : BaseScreenFragment(), com.rfidsoftwares.ui.HardwareKeyHandler {

    override fun screenTitle(): String = "Read RFID Tags"
    override fun screenSubtitle(): String? = "Scan tags in single shot or continuous live mode"
    override fun allowOfflinePanel(): Boolean = false

    private var bg = Executors.newFixedThreadPool(4)
    private var driver: RfidReaderDriver? = null
    private var isDriverReady = false
    private val isContinuousActive = AtomicBoolean(false)

    private var scanBtnRef: MaterialButton? = null
    private var statusTextRef: TextView? = null
    private var summaryTextRef: TextView? = null
    private var radioGroupRef: RadioGroup? = null

    // Tabular Scanned Tags State
    private val tagList = mutableListOf<ScannedTagRow>()
    private val epcIndexMap = mutableMapOf<String, Int>()
    private var totalReadsCount = 0L
    private lateinit var adapter: ScannedTagAdapter
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    data class ScannedTagRow(
        val index: Int,
        val epc: String,
        var rssi: String,
        var count: Int,
        var timestamp: String
    )

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_read_tag, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        val radioGroup: RadioGroup = view.findViewById(R.id.readModeRadioGroup)
        val scanBtn: MaterialButton = view.findViewById(R.id.readTagScanButton)
        val clearBtn: MaterialButton = view.findViewById(R.id.readTagClearButton)
        val statusText: TextView = view.findViewById(R.id.readTagStatusText)
        val summaryText: TextView = view.findViewById(R.id.readTagSummaryText)
        val recyclerView: RecyclerView = view.findViewById(R.id.scannedTagsRecyclerView)

        scanBtnRef = scanBtn
        statusTextRef = statusText
        summaryTextRef = summaryText
        radioGroupRef = radioGroup

        adapter = ScannedTagAdapter(tagList) { row ->
            showTagInspectionDialog(row)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        updateSummaryUI(summaryText)

        // Eager Hardware Reader Initialization on Fragment Entry
        statusText.text = "Initializing reader hardware..."
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
                    statusText.text = "Hardware init failed (${d.displayName}). Check reader power."
                }
            }
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.readModeContinuousRadio) {
                scanBtn.text = if (isContinuousActive.get()) "Stop Continuous Scan" else "Start Continuous Scan"
            } else {
                if (isContinuousActive.get()) {
                    stopContinuousScan(scanBtn, statusText)
                }
                scanBtn.text = "Scan nearest tag (Single)"
            }
        }

        clearBtn.setOnClickListener {
            if (isContinuousActive.get()) {
                stopContinuousScan(scanBtn, statusText)
            }
            tagList.clear()
            epcIndexMap.clear()
            totalReadsCount = 0L
            adapter.notifyDataSetChanged()
            updateSummaryUI(summaryText)
            statusText.text = "Status: Table cleared."
        }

        scanBtn.setOnClickListener {
            val isContinuousMode = radioGroup.checkedRadioButtonId == R.id.readModeContinuousRadio
            if (isContinuousMode) {
                if (isContinuousActive.get()) {
                    stopContinuousScan(scanBtn, statusText)
                } else {
                    startContinuousScan(scanBtn, statusText, summaryText)
                }
            } else {
                // Single Read Mode
                statusText.text = "Status: Scanning (Single shot)..."
                bg.execute {
                    val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                    if (!isDriverReady) {
                        isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                    }
                    if (!isDriverReady) {
                        view.post {
                            statusText.text = "Reader init failed: Check hardware."
                        }
                        return@execute
                    }

                    val (ok, msg, scannedTags) = runCatching {
                        d.startInventory()
                        Thread.sleep(600)
                        val tags = d.readBufferedTagEvents()
                        d.stopInventory()
                        if (tags.isEmpty()) Triple(false, "Status: No tag found nearby.", emptyList<com.rfidsoftwares.rfid.TagEvent>())
                        else Triple(true, "Status: Scanned ${tags.size} tag(s).", tags)
                    }.getOrElse {
                        Triple(false, "Hardware error: ${it.message ?: it.javaClass.simpleName}", emptyList())
                    }

                    view.post {
                        statusText.text = msg
                        if (ok && scannedTags.isNotEmpty()) {
                            if (UhfConfigPrefs.load(requireContext()).soundEnabled) {
                                com.rfidsoftwares.common.sound.SoundBeeper.playBeep()
                            }
                            for (t in scannedTags) {
                                addOrUpdateTagRow(t.epc, t.rssi?.toString() ?: "?")
                            }
                            updateSummaryUI(summaryText)
                            recyclerView.scrollToPosition(0)
                        }
                    }
                }
            }
        }
    }

    private fun startContinuousScan(
        scanBtn: MaterialButton,
        statusText: TextView,
        summaryText: TextView
    ) {
        if (!isContinuousActive.compareAndSet(false, true)) return
        scanBtn.text = "Stop Continuous Scan"
        statusText.text = "Status: Continuous scan active..."

        bg.execute {
            try {
                val d = driver ?: RfidDriverFactory.createDriver(UhfConfigPrefs.load(requireContext()).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(requireContext().applicationContext) } catch (e: Exception) { false }
                }
                if (!isDriverReady) {
                    isContinuousActive.set(false)
                    scanBtn.post {
                        scanBtn.text = "Start Continuous Scan"
                        statusText.text = "Reader init failed: Check hardware."
                    }
                    return@execute
                }

                d.startInventory()
                while (isContinuousActive.get()) {
                    val events = try {
                        d.readBufferedTagEvents()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (events.isNotEmpty()) {
                        val isSoundEnabled = UhfConfigPrefs.load(requireContext().applicationContext).soundEnabled
                        if (isSoundEnabled) {
                            com.rfidsoftwares.common.sound.SoundBeeper.playBeep()
                        }
                        scanBtn.post {
                            for (event in events) {
                                addOrUpdateTagRow(event.epc, event.rssi?.toString() ?: "?")
                            }
                            updateSummaryUI(summaryText)
                        }
                    }
                    Thread.sleep(100)
                }
                d.stopInventory()
            } catch (e: Exception) {
                isContinuousActive.set(false)
                scanBtn.post {
                    scanBtn.text = "Start Continuous Scan"
                    statusText.text = "Status: Continuous scan stopped (${e.message})"
                }
            }
        }
    }

    private fun stopContinuousScan(scanBtn: MaterialButton, statusText: TextView) {
        isContinuousActive.set(false)
        scanBtn.text = "Start Continuous Scan"
        statusText.text = "Status: Continuous scan stopped. ${tagList.size} unique tags captured."
    }

    private fun handleHardwareTrigger(pressed: Boolean) {
        if (!isAdded) return
        val radioGroup = radioGroupRef ?: return
        val scanBtn = scanBtnRef ?: return
        val statusText = statusTextRef ?: return
        val summaryText = summaryTextRef ?: return

        val isContinuousMode = radioGroup.checkedRadioButtonId == R.id.readModeContinuousRadio
        if (pressed) {
            // Trigger Pressed: Start scan
            if (isContinuousMode) {
                if (!isContinuousActive.get()) {
                    startContinuousScan(scanBtn, statusText, summaryText)
                }
            } else {
                scanBtn.performClick()
            }
        } else {
            // Trigger Released: Stop scan
            if (isContinuousMode) {
                if (isContinuousActive.get()) {
                    stopContinuousScan(scanBtn, statusText)
                }
            }
        }
    }

    override fun onHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!com.rfidsoftwares.hardware.HardwareTriggerHelper.isTriggerKey(keyCode)) return false
        if (event.repeatCount > 0) return true
        handleHardwareTrigger(pressed = true)
        return true
    }

    override fun onHardwareKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!com.rfidsoftwares.hardware.HardwareTriggerHelper.isTriggerKey(keyCode)) return false
        handleHardwareTrigger(pressed = false)
        return true
    }

    override fun onResume() {
        super.onResume()
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = { pressed ->
            activity?.runOnUiThread {
                handleHardwareTrigger(pressed)
            }
        }
    }

    override fun onPause() {
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = null
        super.onPause()
    }

    private fun addOrUpdateTagRow(epc: String, rssi: String) {
        totalReadsCount++
        val nowStr = timeFormat.format(Date())
        val existingIndex = epcIndexMap[epc]
        if (existingIndex != null && existingIndex < tagList.size) {
            val existing = tagList[existingIndex]
            existing.rssi = "$rssi dBm"
            existing.count += 1
            existing.timestamp = nowStr
            adapter.notifyItemChanged(existingIndex)
        } else {
            val newRow = ScannedTagRow(
                index = tagList.size + 1,
                epc = epc,
                rssi = "$rssi dBm",
                count = 1,
                timestamp = nowStr
            )
            tagList.add(0, newRow)
            // Re-index map
            epcIndexMap.clear()
            tagList.forEachIndexed { idx, row ->
                epcIndexMap[row.epc] = idx
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSummaryUI(summaryText: TextView) {
        summaryText.text = "Total: $totalReadsCount | Unique: ${tagList.size}"
    }

    override fun onDestroyView() {
        isContinuousActive.set(false)
        try {
            driver?.stopInventory()
            driver?.release()
        } catch (_: Exception) {
        }
        driver = null
        isDriverReady = false
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun showTagInspectionDialog(row: ScannedTagRow) {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tag_inspector, null)

        // Basic info views
        val tvEpc: TextView = dialogView.findViewById(R.id.tvInspectorEpc)
        val tvMeta: TextView = dialogView.findViewById(R.id.tvInspectorMeta)
        val progress: View = dialogView.findViewById(R.id.inspectorProgress)
        val tvStatus: TextView = dialogView.findViewById(R.id.tvInspectorStatus)
        val cardAta: View = dialogView.findViewById(R.id.cardAtaInspection)

        // ATA editable fields
        val etPartNumber = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaPartNumber)
        val etCageCode = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaCageCode)
        val etSerial = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaSerialNumber)
        val etLot = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaLotNumber)
        val etDesc = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaDescription)
        val etQty = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaQuantity)
        val etUom = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaUom)
        val etCond = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaCondition)
        val etMfgDate = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaMfgDate)
        val etExpDate = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaExpDate)
        val etCertRef = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaCertRef)
        val etTraceability = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAtaTraceability)
        val tvRawEnvelope: TextView = dialogView.findViewById(R.id.tvAtaRawEnvelope)

        // Memory bank display
        val tvTid: TextView = dialogView.findViewById(R.id.tvInspectorTid)
        val tvUserMemory: TextView = dialogView.findViewById(R.id.tvInspectorUserMemory)

        // Buttons
        val btnCopy: MaterialButton = dialogView.findViewById(R.id.btnInspectorCopyEpc)
        val btnSaveToTag: MaterialButton = dialogView.findViewById(R.id.btnInspectorSaveToTag)
        val btnOpenWrite: MaterialButton = dialogView.findViewById(R.id.btnInspectorOpenWrite)
        val btnClose: MaterialButton = dialogView.findViewById(R.id.btnInspectorClose)
        val writeProgress: View = dialogView.findViewById(R.id.inspectorWriteProgress)
        val tvWriteStatus: TextView = dialogView.findViewById(R.id.tvInspectorWriteStatus)

        tvEpc.text = row.epc
        tvMeta.text = "RSSI: ${row.rssi}  |  Reads: ${row.count}"

        // If continuous scan is active, stop it so reader hardware is available for tag memory access
        if (isContinuousActive.get()) {
            scanBtnRef?.let { btn ->
                statusTextRef?.let { txt ->
                    stopContinuousScan(btn, txt)
                }
            }
        }

        // Pre-decode Aviation EPC immediately so user sees CAGE and Serial without waiting
        val initialEpcIdentity = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeEpcIdentity(row.epc)
        if (initialEpcIdentity != null) {
            etCageCode.setText(initialEpcIdentity.first)
            etSerial.setText(initialEpcIdentity.second)
            tvRawEnvelope.text = "Aviation EPC: CAGE=${initialEpcIdentity.first} | SER=${initialEpcIdentity.second}"
            tvStatus.text = "Aviation EPC decoded — Reading memory banks..."
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()

        btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EPC", row.epc))
            android.widget.Toast.makeText(context, "EPC copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }

        btnOpenWrite.setOnClickListener {
            dialog.dismiss()
            val args = Bundle().apply { putString("target_epc", row.epc) }
            findNavController().navigate(R.id.writeTagFragment, args)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        // Save to Tag — encode ATA fields and write to USER memory bank
        btnSaveToTag.setOnClickListener {
            val record = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.AtaBirthRecord(
                cageCode = etCageCode.text?.toString()?.trim() ?: "",
                isMfr = true,
                partNumber = etPartNumber.text?.toString()?.trim() ?: "",
                serialNumber = etSerial.text?.toString()?.trim() ?: "",
                lotNumber = etLot.text?.toString()?.trim() ?: "",
                description = etDesc.text?.toString()?.trim() ?: "",
                quantity = etQty.text?.toString()?.trim() ?: "",
                unitOfMeasure = etUom.text?.toString()?.trim() ?: "",
                conditionCode = etCond.text?.toString()?.trim() ?: "",
                dmfDate = etMfgDate.text?.toString()?.trim() ?: "",
                expDate = etExpDate.text?.toString()?.trim() ?: "",
                certReference = etCertRef.text?.toString()?.trim() ?: "",
                traceability = etTraceability.text?.toString()?.trim() ?: ""
            )

            if (record.partNumber.isBlank() && record.serialNumber.isBlank()) {
                android.widget.Toast.makeText(context, "Please enter at least Part Number or Serial Number", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            writeProgress.visibility = View.VISIBLE
            tvWriteStatus.visibility = View.VISIBLE
            tvWriteStatus.text = "Encoding ATA record..."
            btnSaveToTag.isEnabled = false

            bg.execute {
                try {
                    val payload = com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.encodeBirthRecord(record)
                    val d = driver ?: RfidDriverFactory.createDriver(com.rfidsoftwares.common.prefs.UhfConfigPrefs.load(context).driverId).also { driver = it }
                    if (!isDriverReady) {
                        isDriverReady = try { d.init(context.applicationContext) } catch (e: Exception) { false }
                    }

                    dialogView.post {
                        tvWriteStatus.text = "Writing to tag USER memory..."
                        tvUserMemory.text = "USER Bank: ${payload.userMemoryHex}"
                    }

                    val success = d.writeMemoryBank(
                        epc = row.epc,
                        bank = com.rfidsoftwares.rfid.driver.MemoryBank.USER,
                        ptr = 0,
                        dataHex = payload.userMemoryHex,
                        pwdHex = null
                    )

                    dialogView.post {
                        writeProgress.visibility = View.GONE
                        btnSaveToTag.isEnabled = true
                        if (success) {
                            tvWriteStatus.text = "✓ ATA record written successfully!"
                            tvWriteStatus.setTextColor(android.graphics.Color.parseColor("#22C55E"))
                            tvRawEnvelope.text = payload.rawEnvelope
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.RS, "[RS]")
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.GS, "[GS]")
                                .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.EOT, "[EOT]")
                            android.widget.Toast.makeText(context, "ATA data written to tag!", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            tvWriteStatus.text = "✗ Write failed. Check tag is in range."
                            tvWriteStatus.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                        }
                    }
                } catch (e: Exception) {
                    dialogView.post {
                        writeProgress.visibility = View.GONE
                        btnSaveToTag.isEnabled = true
                        tvWriteStatus.text = "✗ Error: ${e.message}"
                        tvWriteStatus.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    }
                }
            }
        }

        dialog.show()

        // Background memory bank read — pre-fill ATA fields
        bg.execute {
            try {
                val d = driver ?: RfidDriverFactory.createDriver(com.rfidsoftwares.common.prefs.UhfConfigPrefs.load(context).driverId).also { driver = it }
                if (!isDriverReady) {
                    isDriverReady = try { d.init(context.applicationContext) } catch (e: Exception) { false }
                }

                val tidHex = try {
                    d.readMemoryBank(epc = row.epc, bank = com.rfidsoftwares.rfid.driver.MemoryBank.TID, ptr = 0, len = 6, pwdHex = null)
                } catch (_: Exception) { null }

                val userHex = try {
                    d.readMemoryBank(epc = row.epc, bank = com.rfidsoftwares.rfid.driver.MemoryBank.USER, ptr = 0, len = 32, pwdHex = null)
                } catch (_: Exception) { null }

                val ataRecord = if (!userHex.isNullOrBlank()) {
                    com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeBirthRecordFull(userHex)
                } else null

                val epcIdentity = initialEpcIdentity ?: com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.decodeEpcIdentity(row.epc)

                dialogView.post {
                    progress.visibility = View.GONE
                    tvTid.text = "TID Silicon ID: ${tidHex ?: "N/A"}"
                    tvUserMemory.text = "USER Bank: ${if (!userHex.isNullOrBlank()) userHex else "(Empty / 0 Words)"}"

                    // Always show editable ATA card
                    cardAta.visibility = View.VISIBLE
                    btnSaveToTag.visibility = View.VISIBLE

                    if (ataRecord != null && ataRecord.isValidAtaSpec2000) {
                        tvStatus.text = "ATA Spec 2000 Record found — Edit and Save to Tag"
                        // Pre-fill fields from tag
                        etPartNumber.setText(ataRecord.partNumber)
                        etCageCode.setText(ataRecord.cageCode)
                        etSerial.setText(ataRecord.serialNumber)
                        etLot.setText(ataRecord.lotNumber)
                        etDesc.setText(ataRecord.description)
                        etQty.setText(ataRecord.quantity)
                        etUom.setText(ataRecord.unitOfMeasure)
                        etCond.setText(ataRecord.conditionCode)
                        etMfgDate.setText(ataRecord.dmfDate)
                        etExpDate.setText(ataRecord.expDate)
                        etCertRef.setText(ataRecord.certReference)
                        etTraceability.setText(ataRecord.traceability)
                        tvRawEnvelope.text = ataRecord.rawEnvelope
                            .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.RS, "[RS]")
                            .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.GS, "[GS]")
                            .replace(com.rfidsoftwares.rfid.ata.AtaSpec2000Encoder.EOT, "[EOT]")
                    } else if (epcIdentity != null) {
                        tvStatus.text = "Aviation EPC decoded — Fill fields and Save to Tag"
                        etCageCode.setText(epcIdentity.first)
                        etSerial.setText(epcIdentity.second)
                        tvRawEnvelope.text = "Aviation EPC: CAGE=${epcIdentity.first} | SER=${epcIdentity.second}"
                    } else {
                        tvStatus.text = "Tag Inspection Complete"
                        if (tvRawEnvelope.text.isNullOrBlank() || tvRawEnvelope.text == "Awaiting read...") {
                            tvRawEnvelope.text = "(Standard tag — No ATA envelope)"
                        }
                    }
                }
            } catch (e: Exception) {
                dialogView.post {
                    progress.visibility = View.GONE
                    tvStatus.text = "Read complete with note: ${e.message}"
                }
            } finally {
                dialogView.post {
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newFixedThreadPool(4)
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    // Inner Adapter for Tabular Tag Rows
    private class ScannedTagAdapter(
        private val items: List<ScannedTagRow>,
        private val onItemClick: (ScannedTagRow) -> Unit
    ) : RecyclerView.Adapter<ScannedTagAdapter.TagViewHolder>() {

        class TagViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val index: TextView = v.findViewById(R.id.tvRowIndex)
            val epc: TextView = v.findViewById(R.id.tvRowEpc)
            val rssi: TextView = v.findViewById(R.id.tvRowRssi)
            val count: TextView = v.findViewById(R.id.tvRowCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_scanned_tag_table, parent, false)
            return TagViewHolder(v)
        }

        override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
            val item = items[position]
            holder.index.text = (items.size - position).toString()
            holder.epc.text = item.epc
            holder.rssi.text = item.rssi
            holder.count.text = item.count.toString()

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
