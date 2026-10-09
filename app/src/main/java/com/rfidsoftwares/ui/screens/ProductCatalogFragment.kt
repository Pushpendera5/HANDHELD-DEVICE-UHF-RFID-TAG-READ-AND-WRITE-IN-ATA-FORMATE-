
package com.rfidsoftwares.ui.screens

import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.text.Editable
import androidx.core.content.ContextCompat
import android.text.TextWatcher
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.controller.proximity.ProximityBeepController
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.usecases.CatalogSyncUseCase
import com.rfidsoftwares.common.prefs.UhfConfigPrefs
import com.rfidsoftwares.rfid.driver.DriverUhfReaderGatewayAdapter
import com.rfidsoftwares.rfid.driver.RfidDriverFactory
import com.rfidsoftwares.rfid.MockUhfReaderGateway
import com.rfidsoftwares.rfid.UhfReaderGateway
import com.rfidsoftwares.ui.base.BaseScreenFragment
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Catalog + registered EPC detail, plus a scan-based “availability” check.
 *
 * Availability is derived by:
 * - expected EPCs = EPCs registered for the selected product in Room
 * - detected EPCs = EPCs read from the UHF reader during a short scan window
 */
class ProductCatalogFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Product Catalog"

    override fun screenSubtitle(): String? = "Search products, open EPC details, and locate selected tags"

    // Product catalog needs full-touch scrolling; the offline overlay can intercept gestures.
    override fun allowOfflinePanel(): Boolean = false

    private var bg = Executors.newSingleThreadExecutor()

    private lateinit var providerId: String

    private lateinit var productRecycler: RecyclerView
    private lateinit var epcRecycler: RecyclerView

    private lateinit var catalogSearchInput: TextInputEditText
    private lateinit var catalogSearchButton: MaterialButton
    private lateinit var epcSearchInput: TextInputEditText
    private lateinit var epcSearchButton: MaterialButton

    private lateinit var productDetailTitle: TextView
    private lateinit var productDetailSubtitle: TextView
    private lateinit var scanAvailabilityButton: MaterialButton
    private lateinit var locateSelectedEpcText: TextView
    private lateinit var locateGuidanceText: TextView
    private lateinit var locateDistanceText: TextView
    private lateinit var locateSignalProgress: LinearProgressIndicator
    private lateinit var availabilityStatusText: TextView

    private var productsAll: List<ProductEntity> = emptyList()
    private var epcsAll: List<ProductEpcEntity> = emptyList()
    private var epcsByProductId: Map<String, List<ProductEpcEntity>> = emptyMap()

    private var filteredProducts: List<ProductEntity> = emptyList()

    private var selectedProductId: String? = null
    private var highlightEpc: String? = null

    private var epcAvailabilityByEpc: Map<String, Boolean> = emptyMap()
    private var scanActive: Boolean = false

    private lateinit var productAdapter: ProductAdapter
    private lateinit var epcAdapter: EpcAdapter

    private lateinit var proximityBeepController: ProximityBeepController

    // EPC selected for "locate" beeping.
    private var targetEpcNorm: String? = null

    // Optional navigation deep-link args for preselect + locating.
    private var pendingTargetProductId: String? = null
    private var pendingTargetEpc: String? = null
    private var pendingAutoStartLocate: Boolean = false

    private data class LocateUiState(
        val selectedEpc: String?,
        val guidance: String,
        val distance: String,
        val status: String,
        val signalStrength: Int,
    )

    companion object {
        private const val LOCATE_IDLE_GUIDANCE = "Choose an EPC to start live locating."
        private const val LOCATE_IDLE_DISTANCE = "Estimated distance: --"
        private const val LOCATE_HISTORY_SIZE = 6
        private const val LOCATE_SIGNAL_FRESH_MS = 250L
        private const val LOCATE_SIGNAL_LOST_MS = 1800L
        private const val LOCATE_SIGNAL_WARN_MS = 700L
        private const val LOCATE_UI_UPDATE_MS = 120L
    }

    private fun postUi(block: () -> Unit) {
        // Avoid requireActivity()/requireContext() from background threads after navigation.
        val root = view ?: return
        root.post { block() }
    }

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_product_catalog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()

        providerId =
            ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId

        // Deep-link support (Missing Items -> locate).
        val navArgs = arguments
        pendingTargetProductId = navArgs?.getString("targetProductId")?.takeIf { it.isNotBlank() }
        pendingTargetEpc = navArgs?.getString("targetEpc")?.takeIf { it.isNotBlank() }
        pendingAutoStartLocate = navArgs?.getBoolean("autoStartLocate", false) ?: false

        productRecycler = view.findViewById(R.id.productRecyclerView)
        epcRecycler = view.findViewById(R.id.epcRecyclerView)

        catalogSearchInput = view.findViewById(R.id.catalogSearchInput)
        catalogSearchButton = view.findViewById(R.id.catalogSearchButton)
        epcSearchInput = view.findViewById(R.id.epcSearchInput)
        epcSearchButton = view.findViewById(R.id.epcSearchButton)

        // Some Chainway C72 keyboard-wedge configurations append `\r`/`\n` after the EPC.
        // Keep this EPC input strictly one-line.
        var epcInputReentryGuard = false
        epcSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (epcInputReentryGuard) return
                val raw = s?.toString().orEmpty()
                if (!raw.contains('\n') && !raw.contains('\r')) return

                val cleaned = raw.replace("\r", "").replace("\n", "")
                if (cleaned == raw) return

                epcInputReentryGuard = true
                try {
                    epcSearchInput.setText(cleaned)
                    epcSearchInput.setSelection(cleaned.length)
                } finally {
                    epcInputReentryGuard = false
                }
            }
        })

        productDetailTitle = view.findViewById(R.id.productDetailTitle)
        productDetailSubtitle = view.findViewById(R.id.productDetailSubtitle)
        scanAvailabilityButton = view.findViewById(R.id.scanAvailabilityButton)
        locateSelectedEpcText = view.findViewById(R.id.locateSelectedEpcText)
        locateGuidanceText = view.findViewById(R.id.locateGuidanceText)
        locateDistanceText = view.findViewById(R.id.locateDistanceText)
        locateSignalProgress = view.findViewById(R.id.locateSignalProgress)
        availabilityStatusText = view.findViewById(R.id.availabilityStatusText)

        productDetailTitle.text = "Select a product"
        productDetailSubtitle.text = "Choose a product to review its EPCs."
        scanAvailabilityButton.text = "Start live locate"
        renderLocateUi(
            LocateUiState(
                selectedEpc = null,
                guidance = LOCATE_IDLE_GUIDANCE,
                distance = LOCATE_IDLE_DISTANCE,
                status = "Continuous locate feedback appears here after you choose an EPC.",
                signalStrength = 0,
            )
        )

        productAdapter = ProductAdapter(onProductPicked = { product ->
            selectProduct(product.id, highlightEpc = null)
        })
        epcAdapter = EpcAdapter(
            availabilityProvider = { epcAvailabilityByEpc },
            highlightEpcProvider = { highlightEpc },
            onEpcPicked = { epcNorm ->
                stopLocate()
                highlightEpc = epcNorm
                targetEpcNorm = epcNorm
                scanAvailabilityButton.isEnabled = true
                epcAvailabilityByEpc = emptyMap()
                epcAdapter.notifyDataSetChanged()
                renderLocateReadyState(epcNorm)
            },
        )

        proximityBeepController = ProximityBeepController()

        // Ensure layout managers exist even if XML attributes fail to apply.
        productRecycler.layoutManager = LinearLayoutManager(requireContext())
        epcRecycler.layoutManager = LinearLayoutManager(requireContext())

        productRecycler.adapter = productAdapter
        epcRecycler.adapter = epcAdapter

        catalogSearchButton.setOnClickListener {
            val q = catalogSearchInput.text?.toString()?.trim().orEmpty()
            applyCatalogFilter(q)
        }

        epcSearchButton.setOnClickListener {
            val epcQuery = epcSearchInput.text?.toString()?.trim().orEmpty()
            if (epcQuery.isBlank()) return@setOnClickListener
            val epcNorm = normalizeEpc(epcQuery)
            val matching = epcsAll.firstOrNull { normalizeEpc(it.epc) == epcNorm }
            if (matching == null) {
                // If user searches an EPC that isn't known, stop any current locating loop.
                stopLocate()
                targetEpcNorm = null
                scanAvailabilityButton.isEnabled = false
                scanAvailabilityButton.text = "Start live locate"
                epcAvailabilityByEpc = emptyMap()
                epcAdapter.notifyAvailabilityChanged()
                renderLocateUi(
                    LocateUiState(
                        selectedEpc = null,
                        guidance = "EPC not registered in local catalog.",
                        distance = LOCATE_IDLE_DISTANCE,
                        status = "Try another EPC or sync the catalog before locating.",
                        signalStrength = 0,
                    )
                )
                return@setOnClickListener
            }
            highlightEpc = epcNorm
            selectProduct(matching.productId, highlightEpc = epcNorm)
            // Align locate behavior: when the EPC search finds an EPC, start locating immediately.
            startLocatingSelectedEpc()
        }

        scanAvailabilityButton.setOnClickListener {
            if (scanActive) {
                stopLocate()
                scanAvailabilityButton.text = "Start live locate"
                scanAvailabilityButton.isEnabled = targetEpcNorm != null
                epcAvailabilityByEpc = emptyMap()
                epcAdapter.notifyAvailabilityChanged()
                renderLocateReadyState(targetEpcNorm, "Live locate stopped.")
                return@setOnClickListener
            }

            selectedProductId ?: return@setOnClickListener
            startLocatingSelectedEpc()
        }

        scanAvailabilityButton.isEnabled = false
        loadCatalogIfNeededAndRender()
    }

    private fun renderLocateUi(state: LocateUiState) {
        locateSelectedEpcText.text = state.selectedEpc?.let { "Selected EPC: $it" } ?: "No EPC selected"
        locateGuidanceText.text = state.guidance
        locateDistanceText.text = state.distance
        availabilityStatusText.text = state.status
        locateSignalProgress.setProgressCompat(state.signalStrength.coerceIn(0, 100), true)
    }

    private fun renderLocateReadyState(
        epcNorm: String?,
        status: String = "Continuous locate will start when you press the button.",
    ) {
        renderLocateUi(
            LocateUiState(
                selectedEpc = epcNorm,
                guidance = if (epcNorm.isNullOrBlank()) {
                    LOCATE_IDLE_GUIDANCE
                } else {
                    "Reader ready. Move slowly and keep the antenna pointed toward the tag."
                },
                distance = LOCATE_IDLE_DISTANCE,
                status = status,
                signalStrength = 0,
            )
        )
    }

    private fun pushRssiSample(samples: MutableList<Int>, rssi: Int) {
        if (samples.size >= LOCATE_HISTORY_SIZE) {
            samples.removeAt(0)
        }
        samples.add(rssi)
    }

    private fun smoothedRssi(samples: List<Int>, staleMs: Long): Double? {
        if (samples.isEmpty() || staleMs >= LOCATE_SIGNAL_LOST_MS) return null
        val base = samples.average()
        if (staleMs <= LOCATE_SIGNAL_FRESH_MS) return base
        val decaySteps = (staleMs - LOCATE_SIGNAL_FRESH_MS) / 180.0
        return (base - (decaySteps * 2.5)).coerceAtLeast(-85.0)
    }

    private fun signalStrengthFromRssi(rssi: Double?): Int {
        if (rssi == null) return 0
        return (((rssi + 80.0) / 35.0) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun signalStrengthFromPresence(staleMs: Long): Int {
        return when {
            staleMs <= LOCATE_SIGNAL_FRESH_MS -> 38
            staleMs <= LOCATE_SIGNAL_WARN_MS -> 28
            staleMs < LOCATE_SIGNAL_LOST_MS -> 18
            else -> 0
        }
    }

    /**
     * Best-effort distance estimate from RSSI (heuristic).
     *
     * RFID RSSI -> distance is environment-dependent, so treat this as a "closer/farther" guide.
     */
    private fun rssiToDistanceLabel(rssi: Double?): String? {
        if (rssi == null) return null
        val txAt1m = -50.0
        val n = 2.2
        val dMeters = Math.pow(10.0, (txAt1m - rssi) / (10.0 * n))
            .coerceIn(0.05, 20.0)

        return if (dMeters < 1.0) {
            val cm = dMeters * 100.0
            "Estimated distance: about ${cm.roundToInt()} cm"
        } else {
            "Estimated distance: about ${String.format(Locale.US, "%.1f", dMeters)} m"
        }
    }

    private fun buildLocateGuidance(signalStrength: Int, delta: Int, staleMs: Long): String {
        return when {
            staleMs > LOCATE_SIGNAL_WARN_MS -> "Signal fading. Sweep back toward the last stronger direction."
            signalStrength >= 88 -> "Very close. Slow down and make small movements."
            signalStrength >= 72 && delta >= 4 -> "Getting warmer. Keep moving in this direction."
            signalStrength >= 72 -> "Close. Reduce your sweep width."
            signalStrength >= 48 && delta >= 4 -> "Signal improving. Continue forward."
            signalStrength >= 48 && delta <= -4 -> "Signal dropped. Turn back toward the stronger read."
            signalStrength >= 30 -> "Weak signal. Sweep slowly left and right."
            else -> "Scanning continuously. Keep the reader pointed at likely tag locations."
        }
    }

    private fun buildLocateStatus(signalStrength: Int, rssi: Double?, staleMs: Long): String {
        if (rssi == null) {
            return "Live locate is active. Waiting for a clean target read."
        }
        val freshnessLabel = if (staleMs <= LOCATE_SIGNAL_FRESH_MS) "live" else "holding"
        return "Signal strength ${signalStrength}% · RSSI ${rssi.roundToInt()} dBm · $freshnessLabel"
    }

    private fun buildPresenceOnlyGuidance(staleMs: Long): String {
        return when {
            staleMs <= LOCATE_SIGNAL_FRESH_MS -> "Target EPC detected. Keep moving slowly to improve lock."
            staleMs <= LOCATE_SIGNAL_WARN_MS -> "Target found, but RSSI is unavailable. Sweep in tighter arcs."
            else -> "Weak target presence only. Return toward the last read direction."
        }
    }

    private fun buildPresenceOnlyStatus(staleMs: Long): String {
        val freshnessLabel = if (staleMs <= LOCATE_SIGNAL_FRESH_MS) "live" else "holding"
        return "Target EPC detected on Chainway reader, but RSSI was unavailable · $freshnessLabel"
    }

    private fun beepPatternForSignal(signalStrength: Int, staleMs: Long): ProximityBeepController.Pattern {
        val stalePenalty = if (staleMs > LOCATE_SIGNAL_WARN_MS) 120L else 0L
        return when {
            signalStrength >= 88 -> ProximityBeepController.Pattern(
                intervalMs = 140L + stalePenalty,
                toneDurationMs = 70,
                toneType = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
            )
            signalStrength >= 72 -> ProximityBeepController.Pattern(
                intervalMs = 210L + stalePenalty,
                toneDurationMs = 85,
                toneType = ToneGenerator.TONE_PROP_ACK,
            )
            signalStrength >= 48 -> ProximityBeepController.Pattern(
                intervalMs = 320L + stalePenalty,
                toneDurationMs = 95,
                toneType = ToneGenerator.TONE_PROP_BEEP,
            )
            else -> ProximityBeepController.Pattern(
                intervalMs = 460L + stalePenalty,
                toneDurationMs = 110,
                toneType = ToneGenerator.TONE_PROP_BEEP,
            )
        }
    }

    private fun beepPatternForPresenceOnly(staleMs: Long): ProximityBeepController.Pattern {
        val stalePenalty = if (staleMs > LOCATE_SIGNAL_WARN_MS) 140L else 0L
        return ProximityBeepController.Pattern(
            intervalMs = 520L + stalePenalty,
            toneDurationMs = 90,
            toneType = ToneGenerator.TONE_PROP_BEEP2,
        )
    }

    private fun stopLocate() {
        scanActive = false
        try {
            proximityBeepController.stop()
        } catch (_: Exception) {
        }
    }

    private fun loadCatalogIfNeededAndRender() {
        scanActive = false
        scanAvailabilityButton.isEnabled = false
        renderLocateUi(
            LocateUiState(
                selectedEpc = null,
                guidance = "Choose a product, then choose an EPC to start locating.",
                distance = LOCATE_IDLE_DISTANCE,
                status = "The live locator will guide you with signal strength and beep speed.",
                signalStrength = 0,
            )
        )

        bg.execute {
            if (productRecycler.context == null) return@execute
            val appCtx = requireContext().applicationContext
            val current = RfidSessionDbProvider.getInstance(appCtx)
            val initialProducts = current.productDao().getProducts(providerId)
            if (initialProducts.isEmpty() && FeatureFlags.TEST_MODE_ENABLED) {
                val adapter = BackendAdapterProvider.getAdapter(providerId)
                CatalogSyncUseCase(adapter).syncCatalog(providerConnectionId = providerId, db = current)
            }

            productsAll = current.productDao().getProducts(providerId).sortedBy { it.name.lowercase(Locale.US) }
            epcsAll = current.productEpcDao().getProductEpcs(providerId)
            epcsByProductId = epcsAll.groupBy { it.productId }

            filteredProducts = productsAll

            postUi {
                productAdapter.submitProducts(filteredProducts)
                if (filteredProducts.isNotEmpty()) {
                    val autoStart = pendingAutoStartLocate
                    val targetProductId = pendingTargetProductId
                    val targetEpc = pendingTargetEpc

                    // Clear so we don't accidentally re-trigger on later re-renders.
                    pendingAutoStartLocate = false
                    pendingTargetProductId = null
                    pendingTargetEpc = null

                    if (!targetProductId.isNullOrBlank()) {
                        selectProduct(targetProductId, highlightEpc = targetEpc)
                    } else {
                        selectProduct(filteredProducts.first().id, highlightEpc = null)
                    }

                    if (autoStart && !targetEpc.isNullOrBlank()) {
                        startLocatingSelectedEpc()
                    }
                }
            }
        }
    }

    private fun applyCatalogFilter(query: String) {
        val q = query.lowercase(Locale.US)
        filteredProducts = if (q.isBlank()) {
            productsAll
        } else {
            productsAll.filter { p ->
                val fields = listOfNotNull(p.name, p.sku, p.description, p.status)
                    .joinToString(" ")
                    .lowercase(Locale.US)
                fields.contains(q)
            }
        }

        productAdapter.submitProducts(filteredProducts)

        if (filteredProducts.isNotEmpty()) {
            selectProduct(filteredProducts.first().id, highlightEpc = null)
        } else {
            selectedProductId = null
            productDetailTitle.text = "No products match"
            productDetailSubtitle.text = "Try a broader search term or clear the filters."
            scanAvailabilityButton.isEnabled = false
            epcAdapter.submitEpcs(emptyList())
            renderLocateUi(
                LocateUiState(
                    selectedEpc = null,
                    guidance = "No EPC can be located until a matching product is visible.",
                    distance = LOCATE_IDLE_DISTANCE,
                    status = "Search results are empty.",
                    signalStrength = 0,
                )
            )
        }
    }

    private fun selectProduct(productId: String, highlightEpc: String?) {
        selectedProductId = productId
        this.highlightEpc = highlightEpc?.let { normalizeEpc(it) }

        val product = productsAll.firstOrNull { it.id == productId }
        productDetailTitle.text = product?.name ?: "Product"
        productDetailSubtitle.text = buildString {
            if (product?.sku != null) append("sku=${product.sku}")
            val status = product?.status
            if (!status.isNullOrBlank()) append(" · status=$status")
            val epcCount = epcsByProductId[productId]?.size ?: 0
            append(" · epcs=$epcCount")
        }

        val epcs = epcsByProductId[productId].orEmpty().sortedBy { normalizeEpc(it.epc) }
        epcAvailabilityByEpc = emptyMap()
        epcAdapter.submitEpcs(epcs)

        val single = if (epcs.size == 1) normalizeEpc(epcs.first().epc) else null
        val chosen = this.highlightEpc ?: single
        targetEpcNorm = chosen

        if (chosen.isNullOrBlank()) {
            scanAvailabilityButton.isEnabled = false
            renderLocateUi(
                LocateUiState(
                    selectedEpc = null,
                    guidance = "Select an EPC from the list to locate.",
                    distance = LOCATE_IDLE_DISTANCE,
                    status = "Live locate becomes available after you choose one EPC.",
                    signalStrength = 0,
                )
            )
        } else {
            scanAvailabilityButton.isEnabled = true
            renderLocateReadyState(chosen)
        }
    }

    private fun startLocatingSelectedEpc() {
        val target = targetEpcNorm ?: return
        if (scanActive) return

        scanActive = true
        scanAvailabilityButton.isEnabled = true
        scanAvailabilityButton.text = "Stop live locate"
        proximityBeepController.stop()
        epcAvailabilityByEpc = emptyMap()
        epcAdapter.notifyAvailabilityChanged()
        renderLocateUi(
            LocateUiState(
                selectedEpc = target,
                guidance = "Scanning continuously. Sweep slowly until the first clean read appears.",
                distance = LOCATE_IDLE_DISTANCE,
                status = "Live locate is active.",
                signalStrength = 0,
            )
        )

        bg.execute {
            val gatewayAndMaybeMock = createGatewayForAvailabilityScan()
            val gateway = gatewayAndMaybeMock.first

            val recentRssi = ArrayList<Int>(LOCATE_HISTORY_SIZE)
            var lastSeenAtMs = 0L
            var lastPublishedAtMs = 0L
            var lastSignalStrength = 0
            var beepRunning = false
            var locateFailed = false

            try {
                val appCtx = requireContext().applicationContext
                if (!gateway.init(appCtx)) {
                    locateFailed = true
                    postUi {
                        renderLocateUi(
                            LocateUiState(
                                selectedEpc = target,
                                guidance = "Reader failed to initialize.",
                                distance = LOCATE_IDLE_DISTANCE,
                                status = "Check the scanner connection, then try again.",
                                signalStrength = 0,
                            )
                        )
                    }
                    return@execute
                }
                if (!gateway.startInventory()) {
                    locateFailed = true
                    postUi {
                        renderLocateUi(
                            LocateUiState(
                                selectedEpc = target,
                                guidance = "Live locate could not start scanning.",
                                distance = LOCATE_IDLE_DISTANCE,
                                status = "Make sure the reader is ready, then try again.",
                                signalStrength = 0,
                            )
                        )
                    }
                    return@execute
                }

                while (scanActive) {
                    val events = gateway.readBufferedTagEvents()
                    val nowMs = SystemClock.uptimeMillis()
                    var matchedThisLoop = false
                    var strongestRssi: Int? = null

                    for (ev in events) {
                        val epcNorm = normalizeEpc(ev.epc)
                        if (epcNorm == target) {
                            lastSeenAtMs = nowMs
                            matchedThisLoop = true
                            val currentRssi = ev.rssi
                            val previousStrongest = strongestRssi
                            if (currentRssi != null && (previousStrongest == null || currentRssi > previousStrongest)) {
                                strongestRssi = currentRssi
                            }
                        }
                    }

                    strongestRssi?.let { pushRssiSample(recentRssi, it) }

                    val staleMs = if (lastSeenAtMs == 0L) Long.MAX_VALUE else nowMs - lastSeenAtMs
                    val smoothedRssi = smoothedRssi(recentRssi, staleMs)
                    val hasTargetPresence = lastSeenAtMs != 0L && staleMs < LOCATE_SIGNAL_LOST_MS
                    val signalStrength = if (smoothedRssi != null) {
                        signalStrengthFromRssi(smoothedRssi)
                    } else if (hasTargetPresence) {
                        signalStrengthFromPresence(staleMs)
                    } else {
                        0
                    }
                    val delta = signalStrength - lastSignalStrength
                    val shouldPublish = matchedThisLoop || (nowMs - lastPublishedAtMs) >= LOCATE_UI_UPDATE_MS

                    if (hasTargetPresence) {
                        val beepPattern = if (smoothedRssi != null) {
                            beepPatternForSignal(signalStrength, staleMs)
                        } else {
                            beepPatternForPresenceOnly(staleMs)
                        }
                        proximityBeepController.updatePattern(beepPattern)
                        if (!beepRunning) {
                            proximityBeepController.start()
                            beepRunning = true
                        }

                        if (shouldPublish) {
                            val distance = rssiToDistanceLabel(smoothedRssi)
                                ?: "Estimated distance: RSSI unavailable on this read"
                            val guidance = if (smoothedRssi != null) {
                                buildLocateGuidance(signalStrength, delta, staleMs)
                            } else {
                                buildPresenceOnlyGuidance(staleMs)
                            }
                            val status = if (smoothedRssi != null) {
                                buildLocateStatus(signalStrength, smoothedRssi, staleMs)
                            } else {
                                buildPresenceOnlyStatus(staleMs)
                            }
                            postUi {
                                epcAvailabilityByEpc = mapOf(target to true)
                                epcAdapter.notifyAvailabilityChanged()
                                renderLocateUi(
                                    LocateUiState(
                                        selectedEpc = target,
                                        guidance = guidance,
                                        distance = distance,
                                        status = status,
                                        signalStrength = signalStrength,
                                    )
                                )
                            }
                            lastPublishedAtMs = nowMs
                        }
                    } else {
                        if (beepRunning) {
                            proximityBeepController.stop()
                            beepRunning = false
                        }

                        if (shouldPublish) {
                            val guidance = if (lastSeenAtMs == 0L) {
                                "Scanning continuously. Sweep slowly until the target appears."
                            } else {
                                "Signal lost. Sweep back toward the last strong direction."
                            }
                            val status = if (lastSeenAtMs == 0L) {
                                "Waiting for the first read from $target."
                            } else {
                                "No clean target read for ${staleMs} ms."
                            }
                            postUi {
                                epcAvailabilityByEpc =
                                    if (lastSeenAtMs == 0L) emptyMap() else mapOf(target to false)
                                epcAdapter.notifyAvailabilityChanged()
                                renderLocateUi(
                                    LocateUiState(
                                        selectedEpc = target,
                                        guidance = guidance,
                                        distance = LOCATE_IDLE_DISTANCE,
                                        status = status,
                                        signalStrength = 0,
                                    )
                                )
                            }
                            lastPublishedAtMs = nowMs
                        }
                    }

                    lastSignalStrength = signalStrength

                    try {
                        Thread.sleep(if (events.isEmpty()) 60L else 20L)
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (_: Exception) {
                locateFailed = true
                postUi {
                    scanActive = false
                    scanAvailabilityButton.isEnabled = targetEpcNorm != null
                    scanAvailabilityButton.text = "Start live locate"
                    renderLocateUi(
                        LocateUiState(
                            selectedEpc = target,
                            guidance = "Locate failed.",
                            distance = LOCATE_IDLE_DISTANCE,
                            status = "Check the reader and try again.",
                            signalStrength = 0,
                        )
                    )
                }
            } finally {
                try {
                    gateway.stopInventory()
                } catch (_: Exception) {
                }
                try {
                    gateway.free()
                } catch (_: Exception) {
                }
                try {
                    proximityBeepController.stop()
                } catch (_: Exception) {
                }
                postUi {
                    scanActive = false
                    scanAvailabilityButton.isEnabled = targetEpcNorm != null
                    scanAvailabilityButton.text = "Start live locate"
                    if (!locateFailed && !targetEpcNorm.isNullOrBlank()) {
                        renderLocateReadyState(
                            epcNorm = targetEpcNorm,
                            status = "Live locate is idle. Press start when you want to scan again.",
                        )
                    }
                }
            }
        }
    }

    private fun createGatewayForAvailabilityScan(): Pair<UhfReaderGateway, MockUhfReaderGateway?> {
        return if (FeatureFlags.TEST_MODE_ENABLED && FeatureFlags.UHF_TEST_MODE_ENABLED) {
            MockUhfReaderGateway().also { it.fixturePathOverride = "test-fixtures/uhf/mock-availability-sequence.json" } to null
        } else {
            val driverId = UhfConfigPrefs.load(requireContext()).driverId
            DriverUhfReaderGatewayAdapter(RfidDriverFactory.createDriver(driverId)) to null
        }
    }

    private fun scanReadEpcs(
        gateway: UhfReaderGateway,
        ctx: android.content.Context,
        durationMs: Long,
    ): Set<String> {
        val initOk = gateway.init(ctx)
        if (!initOk) return emptySet()
        val started = gateway.startInventory()
        if (!started) {
            try { gateway.free() } catch (_: Exception) {}
            return emptySet()
        }

        val start = SystemClock.uptimeMillis()
        val detected = HashSet<String>()
        while (SystemClock.uptimeMillis() - start < durationMs) {
            val events = gateway.readBufferedTagEvents()
            if (events.isNotEmpty()) {
                for (e in events) {
                    val epc = normalizeEpc(e.epc)
                    if (epc.isNotBlank()) detected.add(epc)
                }
            }
            try {
                Thread.sleep(if (events.isEmpty()) 40L else 10L)
            } catch (_: Exception) {
                break
            }
        }

        try { gateway.stopInventory() } catch (_: Exception) {}
        try { gateway.free() } catch (_: Exception) {}
        return detected
    }

    private fun normalizeEpc(epc: String): String =
        epc.trim().uppercase(Locale.US)

    override fun onDestroyView() {
        stopLocate()
        bg.shutdownNow()
        try {
            proximityBeepController.release()
        } catch (_: Exception) {
        }
        super.onDestroyView()
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }

    override fun handleBackNavigation() {
        stopLocate()
        findNavController().navigateUp()
    }

    private class ProductAdapter(
        val onProductPicked: (ProductEntity) -> Unit,
    ) : RecyclerView.Adapter<ProductAdapter.ProductViewHolder>() {

        private var items: List<ProductEntity> = emptyList()

        fun submitProducts(products: List<ProductEntity>) {
            items = products
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.view_product_catalog_row, parent, false)
            return ProductViewHolder(v)
        }

        override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
            holder.bind(items[position], onProductPicked)
        }

        override fun getItemCount(): Int = items.size

        class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(product: ProductEntity, onPick: (ProductEntity) -> Unit) {
                val card: MaterialCardView = itemView.findViewById(R.id.productRowRoot)
                val name: TextView = itemView.findViewById(R.id.productRowName)
                val subtitle: TextView = itemView.findViewById(R.id.productRowSubtitle)
                name.text = product.name
                subtitle.text = buildString {
                    append("sku=${product.sku ?: "n/a"}")
                    append(" · status=${product.status}")
                }
                card.setOnClickListener { onPick(product) }
            }
        }
    }

    private class EpcAdapter(
        private val availabilityProvider: () -> Map<String, Boolean>,
        private val highlightEpcProvider: () -> String?,
        private val onEpcPicked: (String) -> Unit,
    ) : RecyclerView.Adapter<EpcAdapter.EpcViewHolder>() {

        private var epcs: List<ProductEpcEntity> = emptyList()

        fun submitEpcs(epcs: List<ProductEpcEntity>) {
            this.epcs = epcs
            notifyDataSetChanged()
        }

        fun notifyAvailabilityChanged() {
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpcViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.view_product_epc_row, parent, false)
            return EpcViewHolder(v)
        }

        override fun onBindViewHolder(holder: EpcViewHolder, position: Int) {
            holder.bind(
                epcs[position],
                availabilityProvider(),
                highlightEpcProvider(),
                onEpcPicked,
            )
        }

        override fun getItemCount(): Int = epcs.size

        class EpcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(
                epcEnt: ProductEpcEntity,
                availabilityByEpc: Map<String, Boolean>,
                highlightEpc: String?,
                onEpcPicked: (String) -> Unit,
            ) {
                val card: MaterialCardView = itemView.findViewById(R.id.epcRowRoot)
                val dot: View = itemView.findViewById(R.id.epcAvailabilityDot)
                val value: TextView = itemView.findViewById(R.id.epcValueText)
                val state: TextView = itemView.findViewById(R.id.epcStateText)

                val epcNorm = epcEnt.epc.trim().uppercase(Locale.US)
                val available = availabilityByEpc[epcNorm] == true

                val ctx = itemView.context
                val availableColor = ContextCompat.getColor(ctx, R.color.rfid_online)
                val unavailableColor = ContextCompat.getColor(ctx, R.color.rfid_card_stroke)
                val highlightBg = ContextCompat.getColor(ctx, R.color.rfid_chip_background)

                dot.setBackgroundColor(if (available) availableColor else unavailableColor)
                value.text = epcNorm
                state.text = "state=${epcEnt.state}"

                val isHighlighted = !highlightEpc.isNullOrBlank() && highlightEpc.trim().uppercase(Locale.US) == epcNorm
                card.setCardBackgroundColor(if (isHighlighted) highlightBg else ContextCompat.getColor(ctx, R.color.rfid_card_surface))

                card.setOnClickListener { onEpcPicked(epcNorm) }
            }
        }
    }
}

