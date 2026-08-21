package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public data class PreferenceCenterSnapshot(
    val key: String,
    val displayName: String,
    val description: String?,
    val sections: List<PreferenceSection>,
    val projectStyle: PreferenceCenterProjectStyle? = null,
)

public data class PreferenceSection(
    val key: String,
    val title: String?,
    val description: String?,
    val subscriptions: List<SubscriptionPreference>,
)

public data class SubscriptionPreference(
    val key: String,
    val displayName: String,
    val description: String?,
    val profileChoices: Map<Channel, Boolean>?,
    val installationChoice: Boolean?,
)

public enum class PreferenceCenterAppearance {
    LIGHT,
    DARK,
}

public enum class PreferenceCenterStylePolicy {
    SYSTEM,
    FIXED,
}

public data class PreferenceCenterColorScheme(
    val primary: Int? = null,
    val onPrimary: Int? = null,
    val primaryContainer: Int? = null,
    val onPrimaryContainer: Int? = null,
    val surface: Int? = null,
    val surfaceContainerLow: Int? = null,
    val surfaceContainer: Int? = null,
    val onSurface: Int? = null,
    val onSurfaceVariant: Int? = null,
    val outlineVariant: Int? = null,
    val error: Int? = null,
    val onError: Int? = null,
)

/** Immutable project style snapshot compiled when the Preference Center is published. */
public data class PreferenceCenterProjectStyle(
    val policy: PreferenceCenterStylePolicy,
    val fallbackModeKey: String,
    val fixedModeKey: String?,
    val lightModeKey: String?,
    val darkModeKey: String?,
    val modes: Map<String, PreferenceCenterColorScheme>,
    val designTokenVersion: Int,
)

public data class PreferenceCenterMaterialTheme(
    val appearance: PreferenceCenterAppearance,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val surface: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outlineVariant: Int,
    val error: Int,
    val onError: Int,
)

public data class PreferenceCenterDisplayOptions(
    val key: String? = null,
    val localeLanguageTag: String? = null,
    val materialTheme: PreferenceCenterMaterialTheme? = null,
)

public interface PreferenceCenter {
    fun center(): StateFlow<PreferenceCenterSnapshot?>
    fun center(key: String): StateFlow<PreferenceCenterSnapshot?>
    suspend fun refresh()
    fun display(options: PreferenceCenterDisplayOptions = PreferenceCenterDisplayOptions())
}
