
package com.rfidsoftwares.integration.usecases

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.data.local.RfidSessionDatabase
import com.rfidsoftwares.data.local.entities.InventorySessionEntity
import com.rfidsoftwares.data.local.entities.SyncOutboxEntity
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.models.InventoryPushPayload
import com.rfidsoftwares.integration.models.ProductStatePush
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class InventorySessionPushUseCase(
    private val adapter: BackendAdapter,
) {
    private val gson = Gson()

    data class PushComputation(
        val session: InventorySessionEntity,
        val payload: InventoryPushPayload,
        val mismatchCount: Int,
        val hasMismatch: Boolean,
        val idempotencyKey: String,
    )

    fun computePushData(sessionId: String, db: RfidSessionDatabase): PushComputation {
        val session: InventorySessionEntity = db.inventorySessionDao().getSession(sessionId)
            ?: throw IllegalStateException("Session not found: $sessionId")

        val states = db.sessionProductStateDao().getStates(sessionId, session.providerConnectionId)

        val productStates = states.map { s ->
            ProductStatePush(
                productId = s.productId,
                expectedCount = s.expectedCount,
                foundCount = s.foundCount,
                status = s.status,
            )
        }.sortedBy { it.productId }

        val payload = InventoryPushPayload(
            sessionId = session.sessionId,
            providerConnectionId = session.providerConnectionId,
            operatorId = session.operatorId,
            locationId = session.locationId,
            catalogSnapshotMarker = session.catalogSnapshotMarker,
            productStates = productStates,
        )
        val mismatchCount = productStates.count { it.status == "mismatch" }
        val checksum = checksumForPayload(payload)
        val idempotencyKey = "${AppConfig.SyncReliabilityConfig.CLIENT_ID}|${session.sessionId}|${AppConfig.SyncReliabilityConfig.INVENTORY_PUSH_OPERATION_TYPE}|$checksum"
        return PushComputation(
            session = session,
            payload = payload,
            mismatchCount = mismatchCount,
            hasMismatch = mismatchCount > 0,
            idempotencyKey = idempotencyKey,
        )
    }

    fun pushFinishedSession(sessionId: String, db: RfidSessionDatabase) {
        val data = computePushData(sessionId, db)
        adapter.pushInventorySession(
            providerConnectionId = data.session.providerConnectionId,
            payload = data.payload,
            idempotencyKey = data.idempotencyKey,
        )
    }

    fun enqueueOutboxJob(sessionId: String, db: RfidSessionDatabase): Boolean {
        val data = computePushData(sessionId, db)
        if (data.session.state == "incomplete") return false
        if (data.session.state != "finished") return false

        val now = System.currentTimeMillis()
        val row = SyncOutboxEntity(
            jobId = UUID.randomUUID().toString(),
            sessionId = data.session.sessionId,
            providerConnectionId = data.session.providerConnectionId,
            type = AppConfig.SyncReliabilityConfig.INVENTORY_PUSH_OPERATION_TYPE,
            payload = gson.toJson(data.payload),
            idempotencyKey = data.idempotencyKey,
            state = "pending",
            retryCount = 0,
            lastCorrelationId = null,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        return db.syncOutboxDao().insertIgnore(row) > 0L
    }

    private fun checksumForPayload(payload: InventoryPushPayload): String {
        val canonical = JsonObject().apply {
            addProperty("sessionId", payload.sessionId)
            addProperty("providerConnectionId", payload.providerConnectionId)
            addProperty("operatorId", payload.operatorId)
            addProperty("locationId", payload.locationId)
            addProperty("catalogSnapshotMarker", payload.catalogSnapshotMarker ?: "")
            val states = JsonArray()
            payload.productStates
                .sortedBy { it.productId.lowercase(Locale.US) }
                .forEach { s ->
                    states.add(
                        JsonObject().apply {
                            addProperty("productId", s.productId)
                            addProperty("expectedCount", s.expectedCount)
                            addProperty("foundCount", s.foundCount)
                            addProperty("status", s.status)
                        }
                    )
                }
            add("productStates", states)
        }
        val bytes = gson.toJson(canonical).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b) }
    }
}

