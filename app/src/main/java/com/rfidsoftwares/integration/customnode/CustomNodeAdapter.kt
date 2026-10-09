
package com.rfidsoftwares.integration.customnode

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rfidsoftwares.common.config.FeatureFlags
import com.rfidsoftwares.common.config.AppConfig
import com.rfidsoftwares.integration.BackendAdapter
import com.rfidsoftwares.integration.capabilities.AdapterCapabilityMatrix
import com.rfidsoftwares.integration.config.ApiCommonConfig
import com.rfidsoftwares.integration.config.ApiPathCustom
import com.rfidsoftwares.integration.auth.AuthRefreshTelemetry
import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.models.*
import com.rfidsoftwares.integration.models.AuthRequest.DirectCredentialAuth
import com.rfidsoftwares.integration.models.AuthRequest.OAuthAuth
import com.rfidsoftwares.testing.fixtures.FixtureContractValidator
import com.rfidsoftwares.testing.fixtures.FixtureJsonLoader
import com.rfidsoftwares.testing.state.TestModeStateStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 3: Custom Node adapter (OkHttp + Gson).
 *
 * This is a compile-safe integration surface with canonical-first mapping and normalized errors.
 */
class CustomNodeAdapter(
    private val baseUrl: String,
    private val credentialStore: CredentialStore = InMemoryCredentialStore(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(ApiCommonConfig.DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(ApiCommonConfig.DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(ApiCommonConfig.DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build(),
) : BackendAdapter {

    override val providerId: String = "custom_node"
    override val capabilities: AdapterCapabilityMatrix = CustomNodeCapabilityMatrix.MATRIX

    private val gson = Gson()
    private var lastCaptchaToken: String? = null

    private fun testModeEnabled(): Boolean = FeatureFlags.TEST_MODE_ENABLED

    /**
     * Maps a canonical catalog fixture JSON object to canonical app models.
     *
     * This reuses the same mapper functions that live HTTP responses use.
     */
    private fun mapCatalogFromFixture(providerConnectionId: String, catalogObj: JsonObject): ProductCatalogSnapshot {
        val productsArray = catalogObj.getAsJsonArray("products") ?: JsonArray()
        val products = mapProducts(providerConnectionId, productsArray)
        val productEpcs = mapProductEpcsFromCatalogJson(providerConnectionId, catalogObj, productsArray) +
            TestModeStateStore.getRegisteredEpcs(providerConnectionId)
        return ProductCatalogSnapshot(products = products, productEpcs = productEpcs)
    }

    private enum class FixtureOp(val path: String, val requiredKeys: List<String>) {
        AUTH_SUCCESS("test-fixtures/common/auth-success.json", listOf("accessToken")),
        AUTH_FAIL("test-fixtures/common/auth-fail.json", listOf("message")),
        HEALTH_OK("test-fixtures/common/health-check-healthy.json", listOf("healthy")),
        PRODUCTS_FULL("test-fixtures/custom/products-full.json", listOf("products")),
        PRODUCTS_DELTA("test-fixtures/custom/products-delta.json", listOf("products")),
        REGISTER_EPC_SUCCESS("test-fixtures/custom/register-epc-success.json", listOf("state")),
        INVENTORY_PUSH_MATCH("test-fixtures/custom/inventory-push-match.json", listOf("ok")),
        INVENTORY_PUSH_MISMATCH("test-fixtures/custom/inventory-push-mismatch.json", listOf("ok")),
        CUSTOMER_FOUND("test-fixtures/custom/customer-found.json", listOf("customer")),
        CUSTOMER_NOT_FOUND("test-fixtures/custom/customer-not-found.json", emptyList()),
        CUSTOMER_CREATE_SUCCESS("test-fixtures/custom/customer-create-success.json", listOf("customer")),
        CART_RESPONSE("test-fixtures/custom/cart-response.json", listOf("cart")),
        CHECKOUT_SUCCESS("test-fixtures/custom/checkout-success.json", listOf("bill")),
        CHECKOUT_FAIL("test-fixtures/custom/checkout-fail.json", listOf("message")),
        ANTITHEFT_MIXED("test-fixtures/custom/antitheft-scan-mixed.json", listOf("ok")),
        DIAGNOSTICS_SUCCESS("test-fixtures/custom/diagnostics-upload-success.json", listOf("ok")),
    }

    private fun requireFixtureObject(op: FixtureOp, corrId: String): JsonObject {
        return runCatching {
            val obj = FixtureJsonLoader.loadJsonObject(op.path)
            FixtureContractValidator.validateObjectHasKeys(
                obj = obj,
                requiredKeys = op.requiredKeys,
                fixturePathForErrors = op.path,
            )
            obj
        }.getOrElse { e ->
            throw AdapterError.ServerError(
                "Test fixture load/parse failed for '${op.path}' (Correlation-Id=$corrId)",
                e
            )
        }
    }

    override fun startAuth(request: AuthRequest, captchaToken: String?): Credential {
        validateCaptchaForLogin(captchaToken)
        if (!captchaToken.isNullOrBlank()) lastCaptchaToken = captchaToken

        if (testModeEnabled()) {
            val corrId = correlationId()
            if (FeatureFlags.FORCE_AUTH_FAILURE_FIXTURE) {
                val failObj = requireFixtureObject(FixtureOp.AUTH_FAIL, corrId)
                val msg = failObj.get("message")?.asString ?: "Auth failed (test fixture)"
                throw AdapterError.AuthError("$msg (Correlation-Id=$corrId)")
            }
            val obj = requireFixtureObject(FixtureOp.AUTH_SUCCESS, corrId)
            val cred = parseCredential(obj)
            credentialStore.set(cred)
            return cred
        }

        return when (request) {
            is DirectCredentialAuth -> loginWithPassword(request.username, request.password, captchaToken)
            is OAuthAuth -> loginWithOAuth(request.code, request.redirectUri, captchaToken)
        }
    }

    override fun refreshToken(): Credential {
        if (testModeEnabled()) {
            val corrId = correlationId()
            if (FeatureFlags.FORCE_AUTH_FAILURE_FIXTURE) {
                val failObj = requireFixtureObject(FixtureOp.AUTH_FAIL, corrId)
                val msg = failObj.get("message")?.asString ?: "Refresh failed (test fixture)"
                throw AdapterError.AuthError("$msg (Correlation-Id=$corrId)")
            }
            val obj = requireFixtureObject(FixtureOp.AUTH_SUCCESS, corrId)
            val cred = parseCredential(obj)
            credentialStore.set(cred)
            return cred
        }

        val captchaEnabled = AppConfig.CaptchaConfig.CAPTCHA_ENABLED
        val requireOnRefresh = captchaEnabled && AppConfig.CaptchaConfig.REQUIRE_CAPTCHA_ON_REFRESH
        val tokenToUse = if (requireOnRefresh) lastCaptchaToken else null
        return refreshTokenInternal(captchaToken = tokenToUse)
    }

    override fun revoke(): Boolean {
        if (testModeEnabled()) {
            credentialStore.clear()
            return true
        }

        val current = credentialStore.get()
        val refreshToken = current?.refreshToken

        val ok = try {
            if (refreshToken.isNullOrBlank()) {
                true
            } else {
                val body = jsonObj("refreshToken" to refreshToken)
                postJsonForJson(
                    path = ApiPathCustom.AUTH_REVOKE,
                    authHeader = null,
                    body = body,
                )
                true
            }
        } catch (_: Exception) {
            // Best-effort revoke (network/revoke failures shouldn't crash callers).
            false
        }

        credentialStore.clear()
        return ok
    }

    override fun getValidCredential(captchaToken: String?): Credential {
        if (testModeEnabled()) {
            val current = credentialStore.get() ?: throw AdapterError.AuthError("Not authenticated")

            val now = System.currentTimeMillis()
            val expiresAt = current.expiresAtEpochMs
            val nearExpired = expiresAt != null && expiresAt - now < 30_000

            if (expiresAt == null || (!nearExpired && now < expiresAt)) {
                return current
            }

            val effectiveCaptchaToken = captchaToken ?: lastCaptchaToken
            val captchaEnabled = AppConfig.CaptchaConfig.CAPTCHA_ENABLED
            val requireOnRefresh = captchaEnabled && AppConfig.CaptchaConfig.REQUIRE_CAPTCHA_ON_REFRESH

            if (requireOnRefresh) {
                validateCaptchaForRefresh(effectiveCaptchaToken)
            }
            return refreshToken()
        }

        // Phase 3 spec: getValidCredential refreshes when expired/near-expired without UI managing.
        val current = credentialStore.get()
        if (current == null) throw AdapterError.AuthError("Not authenticated")

        val now = System.currentTimeMillis()
        val expiresAt = current.expiresAtEpochMs
        val nearExpired = expiresAt != null && expiresAt - now < 30_000

        if (expiresAt == null || (!nearExpired && now < expiresAt)) {
            return current
        }

        val effectiveCaptchaToken = captchaToken ?: lastCaptchaToken
        val captchaEnabled = AppConfig.CaptchaConfig.CAPTCHA_ENABLED
        val requireOnRefresh = captchaEnabled && AppConfig.CaptchaConfig.REQUIRE_CAPTCHA_ON_REFRESH

        return if (requireOnRefresh) {
            validateCaptchaForRefresh(effectiveCaptchaToken)
            refreshTokenInternal(effectiveCaptchaToken)
        } else {
            // CAPTCHA is optional/disabled for refresh.
            refreshTokenInternal(captchaToken = null)
        }
    }

    override fun peekCredential(): Credential? = credentialStore.get()

    override fun healthCheck(): HealthResult {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.HEALTH_OK, corrId)
            return HealthResult(
                healthy = obj.get("healthy")?.asBoolean ?: true,
                statusMessage = obj.get("message")?.asString ?: "TEST_MODE_OK",
            )
        }

        val corrId = correlationId()
        val url = joinUrl(ApiPathCustom.HEALTH_STATUS)

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .get()
            .build()

        execute(req).use { resp ->
            val code = resp.code

            if (code in 200..299) {
                val json = resp.body?.string().orEmpty()
                val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: JsonObject()
                val healthy = obj.get("healthy")?.asBoolean ?: true
                val msg = obj.get("message")?.asString
                return HealthResult(healthy = healthy, statusMessage = msg)
            }

            when (code) {
                401, 403 -> throw AdapterError.AuthError("Unauthorized health check")
                in 400..499 -> throw AdapterError.ServerError("Health check failed (code=$code)")
                else -> throw AdapterError.ServerError("Health check server error (code=$code)")
            }
        }
    }

    override fun fetchProductsFull(providerConnectionId: String): List<Product> {
        return fetchCatalogFull(providerConnectionId).products
    }

    override fun fetchCatalogFull(providerConnectionId: String): ProductCatalogSnapshot {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.PRODUCTS_FULL, corrId)
            return mapCatalogFromFixture(providerConnectionId, obj)
        }

        val corrId = correlationId()
        val url = joinUrl(ApiPathCustom.PRODUCTS_FULL)
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .get()
            .build()

        return execute(req).use { resp ->
            mapCatalogFromBody(providerConnectionId, resp)
        }
    }

    override fun fetchProductsDelta(providerConnectionId: String, updatedSince: Long): List<Product> {
        return fetchCatalogDelta(providerConnectionId, updatedSince).products
    }

    override fun fetchCatalogDelta(providerConnectionId: String, updatedSince: Long): ProductCatalogSnapshot {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.PRODUCTS_DELTA, corrId)
            return mapCatalogFromFixture(providerConnectionId, obj)
        }

        val url = joinUrl(ApiPathCustom.PRODUCTS_DELTA) + "?updatedSince=$updatedSince"
        val corrId = correlationId()
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .get()
            .build()

        return execute(req).use { resp ->
            mapCatalogFromBody(providerConnectionId, resp)
        }
    }

    override fun searchProducts(providerConnectionId: String, query: String): List<Product> {
        if (testModeEnabled()) {
            // Keep correlation-id behavior aligned with live searchProducts().
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.PRODUCTS_FULL, corrId)
            return mapCatalogFromFixture(providerConnectionId, obj).products
        }

        val url = joinUrl(ApiPathCustom.PRODUCT_BY_QUERY) + "?q=${urlEncode(query)}"
        val corrId = correlationId()
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .get()
            .build()

        return execute(req).use { resp ->
            mapProductsFromBody(providerConnectionId, resp)
        }
    }

    override fun registerEpc(
        providerConnectionId: String,
        epc: String,
        productId: String?
    ): ProductEpc {
        val epcNorm = normalizeEpc(epc)

        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.REGISTER_EPC_SUCCESS, corrId)
            val mapped = mapProductEpc(providerConnectionId, obj, epcNorm)
            val merged = if (productId.isNullOrBlank()) {
                mapped
            } else if (mapped.productId == "unknown") {
                mapped.copy(productId = productId)
            } else {
                mapped
            }
            TestModeStateStore.upsertRegisteredEpc(providerConnectionId, merged)

            return merged
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"
        val url = joinUrl(ApiPathCustom.REGISTER_PRODUCT_EPC)

        val corrId = correlationId()
        val body = jsonObj(
            "epc" to epcNorm,
            "productId" to productId,
        )

        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .post(requestBody)
            .build()

        return execute(req).use { resp ->
            if (resp.isSuccessful) {
                val json = resp.body?.string().orEmpty()
                val respJson = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: JsonObject()
                mapProductEpc(providerConnectionId, respJson, epcNorm)
            } else if (resp.code == 409) {
                // Idempotency: treat "already registered" as success when backend indicates so.
                val json = resp.body?.string().orEmpty()
                val respJson = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
                val msg = respJson?.get("message")?.asString
                    ?: respJson?.get("error")?.asString
                    ?: json

                val alreadyRegistered =
                    msg.contains("already", ignoreCase = true) &&
                        msg.contains("registered", ignoreCase = true)

                if (!alreadyRegistered) throw mapHttpError(resp)

                val mapped = mapProductEpc(
                    providerConnectionId = providerConnectionId,
                    respJson = respJson ?: JsonObject(),
                    epcNorm = epcNorm,
                )
                // Ensure we preserve caller-provided productId if backend didn't return it.
                return@use if (productId.isNullOrBlank()) {
                    mapped
                } else if (mapped.productId == "unknown") {
                    mapped.copy(productId = productId)
                } else {
                    mapped
                }
            } else {
                throw mapHttpError(resp)
            }
        }
    }

    override fun pushInventorySession(
        providerConnectionId: String,
        payload: InventoryPushPayload,
        idempotencyKey: String,
        correlationId: String?,
    ) {
        if (testModeEnabled()) {
            val corrId = correlationId ?: correlationId()
            val op =
                if (payload.productStates.any { it.status != "matched" }) {
                    FixtureOp.INVENTORY_PUSH_MISMATCH
                } else {
                    FixtureOp.INVENTORY_PUSH_MATCH
                }

            // Ensure the fixture exists and is valid JSON.
            requireFixtureObject(op, corrId)
            TestModeStateStore.recordInventoryPushIfNeeded(
                providerConnectionId = providerConnectionId,
                sessionId = payload.sessionId,
                idempotencyKey = idempotencyKey,
                payload = payload,
            )
            return
        }

        val url = joinUrl(ApiPathCustom.INVENTORY_PUSH_SESSION)
        val corrId = correlationId ?: correlationId()
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val jsonBody = gson.toJson(payload)
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .addHeader(ApiCommonConfig.IDEMPOTENCY_HEADER_KEY, idempotencyKey)
            .post(body)
            .build()

        execute(req).use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)

            val json = resp.body?.string().orEmpty()
            if (json.isBlank()) return
            val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return
            val remoteMarker = obj.get("remoteSnapshotMarker")?.asString
                ?: obj.get("catalogSnapshotMarker")?.asString
                ?: obj.get("snapshotMarker")?.asString
            if (!remoteMarker.isNullOrBlank() &&
                !payload.catalogSnapshotMarker.isNullOrBlank() &&
                remoteMarker != payload.catalogSnapshotMarker
            ) {
                throw AdapterError.ConflictError("Remote snapshot marker drift detected")
            }
            val explicitConflict = obj.get("conflict")?.asBoolean ?: false
            if (explicitConflict) {
                throw AdapterError.ConflictError("Backend reported inventory conflict")
            }
        }
    }

    override fun lookupCustomer(
        providerConnectionId: String,
        query: CustomerLookupQuery
    ): Customer? {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val fromState = TestModeStateStore.findCustomer(
                providerConnectionId = providerConnectionId,
                id = query.id,
                phone = query.phone,
                email = query.email,
            )
            if (fromState != null) return fromState
            requireFixtureObject(FixtureOp.CUSTOMER_NOT_FOUND, corrId)
            return null
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val bodyObj = jsonObj(
            "id" to query.id,
            "phone" to query.phone,
            "email" to query.email,
        )

        val respJson = postJsonForJson(
            path = ApiPathCustom.CUSTOMER_LOOKUP,
            authHeader = auth,
            body = bodyObj,
        )

        val customerJson = respJson.getAsJsonObject("customer") ?: return null
        return mapCustomer(providerConnectionId, customerJson)
    }

    override fun createCustomer(providerConnectionId: String, payload: CustomerCreatePayload): Customer {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.CUSTOMER_CREATE_SUCCESS, corrId)
            val customerJson = obj.getAsJsonObject("customer") ?: obj
            val created = mapCustomer(providerConnectionId, customerJson)
            TestModeStateStore.upsertCustomer(providerConnectionId, created)
            return created
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val bodyObj = gson.toJsonTree(payload).asJsonObject
        val respJson = postJsonForJson(
            path = ApiPathCustom.CUSTOMER_CREATE,
            authHeader = auth,
            body = bodyObj,
        )

        val customerJson = respJson.getAsJsonObject("customer") ?: respJson
        return mapCustomer(providerConnectionId, customerJson)
    }

    override fun createCartFromEpcs(providerConnectionId: String, payload: CartFromEpcsPayload): Cart {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.CART_RESPONSE, corrId)
            val baseCart = mapCart(providerConnectionId, obj)
            val seededLines = payload.epcs.mapIndexed { _, epc ->
                com.rfidsoftwares.integration.models.CartLineItem(
                    productId = epc.trim().uppercase(Locale.US),
                    quantity = 1,
                    title = "From EPC",
                    meta = null,
                )
            }
            val cart = baseCart.copy(lineItems = if (seededLines.isEmpty()) baseCart.lineItems else seededLines)
            return TestModeStateStore.createOrReplaceCart(providerConnectionId, cart)
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val bodyObj = gson.toJsonTree(payload).asJsonObject
        val respJson = postJsonForJson(
            path = ApiPathCustom.CART_FROM_EPCS,
            authHeader = auth,
            body = bodyObj,
        )

        return mapCart(providerConnectionId, respJson)
    }

    override fun modifyCart(providerConnectionId: String, payload: CartModifyPayload): Cart {
        if (testModeEnabled()) {
            val corrId = correlationId()
            val obj = requireFixtureObject(FixtureOp.CART_RESPONSE, corrId)
            val fallbackCart = mapCart(providerConnectionId, obj).copy(id = payload.cartId)
            return TestModeStateStore.applyCartModify(providerConnectionId, payload, fallbackCart)
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val bodyObj = gson.toJsonTree(payload).asJsonObject
        val respJson = postJsonForJson(
            path = ApiPathCustom.CART_MODIFY,
            authHeader = auth,
            body = bodyObj,
        )

        return mapCart(providerConnectionId, respJson)
    }

    override fun generateCheckoutBill(
        providerConnectionId: String,
        payload: CheckoutBillPayload,
        idempotencyKey: String
    ): CheckoutBill {
        if (testModeEnabled()) {
            val corrId = correlationId()
            if (FeatureFlags.FORCE_CHECKOUT_FAILURE_FIXTURE) {
                val failObj = requireFixtureObject(FixtureOp.CHECKOUT_FAIL, corrId)
                val msg = failObj.get("message")?.asString ?: "Checkout failed (test fixture)"
                throw AdapterError.ValidationError("$msg (Correlation-Id=$corrId)")
            }
            val obj = requireFixtureObject(FixtureOp.CHECKOUT_SUCCESS, corrId)
            val checkoutBill = mapCheckoutBill(providerConnectionId, obj)
            TestModeStateStore.addCheckoutBill(providerConnectionId, checkoutBill)

            val tagUpdates = extractTagUpdatesFromCheckoutResponse(obj)
            if (tagUpdates.isNotEmpty()) {
                antiTheftUpdateTags(
                    providerConnectionId = providerConnectionId,
                    payload = AntiTheftUpdatePayload(
                        providerConnectionId = providerConnectionId,
                        tagsToUpdate = tagUpdates,
                    ),
                    idempotencyKey = idempotencyKey,
                )
            }

            return checkoutBill
        }

        val url = joinUrl(ApiPathCustom.CHECKOUT_GENERATE_BILL)
        val corrId = correlationId()
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val jsonBody = gson.toJson(payload)
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .addHeader(ApiCommonConfig.IDEMPOTENCY_HEADER_KEY, idempotencyKey)
            .post(body)
            .build()

        return execute(req).use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)

            val json = resp.body?.string().orEmpty()
            val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: JsonObject()

            // Phase 3 responsibility: after successful billing, if the backend provides billed tags,
            // call the anti-theft endpoint hook to update EPC/tag state.
            val checkoutBill = mapCheckoutBill(providerConnectionId, obj)
            val tagUpdates = extractTagUpdatesFromCheckoutResponse(obj)
            if (tagUpdates.isNotEmpty()) {
                antiTheftUpdateTags(
                    providerConnectionId = providerConnectionId,
                    payload = AntiTheftUpdatePayload(
                        providerConnectionId = providerConnectionId,
                        tagsToUpdate = tagUpdates,
                    ),
                    idempotencyKey = idempotencyKey,
                )
            }

            checkoutBill
        }
    }

    private fun extractTagUpdatesFromCheckoutResponse(obj: JsonObject): List<TagUpdate> {
        val tagsArr = obj.getAsJsonArray("tagsToUpdate")
            ?: obj.getAsJsonArray("tags")
            ?: obj.getAsJsonArray("epcs")
            ?: return emptyList()

        val result = ArrayList<TagUpdate>(tagsArr.size())
        for (i in 0 until tagsArr.size()) {
            val t = tagsArr[i].asJsonObject
            val epcValue = t.get("epc")?.asString ?: t.get("tagEpc")?.asString
            if (epcValue.isNullOrBlank()) continue

            val billedState = t.get("billedState")?.asString ?: t.get("state")?.asString
            val meta = t.get("metaData")?.let { gson.toJson(it) } ?: t.get("meta")?.let { gson.toJson(it) }

            result.add(
                TagUpdate(
                    epc = normalizeEpc(epcValue),
                    billedState = billedState,
                    meta = meta,
                )
            )
        }
        return result
    }

    override fun antiTheftUpdateTags(
        providerConnectionId: String,
        payload: AntiTheftUpdatePayload,
        idempotencyKey: String,
        correlationId: String?,
    ): Boolean {
        if (testModeEnabled()) {
            val corrId = correlationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            requireFixtureObject(FixtureOp.ANTITHEFT_MIXED, corrId)
            return true
        }

        val url = joinUrl(ApiPathCustom.ANTI_THEFT_UPDATE_TAGS)
        val corrId = correlationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val jsonBody = gson.toJson(payload)
        val body = jsonBody.toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .addHeader("Authorization", auth)
            .addHeader(ApiCommonConfig.IDEMPOTENCY_HEADER_KEY, idempotencyKey)
            .post(body)
            .build()

        execute(req).use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)
        }
        return true
    }

    override fun uploadDiagnostics(
        providerConnectionId: String,
        payload: DiagnosticsUploadPayload
    ): Boolean {
        if (testModeEnabled()) {
            val corrId = correlationId()
            requireFixtureObject(FixtureOp.DIAGNOSTICS_SUCCESS, corrId)
            return true
        }

        val auth = "Bearer ${getValidCredential(null).accessToken}"

        val bodyObj = gson.toJsonTree(payload).asJsonObject
        postJsonForJson(
            path = ApiPathCustom.RFID_DIAGNOSTICS_UPLOAD,
            authHeader = auth,
            body = bodyObj,
        )
        return true
    }

    private fun validateCaptchaForLogin(captchaToken: String?) {
        if (!AppConfig.CaptchaConfig.CAPTCHA_ENABLED) return
        if (!AppConfig.CaptchaConfig.REQUIRE_CAPTCHA_ON_LOGIN) return
        if (captchaToken.isNullOrBlank()) {
            throw AdapterError.ValidationError("CAPTCHA is enabled but token is missing.")
        }
    }

    private fun validateCaptchaForRefresh(captchaToken: String?) {
        if (!AppConfig.CaptchaConfig.CAPTCHA_ENABLED) return
        if (!AppConfig.CaptchaConfig.REQUIRE_CAPTCHA_ON_REFRESH) return
        if (captchaToken.isNullOrBlank()) {
            throw AdapterError.ValidationError("CAPTCHA is enabled but token is missing.")
        }
    }

    private fun loginWithPassword(username: String, password: String, captchaToken: String?): Credential {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
            if (!captchaToken.isNullOrBlank()) addProperty("captchaToken", captchaToken)
        }
        val respJson = postJsonForJson(
            path = ApiPathCustom.AUTH_LOGIN,
            authHeader = null,
            body = body,
        )
        val cred = parseCredential(respJson)
        credentialStore.set(cred)
        return cred
    }

    private fun loginWithOAuth(code: String, redirectUri: String, captchaToken: String?): Credential {
        // Phase 3 auth mapping: backend contract decides shape; we send a generic payload.
        val body = JsonObject().apply {
            addProperty("code", code)
            addProperty("redirectUri", redirectUri)
            if (!captchaToken.isNullOrBlank()) addProperty("captchaToken", captchaToken)
        }
        val respJson = postJsonForJson(
            path = ApiPathCustom.AUTH_LOGIN,
            authHeader = null,
            body = body,
        )
        val cred = parseCredential(respJson)
        credentialStore.set(cred)
        return cred
    }

    private fun refreshTokenInternal(captchaToken: String?): Credential {
        val current = credentialStore.get() ?: throw AdapterError.AuthError("No credential to refresh")
        val refreshToken = current.refreshToken ?: throw AdapterError.AuthError("Missing refresh token")

        // Backend contract may optionally accept captchaToken. We only send it when provided.
        val body = JsonObject().apply {
            addProperty("refreshToken", refreshToken)
            if (!captchaToken.isNullOrBlank()) addProperty("captchaToken", captchaToken)
        }

        return try {
            val respJson = postJsonForJson(
                path = ApiPathCustom.AUTH_REFRESH,
                authHeader = null,
                body = body,
            )

            val newCred = parseCredential(respJson)
            credentialStore.set(newCred)
            AuthRefreshTelemetry.recordSuccess()
            newCred
        } catch (e: AdapterError) {
            AuthRefreshTelemetry.recordFailure(e.message)
            throw e
        } catch (e: Exception) {
            AuthRefreshTelemetry.recordFailure(e.message)
            throw e
        }
    }

    private fun parseCredential(obj: JsonObject): Credential {
        // Contract placeholder: expected keys: accessToken, refreshToken, expiresAtEpochMs.
        val accessToken = obj.get("accessToken")?.asString
            ?: throw AdapterError.ServerError("Missing accessToken in auth response")
        val refreshToken = obj.get("refreshToken")?.asString
        val expiresAt = obj.get("expiresAtEpochMs")?.asLong
        return Credential(accessToken, refreshToken, expiresAt)
    }

    private fun mapProductsFromBody(providerConnectionId: String, response: Response): List<Product> {
        if (!response.isSuccessful) throw mapHttpError(response)

        val json = response.body?.string().orEmpty()
        if (json.isBlank()) return emptyList()

        val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrNull()
            ?: throw AdapterError.ServerError("Invalid product JSON response")

        val productsArray = obj.getAsJsonArray("products") ?: JsonArray()
        return mapProducts(providerConnectionId, productsArray)
    }

    private fun mapCatalogFromBody(providerConnectionId: String, response: Response): ProductCatalogSnapshot {
        if (!response.isSuccessful) throw mapHttpError(response)

        val json = response.body?.string().orEmpty()
        if (json.isBlank()) return ProductCatalogSnapshot(products = emptyList(), productEpcs = emptyList())

        val obj = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrNull()
            ?: throw AdapterError.ServerError("Invalid catalog JSON response")

        val productsArray = obj.getAsJsonArray("products") ?: JsonArray()
        val products = mapProducts(providerConnectionId, productsArray)

        val productEpcs = mapProductEpcsFromCatalogJson(providerConnectionId, obj, productsArray)
        return ProductCatalogSnapshot(products = products, productEpcs = productEpcs)
    }

    private fun mapProductEpcsFromCatalogJson(
        providerConnectionId: String,
        catalogObj: JsonObject,
        productsArray: JsonArray,
    ): List<ProductEpc> {
        val result = ArrayList<ProductEpc>()

        // Root-level epc mapping list.
        val rootEpcs = catalogObj.getAsJsonArray("epcs")
            ?: catalogObj.getAsJsonArray("productEpcs")
        if (rootEpcs != null) {
            for (i in 0 until rootEpcs.size()) {
                val e = rootEpcs[i].asJsonObject
                val epcValue = e.get("epc")?.asString ?: e.get("tagEpc")?.asString
                if (epcValue.isNullOrBlank()) continue
                result.add(
                    ProductEpc(
                        epc = normalizeEpc(epcValue),
                        providerConnectionId = providerConnectionId,
                        productId = e.get("productId")?.asString ?: e.get("product_id")?.asString ?: "unknown",
                        state = e.get("state")?.asString ?: e.get("status")?.asString ?: "active",
                        createdAtEpochMs = e.get("createdAtEpochMs")?.asLong ?: e.get("createdAt")?.asLong
                            ?: System.currentTimeMillis(),
                        updatedAtEpochMs = e.get("updatedAtEpochMs")?.asLong ?: e.get("updatedAt")?.asLong
                            ?: System.currentTimeMillis(),
                        meta = e.get("metaData")?.let { gson.toJson(it) } ?: e.get("meta")?.let { gson.toJson(it) },
                    )
                )
            }
        }

        // Nested epc mapping list under each product.
        for (i in 0 until productsArray.size()) {
            val p = productsArray[i].asJsonObject
            val parentProductId = p.get("id")?.asString ?: p.get("productId")?.asString ?: p.get("product_id")?.asString
            if (parentProductId.isNullOrBlank()) continue

            val nestedEpcs =
                p.getAsJsonArray("epcs")
                    ?: p.getAsJsonArray("productEpcs")
                    ?: p.getAsJsonArray("variants")
            if (nestedEpcs == null) continue

            for (j in 0 until nestedEpcs.size()) {
                val e = nestedEpcs[j].asJsonObject
                val epcValue = e.get("epc")?.asString ?: e.get("tagEpc")?.asString ?: ""
                if (epcValue.isBlank()) continue

                result.add(
                    ProductEpc(
                        epc = normalizeEpc(epcValue),
                        providerConnectionId = providerConnectionId,
                        productId = e.get("productId")?.asString ?: e.get("product_id")?.asString ?: parentProductId,
                        state = e.get("state")?.asString ?: e.get("status")?.asString ?: "active",
                        createdAtEpochMs = e.get("createdAtEpochMs")?.asLong ?: e.get("createdAt")?.asLong
                            ?: System.currentTimeMillis(),
                        updatedAtEpochMs = e.get("updatedAtEpochMs")?.asLong ?: e.get("updatedAt")?.asLong
                            ?: System.currentTimeMillis(),
                        meta = e.get("metaData")?.let { gson.toJson(it) } ?: e.get("meta")?.let { gson.toJson(it) },
                    )
                )
            }
        }

        return result
    }

    private fun mapProducts(providerConnectionId: String, productsArray: JsonArray): List<Product> {
        val result = ArrayList<Product>(productsArray.size())
        for (i in 0 until productsArray.size()) {
            val p = productsArray[i].asJsonObject
            val id = p.get("id")?.asString ?: p.get("productId")?.asString ?: "unknown"
            val sku = p.get("sku")?.asString
            val name = p.get("name")?.asString ?: id
            val barcode = p.get("barcodePrimary")?.asString
            val status = p.get("status")?.asString ?: "unknown"
            val updatedAt = p.get("updatedAtEpochMs")?.asLong
                ?: p.get("updatedAt")?.asLong
                ?: System.currentTimeMillis()
            val image = p.get("image")?.asString
            val description = p.get("description")?.asString

            val meta = p.get("meta")?.let { gson.toJson(it) }
            result.add(
                Product(
                    id = id,
                    providerConnectionId = providerConnectionId,
                    sku = sku,
                    name = name,
                    barcodePrimary = barcode,
                    status = status,
                    updatedAtEpochMs = updatedAt,
                    image = image,
                    description = description,
                    meta = meta,
                )
            )
        }
        return result
    }

    private fun mapProductEpc(providerConnectionId: String, respJson: JsonObject, epcNorm: String): ProductEpc {
        val epc = respJson.get("epc")?.asString ?: epcNorm
        val productId = respJson.get("productId")?.asString ?: "unknown"
        val state = respJson.get("state")?.asString ?: "active"
        val createdAt = respJson.get("createdAtEpochMs")?.asLong ?: System.currentTimeMillis()
        val updatedAt = respJson.get("updatedAtEpochMs")?.asLong ?: System.currentTimeMillis()
        return ProductEpc(
            epc = normalizeEpc(epc),
            providerConnectionId = providerConnectionId,
            productId = productId,
            state = state,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = updatedAt,
        )
    }

    private fun mapCustomer(providerConnectionId: String, json: JsonObject): Customer {
        val id = json.get("id")?.asString ?: json.get("customerId")?.asString ?: "unknown"
        val phone = json.get("phone")?.asString
        val email = json.get("email")?.asString
        val name = json.get("name")?.asString
        val meta = json.get("meta")?.let { gson.toJson(it) }
        return Customer(
            id = id,
            providerConnectionId = providerConnectionId,
            phone = phone,
            email = email,
            name = name,
            meta = meta,
        )
    }

    private fun mapCart(providerConnectionId: String, respJson: JsonObject): Cart {
        val cartJson = respJson.getAsJsonObject("cart") ?: respJson
        val id = cartJson.get("id")?.asString ?: "cart"
        val total = cartJson.getAsJsonObject("total")
        val totalMoney = total?.let {
            Money(
                currency = it.get("currency")?.asString,
                amount = it.get("amount")?.asDouble,
            )
        }
        val linesArr = cartJson.getAsJsonArray("lineItems") ?: JsonArray()
        val lines = ArrayList<CartLineItem>(linesArr.size())
        for (i in 0 until linesArr.size()) {
            val li = linesArr[i].asJsonObject
            lines.add(
                CartLineItem(
                    productId = li.get("productId")?.asString,
                    quantity = li.get("quantity")?.asInt ?: 0,
                    title = li.get("title")?.asString,
                    meta = li.get("meta")?.let { gson.toJson(it) },
                )
            )
        }
        return Cart(id = id, providerConnectionId = providerConnectionId, lineItems = lines, total = totalMoney)
    }

    private fun mapCheckoutBill(providerConnectionId: String, respJson: JsonObject): CheckoutBill {
        val billJson = respJson.getAsJsonObject("bill") ?: respJson
        val billId = billJson.get("billId")?.asString ?: "bill"
        val status = billJson.get("status")?.asString
        val amountJson = billJson.getAsJsonObject("amount")
        val amount = amountJson?.let {
            Money(currency = it.get("currency")?.asString, amount = it.get("amount")?.asDouble)
        }
        return CheckoutBill(
            billId = billId,
            providerConnectionId = providerConnectionId,
            amount = amount,
            status = status,
        )
    }

    private fun normalizeEpc(epc: String): String =
        epc.trim().uppercase(Locale.US)

    private fun correlationId(): String = UUID.randomUUID().toString()

    private fun joinUrl(path: String): String {
        return if (baseUrl.endsWith("/") && path.startsWith("/")) baseUrl.dropLast(1) + path
        else if (!baseUrl.endsWith("/") && !path.startsWith("/")) baseUrl + "/" + path
        else baseUrl + path
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun jsonObj(vararg pairs: Pair<String, Any?>): JsonObject {
        val obj = JsonObject()
        for ((k, v) in pairs) {
            when (v) {
                null -> obj.add(k, com.google.gson.JsonNull.INSTANCE)
                is String -> obj.addProperty(k, v)
                is Long -> obj.addProperty(k, v)
                is Int -> obj.addProperty(k, v)
                is Double -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
                else -> obj.add(k, gson.toJsonTree(v))
            }
        }
        return obj
    }

    private fun execute(req: Request): Response {
        return try {
            httpClient.newCall(req).execute()
        } catch (e: IOException) {
            throw AdapterError.NetworkError("Network error", e)
        }
    }

    private fun mapHttpError(resp: Response): AdapterError {
        val code = resp.code
        val respCorr = resp.header(ApiCommonConfig.CORRELATION_ID_HEADER_KEY)
            ?: resp.header("X-Correlation-ID")
            ?: resp.header("x-correlation-id")
        val body = resp.body?.string().orEmpty()
        val msg = runCatching {
            val obj = gson.fromJson(body, JsonObject::class.java)
            obj.get("message")?.asString ?: obj.get("error")?.asString
        }.getOrNull()

        return when (code) {
            401, 403 -> AdapterError.AuthError(msg ?: "Authentication error", responseCorrelationId = respCorr)
            409 -> AdapterError.ConflictError(msg ?: "Conflict (code=$code)", responseCorrelationId = respCorr)
            422 -> AdapterError.ValidationError(msg ?: "Validation error", responseCorrelationId = respCorr)
            404 -> AdapterError.ValidationError(msg ?: "Not found (code=$code)", responseCorrelationId = respCorr)
            in 400..499 -> AdapterError.ValidationError(
                msg ?: "Request validation failed (code=$code)",
                responseCorrelationId = respCorr,
            )
            in 500..599 -> AdapterError.ServerError(msg ?: "Server error (code=$code)", responseCorrelationId = respCorr)
            else -> AdapterError.ServerError(msg ?: "HTTP error (code=$code)", responseCorrelationId = respCorr)
        }
    }

    private fun postJsonForJson(path: String, authHeader: String?, body: JsonObject): JsonObject {
        val url = joinUrl(path)
        val corrId = correlationId()
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader(ApiCommonConfig.CORRELATION_ID_HEADER_KEY, corrId)
            .post(requestBody)

        if (!authHeader.isNullOrBlank()) {
            reqBuilder.addHeader("Authorization", authHeader)
        }

        return execute(reqBuilder.build()).use { resp ->
            if (!resp.isSuccessful) throw mapHttpError(resp)

            val json = resp.body?.string().orEmpty()
            if (json.isBlank()) JsonObject()
            else runCatching { gson.fromJson(json, JsonObject::class.java) }
                .getOrNull()
                ?: throw AdapterError.ServerError("Invalid JSON response")
        }
    }
}

