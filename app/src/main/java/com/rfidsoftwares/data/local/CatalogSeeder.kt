
package com.rfidsoftwares.data.local

import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.ProductEntity

object CatalogSeeder {

    /**
     * Phase 2: create minimal local snapshot if DB is empty.
     *
     * Real provider/product sync is not implemented until later phases.
     */
    fun seedIfEmpty(db: RfidSessionDatabase, providerConnectionId: String) {
        val products = db.productDao().getProducts(providerConnectionId)
        if (products.isNotEmpty()) return

        val now = System.currentTimeMillis()
        val demoProvider = providerConnectionId

        // Demo catalog:
        // - P1 expects EPC1 & EPC2 (2 distinct EPCs)
        // - P2 expects EPC3 (1 distinct EPC)
        // - P3 expects EPC4..EPC6 (3 distinct EPCs)
        val p1 = ProductEntity(
            id = "p1",
            providerConnectionId = demoProvider,
            sku = "SKU-1",
            name = "Demo Product A",
            barcodePrimary = null,
            status = "active",
            updatedAt = now,
            image = null,
            description = "Phase 2 demo catalog",
            meta = null,
        )
        val p2 = ProductEntity(
            id = "p2",
            providerConnectionId = demoProvider,
            sku = "SKU-2",
            name = "Demo Product B",
            barcodePrimary = null,
            status = "active",
            updatedAt = now,
            image = null,
            description = "Phase 2 demo catalog",
            meta = null,
        )
        val p3 = ProductEntity(
            id = "p3",
            providerConnectionId = demoProvider,
            sku = "SKU-3",
            name = "Demo Product C",
            barcodePrimary = null,
            status = "active",
            updatedAt = now,
            image = null,
            description = "Phase 2 demo catalog",
            meta = null,
        )

        val allProducts = listOf(p1, p2, p3)

        // Anti-theft demo: billed = safe, active = theft-risk in evaluation.
        val allEpcs = listOf(
            ProductEpcEntity(epc = "EPC1", providerConnectionId = demoProvider, productId = "p1", state = "billed", createdAt = now, updatedAt = now),
            ProductEpcEntity(epc = "EPC2", providerConnectionId = demoProvider, productId = "p1", state = "billed", createdAt = now, updatedAt = now),
            ProductEpcEntity(epc = "EPC3", providerConnectionId = demoProvider, productId = "p2", state = "billed", createdAt = now, updatedAt = now),
            ProductEpcEntity(epc = "EPC4", providerConnectionId = demoProvider, productId = "p3", state = "active", createdAt = now, updatedAt = now),
            ProductEpcEntity(epc = "EPC5", providerConnectionId = demoProvider, productId = "p3", state = "active", createdAt = now, updatedAt = now),
            ProductEpcEntity(epc = "EPC6", providerConnectionId = demoProvider, productId = "p3", state = "active", createdAt = now, updatedAt = now),
        )

        db.productDao().upsertProducts(allProducts)
        db.productEpcDao().upsertEpcs(allEpcs)
    }
}

