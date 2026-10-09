
package com.rfidsoftwares.ui.screens

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.controller.antitheft.AntiTheftAlarmController
import com.rfidsoftwares.controller.antitheft.AntiTheftEvalUi
import com.rfidsoftwares.controller.antitheft.AntiTheftPresentationState
import com.rfidsoftwares.controller.antitheft.AntiTheftSessionEngine
import com.rfidsoftwares.data.local.AuditRetentionPolicy
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.AuditLogEntity
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.usecases.CatalogSyncUseCase
import com.rfidsoftwares.integration.models.AntiTheftUpdatePayload
import com.rfidsoftwares.issues.IssueActions
import com.rfidsoftwares.issues.IssueCategories
import com.rfidsoftwares.issues.IssueLogSupport
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.DriverUhfReaderGatewayAdapter
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.MockUhfReaderGateway
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.UUID
import java.util.concurrent.Executors

class AntiTheftFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Anti-Theft"
    override fun screenSubtitle(): String? = "Start a scan when ready and review pass or warning results"

    private lateinit var db: RfidSessionDatabase
    private lateinit var engine: AntiTheftSessionEngine
    private var mockGateway: MockUhfReaderGateway? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private lateinit var alarm: AntiTheftAlarmController
    @Volatile
    private var currentScanCorrelationId: String? = null

    private var lastUiState: AntiTheftPresentationState? = null

    private val listener = object : AntiTheftSessionEngine.Listener {
        override fun onAntiTheftState(state: AntiTheftPresentationState) {
            lastUiState = state
            if (!isAdded) return
            val v = view ?: return
            v.post {
                renderState(state)
                syncAlarm(state)
            }
        }

        override fun onStatus(text: String) {
            if (!isAdded) return
            view?.post {
                view?.findViewById<TextView>(R.id.antiTheftStatusText)?.text = text
            }
        }

        override fun onError(message: String) {
            if (!isAdded) return
            view?.post {
                view?.findViewById<TextView>(R.id.antiTheftStatusText)?.text = "Error: $message"
            }
        }

        override fun onAntiTheftAudit(eventType: String, message: String, detail: String?) {
            bgExecutor.execute {
                IssueLogSupport.insertAudit(
                    db,
                    AuditLogEntity(
                        eventType = eventType,
                        message = message,
                        detail = detail,
                        providerConnectionId = ActiveProviderStore.activeProviderId
                            ?: AppConfig.ProviderRegistry.providers.first().providerId,
                        correlationId = currentScanCorrelationId,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_anti_theft, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = RfidSessionDbProvider.getInstance(requireContext())
        alarm = AntiTheftAlarmController(requireContext())

        val gateway = if (FeatureFlags.TEST_MODE_ENABLED && FeatureFlags.UHF_TEST_MODE_ENABLED) {
            MockUhfReaderGateway().also { mockGateway = it }
        } else {
            val driverId = UhfConfigPrefs.load(requireContext()).driverId
            DriverUhfReaderGatewayAdapter(RfidDriverFactory.createDriver(driverId))
        }

        engine = AntiTheftSessionEngine(
            db = db,
            gateway = gateway,
            context = requireContext(),
        )

        val rescan: MaterialButton = view.findViewById(R.id.antiTheftRescanButton)
        val stopScan: MaterialButton = view.findViewById(R.id.antiTheftStopScanButton)
        val stopSound: MaterialButton = view.findViewById(R.id.antiTheftStopSoundButton)
        val finalPass: MaterialButton = view.findViewById(R.id.antiTheftFinalPassButton)

        rescan.setOnClickListener { runResyncAndScan() }
        stopScan.setOnClickListener { engine.stopScanKeepingResults(listener) }
        stopSound.setOnClickListener { engine.stopSoundOnly(listener) }
        finalPass.setOnClickListener { onFinalPassClicked() }
        view.findViewById<TextView>(R.id.antiTheftStatusText).text = "Ready to scan."
    }

    override fun onDestroyView() {
        alarm.release()
        engine.stopOnLeave(listener)
        super.onDestroyView()
    }

    private fun providerId(): String =
        ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId

    private fun runResyncAndScan() {
        engine.rescanStopScan(listener)
        alarm.stopLoop()
        bgExecutor.execute {
            // Stable correlation id for all scan-time anti-theft audit events in this resync+scan cycle.
            currentScanCorrelationId = UUID.randomUUID().toString()

            val provider = providerId()
            val adapter = BackendAdapterProvider.getAdapter(provider)
            try {
                CatalogSyncUseCase(adapter).syncCatalog(provider, db)
            } catch (e: Exception) {
                val corr = UUID.randomUUID().toString()
                IssueLogSupport.insertIssue(
                    db,
                    IssueEntity(
                        issueId = UUID.randomUUID().toString(),
                        severity = "error",
                        category = IssueCategories.SYNC,
                        message = "Catalog resync failed: ${e.message ?: "unknown"}",
                        correlationId = corr,
                        detail = e.javaClass.simpleName,
                        createdAt = System.currentTimeMillis(),
                        active = true,
                        suggestedAction = IssueActions.RETRY_SYNC,
                    ),
                )
                engine.resetToIdleAfterFailure(listener)
                view?.post {
                    listener.onError("Catalog resync failed")
                }
                return@execute
            }
            engine.reloadSnapshot(provider)
            mockGateway?.fixturePathOverride = "test-fixtures/uhf/mock-antitheft-sequence.json"
            engine.startScanning(listener)
            AuditRetentionPolicy.enforce(db)
        }
    }

    private fun renderState(s: AntiTheftPresentationState) {
        val v = view ?: return
        val eval = v.findViewById<TextView>(R.id.antiTheftEvalText)
        val counts = v.findViewById<TextView>(R.id.antiTheftCountsText)
        val rescanButton = v.findViewById<MaterialButton>(R.id.antiTheftRescanButton)
        val adapter = BackendAdapterProvider.getAdapter(providerId())
        val evalLabel = when (s.evaluation) {
            AntiTheftEvalUi.IDLE -> "Idle"
            AntiTheftEvalUi.SCANNING_PASS -> "Pass while scanning"
            AntiTheftEvalUi.SCANNING_WARNING -> "Warning while scanning"
            AntiTheftEvalUi.STOPPED_PASS -> "Pass"
            AntiTheftEvalUi.STOPPED_WARNING -> "Warning"
        }
        eval.text = "Evaluation: $evalLabel"
        counts.text = "Billed: ${s.billedSeenCount} · Active: ${s.activeSeenCount} · Unknown: ${s.unknownSeenCount}"
        rescanButton.text = if (s.scanning) "Restart scan" else "Start scan"

        val canFinalize =
            engine.hasSnapshotLoaded() &&
                !s.scanning &&
                !s.activeEver &&
                adapter.capabilities.supportsAntiTheftFinalize
        v.findViewById<MaterialButton>(R.id.antiTheftFinalPassButton).isEnabled = canFinalize
    }

    private fun syncAlarm(s: AntiTheftPresentationState) {
        if (s.activeEver && !s.alarmMuted) {
            alarm.startLoop()
        } else {
            alarm.stopLoop()
        }
    }

    private fun onFinalPassClicked() {
        val s = lastUiState
        if (s?.activeEver == true) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Final pass blocked")
                .setMessage("Clear the active-tag warning or start a new scan after the floor is clear.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (engine.isScanning()) {
            engine.stopScanKeepingResults(listener)
        }
        val provider = providerId()
        val adapter = BackendAdapterProvider.getAdapter(provider)
        if (!adapter.capabilities.supportsAntiTheftFinalize) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Not supported")
                .setMessage("This provider does not support final pass updates.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val payload = engine.buildFinalizePayload()
        val corrId = UUID.randomUUID().toString()
        bgExecutor.execute {
            try {
                adapter.antiTheftUpdateTags(
                    providerConnectionId = provider,
                    payload = payload
                        ?: AntiTheftUpdatePayload(
                            providerConnectionId = provider,
                            tagsToUpdate = emptyList(),
                        ),
                    idempotencyKey = UUID.randomUUID().toString(),
                    correlationId = corrId,
                )
                IssueLogSupport.insertAudit(
                    db,
                    AuditLogEntity(
                        eventType = "ANTITHEFT_FINAL_PASS",
                        message = "Anti-theft finalize succeeded",
                        detail = null,
                        providerConnectionId = provider,
                        correlationId = corrId,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                view?.post {
                    listener.onStatus("Final pass completed. Reference: $corrId")
                }
            } catch (e: AdapterError) {
                IssueLogSupport.recordFromAdapterError(db, e, IssueCategories.SYNC, corrId)
                view?.post {
                    listener.onError("Final pass failed. Reference: $corrId")
                }
            } catch (e: Exception) {
                view?.post {
                    listener.onError("Final Pass failed: ${e.message}")
                }
            }
        }
    }
}
