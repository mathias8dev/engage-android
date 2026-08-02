package io.engage.sdk.core.application

import android.os.SystemClock
import io.engage.sdk.AttributeEditor
import io.engage.sdk.Events
import io.engage.sdk.Installation
import io.engage.sdk.InstallationSubscriptionEditor
import io.engage.sdk.Profile
import io.engage.sdk.ProfileSubscriptionEditor
import io.engage.sdk.SdkFeature
import io.engage.sdk.TagEditor
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.spi.EngageSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DefaultInstallation(
    sessions: SessionStore,
    private val coordinator: OperationCoordinator,
    private val scope: CoroutineScope,
) : Installation {
    override val id: StateFlow<String?> = sessions.session
        .map { it?.installationId }
        .stateIn(scope, SharingStarted.Eagerly, sessions.session.value?.installationId)

    override suspend fun issueBindingCode(): String = coordinator.issueBindingCode()

    override fun editAttributes(block: AttributeEditor.() -> Unit) {
        val changes = AttributeEditor().apply(block).build()
        if (changes.isEmpty) return
        scope.launch {
            coordinator.enqueue(
                OperationType.INSTALLATION_ATTRIBUTES_EDITED,
                buildJsonObject {
                    put("set", JsonObject(changes.values))
                    put("remove", changes.removals.asJsonArray())
                },
            )
        }
    }

    override fun editSubscriptions(block: InstallationSubscriptionEditor.() -> Unit) {
        val changes = InstallationSubscriptionEditor().apply(block).build()
        if (changes.isEmpty()) return
        scope.launch {
            coordinator.enqueue(
                OperationType.INSTALLATION_SUBSCRIPTIONS_EDITED,
                buildJsonObject {
                    put(
                        "changes",
                        buildJsonArray {
                            changes.forEach { change ->
                                add(buildJsonObject {
                                    put("list", change.list)
                                    put("subscribed", change.subscribed)
                                })
                            }
                        },
                    )
                },
            )
        }
    }
}

internal class DefaultProfile(
    private val coordinator: OperationCoordinator,
    private val scope: CoroutineScope,
) : Profile {
    override fun editAttributes(block: AttributeEditor.() -> Unit) {
        val changes = AttributeEditor().apply(block).build()
        if (changes.isEmpty) return
        scope.launch {
            coordinator.enqueue(
                OperationType.PROFILE_ATTRIBUTES_EDITED,
                buildJsonObject {
                    put("set", JsonObject(changes.values))
                    put("remove", changes.removals.asJsonArray())
                },
            )
        }
    }

    override fun editTags(block: TagEditor.() -> Unit) {
        val changes = TagEditor().apply(block).build()
        if (changes.isEmpty) return
        scope.launch {
            coordinator.enqueue(
                OperationType.PROFILE_TAGS_EDITED,
                buildJsonObject {
                    put("add", changes.additions.asJsonArray())
                    put("remove", changes.removals.asJsonArray())
                },
            )
        }
    }

    override fun editSubscriptions(block: ProfileSubscriptionEditor.() -> Unit) {
        val changes = ProfileSubscriptionEditor().apply(block).build()
        if (changes.isEmpty()) return
        scope.launch {
            coordinator.enqueue(
                OperationType.PROFILE_SUBSCRIPTIONS_EDITED,
                buildJsonObject {
                    put(
                        "changes",
                        buildJsonArray {
                            changes.forEach { change ->
                                add(buildJsonObject {
                                    put("list", change.list)
                                    put("channel", change.channel.name)
                                    put("subscribed", change.subscribed)
                                })
                            }
                        },
                    )
                },
            )
        }
    }
}

internal class DefaultEvents(
    private val coordinator: OperationCoordinator,
    private val features: StateFlow<Set<SdkFeature>>,
    private val signals: MutableSharedFlow<EngageSignal>,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : Events {
    private var currentScreen: String? = null
    private var previousScreen: String? = null
    private var visibleSince: Long? = null
    private var accumulatedVisibleMillis = 0L

    override fun track(name: String, block: io.engage.sdk.EventEditor.() -> Unit) {
        io.engage.sdk.validateEventName(name)
        val event = io.engage.sdk.EventEditor().apply(block).build()
        val properties = JsonObject(event.properties)
        if (SdkFeature.IN_APP in features.value) {
            signals.tryEmit(EngageSignal.EventOccurred(name, properties))
        }
        if (SdkFeature.ANALYTICS !in features.value) return
        scope.launch {
            coordinator.enqueue(
                OperationType.EVENT_TRACKED,
                buildJsonObject {
                    put("name", name)
                    put("properties", properties)
                    event.value?.let { put("value", it) }
                    event.transactionId?.let { put("transactionId", it) }
                },
            )
        }
    }

    override fun trackScreen(screenKey: String) {
        io.engage.sdk.validateScreenKey(screenKey)
        if (screenKey == currentScreen) return
        val now = elapsedRealtime()
        val previousDuration = visibleSince?.let { accumulatedVisibleMillis + (now - it).coerceAtLeast(0) }
        previousScreen = currentScreen
        currentScreen = screenKey
        visibleSince = now
        accumulatedVisibleMillis = 0
        if (SdkFeature.IN_APP in features.value) signals.tryEmit(EngageSignal.ScreenViewed(screenKey))
        if (SdkFeature.ANALYTICS !in features.value) return
        scope.launch {
            coordinator.enqueue(
                OperationType.SCREEN_VIEWED,
                buildJsonObject {
                    put("screenKey", screenKey)
                    previousScreen?.let { put("previousScreenKey", it) }
                    previousDuration?.let { put("previousVisibleDurationMillis", it) }
                },
            )
        }
    }

    override fun clearScreen() {
        currentScreen = null
        previousScreen = null
        visibleSince = null
        accumulatedVisibleMillis = 0
        if (SdkFeature.IN_APP in features.value) signals.tryEmit(EngageSignal.ScreenCleared)
    }

    fun onBackground() {
        val since = visibleSince ?: return
        accumulatedVisibleMillis += (elapsedRealtime() - since).coerceAtLeast(0)
        visibleSince = null
    }

    fun onForeground() {
        if (currentScreen != null && visibleSince == null) visibleSince = elapsedRealtime()
    }

    override suspend fun flush() = coordinator.flush()
}

private fun Set<String>.asJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))

