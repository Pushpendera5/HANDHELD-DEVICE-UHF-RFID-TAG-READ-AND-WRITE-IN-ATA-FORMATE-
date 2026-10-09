package com.rfidsoftwares.integration.usecases

import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.dao.ProductDao
import com.rfidsoftwares.data.local.dao.ProductEpcDao
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.models.Product
import com.rfidsoftwares.integration.models.ProductCatalogSnapshot
import com.rfidsoftwares.integration.models.ProductEpc
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CatalogSyncUseCaseTest {

    private lateinit var adapter: BackendAdapter
    private lateinit var db: RfidSessionDatabase
    private lateinit var productDao: ProductDao
    private lateinit var epcDao: ProductEpcDao
    private lateinit var useCase: CatalogSyncUseCase

    @Before
    fun setup() {
        adapter = mock()
        db = mock()
        productDao = mock()
        epcDao = mock()
        whenever(db.productDao()).thenReturn(productDao)
        whenever(db.productEpcDao()).thenReturn(epcDao)
        useCase = CatalogSyncUseCase(adapter)
    }

    @Test
    fun syncCatalog_fetchesAndPersistsCanonicalSnapshot() {
        val snapshot = ProductCatalogSnapshot(
            products = listOf(
                Product(
                    id = "p1",
                    providerConnectionId = "custom_node",
                    sku = "SKU-1",
                    name = "Name 1",
                    barcodePrimary = null,
                    status = "active",
                    updatedAtEpochMs = 111L,
                    image = null,
                    description = "d1",
                    meta = "{}",
                )
            ),
            productEpcs = listOf(
                ProductEpc(
                    epc = "EPC1",
                    providerConnectionId = "custom_node",
                    productId = "p1",
                    state = "active",
                    createdAtEpochMs = 10L,
                    updatedAtEpochMs = 20L,
                )
            )
        )
        whenever(adapter.fetchCatalogFull("custom_node")).thenReturn(snapshot)

        val result = useCase.syncCatalog("custom_node", db)

        val productsCaptor = argumentCaptor<List<com.rfidsoftwares.data.local.entities.ProductEntity>>()
        val epcsCaptor = argumentCaptor<List<com.rfidsoftwares.data.local.entities.ProductEpcEntity>>()
        verify(productDao).upsertProducts(productsCaptor.capture())
        verify(epcDao).upsertEpcs(epcsCaptor.capture())

        assertEquals(snapshot, result)
        assertEquals(1, productsCaptor.firstValue.size)
        assertEquals("p1", productsCaptor.firstValue.first().id)
        assertEquals(1, epcsCaptor.firstValue.size)
        assertEquals("EPC1", epcsCaptor.firstValue.first().epc)
    }
}

