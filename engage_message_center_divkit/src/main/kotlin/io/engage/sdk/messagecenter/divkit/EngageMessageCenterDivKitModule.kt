package io.engage.sdk.messagecenter.divkit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import io.engage.sdk.Engage
import io.engage.sdk.MessageCenter
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.messageCenter
import io.engage.sdk.messagecenter.divkit.data.RenderingRepository
import io.engage.sdk.messagecenter.divkit.data.RenderingStore
import io.engage.sdk.messagecenter.divkit.render.EngageMessageCenterActivity
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageSignal
import kotlinx.coroutines.launch

internal object EngageMessageCenterDivKitModule : EngageModule {
    override val id: String = "engage-message-center-divkit"
    override val features = emptySet<io.engage.sdk.SdkFeature>()
    override val syncModules = emptySet<io.engage.sdk.spi.EngageSyncModule>()

    @Volatile
    private var runtime: MessageCenterUiRuntime? = null

    override fun start(context: EngageModuleContext) {
        if (runtime == null) runtime = MessageCenterUiRuntime(context)
    }

    override suspend fun wipe() {
        runtime?.repository?.clear()
    }

    fun requireRuntime(): MessageCenterUiRuntime = checkNotNull(runtime) {
        "Engage.start(context, config) must complete before displaying the Message Center"
    }
}

internal class MessageCenterUiRuntime(private val context: EngageModuleContext) {
    val repository = RenderingRepository(
        RenderingStore(context.applicationContext),
        context.generation,
        support = { renderingSupport() },
    )

    init {
        context.scope.launch {
            context.generation.collect(repository::activateGeneration)
        }
        context.scope.launch {
            context.signals.collect { signal ->
                if (signal == EngageSignal.LocalDataWiped) repository.clear()
            }
        }
    }

    fun display(messageCenter: MessageCenter) {
        check(messageCenter is MessageCenterRenderingSupport) {
            "engage-message-center-divkit requires the official engage-message-center artifact"
        }
        context.applicationContext.startActivity(
            Intent(context.applicationContext, EngageMessageCenterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun renderingSupport(): MessageCenterRenderingSupport =
        Engage.messageCenter as? MessageCenterRenderingSupport
            ?: error("engage-message-center-divkit requires the official engage-message-center artifact")
}

public class EngageMessageCenterDivKitInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        Engage.registerModule(EngageMessageCenterDivKitModule)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
