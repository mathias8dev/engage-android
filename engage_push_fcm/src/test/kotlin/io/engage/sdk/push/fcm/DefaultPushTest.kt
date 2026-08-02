package io.engage.sdk.push.fcm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.EngageConfig
import io.engage.sdk.PrivacyState
import io.engage.sdk.PushPermission
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageHttpRequest
import io.engage.sdk.spi.EngageHttpResponse
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultPushTest {
    @Test
    fun `concurrent startup signals enqueue one token and await server registration`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("engage_push", Context.MODE_PRIVATE).edit().clear().commit()
        val context = FakeModuleContext(application, backgroundScope)
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )

        runCurrent()

        assertEquals(1, context.operations.filterIsInstance<EngageModuleOperation.PushTokenChanged>().size)
        assertFalse(push.status.value.tokenRegistered)

        context.pushDocuments.value = listOf(
            EngageRemoteDocument(
                key = "state",
                revision = 2,
                payload = buildJsonObject { put("tokenRegistered", true) },
            ),
        )
        runCurrent()

        assertTrue(push.status.value.tokenRegistered)
        application.getSharedPreferences("engage_push", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeModuleContext(
        override val applicationContext: Application,
        override val scope: CoroutineScope,
    ) : EngageModuleContext {
        override val config = EngageConfig("eng_app_test")
        override val installationId = MutableStateFlow<String?>("installation-1")
        override val generation = MutableStateFlow(1L)
        override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
        override val enabledFeatures = MutableStateFlow(setOf(SdkFeature.PUSH))
        override val signals: SharedFlow<EngageSignal> = MutableSharedFlow(extraBufferCapacity = 8)
        val pushDocuments = MutableStateFlow<List<EngageRemoteDocument>>(emptyList())
        val operations = mutableListOf<EngageModuleOperation>()

        override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> = pushDocuments
        override suspend fun enqueue(operation: EngageModuleOperation) {
            operations += operation
        }
        override suspend fun refresh() = Unit
        override suspend fun executeAction(name: String, arguments: JsonObject): Boolean = true
        override suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse =
            error("Not used")
    }
}
