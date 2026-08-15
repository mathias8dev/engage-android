package io.engage.sdk.inapp.render

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import io.engage.sdk.BackdropPolicy
import io.engage.sdk.DismissalPolicy
import io.engage.sdk.InAppAnimation
import io.engage.sdk.InAppContent
import io.engage.sdk.EngageLogger
import io.engage.sdk.OverlayFormat
import io.engage.sdk.OverlayPosition
import io.engage.sdk.OverlayPresentation
import kotlinx.serialization.json.JsonObject

internal class OverlayPresenter {
    private var active: ActiveOverlay? = null

    val activeContent: InAppContent?
        get() = active?.content

    fun show(
        activity: Activity,
        content: InAppContent,
        callbacks: InAppRenderCallbacks,
        onClosed: () -> Unit,
    ): Boolean {
        if (active != null || activity.isFinishing || activity.isDestroyed) {
            EngageLogger.debug(
                "InApp.Overlay",
                "show rejected messageId=${content.messageId} active=${active?.content?.messageId} " +
                    "finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}",
            )
            return false
        }
        val presentation = content.presentation as? OverlayPresentation ?: run {
            EngageLogger.warn("InApp.Overlay", "show rejected messageId=${content.messageId} reason=not_overlay")
            return false
        }
        EngageLogger.info(
            "InApp.Overlay",
            "showing messageId=${content.messageId} format=${presentation.format} " +
                "dismissal=${presentation.dismissal} backdrop=${presentation.backdrop}",
        )
        val dialog = Dialog(activity)
        var dismissalReported = false
        val handler = Handler(Looper.getMainLooper())
        val forwardedCallbacks = object : InAppRenderCallbacks by callbacks {
            override fun onDismissed(content: InAppContent) {
                if (!dismissalReported) {
                    dismissalReported = true
                    callbacks.onDismissed(content)
                }
                dialog.dismiss()
            }

            override fun onRenderFailed(content: InAppContent) {
                EngageLogger.warn("InApp.Overlay", "render failed messageId=${content.messageId}; closing")
                callbacks.onRenderFailed(content)
                dialog.dismiss()
            }
        }
        val view = EngageContentView(activity, content, forwardedCallbacks)
        dialog.setContentView(view)
        dialog.setCancelable(presentation.dismissal == DismissalPolicy.USER_DISMISSIBLE)
        dialog.setCanceledOnTouchOutside(presentation.dismissal == DismissalPolicy.USER_DISMISSIBLE)
        dialog.setOnCancelListener {
            EngageLogger.debug("InApp.Overlay", "dialog cancelled messageId=${content.messageId}")
            if (!dismissalReported) {
                dismissalReported = true
                callbacks.onDismissed(content)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(dialog)
            active = null
            EngageLogger.info("InApp.Overlay", "closed messageId=${content.messageId}")
            onClosed()
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            if (presentation.backdrop == BackdropPolicy.DIMMED) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply { dimAmount = 0.48f }
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        active = ActiveOverlay(content, dialog, callbacks)
        dialog.show()
        EngageLogger.debug("InApp.Overlay", "dialog shown messageId=${content.messageId}")
        configureWindow(dialog, presentation)
        animateIn(view, presentation.animation)
        if (presentation.dismissal == DismissalPolicy.AUTO_DISMISS) {
            val delay = requireNotNull(presentation.autoDismissAfterSeconds).toLong() * 1_000L
            EngageLogger.debug("InApp.Overlay", "auto-dismiss scheduled messageId=${content.messageId} delayMillis=$delay")
            handler.postAtTime(
                {
                    if (!dismissalReported && dialog.isShowing) {
                        EngageLogger.info("InApp.Overlay", "auto-dismissed messageId=${content.messageId}")
                        dismissalReported = true
                        callbacks.onDismissed(content)
                        dialog.dismiss()
                    }
                },
                dialog,
                android.os.SystemClock.uptimeMillis() + delay,
            )
        }
        return true
    }

    fun dismiss(reportDismissal: Boolean) {
        val current = active ?: run {
            EngageLogger.verbose("InApp.Overlay", "dismiss ignored reason=no_active_overlay")
            return
        }
        EngageLogger.info(
            "InApp.Overlay",
            "dismiss requested messageId=${current.content.messageId} reportDismissal=$reportDismissal",
        )
        if (reportDismissal) current.callbacks.onDismissed(current.content)
        current.dialog.dismiss()
    }

    private fun configureWindow(dialog: Dialog, presentation: OverlayPresentation) {
        val window = dialog.window ?: return
        val (width, height) = when (presentation.format) {
            OverlayFormat.BANNER -> ViewGroup.LayoutParams.MATCH_PARENT to ViewGroup.LayoutParams.WRAP_CONTENT
            OverlayFormat.MODAL -> ViewGroup.LayoutParams.WRAP_CONTENT to ViewGroup.LayoutParams.WRAP_CONTENT
            OverlayFormat.FULLSCREEN -> ViewGroup.LayoutParams.MATCH_PARENT to ViewGroup.LayoutParams.MATCH_PARENT
        }
        window.setLayout(width, height)
        window.setGravity(
            when (presentation.format) {
                OverlayFormat.BANNER -> if (presentation.position == OverlayPosition.BOTTOM) Gravity.BOTTOM else Gravity.TOP
                else -> Gravity.CENTER
            },
        )
        EngageLogger.debug(
            "InApp.Overlay",
            "window configured format=${presentation.format} position=${presentation.position} width=$width height=$height",
        )
    }

    private fun animateIn(view: android.view.View, animation: InAppAnimation) {
        EngageLogger.verbose("InApp.Overlay", "entry animation=$animation")
        when (animation) {
            InAppAnimation.NONE -> Unit
            InAppAnimation.FADE -> view.apply { alpha = 0f; animate().alpha(1f).setDuration(220).start() }
            InAppAnimation.SCALE -> view.apply {
                alpha = 0f
                scaleX = 0.92f
                scaleY = 0.92f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
            }
            InAppAnimation.SLIDE -> view.apply {
                translationY = resources.displayMetrics.density * 48f
                alpha = 0f
                animate().translationY(0f).alpha(1f).setDuration(240).start()
            }
        }
    }

    private data class ActiveOverlay(
        val content: InAppContent,
        val dialog: Dialog,
        val callbacks: InAppRenderCallbacks,
    )
}
