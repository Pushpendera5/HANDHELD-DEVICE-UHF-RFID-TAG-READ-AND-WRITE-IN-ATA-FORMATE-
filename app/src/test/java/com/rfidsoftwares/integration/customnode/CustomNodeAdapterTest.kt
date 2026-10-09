package com.rfidsoftwares.integration.customnode

import com.rfidsoftwares.integration.error.AdapterError
import com.rfidsoftwares.integration.error.AdapterError.AuthError
import com.rfidsoftwares.integration.models.Credential
import com.rfidsoftwares.integration.models.InventoryPushPayload
import com.rfidsoftwares.integration.models.ProductStatePush
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CustomNodeAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var adapter: CustomNodeAdapter

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        credentialStore = InMemoryCredentialStore().apply {
            set(Credential(accessToken = "token-abc", refreshToken = "r1", expiresAtEpochMs = null))
        }
        adapter = CustomNodeAdapter(
            baseUrl = server.url("/").toString(),
            credentialStore = credentialStore,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun pushInventorySession_http401_mapsResponseCorrelationId() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Correlation-Id", "trace-from-server")
                .setBody("""{"message":"expired"}""")
        )
        val payload = InventoryPushPayload(
            sessionId = "s1",
            providerConnectionId = "custom_node",
            operatorId = "op",
            locationId = "loc",
            catalogSnapshotMarker = "p:1|e:2",
            productStates = listOf(ProductStatePush("p1", 1, 1, "matched")),
        )
        try {
            adapter.pushInventorySession(
                providerConnectionId = "custom_node",
                payload = payload,
                idempotencyKey = "idem-x",
                correlationId = "client-sent",
            )
            fail("expected AuthError")
        } catch (e: AuthError) {
            assertEquals("trace-from-server", e.responseCorrelationId)
        }
    }

    @Test
    fun pushInventorySession_sendsIdempotencyAndProvidedCorrelationHeaders() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val payload = InventoryPushPayload(
            sessionId = "s1",
            providerConnectionId = "custom_node",
            operatorId = "op",
            locationId = "loc",
            catalogSnapshotMarker = "p:1|e:2",
            productStates = listOf(ProductStatePush("p1", 1, 1, "matched")),
        )

        adapter.pushInventorySession(
            providerConnectionId = "custom_node",
            payload = payload,
            idempotencyKey = "idem-123",
            correlationId = "corr-xyz",
        )

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        requireNotNull(request)
        assertEquals("/v1/inventory/sessions/push", request.path)
        assertEquals("corr-xyz", request.getHeader("Correlation-Id"))
        assertEquals("idem-123", request.getHeader("Idempotency-Key"))
        assertTrue((request.getHeader("Authorization") ?: "").startsWith("Bearer "))
    }

    @Test
    fun pushInventorySession_throwsConflictError_onRemoteSnapshotMarkerDrift() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true,"remoteSnapshotMarker":"p:999|e:999"}""")
        )
        val payload = InventoryPushPayload(
            sessionId = "s1",
            providerConnectionId = "custom_node",
            operatorId = "op",
            locationId = "loc",
            catalogSnapshotMarker = "p:1|e:2",
            productStates = listOf(ProductStatePush("p1", 1, 0, "mismatch")),
        )

        val result = runCatching {
            adapter.pushInventorySession(
                providerConnectionId = "custom_node",
                payload = payload,
                idempotencyKey = "idem-1",
                correlationId = "corr-1",
            )
        }.exceptionOrNull()

        assertTrue(result is AdapterError.ConflictError)
    }

    @Test
    fun healthCheck_mapsHealthyResponse() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"healthy":true,"message":"OK"}"""))

        val result = adapter.healthCheck()

        assertTrue(result.healthy)
        assertEquals("OK", result.statusMessage)
    }

    @Test
    fun fetchCatalogFull_mapsProductsAndEpcs() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "products":[{"id":"p1","name":"Demo","status":"active","updatedAtEpochMs":100}],
                  "epcs":[{"epc":"epc-1","productId":"p1","state":"active","createdAtEpochMs":1,"updatedAtEpochMs":2}]
                }
                """.trimIndent()
            )
        )

        val snapshot = adapter.fetchCatalogFull("custom_node")

        assertEquals(1, snapshot.products.size)
        assertEquals("p1", snapshot.products.first().id)
        assertEquals(1, snapshot.productEpcs.size)
        assertEquals("EPC-1", snapshot.productEpcs.first().epc)
    }

    @Test
    fun registerEpc_treatsAlreadyRegisteredConflictAsSuccess() {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody("""{"message":"EPC already registered","productId":"p1","epc":"epc-9"}""")
        )

        val mapped = adapter.registerEpc(
            providerConnectionId = "custom_node",
            epc = "epc-9",
            productId = "p1",
        )

        assertNotNull(mapped)
        assertEquals("EPC-9", mapped.epc)
    }
}

