package io.engage.sdk.spi

import android.content.Context
import androidx.annotation.RestrictTo
import io.engage.sdk.EngageConfig
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** SPI used only by official optional Engage artifacts. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface EngageModule {
    public val id: String
    public val features: Set<SdkFeature>
    public val syncModules: Set<EngageSyncModule>

    public fun start(context: EngageModuleContext)
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class EngageSyncModule {
    PUSH,
    IN_APP,
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public data class EngageRemoteDocument(
    val key: String,
    val revision: Long,
    val payload: JsonObject,
)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed interface EngageSignal {
    public data class EventOccurred(val name: String, val properties: JsonObject) : EngageSignal
    public data class ScreenViewed(val key: String) : EngageSignal
    public data object ScreenCleared : EngageSignal
    public data object AppOpened : EngageSignal
    public data object AppBackgrounded : EngageSignal
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed interface EngageModuleOperation {
    public data class PushTokenChanged(val token: String?) : EngageModuleOperation
    public data class PushSubscriptionChanged(val optedIn: Boolean) : EngageModuleOperation

    public data class Interaction(
        val experienceId: String,
        val messageId: String,
        val variantId: String?,
        val type: InteractionType,
    ) : EngageModuleOperation

    public data class PushReceipt(val deliveryId: String, val type: PushReceiptType) : EngageModuleOperation
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class InteractionType { IMPRESSION, CLICK, DISMISS, CONVERSION }

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public enum class PushReceiptType { DELIVERED, OPENED }

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface EngageModuleContext {
    public val applicationContext: Context
    public val config: EngageConfig
    public val scope: CoroutineScope
    public val installationId: StateFlow<String?>
    public val generation: StateFlow<Long>
    public val privacy: StateFlow<PrivacyState>
    public val enabledFeatures: StateFlow<Set<SdkFeature>>
    public val signals: SharedFlow<EngageSignal>

    public fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>>
    public suspend fun enqueue(operation: EngageModuleOperation)
    public suspend fun refresh()
    public suspend fun executeAction(name: String, arguments: JsonObject): Boolean
}

