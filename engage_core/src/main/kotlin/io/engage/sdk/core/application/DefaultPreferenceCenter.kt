package io.engage.sdk.core.application

import android.content.Context
import android.content.Intent
import android.graphics.Color
import io.engage.sdk.Channel
import io.engage.sdk.EngageLogger
import io.engage.sdk.PreferenceCenter
import io.engage.sdk.PreferenceCenterDisplayOptions
import io.engage.sdk.PreferenceCenterSnapshot
import io.engage.sdk.PreferenceCenterColorScheme
import io.engage.sdk.PreferenceCenterProjectStyle
import io.engage.sdk.PreferenceCenterStylePolicy
import io.engage.sdk.PreferenceSection
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.SubscriptionPreference
import io.engage.sdk.core.data.PreferenceCenterActivity
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class DefaultPreferenceCenter(
    private val context: Context,
    private val sessions: SessionStore,
    private val syncStore: SyncStore,
    private val outbox: OperationOutbox,
    private val features: StateFlow<Set<SdkFeature>>,
    private val scope: CoroutineScope,
    private val refreshRemoteState: suspend () -> Unit,
) : PreferenceCenter {
    private val flows = ConcurrentHashMap<String, StateFlow<PreferenceCenterSnapshot?>>()

    override fun center(): StateFlow<PreferenceCenterSnapshot?> {
        EngageLogger.debug("Preferences", "default center requested")
        return flowFor(DEFAULT_CENTER)
    }

    override fun center(key: String): StateFlow<PreferenceCenterSnapshot?> {
        require(CENTER_KEY.matches(key)) { "Preference center keys must match ${CENTER_KEY.pattern}" }
        EngageLogger.debug("Preferences", "center requested key=$key")
        return flowFor(key)
    }

    override fun display(options: PreferenceCenterDisplayOptions) {
        options.key?.let { key ->
            require(CENTER_KEY.matches(key)) { "Preference center keys must match ${CENTER_KEY.pattern}" }
        }
        displayInternal(options)
    }

    override suspend fun refresh() {
        EngageLogger.info("Preferences", "manual refresh requested")
        refreshRemoteState()
        EngageLogger.info("Preferences", "manual refresh completed")
    }

    private fun flowFor(key: String): StateFlow<PreferenceCenterSnapshot?> = flows.getOrPut(key) {
        EngageLogger.debug("Preferences", "creating synchronized center flow key=$key")
        combine(
            syncStore.snapshot,
            outbox.pending,
            sessions.session,
            sessions.privacy,
            features,
        ) { snapshot, pending, session, privacy, enabled ->
            if (
                privacy != PrivacyState.OPTED_IN ||
                SdkFeature.PREFERENCES !in enabled ||
                session == null ||
                snapshot.generation != session.generation
            ) {
                null
            } else {
                PreferenceProjection(snapshot, pending, session.generation)
                    .center(key.takeUnless { it == DEFAULT_CENTER })
            }
        }.onEach { center ->
            EngageLogger.verbose(
                "Preferences",
                "center emitted requestedKey=$key resolvedKey=${center?.key} sections=${center?.sections?.size ?: 0}",
            )
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    private fun displayInternal(options: PreferenceCenterDisplayOptions) {
        EngageLogger.info("Preferences", "display requested key=${options.key ?: "default"}")
        val presentation = options.materialTheme
        context.startActivity(
            Intent(context, PreferenceCenterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PreferenceCenterActivity.EXTRA_CENTER_KEY, options.key)
                .putExtra(PreferenceCenterActivity.EXTRA_LOCALE, options.localeLanguageTag)
                .apply {
                    if (presentation != null) {
                        putExtra(PreferenceCenterActivity.EXTRA_APPEARANCE, presentation.appearance.name)
                        putExtra(PreferenceCenterActivity.EXTRA_PRIMARY, presentation.primary)
                        putExtra(PreferenceCenterActivity.EXTRA_ON_PRIMARY, presentation.onPrimary)
                        putExtra(PreferenceCenterActivity.EXTRA_PRIMARY_CONTAINER, presentation.primaryContainer)
                        putExtra(PreferenceCenterActivity.EXTRA_ON_PRIMARY_CONTAINER, presentation.onPrimaryContainer)
                        putExtra(PreferenceCenterActivity.EXTRA_SURFACE, presentation.surface)
                        putExtra(PreferenceCenterActivity.EXTRA_SURFACE_CONTAINER_LOW, presentation.surfaceContainerLow)
                        putExtra(PreferenceCenterActivity.EXTRA_SURFACE_CONTAINER, presentation.surfaceContainer)
                        putExtra(PreferenceCenterActivity.EXTRA_ON_SURFACE, presentation.onSurface)
                        putExtra(PreferenceCenterActivity.EXTRA_ON_SURFACE_VARIANT, presentation.onSurfaceVariant)
                        putExtra(PreferenceCenterActivity.EXTRA_OUTLINE_VARIANT, presentation.outlineVariant)
                        putExtra(PreferenceCenterActivity.EXTRA_ERROR, presentation.error)
                        putExtra(PreferenceCenterActivity.EXTRA_ON_ERROR, presentation.onError)
                    }
                },
        )
    }

    private companion object {
        const val DEFAULT_CENTER = "\u0000default"
        val CENTER_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

private class PreferenceProjection(
    snapshot: StoredSyncSnapshot,
    pending: List<io.engage.sdk.core.domain.SdkOperation>,
    activeGeneration: Long,
) {
    private val payload = snapshot.documents.firstOrNull {
        it.module == SdkModule.PREFERENCES && it.key == "subscriptions"
    }?.payload
    private val catalog = payload?.get("catalog") as? JsonArray ?: JsonArray(emptyList())
    private val centers = payload?.get("centers") as? JsonObject ?: JsonObject(emptyMap())
    private val installationChoices = mutableMapOf<String, Boolean>()
    private val profileChoices = mutableMapOf<Pair<String, Channel>, Boolean>()

    init {
        (payload?.get("installation") as? JsonArray).orEmptyObjects().forEach { choice ->
            val list = choice.string("listKey") ?: return@forEach
            val subscribed = choice.boolean("subscribed") ?: return@forEach
            installationChoices[list] = subscribed
        }
        (payload?.get("profile") as? JsonArray).orEmptyObjects().forEach { choice ->
            val list = choice.string("listKey") ?: return@forEach
            val channel = choice.string("channel")?.let { runCatching { Channel.valueOf(it) }.getOrNull() }
                ?: return@forEach
            val subscribed = choice.boolean("subscribed") ?: return@forEach
            profileChoices[list to channel] = subscribed
        }
        pending.forEach { operation ->
            when (operation.type) {
                OperationType.INSTALLATION_SUBSCRIPTIONS_EDITED ->
                    (operation.payload["changes"] as? JsonArray).orEmptyObjects().forEach { change ->
                        val list = change.string("list") ?: return@forEach
                        val subscribed = change.boolean("subscribed") ?: return@forEach
                        installationChoices[list] = subscribed
                    }
                OperationType.PROFILE_SUBSCRIPTIONS_EDITED ->
                    if (operation.generation == activeGeneration) {
                        (operation.payload["changes"] as? JsonArray).orEmptyObjects().forEach { change ->
                            val list = change.string("list") ?: return@forEach
                            val channel = change.string("channel")?.let {
                                runCatching { Channel.valueOf(it) }.getOrNull()
                            } ?: return@forEach
                            val subscribed = change.boolean("subscribed") ?: return@forEach
                            profileChoices[list to channel] = subscribed
                        }
                    }
                else -> Unit
            }
        }
    }

    fun center(requestedKey: String?): PreferenceCenterSnapshot? {
        val candidate = centers.entries.firstNotNullOfOrNull { (key, value) ->
            val entry = value as? JsonObject ?: return@firstNotNullOfOrNull null
            val definition = entry["definition"] as? JsonObject ?: return@firstNotNullOfOrNull null
            when {
                requestedKey != null && key == requestedKey -> key to definition
                requestedKey == null && definition.boolean("isDefault") == true -> key to definition
                else -> null
            }
        } ?: return null
        val (key, definition) = candidate
        return PreferenceCenterSnapshot(
            key = key,
            displayName = localized(definition["displayName"]) ?: key,
            description = localized(definition["description"]),
            sections = (definition["sections"] as? JsonArray).orEmptyObjects().mapNotNull { section ->
                val sectionKey = section.string("key") ?: return@mapNotNull null
                PreferenceSection(
                    key = sectionKey,
                    title = localized(section["title"]),
                    description = localized(section["description"]),
                    subscriptions = (section["subscriptionListKeys"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                        .mapNotNull(::subscription),
                )
            },
            projectStyle = projectStyle(definition["resolvedStyle"] as? JsonObject),
        )
    }

    private fun projectStyle(value: JsonObject?): PreferenceCenterProjectStyle? {
        value ?: return null
        val policy = value.string("appearancePolicy")
            ?.let { runCatching { PreferenceCenterStylePolicy.valueOf(it) }.getOrNull() }
            ?: return null
        val fallbackModeKey = value.string("fallbackModeKey") ?: return null
        val systemModes = value["systemModeKeys"] as? JsonObject
        val modes = (value["modes"] as? JsonObject).orEmpty().mapNotNull { (modeKey, rawPalette) ->
            val palette = rawPalette as? JsonObject ?: return@mapNotNull null
            modeKey to PreferenceCenterColorScheme(
                primary = palette.color("PRIMARY"),
                onPrimary = palette.color("ON_PRIMARY"),
                primaryContainer = palette.color("PRIMARY_CONTAINER"),
                onPrimaryContainer = palette.color("ON_PRIMARY_CONTAINER"),
                surface = palette.color("SURFACE"),
                surfaceContainerLow = palette.color("SURFACE_CONTAINER_LOW"),
                surfaceContainer = palette.color("SURFACE_CONTAINER"),
                onSurface = palette.color("ON_SURFACE"),
                onSurfaceVariant = palette.color("ON_SURFACE_VARIANT"),
                outlineVariant = palette.color("OUTLINE_VARIANT"),
                error = palette.color("ERROR"),
                onError = palette.color("ON_ERROR"),
            )
        }.toMap()
        if (modes.isEmpty()) return null
        return PreferenceCenterProjectStyle(
            policy = policy,
            fallbackModeKey = fallbackModeKey,
            fixedModeKey = value.string("fixedModeKey"),
            lightModeKey = systemModes?.string("LIGHT"),
            darkModeKey = systemModes?.string("DARK"),
            modes = modes,
            designTokenVersion = (value["designTokenVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    private fun subscription(key: String): SubscriptionPreference? {
        val item = catalog.orEmptyObjects().firstOrNull { it.string("key") == key } ?: return null
        val defaults = item.boolean("defaultSubscribed") ?: false
        val scopes = (item["scopes"] as? JsonArray)?.strings().orEmpty()
        val channels = (item["channels"] as? JsonArray)?.strings().orEmpty()
            .mapNotNull { runCatching { Channel.valueOf(it) }.getOrNull() }
        return SubscriptionPreference(
            key = key,
            displayName = localized(item["displayName"]) ?: key,
            description = localized(item["description"]),
            profileChoices = channels.takeIf { "PROFILE" in scopes }?.associateWith { channel ->
                profileChoices[key to channel] ?: defaults
            },
            installationChoice = if ("INSTALLATION" in scopes) installationChoices[key] ?: defaults else null,
        )
    }
}

private fun JsonArray?.orEmptyObjects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.color(key: String): Int? = string(key)?.let { runCatching { Color.parseColor(it) }.getOrNull() }

private fun localized(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonObject -> {
        val locale = Locale.getDefault()
        listOf(locale.toLanguageTag(), locale.language, "default")
            .firstNotNullOfOrNull { key -> (element[key] as? JsonPrimitive)?.contentOrNull }
            ?: element.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
    }
    else -> null
}
