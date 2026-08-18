package io.engage.sdk.messagecenter.divkit.render

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import io.engage.sdk.messagecenter.divkit.MessageCenterMaterialTheme
import io.engage.sdk.messagecenter.divkit.R
import kotlin.math.min

/** Material 3 destructive confirmation whose palette is supplied by the host application. */
internal class MaterialDeleteConfirmationDialog(
    context: Context,
    private val materialTheme: MessageCenterMaterialTheme,
    private val onConfirm: () -> Unit,
) : Dialog(context) {
    internal val content: View = createContent()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(content)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIALOG_DIM_AMOUNT)
        }
        setOnShowListener {
            val availableWidth = context.resources.displayMetrics.widthPixels - dp(2 * DIALOG_HORIZONTAL_MARGIN_DP)
            window?.setLayout(min(dp(DIALOG_MAX_WIDTH_DP), availableWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun createContent(): View = LinearLayout(context).apply {
        id = R.id.engage_message_center_delete_dialog
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(16))
        elevation = dp(6).toFloat()
        background = roundedBackground(materialTheme.surfaceContainer, dp(28).toFloat())

        addView(
            FrameLayout(context).apply {
                background = roundedBackground(materialTheme.error.withAlpha(24), dp(24).toFloat())
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.engage_message_center_delete)
                        drawable?.mutate()?.also { DrawableCompat.setTint(it, materialTheme.error) }
                        contentDescription = null
                    },
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(20) },
        )

        addView(
            TextView(context).apply {
                id = R.id.engage_message_center_delete_dialog_title
                setText(R.string.engage_message_center_delete_title)
                setTextColor(materialTheme.onSurface)
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        addView(
            TextView(context).apply {
                id = R.id.engage_message_center_delete_dialog_body
                setText(R.string.engage_message_center_delete_body)
                setTextColor(materialTheme.onSurfaceVariant)
                textSize = 16f
                setLineSpacing(0f, 1.2f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(20)
            },
        )

        addView(
            LinearLayout(context).apply {
                gravity = Gravity.END
                orientation = LinearLayout.HORIZONTAL
                addView(
                    action(android.R.string.cancel, materialTheme.primary) { dismiss() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)),
                )
                addView(
                    action(R.string.engage_message_center_delete, materialTheme.error) {
                        dismiss()
                        onConfirm()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                        marginStart = dp(8)
                    },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
    }

    private fun action(label: Int, color: Int, onClick: () -> Unit): TextView = TextView(context).apply {
        id = if (label == R.string.engage_message_center_delete) {
            R.id.engage_message_center_delete_dialog_confirm
        } else {
            R.id.engage_message_center_delete_dialog_cancel
        }
        gravity = Gravity.CENTER
        minWidth = dp(64)
        setPadding(dp(16), 0, dp(16), 0)
        setText(label)
        setTextColor(color)
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        isAllCaps = false
        isClickable = true
        isFocusable = true
        background = RippleDrawable(
            ColorStateList.valueOf(color.withAlpha(28)),
            ColorDrawable(Color.TRANSPARENT),
            roundedBackground(Color.WHITE, dp(20).toFloat()),
        )
        setOnClickListener { onClick() }
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val DIALOG_DIM_AMOUNT = 0.32f
        const val DIALOG_HORIZONTAL_MARGIN_DP = 24
        const val DIALOG_MAX_WIDTH_DP = 560
    }
}
