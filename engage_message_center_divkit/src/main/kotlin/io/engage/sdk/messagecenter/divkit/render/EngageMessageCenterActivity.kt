package io.engage.sdk.messagecenter.divkit.render

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RestrictTo
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntry
import io.engage.sdk.messagecenter.divkit.R

/** Activity host used only by the explicit ready-made [io.engage.sdk.display] command. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EngageMessageCenterActivity : ComponentActivity() {
    private lateinit var listView: EngageMessageCenterListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        listView = EngageMessageCenterListView(
            this,
            onEntryTap = ::openDetail,
            onError = { error ->
                EngageLogger.warn(
                    "MessageCenter.Activity",
                    "embedded list error code=${error.code} retryable=${error.isRetryable} message=${error.message}",
                )
            },
        )
        setContentView(createContentView())
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
            nightMode != Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroy() {
        if (::listView.isInitialized) listView.close()
        super.onDestroy()
    }

    private fun createContentView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(R.color.engage_message_center_page))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        addView(createHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)))
        addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun createHeader(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(color(R.color.engage_message_center_header))
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.engage_message_center_back)
                contentDescription = getString(R.string.engage_message_center_back)
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(52, 255, 255, 255)),
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(24).toFloat()
                        setColor(Color.argb(24, 255, 255, 255))
                    },
                    null,
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) },
        )
        addView(
            TextView(context).apply {
                setText(R.string.engage_message_center_title)
                setTextColor(Color.WHITE)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
    }

    private fun openDetail(entry: InboxEntry) {
        startActivity(
            Intent(this, EngageMessageCenterDetailActivity::class.java)
                .putExtra(EngageMessageCenterDetailActivity.EXTRA_ENTRY_ID, entry.id.value),
        )
    }

    private fun color(resource: Int): Int = ContextCompat.getColor(this, resource)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
