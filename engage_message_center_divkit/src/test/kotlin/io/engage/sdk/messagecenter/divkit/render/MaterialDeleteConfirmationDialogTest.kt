package io.engage.sdk.messagecenter.divkit.render

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.messagecenter.divkit.MessageCenterMaterialTheme
import io.engage.sdk.messagecenter.divkit.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaterialDeleteConfirmationDialogTest {
    @Test
    fun `dialog consumes the supplied material 3 roles`() {
        val theme = testTheme()
        val dialog = MaterialDeleteConfirmationDialog(
            context = ApplicationProvider.getApplicationContext(),
            materialTheme = theme,
            onConfirm = {},
        )

        assertEquals(
            theme.surfaceContainer,
            shadowOf(dialog.content.background as GradientDrawable).lastSetColor,
        )
        assertEquals(
            theme.onSurface,
            dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_title).currentTextColor,
        )
        assertEquals(
            theme.onSurfaceVariant,
            dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_body).currentTextColor,
        )
        assertEquals(
            theme.primary,
            dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_cancel).currentTextColor,
        )
        assertEquals(
            theme.error,
            dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_confirm).currentTextColor,
        )
    }

    @Test
    fun `destructive action confirms only after an explicit click`() {
        var confirmed = false
        val dialog = MaterialDeleteConfirmationDialog(
            context = ApplicationProvider.getApplicationContext(),
            materialTheme = testTheme(),
            onConfirm = { confirmed = true },
        )

        dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_cancel).performClick()
        assertFalse(confirmed)

        dialog.content.findViewById<TextView>(R.id.engage_message_center_delete_dialog_confirm).performClick()
        assertTrue(confirmed)
    }

    private fun testTheme(): MessageCenterMaterialTheme = MessageCenterMaterialTheme(
        primary = 0xFF006A60.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFF9EF2E4.toInt(),
        surface = 0xFFFFF8FA.toInt(),
        surfaceContainerLow = 0xFFF9F2F4.toInt(),
        surfaceContainer = 0xFFF3ECEE.toInt(),
        onSurface = 0xFF201A1B.toInt(),
        onSurfaceVariant = 0xFF4F4547.toInt(),
        outlineVariant = 0xFFD2C4C6.toInt(),
        error = 0xFFBA1A1A.toInt(),
        onError = 0xFFFFFFFF.toInt(),
    )
}
