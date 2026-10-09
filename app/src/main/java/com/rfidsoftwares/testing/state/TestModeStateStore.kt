
package com.rfidsoftwares.testing.state

import com.rfidsoftwares.integration.models.Cart
import com.rfidsoftwares.integration.models.CartModifyPayload
import com.rfidsoftwares.integration.models.CheckoutBill
import com.rfidsoftwares.integration.models.Customer
import com.rfidsoftwares.integration.models.ProductEpc
import com.rfidsoftwares.integration.models.InventoryPushPayload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 8 in-memory simulated write-state for test mode.
 *
 * Scope rules (minimal for current Phase 8 app):
 * - inventory pushes are scoped by `providerConnectionId` + `sessionId` + idempotency key.
 */
object TestModeStateStore {
    private const val DEFAULT_SCENARIO_ID = "default"

    data class InventoryPushKey(
        val scenarioId: String,
        val providerConnectionId: String,
        val sessionId: String,
        val idempotencyKey: String,
    )

    private data class InventoryPushRecord(
        val payloadSnapshotHash: Int,
    )

    private val inventoryPushes = ConcurrentHashMap<InventoryPushKey, InventoryPushRecord>()
    private val registeredEpcsByProvider = ConcurrentHashMap<String, ConcurrentHashMap<String, ProductEpc>>()
    private val customersByProvider = ConcurrentHashMap<String, ConcurrentHashMap<String, Customer>>()
    private val cartsByProvider = ConcurrentHashMap<String, ConcurrentHashMap<String, Cart>>()
    private val checkoutBillsByProvider = ConcurrentHashMap<String, CopyOnWriteArrayList<CheckoutBill>>()

    fun recordInventoryPushIfNeeded(
        providerConnectionId: String,
        sessionId: String,
        idempotencyKey: String,
        payload: InventoryPushPayload,
    ) {
        val key = InventoryPushKey(
            scenarioId = DEFAULT_SCENARIO_ID,
            providerConnectionId = providerConnectionId,
            sessionId = sessionId,
            idempotencyKey = idempotencyKey,
        )

        // Idempotency behavior: identical idempotency key should not cause conflicting state updates.
        val payloadHash = payload.hashCode()
        inventoryPushes.computeIfAbsent(key) {
            InventoryPushRecord(payloadSnapshotHash = payloadHash)
        }
    }

    fun upsertRegisteredEpc(providerConnectionId: String, epc: ProductEpc) {
        val byEpc = registeredEpcsByProvider.computeIfAbsent(providerConnectionId) { ConcurrentHashMap() }
        byEpc[epc.epc] = epc
    }

    fun getRegisteredEpcs(providerConnectionId: String): List<ProductEpc> {
        return registeredEpcsByProvider[providerConnectionId]?.values?.toList() ?: emptyList()
    }

    fun upsertCustomer(providerConnectionId: String, customer: Customer) {
        val byId = customersByProvider.computeIfAbsent(providerConnectionId) { ConcurrentHashMap() }
        byId[customer.id] = customer
    }

    fun findCustomer(
        providerConnectionId: String,
        id: String?,
        phone: String?,
        email: String?,
    ): Customer? {
        val byId = customersByProvider[providerConnectionId] ?: return null
        if (!id.isNullOrBlank()) {
            byId[id]?.let { return it }
        }
        if (!phone.isNullOrBlank()) {
            byId.values.firstOrNull { it.phone == phone }?.let { return it }
        }
        if (!email.isNullOrBlank()) {
            byId.values.firstOrNull { it.email == email }?.let { return it }
        }
        return null
    }

    fun createOrReplaceCart(providerConnectionId: String, cart: Cart): Cart {
        val byId = cartsByProvider.computeIfAbsent(providerConnectionId) { ConcurrentHashMap() }
        byId[cart.id] = cart
        return cart
    }

    fun getCart(providerConnectionId: String, cartId: String): Cart? {
        return cartsByProvider[providerConnectionId]?.get(cartId)
    }

    fun applyCartModify(providerConnectionId: String, payload: CartModifyPayload, fallbackCart: Cart): Cart {
        val byId = cartsByProvider.computeIfAbsent(providerConnectionId) { ConcurrentHashMap() }
        val current = byId[payload.cartId]
        if (current == null) {
            byId[fallbackCart.id] = fallbackCart
            return fallbackCart
        }

        val newLines = current.lineItems.mapIndexed { idx, line ->
            val mod = payload.modifications.firstOrNull { it.lineIndex == idx }
            if (mod == null) line else line.copy(quantity = mod.quantity)
        }
        val updated = current.copy(lineItems = newLines)
        byId[current.id] = updated
        return updated
    }

    fun addCheckoutBill(providerConnectionId: String, bill: CheckoutBill) {
        val list = checkoutBillsByProvider.computeIfAbsent(providerConnectionId) { CopyOnWriteArrayList() }
        list.add(bill)
    }

    fun clear() {
        inventoryPushes.clear()
        registeredEpcsByProvider.clear()
        customersByProvider.clear()
        cartsByProvider.clear()
        checkoutBillsByProvider.clear()
    }
}

