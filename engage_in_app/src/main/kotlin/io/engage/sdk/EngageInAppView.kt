package io.engage.sdk

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import io.engage.sdk.inapp.EngageInAppModule
import io.engage.sdk.inapp.render.EngageContentView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** View-system host for an embedded Engage placement. */
public class EngageInAppView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    public var placementKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (isAttachedToWindow) observePlacement()
        }

    private var scope: CoroutineScope? = null
    private var collection: Job? = null
    private var lastContent: InAppContent? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        observePlacement()
    }

    override fun onDetachedFromWindow() {
        collection?.cancel()
        collection = null
        scope?.cancel()
        scope = null
        removeAllViews()
        super.onDetachedFromWindow()
    }

    private fun observePlacement() {
        collection?.cancel()
        removeAllViews()
        val key = placementKey ?: return
        collection = scope?.launch {
            Engage.inApp.placement(key).collectLatest { content ->
                if (content == null && shouldReserveLastSpace()) {
                    getChildAt(0)?.visibility = INVISIBLE
                } else {
                    removeAllViews()
                }
                if (content != null) {
                    lastContent = content
                    addView(
                        EngageContentView(context, content, EngageInAppModule.requireRenderCallbacks()),
                        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                    )
                }
            }
        }
    }

    private fun shouldReserveLastSpace(): Boolean =
        ((lastContent?.presentation as? EmbeddedPresentation)?.emptyState == EmptyStatePolicy.RESERVE_SPACE) &&
            childCount > 0
}
