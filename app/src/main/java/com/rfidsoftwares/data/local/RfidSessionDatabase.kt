
package com.rfidsoftwares.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.rfidsoftwares.data.local.dao.AuditLogDao
import com.rfidsoftwares.data.local.dao.InventorySessionDao
import com.rfidsoftwares.data.local.dao.IssueDao
import com.rfidsoftwares.data.local.dao.ProductDao
import com.rfidsoftwares.data.local.dao.ProductEpcDao
import com.rfidsoftwares.data.local.dao.SessionProductStateDao
import com.rfidsoftwares.data.local.dao.SessionScanDao
import com.rfidsoftwares.data.local.dao.SyncOutboxDao
import com.rfidsoftwares.data.local.dao.UnknownEpcCacheDao
import com.rfidsoftwares.data.local.entities.AuditLogEntity
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.IssueEntity
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.SessionProductStateEntity
import com.rfidsoftwares.data.local.entities.SessionScanEntity
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity
import com.rfidsoftwares.data.local.entities.UnknownEpcCacheEntity

/**
 * Phase 2 local persistence foundation.
 *
 * Note: Provider sync/catalog updates are not implemented in Phase 2.
 */
@Database(
    entities = [
        ProductEntity::class,
        ProductEpcEntity::class,
        InventorySessionEntity::class,
        SessionScanEntity::class,
        SessionProductStateEntity::class,
        UnknownEpcCacheEntity::class,
        SyncOutboxEntity::class,
        IssueEntity::class,
        AuditLogEntity::class,
    ],
    version = 3,
    // Keeps a schema history folder for future migrations (Phase 9+ can validate upgrade paths).
    exportSchema = true
)
abstract class RfidSessionDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun productEpcDao(): ProductEpcDao
    abstract fun inventorySessionDao(): InventorySessionDao
    abstract fun sessionScanDao(): SessionScanDao
    abstract fun sessionProductStateDao(): SessionProductStateDao
    abstract fun unknownEpcCacheDao(): UnknownEpcCacheDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun issueDao(): IssueDao
    abstract fun auditLogDao(): AuditLogDao
}

