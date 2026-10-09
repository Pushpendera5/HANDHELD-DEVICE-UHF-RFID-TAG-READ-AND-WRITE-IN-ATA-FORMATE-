
package com.rfidsoftwares.support

import android.content.Context
import com.rfidsoftwares.data.local.RfidSessionDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogExportUseCase {

    fun writeCombinedExportFile(context: Context, db: RfidSessionDatabase): File {
        val dir = File(context.cacheDir, "issue_exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val out = File(dir, "rfid_logs_$stamp.txt")
        val issues = db.issueDao().listAllRecent()
        val audit = db.auditLogDao().listRecent(500)
        val text = buildString {
            appendLine("=== Issues (most recent first) ===")
            if (issues.isEmpty()) {
                appendLine("(none)")
            } else {
                for (i in issues) {
                    appendLine("[${i.severity}] ${i.category} @ ${i.createdAt}")
                    appendLine(i.message)
                    if (!i.correlationId.isNullOrBlank()) appendLine("Correlation-Id: ${i.correlationId}")
                    if (!i.detail.isNullOrBlank()) appendLine("Detail: ${i.detail}")
                    appendLine("---")
                }
            }
            appendLine()
            appendLine("=== Audit log (most recent first) ===")
            if (audit.isEmpty()) {
                appendLine("(none)")
            } else {
                for (a in audit) {
                    appendLine("[${a.eventType}] @ ${a.createdAt}")
                    appendLine(a.message)
                    if (!a.detail.isNullOrBlank()) appendLine("Detail: ${a.detail}")
                    if (!a.correlationId.isNullOrBlank()) appendLine("Correlation-Id: ${a.correlationId}")
                    appendLine("---")
                }
            }
        }
        out.writeText(text)
        return out
    }
}
