
package com.rfidsoftwares.ui.screens

import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.controller.session.InventorySessionEngine
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity
import com.rfidsoftwares.data.local.projections.InventoryProductStateRow
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.usecases.CatalogSyncUseCase
import com.rfidsoftwares.integration.usecases.InventorySessionPushUseCase
import com.rfidsoftwares.integration.workers.SyncOutboxWorker
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.DriverUhfReaderGatewayAdapter
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.MockUhfReaderGateway
import com.rfidsoftwares.ui.dialogs.EpcStatusDialogFragment
import com.rfidsoftwares.ui.HardwareKeyHandler
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.util.concurrent.Executors

class InventorySyncFragment : BaseScreenFragment(), HardwareKeyHandler {

    private enum class InventoryFilter {
        ALL,
        NEEDS_REVIEW,
        MISSING_ONLY,
        VERIFIED,
    }

    private enum class ProductRowState {
        VERIFIED,
        PARTIAL,
        MISSING,
        UNTRACKED,
    }

    private data class InventoryProductUi(
        val productId: String,
        val name: String,
        val skuLine: String,
        val statusLabel: String,
        val countsLine: String,
        val hintLine: String,
        val progressPercent: Int,
        val rowState: ProductRowState,
    )

    private data class InventoryDashboardSnapshot(
        val sessionStateLabel: String,
        val sessionStateVisible: Boolean,
        val statusHeadline: String,
        val uploadState: String,
        val uploadStateVisible: Boolean,
        val productsValue: String,
        val verifiedValue: String,
        val reviewValue: String,
        val unknownValue: String,
        val listHeading: String,
        val products: List<InventoryProductUi>,
        val emptyState: String,
        val missingPreview: String,
        val unknownPreview: String,
        val startLabel: String,
        val idleActionVisible: Boolean,
        val actionRowVisible: Boolean,
        val running: Boolean,
    )

    override fun screenTitle(): String = "Inventory Sync"
    override fun screenSubtitle(): String? = "Verify stock quickly and review gaps before upload"

    private lateinit var db: RfidSessionDatabase
    private lateinit var engine: InventorySessionEngine

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var sessionListener: InventorySessionEngine.Listener? = null
    private var knownMatchTone: ToneGenerator? = null

    private lateinit var sessionStateText: TextView
    private lateinit var sessionStatusText: TextView
    private lateinit var uploadStateText: TextView
    private lateinit var listHeadingText: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var missingPreviewText: TextView
    private lateinit var unknownPreviewText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var finishButton: MaterialButton
    private lateinit var idleActionRow: View
    private lateinit var actionRow: View
    private lateinit var filterGroup: ChipGroup
    private lateinit var filterAllChip: Chip
    private lateinit var filterReviewChip: Chip
    private lateinit var filterMissingChip: Chip
    private lateinit var filterVerifiedChip: Chip
    private lateinit var productsRecycler: RecyclerView

    private lateinit var productsAdapter: InventoryProductAdapter

    private var currentFilter: InventoryFilter = InventoryFilter.ALL
    private var displayedSessionId: String? = null
    private var statusOverride: String? = null
    private var conflictDialogVisible: Boolean = false

    // The RFID scan loop can trigger onTabsUpdated very frequently. Throttling prevents the UI thread from being flooded.
    private val dashboardRefreshInFlight = AtomicBoolean(false)
    private val dashboardRefreshScheduled = AtomicBoolean(false)
    private var lastDashboardRefreshAtMs: Long = 0L
    private val dashboardRefreshMinIntervalMs: Long = 600L

