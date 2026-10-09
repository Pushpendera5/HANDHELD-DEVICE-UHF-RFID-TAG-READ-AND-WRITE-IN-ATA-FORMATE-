
package com.rfidsoftwares.integration.mappers

import com.rfidsoftwares.data.local.entities.ProductEpcEntity
import com.rfidsoftwares.data.local.entities.ProductEntity
import com.rfidsoftwares.integration.models.Product
import com.rfidsoftwares.integration.models.ProductEpc

/**
 * Phase 3 "canonical-first" mappers.
 *
 * These utilities convert canonical integration models into the Room entities
 * used by the app's local persistence layer.
 */
object CanonicalToRoomMappers {
    fun Product.toRoomEntity(): ProductEntity {
        return ProductEntity(
            id = id,
            providerConnectionId = providerConnectionId,
            sku = sku,
            name = name,
            barcodePrimary = barcodePrimary,
            status = status,
            updatedAt = updatedAtEpochMs,
            image = image,
            description = description,
            meta = meta,
        )
    }

    fun ProductEpc.toRoomEntity(): ProductEpcEntity {
        return ProductEpcEntity(
            epc = epc,
            providerConnectionId = providerConnectionId,
            productId = productId,
            state = state,
            createdAt = createdAtEpochMs,
            updatedAt = updatedAtEpochMs,
        )
    }
}

