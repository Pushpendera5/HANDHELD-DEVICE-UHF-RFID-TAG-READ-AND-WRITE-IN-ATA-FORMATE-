
package com.rfidsoftwares.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.rfidsoftwares.R
import com.rfidsoftwares.data.local.MissingItemsLocalStore
import java.util.Locale

class EpcStatusDialogFragment : DialogFragment() {

    private data class EpcRow(
        val epc: String,
        val statusLabel: String,
        val canAddToMissing: Boolean,
    )

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var closeButton: MaterialButton

    private var providerConnectionIdArg: String = ""
    private var sessionIdArg: String = ""
    private var productIdArg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make sure tapping outside closes it.
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.dialog_epc_status, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.epcDialogTitle)
        subtitleView = view.findViewById(R.id.epcDialogSubtitle)
        emptyText = view.findViewById(R.id.epcDialogEmptyText)
        recycler = view.findViewById(R.id.epcDialogRecyclerView)
        closeButton = view.findViewById(R.id.epcDialogCloseButton)

        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val subtitle = requireArguments().getString(ARG_SUBTITLE).orEmpty()
        val epcs = requireArguments().getStringArrayList(ARG_EPCS) ?: arrayListOf()
        val foundEpcs = (requireArguments().getStringArrayList(ARG_FOUND_EPCS) ?: arrayListOf())
        val scanAvailable = requireArguments().getBoolean(ARG_SCAN_AVAILABLE, false)
        providerConnectionIdArg = requireArguments().getString(ARG_PROVIDER_CONNECTION_ID).orEmpty()
        sessionIdArg = requireArguments().getString(ARG_SESSION_ID).orEmpty()
        productIdArg = requireArguments().getString(ARG_PRODUCT_ID).orEmpty()

        titleView.text = title
        subtitleView.text = subtitle

        val foundSet = foundEpcs
            .map { it.trim().uppercase(Locale.US) }
            .toHashSet()

        val rows = epcs.map { expected ->
            val normalized = expected.trim().uppercase(Locale.US)
            val status = if (!scanAvailable) {
                "NOT SCANNED"
            } else if (foundSet.contains(normalized)) {
                "FOUND"
            } else {
                "MISSING"
            }
            val canAddToMissing = scanAvailable && status == "MISSING"
            EpcRow(epc = expected, statusLabel = status, canAddToMissing = canAddToMissing)
        }

        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = EpcStatusAdapter(rows)

        closeButton.setOnClickListener {
            dismiss()
        }
    }

    private inner class EpcStatusAdapter(
        private val rows: List<EpcRow>,
    ) : RecyclerView.Adapter<EpcStatusAdapter.EpcViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpcViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_epc_status_row, parent, false)
            return EpcViewHolder(view)
        }

        override fun onBindViewHolder(holder: EpcViewHolder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        inner class EpcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val epcText: TextView = itemView.findViewById(R.id.epcRowEpcText)
            private val statusText: TextView = itemView.findViewById(R.id.epcRowStatusText)
            private val copyButton: ImageButton = itemView.findViewById(R.id.epcRowCopyButton)

            fun bind(row: EpcRow) {
                epcText.text = row.epc
                statusText.text = row.statusLabel
                copyButton.setOnClickListener {
                    if (row.canAddToMissing) {
                        val ctx = itemView.context
                        MissingItemsLocalStore.getInstance(ctx).addMissingEpcAsync(
                            providerConnectionId = providerConnectionIdArg,
                            sessionId = sessionIdArg,
                            productId = productIdArg,
                            epc = row.epc,
                        )
                        Toast.makeText(ctx, "Added to Missing Items", Toast.LENGTH_SHORT).show()
                    } else {
                        copyToClipboard(itemView.context, row.epc)
                    }
                }
            }
        }
    }

    private fun copyToClipboard(ctx: Context, value: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("EPC", value))
    }

    companion object {
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"
        private const val ARG_EPCS = "arg_epcs"
        private const val ARG_FOUND_EPCS = "arg_found_epcs"
        private const val ARG_SCAN_AVAILABLE = "arg_scan_available"
        private const val ARG_PROVIDER_CONNECTION_ID = "arg_provider_connection_id"
        private const val ARG_SESSION_ID = "arg_session_id"
        private const val ARG_PRODUCT_ID = "arg_product_id"

        fun newInstance(
            title: String,
            subtitle: String,
            epcs: ArrayList<String>,
            foundEpcs: ArrayList<String>,
            scanAvailable: Boolean,
            providerConnectionId: String,
            sessionId: String,
            productId: String,
        ): EpcStatusDialogFragment {
            return EpcStatusDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle)
                    putStringArrayList(ARG_EPCS, epcs)
                    putStringArrayList(ARG_FOUND_EPCS, foundEpcs)
                    putBoolean(ARG_SCAN_AVAILABLE, scanAvailable)
                    putString(ARG_PROVIDER_CONNECTION_ID, providerConnectionId)
                    putString(ARG_SESSION_ID, sessionId)
                    putString(ARG_PRODUCT_ID, productId)
                }
            }
        }
    }
}

