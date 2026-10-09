
package com.rfidsoftwares.integration.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.integration.BackendAdapterProvider
import java.util.concurrent.TimeUnit

class SyncOutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = RfidSessionDbProvider.getInstance(applicationContext)
        val shouldRetryWorker = SyncOutboxProcessor(
            db = db,
            adapterResolver = { providerId -> BackendAdapterProvider.getAdapter(providerId) },
        ).processOnce()
        return if (shouldRetryWorker) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "sync-outbox-retry-work"

        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        /** Issue Center / manual retry: run a processor pass as soon as constraints allow. */
        fun enqueueImmediate(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}

