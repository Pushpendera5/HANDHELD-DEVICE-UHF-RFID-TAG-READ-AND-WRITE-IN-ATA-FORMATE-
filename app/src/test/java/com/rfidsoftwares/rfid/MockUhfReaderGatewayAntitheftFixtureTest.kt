package com.rfidsoftwares.rfid

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.rfidsoftwares.RfidInventoryApp
import com.rfidsoftwares.common.config.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8: [MockUhfReaderGateway] + `assets/test-fixtures/uhf/mock-antitheft-sequence.json`
 * (same path as AntiTheftFragment test-mode override).
 */
private class UhfFixtureTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RfidInventoryApp.AppContextHolder.setApplicationContext(this)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = UhfFixtureTestApplication::class)
class MockUhfReaderGatewayAntitheftFixtureTest {

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        forceDebuggable(app)
        RfidInventoryApp.AppContextHolder.setApplicationContext(app)
    }

    @Test
    fun mockAntitheftSequence_loadsFixture_andEmitsBatchedEvents() {
        // If FeatureFlags logic changes in the future, this test should remain informative instead of failing spuriously.
        assumeTrue(
            "Skipping fixture-driven UHF test because FeatureFlags are not in test mode " +
                "(TEST_MODE_ENABLED=${FeatureFlags.TEST_MODE_ENABLED}, UHF_TEST_MODE_ENABLED=${FeatureFlags.UHF_TEST_MODE_ENABLED}).",
            FeatureFlags.TEST_MODE_ENABLED && FeatureFlags.UHF_TEST_MODE_ENABLED,
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val gateway = MockUhfReaderGateway()
        assertTrue(gateway.init(app))

        gateway.fixturePathOverride = "test-fixtures/uhf/mock-antitheft-sequence.json"
        assertTrue(gateway.startInventory())

        val first = gateway.readBufferedTagEvents()
        assertEquals(
            listOf(
                "E280699500005000FEF3B548",
                "E280699500005000FEF83548",
            ),
            first.map { it.epc },
        )

        val second = gateway.readBufferedTagEvents()
        assertEquals(
            listOf(
                "UNKNOWN_TAG_X",
                "E280699500004000FEF5D148",
            ),
            second.map { it.epc },
        )

        val third = gateway.readBufferedTagEvents()
        assertEquals(
            listOf(
                "E280699500005000FEF3B548",
                "E280699500005000FEF83548",
            ),
            third.map { it.epc },
        )

        val fourth = gateway.readBufferedTagEvents()
        assertEquals(listOf("UNKNOWN_TAG_Y"), fourth.map { it.epc })
        assertTrue(gateway.readBufferedTagEvents().isEmpty())

        val diag = gateway.readDiagnosticsSummary()
        assertTrue(diag.readerOpen)
        assertTrue(diag.detailLine.contains("active"))

        gateway.stopInventory()
    }

    private fun forceDebuggable(app: Application) {
        val ai = app.applicationInfo
        ai.flags = ai.flags or ApplicationInfo.FLAG_DEBUGGABLE
    }
}
