package io.engage.sdk.messagecenter.divkit.render

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RestrictTo
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.Inbox
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
    private lateinit var inbox: Inbox
    private lateinit var pager: InboxPager
    private lateinit var adapter: InboxAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var filterBar: View
    private lateinit var allFilter: TextView
    private lateinit var unreadFilter: TextView
    private lateinit var markAllRead: TextView
    private lateinit var unreadSubtitle: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorBanner: TextView
    private var pagerState = InboxPagerState()
    private var reportedUnreadCount = 0
    private var selectedFilter = InboxViewFilter.ALL
    private var renderings: Map<InboxEntryId, RenderingResolution> = emptyMap()
    private var renderingError = false
    private var loadNextJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        EngageLogger.info("MessageCenter.Activity", "created restored=${savedInstanceState != null}")
        val runtime = EngageMessageCenterDivKitModule.requireRuntime()
        inbox = Engage.messageCenter.inbox
        reportedUnreadCount = inbox.unreadCount.value
        pager = inbox.pager(PAGE_SIZE)
        adapter = InboxAdapter(
            lifecycleScope,
            InboxActionRouter(inbox, runtime.renderingSupport()),
            ::openDetail,
        )
        setContentView(createContentView())
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars =
            nightMode != Configuration.UI_MODE_NIGHT_YES

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
                    inbox.unreadCount.collect { count ->
                        reportedUnreadCount = count
                        render()
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
            setBackgroundColor(color(R.color.engage_message_center_page))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        root.addView(createHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))
        filterBar = createFilterBar()
        root.addView(filterBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))

        errorBanner = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(color(R.color.engage_message_center_text))
            setBackgroundColor(color(R.color.engage_message_center_accent_soft))
            textSize = 13f
        }
        root.addView(
            errorBanner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val content = FrameLayout(this)
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EngageMessageCenterActivity)
            adapter = this@EngageMessageCenterActivity.adapter
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(24))
            addItemDecoration(InboxSpacingDecoration(dp(12)))
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
        emptyState = createEmptyState()
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
            setColorSchemeColors(color(R.color.engage_message_center_accent))
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
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(color(R.color.engage_message_center_header))

        addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.engage_message_center_back)
                contentDescription = getString(R.string.engage_message_center_back)
                background = roundedRippleBackground(
                    fillColor = Color.argb(24, 255, 255, 255),
                    rippleColor = Color.argb(52, 255, 255, 255),
                    radius = dp(24).toFloat(),
                )
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    EngageLogger.info("MessageCenter.Activity", "back requested")
                    finish()
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) },
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        setText(R.string.engage_message_center_title)
                        setTextColor(Color.WHITE)
                        textSize = 24f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                unreadSubtitle = TextView(context).apply {
                    setTextColor(color(R.color.engage_message_center_header_secondary))
                    textSize = 13f
                    visibility = View.GONE
                }
                addView(
                    unreadSubtitle,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun createFilterBar(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setBackgroundColor(color(R.color.engage_message_center_page))

        allFilter = filterChip(R.string.engage_message_center_filter_all, InboxViewFilter.ALL)
        unreadFilter = filterChip(R.string.engage_message_center_filter_unread, InboxViewFilter.UNREAD)
        addView(allFilter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        addView(
            unreadFilter,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) },
        )
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        markAllRead = TextView(context).apply {
            setText(R.string.engage_message_center_mark_all_read)
            setTextColor(color(R.color.engage_message_center_accent))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(4), 0)
            isClickable = true
            isFocusable = true
            background = roundedRippleBackground(
                fillColor = Color.TRANSPARENT,
                rippleColor = colorWithAlpha(R.color.engage_message_center_accent, 28),
                radius = dp(16).toFloat(),
            )
            setOnClickListener {
                EngageLogger.info("MessageCenter.Activity", "mark all read requested")
                lifecycleScope.launch { inbox.markAllRead() }
            }
        }
        addView(markAllRead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    private fun filterChip(label: Int, filter: InboxViewFilter): TextView = TextView(this).apply {
        setText(label)
        gravity = Gravity.CENTER
        textSize = 13f
        setPadding(dp(20), 0, dp(20), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (selectedFilter == filter) return@setOnClickListener
            selectedFilter = filter
            recyclerView.scrollToPosition(0)
            render()
        }
    }

    private fun createEmptyState(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(24), dp(32), dp(24))

        addView(
            FrameLayout(context).apply {
                background = roundedBackground(
                    color(R.color.engage_message_center_accent_soft),
                    dp(56).toFloat(),
                )
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.engage_message_center_empty)
                        contentDescription = null
                    },
                    FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(dp(112), dp(112)),
        )
        emptyTitle = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(color(R.color.engage_message_center_text))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(
            emptyTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            },
        )
        emptyBody = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(color(R.color.engage_message_center_text_secondary))
            textSize = 14f
            maxWidth = dp(300)
        }
        addView(
            emptyBody,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        addView(
            TextView(context).apply {
                setText(R.string.engage_message_center_refresh)
                setTextColor(color(R.color.engage_message_center_accent))
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.engage_message_center_refresh, 0, 0, 0)
                compoundDrawablePadding = dp(10)
                setPadding(dp(20), 0, dp(20), 0)
                isClickable = true
                isFocusable = true
                background = roundedRippleBackground(
                    fillColor = color(R.color.engage_message_center_surface),
                    rippleColor = colorWithAlpha(R.color.engage_message_center_accent, 28),
                    radius = dp(16).toFloat(),
                    strokeColor = color(R.color.engage_message_center_outline),
                )
                setOnClickListener { refreshAll() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(28) },
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

    private fun openDetail(item: InboxUiItem) {
        if (item.rendering !is RenderingResolution.Available) return
        startActivity(
            Intent(this, EngageMessageCenterDetailActivity::class.java)
                .putExtra(EngageMessageCenterDetailActivity.EXTRA_ENTRY_ID, item.entry.id.value),
        )
    }

    private fun requestNextPageIfNeeded() {
        if (!::adapter.isInitialized || !pagerState.hasMore || pagerState.isLoadingMore) return
        if (selectedFilter == InboxViewFilter.UNREAD && adapter.itemCount == 0) {
            requestNextPage()
            return
        }
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (lastVisible < adapter.itemCount - PREFETCH_DISTANCE) return
        requestNextPage()
    }

    private fun requestNextPage() {
        if (loadNextJob?.isActive == true) return
        EngageLogger.debug("MessageCenter.Activity", "next page requested itemCount=${adapter.itemCount}")
        loadNextJob = lifecycleScope.launch { pager.loadNextPage() }
    }

    private fun render() {
        if (!::adapter.isInitialized || !::swipeRefresh.isInitialized) return
        val model = messageCenterUiModel(
            state = pagerState,
            filter = selectedFilter,
            reportedUnreadCount = reportedUnreadCount,
            renderings = renderings,
            renderingError = renderingError,
        )
        EngageLogger.verbose(
            "MessageCenter.Activity",
            "rendering UI entries=${pagerState.entries.size} visible=${model.items.size} unread=${model.unreadCount} " +
                "filter=$selectedFilter renderings=${renderings.size} pagerError=${pagerState.error?.code} " +
                "renderingError=$renderingError",
        )
        adapter.submitList(model.items, ::requestNextPageIfNeeded)
        swipeRefresh.isRefreshing = pagerState.isRefreshing && pagerState.entries.isNotEmpty()
        progress.visibility = if (model.showProgress) View.VISIBLE else View.GONE
        filterBar.visibility = if (model.showFilters) View.VISIBLE else View.GONE
        markAllRead.visibility = if (model.showMarkAllRead) View.VISIBLE else View.GONE
        unreadSubtitle.visibility = if (model.unreadCount > 0) View.VISIBLE else View.GONE
        if (model.unreadCount > 0) {
            unreadSubtitle.text = resources.getQuantityString(
                R.plurals.engage_message_center_unread_count,
                model.unreadCount,
                model.unreadCount,
            )
        }
        emptyState.visibility = if (model.emptyKind != null) View.VISIBLE else View.GONE
        when (model.emptyKind) {
            MessageCenterEmptyKind.INBOX -> setEmptyCopy(
                R.string.engage_message_center_empty_title,
                R.string.engage_message_center_empty_body,
            )
            MessageCenterEmptyKind.UNREAD -> setEmptyCopy(
                R.string.engage_message_center_no_unread_title,
                R.string.engage_message_center_no_unread_body,
            )
            MessageCenterEmptyKind.ERROR -> setEmptyCopy(
                R.string.engage_message_center_retry_title,
                R.string.engage_message_center_retry,
            )
            null -> Unit
        }
        errorBanner.visibility = if (model.showErrorBanner) View.VISIBLE else View.GONE
        if (model.showErrorBanner) {
            errorBanner.setText(
                if (renderingError) R.string.engage_message_center_rendering_retry
                else R.string.engage_message_center_retry,
            )
        }
        updateFilterAppearance()
        if (model.shouldLoadMoreForUnreadFilter) requestNextPage()
    }

    private fun setEmptyCopy(title: Int, body: Int) {
        emptyTitle.setText(title)
        emptyBody.setText(body)
    }

    private fun updateFilterAppearance() {
        if (!::allFilter.isInitialized || !::unreadFilter.isInitialized) return
        styleFilter(allFilter, selectedFilter == InboxViewFilter.ALL)
        styleFilter(unreadFilter, selectedFilter == InboxViewFilter.UNREAD)
    }

    private fun styleFilter(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(
            color(
                if (selected) R.color.engage_message_center_on_accent
                else R.color.engage_message_center_text,
            ),
        )
        view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = roundedRippleBackground(
            fillColor = color(
                if (selected) R.color.engage_message_center_accent
                else R.color.engage_message_center_surface,
            ),
            rippleColor = colorWithAlpha(R.color.engage_message_center_accent, 36),
            radius = dp(20).toFloat(),
            strokeColor = if (selected) null else color(R.color.engage_message_center_outline),
        )
    }

    private fun roundedRippleBackground(
        fillColor: Int,
        rippleColor: Int,
        radius: Float,
        strokeColor: Int? = null,
    ) = RippleDrawable(
        ColorStateList.valueOf(rippleColor),
        roundedBackground(fillColor, radius, strokeColor),
        null,
    )

    private fun roundedBackground(fillColor: Int, radius: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fillColor)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun color(resource: Int): Int = ContextCompat.getColor(this, resource)

    private fun colorWithAlpha(resource: Int, alpha: Int): Int =
        (color(resource) and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 5
    }
}

private class InboxSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.top = spacing / 2
        outRect.bottom = spacing / 2
    }
}
