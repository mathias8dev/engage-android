package io.engage.sdk.push.fcm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import io.engage.sdk.AndroidPushChannel
import io.engage.sdk.AndroidPushConfig
import io.engage.sdk.AndroidPushSound
import io.engage.sdk.EngageConfig
import io.engage.sdk.ForegroundPresentation
import io.engage.sdk.NotificationImportance
import io.engage.sdk.PrivacyState
import io.engage.sdk.Push
import io.engage.sdk.PushEvent
import io.engage.sdk.PushPermission
import io.engage.sdk.PushStatus
import io.engage.sdk.PushSubscriptionState
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DefaultPush(
    private val moduleContext: EngageModuleContext,
    private val tokenProvider: PushTokenProvider = FirebasePushTokenProvider,
    private val permissionProvider: PushPermissionProvider = AndroidPushPermissionProvider,
    private val imageLoader: PushImageLoader = HttpPushImageLoader,
    private val notificationObserver: (Notification) -> Unit = {},
) : Push {
    private val application = moduleContext.applicationContext as Application
    private val preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableStatus = MutableStateFlow(
        PushStatus(
            permission = permissionProvider.current(application),
            subscription = storedSubscription(),
            tokenRegistered = false,
        ),
    )
    private val mutableEvents = MutableSharedFlow<PushEvent>(extraBufferCapacity = 32)
    private var currentToken: String? = null
    private var appInForeground = false
    private var previousPrivacy = moduleContext.privacy.value
    private val tokenMutex = Mutex()
    private val permissionMutex = Mutex()
    private val subscriptionMutex = Mutex()

    override val status: StateFlow<PushStatus> = mutableStatus
    override val events: SharedFlow<PushEvent> = mutableEvents

    init {
        AndroidChannelRegistrar.register(application, moduleContext.config)
        application.registerActivityLifecycleCallbacks(PushOpenCallbacks(::handleOpenIntent))
        moduleContext.scope.launch {
            moduleContext.documents(EngageSyncModule.PUSH).collectLatest { documents ->
                val state = documents.firstOrNull { it.key == "state" }?.payload
                val registered = (state?.get("tokenRegistered") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (registered != null) mutableStatus.value = mutableStatus.value.copy(tokenRegistered = registered)
                val subscription = (state?.get("subscription") as? JsonPrimitive)?.content
                    ?.let { value -> runCatching { PushSubscriptionState.valueOf(value) }.getOrNull() }
                if (subscription != null && !preferences.getBoolean(PENDING_SUBSCRIPTION, false)) {
                    check(preferences.edit().putString(SUBSCRIPTION, subscription.name).commit()) {
                        "Could not persist Engage push subscription state"
                    }
                    mutableStatus.value = mutableStatus.value.copy(subscription = subscription)
                }
            }
        }
        moduleContext.scope.launch {
            moduleContext.signals.collect { signal ->
                when (signal) {
                    EngageSignal.AppOpened -> {
                        appInForeground = true
                        synchronizePermission()
                        synchronizeSubscription()
                        synchronizeToken()
                    }
                    EngageSignal.AppBackgrounded -> appInForeground = false
                    EngageSignal.LocalDataWiped -> wipe()
                    else -> Unit
                }
            }
        }
        moduleContext.scope.launch {
            moduleContext.enabledFeatures.collectLatest { synchronizeToken() }
        }
        moduleContext.scope.launch {
            moduleContext.privacy.collectLatest { privacy ->
                if (previousPrivacy == PrivacyState.OPTED_OUT && privacy == PrivacyState.OPTED_IN) {
                    synchronizeSubscription(force = true)
                    synchronizePermission()
                    synchronizeToken()
                }
                previousPrivacy = privacy
            }
        }
        moduleContext.scope.launch {
            synchronizePermission()
            synchronizeSubscription()
            synchronizeToken()
        }
    }

    override suspend fun optIn() {
        persistSubscription(PushSubscriptionState.OPTED_IN)
        synchronizeSubscription()
    }

    override suspend fun optOut() {
        persistSubscription(PushSubscriptionState.OPTED_OUT)
        synchronizeSubscription()
    }

    fun wipe() {
        currentToken = null
        check(preferences.edit().clear().commit()) { "Could not wipe Engage push state" }
        mutableStatus.value = mutableStatus.value.copy(
            subscription = PushSubscriptionState.OPTED_IN,
            tokenRegistered = false,
        )
    }

    fun onNewToken(token: String) {
        currentToken = token
        moduleContext.scope.launch { submitTokenIfNeeded(token) }
    }

    fun onMessage(message: RemoteMessage) {
        val payload = EngagePushPayload.from(message.data) ?: return
        if (!canRun()) return
        mutableEvents.tryEmit(PushEvent.Received(payload.deliveryId, payload.messageId, payload.customData()))
        moduleContext.scope.launch {
            moduleContext.enqueue(
                EngageModuleOperation.PushReceipt(
                    payload.deliveryId,
                    io.engage.sdk.spi.PushReceiptType.DELIVERED,
                ),
            )
            if (!appInForeground || moduleContext.config.push.foregroundPresentation == ForegroundPresentation.SHOW) {
                val image = payload.imageUrl?.let { url -> imageLoader.load(url) }
                showNotification(payload, image)
            }
        }
    }

    fun onDismiss(intent: Intent) {
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return
        if (!canRun()) return
        mutableEvents.tryEmit(PushEvent.Dismissed(payload.deliveryId, payload.messageId))
    }

    fun onAction(intent: Intent) {
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return
        val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)?.takeIf(String::isNotBlank) ?: return
        if (!canRun()) return
        NotificationManagerCompat.from(application).cancel(payload.notificationTag, payload.deliveryId.hashCode())
        mutableEvents.tryEmit(
            PushEvent.ActionSelected(payload.deliveryId, payload.messageId, actionKey, payload.customData()),
        )
        moduleContext.scope.launch {
            moduleContext.enqueue(
                EngageModuleOperation.PushReceipt(
                    payload.deliveryId,
                    io.engage.sdk.spi.PushReceiptType.OPENED,
                ),
            )
            moduleContext.executeAction(
                actionKey,
                JsonObject(payload.actionArguments.mapValues { JsonPrimitive(it.value) }),
            )
            if (intent.getBooleanExtra(EXTRA_ACTION_OPENS_APP, false)) {
                application.packageManager.getLaunchIntentForPackage(application.packageName)?.let { launcher ->
                    payload.data.forEach { (key, value) -> launcher.putExtra(key, value) }
                    application.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    internal fun handleOpenIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return
        intent.putExtra(EXTRA_HANDLED, true)
        if (!canRun()) return
        val deepLink = payload.actionValue.takeIf {
            payload.actionType == "DEEPLINK" || payload.actionType == "WEB_URL"
        }
        mutableEvents.tryEmit(PushEvent.Opened(payload.deliveryId, payload.messageId, deepLink, payload.customData()))
        moduleContext.scope.launch {
            moduleContext.enqueue(
                EngageModuleOperation.PushReceipt(
                    payload.deliveryId,
                    io.engage.sdk.spi.PushReceiptType.OPENED,
                ),
            )
            when (payload.actionType) {
                "CUSTOM" -> payload.actionValue?.let { action ->
                    moduleContext.executeAction(
                        action,
                        JsonObject(payload.actionArguments.mapValues { JsonPrimitive(it.value) }),
                    )
                }
                // Navigation belongs to the App. The SDK exposes the destination in PushEvent.Opened
                // on both Android and iOS instead of opening it a second time on Android.
                "DEEPLINK", "WEB_URL" -> Unit
            }
        }
    }

    private suspend fun synchronizeToken() = tokenMutex.withLock {
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) return
        if (SdkFeature.PUSH !in moduleContext.enabledFeatures.value) {
            if (preferences.getString(REGISTERED_TOKEN_HASH, null) != DISABLED_MARKER) {
                if (moduleContext.enqueue(EngageModuleOperation.PushTokenChanged(null))) {
                    persistRegisteredTokenHash(DISABLED_MARKER)
                    mutableStatus.value = mutableStatus.value.copy(tokenRegistered = false)
                }
            }
            return
        }
        val token = currentToken ?: runCatching { tokenProvider.token() }.getOrNull() ?: return
        currentToken = token
        submitTokenIfNeededLocked(token)
    }

    private suspend fun submitTokenIfNeeded(token: String) = tokenMutex.withLock {
        submitTokenIfNeededLocked(token)
    }

    private suspend fun submitTokenIfNeededLocked(token: String) {
        if (!canRun()) return
        val hash = token.sha256()
        if (preferences.getString(REGISTERED_TOKEN_HASH, null) == hash) return
        if (moduleContext.enqueue(EngageModuleOperation.PushTokenChanged(token))) {
            persistRegisteredTokenHash(hash)
        }
    }

    private suspend fun synchronizePermission() = permissionMutex.withLock {
        val permission = permissionProvider.current(application)
        mutableStatus.value = mutableStatus.value.copy(permission = permission)
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) return
        if (preferences.getString(REPORTED_PERMISSION, null) == permission.name) return
        if (moduleContext.enqueue(EngageModuleOperation.PushPermissionChanged(permission.name))) {
            check(preferences.edit().putString(REPORTED_PERMISSION, permission.name).commit()) {
                "Could not persist Engage push permission state"
            }
        }
    }

    private suspend fun synchronizeSubscription(force: Boolean = false) = subscriptionMutex.withLock {
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) return
        if (!force && !preferences.getBoolean(PENDING_SUBSCRIPTION, false)) return
        val subscription = storedSubscription()
        val accepted = moduleContext.enqueue(
            EngageModuleOperation.PushSubscriptionChanged(
                optedIn = subscription == PushSubscriptionState.OPTED_IN,
            ),
        )
        if (accepted) {
            check(preferences.edit().putBoolean(PENDING_SUBSCRIPTION, false).commit()) {
                "Could not persist Engage push subscription acknowledgement"
            }
        }
    }

    private fun canRun(): Boolean =
        moduleContext.privacy.value == PrivacyState.OPTED_IN &&
            SdkFeature.PUSH in moduleContext.enabledFeatures.value

    private fun persistSubscription(subscription: PushSubscriptionState) {
        check(
            preferences.edit()
                .putString(SUBSCRIPTION, subscription.name)
                .putBoolean(PENDING_SUBSCRIPTION, true)
                .commit(),
        ) {
            "Could not persist Engage push subscription"
        }
        mutableStatus.value = mutableStatus.value.copy(subscription = subscription)
    }

    private fun persistRegisteredTokenHash(hash: String) {
        check(preferences.edit().putString(REGISTERED_TOKEN_HASH, hash).commit()) {
            "Could not persist Engage push token state"
        }
    }

    private fun storedSubscription(): PushSubscriptionState = preferences.getString(SUBSCRIPTION, null)
        ?.let { runCatching { PushSubscriptionState.valueOf(it) }.getOrNull() }
        ?: PushSubscriptionState.OPTED_IN

    private fun showNotification(payload: EngagePushPayload, image: Bitmap?) {
        val config = moduleContext.config.push.android ?: return
        val title = payload.title ?: return
        val body = payload.body ?: return
        val contentIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)?.let { launcher ->
            for ((key, value) in payload.data) launcher.putExtra(key, value)
            PendingIntent.getActivity(
                application,
                payload.deliveryId.hashCode(),
                launcher.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val dismissIntent = Intent(application, EngagePushDismissReceiver::class.java).apply {
            for ((key, value) in payload.data) putExtra(key, value)
        }
        val deleteIntent = PendingIntent.getBroadcast(
            application,
            payload.deliveryId.hashCode(),
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channel = payload.channelKey?.takeIf { remote ->
            config.channels.any { it.key == remote }
        } ?: config.defaultChannelKey
        val builder = NotificationCompat.Builder(application, channel)
            .setSmallIcon(config.smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setDeleteIntent(deleteIntent)
        contentIntent?.let(builder::setContentIntent)
        if (image != null) {
            builder
                .setLargeIcon(image)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?))
        }
        addCategoryActions(builder, payload)
        config.accentColor?.let { builder.setColor(ContextCompat.getColor(application, it)) }
        if (permissionProvider.current(application) == PushPermission.AUTHORIZED) {
            notifySafely(payload.notificationTag, payload.deliveryId.hashCode(), builder)
        }
    }

    private fun addCategoryActions(builder: NotificationCompat.Builder, payload: EngagePushPayload) {
        val config = moduleContext.config.push.android ?: return
        val category = config.categories.firstOrNull { it.key == payload.categoryKey } ?: return
        category.actions.forEach { action ->
            val intent = Intent(application, EngagePushActionReceiver::class.java).apply {
                payload.data.forEach { (key, value) -> putExtra(key, value) }
                putExtra(EXTRA_ACTION_KEY, action.key)
                putExtra(EXTRA_ACTION_OPENS_APP, action.opensApp)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                application,
                "${payload.deliveryId}\u0000${action.key}".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, application.getString(action.title), pendingIntent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(tag: String?, id: Int, builder: NotificationCompat.Builder) {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!runtimeGranted) return
        try {
            val notification = builder.build()
            notificationObserver(notification)
            NotificationManagerCompat.from(application).notify(tag, id, notification)
        } catch (_: SecurityException) {
            moduleContext.scope.launch { synchronizePermission() }
        }
    }

    private companion object {
        const val PREFERENCES = "engage_push"
        const val SUBSCRIPTION = "subscription"
        const val PENDING_SUBSCRIPTION = "pending_subscription"
        const val REGISTERED_TOKEN_HASH = "registered_token_hash"
        const val REPORTED_PERMISSION = "reported_permission"
        const val DISABLED_MARKER = "disabled"
        const val EXTRA_HANDLED = "engage_push_handled"
        const val EXTRA_ACTION_KEY = "engage_push_action_key"
        const val EXTRA_ACTION_OPENS_APP = "engage_push_action_opens_app"
    }
}

internal fun interface PushTokenProvider {
    suspend fun token(): String
}

private object FirebasePushTokenProvider : PushTokenProvider {
    @Suppress("DEPRECATION")
    override suspend fun token(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
            .addOnCanceledListener { continuation.cancel() }
    }
}

internal fun interface PushPermissionProvider {
    fun current(context: Context): PushPermission
}

private object AndroidPushPermissionProvider : PushPermissionProvider {
    override fun current(context: Context): PushPermission {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return PushPermission.DENIED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return PushPermission.AUTHORIZED
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return PushPermission.AUTHORIZED
        // Android does not expose a public, reliable distinction between "never asked" and
        // "denied" to a library that intentionally does not own the permission prompt.
        return PushPermission.DENIED
    }
}

private class PushOpenCallbacks(
    private val onIntent: (Intent) -> Unit,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, state: Bundle?) = onIntent(activity.intent)
    override fun onActivityResumed(activity: Activity) = onIntent(activity.intent)
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

private object AndroidChannelRegistrar {
    fun register(context: Context, config: EngageConfig) {
        val android = config.push.android ?: return
        validate(android)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        android.channels.forEach { channel -> manager.createNotificationChannel(channel.toNative(context)) }
    }

    private fun validate(config: AndroidPushConfig) {
        require(config.channels.map(AndroidPushChannel::key).distinct().size == config.channels.size) {
            "Android push channel keys must be unique"
        }
        require(config.channels.any { it.key == config.defaultChannelKey }) {
            "Android push defaultChannelKey must reference a configured channel"
        }
        require(config.categories.map { it.key }.distinct().size == config.categories.size) {
            "Android push category keys must be unique"
        }
        require(config.categories.all { category ->
            category.actions.isNotEmpty() &&
                category.actions.map { it.key }.distinct().size == category.actions.size
        }) {
            "Android push categories must contain uniquely keyed actions"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun AndroidPushChannel.toNative(context: Context): NotificationChannel =
        NotificationChannel(key, context.getString(name), importance.toNative()).apply {
            description = this@toNative.description?.let(context::getString)
            setShowBadge(showBadge)
            when (val configuredSound = this@toNative.sound) {
                AndroidPushSound.Default -> Unit
                AndroidPushSound.Silent -> setSound(null, null)
                is AndroidPushSound.Resource -> setSound(
                    "android.resource://${context.packageName}/${configuredSound.resourceId}".toUri(),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            }
        }

    private fun NotificationImportance.toNative(): Int = when (this) {
        NotificationImportance.MIN -> NotificationManager.IMPORTANCE_MIN
        NotificationImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        NotificationImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
        NotificationImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
        NotificationImportance.MAX -> NotificationManager.IMPORTANCE_MAX
    }
}

private fun Intent.stringExtras(): Map<String, String> = extras?.keySet().orEmpty().mapNotNull { key ->
    @Suppress("DEPRECATION")
    (extras?.get(key) as? String)?.let { key to it }
}.toMap()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

private fun EngagePushPayload.customData(): Map<String, String> =
    data.filterKeys { !it.startsWith("engage_") }
