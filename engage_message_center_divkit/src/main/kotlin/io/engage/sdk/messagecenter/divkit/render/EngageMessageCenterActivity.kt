package io.engage.sdk.messagecenter.divkit.render

import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RestrictTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxPager
import io.engage.sdk.InboxPagerState
import io.engage.sdk.messageCenter
import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule
import io.engage.sdk.messagecenter.divkit.R
import io.engage.sdk.messagecenter.divkit.data.RenderingGenerationChangedException
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EngageMessageCenterActivity : ComponentActivity() {
    private lateinit var pager: InboxPager
    private lateinit var adapter: InboxAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyState: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorBanner: TextView
    private var pagerState = InboxPagerState()
    private var renderings: Map<InboxEntryId, RenderingResolution> = emptyMap()
    private var renderingError = false
    private var loadNextJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngageLogger.info("MessageCenter.Activity", "created restored=${savedInstanceState != null}")
        val runtime = EngageMessageCenterDivKitModule.requireRuntime()
        val inbox = Engage.messageCenter.inbox
        pager = inbox.pager(PAGE_SIZE)
        adapter = InboxAdapter(
            lifecycleScope,
            InboxActionRouter(inbox, runtime.renderingSupport()),
        )
        setContentView(createContentView())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    pager.state.collect { state ->
                        EngageLogger.verbose(
                            "MessageCenter.Activity",
                            "pager state entries=${state.entries.size} refreshing=${state.isRefreshing} " +
                                "loadingMore=${state.isLoadingMore} hasMore=${state.hasMore} error=${state.error?.code}",
                        )
                        pagerState = state
                        render()
                        requestNextPageIfNeeded()
                    }
                }
                launch {
                    pager.state
                        .map { state -> state.entries.map { entry -> entry.id } }
                        .distinctUntilChanged()
                        .collectLatest { entryIds ->
                            EngageLogger.debug("MessageCenter.Activity", "renderings loading entries=${entryIds.size}")
                            renderings = runtime.repository.cached(entryIds)
                            renderingError = false
                            render()
                            if (entryIds.isNotEmpty()) {
                                try {
                                    renderings = runtime.repository.resolve(entryIds)
                                } catch (error: Throwable) {
                                    if (error is CancellationException) throw error
                                    if (error !is RenderingGenerationChangedException) renderingError = true
                                    EngageLogger.error("MessageCenter.Activity", "rendering resolution failed", error)
                                }
                                render()
                            }
                        }
                }
            }
        }
    }

    override fun onDestroy() {
        EngageLogger.info("MessageCenter.Activity", "destroying")
        if (::pager.isInitialized) pager.close()
        super.onDestroy()
    }

    private fun createContentView(): View {
        EngageLogger.debug("MessageCenter.Activity", "content view creating")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt()))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        root.addView(createHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        errorBanner = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, 0xFF000000.toInt()))
            setBackgroundColor(resolveThemeColor(android.R.attr.colorAccent, 0xFFE0E0E0.toInt()) and 0x33FFFFFF)
        }
        root.addView(errorBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val content = FrameLayout(this)
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EngageMessageCenterActivity)
            adapter = this@EngageMessageCenterActivity.adapter
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(20))
            addItemDecoration(InboxSpacingDecoration(dp(8)))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    EngageLogger.verbose("MessageCenter.Activity", "list scrolled dx=$dx dy=$dy")
                    requestNextPageIfNeeded()
                }
            })
        }
        content.addView(
            recyclerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        emptyState = TextView(this).apply {
            gravity = Gravity.CENTER
            setText(R.string.engage_message_center_empty)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        content.addView(
            emptyState,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        progress = ProgressBar(this)
        content.addView(
            progress,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )

        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(resolveThemeColor(android.R.attr.colorAccent, 0xFF666666.toInt()))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { refreshAll() }
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        EngageLogger.debug("MessageCenter.Activity", "content view created")
        return root
    }

    private fun createHeader(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)

        addView(
            Button(context).apply {
                text = BACK_GLYPH
                contentDescription = getString(R.string.engage_message_center_back)
                isAllCaps = false
                setOnClickListener {
                    EngageLogger.info("MessageCenter.Activity", "back requested")
                    finish()
                }
            },
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            TextView(context).apply {
                setText(R.string.engage_message_center_title)
                setTextAppearance(android.R.style.TextAppearance_Material_Title)
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        addView(
            Button(context).apply {
                setText(R.string.engage_message_center_mark_all_read)
                isAllCaps = false
                setOnClickListener {
                    EngageLogger.info("MessageCenter.Activity", "mark all read requested")
                    lifecycleScope.launch { Engage.messageCenter.inbox.markAllRead() }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun refreshAll() {
        EngageLogger.info("MessageCenter.Activity", "manual refresh requested")
        lifecycleScope.launch {
            pager.refresh()
            val entryIds = pager.state.value.entries.map { entry -> entry.id }
            if (entryIds.isNotEmpty()) {
                try {
                    renderings = EngageMessageCenterDivKitModule.requireRuntime().repository.resolve(entryIds)
                    renderingError = false
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error !is RenderingGenerationChangedException) renderingError = true
                    EngageLogger.error("MessageCenter.Activity", "manual rendering refresh failed", error)
                }
            }
            render()
            EngageLogger.info("MessageCenter.Activity", "manual refresh completed entries=${pager.state.value.entries.size}")
        }
    }

    private fun requestNextPageIfNeeded() {
        if (!::adapter.isInitialized || !pagerState.hasMore || pagerState.isLoadingMore) return
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (lastVisible < adapter.itemCount - PREFETCH_DISTANCE) return
        if (loadNextJob?.isActive == true) return
        EngageLogger.debug(
            "MessageCenter.Activity",
            "next page requested lastVisible=$lastVisible itemCount=${adapter.itemCount}",
        )
        loadNextJob = lifecycleScope.launch { pager.loadNextPage() }
    }

    private fun render() {
        if (!::adapter.isInitialized || !::swipeRefresh.isInitialized) return
        EngageLogger.verbose(
            "MessageCenter.Activity",
            "rendering UI entries=${pagerState.entries.size} renderings=${renderings.size} " +
                "pagerError=${pagerState.error?.code} renderingError=$renderingError",
        )
        adapter.submitList(
            pagerState.entries.map { entry -> InboxUiItem(entry, renderings[entry.id]) },
            ::requestNextPageIfNeeded,
        )
        swipeRefresh.isRefreshing = pagerState.isRefreshing
        progress.visibility = if (pagerState.entries.isEmpty() && pagerState.isRefreshing) View.VISIBLE else View.GONE
        emptyState.visibility = if (pagerState.entries.isEmpty() && !pagerState.isRefreshing) View.VISIBLE else View.GONE
        emptyState.setText(
            if (pagerState.error != null) R.string.engage_message_center_retry
            else R.string.engage_message_center_empty,
        )
        val showError = pagerState.entries.isNotEmpty() && (pagerState.error != null || renderingError)
        errorBanner.visibility = if (showError) View.VISIBLE else View.GONE
        if (showError) {
            errorBanner.setText(
                if (renderingError) R.string.engage_message_center_rendering_retry
                else R.string.engage_message_center_retry,
            )
        }
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 5
        const val BACK_GLYPH = "‹"
    }
}

private class InboxSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.top = spacing / 2
        outRect.bottom = spacing / 2
    }
}
