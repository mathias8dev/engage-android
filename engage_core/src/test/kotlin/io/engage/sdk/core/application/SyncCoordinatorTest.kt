package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.BindingCodeResponse
import io.engage.sdk.core.domain.BootstrapRequest
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.InstallationStateResponse
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationBatchRequest
import io.engage.sdk.core.domain.OperationBatchResponse
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class SyncCoordinatorTest {
    @Test
    fun `installation state still converges when no feature module is enabled`() = runTest {
        val session = InstallationSession(
            installationId = "installation-1",
            credential = "credential",
            revocationCredential = "revocation",
            recoveryToken = "recovery",
            generation = 3,
            privacy = PrivacyState.OPTED_IN,
            pushSubscription = "OPTED_IN",
            serverTime = "2026-08-02T10:00:00Z",
        )
        val sessions = FakeSessionStore(session)
        val store = FakeSyncStore()
        val api = FakeApi(session)
        val coordinator = SyncCoordinator(URI.create("https://edge.test/v1/"), sessions, store, api)

        coordinator.refresh(emptySet())

        assertEquals(1, api.installationRequests)
        assertEquals(0, api.syncRequests)
        assertEquals(0, store.applies)
    }

    private class FakeSessionStore(initial: InstallationSession) : SessionStore {
        override val session = MutableStateFlow<InstallationSession?>(initial)
        override val privacy = MutableStateFlow(initial.privacy)
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

    private class FakeSyncStore : SyncStore {
        override val snapshot = MutableStateFlow(StoredSyncSnapshot())
        var applies = 0
        override suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse) {
            applies += 1
        }
        override suspend fun clear() = Unit
    }

    private class FakeApi(private val current: InstallationSession) : MobileEdgeApi {
        var installationRequests = 0
        var syncRequests = 0

        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest) = current
        override suspend fun issueBindingCode(endpoint: URI, credential: String) =
            BindingCodeResponse("binding-code", "2026-08-02T10:20:00Z")
        override suspend fun getInstallation(endpoint: URI, credential: String): InstallationStateResponse {
            installationRequests += 1
            return InstallationStateResponse(
                current.installationId,
                current.generation,
                "BOUND",
                current.privacy,
                current.pushSubscription,
                true,
                current.serverTime,
            )
        }
        override suspend fun sendOperations(
            endpoint: URI,
            credential: String,
            batch: OperationBatchRequest,
        ): OperationBatchResponse = error("Not used")
        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse {
            syncRequests += 1
            error("No module sync expected")
        }
        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) = Unit
    }
}
