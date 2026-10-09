
package com.rfidsoftwares.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.common.auth.ActiveProviderStore
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.data.local.MissingItemsLocalStore
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.ui.base.BaseScreenFragment

class MissingItemsFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Missing Items"

    override fun screenSubtitle(): String? = "EPC tags that were expected but not found"

    data class MissingProductUi(
        val productId: String,
        val productName: String,
        val sku: String?,
        val missingEpcs: List<String>,
    )

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var sectionTitle: TextView

    private val adapter = MissingProductAdapter(
        onLocateClicked = { item -> onProductClicked(item) },
        onRemoveClicked = { item -> onRemoveProductClicked(item) },
    )

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_missing_items, container, false)
    }

    override fun allowOfflinePanel(): Boolean = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler = view.findViewById(R.id.missingItemsRecyclerView)
        emptyState = view.findViewById(R.id.missingItemsEmptyStateText)
        sectionTitle = view.findViewById(R.id.missingItemsSectionTitle)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        loadMissingItems()
    }

    override fun onResume() {
        super.onResume()
        // Missing list can change when the dialog adds items.
        loadMissingItems()
    }

    private fun loadMissingItems() {
        val providerConnectionId =
            ActiveProviderStore.activeProviderId ?: AppConfig.ProviderRegistry.providers.first().providerId
        val db = RfidSessionDbProvider.getInstance(requireContext())

        val products = db.productDao().getProducts(providerConnectionId)
        val productById = products.associateBy { it.id }

        val missing = MissingItemsLocalStore.getInstance(requireContext())
            .loadMissingProductsForProvider(providerConnectionId)

        val items = missing.map { mp ->
            val p: ProductEntity? = productById[mp.productId]
            val missingEpcs = mp.epcs.map { it.epc }
            MissingProductUi(
                productId = mp.productId,
                productName = p?.name ?: mp.productId,
                sku = p?.sku,
                missingEpcs = missingEpcs,
            )
        }.filter { it.missingEpcs.isNotEmpty() }

        adapter.submit(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        sectionTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onProductClicked(item: MissingProductUi) {
        val epcs = item.missingEpcs
        if (epcs.isEmpty()) return

        val epcArray = epcs.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.productName)
            .setMessage("Select an EPC to locate.")
            .setItems(epcArray) { _, which ->
                val chosen = epcArray[which]
                showFindEpcConfirm(
                    productId = item.productId,
                    targetEpc = chosen,
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFindEpcConfirm(
        productId: String,
        targetEpc: String,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Find EPC")
            .setMessage("Start live locate for the selected missing EPC?")
            .setPositiveButton("Find") { _, _ ->
                val bundle = bundleOf(
                    "targetProductId" to productId,
                    "targetEpc" to targetEpc,
                    "autoStartLocate" to true,
                )
                findNavController().navigate(R.id.findProductFragment, bundle)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentProviderConnectionId(): String =
        ActiveProviderStore.activeProviderId
            ?: AppConfig.ProviderRegistry.providers.first().providerId

    private fun onRemoveProductClicked(item: MissingProductUi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove missing items")
            .setMessage("Clear all missing EPCs for ${item.productName}?")
            .setPositiveButton("Remove") { _, _ ->
                val providerConnectionId = currentProviderConnectionId()
                val store = MissingItemsLocalStore.getInstance(requireContext())
                try {
                    store.removeMissingProductBlocking(
                        providerConnectionId = providerConnectionId,
                        productId = item.productId,
                    )
                } catch (_: Exception) {
                    // Best-effort only.
                }
                loadMissingItems()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class MissingProductAdapter(
        private val onLocateClicked: (MissingProductUi) -> Unit,
        private val onRemoveClicked: (MissingProductUi) -> Unit,
    ) : RecyclerView.Adapter<MissingProductAdapter.MissingProductViewHolder>() {

        private var items: List<MissingProductUi> = emptyList()

        fun submit(newItems: List<MissingProductUi>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MissingProductViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.view_missing_product_row, parent, false)
            return MissingProductViewHolder(view)
        }

        override fun onBindViewHolder(holder: MissingProductViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class MissingProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.missingProductRowRoot)
            private val name: TextView = itemView.findViewById(R.id.missingProductRowName)
            private val sku: TextView = itemView.findViewById(R.id.missingProductRowSku)
            private val missingCount: TextView = itemView.findViewById(R.id.missingProductRowMissingCount)
            private val preview: TextView = itemView.findViewById(R.id.missingProductRowPreview)
            private val removeButton: android.widget.ImageButton =
                itemView.findViewById(R.id.missingProductRowRemoveButton)

            fun bind(item: MissingProductUi) {
                name.text = item.productName
                sku.text = item.sku?.let { "sku=$it" } ?: "sku=n/a"
                missingCount.text = "${item.missingEpcs.size} missing tag(s)"

                val top = item.missingEpcs.take(3)
                val remaining = item.missingEpcs.size - top.size
                preview.text = if (remaining > 0) {
                    "${top.joinToString(", ")} +$remaining more"
                } else {
                    top.joinToString(", ")
                }

                card.setOnClickListener { onLocateClicked(item) }
                removeButton.setOnClickListener { onRemoveClicked(item) }
            }
        }
    }
}

