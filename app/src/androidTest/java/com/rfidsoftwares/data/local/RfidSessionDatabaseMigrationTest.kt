package com.rfidsoftwares.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RfidSessionDatabaseMigrationTest {

    private val testDb = "rfid_session_migration_test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RfidSessionDatabase::class.java,
    )

    @Test
    fun migrate1To2_addsCatalogSnapshotMarkerAndSyncOutbox() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO InventorySession (sessionId, providerConnectionId, operatorId, locationId, startedAt, finishedAt, state, updatedAt)
                VALUES ('s-mig', 'custom_node', 'op', 'loc', 1, NULL, 'active', 2)
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 2, true, *RfidSessionMigrations.ALL)

        db.query("SELECT catalogSnapshotMarker FROM InventorySession WHERE sessionId = 's-mig'").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='SyncOutbox'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("SyncOutbox", c.getString(0))
        }
        db.close()
    }

    @Test
    fun migrate1To2_enforcesUniqueProviderAndIdempotencyKeyOnSyncOutbox() {
        helper.createDatabase(testDb + "_uniq", 1).apply { close() }

        val db = helper.runMigrationsAndValidate(testDb + "_uniq", 2, true, *RfidSessionMigrations.ALL)
        val insert = """
            INSERT INTO SyncOutbox (jobId, sessionId, providerConnectionId, type, payload, idempotencyKey, state, retryCount, lastCorrelationId, lastError, createdAt, updatedAt)
            VALUES (?, 's1', 'custom_node', 'T', '{}', 'idem-x', 'pending', 0, NULL, NULL, 1, 1)
        """.trimIndent()

        db.compileStatement(insert).use { st ->
            st.bindString(1, "job-a")
            assertEquals(1L, st.executeInsert())
        }
        db.compileStatement(insert).use { st ->
            st.bindString(1, "job-b")
            try {
                st.executeInsert()
                throw AssertionError("expected UNIQUE constraint failure")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                // expected: duplicate (providerConnectionId, idempotencyKey)
            }
        }
        db.close()
    }

    @Test
    fun migrate2To3_addsIssueAndAuditTables() {
        helper.createDatabase(testDb + "_v2", 2).apply { close() }

        val db = helper.runMigrationsAndValidate(testDb + "_v2", 3, true, *RfidSessionMigrations.ALL)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='IssueRecord'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("IssueRecord", c.getString(0))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='AuditLog'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("AuditLog", c.getString(0))
        }
        db.close()
    }
}
