package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.BindingCodeResponse
import io.engage.sdk.core.domain.BootstrapRequest
import io.engage.sdk.core.domain.DeviceMetadata
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.InstallationStateResponse
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationBatchRequest
import io.engage.sdk.core.domain.OperationBatchResponse
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationStatus
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OperationCoordinatorTest {
    private val session = InstallationSession(
        installationId = "installation-1",
        credential = "credential",
        revocationCredential = "revocation",
        recoveryToken = "recovery",
        generation = 3,
        privacy = PrivacyState.OPTED_IN,
        pushSubscription = "OPTED_IN",
        serverTime = "2026-08-02T10:00:00Z",
    )
    private val sessions = FakeSessionStore(session)
    private val outbox = FakeOutbox()
    private val api = FakeApi(session)
    private val coordinator = OperationCoordinator(
        endpoint = URI.create("https://edge.test/v1/"),
        appKey = "eng_app_test",
        metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
        sessions = sessions,
        outbox = outbox,
        api = api,
        clock = Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC),
        newId = { "operation-${outbox.operations.size + 1}" },
    )

    @Test
    fun `operation captures current generation and is removed after acknowledgement`() = runTest {
        val accepted = coordinator.enqueue(
            OperationType.EVENT_TRACKED,
            buildJsonObject { put("name", "checkout_started") },
        )

        assertTrue(accepted)
        assertEquals(3, outbox.operations.single().generation)

        coordinator.flush()

        assertTrue(outbox.operations.isEmpty())
        assertEquals("batch-1", api.sent.single().batchId)
    }

    @Test
    fun `business operation is ignored during privacy opt out`() = runTest {
        sessions.setPrivacy(PrivacyState.OPTED_OUT)

        val accepted = coordinator.enqueue(OperationType.EVENT_TRACKED, buildJsonObject {})

        assertFalse(accepted)
        assertTrue(outbox.operations.isEmpty())
    }

    private class FakeSessionStore(initial: InstallationSession?) : SessionStore {
        override val session = MutableStateFlow(initial)
        override val privacy = MutableStateFlow(initial?.privacy ?: PrivacyState.OPTED_IN)
        override suspend fun recoveryToken(): String? = session.value?.recoveryToken
        override suspend fun saveSession(session: InstallationSession) {
            this.session.value = session
            privacy.value = session.privacy
        }
        override suspend fun setPrivacy(state: PrivacyState) {
            privacy.value = state
        }
        override suspend fun clearSession() {
            session.value = null
        }
    }

    private class FakeOutbox : OperationOutbox {
        val operations = mutableListOf<SdkOperation>()
        private var reserved: ReservedOperationBatch? = null

        override suspend fun enqueue(operation: SdkOperation) {
            operations += operation
        }

        override suspend fun reserve(limit: Int, allowedTypes: Set<OperationType>?): ReservedOperationBatch? {
            reserved?.let { return it }
            val eligible = operations.filter { allowedTypes == null || it.type in allowedTypes }
            if (eligible.isEmpty()) return null
            return ReservedOperationBatch("batch-1", eligible.take(limit)).also { reserved = it }
        }

        override suspend fun settle(batchId: String, results: List<OperationResult>): Boolean {
            val ids = results.mapTo(mutableSetOf(), OperationResult::operationId)
            val changed = operations.removeAll { it.operationId in ids }
            reserved = null
            return changed
        }

        override suspend fun clear() {
            operations.clear()
            reserved = null
        }
    }

    private class FakeApi(private val bootstrapSession: InstallationSession) : MobileEdgeApi {
        val sent = mutableListOf<OperationBatchRequest>()
        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest) = bootstrapSession
        override suspend fun issueBindingCode(endpoint: URI, credential: String) =
            BindingCodeResponse("binding-code", "2026-08-02T10:20:00Z")

        override suspend fun getInstallation(endpoint: URI, credential: String) =
            InstallationStateResponse(
                "installation-1",
                3,
                "BOUND",
                PrivacyState.OPTED_IN,
                "OPTED_IN",
                true,
                "2026-08-02T10:00:00Z",
            )

        override suspend fun sendOperations(
            endpoint: URI,
            credential: String,
            batch: OperationBatchRequest,
        ): OperationBatchResponse {
            sent += batch
            return OperationBatchResponse(
                batchId = batch.batchId,
                results = batch.operations.map {
                    OperationResult(it.operationId, OperationStatus.ACCEPTED)
                },
                serverTime = "2026-08-02T10:15:31Z",
            )
        }

        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse =
            error("Not used")

        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) = Unit
    }
}
