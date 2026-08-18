package io.engage.sdk.core.data

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.engage.sdk.Channel
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.PreferenceCenterAppearance
import io.engage.sdk.PreferenceCenterSnapshot
import io.engage.sdk.core.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

public class PreferenceCenterActivity : Activity() {
    private val scope = MainScope()
    private lateinit var palette: PreferencePalette
    private lateinit var screenTitle: TextView
    private lateinit var body: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = preferencePalette()
        configureSystemBars()
        EngageLogger.info(
            "PreferencesUI",
            "activity created key=${intent.getStringExtra(EXTRA_CENTER_KEY) ?: "default"}",
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.surface)
            addView(header(), matchWidth(56.dp))
            addView(View(context).apply { setBackgroundColor(palette.outlineVariant) }, matchWidth(1.dp))
            body = FrameLayout(context).apply { setBackgroundColor(palette.surface) }
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            installSystemBarInsets()
        }
        setContentView(root)
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
        body.removeAllViews()
        if (!snapshot.hasVisiblePreferences()) {
            screenTitle.text = getString(R.string.engage_preference_center_title)
            body.addView(unavailableState(), frameMatch())
            return
        }

        screenTitle.text = snapshot!!.displayName
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 24.dp, 20.dp, 40.dp)
        }
        snapshot.description?.let { content.addSupportingText(it) }
        snapshot.sections.forEachIndexed { index, section ->
            if (index > 0) content.addView(space(20.dp))
            section.title?.let { content.addHeading(it) }
            section.description?.let { content.addSupportingText(it) }
            section.subscriptions.forEach { subscription ->
                subscription.profileChoices?.forEach { (channel, subscribed) ->
                    content.addView(
                        choice(
                            title = subscription.displayName,
                            subtitle = listOfNotNull(subscription.description, channel.displayName).joinToString(" · "),
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
                        matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp },
                    )
                }
                subscription.installationChoice?.let { subscribed ->
                    content.addView(
                        choice(
                            title = subscription.displayName,
                            subtitle = subscription.description,
                            checked = subscribed,
                        ) { enabled ->
                            EngageLogger.info(
                                "PreferencesUI",
                                "installation choice changed list=${subscription.key} enabled=$enabled",
                            )
                            Engage.installation.editSubscriptions {
                                if (enabled) subscribe(subscription.key) else unsubscribe(subscription.key)
                            }
                        },
                        matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10.dp },
                    )
                }
            }
        }
        body.addView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }, frameMatch())
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(4.dp, 0, 16.dp, 0)
        addView(ImageButton(context).apply {
            setImageResource(R.drawable.engage_preference_center_close)
            imageTintList = ColorStateList.valueOf(palette.onSurface)
            background = ripple(palette.onSurface, Color.TRANSPARENT, 24.dp)
            contentDescription = getString(R.string.engage_preference_center_close)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        screenTitle = TextView(context).apply {
            text = getString(R.string.engage_preference_center_title)
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(screenTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun unavailableState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(28.dp, 40.dp, 28.dp, 40.dp)

        addView(FrameLayout(context).apply {
            background = rounded(palette.primaryContainer, 24.dp)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.engage_preference_center_unavailable)
                imageTintList = ColorStateList.valueOf(palette.onPrimaryContainer)
                contentDescription = null
            }, FrameLayout.LayoutParams(32.dp, 32.dp, Gravity.CENTER))
        }, LinearLayout.LayoutParams(72.dp, 72.dp).apply { bottomMargin = 24.dp })

        addView(TextView(context).apply {
            text = getString(R.string.engage_preference_center_unavailable_title)
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10.dp })

        addView(TextView(context).apply {
            text = getString(R.string.engage_preference_center_unavailable_body)
            setTextColor(palette.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.18f)
            gravity = Gravity.CENTER
            maxWidth = 360.dp
        }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 28.dp })

        addView(TextView(context).apply {
            text = getString(R.string.engage_preference_center_close_button)
            setTextColor(palette.onPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ripple(palette.onPrimary, palette.primary, 16.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(240.dp, 52.dp))
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun choice(
        title: String,
        subtitle: String?,
        checked: Boolean,
        onChange: suspend (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16.dp, 14.dp, 10.dp, 14.dp)
        background = rounded(palette.surfaceContainerLow, 16.dp)

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(palette.onSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
            subtitle?.takeIf(String::isNotBlank)?.let { value ->
                addView(TextView(context).apply {
                    text = value
                    setTextColor(palette.onSurfaceVariant)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, 4.dp, 0, 0)
                }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12.dp
        })

        addView(Switch(context).apply {
            isChecked = checked
            contentDescription = title
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.onPrimary, palette.outlineVariant),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.primary, palette.surfaceContainer),
            )
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.addHeading(value: String) {
        addView(TextView(context).apply {
            text = value
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
        }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.addSupportingText(value: String) {
        addView(TextView(context).apply {
            text = value
            setTextColor(palette.onSurfaceVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.14f)
            setPadding(0, 6.dp, 0, 4.dp)
        }, matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.surface
        window.navigationBarColor = palette.surface
        var flags = window.decorView.systemUiVisibility
        flags = if (palette.appearance == PreferenceCenterAppearance.LIGHT) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (palette.appearance == PreferenceCenterAppearance.LIGHT) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun View.installSystemBarInsets() {
        setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(left, top, right, bottom)
            insets
        }
        requestApplyInsets()
    }

    private fun preferencePalette(): PreferencePalette {
        fun extra(key: String, fallback: Int): Int = if (intent.hasExtra(key)) intent.getIntExtra(key, fallback) else fallback
        val surface = themedColor(android.R.attr.colorBackground, Color.WHITE)
        val onSurface = themedColor(android.R.attr.textColorPrimary, Color.BLACK)
        val onSurfaceVariant = themedColor(android.R.attr.textColorSecondary, Color.DKGRAY)
        val primary = themedColor(android.R.attr.colorAccent, Color.rgb(0, 106, 96))
        val appearance = intent.getStringExtra(EXTRA_APPEARANCE)
            ?.let { runCatching { PreferenceCenterAppearance.valueOf(it) }.getOrNull() }
            ?: if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            ) PreferenceCenterAppearance.DARK else PreferenceCenterAppearance.LIGHT
        return PreferencePalette(
            appearance = appearance,
            primary = extra(EXTRA_PRIMARY, primary),
            onPrimary = extra(EXTRA_ON_PRIMARY, Color.WHITE),
            primaryContainer = extra(EXTRA_PRIMARY_CONTAINER, primary.withAlpha(36)),
            onPrimaryContainer = extra(EXTRA_ON_PRIMARY_CONTAINER, primary),
            surface = extra(EXTRA_SURFACE, surface),
            surfaceContainerLow = extra(EXTRA_SURFACE_CONTAINER_LOW, onSurface.withAlpha(10).compositeOver(surface)),
            surfaceContainer = extra(EXTRA_SURFACE_CONTAINER, onSurface.withAlpha(20).compositeOver(surface)),
            onSurface = extra(EXTRA_ON_SURFACE, onSurface),
            onSurfaceVariant = extra(EXTRA_ON_SURFACE_VARIANT, onSurfaceVariant),
            outlineVariant = extra(EXTRA_OUTLINE_VARIANT, onSurface.withAlpha(36).compositeOver(surface)),
        )
    }

    private fun themedColor(attribute: Int, fallback: Int): Int {
        val attributes = obtainStyledAttributes(intArrayOf(attribute))
        return try {
            attributes.getColor(0, fallback)
        } finally {
            attributes.recycle()
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun ripple(rippleColor: Int, backgroundColor: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf(rippleColor.withAlpha(32)),
        rounded(backgroundColor, radius),
        rounded(Color.WHITE, radius),
    )

    private fun space(height: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    private fun Int.compositeOver(background: Int): Int {
        val alpha = Color.alpha(this) / 255f
        fun channel(foreground: Int, back: Int): Int = (foreground * alpha + back * (1f - alpha)).toInt()
        return Color.rgb(
            channel(Color.red(this), Color.red(background)),
            channel(Color.green(this), Color.green(background)),
            channel(Color.blue(this), Color.blue(background)),
        )
    }

    private fun matchWidth(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun frameMatch() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    public companion object {
        public const val EXTRA_CENTER_KEY: String = "io.engage.sdk.preference_center_key"
        public const val EXTRA_LOCALE: String = "io.engage.sdk.preference_center_locale"
        public const val EXTRA_APPEARANCE: String = "io.engage.sdk.preference_center_appearance"
        public const val EXTRA_PRIMARY: String = "io.engage.sdk.preference_center_primary"
        public const val EXTRA_ON_PRIMARY: String = "io.engage.sdk.preference_center_on_primary"
        public const val EXTRA_PRIMARY_CONTAINER: String = "io.engage.sdk.preference_center_primary_container"
        public const val EXTRA_ON_PRIMARY_CONTAINER: String = "io.engage.sdk.preference_center_on_primary_container"
        public const val EXTRA_SURFACE: String = "io.engage.sdk.preference_center_surface"
        public const val EXTRA_SURFACE_CONTAINER_LOW: String = "io.engage.sdk.preference_center_surface_container_low"
        public const val EXTRA_SURFACE_CONTAINER: String = "io.engage.sdk.preference_center_surface_container"
        public const val EXTRA_ON_SURFACE: String = "io.engage.sdk.preference_center_on_surface"
        public const val EXTRA_ON_SURFACE_VARIANT: String = "io.engage.sdk.preference_center_on_surface_variant"
        public const val EXTRA_OUTLINE_VARIANT: String = "io.engage.sdk.preference_center_outline_variant"
        public const val EXTRA_ERROR: String = "io.engage.sdk.preference_center_error"
        public const val EXTRA_ON_ERROR: String = "io.engage.sdk.preference_center_on_error"
    }
}

private data class PreferencePalette(
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
)

private val Channel.displayName: String
    get() = when (this) {
        Channel.EMAIL -> "Email"
        Channel.SMS -> "SMS"
        Channel.PUSH -> "Push"
        Channel.WHATSAPP -> "WhatsApp"
    }

internal fun PreferenceCenterSnapshot?.hasVisiblePreferences(): Boolean = this?.sections?.any { section ->
    section.subscriptions.any { preference ->
        preference.installationChoice != null || !preference.profileChoices.isNullOrEmpty()
    }
} == true