    // Extra guard: the scan loop can call onTabsUpdated even when values did not change.
    // We only schedule a UI refresh if the tab-state signature differs from last time.
    private var lastTabsSignature: String? = null

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_inventory_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmLeaveIfNeeded()
                }
            }
        )

        db = RfidSessionDbProvider.getInstance(requireContext())
        knownMatchTone = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (_: Exception) {
            null
        }

        val gateway = if (FeatureFlags.TEST_MODE_ENABLED && FeatureFlags.UHF_TEST_MODE_ENABLED) {
            MockUhfReaderGateway()
        } else {
            val driverId = UhfConfigPrefs.load(requireContext()).driverId
            DriverUhfReaderGatewayAdapter(RfidDriverFactory.createDriver(driverId))
        }
        engine = InventorySessionEngine(
            db = db,
            gateway = gateway,
            context = requireContext(),
        )

        sessionStateText = view.findViewById(R.id.inventorySessionStateText)
        sessionStatusText = view.findViewById(R.id.sessionStatusText)
        uploadStateText = view.findViewById(R.id.inventoryUploadStateText)
        listHeadingText = view.findViewById(R.id.inventoryListHeadingText)
        emptyStateText = view.findViewById(R.id.inventoryEmptyStateText)
        missingPreviewText = view.findViewById(R.id.inventoryMissingPreviewText)
        unknownPreviewText = view.findViewById(R.id.inventoryUnknownPreviewText)
        startButton = view.findViewById(R.id.startSessionButton)
        refreshButton = view.findViewById(R.id.refreshInventoryButton)
        resetButton = view.findViewById(R.id.resetInventoryButton)
        stopButton = view.findViewById(R.id.stopSessionButton)
        finishButton = view.findViewById(R.id.finishSessionButton)
        idleActionRow = view.findViewById(R.id.inventoryIdleActionRow)
        actionRow = view.findViewById(R.id.inventoryActionRow)
        filterGroup = view.findViewById(R.id.inventoryFilterChipGroup)
        filterAllChip = view.findViewById(R.id.filterAllChip)
        filterReviewChip = view.findViewById(R.id.filterReviewChip)
        filterMissingChip = view.findViewById(R.id.filterMissingChip)
        filterVerifiedChip = view.findViewById(R.id.filterVerifiedChip)
        productsRecycler = view.findViewById(R.id.inventoryProductsRecyclerView)

        productsAdapter = InventoryProductAdapter { item ->
            showEpcStatusDialogForProduct(item)
        }
        productsRecycler.layoutManager = LinearLayoutManager(requireContext())
        productsRecycler.adapter = productsAdapter

        filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.filterReviewChip -> InventoryFilter.NEEDS_REVIEW
                R.id.filterMissingChip -> InventoryFilter.MISSING_ONLY
                R.id.filterVerifiedChip -> InventoryFilter.VERIFIED
                else -> InventoryFilter.ALL
            }
            refreshDashboard()
        }

        val listener = object : InventorySessionEngine.Listener {
            override fun onStatus(text: String) {
                statusOverride = text
                postUi {
                    sessionStatusText.text = text
                }
            }

            override fun onTabsUpdated(state: InventorySessionEngine.SessionTabsState) {
                val signature = "${state.allProductsCount}|${state.foundProductsCount}|${state.notFoundProductsCount}|${state.unknownEpcCount}"
                if (signature == lastTabsSignature) return
                lastTabsSignature = signature
                requestDashboardRefresh()
            }

            override fun onSessionFinished(sessionId: String) {
                displayedSessionId = sessionId
                statusOverride = "Stock check finished. Review the results below before upload."
                refreshDashboard()
                bgExecutor.execute {
                    if (!isAdded) return@execute
                    try {
                        val store = com.rfidsoftwares.data.local.MissingItemsLocalStore.getInstance(requireContext())
                        val session = db.inventorySessionDao().getSession(sessionId) ?: return@execute

                        // Persist "missing EPCs" locally so the admin can review/locate them later.
                        // We merge/accumulate across sessions for now (server sync comes later).
                        try {
                            store.mergeMissingFromSessionBlocking(
                                providerConnectionId = session.providerConnectionId,
                                sessionId = sessionId,
                                db = db,
                            )
                        } catch (_: Exception) {
                            // Missing-item persistence must never block upload review UX.
                        }

                        val adapter = BackendAdapterProvider.getAdapter(session.providerConnectionId)
                        val pushData = InventorySessionPushUseCase(adapter).computePushData(sessionId, db)
                        if (!pushData.hasMismatch) {
                            statusOverride = "All tracked products matched the catalog snapshot."
                            refreshDashboard()
                            return@execute
                        }
                        postUi {
                            showMismatchDialog(
                                sessionId = sessionId,
                                mismatchCount = pushData.mismatchCount,
                                canPush = session.state == "finished",
                            )
                        }
                    } catch (_: Exception) {
                        statusOverride = "The session finished, but the upload review could not be prepared."
                        refreshDashboard()
                    }
                }
            }

            override fun onSessionStopped(sessionId: String, incomplete: Boolean) {
                displayedSessionId = sessionId
                statusOverride = if (incomplete) {
                    "Stock check paused. You can resume from where you stopped."
                } else {
                    "Stock check stopped."
                }
                refreshDashboard()
            }

            override fun onError(message: String) {
                statusOverride = message
                refreshDashboard()
            }

            override fun onKnownEpcMatched(epc: String) {
                if (!isAdded) return
                view.post {
                    try {
                        knownMatchTone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        sessionListener = listener

        startButton.setOnClickListener {
            handlePrimaryEntry(triggeredByHardware = false)
        }
        refreshButton.setOnClickListener {
            handleRefreshCatalog()
        }
        resetButton.setOnClickListener {
            handleResetViewState()
        }
        stopButton.setOnClickListener {
            setActionButtonsBusy()
            bgExecutor.execute {
                sessionListener?.let { engine.stopIncomplete(it) }
            }
        }
        finishButton.setOnClickListener {
            setActionButtonsBusy()
            bgExecutor.execute {
                sessionListener?.let { engine.finishSession(it) }
            }
        }

        statusOverride = "Start stock check."
        refreshDashboard()
        checkAndSurfaceConflictedOutboxJob()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        checkAndSurfaceConflictedOutboxJob()
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = { pressed ->
            if (pressed) {
                activity?.runOnUiThread {
                    val focused = activity?.currentFocus
                    if (focused !is EditText) {
                        handleIdleHardwareTrigger()
                    }
                }
            }
        }
    }

    override fun onPause() {
        com.rfidsoftwares.hardware.HardwareTriggerHelper.triggerListener = null
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        val listener = sessionListener ?: return
        if (!::engine.isInitialized || !engine.isScanActive()) return
        bgExecutor.execute {
            try {
                engine.stopActiveAsIncompleteIfScanActive(listener)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroyView() {
        try {
            knownMatchTone?.release()
        } catch (_: Exception) {
        }
        knownMatchTone = null

        val listener = sessionListener
        if (::engine.isInitialized && engine.isScanActive() && listener != null) {
            bgExecutor.execute {
                try {
                    engine.stopActiveAsIncompleteIfScanActive(listener)
                } catch (_: Exception) {
                }
            }
        }
        sessionListener = null
        super.onDestroyView()
    }

    private fun postUi(block: () -> Unit) {
        view?.post(block)
    }

    private fun currentProviderId(): String =
        ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId

    private fun setActionButtonsBusy() {
        startButton.isEnabled = false
        refreshButton.isEnabled = false
        resetButton.isEnabled = false
        stopButton.isEnabled = false
        finishButton.isEnabled = false
    }

    private fun handleResetViewState() {
        if (!::db.isInitialized || !isAdded || engine.isScanActive()) return
        setActionButtonsBusy()
        currentFilter = InventoryFilter.ALL
        filterAllChip.isChecked = true
        displayedSessionId = null
        statusOverride = "Resetting inventory data..."
        sessionStatusText.text = statusOverride
        bgExecutor.execute {
            val provider = currentProviderId()
            clearInventoryData(provider)
            val adapter = BackendAdapterProvider.getAdapter(provider)
            try {
                CatalogSyncUseCase(adapter).syncCatalog(providerConnectionId = provider, db = db)
                statusOverride = "Reset complete. Refreshed from ${catalogSourceLabel()}."
            } catch (e: AdapterError) {
                statusOverride = "Data cleared, but refresh failed: ${e.message.orEmpty()}"
            } catch (_: Exception) {
                statusOverride = "Data cleared, but refresh failed."
            }
            lastTabsSignature = null
            refreshDashboard()
        }
    }

    private fun handleRefreshCatalog() {
        if (!::db.isInitialized || !isAdded || engine.isScanActive()) return
        setActionButtonsBusy()
        currentFilter = InventoryFilter.ALL
        filterAllChip.isChecked = true
        displayedSessionId = null
        statusOverride = "Refreshing catalog..."
        sessionStatusText.text = statusOverride
        bgExecutor.execute {
            val provider = currentProviderId()
            val adapter = BackendAdapterProvider.getAdapter(provider)
            try {
                CatalogSyncUseCase(adapter).syncCatalog(providerConnectionId = provider, db = db)
                val active = db.inventorySessionDao().getActiveSession(provider)
                displayedSessionId = active?.sessionId
                statusOverride = if (active != null) {
                    "Refreshed from ${catalogSourceLabel()}. Resume when ready."
                } else {
                    "Refreshed from ${catalogSourceLabel()}. Start stock check."
                }
            } catch (e: AdapterError) {
                statusOverride = "Could not refresh catalog: ${e.message.orEmpty()}"
            } catch (_: Exception) {
                statusOverride = "Could not refresh catalog."
            }
            lastTabsSignature = null
            refreshDashboard()
        }
    }

    private fun catalogSourceLabel(): String {
        return if (FeatureFlags.TEST_MODE_ENABLED) {
            "test data"
        } else {
            "connected API"
        }
    }

    private fun clearInventoryData(provider: String) {
        db.syncOutboxDao().deleteByProvider(provider)
        db.unknownEpcCacheDao().deleteByProvider(provider)
        db.sessionScanDao().deleteByProvider(provider)
        db.sessionProductStateDao().deleteByProvider(provider)
        db.inventorySessionDao().deleteByProvider(provider)
        db.productEpcDao().deleteByProvider(provider)
        db.productDao().deleteByProvider(provider)
    }

    private fun launchSession(forceNew: Boolean, provider: String) {
        setActionButtonsBusy()
        statusOverride = "Refreshing products before scanning..."
        sessionStatusText.text = statusOverride
        bgExecutor.execute {
            val listener = sessionListener ?: return@execute
            val adapter = BackendAdapterProvider.getAdapter(provider)
            try {
                CatalogSyncUseCase(adapter).syncCatalog(providerConnectionId = provider, db = db)
            } catch (e: AdapterError) {
                statusOverride = "Could not refresh products before scanning: ${e.message.orEmpty()}"
                refreshDashboard()
                return@execute
            } catch (_: Exception) {
                statusOverride = "Could not refresh products before scanning."
                refreshDashboard()
                return@execute
            }

            val active = db.inventorySessionDao().getActiveSession(provider)
            if (active != null && !forceNew) {
                displayedSessionId = active.sessionId
                statusOverride = "Resuming saved stock check..."
                engine.resumeSession(active.sessionId, active.providerConnectionId, listener)
                return@execute
            }

            if (active != null && forceNew) {
                db.inventorySessionDao().updateSessionState(
                    sessionId = active.sessionId,
                    state = "incomplete",
                    finishedAt = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }

            displayedSessionId = null
            statusOverride = "Starting new stock check..."
            engine.startNewSession(
                InventorySessionEngine.StartRequest(
                    providerConnectionId = provider,
                    operatorId = AppConfig.BootstrapAdminPolicy.BOOTSTRAP_USERNAME,
                    locationId = "default_location",
                ),
                listener,
            )
        }
    }

    private fun refreshDashboard() {
        if (!::db.isInitialized) return
        bgExecutor.execute {
            if (!isAdded) return@execute
            if (!dashboardRefreshInFlight.compareAndSet(false, true)) return@execute
            val provider = currentProviderId()
            val active = db.inventorySessionDao().getActiveSession(provider)
            val sessionToShow = active ?: displayedSessionId?.let { db.inventorySessionDao().getSession(it) }
            displayedSessionId = sessionToShow?.sessionId
            val snapshot = buildDashboardSnapshot(sessionToShow)
            postUi {
                try {
                    renderDashboard(snapshot)
                } finally {
                    dashboardRefreshInFlight.set(false)
                }
            }
        }
    }

    private fun requestDashboardRefresh(force: Boolean = false) {
        if (!force) {
            // If a refresh render is already in progress, just make sure we do one more later.
            if (dashboardRefreshInFlight.get()) {
                if (dashboardRefreshScheduled.compareAndSet(false, true)) {
                    view?.postDelayed({
                        dashboardRefreshScheduled.set(false)
                        lastDashboardRefreshAtMs = System.currentTimeMillis()
                        refreshDashboard()
                    }, 250L)
                }
                return
            }

            val now = System.currentTimeMillis()
            val elapsed = now - lastDashboardRefreshAtMs
            if (elapsed < dashboardRefreshMinIntervalMs) {
                if (dashboardRefreshScheduled.compareAndSet(false, true)) {
                    val delay = (dashboardRefreshMinIntervalMs - elapsed).coerceAtLeast(0L)
                    view?.postDelayed({
                        dashboardRefreshScheduled.set(false)
                        lastDashboardRefreshAtMs = System.currentTimeMillis()
                        refreshDashboard()
                    }, delay)
                }
                return
            }
        }

        lastDashboardRefreshAtMs = System.currentTimeMillis()
        refreshDashboard()
    }

    private fun handlePrimaryEntry(triggeredByHardware: Boolean) {
        setActionButtonsBusy()
        bgExecutor.execute {
            val provider = currentProviderId()
            val active = db.inventorySessionDao().getActiveSession(provider)
            if (active != null) {
                if (triggeredByHardware) {
                    statusOverride = "Resuming saved stock check..."
                    launchSession(forceNew = false, provider = provider)
                } else {
                    postUi {
                        showResumeOrRestartDialog(active, provider)
                    }
                }
            } else {
                statusOverride = "Starting new stock check..."
                launchSession(forceNew = false, provider = provider)
            }
        }
    }

    private fun showResumeOrRestartDialog(active: InventorySessionEntity, provider: String) {
        if (!isAdded) return
        startButton.isEnabled = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Continue stock check?")
            .setMessage("A paused stock check was found for this store. Resume it or start a fresh stock check.")
            .setNegativeButton("Cancel") { _, _ ->
                refreshDashboard()
            }
            .setNeutralButton("Start fresh") { _, _ ->
                launchSession(forceNew = true, provider = provider)
            }
            .setPositiveButton("Resume") { _, _ ->
                displayedSessionId = active.sessionId
                statusOverride = "Resuming saved stock check..."
                launchSession(forceNew = false, provider = provider)
            }
            .show()
    }

    private fun buildDashboardSnapshot(
        session: InventorySessionEntity?,
    ): InventoryDashboardSnapshot {
        val rows = if (session != null) {
            db.sessionProductStateDao().getInventoryRows(session.sessionId, session.providerConnectionId)
        } else {
            emptyList()
        }
        val unknownCount = if (session != null) {
            db.sessionScanDao().countUnknownForSession(session.sessionId, session.providerConnectionId)
        } else {
            0
        }
        val unknownRecent = if (session != null) {
            db.sessionScanDao().getUnknownEpcsForSession(session.sessionId, session.providerConnectionId, 5)
        } else {
            emptyList()
        }
        val latestJob = session?.let { db.syncOutboxDao().getLatestJobForSession(it.sessionId) }

        val products = rows.map { row -> row.toUiModel() }
        val filteredProducts = products.filterBy(currentFilter)

        val trackableRows = rows.filter { it.expectedCount > 0 }
        val untrackedProducts = rows.count { it.expectedCount <= 0 }
        val totalProducts = trackableRows.size
        val verifiedProducts = trackableRows.count { it.foundCount >= it.expectedCount }
        val reviewProducts = trackableRows.count { it.foundCount < it.expectedCount }
        val expectedTags = rows.sumOf { it.expectedCount }
        val foundTags = rows.sumOf { it.foundCount }

        val missingPreview = rows
            .filter { it.foundCount < it.expectedCount }
            .take(3)
            .joinToString("\n") { row ->
                val missingCount = (row.expectedCount - row.foundCount).coerceAtLeast(0)
                "${row.nameWithSku()} - missing $missingCount"
            }
            .ifBlank {
                if (untrackedProducts > 0) {
                    "$untrackedProducts catalog product(s) have no assigned tags and are excluded from verification totals."
                } else {
                    "No products are waiting for a stock review right now."
                }
            }

        val unknownPreview = if (unknownRecent.isEmpty()) {
            "No unrecognized tags have been scanned in this session."
        } else {
            buildString {
                append("Recent unrecognized tags:")
                unknownRecent.forEach { epc ->
                    append("\n• ")
                    append(epc)
                }
            }
        }

        val pausedSessionExists = session?.state == "incomplete" && !engine.isScanActive()
        val running = engine.isScanActive()
        val finishedSession = session?.state == "finished"

        val sessionStateLabel = when {
            running -> "Live scan"
            pausedSessionExists -> "Paused"
            finishedSession -> "Finished"
            else -> ""
        }

        val defaultHeadline = when {
            running -> "$foundTags of $expectedTags tags verified"
            pausedSessionExists -> "$foundTags of $expectedTags tags verified"
            finishedSession -> "$verifiedProducts of $totalProducts products verified"
            session == null -> "Start stock check"
            else -> "Review session"
        }

        val uploadState = when {
            session == null -> ""
            session.state != "finished" -> ""
            latestJob == null && reviewProducts == 0 && unknownCount == 0 && untrackedProducts == 0 ->
                "Everything matched. Upload is optional."
            latestJob == null && reviewProducts == 0 && unknownCount == 0 ->
                "All tagged products matched. $untrackedProducts product(s) have no assigned tags."
            latestJob == null ->
                "Review the result below before upload."
            else -> {
                val stateLabel = when (latestJob.state) {
                    "pending" -> "Upload queued"
                    "retrying" -> "Upload retrying"
                    "running" -> "Upload in progress"
                    "conflicted" -> "Upload conflict needs review"
                    "failed_permanent" -> "Upload stopped"
                    "done", "completed", "success" -> "Upload completed"
                    else -> "Upload status: ${latestJob.state.replaceFirstChar { it.uppercase() }}"
                }
                val errorSummary = latestJob.lastError?.let { " - $it" }.orEmpty()
                "$stateLabel$errorSummary"
            }
        }

        val startLabel = when {
            pausedSessionExists -> "Resume"
            finishedSession -> "Start new"
            else -> "Start"
        }

        val listHeading = when (currentFilter) {
            InventoryFilter.ALL -> "Products to verify"
            InventoryFilter.NEEDS_REVIEW -> "Products needing review"
            InventoryFilter.MISSING_ONLY -> "Products still missing"
            InventoryFilter.VERIFIED -> "Verified products"
        }

        val emptyState = when (currentFilter) {
            InventoryFilter.ALL -> "Products will appear here once a session is ready."
            InventoryFilter.NEEDS_REVIEW -> "Nothing needs review in the current filter."
            InventoryFilter.MISSING_ONLY -> "No products are fully missing right now."
            InventoryFilter.VERIFIED -> "No fully verified products yet."
        }

        return InventoryDashboardSnapshot(
            sessionStateLabel = sessionStateLabel,
            sessionStateVisible = sessionStateLabel.isNotBlank(),
            statusHeadline = if (session == null && !running) defaultHeadline else (statusOverride ?: defaultHeadline),
            uploadState = uploadState,
            uploadStateVisible = uploadState.isNotBlank(),
            productsValue = totalProducts.toString(),
            verifiedValue = verifiedProducts.toString(),
            reviewValue = reviewProducts.toString(),
            unknownValue = unknownCount.toString(),
            listHeading = listHeading,
            products = filteredProducts,
            emptyState = emptyState,
            missingPreview = missingPreview,
            unknownPreview = unknownPreview,
            startLabel = startLabel,
            idleActionVisible = !running,
            actionRowVisible = running,
            running = running,
        )
    }

    private fun renderDashboard(snapshot: InventoryDashboardSnapshot) {
        sessionStateText.text = snapshot.sessionStateLabel
        sessionStateText.visibility = if (snapshot.sessionStateVisible) View.VISIBLE else View.GONE
        sessionStatusText.text = snapshot.statusHeadline
        uploadStateText.text = snapshot.uploadState
        uploadStateText.visibility = if (snapshot.uploadStateVisible) View.VISIBLE else View.GONE
        listHeadingText.text = snapshot.listHeading
        missingPreviewText.text = snapshot.missingPreview
        unknownPreviewText.text = snapshot.unknownPreview

        bindStatCard(R.id.inventoryProductsStatCard, "Products", snapshot.productsValue)
        bindStatCard(R.id.inventoryVerifiedStatCard, "Verified", snapshot.verifiedValue)
        bindStatCard(R.id.inventoryReviewStatCard, "Need review", snapshot.reviewValue)
        bindStatCard(R.id.inventoryUnknownStatCard, "Unknown tags", snapshot.unknownValue)

        productsAdapter.submitItems(snapshot.products)
        emptyStateText.text = snapshot.emptyState
        emptyStateText.visibility = if (snapshot.products.isEmpty()) View.VISIBLE else View.GONE
        productsRecycler.visibility = if (snapshot.products.isEmpty()) View.GONE else View.VISIBLE

        startButton.text = snapshot.startLabel
        idleActionRow.visibility = if (snapshot.idleActionVisible) View.VISIBLE else View.GONE
        actionRow.visibility = if (snapshot.actionRowVisible) View.VISIBLE else View.GONE

        startButton.isEnabled = !snapshot.running
        refreshButton.isEnabled = !snapshot.running
        resetButton.isEnabled = !snapshot.running
        stopButton.isEnabled = snapshot.running
        finishButton.isEnabled = snapshot.running

        when (currentFilter) {
            InventoryFilter.ALL -> filterAllChip.isChecked = true
            InventoryFilter.NEEDS_REVIEW -> filterReviewChip.isChecked = true
            InventoryFilter.MISSING_ONLY -> filterMissingChip.isChecked = true
            InventoryFilter.VERIFIED -> filterVerifiedChip.isChecked = true
        }
    }

    private fun bindStatCard(cardId: Int, label: String, value: String) {
        val card = view?.findViewById<View>(cardId) ?: return
        val valueView: TextView = card.findViewById(R.id.dashboardStatValue)
        val labelView: TextView = card.findViewById(R.id.dashboardStatLabel)
        valueView.text = value
        labelView.text = label
    }

    private fun InventoryProductStateRow.toUiModel(): InventoryProductUi {
        val missing = (expectedCount - foundCount).coerceAtLeast(0)
        val progress = if (expectedCount <= 0) {
            100
        } else {
            ((foundCount.toFloat() / expectedCount.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }
        val rowState = when {
            expectedCount <= 0 -> ProductRowState.UNTRACKED
            foundCount >= expectedCount -> ProductRowState.VERIFIED
            foundCount == 0 -> ProductRowState.MISSING
            else -> ProductRowState.PARTIAL
        }
        val statusLabel = when (rowState) {
            ProductRowState.VERIFIED -> "Verified"
            ProductRowState.PARTIAL -> "Partially found"
            ProductRowState.MISSING -> "Missing"
            ProductRowState.UNTRACKED -> "No tags assigned"
        }
        val hint = when (rowState) {
            ProductRowState.VERIFIED -> "All expected tags have been scanned for this product."
            ProductRowState.PARTIAL -> "Keep scanning this product. $missing tag(s) still missing."
            ProductRowState.MISSING -> "No matching tag has been scanned for this product yet."
            ProductRowState.UNTRACKED -> "This product has no expected tag count in the current catalog."
        }
        val countLine = when (rowState) {
            ProductRowState.UNTRACKED -> "No expected tag count is assigned in the catalog."
            else -> "$foundCount of $expectedCount tags verified"
        }
        val skuLine = buildString {
            append(sku?.takeIf { it.isNotBlank() } ?: "No SKU")
            if (productStatus.isNotBlank()) {
                append(" · ")
                append(productStatus.replaceFirstChar { it.uppercase() })
            }
        }
        return InventoryProductUi(
            productId = productId,
            name = productName,
            skuLine = skuLine,
            statusLabel = statusLabel,
            countsLine = countLine,
            hintLine = hint,
            progressPercent = progress,
            rowState = rowState,
        )
    }

    private fun showEpcStatusDialogForProduct(product: InventoryProductUi) {
        val provider = currentProviderId()
        val sessionId = displayedSessionId
        val scanAvailable = sessionId != null

        bgExecutor.execute {
            val expectedEpcs = db.productEpcDao()
                .getProductEpcsForProduct(providerConnectionId = provider, productId = product.productId)
                .map { it.epc.trim().uppercase(Locale.US) }
                .distinct()

            val foundEpcs = if (scanAvailable) {
                db.sessionScanDao().getFoundKnownEpcsForProduct(
                    sessionId = sessionId.orEmpty(),
                    providerConnectionId = provider,
                    productId = product.productId,
                )
                    .map { it.trim().uppercase(Locale.US) }
                    .distinct()
            } else {
                emptyList()
            }

            if (!isAdded) return@execute
            postUi {
                val subtitle = if (scanAvailable) {
                    "Found vs missing EPCs for this session"
                } else {
                    "Catalog EPCs (no active scan session)"
                }
                val dialog = EpcStatusDialogFragment.newInstance(
                    title = product.name,
                    subtitle = subtitle,
                    epcs = ArrayList(expectedEpcs),
                    foundEpcs = ArrayList(foundEpcs),
                    scanAvailable = scanAvailable,
                    providerConnectionId = provider,
                    sessionId = sessionId.orEmpty(),
                    productId = product.productId,
                )
                dialog.show(childFragmentManager, "EpcStatusDialog:${product.productId}")
            }
        }
    }

    private fun InventoryProductStateRow.nameWithSku(): String {
        val skuText = sku?.takeIf { it.isNotBlank() } ?: return productName
        return "$productName ($skuText)"
    }

    private fun List<InventoryProductUi>.filterBy(filter: InventoryFilter): List<InventoryProductUi> {
        return when (filter) {
            InventoryFilter.ALL -> this
            InventoryFilter.NEEDS_REVIEW -> filter { it.rowState == ProductRowState.PARTIAL || it.rowState == ProductRowState.MISSING }
            InventoryFilter.MISSING_ONLY -> filter { it.rowState == ProductRowState.MISSING }
            InventoryFilter.VERIFIED -> filter { it.rowState == ProductRowState.VERIFIED }
        }
    }

    private fun isC72TriggerKey(keyCode: Int): Boolean {
        return com.rfidsoftwares.hardware.HardwareTriggerHelper.isTriggerKey(keyCode)
    }

    private fun handleIdleHardwareTrigger() {
        if (!isAdded || !::engine.isInitialized || engine.isScanActive()) return
        postUi {
            handlePrimaryEntry(triggeredByHardware = true)
        }
    }

    override fun onHardwareKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isC72TriggerKey(keyCode)) return false
        if (event.repeatCount > 0) return true

        // Chainway C72 "gun" buttons are sometimes configured as a keyboard-wedge reader.
        // If the user is actively typing into an input, don't consume the trigger key.
        val focused = activity?.currentFocus
        if (focused is EditText) return false

        handleIdleHardwareTrigger()
        return true
    }

    private fun showMismatchDialog(sessionId: String, mismatchCount: Int, canPush: Boolean) {
        if (!isAdded) return
        val message = buildString {
            append("$mismatchCount product(s) still need review before upload.")
            if (!canPush) {
                append("\n\nUpload is blocked until the stock check is finished.")
            } else {
                append("\n\nUnrecognized tags are excluded from upload and stay listed on this screen for review.")
            }
        }
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Review stock differences")
            .setMessage(message)
            .setNegativeButton("Keep reviewing") { _, _ ->
                statusOverride = "Review the products below before you queue the upload."
                refreshDashboard()
            }
            .setCancelable(false)
        if (canPush) {
            builder.setPositiveButton("Queue upload") { _, _ ->
                enqueuePushJob(sessionId)
            }
        }
        builder.show()
    }

    private fun enqueuePushJob(sessionId: String) {
        val appContext = requireContext().applicationContext
        bgExecutor.execute {
            try {
                val session = db.inventorySessionDao().getSession(sessionId)
                if (session == null || session.state != "finished") {
                    statusOverride = "Upload skipped because the session is not ready."
                    refreshDashboard()
                    return@execute
                }
                val adapter = BackendAdapterProvider.getAdapter(session.providerConnectionId)
                val created = InventorySessionPushUseCase(adapter).enqueueOutboxJob(sessionId, db)
                statusOverride = if (created) {
                    SyncOutboxWorker.schedule(appContext)
                    "Upload queued. It will retry automatically if the network is unavailable."
                } else {
                    "An upload is already queued for this session."
                }
            } catch (e: AdapterError) {
                statusOverride = "Could not queue the upload: ${e.message.orEmpty()}"
            } catch (_: Exception) {
                statusOverride = "Could not queue the upload."
            }
            refreshDashboard()
        }
    }

    private fun checkAndSurfaceConflictedOutboxJob() {
        if (!isAdded || conflictDialogVisible || !::db.isInitialized) return
        val provider = currentProviderId()
        bgExecutor.execute {
            val conflicted = db.syncOutboxDao().getLatestConflictedJob(provider) ?: return@execute
            if (!isAdded || conflictDialogVisible) return@execute
            postUi {
                showConflictedOutboxDialog(conflicted)
            }
        }
    }

    private fun showConflictedOutboxDialog(job: SyncOutboxEntity) {
        if (!isAdded || conflictDialogVisible) return
        conflictDialogVisible = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Upload conflict needs review")
            .setMessage(
                "A previous stock upload for this store conflicted with newer remote data.\n\n" +
                    "Session: ${job.sessionId}\n" +
                    "Reason: ${job.lastError ?: "Remote data changed before upload completed."}\n\n" +
                    "Keep it if you want to review the stock check again, or discard it if you do not want this upload retried."
            )
            .setNegativeButton("Keep for review") { _, _ ->
                conflictDialogVisible = false
                statusOverride = "Upload conflict kept for review."
                refreshDashboard()
            }
            .setPositiveButton("Discard upload") { _, _ ->
                bgExecutor.execute {
                    db.syncOutboxDao().discardConflictedJob(
                        jobId = job.jobId,
                        reason = "Discarded after conflict review",
                        now = System.currentTimeMillis(),
                    )
                    conflictDialogVisible = false
                    statusOverride = "Conflicted upload discarded."
                    refreshDashboard()
                }
            }
            .setOnDismissListener {
                conflictDialogVisible = false
            }
            .setCancelable(true)
            .show()
    }

    override fun handleBackNavigation() {
        confirmLeaveIfNeeded()
    }

    private fun confirmLeaveIfNeeded() {
        if (!::engine.isInitialized || !engine.isScanActive()) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Leave active stock check?")
            .setMessage("Leaving now will pause the current stock check so it can be resumed later.")
            .setNegativeButton("Stay", null)
            .setPositiveButton("Leave") { _, _ ->
                findNavController().navigateUp()
            }
            .show()
    }

    private class InventoryProductAdapter(
        private val onProductClicked: (InventoryProductUi) -> Unit,
    ) : RecyclerView.Adapter<InventoryProductAdapter.ProductViewHolder>() {
        private var items: List<InventoryProductUi> = emptyList()

        fun submitItems(newItems: List<InventoryProductUi>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_inventory_product_row, parent, false)
            return ProductViewHolder(view)
        }

        override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onProductClicked(item) }
        }

        override fun getItemCount(): Int = items.size

        class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val root: MaterialCardView = itemView.findViewById(R.id.inventoryProductRowRoot)
            private val name: TextView = itemView.findViewById(R.id.inventoryProductRowName)
            private val sku: TextView = itemView.findViewById(R.id.inventoryProductRowSku)
            private val status: TextView = itemView.findViewById(R.id.inventoryProductRowStatus)
            private val counts: TextView = itemView.findViewById(R.id.inventoryProductRowCount)
            private val hint: TextView = itemView.findViewById(R.id.inventoryProductRowHint)
            private val progress: LinearProgressIndicator = itemView.findViewById(R.id.inventoryProductRowProgress)

            fun bind(item: InventoryProductUi) {
                val ctx = itemView.context
                name.text = item.name
                sku.text = item.skuLine
                status.text = item.statusLabel
                counts.text = item.countsLine
                hint.text = item.hintLine
                progress.setProgressCompat(item.progressPercent, true)

                val accentColor = when (item.rowState) {
                    ProductRowState.VERIFIED -> ContextCompat.getColor(ctx, R.color.rfid_online)
                    ProductRowState.PARTIAL -> ContextCompat.getColor(ctx, R.color.rfid_primary)
                    ProductRowState.MISSING -> ContextCompat.getColor(ctx, R.color.rfid_error)
                    ProductRowState.UNTRACKED -> ContextCompat.getColor(ctx, R.color.rfid_secondary)
                }
                status.setTextColor(accentColor)
                status.setTypeface(null, Typeface.BOLD)
                root.strokeColor = ContextCompat.getColor(ctx, R.color.rfid_card_stroke)
                root.setCardBackgroundColor(
                    if (item.rowState == ProductRowState.VERIFIED) {
                        ContextCompat.getColor(ctx, R.color.rfid_chip_background)
                    } else {
                        ContextCompat.getColor(ctx, R.color.rfid_card_surface)
                    }
                )
                progress.setIndicatorColor(accentColor)
            }
        }
    }
}

