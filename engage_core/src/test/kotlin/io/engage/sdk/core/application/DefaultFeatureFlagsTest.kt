package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
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
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncDocument
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URI
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFeatureFlagsTest {
    @Test
    fun `returns typed value and deduplicates experiment exposure`() = runTest {
        val session = InstallationSession(
            "installation-1",
            "credential",
            "revocation",
            "recovery",
            4,
            PrivacyState.OPTED_IN,
            "OPTED_IN",
            "2026-08-02T12:00:00Z",
        )
        val sessions = FakeSessions(session)
        val outbox = FakeOutbox()
        val coordinator = OperationCoordinator(
            URI.create("https://edge.test/v1/"),
            "eng_app_test",
            DeviceMetadata("fr", "Europe/Paris", "1", "1", null, null, null),
            sessions,
            outbox,
            UnusedApi,
        )
        val sync = FakeSyncStore(
            StoredSyncSnapshot(
                generation = 4,
                documents = listOf(
                    SyncDocument(
                        SdkModule.FEATURE_FLAGS,
                        "snapshot",
                        12,
                        buildJsonObject {
                            put("flags", buildJsonObject {
                                put("checkout_v2", buildJsonObject {
                                    put("type", "BOOLEAN")
                                    put("value", true)
                                    put("revision", 12)
                                    put("variantKey", "treatment")
                                    put("experimentId", "checkout_conversion")
                                })
                            })
                        },
                    ),
                ),
            ),
        )
        val flags = DefaultFeatureFlags(
            sessions,
            sync,
            MutableStateFlow(setOf(SdkFeature.FEATURE_FLAGS)),
            coordinator,
            InMemoryExposures(),
            this,
        )

        assertEquals(true, flags.getBoolean("checkout_v2", false))
        assertEquals(true, flags.getBoolean("checkout_v2", false))
        advanceUntilIdle()

        assertEquals(1, outbox.operations.distinctBy(SdkOperation::operationId).size)
        assertEquals(OperationType.FLAG_EXPOSED, outbox.operations.single().type)
        UUID.fromString(outbox.operations.single().operationId)
    }

    @Test
    fun `stale generation snapshot always returns fallback`() = runTest {
        val session = InstallationSession(
            "installation-1", "credential", "revocation", "recovery", 5,
            PrivacyState.OPTED_IN, "OPTED_IN", "2026-08-02T12:00:00Z",
        )
        val sessions = FakeSessions(session)
        val flags = DefaultFeatureFlags(
            sessions,
            FakeSyncStore(
                StoredSyncSnapshot(
                    generation = 4,
                    documents = listOf(
                        SyncDocument(
                            SdkModule.FEATURE_FLAGS,
                            "snapshot",
                            12,
                            buildJsonObject {
                                put("flags", buildJsonObject {
                                    put("checkout_v2", buildJsonObject {
                                        put("type", "BOOLEAN")
                                        put("value", true)
                                        put("revision", 12)
                                    })
                                })
                            },
                        ),
                    ),
                ),
            ),
            MutableStateFlow(setOf(SdkFeature.FEATURE_FLAGS)),
            OperationCoordinator(
                URI.create("https://edge.test/v1/"),
                "eng_app_test",
                DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
                sessions,
                FakeOutbox(),
                UnusedApi,
            ),
            InMemoryExposures(),
            this,
        )

        assertFalse(flags.getBoolean("checkout_v2", false))
    }

    @Test
    fun `privacy opt out always returns fallback`() = runTest {
        val sessions = FakeSessions(null).apply { privacy.value = PrivacyState.OPTED_OUT }
        val flags = DefaultFeatureFlags(
            sessions,
            FakeSyncStore(StoredSyncSnapshot()),
            MutableStateFlow(setOf(SdkFeature.FEATURE_FLAGS)),
            OperationCoordinator(
                URI.create("https://edge.test/v1/"),
                "eng_app_test",
                DeviceMetadata("fr", "UTC", "1", "1", null, null, null),
                sessions,
                FakeOutbox(),
                UnusedApi,
            ),
            InMemoryExposures(),
            this,
        )

        assertFalse(flags.getBoolean("checkout_v2", false))
    }

    private class FakeSessions(initial: InstallationSession?) : SessionStore {
        override val session = MutableStateFlow(initial)
        override val privacy = MutableStateFlow(initial?.privacy ?: PrivacyState.OPTED_IN)
        override suspend fun recoveryToken() = session.value?.recoveryToken
        override suspend fun saveSession(session: InstallationSession) { this.session.value = session }
        override suspend fun setPrivacy(state: PrivacyState) { privacy.value = state }
        override suspend fun clearSession() { session.value = null }
    }

    private class FakeOutbox : OperationOutbox {
        val operations = mutableListOf<SdkOperation>()
        override val pending = MutableStateFlow<List<SdkOperation>>(emptyList())
        override suspend fun enqueue(operation: SdkOperation) {
            if (operations.none { it.operationId == operation.operationId }) operations += operation
            pending.value = operations.toList()
        }
        override suspend fun reserve(limit: Int, allowedTypes: Set<OperationType>?): ReservedOperationBatch? = null
        override suspend fun settle(batchId: String, results: List<OperationResult>) = false
        override suspend fun clear() {
            operations.clear()
            pending.value = emptyList()
        }
    }

    private class FakeSyncStore(initial: StoredSyncSnapshot) : SyncStore {
        override val snapshot = MutableStateFlow(initial)
        override suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse) = Unit
        override suspend fun clear() { snapshot.value = StoredSyncSnapshot() }
    }

    private class InMemoryExposures : ExposureStore {
        private val ids = mutableSetOf<String>()
        override fun contains(exposureId: String) = exposureId in ids
        override suspend fun mark(exposureId: String) { ids += exposureId }
        override suspend fun clear() = ids.clear()
    }

    private object UnusedApi : MobileEdgeApi {
        override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest): InstallationSession = error("unused")
        override suspend fun issueBindingCode(endpoint: URI, credential: String): BindingCodeResponse = error("unused")
        override suspend fun getInstallation(endpoint: URI, credential: String): InstallationStateResponse = error("unused")
        override suspend fun sendOperations(endpoint: URI, credential: String, batch: OperationBatchRequest): OperationBatchResponse = error("unused")
        override suspend fun synchronize(endpoint: URI, credential: String, request: SyncRequest): SyncResponse = error("unused")
        override suspend fun revoke(endpoint: URI, revocationCredential: String, operationId: String) = Unit
    }
}
