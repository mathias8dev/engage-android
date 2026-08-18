package io.engage.sdk.messagecenter.divkit

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/** Closed error vocabulary emitted by the reusable Message Center views. */
public enum class MessageCenterViewErrorCode {
    INBOX,
    RENDERING,
}

public data class MessageCenterViewError(
    val code: MessageCenterViewErrorCode,
    val message: String,
    val isRetryable: Boolean,
)

/** Material 3 color roles consumed by the navigation-independent Message Center views. */
public data class MessageCenterMaterialTheme(
    @param:ColorInt val primary: Int,
    @param:ColorInt val onPrimary: Int,
    @param:ColorInt val primaryContainer: Int,
    @param:ColorInt val surface: Int,
    @param:ColorInt val surfaceContainerLow: Int,
    @param:ColorInt val surfaceContainer: Int,
    @param:ColorInt val onSurface: Int,
    @param:ColorInt val onSurfaceVariant: Int,
    @param:ColorInt val outlineVariant: Int,
    @param:ColorInt val error: Int = Color.rgb(186, 26, 26),
    @param:ColorInt val onError: Int = Color.WHITE,
) {
    public companion object {
        public fun defaults(context: Context): MessageCenterMaterialTheme = MessageCenterMaterialTheme(
            primary = context.color(R.color.engage_message_center_accent),
            onPrimary = context.color(R.color.engage_message_center_on_accent),
            primaryContainer = context.color(R.color.engage_message_center_accent_soft),
            surface = context.color(R.color.engage_message_center_page),
            surfaceContainerLow = context.color(R.color.engage_message_center_surface),
            surfaceContainer = context.color(R.color.engage_message_center_surface_read),
            onSurface = context.color(R.color.engage_message_center_text),
            onSurfaceVariant = context.color(R.color.engage_message_center_text_secondary),
            outlineVariant = context.color(R.color.engage_message_center_outline),
            error = context.color(R.color.engage_message_center_error),
            onError = context.color(R.color.engage_message_center_on_error),
        )

        private fun Context.color(resource: Int): Int = ContextCompat.getColor(this, resource)
    }
}

/** Host-owned layout tokens for the navigation-independent Message Center views. */
public data class MessageCenterViewLayout(
    val horizontalPaddingDp: Float = 16f,
    val itemSpacingDp: Float = 12f,
    val itemCornerRadiusDp: Float = 20f,
) {
    init {
        require(horizontalPaddingDp >= 0f) { "horizontalPaddingDp must be positive" }
        require(itemSpacingDp >= 0f) { "itemSpacingDp must be positive" }
        require(itemCornerRadiusDp >= 0f) { "itemCornerRadiusDp must be positive" }
    }

}
