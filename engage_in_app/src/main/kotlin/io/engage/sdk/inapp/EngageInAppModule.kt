package io.engage.sdk.inapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.InApp
import io.engage.sdk.SdkFeature
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageSyncModule
import io.engage.sdk.inapp.render.InAppRenderCallbacks

internal object EngageInAppModule : EngageModule {
    override val id: String = "engage-in-app"
    override val features: Set<SdkFeature> = setOf(SdkFeature.IN_APP)
    override val syncModules: Set<EngageSyncModule> = setOf(EngageSyncModule.IN_APP)
    private var api: InApp? = null

    override fun start(context: EngageModuleContext) {
        if (api == null) {
            context.logInfo("InApp", "module starting")
            api = DefaultInApp(context)
        } else {
            context.logVerbose("InApp", "module start ignored reason=already_started")
        }
    }

    override suspend fun wipe() {
        EngageLogger.warn("InApp", "module wipe requested")
        (api as? DefaultInApp)?.wipe()
    }

    fun requireApi(): InApp = checkNotNull(api) {
        "engage-in-app is installed but Engage.start(context, config) has not completed"
    }

    fun requireRenderCallbacks(): InAppRenderCallbacks = requireApi() as InAppRenderCallbacks
}

public class EngageInAppInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        EngageLogger.debug("InApp", "initialization provider registering module")
        Engage.registerModule(EngageInAppModule)
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
