package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
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
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.spi.EngageSignal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class DefaultEventsTest {
    @Test
    fun `screen tracked in background starts counting only after foreground`() = runTest {
        var elapsed = 0L
        val outbox = RecordingOutbox()
        val sessions = FakeSessions()
        val coordinator = OperationCoordinator(
            URI.create("https://edge.test/v1/"),
            "eng_app_test",
            DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
            sessions,
            outbox,
            NoopApi,
        )
        val events = DefaultEvents(
            coordinator,
            MutableStateFlow(setOf(SdkFeature.ANALYTICS)),
            sessions.privacy,
            MutableSharedFlow<EngageSignal>(extraBufferCapacity = 1),
        ) { elapsed }

        events.onBackground()
        elapsed = 5_000
        events.trackScreen("background_screen")
        elapsed = 9_000
        events.onForeground()
        elapsed = 10_000
        events.trackScreen("foreground_screen")

        assertEquals("foreground_screen", outbox.operations.last().payload["screen_key"]!!.jsonPrimitive.content)
        assertEquals("background_screen", outbox.operations.last().payload["previous_screen_key"]!!.jsonPrimitive.content)
        assertEquals(
            1_000L,
            outbox.operations.last().payload["previous_visible_duration_millis"]!!.jsonPrimitive.long,
        )
    }

    @Test
    fun `screen tracked after a background process start does not count pre-foreground time`() = runTest {
        var elapsed = 5_000L
        val outbox = RecordingOutbox()
        val sessions = FakeSessions()
        val coordinator = OperationCoordinator(
            URI.create("https://edge.test/v1/"),
            "eng_app_test",
            DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
            sessions,
            outbox,
            NoopApi,
        )
        val events = DefaultEvents(
            coordinator,
            MutableStateFlow(setOf(SdkFeature.ANALYTICS)),
            sessions.privacy,
            MutableSharedFlow<EngageSignal>(extraBufferCapacity = 1),
            initiallyForeground = false,
            elapsedRealtime = { elapsed },
        )

        events.trackScreen("background_screen")
        elapsed = 9_000
        events.onForeground()
        elapsed = 10_000
        events.trackScreen("foreground_screen")

        assertEquals(
            1_000L,
            outbox.operations.last().payload["previous_visible_duration_millis"]!!.jsonPrimitive.long,
        )
    }

    @Test
    fun `screen operations use the canonical snake case wire contract`() = runTest {
        var elapsed = 0L
        val outbox = RecordingOutbox()
        val sessions = FakeSessions()
        val coordinator = OperationCoordinator(
            URI.create("https://edge.test/v1/"),
            "eng_app_test",
            DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
            sessions,
            outbox,
            NoopApi,
        )
        val events = DefaultEvents(
            coordinator,
            MutableStateFlow(setOf(SdkFeature.ANALYTICS)),
            sessions.privacy,
            MutableSharedFlow<EngageSignal>(extraBufferCapacity = 1),
        ) { elapsed }

        events.trackScreen("checkout")
        elapsed = 250
        events.clearScreen()

        assertEquals("checkout", outbox.operations[0].payload["screen_key"]!!.jsonPrimitive.content)
        assertEquals(null, outbox.operations[0].payload["screenKey"])
        assertEquals("checkout", outbox.operations[1].payload["screen_key"]!!.jsonPrimitive.content)
        assertEquals(250L, outbox.operations[1].payload["visible_duration_millis"]!!.jsonPrimitive.long)
        assertEquals(null, outbox.operations[1].payload["visibleDurationMillis"])
    }

    private class FakeSessions : SessionStore {
        override val session = MutableStateFlow<InstallationSession?>(
            InstallationSession(
                "installation", "credential", "revocation", "recovery", 1,
                PrivacyState.OPTED_IN, "OPTED_IN", "2026-08-06T12:00:00Z",
            ),
        )
        override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
        override suspend fun recoveryToken() = session.value?.recoveryToken
        override suspend fun saveSession(session: InstallationSession) { this.session.value = session }
        override suspend fun setPrivacy(state: PrivacyState) { privacy.value = state }
        override suspend fun clearSession() { session.value = null }
    }

    private class RecordingOutbox : OperationOutbox {
        val operations = mutableListOf<SdkOperation>()
        override val pending = MutableStateFlow<List<SdkOperation>>(emptyList())
        override suspend fun enqueue(operation: SdkOperation) {
            operations += operation
            pending.value = operations.toList()
        }
        override suspend fun reserve(limit: Int, allowedTypes: Set<OperationType>?) =
            null as ReservedOperationBatch?
        override suspend fun settle(batchId: String, results: List<OperationResult>) = false
        override suspend fun clear() { operations.clear(); pending.value = emptyList() }
    }

    private object NoopApi : MobileEdgeApi {
        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest): InstallationSession =
            error("unused")
        override suspend fun issueBindingCode(endpoint: URI, credential: String): BindingCodeResponse = error("unused")
        override suspend fun getInstallation(endpoint: URI, credential: String): InstallationStateResponse = error("unused")
        override suspend fun sendOperations(
            endpoint: URI,
            credential: String,
            batch: OperationBatchRequest,
        ): OperationBatchResponse = error("unused")
        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse =
            error("unused")
        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) = Unit
    }
}
