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
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
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
    private var enqueueNotifications = 0
    private val coordinator = OperationCoordinator(
        endpoint = URI.create("https://edge.test/v1/"),
        appKey = "eng_app_test",
        metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
        sessions = sessions,
        outbox = outbox,
        api = api,
        clock = Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC),
        newId = { "operation-${outbox.operations.size + 1}" },
        onEnqueued = { enqueueNotifications += 1 },
    )

    @Test
    fun `operation captures current generation and is removed after acknowledgement`() = runTest {
        val accepted = coordinator.enqueue(
            OperationType.EVENT_TRACKED,
            buildJsonObject { put("name", "checkout_started") },
        )

        assertTrue(accepted)
        assertEquals(3, outbox.operations.single().generation)
        assertEquals(1, enqueueNotifications)

        coordinator.flush()

        assertTrue(outbox.operations.isEmpty())
        assertEquals("batch-1", api.sent.single().batchId)
    }

    @Test
    fun `public mutation returns only after durable outbox persistence`() = runTest {
        val persistenceGate = CompletableDeferred<Unit>()
        outbox.beforePersist = { persistenceGate.await() }
        val mutation = async {
            DefaultProfile(coordinator).editTags { add("vip") }
        }

        runCurrent()

        assertFalse(mutation.isCompleted)
        assertTrue(outbox.operations.isEmpty())

        persistenceGate.complete(Unit)
        mutation.await()

        assertEquals(OperationType.PROFILE_TAGS_EDITED, outbox.operations.single().type)
    }

    @Test
    fun `public mutation propagates durable outbox failure`() = runTest {
        val expected = IllegalStateException("disk unavailable")
        outbox.enqueueFailure = expected

        val actual = runCatching {
            DefaultProfile(coordinator).editTags { add("vip") }
        }.exceptionOrNull()

        assertEquals(expected, actual)
        assertTrue(outbox.operations.isEmpty())
    }

    @Test
    fun `business operation is ignored during privacy opt out`() = runTest {
        sessions.setPrivacy(PrivacyState.OPTED_OUT)

        val accepted = coordinator.enqueue(OperationType.EVENT_TRACKED, buildJsonObject {})

        assertFalse(accepted)
        assertTrue(outbox.operations.isEmpty())
        assertEquals(0, enqueueNotifications)
    }

    @Test
    fun `persisted opted out installation can replay its privacy operation after restart`() = runTest {
        val optedOutSession = session.copy(privacy = PrivacyState.OPTED_OUT)
        val optedOutSessions = FakeSessionStore(optedOutSession)
        val optedOutOutbox = FakeOutbox()
        val optedOutApi = FakeApi(optedOutSession)
        val optedOutCoordinator = OperationCoordinator(
            endpoint = URI.create("https://edge.test/v1/"),
            appKey = "eng_app_test",
            metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
            sessions = optedOutSessions,
            outbox = optedOutOutbox,
            api = optedOutApi,
            clock = Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC),
        )

        assertTrue(
            optedOutCoordinator.enqueue(
                type = OperationType.PRIVACY_STATE_SET,
                payload = buildJsonObject { put("state", "OPTED_OUT") },
                allowWhileOptedOut = true,
            ),
        )

        optedOutCoordinator.flush()

        assertTrue(optedOutOutbox.operations.isEmpty())
        assertEquals(OperationType.PRIVACY_STATE_SET, optedOutApi.sent.single().operations.single().type)
    }

    @Test
    fun `first launch queues durably before network bootstrap`() = runTest {
        val firstLaunchSessions = FakeSessionStore(null)
        val firstLaunchOutbox = FakeOutbox()
        val firstLaunchApi = FakeApi(session)
        val firstLaunchCoordinator = OperationCoordinator(
            endpoint = URI.create("https://edge.test/v1/"),
            appKey = "eng_app_test",
            metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
            sessions = firstLaunchSessions,
            outbox = firstLaunchOutbox,
            api = firstLaunchApi,
            clock = Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC),
        )

        assertTrue(
            firstLaunchCoordinator.enqueue(
                OperationType.EVENT_TRACKED,
                buildJsonObject { put("name", "app_started_offline") },
            ),
        )

        assertEquals(0, firstLaunchApi.bootstrapRequests)
        assertEquals(0, firstLaunchOutbox.operations.single().generation)

        firstLaunchCoordinator.flush()

        assertEquals(1, firstLaunchApi.bootstrapRequests)
        assertTrue(firstLaunchOutbox.operations.isEmpty())
    }

    @Test
    fun `incomplete acknowledgements leave the reserved batch intact`() = runTest {
        coordinator.enqueue(OperationType.EVENT_TRACKED, buildJsonObject {})
        coordinator.enqueue(OperationType.EVENT_TRACKED, buildJsonObject {})
        api.omitLastResult = true

        val failure = runCatching { coordinator.flush() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, outbox.operations.size)
    }

    @Test
    fun `issuing a binding code starts reconciliation from the current generation`() = runTest {
        var poll: Pair<Long, String>? = null
        val value = OperationCoordinator(
            endpoint = URI.create("https://edge.test/v1/"),
            appKey = "eng_app_test",
            metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
            sessions = sessions,
            outbox = outbox,
            api = api,
            onBindingCodeIssued = { generation, expiresAt -> poll = generation to expiresAt },
        ).issueBindingCode()

        assertEquals("binding-code", value)
        assertEquals(3L to "2026-08-02T10:20:00Z", poll)
    }

    @Test
    fun `profile mutation waits for the server confirmed binding generation`() = runTest {
        val pendingCoordinator = OperationCoordinator(
            endpoint = URI.create("https://edge.test/v1/"),
            appKey = "eng_app_test",
            metadata = DeviceMetadata("fr-FR", "Europe/Paris", "1", "2", "3", "Pixel", "16"),
            sessions = sessions,
            outbox = outbox,
            api = api,
            clock = Clock.fixed(Instant.parse("2026-08-02T10:15:30Z"), ZoneOffset.UTC),
        )
        pendingCoordinator.issueBindingCode()

        val enqueue = async {
            pendingCoordinator.enqueue(OperationType.PROFILE_ATTRIBUTES_EDITED, buildJsonObject {})
        }
        runCurrent()
        assertTrue(outbox.operations.isEmpty())

        sessions.saveSession(session.copy(generation = 4))
        runCurrent()

        assertTrue(enqueue.await())
        assertEquals(4, outbox.operations.single().generation)
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
        override val pending = MutableStateFlow<List<SdkOperation>>(emptyList())
        private var reserved: ReservedOperationBatch? = null
        var beforePersist: suspend () -> Unit = {}
        var enqueueFailure: Throwable? = null

        override suspend fun enqueue(operation: SdkOperation) {
            beforePersist()
            enqueueFailure?.let { throw it }
            operations += operation
            pending.value = operations.toList()
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
            pending.value = operations.toList()
            reserved = null
            return changed
        }

        override suspend fun clear() {
            operations.clear()
            pending.value = emptyList()
            reserved = null
        }
    }

    private class FakeApi(private val bootstrapSession: InstallationSession) : MobileEdgeApi {
        val sent = mutableListOf<OperationBatchRequest>()
        var omitLastResult = false
        var bootstrapRequests = 0
        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest): InstallationSession {
            bootstrapRequests += 1
            return bootstrapSession
        }
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
                }.let { if (omitLastResult) it.dropLast(1) else it },
                serverTime = "2026-08-02T10:15:31Z",
            )
        }

        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse =
            error("Not used")

        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) = Unit
    }
}
