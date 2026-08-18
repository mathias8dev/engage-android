package io.engage.sdk.push.fcm

import android.app.Notification
import android.app.NotificationManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.AndroidPushAction
import io.engage.sdk.AndroidPushCategory
import io.engage.sdk.AndroidPushChannel
import io.engage.sdk.AndroidPushConfig
import io.engage.sdk.EngageConfig
import io.engage.sdk.PrivacyState
import io.engage.sdk.PushConfig
import io.engage.sdk.PushEvent
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultPushTest {
    @Test
    fun `permission is retried when the durable outbox rejects the first enqueue`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("engage_push_test", Context.MODE_PRIVATE).edit().clear().commit()
        val context = FakeModuleContext(application, backgroundScope).apply { acceptOperations = false }
        DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        runCurrent()

        assertTrue(context.operations.isEmpty())

        context.acceptOperations = true
        context.mutableSignals.emit(EngageSignal.AppOpened)
        runCurrent()

        assertEquals(
            listOf(PushPermission.AUTHORIZED.name),
            context.operations.filterIsInstance<EngageModuleOperation.PushPermissionChanged>().map { it.permission },
        )
        application.getSharedPreferences("engage_push_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `concurrent startup signals enqueue one token and await server registration`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("engage_push_test", Context.MODE_PRIVATE).edit().clear().commit()
        val context = FakeModuleContext(application, backgroundScope)
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )

        runCurrent()

        assertEquals(1, context.operations.filterIsInstance<EngageModuleOperation.PushTokenChanged>().size)
        assertEquals(
            listOf(PushPermission.AUTHORIZED.name),
            context.operations.filterIsInstance<EngageModuleOperation.PushPermissionChanged>().map { it.permission },
        )
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
        application.getSharedPreferences("engage_push_test", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    @Config(sdk = [32])
    fun `received events expose only custom data and rich images use big picture style`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var notification: Notification? = null
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
            imageLoader = PushImageLoader { image },
            notificationObserver = { notification = it },
        )
        val event = async { push.events.first() }
        runCurrent()

        push.processMessage(pushPayload())

        assertEquals(
            PushEvent.Received("delivery-1", "message-1", mapOf("merchant" to "Paris")),
            event.await(),
        )
        assertNotNull(notification?.extras?.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertEquals(
            EngagePushOpenActivity::class.java.name,
            shadowOf(notification!!.contentIntent).savedIntent.component?.className,
        )
        clearState(application)
    }

    @Test
    fun `a delivery is processed only once across push instances`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        var notifications = 0
        val first = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
            notificationObserver = { notifications += 1 },
        )
        runCurrent()

        first.processMessage(pushPayload())
        val second = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
            notificationObserver = { notifications += 1 },
        )
        runCurrent()
        second.processMessage(pushPayload())

        assertEquals(1, notifications)
        assertEquals(
            1,
            context.operations.filterIsInstance<EngageModuleOperation.PushReceipt>()
                .count { it.deliveryId == "delivery-1" },
        )
        clearState(application)
    }

    @Test
    fun `a push targeting another installation is ignored`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        var notifications = 0
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
            notificationObserver = { notifications += 1 },
        )
        runCurrent()

        push.processMessage(pushPayload().plus("engage_installation_id" to "installation-2"))

        assertEquals(0, notifications)
        assertTrue(context.operations.filterIsInstance<EngageModuleOperation.PushReceipt>().isEmpty())
        clearState(application)
    }

    @Test
    fun `notification category actions receive action arguments instead of custom data`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        runCurrent()
        val intent = Intent().apply {
            pushPayload().forEach(::putExtra)
            putExtra("engage_push_action_key", "open_order")
        }

        push.onAction(intent)
        runCurrent()

        assertEquals("open_order", context.executedAction)
        assertEquals(JsonObject(mapOf("order_id" to JsonPrimitive("order-42"))), context.executedArguments)
        clearState(application)
    }

    @Test
    fun `notification action does not complete before durable work finishes`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val gate = CompletableDeferred<Unit>()
        val context = FakeModuleContext(application, backgroundScope, pushConfig()).apply {
            beforeExecuteAction = { gate.await() }
        }
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        runCurrent()
        val intent = Intent().apply {
            pushPayload().forEach(::putExtra)
            putExtra("engage_push_action_key", "open_order")
        }

        val processing = async { push.onAction(intent) }
        runCurrent()

        assertFalse(processing.isCompleted)
        assertTrue(context.operations.filterIsInstance<EngageModuleOperation.PushReceipt>().isNotEmpty())
        gate.complete(Unit)
        processing.await()
        clearState(application)
    }

    @Test
    fun `opening a deep link emits filtered data without navigating on behalf of the app`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        val event = async { push.events.first() }
        runCurrent()
        val intent = Intent().apply { pushPayload(actionType = "DEEPLINK").forEach(::putExtra) }

        push.handleOpenIntent(intent)
        runCurrent()

        assertEquals(
            PushEvent.Opened(
                "delivery-1",
                "message-1",
                "engage-test://orders/42",
                mapOf("merchant" to "Paris"),
            ),
            event.await(),
        )
        assertNull(shadowOf(application).nextStartedActivity)
        clearState(application)
    }

    @Test
    fun `notification trampoline can await open work before finishing`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val gate = CompletableDeferred<Unit>()
        val context = FakeModuleContext(application, backgroundScope, pushConfig()).apply {
            beforeExecuteAction = { gate.await() }
        }
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        runCurrent()
        val intent = Intent().apply { pushPayload(actionType = "CUSTOM").forEach(::putExtra) }

        val processing = async { push.handleOpenIntentAwaitingWork(intent) }
        runCurrent()

        assertFalse(processing.isCompleted)
        assertTrue(context.operations.filterIsInstance<EngageModuleOperation.PushReceipt>().isNotEmpty())
        gate.complete(Unit)
        assertTrue(processing.await())
        clearState(application)
    }

    @Test
    fun `opening a web URL launches the browser and does not expose it as an app deep link`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        clearState(application)
        val context = FakeModuleContext(application, backgroundScope, pushConfig())
        val push = DefaultPush(
            moduleContext = context,
            tokenProvider = PushTokenProvider { "fcm-token" },
            permissionProvider = PushPermissionProvider { PushPermission.AUTHORIZED },
        )
        val event = async { push.events.first() }
        runCurrent()
        val intent = Intent().apply {
            pushPayload(actionType = "WEB_URL", actionValue = "https://www.google.com/search?q=engage")
                .forEach(::putExtra)
        }

        push.handleOpenIntent(intent)
        runCurrent()

        assertEquals(
            PushEvent.Opened(
                "delivery-1",
                "message-1",
                null,
                mapOf("merchant" to "Paris"),
            ),
            event.await(),
        )
        val browserIntent = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, browserIntent.action)
        assertEquals("https://www.google.com/search?q=engage", browserIntent.dataString)
        assertTrue(Intent.CATEGORY_BROWSABLE in browserIntent.categories)
        clearState(application)
    }

    private fun pushConfig() = EngageConfig(
        appKey = "eng_app_test",
        push = PushConfig(
            android = AndroidPushConfig(
                smallIcon = android.R.drawable.ic_dialog_info,
                defaultChannelKey = "general",
                channels = listOf(AndroidPushChannel("general", android.R.string.untitled)),
                categories = listOf(
                    AndroidPushCategory(
                        "orders",
                        listOf(AndroidPushAction("open_order", android.R.string.ok)),
                    ),
                ),
            ),
        ),
    )

    private fun pushPayload(actionType: String = "CUSTOM", actionValue: String? = null) = mapOf(
        "engage_delivery_id" to "delivery-1",
        "engage_message_id" to "message-1",
        "engage_installation_id" to "installation-1",
        "engage_action_type" to actionType,
        "engage_action_value" to (actionValue ?: if (actionType == "CUSTOM") {
            "open_order"
        } else {
            "engage-test://orders/42"
        }),
        "engage_action_arg_order_id" to "order-42",
        "engage_title" to "Order ready",
        "engage_body" to "Order 42 is ready",
        "engage_image_url" to "https://cdn.example.test/order.png",
        "engage_category_key" to "orders",
        "merchant" to "Paris",
    )

    private fun clearState(application: Application) {
        application.getSharedPreferences("engage_push", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("engage_push_test", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSystemService(NotificationManager::class.java).cancelAll()
    }

    private class FakeModuleContext(
        override val applicationContext: Application,
        override val scope: CoroutineScope,
        override val config: EngageConfig = EngageConfig("eng_app_test"),
    ) : EngageModuleContext {
        override val storageScope: String = "test"
        override val installationId = MutableStateFlow<String?>("installation-1")
        override val generation = MutableStateFlow(1L)
        override val privacy = MutableStateFlow(PrivacyState.OPTED_IN)
        override val enabledFeatures = MutableStateFlow(setOf(SdkFeature.PUSH))
        val mutableSignals = MutableSharedFlow<EngageSignal>(extraBufferCapacity = 8)
        override val signals: SharedFlow<EngageSignal> = mutableSignals
        val pushDocuments = MutableStateFlow<List<EngageRemoteDocument>>(emptyList())
        val operations = mutableListOf<EngageModuleOperation>()
        var acceptOperations = true
        var executedAction: String? = null
        var executedArguments: JsonObject? = null
        var beforeExecuteAction: suspend () -> Unit = {}

        override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> = pushDocuments
        override suspend fun enqueue(operation: EngageModuleOperation): Boolean {
            if (acceptOperations) operations += operation
            return acceptOperations
        }
        override suspend fun refresh() = Unit
        override suspend fun executeAction(name: String, arguments: JsonObject): Boolean {
            beforeExecuteAction()
            executedAction = name
            executedArguments = arguments
            return true
        }
        override suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse =
            error("Not used")
    }
}
