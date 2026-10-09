
package com.rfidsoftwares.ui.screens

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rfidsoftwares.R
import com.rfidsoftwares.data.local.AuditRetentionPolicy
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.integration.workers.SyncOutboxWorker
import com.rfidsoftwares.issues.IssueActions
import com.rfidsoftwares.support.LogExportUseCase
import com.rfidsoftwares.ui.base.BaseScreenFragment
import androidx.navigation.fragment.findNavController
import java.util.concurrent.Executors

class IssueCenterFragment : BaseScreenFragment() {

    override fun screenTitle(): String = "Issue Center"
    override fun screenSubtitle(): String? = "Review issues, dismiss noise, and export support logs"

    private var bg = Executors.newSingleThreadExecutor()

    override fun createBody(inflater: LayoutInflater, container: FrameLayout): View {
        return inflater.inflate(R.layout.body_issue_center, container, false)
    }

    override fun onResume() {
        super.onResume()
        ensureBg()
        refreshList()
    }

    private fun refreshList() {
        val root = view?.findViewById<LinearLayout>(R.id.issueListContainer) ?: return
        val empty = view?.findViewById<TextView>(R.id.issueEmptyText) ?: return
        bg.execute {
            val db = RfidSessionDbProvider.getInstance(requireContext())
            AuditRetentionPolicy.enforce(db)
            val issues = db.issueDao().listActive()
            requireActivity().runOnUiThread {
                root.removeAllViews()
                if (issues.isEmpty()) {
                    empty.visibility = View.VISIBLE
                } else {
                    empty.visibility = View.GONE
                    for (issue in issues) {
                        root.addView(buildIssueCard(issue))
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ensureBg()
        view.findViewById<MaterialButton>(R.id.issueRefreshButton).setOnClickListener { refreshList() }
        view.findViewById<MaterialButton>(R.id.issueExportButton).setOnClickListener { shareExport() }
    }

    override fun onDestroyView() {
        bg.shutdownNow()
        super.onDestroyView()
    }

    private fun buildIssueCard(issue: IssueEntity): View {
        val card = MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            radius = 12f * resources.displayMetrics.density
            cardElevation = 2f * resources.displayMetrics.density
            useCompatPadding = true
        }
        val inner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(requireContext()).apply {
            text = "${issue.severity.replaceFirstChar { it.uppercase() }} · ${issue.message}"
            textSize = 14f
        }
        val corr = TextView(requireContext()).apply {
            text = if (issue.correlationId.isNullOrBlank()) {
                "Reference: —"
            } else {
                "Reference: ${issue.correlationId}"
            }
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        inner.addView(title)
        inner.addView(
            TextView(requireContext()).apply {
                text = "Category: ${issue.category}"
                textSize = 12f
                setPadding(0, 6, 0, 0)
            },
        )
        inner.addView(corr)
        issue.detail?.takeIf { it.isNotBlank() }?.let { d ->
            inner.addView(
                TextView(requireContext()).apply {
                    text = "Detail: $d"
                    textSize = 12f
                    setPadding(0, 6, 0, 0)
                },
            )
        }
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        val dismissBtn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Dismiss issue"
            setOnClickListener {
                bg.execute {
                    val db = RfidSessionDbProvider.getInstance(requireContext())
                    db.issueDao().dismiss(issue.issueId)
                    AuditRetentionPolicy.enforce(db)
                    requireActivity().runOnUiThread { refreshList() }
                }
            }
        }
        actions.addView(dismissBtn)
        when (issue.suggestedAction) {
            IssueActions.RETRY_SYNC -> {
                val b = MaterialButton(requireContext()).apply {
                    text = "Retry sync"
                    setOnClickListener {
                        SyncOutboxWorker.enqueueImmediate(requireContext())
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Sync retry queued. A network connection is still required.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                actions.addView(b)
            }
            IssueActions.OPEN_DIAGNOSTICS -> {
                val b = MaterialButton(requireContext()).apply {
                    text = "Diagnostics"
                    setOnClickListener {
                        findNavController().navigate(R.id.diagnosticsFragment)
                    }
                }
                actions.addView(b)
            }
            IssueActions.CONFLICT_GUIDANCE -> {
                val b = MaterialButton(requireContext()).apply {
                    text = "Conflict help"
                    setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Conflict")
                            .setMessage(
                                "Do not auto-push conflicting inventory jobs. Resolve the conflict on the server " +
                                    "or discard the job from the reconciliation flow, then retry sync.",
                            )
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
                actions.addView(b)
            }
        }
        inner.addView(actions)
        card.addView(inner)
        return card
    }

    private fun shareExport() {
        bg.execute {
            val db = RfidSessionDbProvider.getInstance(requireContext())
            AuditRetentionPolicy.enforce(db)
            val file = LogExportUseCase.writeCombinedExportFile(requireContext(), db)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "RFID app logs export")
            }
            requireActivity().runOnUiThread {
                startActivity(Intent.createChooser(send, "Export logs"))
            }
        }
    }

    private fun ensureBg() {
        if (bg.isShutdown || bg.isTerminated) {
            bg = Executors.newSingleThreadExecutor()
        }
    }
}
