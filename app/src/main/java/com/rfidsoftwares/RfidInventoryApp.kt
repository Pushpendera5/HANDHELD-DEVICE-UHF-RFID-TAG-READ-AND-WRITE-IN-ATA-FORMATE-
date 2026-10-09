
package com.rfidsoftwares

import android.app.Application
import android.content.Context
import com.rfidsoftwares.data.local.AuditRetentionPolicy
import com.rfidsoftwares.data.local.RfidSessionDbProvider
import com.rfidsoftwares.integration.auth.AuthRefreshTelemetry
import com.rfidsoftwares.integration.workers.SyncOutboxWorker
import java.util.concurrent.Executors

/**
 * App-wide context holder used by test-mode fixture loaders.
 *
 * Phase 8 requires reading JSON fixtures from `assets/test-fixtures/...`.
 */
class RfidInventoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.setApplicationContext(this)
        AuthRefreshTelemetry.load(this)
        SyncOutboxWorker.schedule(this)
        Executors.newSingleThreadExecutor().execute {
            val db = RfidSessionDbProvider.getInstance(this)
            AuditRetentionPolicy.enforce(db)
        }
    }

    companion object AppContextHolder {
        @Volatile
        private var applicationContext: Context? = null

        fun setApplicationContext(app: Application) {
            applicationContext = app.applicationContext
        }

        fun requireContext(): Context {
            return applicationContext
                ?: throw IllegalStateException("Application context not initialized yet.")
        }
    }
}

