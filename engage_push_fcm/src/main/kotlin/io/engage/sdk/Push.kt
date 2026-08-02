package io.engage.sdk

import io.engage.sdk.push.fcm.EngagePushModule
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

public enum class PushSubscriptionState { OPTED_IN, OPTED_OUT }

public enum class PushPermission { NOT_DETERMINED, DENIED, AUTHORIZED, PROVISIONAL, EPHEMERAL }

public data class PushStatus(
    val permission: PushPermission,
    val subscription: PushSubscriptionState,
    val tokenRegistered: Boolean,
)

public sealed interface PushEvent {
    public data class Received(
        val deliveryId: String,
        val messageId: String,
        val data: Map<String, String>,
    ) : PushEvent

    public data class Opened(
        val deliveryId: String,
        val messageId: String,
        val deepLink: String?,
        val data: Map<String, String>,
    ) : PushEvent

    public data class Dismissed(
        val deliveryId: String,
        val messageId: String,
    ) : PushEvent

    public data class ActionSelected(
        val deliveryId: String,
        val messageId: String,
        val actionKey: String,
        val data: Map<String, String>,
    ) : PushEvent
}

public interface Push {
    val status: StateFlow<PushStatus>
    val events: SharedFlow<PushEvent>

    suspend fun optIn()
    suspend fun optOut()
}

public val Engage.push: Push get() = EngagePushModule.requireApi()
