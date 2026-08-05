package io.engage.sdk.messagecenter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.MessageCenter
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext

internal object EngageMessageCenterModule : EngageModule {
    override val id: String = "engage-message-center"
    override val features: Set<SdkFeature> = setOf(SdkFeature.MESSAGE_CENTER)
    override val syncModules = emptySet<io.engage.sdk.spi.EngageSyncModule>()
    private var api: MessageCenter? = null

    override fun start(context: EngageModuleContext) {
        if (api == null) {
            context.logInfo("MessageCenter", "module starting")
            api = DefaultMessageCenter(context)
        } else {
            context.logDebug("MessageCenter", "module start ignored reason=already_started")
        }
    }

    override suspend fun wipe() {
        EngageLogger.warn("MessageCenter", "module wipe requested")
        (api as? DefaultMessageCenter)?.wipe()
    }

    fun requireApi(): MessageCenter = checkNotNull(api) {
        "engage-message-center is installed but Engage.start(context, config) has not completed"
    }
}

public class EngageMessageCenterInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        EngageLogger.debug("MessageCenter", "init provider registering module")
        Engage.registerModule(EngageMessageCenterModule)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
