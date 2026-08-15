package io.engage.sdk.core.data

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.engage.sdk.Channel
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.PreferenceCenterSnapshot
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

public class PreferenceCenterActivity : Activity() {
    private val scope = MainScope()
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngageLogger.info("PreferencesUI", "activity created key=${intent.getStringExtra(EXTRA_CENTER_KEY) ?: "default"}")
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        val key = intent.getStringExtra(EXTRA_CENTER_KEY)
        val center = if (key == null) Engage.preferenceCenter.center() else Engage.preferenceCenter.center(key)
        scope.launch { center.collectLatest(::render) }
    }

    override fun onDestroy() {
        EngageLogger.info("PreferencesUI", "activity destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun render(snapshot: PreferenceCenterSnapshot?) {
        EngageLogger.debug(
            "PreferencesUI",
            "render snapshot=${snapshot?.key} sections=${snapshot?.sections?.size ?: 0}",
        )
        content.removeAllViews()
        if (snapshot == null) return
        title = snapshot.displayName
        snapshot.description?.let { content.addText(it) }
        snapshot.sections.forEach { section ->
            section.title?.let { content.addText(it, heading = true) }
            section.description?.let { content.addText(it) }
            section.subscriptions.forEach { subscription ->
                subscription.profileChoices?.forEach { (channel, subscribed) ->
                    content.addView(
                        choice(
                            label = "${subscription.displayName} · ${channel.name.lowercase()}",
                            checked = subscribed,
                        ) { enabled ->
                            EngageLogger.info(
                                "PreferencesUI",
                                "profile choice changed list=${subscription.key} channel=${channel.name} enabled=$enabled",
                            )
                            Engage.profile.editSubscriptions {
                                if (enabled) subscribe(subscription.key, setOf(channel))
                                else unsubscribe(subscription.key, setOf(channel))
                            }
                        },
                    )
                }
                subscription.installationChoice?.let { subscribed ->
                    content.addView(
                        choice(subscription.displayName, subscribed) { enabled ->
                            EngageLogger.info(
                                "PreferencesUI",
                                "installation choice changed list=${subscription.key} enabled=$enabled",
                            )
                            Engage.installation.editSubscriptions {
                                if (enabled) subscribe(subscription.key) else unsubscribe(subscription.key)
                            }
                        },
                    )
                }
            }
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun choice(label: String, checked: Boolean, onChange: suspend (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        var reverting = false
        setOnCheckedChangeListener { button, value ->
            if (reverting) return@setOnCheckedChangeListener
            button.isEnabled = false
            scope.launch {
                try {
                    onChange(value)
                } catch (error: Exception) {
                    EngageLogger.error("PreferencesUI", "subscription edit failed", error)
                    reverting = true
                    button.isChecked = !value
                    reverting = false
                } finally {
                    button.isEnabled = true
                }
            }
        }
    }

    private fun LinearLayout.addText(value: String, heading: Boolean = false) {
        addView(TextView(context).apply {
            text = value
            textSize = if (heading) 20f else 16f
            setPadding(0, 12.dp, 0, 8.dp)
        })
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    public companion object {
        public const val EXTRA_CENTER_KEY: String = "io.engage.sdk.preference_center_key"
    }
}
