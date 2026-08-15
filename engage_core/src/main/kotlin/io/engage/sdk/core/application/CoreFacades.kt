package io.engage.sdk.core.application

import android.os.SystemClock
import io.engage.sdk.AttributeEditor
import io.engage.sdk.Events
import io.engage.sdk.Installation
import io.engage.sdk.InstallationSubscriptionEditor
import io.engage.sdk.EngageLogger
import io.engage.sdk.Profile
import io.engage.sdk.ProfileSubscriptionEditor
import io.engage.sdk.PrivacyState
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

    override suspend fun issueBindingCode(): String {
        EngageLogger.info("Installation", "binding code requested")
        return coordinator.issueBindingCode().also {
            EngageLogger.info("Installation", "binding code issued length=${it.length}")
        }
    }

    override suspend fun editAttributes(block: AttributeEditor.() -> Unit) {
        val changes = AttributeEditor().apply(block).build()
        if (changes.isEmpty) {
            EngageLogger.debug("Installation", "attribute edit ignored because it is empty")
            return
        }
        EngageLogger.debug(
            "Installation",
            "attribute edit setKeys=${changes.values.keys.sorted()} removeKeys=${changes.removals.sorted()}",
        )
        coordinator.enqueue(
            OperationType.INSTALLATION_ATTRIBUTES_EDITED,
            buildJsonObject {
                put("set", JsonObject(changes.values))
                put("remove", changes.removals.asJsonArray())
            },
        )
    }

    override suspend fun editSubscriptions(block: InstallationSubscriptionEditor.() -> Unit) {
        val changes = InstallationSubscriptionEditor().apply(block).build()
        if (changes.isEmpty()) {
            EngageLogger.debug("Installation", "subscription edit ignored because it is empty")
            return
        }
        EngageLogger.debug("Installation", "subscription edit count=${changes.size}")
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

internal class DefaultProfile(
    private val coordinator: OperationCoordinator,
) : Profile {
    override suspend fun editAttributes(block: AttributeEditor.() -> Unit) {
        val changes = AttributeEditor().apply(block).build()
        if (changes.isEmpty) {
            EngageLogger.debug("Profile", "attribute edit ignored because it is empty")
            return
        }
        EngageLogger.debug(
            "Profile",
            "attribute edit setKeys=${changes.values.keys.sorted()} removeKeys=${changes.removals.sorted()}",
        )
        coordinator.enqueue(
            OperationType.PROFILE_ATTRIBUTES_EDITED,
            buildJsonObject {
                put("set", JsonObject(changes.values))
                put("remove", changes.removals.asJsonArray())
            },
        )
    }

    override suspend fun editTags(block: TagEditor.() -> Unit) {
        val changes = TagEditor().apply(block).build()
        if (changes.isEmpty) {
            EngageLogger.debug("Profile", "tag edit ignored because it is empty")
            return
        }
        EngageLogger.debug(
            "Profile",
            "tag edit additions=${changes.additions.size} removals=${changes.removals.size}",
        )
        coordinator.enqueue(
            OperationType.PROFILE_TAGS_EDITED,
            buildJsonObject {
                put("add", changes.additions.asJsonArray())
                put("remove", changes.removals.asJsonArray())
            },
        )
    }

    override suspend fun editSubscriptions(block: ProfileSubscriptionEditor.() -> Unit) {
        val changes = ProfileSubscriptionEditor().apply(block).build()
        if (changes.isEmpty()) {
            EngageLogger.debug("Profile", "subscription edit ignored because it is empty")
            return
        }
        EngageLogger.debug("Profile", "subscription edit count=${changes.size}")
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

internal class DefaultEvents(
    private val coordinator: OperationCoordinator,
    private val features: StateFlow<Set<SdkFeature>>,
    private val privacy: StateFlow<PrivacyState>,
    private val signals: MutableSharedFlow<EngageSignal>,
    initiallyForeground: Boolean = true,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : Events {
    private var currentScreen: String? = null
    private var previousScreen: String? = null
    private var visibleSince: Long? = null
    private var accumulatedVisibleMillis = 0L
    private var appInForeground = initiallyForeground

    override suspend fun track(name: String, block: io.engage.sdk.EventEditor.() -> Unit) {
        io.engage.sdk.validateEventName(name)
        val event = io.engage.sdk.EventEditor().apply(block).build()
        EngageLogger.debug("Events", "track name=$name propertyKeys=${event.properties.keys.sorted()}")
        if (privacy.value != PrivacyState.OPTED_IN) {
            EngageLogger.debug("Events", "track ignored name=$name reason=privacy_opted_out")
            return
        }
        val properties = JsonObject(event.properties)
        if (SdkFeature.IN_APP in features.value) {
            signals.tryEmit(EngageSignal.EventOccurred(name, properties))
        }
        if (SdkFeature.ANALYTICS !in features.value) {
            EngageLogger.debug("Events", "network tracking skipped name=$name reason=analytics_disabled")
            return
        }
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

    override suspend fun trackScreen(screenKey: String) {
        io.engage.sdk.validateScreenKey(screenKey)
        EngageLogger.debug("Events", "trackScreen key=$screenKey")
        if (!screenCollectionEnabled()) {
            EngageLogger.debug("Events", "trackScreen ignored key=$screenKey reason=collection_disabled")
            return
        }
        if (screenKey == currentScreen) {
            EngageLogger.verbose("Events", "trackScreen ignored key=$screenKey reason=already_current")
            return
        }
        val now = elapsedRealtime()
        val previousDuration = visibleSince?.let { accumulatedVisibleMillis + (now - it).coerceAtLeast(0) }
        previousScreen = currentScreen
        currentScreen = screenKey
        visibleSince = now.takeIf { appInForeground }
        accumulatedVisibleMillis = 0
        if (SdkFeature.IN_APP in features.value) signals.tryEmit(EngageSignal.ScreenViewed(screenKey))
        if (SdkFeature.ANALYTICS !in features.value) return
        coordinator.enqueue(
            OperationType.SCREEN_VIEWED,
            buildJsonObject {
                put("screenKey", screenKey)
                previousScreen?.let { put("previousScreenKey", it) }
                previousDuration?.let { put("previousVisibleDurationMillis", it) }
            },
        )
    }

    override suspend fun clearScreen() {
        EngageLogger.debug("Events", "clearScreen requested")
        if (!screenCollectionEnabled()) {
            EngageLogger.debug("Events", "clearScreen ignored reason=collection_disabled")
            return
        }
        val screenKey = currentScreen ?: run {
            EngageLogger.verbose("Events", "clearScreen ignored reason=no_current_screen")
            return
        }
        val now = elapsedRealtime()
        val visibleDuration = visibleSince?.let { accumulatedVisibleMillis + (now - it).coerceAtLeast(0) }
            ?: accumulatedVisibleMillis
        currentScreen = null
        previousScreen = null
        visibleSince = null
        accumulatedVisibleMillis = 0
        if (SdkFeature.IN_APP in features.value) signals.tryEmit(EngageSignal.ScreenCleared)
        if (SdkFeature.ANALYTICS in features.value) {
            coordinator.enqueue(
                OperationType.SCREEN_CLEARED,
                buildJsonObject {
                    put("screenKey", screenKey)
                    put("visibleDurationMillis", visibleDuration)
                },
            )
        }
    }

    fun onBackground() {
        EngageLogger.verbose("Events", "screen timer backgrounded current=$currentScreen")
        appInForeground = false
        if (!screenCollectionEnabled()) return
        val since = visibleSince ?: return
        accumulatedVisibleMillis += (elapsedRealtime() - since).coerceAtLeast(0)
        visibleSince = null
    }

    fun onForeground() {
        EngageLogger.verbose("Events", "screen timer foregrounded current=$currentScreen")
        appInForeground = true
        if (!screenCollectionEnabled()) return
        if (currentScreen != null && visibleSince == null) visibleSince = elapsedRealtime()
    }

    fun resetScreenContext() {
        EngageLogger.debug("Events", "screen context reset")
        currentScreen = null
        previousScreen = null
        visibleSince = null
        accumulatedVisibleMillis = 0
    }

    private fun screenCollectionEnabled(): Boolean =
        privacy.value == PrivacyState.OPTED_IN &&
            (SdkFeature.IN_APP in features.value || SdkFeature.ANALYTICS in features.value)

    override suspend fun flush() {
        EngageLogger.info("Events", "flush requested")
        coordinator.flush()
    }
}

private fun Set<String>.asJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
