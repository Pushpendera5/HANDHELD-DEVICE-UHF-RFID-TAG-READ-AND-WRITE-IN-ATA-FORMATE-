package com.rfidsoftwares.integration.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.rfidsoftwares.RfidInventoryApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persistence for diagnostics (Phase 5) — Robolectric JVM test (Phase 8).
 * Uses a slim [Application] so we do not run production [RfidInventoryApp.onCreate] (WorkManager / DB).
 */
private class AuthTelemetryRobolectricApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RfidInventoryApp.AppContextHolder.setApplicationContext(this)
        AuthRefreshTelemetry.load(this)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = AuthTelemetryRobolectricApp::class)
class AuthRefreshTelemetryTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("auth_refresh_telemetry", 0).edit().clear().commit()
        AuthRefreshTelemetry.load(app)
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("auth_refresh_telemetry", 0).edit().clear().commit()
    }

    @Test
    fun recordSuccess_persistsAndReloads() {
        AuthRefreshTelemetry.recordSuccess()
        AuthRefreshTelemetry.lastSuccess = null
        AuthRefreshTelemetry.lastAttemptAtEpochMs = 0L
        AuthRefreshTelemetry.lastErrorSummary = "cleared"

        AuthRefreshTelemetry.load(app)

        assertEquals(true, AuthRefreshTelemetry.lastSuccess)
        assertTrue(AuthRefreshTelemetry.lastAttemptAtEpochMs > 0L)
        assertNull(AuthRefreshTelemetry.lastErrorSummary)
    }

    @Test
    fun recordFailure_persistsErrorAndReloads() {
        AuthRefreshTelemetry.recordFailure("token rejected")
        AuthRefreshTelemetry.lastSuccess = null
        AuthRefreshTelemetry.lastErrorSummary = null

        AuthRefreshTelemetry.load(app)

        assertEquals(false, AuthRefreshTelemetry.lastSuccess)
        assertEquals("token rejected", AuthRefreshTelemetry.lastErrorSummary)
    }
}
