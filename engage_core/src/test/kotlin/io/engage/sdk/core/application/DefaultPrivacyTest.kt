package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.BindingCodeResponse
import io.engage.sdk.core.domain.BootstrapRequest
import io.engage.sdk.core.domain.DeviceMetadata
import io.engage.sdk.core.domain.ExposureStore
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.InstallationStateResponse
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationBatchRequest
import io.engage.sdk.core.domain.OperationBatchResponse
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.core.domain.RevocationStore
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPrivacyTest {
    @Test
    fun `wipe persists isolated revocation before clearing every functional store`() = runTest {
        val session = InstallationSession(
            "installation-1",
            "credential",
            "limited-revocation",
            "recovery",
            7,
            PrivacyState.OPTED_IN,
            "OPTED_IN",
            "2026-08-02T12:00:00Z",
        )
        val sessions = FakeSessions(session)
        val outbox = FakeOutbox().apply { pending.value = listOf(operation()) }
        val sync = FakeSyncStore()
        val exposures = FakeExposures()
        val revocations = FakeRevocations()
        val api = FailingRevocationApi
        val endpoint = URI.create("https://edge.test/v1/")
        var moduleWipeNotified = false
        val coordinator = OperationCoordinator(
            endpoint,
            "eng_app_test",
            DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
            sessions,
            outbox,
            api,
        )
        val privacy = DefaultPrivacy(
            endpoint,
            sessions,
            outbox,
            sync,
            exposures,
            revocations,
            coordinator,
            api,
            backgroundScope,
            newId = { "revocation-operation" },
            onLocalDataWiped = { moduleWipeNotified = true },
        )

        privacy.optOutAndWipe()

        assertEquals(PrivacyState.OPTED_OUT, privacy.state.value)
        assertNull(sessions.session.value)
        assertTrue(outbox.pending.value.isEmpty())
        assertTrue(sync.cleared)
        assertTrue(exposures.cleared)
        assertTrue(moduleWipeNotified)
        assertEquals(
            RevocationEnvelope("revocation-operation", "limited-revocation"),
            revocations.envelope,
        )
        runCurrent()
        assertEquals(1, FailingRevocationApi.revokeCalls)
        assertEquals("limited-revocation", revocations.envelope?.credential)
    }

    private fun operation() = SdkOperation(
        "operation-1",
        7,
        OperationType.EVENT_TRACKED,
        "2026-08-02T12:00:00Z",
        kotlinx.serialization.json.buildJsonObject {},
    )

    private class FakeSessions(initial: InstallationSession) : SessionStore {
        override val session = MutableStateFlow<InstallationSession?>(initial)
        override val privacy = MutableStateFlow(initial.privacy)
        override suspend fun recoveryToken() = session.value?.recoveryToken
        override suspend fun saveSession(session: InstallationSession) { this.session.value = session }
        override suspend fun setPrivacy(state: PrivacyState) { privacy.value = state }
        override suspend fun clearSession() { session.value = null }
    }

    private class FakeOutbox : OperationOutbox {
        override val pending = MutableStateFlow<List<SdkOperation>>(emptyList())
        override suspend fun enqueue(operation: SdkOperation) { pending.value += operation }
        override suspend fun reserve(limit: Int, allowedTypes: Set<OperationType>?): ReservedOperationBatch? = null
        override suspend fun settle(batchId: String, results: List<OperationResult>) = false
        override suspend fun clear() { pending.value = emptyList() }
    }

    private class FakeSyncStore : SyncStore {
        override val snapshot = MutableStateFlow(StoredSyncSnapshot())
        var cleared = false
        override suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse) = Unit
        override suspend fun clear() { cleared = true }
    }

    private class FakeExposures : ExposureStore {
        var cleared = false
        override fun contains(exposureId: String) = false
        override suspend fun mark(exposureId: String) = Unit
        override suspend fun clear() { cleared = true }
    }

    private class FakeRevocations : RevocationStore {
        var envelope: RevocationEnvelope? = null
        override suspend fun get() = envelope
        override suspend fun save(envelope: RevocationEnvelope) { this.envelope = envelope }
        override suspend fun clear() { envelope = null }
    }

    private object FailingRevocationApi : MobileEdgeApi {
        var revokeCalls = 0
        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest): InstallationSession = error("unused")
        override suspend fun issueBindingCode(endpoint: URI, credential: String): BindingCodeResponse = error("unused")
        override suspend fun getInstallation(endpoint: URI, credential: String): InstallationStateResponse = error("unused")
        override suspend fun sendOperations(endpoint: URI, credential: String, batch: OperationBatchRequest): OperationBatchResponse = error("unused")
        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse = error("unused")
        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) {
            revokeCalls += 1
            throw IOException("offline")
        }
    }
}
