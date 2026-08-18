package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public data class PreferenceCenterSnapshot(
    val key: String,
    val displayName: String,
    val description: String?,
    val sections: List<PreferenceSection>,
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
