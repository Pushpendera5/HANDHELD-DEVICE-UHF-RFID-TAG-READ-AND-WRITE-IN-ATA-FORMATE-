package com.rfidsoftwares.data.local

import com.rfidsoftwares.data.local.dao.ProductDao
import com.rfidsoftwares.data.local.dao.ProductEpcDao
import com.rfidsoftwares.data.local.entities.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CatalogSeederTest {

    private lateinit var db: RfidSessionDatabase
    private lateinit var productDao: ProductDao
    private lateinit var epcDao: ProductEpcDao

    @Before
    fun setup() {
        db = mock()
        productDao = mock()
        epcDao = mock()

        whenever(db.productDao()).thenReturn(productDao)
        whenever(db.productEpcDao()).thenReturn(epcDao)
    }

    @Test
    fun seedIfEmpty_doesNothing_whenProductsAlreadyExist() {
        whenever(productDao.getProducts("custom_node")).thenReturn(
            listOf(
                ProductEntity(
                    id = "existing",
                    providerConnectionId = "custom_node",
                    sku = "sku",
                    name = "Existing",
                    barcodePrimary = null,
                    status = "active",
                    updatedAt = 1L,
                    image = null,
                    description = null,
                    meta = null,
                )
            )
        )

        CatalogSeeder.seedIfEmpty(db, "custom_node")

        verify(productDao, never()).upsertProducts(org.mockito.kotlin.any())
        verify(epcDao, never()).upsertEpcs(org.mockito.kotlin.any())
    }

    @Test
    fun seedIfEmpty_insertsExpectedDemoCatalog_whenEmpty() {
        whenever(productDao.getProducts("custom_node")).thenReturn(emptyList())

        CatalogSeeder.seedIfEmpty(db, "custom_node")

        val productCaptor = argumentCaptor<List<ProductEntity>>()
        val epcCaptor = argumentCaptor<List<com.rfidsoftwares.data.local.entities.ProductEpcEntity>>()
        verify(productDao).upsertProducts(productCaptor.capture())
        verify(epcDao).upsertEpcs(epcCaptor.capture())

        assertEquals(3, productCaptor.firstValue.size)
        assertEquals(6, epcCaptor.firstValue.size)
        assertEquals("custom_node", productCaptor.firstValue.first().providerConnectionId)
        assertNotNull(epcCaptor.firstValue.first().epc)
    }
}

