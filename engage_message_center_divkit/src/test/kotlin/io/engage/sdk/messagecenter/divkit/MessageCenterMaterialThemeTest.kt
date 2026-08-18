package io.engage.sdk.messagecenter.divkit

import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.messagecenter.divkit.render.shouldApplyMessageCenterNativeChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageCenterMaterialThemeTest {
    @Test
    fun `defaults resolve a complete material 3 palette`() {
        val theme = MessageCenterMaterialTheme.defaults(ApplicationProvider.getApplicationContext())

        assertEquals(0xFF, theme.surface ushr 24)
        assertEquals(0xFF, theme.primary ushr 24)
        assertEquals(0xFF, theme.error ushr 24)
        assertEquals(0xFF, theme.onError ushr 24)
    }

    @Test
    fun `negative dimensions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MessageCenterViewLayout(itemSpacingDp = -1f)
        }
    }

    @Test
    fun `published DivKit rendering owns its visual chrome`() {
        assertFalse(
            shouldApplyMessageCenterNativeChrome(
                showChrome = true,
                hasPublishedRendering = true,
            ),
        )
        assertTrue(
            shouldApplyMessageCenterNativeChrome(
                showChrome = true,
                hasPublishedRendering = false,
            ),
        )
        assertFalse(
            shouldApplyMessageCenterNativeChrome(
                showChrome = false,
                hasPublishedRendering = false,
            ),
        )
    }
}
