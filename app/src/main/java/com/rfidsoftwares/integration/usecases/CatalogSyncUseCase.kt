
package com.rfidsoftwares.integration.usecases

import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.mappers.CanonicalToRoomMappers
import com.rfidsoftwares.integration.models.ProductCatalogSnapshot

class CatalogSyncUseCase(
    private val adapter: BackendAdapter,
) {
    /**
     * Fetch provider catalog (products + EPC mappings) and upsert into local Room.
     *
     * Phase 3 rule: mapping belongs in adapter layer; persistence belongs in app layer.
     */
    fun syncCatalog(providerConnectionId: String, db: RfidSessionDatabase): ProductCatalogSnapshot {
        val snapshot = adapter.fetchCatalogFull(providerConnectionId)
        val products = CanonicalToRoomMappers.run { snapshot.products.map { it.toRoomEntity() } }
        val epcs = CanonicalToRoomMappers.run { snapshot.productEpcs.map { it.toRoomEntity() } }
        db.productDao().upsertProducts(products)
        db.productEpcDao().upsertEpcs(epcs)
        return snapshot
    }
}

