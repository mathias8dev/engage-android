package io.engage.sdk.spi

import android.content.Context
import androidx.annotation.RestrictTo
import io.engage.sdk.EngageConfig
import io.engage.sdk.EngageLogger
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

    /** Durably removes every functional value owned by this module before a privacy wipe returns. */
    public suspend fun wipe() = Unit
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
    public data object LocalDataWiped : EngageSignal
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public sealed interface EngageModuleOperation {
    public data class PushTokenChanged(val token: String?) : EngageModuleOperation
    public data class PushSubscriptionChanged(val optedIn: Boolean) : EngageModuleOperation
    public data class PushPermissionChanged(val permission: String) : EngageModuleOperation

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
public enum class EngageHttpMethod { GET, POST }

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public data class EngageHttpRequest(
    val method: EngageHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: JsonObject? = null,
)

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public data class EngageHttpResponse(
    val statusCode: Int,
    val body: JsonObject?,
) {
    public val isSuccessful: Boolean get() = statusCode in 200..299
}

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface EngageModuleContext {
    public val applicationContext: Context
    public val config: EngageConfig
    /** Stable namespace separating durable optional-module state for each Engage application. */
    public val storageScope: String
        get() {
            var hash = -3750763034362895579L
            config.appKey.toByteArray(Charsets.UTF_8).forEach { byte ->
                hash = (hash xor byte.toUByte().toLong()) * 1099511628211L
            }
            return hash.toULong().toString(16).padStart(16, '0')
        }
    public val scope: CoroutineScope
    public val installationId: StateFlow<String?>
    public val generation: StateFlow<Long>
    public val privacy: StateFlow<PrivacyState>
    public val enabledFeatures: StateFlow<Set<SdkFeature>>
    public val signals: SharedFlow<EngageSignal>

    public fun logVerbose(component: String, message: String) = EngageLogger.verbose(component, message)
    public fun logDebug(component: String, message: String) = EngageLogger.debug(component, message)
    public fun logInfo(component: String, message: String) = EngageLogger.info(component, message)
    public fun logWarn(component: String, message: String, error: Throwable? = null) =
        EngageLogger.warn(component, message, error)
    public fun logError(component: String, message: String, error: Throwable? = null) =
        EngageLogger.error(component, message, error)

    public fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>>
    /** Returns true only after the operation has been accepted by the durable core outbox. */
    public suspend fun enqueue(operation: EngageModuleOperation): Boolean
    public suspend fun refresh()
    public suspend fun executeAction(name: String, arguments: JsonObject): Boolean
    public suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse =
        error("This Engage core does not provide optional-module HTTP transport")
}
