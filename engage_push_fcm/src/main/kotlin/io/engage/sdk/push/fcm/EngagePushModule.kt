package io.engage.sdk.push.fcm

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.engage.sdk.Engage
import io.engage.sdk.Push
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageSyncModule

internal object EngagePushModule : EngageModule {
    override val id: String = "engage-push-fcm"
    override val features: Set<SdkFeature> = setOf(SdkFeature.PUSH)
    override val syncModules: Set<EngageSyncModule> = setOf(EngageSyncModule.PUSH)
    private var api: Push? = null

    override fun start(context: EngageModuleContext) {
        if (api == null) api = DefaultPush(context)
    }

    override suspend fun wipe() {
        (api as? DefaultPush)?.wipe()
    }

    fun requireApi(): Push = checkNotNull(api) {
        "engage-push-fcm is installed but Engage.start(context, config) has not completed"
    }

    fun onNewToken(token: String) = (api as? DefaultPush)?.onNewToken(token)
    fun onMessage(message: com.google.firebase.messaging.RemoteMessage) = (api as? DefaultPush)?.onMessage(message)
    fun onDismiss(intent: android.content.Intent) = (api as? DefaultPush)?.onDismiss(intent)
    fun onAction(intent: android.content.Intent) = (api as? DefaultPush)?.onAction(intent)
}

public class EngagePushInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Engage.registerModule(EngagePushModule)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
