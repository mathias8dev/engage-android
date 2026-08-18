package io.engage.sdk.messagecenter.divkit.render

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
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
import io.engage.sdk.InboxEntryId
import io.engage.sdk.messagecenter.divkit.R

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EngageMessageCenterDetailActivity : ComponentActivity() {
    private lateinit var detailView: EngageMessageCenterDetailView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val rawEntryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        if (rawEntryId.isNullOrBlank()) {
            EngageLogger.warn("MessageCenter.Detail", "opening rejected reason=missing_entry_id")
            finish()
            return
        }
        detailView = EngageMessageCenterDetailView(
            this,
            onError = { error ->
                EngageLogger.warn(
                    "MessageCenter.Detail",
                    "embedded detail error code=${error.code} retryable=${error.isRetryable} message=${error.message}",
                )
            },
        )
        setContentView(createContentView())
        detailView.display(InboxEntryId(rawEntryId))
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
            nightMode != Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroy() {
        if (::detailView.isInitialized) detailView.close()
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
        addView(createHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        addView(detailView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun createHeader(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setBackgroundColor(color(R.color.engage_message_center_header))
        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.engage_message_center_back)
                contentDescription = getString(R.string.engage_message_center_back)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) },
        )
        addView(
            TextView(context).apply {
                setText(R.string.engage_message_center_detail_title)
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
    }

    private fun color(resource: Int): Int = ContextCompat.getColor(this, resource)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    public companion object {
        public const val EXTRA_ENTRY_ID: String = "io.engage.sdk.messagecenter.ENTRY_ID"
    }
}
