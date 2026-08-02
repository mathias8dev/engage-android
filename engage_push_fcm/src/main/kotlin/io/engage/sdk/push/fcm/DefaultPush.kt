package io.engage.sdk.push.fcm

import io.engage.sdk.Push
import io.engage.sdk.PushEvent
import io.engage.sdk.PushPermission
import io.engage.sdk.PushStatus
import io.engage.sdk.PushSubscriptionState
import io.engage.sdk.spi.EngageModuleContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

internal class DefaultPush(private val context: EngageModuleContext) : Push {
    private val mutableStatus = MutableStateFlow(
        PushStatus(PushPermission.NOT_DETERMINED, PushSubscriptionState.OPTED_IN, false),
    )
    private val mutableEvents = MutableSharedFlow<PushEvent>(extraBufferCapacity = 32)

    override val status: StateFlow<PushStatus> = mutableStatus
    override val events: SharedFlow<PushEvent> = mutableEvents

    override suspend fun optIn() {
        mutableStatus.value = mutableStatus.value.copy(subscription = PushSubscriptionState.OPTED_IN)
    }

    override suspend fun optOut() {
        mutableStatus.value = mutableStatus.value.copy(subscription = PushSubscriptionState.OPTED_OUT)
    }
}

