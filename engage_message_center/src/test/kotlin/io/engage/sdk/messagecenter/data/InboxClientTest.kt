package io.engage.sdk.messagecenter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.EngageConfig
import io.engage.sdk.InboxRenderer
import io.engage.sdk.InboxRenderingSurface
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.messagecenter.domain.MutationType
import io.engage.sdk.messagecenter.domain.PendingMutation
import io.engage.sdk.messagecenter.domain.ReservedMutationBatch
import io.engage.sdk.spi.EngageHttpRequest
import io.engage.sdk.spi.EngageHttpResponse
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageRemoteDocument
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class InboxClientTest {
    @Test
    fun `page preserves the flat business payload`() = runTest {
        val context = ClientContext(this).apply { response = response(PAGE) }

        val page = InboxClient(context).page(cursor = "next/+", pageSize = 20)

        assertEquals("order.shipped", page.entries.single().key)
        assertEquals("42", page.entries.single().payload["orderId"].toString().trim('"'))
        assertEquals(3, page.unreadCount)
        assertEquals(mapOf("pageSize" to "20", "cursor" to "next/+"), context.request?.query)
    }

    @Test
    fun `mutation sends the stable generation and operation ids`() = runTest {
        val context = ClientContext(this).apply { response = response(RESULT) }
        val operation = PendingMutation(
            "operation-1",
            7,
            MutationType.MARK_READ,
            "entry-1",
            Instant.EPOCH,
            "batch-1",
        )

        val results = InboxClient(context).mutate(ReservedMutationBatch("batch-1", 7, listOf(operation)))

        assertEquals("operation-1", results.single().operationId)
        assertEquals("7", context.request?.body?.get("generation").toString())
    }

    @Test
    fun `rendering resolution accepts only requested entries`() = runTest {
        val context = ClientContext(this).apply { response = response(RENDERING) }

        val rendering = InboxClient(context).renderings(listOf("entry-1", "entry-1")).single()

        assertEquals("entry-1", rendering.entryId)
        assertEquals(InboxRenderer.DIVKIT, rendering.renderer)
        assertEquals(2, rendering.revision)
        assertEquals(
            setOf(InboxRenderingSurface.SUMMARY, InboxRenderingSurface.DETAIL),
            rendering.surfaces.keys,
        )
        assertEquals(1, (context.request?.body?.get("entryIds") as JsonArray).size)
    }

    @Test
    fun `rendering resolution rejects an entry outside the request`() {
        assertThrows(InboxInvalidResponseException::class.java) {
            runTest {
                val context = ClientContext(this).apply { response = response(RENDERING) }
                InboxClient(context).renderings(listOf("another-entry"))
            }
        }
    }

    @Test
    fun `rendering resolution rejects an incomplete surface set`() {
        assertThrows(InboxInvalidResponseException::class.java) {
            runTest {
                val context = ClientContext(this).apply { response = response(RENDERING_WITHOUT_DETAIL) }
                InboxClient(context).renderings(listOf("entry-1"))
            }
        }
    }

    @Test
    fun `rendering resolution requires its closed renderer vocabulary`() {
        assertThrows(InboxInvalidResponseException::class.java) {
            runTest {
                val context = ClientContext(this).apply { response = response(RENDERING_WITHOUT_RENDERER) }
                InboxClient(context).renderings(listOf("entry-1"))
            }
        }
    }

    private fun response(raw: String) = EngageHttpResponse(200, Json.parseToJsonElement(raw).jsonObject)

    private companion object {
        const val PAGE = """
            {
              "entries":[{
                "id":"entry-1","key":"order.shipped","payload":{"orderId":"42"},
                "scope":"PROFILE","sentAt":"2026-08-02T10:00:00Z","expiresAt":null,"readAt":null
              }],
              "nextCursor":"cursor-2","hasMore":true,"unreadCount":3
            }
        """
        const val RESULT = """
            {
              "batchId":"batch-1","serverTime":"2026-08-02T10:00:00Z",
              "results":[{"operationId":"operation-1","status":"ACCEPTED"}]
            }
        """
        const val RENDERING = """
            {
              "renderings":[{
                "entryId":"entry-1","renderer":"DIVKIT","revision":2,
                "surfaces":{
                  "SUMMARY":{"card":{"log_id":"inbox-entry-1-summary","states":[]}},
                  "DETAIL":{"card":{"log_id":"inbox-entry-1-detail","states":[]}}
                }
              }]
            }
        """
        const val RENDERING_WITHOUT_DETAIL = """
            {
              "renderings":[{
                "entryId":"entry-1","renderer":"DIVKIT","revision":2,
                "surfaces":{"SUMMARY":{"card":{"log_id":"summary","states":[]}}}
              }]
            }
        """
        const val RENDERING_WITHOUT_RENDERER = """
            {
              "renderings":[{
                "entryId":"entry-1","revision":2,
                "surfaces":{
                  "SUMMARY":{"card":{"log_id":"summary","states":[]}},
                  "DETAIL":{"card":{"log_id":"detail","states":[]}}
                }
              }]
            }
        """
    }
}

private class ClientContext(override val scope: CoroutineScope) : EngageModuleContext {
    override val applicationContext: Context = ApplicationProvider.getApplicationContext()
    override val config = EngageConfig("eng_app_test")
    override val installationId = MutableStateFlow<String?>("installation-1")
    override val generation = MutableStateFlow(7L)
    override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
    override val enabledFeatures = MutableStateFlow(setOf(SdkFeature.MESSAGE_CENTER))
    override val signals: SharedFlow<EngageSignal> = MutableSharedFlow()
    var request: EngageHttpRequest? = null
    lateinit var response: EngageHttpResponse

    override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> = MutableStateFlow(emptyList())
    override suspend fun enqueue(operation: EngageModuleOperation) = true
    override suspend fun refresh() = Unit
    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean = false
    override suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse {
        this.request = request
        return response
    }
}
