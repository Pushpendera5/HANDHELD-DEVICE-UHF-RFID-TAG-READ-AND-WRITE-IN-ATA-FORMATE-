
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.integration.BackendAdapterProvider
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.CartFromEpcsPayload
import com.rfidsoftwares.integration.models.CheckoutBillPayload
import com.rfidsoftwares.testing.state.TestModeStateStore
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.ui.base.BaseScreenFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID
import java.util.concurrent.Executors

class PlaceholderFragment : BaseScreenFragment() {

    override fun screenTitle(): String = titleFor(screenKey)

    override fun screenSubtitle(): String? = "Limited workflow preview"

    private val screenKey: String
        get() = requireArguments().getString(ARG_SCREEN_KEY) ?: "unknown"

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return when (screenKey) {
            "find_product" -> buildFindProductBody()
            "register_epc" -> buildRegisterEpcBody()
            "checkout_billing" -> buildCheckoutBillingBody()
            else -> buildComingSoonPanel(inflater, container)
        }
    }

    private fun buildComingSoonPanel(inflater: LayoutInflater, container: FrameLayout): View {
        val panel = inflater.inflate(R.layout.view_state_panel, container, false)
        val title = panel.findViewById<TextView>(R.id.statePanelTitle)
        val msg = panel.findViewById<TextView>(R.id.statePanelMessage)
        val actionBtn = panel.findViewById<Button>(R.id.statePanelActionButton)

        title.text = "Not available yet"
        msg.text = "\"${titleFor(screenKey)}\" is planned, but this workflow is still being completed."

        actionBtn.text = "Go back"
        actionBtn.visibility = View.VISIBLE
        actionBtn.setOnClickListener {
            findNavController().navigateUp()
        }
        return panel
    }

    private fun providerId(): String =
        ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId

    private var bg = Executors.newSingleThreadExecutor()

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }

    override fun onDestroyView() {
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun buildFindProductBody(): View {
        ensureBg()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val scroll = ScrollView(requireContext()).apply {
            isFillViewport = true
        }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val title = TextView(requireContext()).apply {
            text = "Search cached products"
            textSize = 14f
        }
        root.addView(title)

        val query = TextInputEditText(requireContext()).apply {
            hint = "Query (e.g., mobile, charger, watch)"
        }
        root.addView(query)

        val searchBtn = MaterialButton(requireContext()).apply {
            text = "Search"
        }
        root.addView(searchBtn)

        val results = TextView(requireContext()).apply {
            text = ""
            textSize = 13f
        }
        root.addView(results)

        searchBtn.setOnClickListener {
            val q = query.text?.toString()?.trim().orEmpty()
            if (q.isBlank()) {
                results.text = "Enter a search query first."
                return@setOnClickListener
            }
            searchBtn.isEnabled = false
            results.text = "Searching \"$q\"..."

            bg.execute {
                try {
                    val db = RfidSessionDbProvider.getInstance(requireContext())
                    val needle = q.lowercase()
                    val prods = db.productDao().getProducts(providerId())
                    val matches = prods.filter { p ->
                        val fields = listOfNotNull(
                            p.id,
                            p.sku,
                            p.name,
                            p.description,
                            p.status,
                        ).joinToString(" ").lowercase()
                        fields.contains(needle)
                    }
                    val lines = if (matches.isEmpty()) {
                        listOf("No matches.")
                    } else {
                        matches.take(12).map { p ->
                            "- ${p.id} · ${p.sku ?: "n/a"} · ${p.name}"
                        }
                    }
                    root.post {
                        searchBtn.isEnabled = true
                        results.text = lines.joinToString("\n")
                    }
                } catch (e: Exception) {
                    root.post {
                        searchBtn.isEnabled = true
                        results.text = "Search failed: ${e.message.orEmpty()}"
                    }
                }
            }
        }

        return scroll
    }

    private fun buildRegisterEpcBody(): View {
        ensureBg()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val epcInput = TextInputEditText(requireContext()).apply {
            hint = "EPC"
        }
        root.addView(epcInput)

        val productIdInput = TextInputEditText(requireContext()).apply {
            hint = "Product ID (optional)"
        }
        root.addView(productIdInput)

        val quickFill = MaterialButton(requireContext()).apply {
            text = "Fill example values"
        }
        root.addView(quickFill)

        val registerBtn = MaterialButton(requireContext()).apply {
            text = "Register EPC"
        }
        root.addView(registerBtn)

        val results = TextView(requireContext()).apply {
            text = ""
            textSize = 13f
        }
        root.addView(results)

        quickFill.setOnClickListener {
            epcInput.setText("E280699500005000FEF3B548")
            productIdInput.setText("p001")
        }

        registerBtn.setOnClickListener {
            val epc = epcInput.text?.toString()?.trim().orEmpty()
            if (epc.isBlank()) {
                results.text = "Enter EPC first."
                return@setOnClickListener
            }
            val productId = productIdInput.text?.toString()?.trim().orEmpty().ifBlank { null }

            registerBtn.isEnabled = false
            results.text = "Registering EPC..."

            bg.execute {
                val adapter = BackendAdapterProvider.getAdapter(providerId())
                try {
                    val mapped = adapter.registerEpc(providerId(), epc, productId)
                    val registered = TestModeStateStore.getRegisteredEpcs(providerId())
                    root.post {
                        registerBtn.isEnabled = true
                        results.text = buildString {
                            append("Registered: ${mapped.epc}\n")
                            append("Product: ${mapped.productId} · State: ${mapped.state}\n\n")
                            append("Registered EPCs in this session: ${registered.size}")
                        }
                    }
                } catch (e: AdapterError) {
                    root.post {
                        registerBtn.isEnabled = true
                        results.text = "Register failed: ${e.message.orEmpty()}"
                    }
                } catch (e: Exception) {
                    root.post {
                        registerBtn.isEnabled = true
                        results.text = "Register failed: ${e.message.orEmpty()}"
                    }
                }
            }
        }

        return root
    }

    private fun buildCheckoutBillingBody(): View {
        ensureBg()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val epcsInput = TextInputEditText(requireContext()).apply {
            hint = "Comma-separated EPCs"
        }
        root.addView(epcsInput)

        val createCartBtn = MaterialButton(requireContext()).apply {
            text = "Create cart from EPCs"
        }
        root.addView(createCartBtn)

        val cartIdView = TextView(requireContext()).apply {
            text = "Cart ID: (not created yet)"
            textSize = 13f
        }
        root.addView(cartIdView)

        val billBtn = MaterialButton(requireContext()).apply {
            text = "Generate bill from cart"
        }
        root.addView(billBtn)

        val billView = TextView(requireContext()).apply {
            text = ""
            textSize = 13f
        }
        root.addView(billView)

        var lastCartId: String? = null

        createCartBtn.setOnClickListener {
            val epcs = epcsInput.text?.toString()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            if (epcs.isEmpty()) {
                billView.text = "Enter at least one EPC."
                return@setOnClickListener
            }

            createCartBtn.isEnabled = false
            cartIdView.text = "Cart ID: creating..."
            billView.text = ""

            bg.execute {
                val adapter = BackendAdapterProvider.getAdapter(providerId())
                try {
                    val payload = CartFromEpcsPayload(
                        cartClientRequestId = UUID.randomUUID().toString(),
                        providerConnectionId = providerId(),
                        epcs = epcs,
                    )
                    val cart = adapter.createCartFromEpcs(providerId(), payload)
                    lastCartId = cart.id
                    root.post {
                        createCartBtn.isEnabled = true
                        cartIdView.text = "Cart ID: ${cart.id}"
                        billView.text = "Cart created. Ready for billing."
                    }
                } catch (e: AdapterError) {
                    root.post {
                        createCartBtn.isEnabled = true
                        cartIdView.text = "Cart ID: (not created)"
                        billView.text = "Create cart failed: ${e.message.orEmpty()}"
                    }
                } catch (e: Exception) {
                    root.post {
                        createCartBtn.isEnabled = true
                        cartIdView.text = "Cart ID: (not created)"
                        billView.text = "Create cart failed: ${e.message.orEmpty()}"
                    }
                }
            }
        }

        billBtn.setOnClickListener {
            val cartId = lastCartId
            if (cartId.isNullOrBlank()) {
                billView.text = "Create cart first."
                return@setOnClickListener
            }

            billBtn.isEnabled = false
            billView.text = "Generating bill..."

            bg.execute {
                val adapter = BackendAdapterProvider.getAdapter(providerId())
                try {
                    val payload = CheckoutBillPayload(
                        cartId = cartId,
                        providerConnectionId = providerId(),
                        checkoutClientRequestId = UUID.randomUUID().toString(),
                        paymentType = "card",
                    )
                    val idempotencyKey = UUID.randomUUID().toString()
                    val bill = adapter.generateCheckoutBill(providerId(), payload, idempotencyKey)
                    root.post {
                        billBtn.isEnabled = true
                        billView.text = "Bill: ${bill.billId} · Status: ${bill.status} · Amount: ${bill.amount?.amount ?: "n/a"}"
                    }
                } catch (e: AdapterError) {
                    root.post {
                        billBtn.isEnabled = true
                        billView.text = "Billing failed: ${e.message.orEmpty()}"
                    }
                } catch (e: Exception) {
                    root.post {
                        billBtn.isEnabled = true
                        billView.text = "Billing failed: ${e.message.orEmpty()}"
                    }
                }
            }
        }

        return root
    }

    private fun titleFor(key: String): String {
        return when (key) {
            "inventory_sync" -> "Inventory Sync"
            "find_product" -> "Find Product"
            "register_epc" -> "Register EPC"
            "checkout_billing" -> "Checkout & Billing"
            "anti_theft" -> "Anti-Theft"
            "diagnostics" -> "Diagnostics"
            "role_management" -> "Role Management"
            else -> "Feature"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // BaseScreenFragment calls screenTitle()/screenSubtitle() and createBody().
    }

    companion object {
        const val ARG_SCREEN_KEY = "screenKey"
    }
}

