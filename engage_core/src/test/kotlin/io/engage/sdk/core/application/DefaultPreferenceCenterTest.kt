package io.engage.sdk.core.application

import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.Channel
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncDocument
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultPreferenceCenterTest {
    @Test
    fun `subscribers share one optimistic projection backed by pending operations`() = runTest {
        val outbox = FakeOutbox()
        val center = DefaultPreferenceCenter(
            ApplicationProvider.getApplicationContext(),
            FakeSessions(),
            FakeSyncStore(snapshot()),
            outbox,
            MutableStateFlow(setOf(SdkFeature.PREFERENCES)),
            backgroundScope,
        )

        val projection = center.center()
        assertSame(projection, center.center())
        val initial = projection.filterNotNull().first()
        assertEquals(true, initial.sections.single().subscriptions.single().profileChoices?.get(Channel.EMAIL))

        outbox.pending.value = listOf(
            SdkOperation(
                operationId = "operation-1",
                generation = 1,
                type = OperationType.PROFILE_SUBSCRIPTIONS_EDITED,
                occurredAt = "2026-08-02T12:00:00Z",
                payload = buildJsonObject {
                    put("changes", buildJsonArray {
                        add(buildJsonObject {
                            put("list", "marketing")
                            put("channel", "EMAIL")
                            put("subscribed", false)
                        })
                    })
                },
            ),
        )
        val updated = projection.filterNotNull().first {
            it.sections.single().subscriptions.single().profileChoices?.get(Channel.EMAIL) == false
        }
        assertEquals(false, updated.sections.single().subscriptions.single().profileChoices?.get(Channel.EMAIL))
    }

    private fun snapshot() = StoredSyncSnapshot(
        generation = 1,
        documents = listOf(
            SyncDocument(
                SdkModule.PREFERENCES,
                "subscriptions",
                1,
                buildJsonObject {
                    put("catalog", buildJsonArray {
                        add(buildJsonObject {
                            put("key", "marketing")
                            put("displayName", "Marketing")
                            put("scopes", buildJsonArray { add(JsonPrimitive("PROFILE")) })
                            put("channels", buildJsonArray { add(JsonPrimitive("EMAIL")) })
                            put("defaultSubscribed", false)
                        })
                    })
                    put("centers", buildJsonObject {
                        put("default", buildJsonObject {
                            put("definition", buildJsonObject {
                                put("displayName", "Preferences")
                                put("isDefault", true)
                                put("sections", buildJsonArray {
                                    add(buildJsonObject {
                                        put("key", "communications")
                                        put(
                                            "subscriptionListKeys",
                                            buildJsonArray { add(JsonPrimitive("marketing")) },
                                        )
                                    })
                                })
                            })
                        })
                    })
                    put("installation", buildJsonArray {})
                    put("profile", buildJsonArray {
                        add(buildJsonObject {
                            put("listKey", "marketing")
                            put("channel", "EMAIL")
                            put("subscribed", true)
                        })
                    })
                },
            ),
        ),
    )

    private class FakeSessions : SessionStore {
        override val session = MutableStateFlow<InstallationSession?>(null)
        override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
        override suspend fun recoveryToken(): String? = null
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

    private class FakeSyncStore(initial: StoredSyncSnapshot) : SyncStore {
        override val snapshot = MutableStateFlow(initial)
        override suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse) = Unit
        override suspend fun clear() { snapshot.value = StoredSyncSnapshot() }
    }
}
