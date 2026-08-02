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
        if (active != null || activity.isFinishing || activity.isDestroyed) return false
        val presentation = content.presentation as? OverlayPresentation ?: return false
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
                callbacks.onRenderFailed(content)
                dialog.dismiss()
            }
        }
        val view = EngageContentView(activity, content, forwardedCallbacks)
        dialog.setContentView(view)
        dialog.setCancelable(presentation.dismissal == DismissalPolicy.USER_DISMISSIBLE)
        dialog.setCanceledOnTouchOutside(presentation.dismissal == DismissalPolicy.USER_DISMISSIBLE)
        dialog.setOnCancelListener {
            if (!dismissalReported) {
                dismissalReported = true
                callbacks.onDismissed(content)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(dialog)
            active = null
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
        configureWindow(dialog, presentation)
        animateIn(view, presentation.animation)
        if (presentation.dismissal == DismissalPolicy.AUTO_DISMISS) {
            val delay = requireNotNull(presentation.autoDismissAfterSeconds).toLong() * 1_000L
            handler.postAtTime(
                {
                    if (!dismissalReported && dialog.isShowing) {
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
        val current = active ?: return
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
    }

    private fun animateIn(view: android.view.View, animation: InAppAnimation) {
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

