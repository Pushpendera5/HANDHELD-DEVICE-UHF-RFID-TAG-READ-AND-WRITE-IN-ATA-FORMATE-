
package com.rfidsoftwares.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Phase 2 migration strategy placeholder.
 *
 * Schema starts at version=1. When we introduce schema changes in later phases,
 * we will add explicit Migration objects here and wire them into the DB builder.
 *
 * The existence of this file + wiring provides "versioned migration strategy from day one".
 */
object RfidSessionMigrations {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE InventorySession ADD COLUMN catalogSnapshotMarker TEXT"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `SyncOutbox` (" +
                    "`jobId` TEXT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`providerConnectionId` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`payload` TEXT NOT NULL, " +
                    "`idempotencyKey` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL, " +
                    "`retryCount` INTEGER NOT NULL, " +
                    "`lastCorrelationId` TEXT, " +
                    "`lastError` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`jobId`)" +
                    ")"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `idx_outbox_provider_idempotency_unique` " +
                    "ON `SyncOutbox` (`providerConnectionId`, `idempotencyKey`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_outbox_provider_state` " +
                    "ON `SyncOutbox` (`providerConnectionId`, `state`)"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `IssueRecord` (" +
                    "`issueId` TEXT NOT NULL, " +
                    "`severity` TEXT NOT NULL, " +
                    "`category` TEXT NOT NULL, " +
                    "`message` TEXT NOT NULL, " +
                    "`correlationId` TEXT, " +
                    "`detail` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`active` INTEGER NOT NULL, " +
                    "`suggestedAction` TEXT NOT NULL, " +
                    "PRIMARY KEY(`issueId`)" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_issue_active_created` ON `IssueRecord` (`active`, `createdAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_issue_created` ON `IssueRecord` (`createdAt`)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `AuditLog` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`eventType` TEXT NOT NULL, " +
                    "`message` TEXT NOT NULL, " +
                    "`detail` TEXT, " +
                    "`providerConnectionId` TEXT, " +
                    "`correlationId` TEXT, " +
                    "`createdAt` INTEGER NOT NULL" +
                    ")"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_audit_created` ON `AuditLog` (`createdAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_audit_provider` ON `AuditLog` (`providerConnectionId`)"
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

