package io.engage.sdk.core.data

import io.engage.sdk.Channel
import io.engage.sdk.PreferenceCenterSnapshot
import io.engage.sdk.PreferenceSection
import io.engage.sdk.SubscriptionPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceCenterPresentationTest {
    @Test
    fun `missing and structurally empty centers are not rendered as content`() {
        assertFalse(null.hasVisiblePreferences())
        assertFalse(snapshot(emptyList()).hasVisiblePreferences())
        assertFalse(snapshot(listOf(section(emptyList()))).hasVisiblePreferences())
    }

    @Test
    fun `installation and profile choices make a center visible`() {
        assertTrue(
            snapshot(
                listOf(section(listOf(preference(installationChoice = true)))),
            ).hasVisiblePreferences(),
        )
        assertTrue(
            snapshot(
                listOf(section(listOf(preference(profileChoices = mapOf(Channel.PUSH to false))))),
            ).hasVisiblePreferences(),
        )
    }

    private fun snapshot(sections: List<PreferenceSection>) = PreferenceCenterSnapshot(
        key = "communications",
        displayName = "Communication preferences",
        description = null,
        sections = sections,
    )

    private fun section(subscriptions: List<SubscriptionPreference>) = PreferenceSection(
        key = "notifications",
        title = "Notifications",
        description = null,
        subscriptions = subscriptions,
    )

    private fun preference(
        profileChoices: Map<Channel, Boolean>? = null,
        installationChoice: Boolean? = null,
    ) = SubscriptionPreference(
        key = "news",
        displayName = "News",
        description = null,
        profileChoices = profileChoices,
        installationChoice = installationChoice,
    )
}
