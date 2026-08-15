package io.engage.sdk.push.fcm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.Push
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageSyncModule

internal object EngagePushModule : EngageModule {
    override val id: String = "engage-push-fcm"
    override val features: Set<SdkFeature> = setOf(SdkFeature.PUSH)
    override val syncModules: Set<EngageSyncModule> = setOf(EngageSyncModule.PUSH)
    @Volatile private var api: Push? = null

    override fun start(context: EngageModuleContext) {
        if (api == null) {
            context.logInfo("Push", "FCM module starting")
            api = DefaultPush(context)
        } else {
            context.logVerbose("Push", "FCM module start ignored reason=already_started")
        }
    }

    override suspend fun wipe() {
        EngageLogger.warn("Push", "module wipe requested")
        (api as? DefaultPush)?.wipe()
    }

    fun requireApi(): Push = checkNotNull(api) {
        "engage-push-fcm is installed but Engage.start(context, config) has not completed"
    }

    fun onNewToken(token: String) = (api as? DefaultPush)?.onNewToken(token) ?: run {
        EngageLogger.warn("Push", "FCM token received before Engage.start; token value redacted")
    }

    suspend fun processMessage(data: Map<String, String>): PushWorkOutcome =
        (api as? DefaultPush)?.let { push ->
            push.processMessage(data)
            PushWorkOutcome.PROCESSED
        } ?: run {
            EngageLogger.warn("Push", "durable FCM work is waiting for Engage.start")
            PushWorkOutcome.NOT_READY
        }

    fun onDismiss(intent: android.content.Intent) = (api as? DefaultPush)?.onDismiss(intent)
        ?: run { EngageLogger.warn("Push", "dismiss received before Engage.start") }

    suspend fun onAction(intent: android.content.Intent) {
        (api as? DefaultPush)?.onAction(intent)
            ?: EngageLogger.warn("Push", "action received before Engage.start")
    }

    fun onOpen(intent: android.content.Intent): Boolean = (api as? DefaultPush)?.handleOpenIntent(intent) ?: run {
        EngageLogger.warn("Push", "open received before Engage.start")
        false
    }

    suspend fun onOpenAwaitingWork(intent: android.content.Intent): Boolean =
        (api as? DefaultPush)?.handleOpenIntentAwaitingWork(intent) ?: run {
            EngageLogger.warn("Push", "open received before Engage.start")
            false
        }
}

public class EngagePushInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        EngageLogger.debug("Push", "initialization provider registering module")
        Engage.registerModule(EngagePushModule)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
