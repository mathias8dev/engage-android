package io.engage.sdk.messagecenter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.EngageConfig
import io.engage.sdk.InboxEntryId
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.messagecenter.data.InboxClient
import io.engage.sdk.messagecenter.data.SqliteInboxStore
import io.engage.sdk.spi.EngageHttpMethod
import io.engage.sdk.spi.EngageHttpRequest
import io.engage.sdk.spi.EngageHttpResponse
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageRemoteDocument
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultInboxTest {
    @Test
    fun `pagers deduplicate fetches and share optimistic mutations`() = runTest {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        appContext.deleteDatabase("engage_message_center.db")
        val gate = CompletableDeferred<Unit>()
        val moduleContext = RuntimeContext(backgroundScope, gate)
        val store = SqliteInboxStore(appContext, newId = { "batch-1" })
        val inbox = DefaultInbox(
            context = moduleContext,
            store = store,
            client = InboxClient(moduleContext),
            newId = { "operation-1" },
        )
        runCurrent()

        val first = inbox.pager(20)
        val second = inbox.pager(20)
        runCurrent()
        assertEquals(1, moduleContext.pageRequests)

        gate.complete(Unit)
        runCurrent()
        assertEquals("entry-1", first.state.value.entries.single().id.value)
        assertEquals(first.state.value.entries, second.state.value.entries)

        first.close()
        inbox.markRead(InboxEntryId("entry-1"))
        runCurrent()

        assertNotNull(second.state.value.entries.single().readAt)
        assertEquals(0, inbox.unreadCount.value)
        assertEquals(1, moduleContext.mutationRequests)
        second.close()
        store.close()
        appContext.deleteDatabase("engage_message_center.db")
    }
}
private class RuntimeContext(
    override val scope: CoroutineScope,
    private val pageGate: CompletableDeferred<Unit>,
) : EngageModuleContext {
    override val applicationContext: Context = ApplicationProvider.getApplicationContext()
    override val config = EngageConfig("eng_app_test")
    override val installationId = MutableStateFlow<String?>("installation-1")
    override val generation = MutableStateFlow(3L)
    override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
    override val enabledFeatures = MutableStateFlow(setOf(SdkFeature.MESSAGE_CENTER))
    override val signals: SharedFlow<EngageSignal> = MutableSharedFlow(extraBufferCapacity = 8)
    var pageRequests = 0
    var mutationRequests = 0

    override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> = MutableStateFlow(emptyList())
    override suspend fun enqueue(operation: EngageModuleOperation) = Unit
    override suspend fun refresh() = Unit
    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean = true

    override suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse = when {
        request.method == EngageHttpMethod.GET && request.path == "sdk/inbox" -> {
            pageRequests += 1
            pageGate.await()
            EngageHttpResponse(200, Json.parseToJsonElement(PAGE).jsonObject)
        }
        request.path == "sdk/inbox/operations:batch" -> {
            mutationRequests += 1
            val batchId = request.body?.get("batchId").toString().trim('"')
            val operations = request.body?.get("operations") as JsonArray
            EngageHttpResponse(
                202,
                buildJsonObject {
                    put("batchId", batchId)
                    put("serverTime", "2026-08-02T12:00:00Z")
                    put("results", buildJsonArray {
                        operations.forEach { operation ->
                            add(buildJsonObject {
                                put("operationId", operation.jsonObject.getValue("operationId"))
                                put("status", "ACCEPTED")
                            })
                        }
                    })
                },
            )
        }
        else -> error("Unexpected request ${request.path}")
    }

    private companion object {
        const val PAGE = """
            {
              "entries":[{
                "id":"entry-1","key":"order.shipped","payload":{"orderId":"42"},
                "scope":"PROFILE","sentAt":"2026-08-02T10:00:00Z","expiresAt":null,"readAt":null
              }],
              "nextCursor":null,"hasMore":false,"unreadCount":1
            }
        """
    }
}
