package io.engage.sdk.inapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.EngageConfig
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.inapp.render.InAppRenderCallbacks
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageRemoteDocument
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultInAppTest {
    @Test
    fun `placements are shared measured and cleared by privacy`() = runTest {
        val moduleContext = FakeModuleContext(backgroundScope)
        val inApp = DefaultInApp(moduleContext)
        val first = inApp.placement("home.hero")
        val second = inApp.placement("home.hero")
        assertSame(first, second)
        runCurrent()

        moduleContext.documents.value = listOf(document())
        runCurrent()
        moduleContext.mutableSignals.emit(EngageSignal.ScreenViewed("home"))
        runCurrent()

        val content = requireNotNull(first.value)
        (inApp as InAppRenderCallbacks).onVisible(content)
        runCurrent()
        assertEquals(1, moduleContext.operations.size)

        moduleContext.privacy.value = PrivacyState.OPTED_OUT
        runCurrent()
        assertNull(first.value)
    }

    @Test
    fun `automation outcomes are constrained and queued with their properties`() = runTest {
        val moduleContext = FakeModuleContext(backgroundScope)
        val inApp = DefaultInApp(moduleContext)
        val placement = inApp.placement("home.hero")
        moduleContext.documents.value = listOf(automationDocument())
        moduleContext.mutableSignals.emit(EngageSignal.AppOpened)
        runCurrent()
        val content = requireNotNull(placement.value)
        val properties = Json.parseToJsonElement("""{"plan":"pro"}""").jsonObject

        assertTrue(inApp.trackOutcome(content, "accepted", properties))
        assertFalse(inApp.trackOutcome(content, "not_connected", properties))
        assertFalse(inApp.trackOutcome(content, "Invalid Key", properties))

        val operation = moduleContext.operations.single() as EngageModuleOperation.Interaction
        assertEquals(io.engage.sdk.spi.InteractionType.OUTCOME, operation.type)
        assertEquals("accepted", operation.outcomeKey)
        assertEquals(properties, operation.properties)
    }

    private fun document() = EngageRemoteDocument(
        "hero",
        1,
        Json.parseToJsonElement(
            """
            {
              "experienceId":"hero","version":1,"publishedAt":"2026-01-01T00:00:00Z",
              "definition":{
                "triggers":[{"id":"screen","type":"SCREEN_VIEW","delaySeconds":0,"screenName":"home"}],
                "schedule":{"startAt":null,"endAt":null,"timezoneMode":"ENVIRONMENT"},
                "priority":1,"conflictPolicy":"QUEUE",
                "displayPolicy":{"maxTotalImpressions":3,"maxImpressionsPerSession":3,"maxImpressionsPerDay":3,"cooldownMinutes":null,"redisplayAfterDismissal":true},
                "defaultLocale":"und","fallbackLocale":null,
                "contentVariants":[{
                  "id":"v1","key":"v1","locale":"und","allocationPercentage":100,
                  "content":{"type":"SCENE","payload":{"card":{"log_id":"hero","states":[]}}},
                  "presentation":{"mode":"EMBEDDED","embedded":{"placementKey":"home.hero","emptyState":"COLLAPSE"}}
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private fun automationDocument() = EngageRemoteDocument(
        "automation:message-1",
        1,
        Json.parseToJsonElement(
            """
            {
              "source":"AUTOMATION","experienceId":"hero","experienceVersion":1,
              "messageId":"message-1","automationId":"automation-1","automationVersion":2,
              "automationRunId":"run-1","automationNodeId":"node-1","outcomeKeys":["accepted"],
              "content":{"type":"SCENE","payload":{"card":{"log_id":"hero","states":[]}}},
              "presentation":{"mode":"EMBEDDED","embedded":{"placementKey":"home.hero"}},
              "availableAt":"2026-01-01T00:00:00Z","expiresAt":"2027-01-01T00:00:00Z"
            }
            """.trimIndent(),
        ).jsonObject,
    )
}

private class FakeModuleContext(override val scope: CoroutineScope) : EngageModuleContext {
    override val applicationContext: Context = ApplicationProvider.getApplicationContext()
    override val config = EngageConfig("eng_app_test")
    override val installationId = MutableStateFlow<String?>("installation-test")
    override val generation = MutableStateFlow(1L)
    override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
    override val enabledFeatures = MutableStateFlow(setOf(SdkFeature.IN_APP))
    val mutableSignals = MutableSharedFlow<EngageSignal>(extraBufferCapacity = 8)
    override val signals: SharedFlow<EngageSignal> = mutableSignals
    val documents = MutableStateFlow<List<EngageRemoteDocument>>(emptyList())
    val operations = mutableListOf<EngageModuleOperation>()

    override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> = documents
    override suspend fun enqueue(operation: EngageModuleOperation): Boolean {
        operations += operation
        return true
    }
    override suspend fun refresh() = Unit
    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean = true
}
