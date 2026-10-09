package com.rfidsoftwares.integration

import com.rfidsoftwares.integration.adapterstubs.DisabledBackendAdapter
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendAdapterProviderTest {

    @Test
    fun getAdapter_returnsSingletonCustomAdapter_forCustomNodeIds() {
        val a = BackendAdapterProvider.getAdapter("custom_node")
        val b = BackendAdapterProvider.getAdapter("custom_node")
        assertSame(a, b)
    }

    @Test
    fun getAdapter_returnsDisabledAdapter_forUnknownProvider() {
        val adapter = BackendAdapterProvider.getAdapter("unknown_provider")
        assertTrue(adapter is DisabledBackendAdapter)
    }
}

