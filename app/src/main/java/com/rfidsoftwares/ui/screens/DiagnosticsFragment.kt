
package com.rfidsoftwares.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.integration.auth.AuthRefreshTelemetry
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.DiagnosticsUploadPayload
import com.rfidsoftwares.issues.IssueCategories
import com.rfidsoftwares.issues.IssueLogSupport
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.DriverUhfReaderGatewayAdapter
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.MockUhfReaderGateway
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class DiagnosticsFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Diagnostics"
    override fun screenSubtitle(): String? = "Reader, network, sign-in, and sync health"

    private var bg = Executors.newSingleThreadExecutor()
    private val gson = Gson()
    private var detailsExpanded = false
    private var lastDetailsJson = "{}"

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_diagnostics, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()
        val db = RfidSessionDbProvider.getInstance(requireContext())
        val gateway = if (FeatureFlags.TEST_MODE_ENABLED && FeatureFlags.UHF_TEST_MODE_ENABLED) {
            MockUhfReaderGateway()
        } else {
            val driverId = UhfConfigPrefs.load(requireContext()).driverId
            DriverUhfReaderGatewayAdapter(RfidDriverFactory.createDriver(driverId))
        }

        val refresh: MaterialButton = view.findViewById(R.id.diagnosticsRefreshButton)
        val expand: MaterialButton = view.findViewById(R.id.diagnosticsExpandButton)
        val details: TextView = view.findViewById(R.id.diagnosticsDetailsText)
        val upload: MaterialButton = view.findViewById(R.id.diagnosticsUploadButton)

        expand.setOnClickListener {
            detailsExpanded = !detailsExpanded
            details.visibility = if (detailsExpanded) View.VISIBLE else View.GONE
            expand.text = if (detailsExpanded) "Hide technical details" else "Show technical details"
            if (detailsExpanded) details.text = lastDetailsJson
        }

        refresh.setOnClickListener { runRefresh(db, gateway, view) }
        upload.setOnClickListener { runUpload(gateway, view) }

        runRefresh(db, gateway, view)
    }

    override fun onDestroyView() {
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun providerId(): String =
        ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId

    private fun runRefresh(db: com.rfidsoftwares.data.local.RfidSessionDatabase, gateway: com.rfidsoftwares.rfid.UhfReaderGateway, root: View) {
        bg.execute {
            val now = System.currentTimeMillis()
            val reader = gateway.readDiagnosticsSummary()
            val online = isOnline(requireContext())
            val adapter = BackendAdapterProvider.getAdapter(providerId())
            val cred = try {
                adapter.peekCredential()
            } catch (_: Exception) {
                null
            }
            val credLine = when {
                cred == null -> "No credential in memory"
                cred.expiresAtEpochMs == null -> "Token present (no expiry metadata)"
                cred.expiresAtEpochMs < now -> "Token appears expired — refresh on next API call"
                cred.expiresAtEpochMs - now < 60_000 -> "Token expiring within 60s"
                else -> "Token valid (expires ${cred.expiresAtEpochMs})"
            }
            val refreshLine = when (AuthRefreshTelemetry.lastSuccess) {
                null -> "Last refresh: none in this app session"
                true -> "Last token refresh: successful at ${formatTimestamp(AuthRefreshTelemetry.lastAttemptAtEpochMs)}"
                false ->
                    "Last token refresh: failed (${AuthRefreshTelemetry.lastErrorSummary ?: "unknown"}) at " +
                        formatTimestamp(AuthRefreshTelemetry.lastAttemptAtEpochMs)
            }
            val authLine = "$credLine\n$refreshLine"

            val pid = providerId()
            val grouped = try {
                db.syncOutboxDao().countGroupedByState(pid)
            } catch (_: Exception) {
                emptyList()
            }
            val pending = grouped.find { it.state == "pending" }?.count ?: 0
            val retrying = grouped.find { it.state == "retrying" }?.count ?: 0
            val running = grouped.find { it.state == "running" }?.count ?: 0
            val failed = grouped.find { it.state == "failed_permanent" }?.count ?: 0
            val conflicted = grouped.find { it.state == "conflicted" }?.count ?: 0
            val lastOutbox = db.syncOutboxDao().maxUpdatedAt(pid)

            val readerLine = buildString {
                append(if (reader.sdkReady) "SDK ready" else "SDK not ready")
                append(" · ")
                append(if (reader.readerOpen) "reader session open" else "reader idle")
                reader.powerDbm?.let { append(" · power≈${it} dBm") }
                reader.regionOrFrequency?.let { append(" · $it") }
            }

            val obj = JsonObject().apply {
                addProperty("capturedAtEpochMs", now)
                addProperty("networkOnline", online)
                addProperty("readerDetail", reader.detailLine)
                addProperty("authSummary", authLine)
                addProperty("outboxPending", pending)
                addProperty("outboxRetrying", retrying)
                addProperty("outboxRunning", running)
                addProperty("outboxFailedPermanent", failed)
                addProperty("outboxConflicted", conflicted)
                addProperty("outboxLastUpdatedAt", lastOutbox ?: -1L)
            }
            lastDetailsJson = gson.toJson(obj)

            val readerSparse =
                reader.sdkReady &&
                    !reader.readerOpen &&
                    reader.powerDbm == null &&
                    reader.regionOrFrequency.isNullOrBlank() &&
                    reader.batteryNote.isNullOrBlank()
            val readerTip = if (readerSparse) {
                "\nTip: start Inventory Sync or Anti-Theft once so the reader initializes, then refresh this screen."
            } else {
                ""
            }

            val updatedAt = formatTimestamp(now)

            root.post {
                root.findViewById<TextView>(R.id.diagnosticsReaderSummary).text =
                    "$readerLine\n${reader.detailLine}$readerTip\nUpdated: $updatedAt"
                root.findViewById<TextView>(R.id.diagnosticsNetworkSummary).text =
                    if (online) "Online · checked $updatedAt" else "Offline or no internet route · checked $updatedAt"
                root.findViewById<TextView>(R.id.diagnosticsAuthSummary).text =
                    "$authLine\nChecked: $updatedAt"
                root.findViewById<TextView>(R.id.diagnosticsOutboxSummary).text =
                    "Pending $pending · Retrying $retrying · Running $running · Failed $failed · Conflicted $conflicted" +
                        (lastOutbox?.let { "\nLast queue update: ${formatTimestamp(it)}" } ?: "")
                if (detailsExpanded) {
                    root.findViewById<TextView>(R.id.diagnosticsDetailsText).text = lastDetailsJson
                }
            }
        }
    }

    private fun runUpload(gateway: com.rfidsoftwares.rfid.UhfReaderGateway, root: View) {
        val provider = providerId()
        val adapter = BackendAdapterProvider.getAdapter(provider)
        if (!adapter.capabilities.supportsDiagnosticsUpload) {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage("Diagnostics upload is not supported for this provider.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val corrId = UUID.randomUUID().toString()
        val snapshot = gateway.readDiagnosticsSummary()
        val body = JsonObject().apply {
            addProperty("correlationId", corrId)
            addProperty("readerOpen", snapshot.readerOpen)
            addProperty("sdkReady", snapshot.sdkReady)
            addProperty("detail", snapshot.detailLine)
            snapshot.powerDbm?.let { addProperty("powerDbm", it) }
            snapshot.regionOrFrequency?.let { addProperty("region", it) }
        }
        bg.execute {
            try {
                adapter.uploadDiagnostics(
                    providerConnectionId = provider,
                    payload = DiagnosticsUploadPayload(
                        providerConnectionId = provider,
                        readerDiagnosticsJson = gson.toJson(body),
                    ),
                )
                root.post {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Diagnostics sent successfully.\nReference: $corrId")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: AdapterError) {
                IssueLogSupport.recordFromAdapterError(
                    RfidSessionDbProvider.getInstance(requireContext()),
                    e,
                    IssueCategories.RFID,
                    corrId,
                )
                root.post {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Upload failed")
                        .setMessage("${e.message}\nReference: $corrId")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun formatTimestamp(value: Long?): String {
        if (value == null || value <= 0L) return "unknown"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }
}
