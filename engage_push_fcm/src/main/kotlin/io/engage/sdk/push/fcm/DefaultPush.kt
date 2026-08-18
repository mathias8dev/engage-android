package io.engage.sdk.push.fcm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
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
import io.engage.sdk.AndroidPushChannel
import io.engage.sdk.AndroidPushConfig
import io.engage.sdk.AndroidPushSound
import io.engage.sdk.EngageConfig
import io.engage.sdk.EngageLogger
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
import io.engage.sdk.spi.scopedPreferencesName
import kotlinx.coroutines.CancellationException
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
    private val preferences = application.getSharedPreferences(
        moduleContext.scopedPreferencesName(PREFERENCES),
        Context.MODE_PRIVATE,
    )
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
        moduleContext.logInfo(
            "Push",
            "initializing permission=${mutableStatus.value.permission} subscription=${mutableStatus.value.subscription} " +
                "foregroundPresentation=${moduleContext.config.push.foregroundPresentation}",
        )
        AndroidChannelRegistrar.register(application, moduleContext.config)
        application.registerActivityLifecycleCallbacks(PushOpenCallbacks(::handleOpenIntent))
        moduleContext.scope.launch {
            moduleContext.documents(EngageSyncModule.PUSH).collectLatest { documents ->
                moduleContext.logVerbose("Push", "remote state documents received count=${documents.size}")
                val state = documents.firstOrNull { it.key == "state" }?.payload
                val registered = (state?.get("tokenRegistered") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                if (registered != null) {
                    mutableStatus.value = mutableStatus.value.copy(tokenRegistered = registered)
                    moduleContext.logInfo("Push", "token registration confirmed registered=$registered")
                }
                val subscription = (state?.get("subscription") as? JsonPrimitive)?.content
                    ?.let { value -> runCatching { PushSubscriptionState.valueOf(value) }.getOrNull() }
                if (subscription != null && !preferences.getBoolean(PENDING_SUBSCRIPTION, false)) {
                    check(preferences.edit().putString(SUBSCRIPTION, subscription.name).commit()) {
                        "Could not persist Engage push subscription state"
                    }
                    mutableStatus.value = mutableStatus.value.copy(subscription = subscription)
                    moduleContext.logInfo("Push", "subscription confirmed state=$subscription")
                }
            }
        }
        moduleContext.scope.launch {
            moduleContext.signals.collect { signal ->
                moduleContext.logVerbose("Push", "core signal=${signal::class.simpleName}")
                when (signal) {
                    EngageSignal.AppOpened -> {
                        appInForeground = true
                        synchronizePermission()
                        synchronizeSubscription()
                        synchronizeToken()
                    }
                    EngageSignal.AppBackgrounded -> {
                        appInForeground = false
                        moduleContext.logDebug("Push", "application backgrounded")
                    }
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
                moduleContext.logDebug("Push", "privacy observed state=$privacy previous=$previousPrivacy")
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
        moduleContext.logInfo("Push", "optIn requested")
        persistSubscription(PushSubscriptionState.OPTED_IN)
        synchronizeSubscription()
    }

    override suspend fun optOut() {
        moduleContext.logInfo("Push", "optOut requested")
        persistSubscription(PushSubscriptionState.OPTED_OUT)
        synchronizeSubscription()
    }

    fun wipe() {
        moduleContext.logWarn("Push", "local push state wipe started")
        currentToken = null
        check(preferences.edit().clear().commit()) { "Could not wipe Engage push state" }
        mutableStatus.value = mutableStatus.value.copy(
            subscription = PushSubscriptionState.OPTED_IN,
            tokenRegistered = false,
        )
        moduleContext.logWarn("Push", "local push state wiped")
    }

    fun onNewToken(token: String) {
        currentToken = token
        moduleContext.logInfo("Push", "FCM token observed hash=${token.sha256().take(TOKEN_HASH_LOG_LENGTH)}")
        moduleContext.scope.launch { submitTokenIfNeeded(token) }
    }

    suspend fun processMessage(data: Map<String, String>) {
        val payload = EngagePushPayload.from(data) ?: return
        if (payload.installationId != null && payload.installationId != moduleContext.installationId.value) {
            moduleContext.logDebug(
                "Push",
                "message ignored deliveryId=${payload.deliveryId} reason=installation_mismatch",
            )
            return
        }
        if (!canRun()) {
            moduleContext.logDebug(
                "Push",
                "message ignored deliveryId=${payload.deliveryId} reason=${disabledReason()}",
            )
            return
        }
        if (!claimDelivery(payload.deliveryId)) {
            moduleContext.logDebug(
                "Push",
                "message ignored deliveryId=${payload.deliveryId} reason=duplicate_delivery",
            )
            return
        }
        val emitted = mutableEvents.tryEmit(
            PushEvent.Received(payload.deliveryId, payload.messageId, payload.customData()),
        )
        moduleContext.logInfo(
            "Push",
            "message received deliveryId=${payload.deliveryId} messageId=${payload.messageId} eventEmitted=$emitted",
        )
        val receiptAccepted = moduleContext.enqueue(
            EngageModuleOperation.PushReceipt(
                payload.deliveryId,
                io.engage.sdk.spi.PushReceiptType.DELIVERED,
            ),
        )
        moduleContext.logDebug(
            "Push",
            "delivery receipt enqueued deliveryId=${payload.deliveryId} accepted=$receiptAccepted",
        )
        if (!appInForeground || moduleContext.config.push.foregroundPresentation == ForegroundPresentation.SHOW) {
            val image = payload.imageUrl?.let { url -> imageLoader.load(url) }
            showNotification(payload, image)
        } else {
            moduleContext.logInfo(
                "Push",
                "notification suppressed deliveryId=${payload.deliveryId} reason=foreground_policy_silent",
            )
        }
    }

    fun onDismiss(intent: Intent) {
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return
        if (!canRun()) {
            moduleContext.logDebug("Push", "dismiss ignored deliveryId=${payload.deliveryId} reason=${disabledReason()}")
            return
        }
        val emitted = mutableEvents.tryEmit(PushEvent.Dismissed(payload.deliveryId, payload.messageId))
        moduleContext.logInfo("Push", "notification dismissed deliveryId=${payload.deliveryId} eventEmitted=$emitted")
    }

    suspend fun onAction(intent: Intent) {
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return
        val actionKey = intent.getStringExtra(EXTRA_ACTION_KEY)?.takeIf(String::isNotBlank) ?: run {
            moduleContext.logWarn("Push", "notification action ignored deliveryId=${payload.deliveryId} reason=missing_key")
            return
        }
        if (!canRun()) {
            moduleContext.logDebug("Push", "action ignored deliveryId=${payload.deliveryId} reason=${disabledReason()}")
            return
        }
        NotificationManagerCompat.from(application).cancel(payload.notificationTag, payload.deliveryId.hashCode())
        val emitted = mutableEvents.tryEmit(
            PushEvent.ActionSelected(payload.deliveryId, payload.messageId, actionKey, payload.customData()),
        )
        moduleContext.logInfo(
            "Push",
            "notification action selected deliveryId=${payload.deliveryId} action=$actionKey eventEmitted=$emitted",
        )
        val receiptAccepted = moduleContext.enqueue(
            EngageModuleOperation.PushReceipt(
                payload.deliveryId,
                io.engage.sdk.spi.PushReceiptType.OPENED,
            ),
        )
        val actionCompleted = moduleContext.executeAction(
            actionKey,
            JsonObject(payload.actionArguments.mapValues { JsonPrimitive(it.value) }),
        )
        moduleContext.logInfo(
            "Push",
            "notification action processed deliveryId=${payload.deliveryId} action=$actionKey " +
                "receiptAccepted=$receiptAccepted completed=$actionCompleted",
        )
        if (intent.getBooleanExtra(EXTRA_ACTION_OPENS_APP, false)) {
            application.packageManager.getLaunchIntentForPackage(application.packageName)?.let { launcher ->
                payload.data.forEach { (key, value) -> launcher.putExtra(key, value) }
                application.startActivity(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                moduleContext.logDebug("Push", "host application launched action=$actionKey")
            }
        }
    }

    override fun handleOpenIntent(intent: Intent): Boolean {
        val prepared = prepareOpen(intent)
        prepared.payload?.let { payload -> moduleContext.scope.launch { processOpen(payload) } }
        return prepared.handled
    }

    suspend fun handleOpenIntentAwaitingWork(intent: Intent): Boolean {
        val prepared = prepareOpen(intent)
        prepared.payload?.let { processOpen(it) }
        return prepared.handled
    }

    private fun prepareOpen(intent: Intent): PreparedOpen {
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) {
            moduleContext.logVerbose("Push", "open intent ignored reason=already_handled")
            return PreparedOpen(handled = true)
        }
        val payload = EngagePushPayload.from(intent.stringExtras()) ?: return PreparedOpen(handled = false)
        intent.putExtra(EXTRA_HANDLED, true)
        if (!canRun()) {
            moduleContext.logDebug("Push", "open ignored deliveryId=${payload.deliveryId} reason=${disabledReason()}")
            return PreparedOpen(handled = true)
        }
        val deepLink = payload.actionValue.takeIf { payload.actionType == "DEEPLINK" }
        val emitted = mutableEvents.tryEmit(
            PushEvent.Opened(payload.deliveryId, payload.messageId, deepLink, payload.customData()),
        )
        moduleContext.logInfo(
            "Push",
            "notification opened deliveryId=${payload.deliveryId} messageId=${payload.messageId} " +
                "actionType=${payload.actionType} eventEmitted=$emitted",
        )
        return PreparedOpen(handled = true, payload = payload)
    }

    private suspend fun processOpen(payload: EngagePushPayload) {
        val receiptAccepted = moduleContext.enqueue(
            EngageModuleOperation.PushReceipt(
                payload.deliveryId,
                io.engage.sdk.spi.PushReceiptType.OPENED,
            ),
        )
        moduleContext.logDebug(
            "Push",
            "open receipt enqueued deliveryId=${payload.deliveryId} accepted=$receiptAccepted",
        )
        when (payload.actionType) {
            "CUSTOM" -> payload.actionValue?.let { action ->
                val completed = moduleContext.executeAction(
                    action,
                    JsonObject(payload.actionArguments.mapValues { JsonPrimitive(it.value) }),
                )
                moduleContext.logInfo("Push", "custom open action=$action completed=$completed")
            }
            // Application deep links remain owned by the host. WEB_URL is a complete
            // Engage action and opens the external browser without host-side plumbing.
            "DEEPLINK" -> Unit
            "WEB_URL" -> openWebUrl(payload)
        }
    }

    private fun openWebUrl(payload: EngagePushPayload) {
        val uri = payload.actionValue?.toUri()?.takeIf { destination ->
            destination.scheme?.lowercase() in setOf("http", "https") && !destination.host.isNullOrBlank()
        } ?: run {
            moduleContext.logWarn(
                "Push",
                "web URL ignored deliveryId=${payload.deliveryId} reason=invalid_destination",
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            application.startActivity(intent)
            moduleContext.logInfo("Push", "web URL opened deliveryId=${payload.deliveryId} host=${uri.host}")
        } catch (error: ActivityNotFoundException) {
            moduleContext.logWarn(
                "Push",
                "web URL open failed deliveryId=${payload.deliveryId} reason=no_handler",
                error,
            )
        } catch (error: SecurityException) {
            moduleContext.logWarn(
                "Push",
                "web URL open failed deliveryId=${payload.deliveryId} reason=security_exception",
                error,
            )
        }
    }

    private data class PreparedOpen(
        val handled: Boolean,
        val payload: EngagePushPayload? = null,
    )

    private suspend fun synchronizeToken() = tokenMutex.withLock {
        moduleContext.logVerbose("Push", "token synchronization started")
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) {
            moduleContext.logDebug("Push", "token synchronization skipped reason=privacy_opted_out")
            return
        }
        if (SdkFeature.PUSH !in moduleContext.enabledFeatures.value) {
            if (preferences.getString(REGISTERED_TOKEN_HASH, null) != DISABLED_MARKER) {
                if (moduleContext.enqueue(EngageModuleOperation.PushTokenChanged(null))) {
                    persistRegisteredTokenHash(DISABLED_MARKER)
                    mutableStatus.value = mutableStatus.value.copy(tokenRegistered = false)
                    moduleContext.logInfo("Push", "remote token removal enqueued because push feature is disabled")
                }
            }
            return
        }
        val token = currentToken ?: try {
            tokenProvider.token()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            moduleContext.logWarn("Push", "FCM token lookup failed", error)
            null
        } ?: run {
            moduleContext.logDebug("Push", "token synchronization stopped reason=no_token")
            return
        }
        currentToken = token
        submitTokenIfNeededLocked(token)
    }

    private suspend fun submitTokenIfNeeded(token: String) = tokenMutex.withLock {
        submitTokenIfNeededLocked(token)
    }

    private suspend fun submitTokenIfNeededLocked(token: String) {
        if (!canRun()) {
            moduleContext.logDebug("Push", "token submission skipped reason=${disabledReason()}")
            return
        }
        val hash = token.sha256()
        if (preferences.getString(REGISTERED_TOKEN_HASH, null) == hash) {
            moduleContext.logVerbose("Push", "token submission deduplicated hash=${hash.take(TOKEN_HASH_LOG_LENGTH)}")
            return
        }
        if (moduleContext.enqueue(EngageModuleOperation.PushTokenChanged(token))) {
            persistRegisteredTokenHash(hash)
            moduleContext.logInfo("Push", "token change enqueued hash=${hash.take(TOKEN_HASH_LOG_LENGTH)}")
        } else {
            moduleContext.logWarn("Push", "token change rejected by core hash=${hash.take(TOKEN_HASH_LOG_LENGTH)}")
        }
    }

    private suspend fun synchronizePermission() = permissionMutex.withLock {
        val permission = permissionProvider.current(application)
        mutableStatus.value = mutableStatus.value.copy(permission = permission)
        moduleContext.logDebug("Push", "permission observed state=$permission")
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) {
            moduleContext.logDebug("Push", "permission synchronization skipped reason=privacy_opted_out")
            return
        }
        if (preferences.getString(REPORTED_PERMISSION, null) == permission.name) {
            moduleContext.logVerbose("Push", "permission synchronization deduplicated state=$permission")
            return
        }
        if (moduleContext.enqueue(EngageModuleOperation.PushPermissionChanged(permission.name))) {
            check(preferences.edit().putString(REPORTED_PERMISSION, permission.name).commit()) {
                "Could not persist Engage push permission state"
            }
            moduleContext.logInfo("Push", "permission change enqueued state=$permission")
        }
    }

    private suspend fun synchronizeSubscription(force: Boolean = false) = subscriptionMutex.withLock {
        moduleContext.logVerbose("Push", "subscription synchronization started force=$force")
        if (moduleContext.privacy.value != PrivacyState.OPTED_IN) {
            moduleContext.logDebug("Push", "subscription synchronization skipped reason=privacy_opted_out")
            return
        }
        if (!force && !preferences.getBoolean(PENDING_SUBSCRIPTION, false)) {
            moduleContext.logVerbose("Push", "subscription synchronization skipped reason=no_pending_change")
            return
        }
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
        moduleContext.logInfo("Push", "subscription synchronization state=$subscription accepted=$accepted")
    }

    private fun canRun(): Boolean =
        moduleContext.privacy.value == PrivacyState.OPTED_IN &&
            SdkFeature.PUSH in moduleContext.enabledFeatures.value

    private fun disabledReason(): String = when {
        moduleContext.privacy.value != PrivacyState.OPTED_IN -> "privacy_opted_out"
        SdkFeature.PUSH !in moduleContext.enabledFeatures.value -> "feature_disabled"
        else -> "unknown"
    }

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
        moduleContext.logInfo("Push", "subscription persisted state=$subscription pending=true")
    }

    private fun persistRegisteredTokenHash(hash: String) {
        check(preferences.edit().putString(REGISTERED_TOKEN_HASH, hash).commit()) {
            "Could not persist Engage push token state"
        }
        moduleContext.logVerbose(
            "Push",
            "token state persisted marker=${if (hash == DISABLED_MARKER) hash else hash.take(TOKEN_HASH_LOG_LENGTH)}",
        )
    }

    private fun storedSubscription(): PushSubscriptionState = preferences.getString(SUBSCRIPTION, null)
        ?.let { runCatching { PushSubscriptionState.valueOf(it) }.getOrNull() }
        ?: PushSubscriptionState.OPTED_IN

    private fun claimDelivery(deliveryId: String): Boolean = synchronized(preferences) {
        val processed = preferences.getString(PROCESSED_DELIVERIES, null)
            ?.lineSequence()
            ?.filter(String::isNotBlank)
            ?.toMutableList()
            ?: mutableListOf()
        if (deliveryId in processed) return@synchronized false
        processed += deliveryId
        val bounded = processed.takeLast(MAX_PROCESSED_DELIVERIES).joinToString("\n")
        check(preferences.edit().putString(PROCESSED_DELIVERIES, bounded).commit()) {
            "Could not persist processed Engage push deliveries"
        }
        true
    }

    private fun showNotification(payload: EngagePushPayload, image: Bitmap?) {
        val config = moduleContext.config.push.android ?: run {
            moduleContext.logWarn("Push", "notification skipped deliveryId=${payload.deliveryId} reason=no_android_config")
            return
        }
        val title = payload.title ?: run {
            moduleContext.logWarn("Push", "notification skipped deliveryId=${payload.deliveryId} reason=missing_title")
            return
        }
        val body = payload.body ?: run {
            moduleContext.logWarn("Push", "notification skipped deliveryId=${payload.deliveryId} reason=missing_body")
            return
        }
        val openIntent = Intent(application, EngagePushOpenActivity::class.java).apply {
            for ((key, value) in payload.data) putExtra(key, value)
        }
        val contentIntent = PendingIntent.getActivity(
            application,
            payload.deliveryId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        moduleContext.logDebug(
            "Push",
            "notification building deliveryId=${payload.deliveryId} channel=$channel " +
                "requestedChannel=${payload.channelKey} category=${payload.categoryKey} richImage=${image != null}",
        )
        val builder = NotificationCompat.Builder(application, channel)
            .setSmallIcon(config.smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setDeleteIntent(deleteIntent)
        builder.setContentIntent(contentIntent)
        if (image != null) {
            builder
                .setLargeIcon(image)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Bitmap?))
        }
        addCategoryActions(builder, payload)
        config.accentColor?.let { builder.setColor(ContextCompat.getColor(application, it)) }
        if (permissionProvider.current(application) == PushPermission.AUTHORIZED) {
            notifySafely(payload.notificationTag, payload.deliveryId.hashCode(), builder)
        } else {
            moduleContext.logWarn(
                "Push",
                "notification skipped deliveryId=${payload.deliveryId} reason=permission_not_authorized",
            )
        }
    }

    private fun addCategoryActions(builder: NotificationCompat.Builder, payload: EngagePushPayload) {
        val config = moduleContext.config.push.android ?: return
        val category = config.categories.firstOrNull { it.key == payload.categoryKey } ?: return
        moduleContext.logDebug(
            "Push",
            "adding category actions deliveryId=${payload.deliveryId} category=${category.key} count=${category.actions.size}",
        )
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
        if (!runtimeGranted) {
            moduleContext.logWarn("Push", "notification post skipped id=$id reason=runtime_permission_denied")
            return
        }
        try {
            val notification = builder.build()
            notificationObserver(notification)
            NotificationManagerCompat.from(application).notify(tag, id, notification)
            moduleContext.logInfo("Push", "notification posted tag=$tag id=$id")
        } catch (error: SecurityException) {
            moduleContext.logWarn("Push", "notification post failed tag=$tag id=$id", error)
            moduleContext.scope.launch { synchronizePermission() }
        }
    }

    private companion object {
        const val PREFERENCES = "engage_push"
        const val PROCESSED_DELIVERIES = "processed_delivery_ids"
        const val MAX_PROCESSED_DELIVERIES = 100
        const val SUBSCRIPTION = "subscription"
        const val PENDING_SUBSCRIPTION = "pending_subscription"
        const val REGISTERED_TOKEN_HASH = "registered_token_hash"
        const val REPORTED_PERMISSION = "reported_permission"
        const val DISABLED_MARKER = "disabled"
        const val TOKEN_HASH_LOG_LENGTH = 12
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
        val android = config.push.android ?: run {
            EngageLogger.debug("Push", "Android channel registration skipped reason=no_config")
            return
        }
        validate(android)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            EngageLogger.debug("Push", "Android channel registration skipped reason=api_below_26")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        android.channels.forEach { channel ->
            manager.createNotificationChannel(channel.toNative(context))
            EngageLogger.info(
                "Push",
                "Android channel registered key=${channel.key} importance=${channel.importance} " +
                    "badge=${channel.showBadge} sound=${channel.sound::class.simpleName}",
            )
        }
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

internal fun EngagePushPayload.customData(): Map<String, String> =
    data.filterKeys { !it.startsWith("engage_") }
