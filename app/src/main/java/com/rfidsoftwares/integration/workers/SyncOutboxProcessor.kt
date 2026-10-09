
package com.rfidsoftwares.integration.workers

import com.google.gson.Gson
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.InventoryPushPayload
import com.rfidsoftwares.issues.IssueLogSupport
import java.util.UUID

internal class SyncOutboxProcessor(
    private val db: RfidSessionDatabase,
    private val adapterResolver: (String) -> BackendAdapter,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val corrIdProvider: () -> String = { UUID.randomUUID().toString() },
) {
    private val gson = Gson()

    fun processOnce(): Boolean {
        db.syncOutboxDao().recoverStuckRunningJobs(nowProvider())
        var shouldRetryWorker = false
        val providers = AppConfig.ProviderRegistry.providers.map { it.providerId }
        for (providerId in providers) {
            val jobs = db.syncOutboxDao().getRunnableJobs(providerId, 20)
            for (job in jobs) {
                val corrId = corrIdProvider()
                val attempt = job.retryCount + 1
                db.syncOutboxDao().updateJobState(
                    jobId = job.jobId,
                    state = "running",
                    retryCount = job.retryCount,
                    corrId = corrId,
                    error = null,
                    now = nowProvider(),
                )
                try {
                    val payload = gson.fromJson(job.payload, InventoryPushPayload::class.java)
                    val session = db.inventorySessionDao().getSession(job.sessionId)
                    if (session?.state == "incomplete") {
                        db.syncOutboxDao().updateJobState(
                            jobId = job.jobId,
                            state = "failed_permanent",
                            retryCount = attempt,
                            corrId = corrId,
                            error = "Blocked push for incomplete session (Correlation-Id=$corrId)",
                            now = nowProvider(),
                        )
                        logOutboxIssue(
                            job = job,
                            terminalState = "failed_permanent",
                            corrId = corrId,
                            message = "Blocked push for incomplete session",
                        )
                        continue
                    }
                    adapterResolver(job.providerConnectionId).pushInventorySession(
                        providerConnectionId = job.providerConnectionId,
                        payload = payload,
                        idempotencyKey = job.idempotencyKey,
                        correlationId = corrId,
                    )
                    db.syncOutboxDao().updateJobState(
                        jobId = job.jobId,
                        state = "succeeded",
                        retryCount = attempt,
                        corrId = corrId,
                        error = null,
                        now = nowProvider(),
                    )
                } catch (conflict: AdapterError.ConflictError) {
                    db.syncOutboxDao().updateJobState(
                        jobId = job.jobId,
                        state = "conflicted",
                        retryCount = attempt,
                        corrId = corrId,
                        error = "${conflict.message.orEmpty()} (Correlation-Id=$corrId)",
                        now = nowProvider(),
                    )
                    logOutboxIssue(
                        job = job,
                        terminalState = "conflicted",
                        corrId = conflict.responseCorrelationId ?: corrId,
                        message = conflict.message.orEmpty(),
                    )
                } catch (transient: AdapterError.NetworkError) {
                    val effCorr = transient.responseCorrelationId ?: corrId
                    handleRetry(job, attempt, effCorr, transient.message.orEmpty())
                    if (attempt < AppConfig.SyncReliabilityConfig.OUTBOX_MAX_RETRIES) shouldRetryWorker = true
                } catch (server: AdapterError.ServerError) {
                    val effCorr = server.responseCorrelationId ?: corrId
                    handleRetry(job, attempt, effCorr, server.message.orEmpty())
                    if (attempt < AppConfig.SyncReliabilityConfig.OUTBOX_MAX_RETRIES) shouldRetryWorker = true
                } catch (auth: AdapterError.AuthError) {
                    val effCorr = auth.responseCorrelationId ?: corrId
                    markFailedPermanent(job, attempt, effCorr, auth.message.orEmpty())
                } catch (validation: AdapterError.ValidationError) {
                    val effCorr = validation.responseCorrelationId ?: corrId
                    markFailedPermanent(job, attempt, effCorr, validation.message.orEmpty())
                } catch (other: Exception) {
                    markFailedPermanent(job, attempt, corrId, other.message ?: "Unknown push failure")
                }
            }
        }
        return shouldRetryWorker
    }

    private fun logOutboxIssue(job: SyncOutboxEntity, terminalState: String, corrId: String, message: String) {
        runCatching {
            IssueLogSupport.recordOutboxFailure(
                db = db,
                providerConnectionId = job.providerConnectionId,
                jobId = job.jobId,
                sessionId = job.sessionId,
                terminalState = terminalState,
                message = message,
                correlationId = corrId,
            )
        }
    }

    private fun handleRetry(job: SyncOutboxEntity, attempt: Int, corrId: String, message: String) {
        val terminal = attempt >= AppConfig.SyncReliabilityConfig.OUTBOX_MAX_RETRIES
        db.syncOutboxDao().updateJobState(
            jobId = job.jobId,
            state = if (terminal) "failed_permanent" else "retrying",
            retryCount = attempt,
            corrId = corrId,
            error = "$message (Correlation-Id=$corrId)",
            now = nowProvider(),
        )
        if (terminal) {
            logOutboxIssue(job, "failed_permanent", corrId, message)
        }
    }

    private fun markFailedPermanent(job: SyncOutboxEntity, attempt: Int, corrId: String, message: String) {
        db.syncOutboxDao().updateJobState(
            jobId = job.jobId,
            state = "failed_permanent",
            retryCount = attempt,
            corrId = corrId,
            error = "$message (Correlation-Id=$corrId)",
            now = nowProvider(),
        )
        logOutboxIssue(job, "failed_permanent", corrId, message)
    }
}

