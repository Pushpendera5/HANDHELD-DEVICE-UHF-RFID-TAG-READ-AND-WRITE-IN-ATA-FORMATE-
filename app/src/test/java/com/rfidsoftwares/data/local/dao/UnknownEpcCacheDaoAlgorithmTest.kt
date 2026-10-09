package com.rfidsoftwares.data.local.dao

import com.rfidsoftwares.data.local.entities.UnknownEpcCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UnknownEpcCacheDaoAlgorithmTest {

    @Test
    fun upsertAndEnforceCap_keepsOnlyMostRecentUniqueEpcs() {
        val dao = InMemoryUnknownEpcCacheDao()
        val provider = "custom_node"
        for (i in 1..105) {
            dao.upsertAndEnforceCap(provider, "EPC$i", now = i.toLong(), cap = 100)
        }

        val all = dao.getAllUnknownOrderedOldestFirst(provider)
        assertEquals(100, all.size)
        // Oldest five must be evicted.
        val epcs = all.map { it.epc }.toSet()
        for (i in 1..5) {
            assertEquals(false, epcs.contains("EPC$i"))
        }
        assertEquals(true, epcs.contains("EPC105"))
    }

    @Test
    fun upsertAndEnforceCap_updatesSeenCount_andLastSeen_forExistingEpc() {
        val dao = InMemoryUnknownEpcCacheDao()
        val provider = "custom_node"
        dao.upsertAndEnforceCap(provider, "EPC1", now = 10L, cap = 100)
        dao.upsertAndEnforceCap(provider, "EPC1", now = 25L, cap = 100)

        val row = dao.getUnknown(provider, "EPC1")
        assertNotNull(row)
        assertEquals(2, row!!.seenCount)
        assertEquals(10L, row.firstSeenAt)
        assertEquals(25L, row.lastSeenAt)
    }
}

private class InMemoryUnknownEpcCacheDao : UnknownEpcCacheDao {
    private val store = LinkedHashMap<Pair<String, String>, UnknownEpcCacheEntity>()

    override fun getUnknown(providerConnectionId: String, epc: String): UnknownEpcCacheEntity? {
        return store[providerConnectionId to epc]
    }

    override fun insertUnknown(cache: UnknownEpcCacheEntity) {
        store.putIfAbsent(cache.providerConnectionId to cache.epc, cache)
    }

    override fun updateSeen(providerConnectionId: String, epc: String, lastSeenAt: Long) {
        val key = providerConnectionId to epc
        val existing = store[key] ?: return
        store[key] = existing.copy(lastSeenAt = lastSeenAt, seenCount = existing.seenCount + 1)
    }

    override fun getUnknownEpcsOrdered(providerConnectionId: String, limit: Int): List<String> {
        return store.values
            .filter { it.providerConnectionId == providerConnectionId }
            .sortedWith(compareByDescending<UnknownEpcCacheEntity> { it.lastSeenAt }.thenByDescending { it.firstSeenAt })
            .take(limit)
            .map { it.epc }
    }

    override fun countUnknown(providerConnectionId: String): Int {
        return store.values.count { it.providerConnectionId == providerConnectionId }
    }

    override fun getAllUnknownOrderedOldestFirst(providerConnectionId: String): List<UnknownEpcCacheEntity> {
        return store.values
            .filter { it.providerConnectionId == providerConnectionId }
            .sortedWith(compareBy<UnknownEpcCacheEntity> { it.lastSeenAt }.thenBy { it.firstSeenAt })
    }

    override fun deleteByEpcs(providerConnectionId: String, epcs: List<String>) {
        epcs.forEach { store.remove(providerConnectionId to it) }
    }

    override fun deleteByProvider(providerConnectionId: String) {
        store.keys
            .filter { it.first == providerConnectionId }
            .toList()
            .forEach { store.remove(it) }
    }
}

