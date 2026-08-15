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

public interface PreferenceCenter {
    fun center(): StateFlow<PreferenceCenterSnapshot?>
    fun center(key: String): StateFlow<PreferenceCenterSnapshot?>
    fun display()
    fun display(key: String)
}
