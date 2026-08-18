package io.engage.sdk.messagecenter.divkit.render

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntry
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxPagerState
import io.engage.sdk.InboxPager
import io.engage.sdk.InboxSortOrder
import io.engage.sdk.messageCenter
import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule
import io.engage.sdk.messagecenter.divkit.MessageCenterViewError
import io.engage.sdk.messagecenter.divkit.MessageCenterViewErrorCode
import io.engage.sdk.messagecenter.divkit.MessageCenterMaterialTheme
import io.engage.sdk.messagecenter.divkit.MessageCenterViewLayout
import io.engage.sdk.messagecenter.divkit.R
import io.engage.sdk.messagecenter.divkit.data.RenderingGenerationChangedException
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reusable Message Center list content. This view owns no Activity, toolbar, or navigation.
 * The host decides what selecting an [InboxEntry] means.
 */
public class EngageMessageCenterListView(
    context: Context,
    pageSize: Int = DEFAULT_PAGE_SIZE,
    private val sortOrder: InboxSortOrder = InboxSortOrder.NEWEST_FIRST,
    public var onEntryTap: ((InboxEntry) -> Unit)? = null,
    public var onError: ((MessageCenterViewError) -> Unit)? = null,
    startImmediately: Boolean = true,
    private val materialTheme: MessageCenterMaterialTheme = MessageCenterMaterialTheme.defaults(context),
    private val layout: MessageCenterViewLayout = MessageCenterViewLayout(),
) : LinearLayout(context), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime = EngageMessageCenterDivKitModule.requireRuntime()
    private val inbox = Engage.messageCenter.inbox
    private val pageSize = pageSize
    private lateinit var pager: InboxPager
    private val adapter = InboxAdapter(
        scope,
        InboxActionRouter(inbox, runtime.renderingSupport()),
        materialTheme,
        layout,
    ) { item -> onEntryTap?.invoke(item.entry) }
    private val recyclerView: RecyclerView
    private val swipeRefresh: SwipeRefreshLayout
    private val filterBar: View
    private lateinit var headerSummary: TextView
    private lateinit var allFilter: TextView
    private lateinit var unreadFilter: TextView
    private val emptyState: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private val progress: ProgressBar
    private val errorBanner: TextView
    private var pagerState = InboxPagerState()
    private var reportedUnreadCount = 0
    private var selectedFilter = InboxViewFilter.ALL
    private var renderings: Map<InboxEntryId, RenderingResolution> = emptyMap()
    private var renderingError = false
    private var loadNextJob: Job? = null
    private var closed = false
    private var started = false
    private var lastReportedError: String? = null
    private var deleteDialog: Dialog? = null
    private var deleteConfirmationJob: Job? = null

    init {
        require(pageSize in 1..100) { "Inbox pageSize must be between 1 and 100" }
        orientation = VERTICAL
        setBackgroundColor(materialTheme.surface)

        filterBar = createFilterBar().also {
            addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        errorBanner = TextView(context).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
                dp(10),
                dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
                dp(10),
            )
            setTextColor(materialTheme.onSurface)
            setBackgroundColor(materialTheme.primaryContainer)
            textSize = 13f
        }.also {
            addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val content = FrameLayout(context)
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@EngageMessageCenterListView.adapter
            clipToPadding = false
            setPadding(
                dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
                dp(4),
                dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
                dp(24),
            )
            addItemDecoration(
                InboxSpacingDecoration(dp(this@EngageMessageCenterListView.layout.itemSpacingDp)),
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    requestNextPageIfNeeded()
                }
            })
        }
        ItemTouchHelper(
            InboxDeleteSwipeCallback(
                context = context,
                adapter = adapter,
                materialTheme = materialTheme,
                onDeleteRequested = ::requestDelete,
            ),
        ).attachToRecyclerView(recyclerView)
        content.addView(
            recyclerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        emptyState = createEmptyState().also {
            content.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        progress = ProgressBar(context).apply {
            indeterminateTintList = ColorStateList.valueOf(materialTheme.primary)
        }.also {
            content.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        swipeRefresh = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(materialTheme.primary)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener(::refresh)
        }.also { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }

        reportedUnreadCount = inbox.unreadCount.value
        if (startImmediately) start()
        EngageLogger.info("MessageCenter.ListView", "initialized pageSize=$pageSize")
    }

    public fun start() {
        check(!closed) { "EngageMessageCenterListView is closed" }
        if (started) return
        started = true
        pager = inbox.pager(pageSize, sortOrder)
        collectState()
    }

    public fun refresh() {
        if (closed) return
        if (!started) {
            start()
            return
        }
        scope.launch {
            try {
                pager.refresh()
                resolveRenderings(pager.state.value.entries.map(InboxEntry::id))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportError(MessageCenterViewErrorCode.INBOX, error.message ?: "Inbox refresh failed", true)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        deleteDialog?.dismiss()
        deleteDialog = null
        deleteConfirmationJob?.cancel()
        deleteConfirmationJob = null
        if (::pager.isInitialized) pager.close()
        scope.cancel()
        EngageLogger.info("MessageCenter.ListView", "closed")
    }

    private fun requestDelete(entry: InboxEntry) {
        if (closed || deleteConfirmationJob?.isActive == true || deleteDialog?.isShowing == true) return
        showNativeDeleteConfirmation(entry)
    }

    private fun showNativeDeleteConfirmation(entry: InboxEntry) {
        deleteDialog = MaterialDeleteConfirmationDialog(
            context = context,
            materialTheme = materialTheme,
            onConfirm = {
                deleteConfirmationJob = scope.launch { deleteEntry(entry) }
            },
        ).also { dialog ->
            dialog.setOnDismissListener {
                if (deleteDialog === dialog) deleteDialog = null
            }
            dialog.show()
        }
    }

    private suspend fun deleteEntry(entry: InboxEntry) {
        try {
            inbox.delete(entry.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            reportDeleteFailure()
        }
    }

    private fun reportDeleteFailure() {
        reportError(
            MessageCenterViewErrorCode.INBOX,
            context.getString(R.string.engage_message_center_delete_failed),
            true,
        )
    }

    private fun collectState() {
        scope.launch {
            pager.state.collect { state ->
                pagerState = state
                state.error?.let { error ->
                    reportError(MessageCenterViewErrorCode.INBOX, error.message, error.isRetryable)
                }
                render()
                requestNextPageIfNeeded()
            }
        }
        scope.launch {
            inbox.unreadCount.collect { count ->
                reportedUnreadCount = count
                render()
            }
        }
        scope.launch {
            pager.state
                .map { state -> state.entries.map(InboxEntry::id) }
                .distinctUntilChanged()
                .collectLatest(::resolveRenderings)
        }
    }

    private suspend fun resolveRenderings(entryIds: List<InboxEntryId>) {
        renderings = runtime.repository.cached(entryIds)
        renderingError = false
        render()
        if (entryIds.isEmpty()) return
        try {
            renderings = runtime.repository.resolve(entryIds)
            lastReportedError = null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error !is RenderingGenerationChangedException) {
                renderingError = true
                reportError(
                    MessageCenterViewErrorCode.RENDERING,
                    error.message ?: "Inbox rendering resolution failed",
                    true,
                )
            }
        }
        render()
    }

    private fun requestNextPageIfNeeded() {
        if (closed || !pagerState.hasMore || pagerState.isLoadingMore) return
        if (selectedFilter == InboxViewFilter.UNREAD && adapter.itemCount == 0) {
            requestNextPage()
            return
        }
        val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        if (manager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH_DISTANCE) requestNextPage()
    }

    private fun requestNextPage() {
        if (loadNextJob?.isActive == true) return
        loadNextJob = scope.launch {
            try {
                pager.loadNextPage()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportError(MessageCenterViewErrorCode.INBOX, error.message ?: "Inbox pagination failed", true)
            }
        }
    }

    private fun render() {
        val model = messageCenterUiModel(
            state = pagerState,
            filter = selectedFilter,
            reportedUnreadCount = reportedUnreadCount,
            renderings = renderings,
            renderingError = renderingError,
        )
        adapter.submitList(model.items, ::requestNextPageIfNeeded)
        swipeRefresh.isRefreshing = pagerState.isRefreshing && pagerState.entries.isNotEmpty()
        progress.visibility = if (model.showProgress) View.VISIBLE else View.GONE
        filterBar.visibility = if (model.showFilters) View.VISIBLE else View.GONE
        headerSummary.text = messageCenterHeaderSummary(resources, model.messageCount, model.unreadCount)
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
        styleFilter(allFilter, selectedFilter == InboxViewFilter.ALL)
        styleFilter(unreadFilter, selectedFilter == InboxViewFilter.UNREAD)
        if (model.shouldLoadMoreForUnreadFilter) requestNextPage()
    }

    private fun createFilterBar(): View = LinearLayout(context).apply {
        gravity = Gravity.START
        orientation = VERTICAL
        setPadding(
            dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
            dp(8),
            dp(this@EngageMessageCenterListView.layout.horizontalPaddingDp),
            dp(10),
        )
        setBackgroundColor(materialTheme.surface)
        headerSummary = TextView(context).apply {
            setTextColor(materialTheme.onSurfaceVariant)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addView(
            headerSummary,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        allFilter = filterChip(R.string.engage_message_center_filter_all, InboxViewFilter.ALL)
        unreadFilter = filterChip(R.string.engage_message_center_filter_unread, InboxViewFilter.UNREAD)
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
                background = roundedBackground(materialTheme.surfaceContainer, dp(20).toFloat())
                addView(allFilter, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(unreadFilter, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            },
            LayoutParams(dp(160), dp(36)).apply { topMargin = dp(6) },
        )
    }

    private fun filterChip(label: Int, filter: InboxViewFilter): TextView = TextView(context).apply {
        setText(label)
        gravity = Gravity.CENTER
        textSize = 12f
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (selectedFilter != filter) {
                selectedFilter = filter
                recyclerView.scrollToPosition(0)
                render()
            }
        }
    }

    private fun createEmptyState(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(24), dp(32), dp(24))
        addView(
            FrameLayout(context).apply {
                background = roundedBackground(materialTheme.primaryContainer, dp(56).toFloat())
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.engage_message_center_empty)
                        imageTintList = ColorStateList.valueOf(materialTheme.primary)
                        contentDescription = null
                    },
                    FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER),
                )
            },
            LayoutParams(dp(112), dp(112)),
        )
        emptyTitle = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(materialTheme.onSurface)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(emptyTitle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        emptyBody = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(materialTheme.onSurfaceVariant)
            textSize = 14f
            maxWidth = dp(300)
        }
        addView(emptyBody, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(
            TextView(context).apply {
                setText(R.string.engage_message_center_refresh)
                setTextColor(materialTheme.primary)
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(20), 0, dp(20), 0)
                isClickable = true
                isFocusable = true
                background = roundedRippleBackground(
                    materialTheme.surfaceContainerLow,
                    materialTheme.primary.withAlpha(28),
                    dp(16).toFloat(),
                    materialTheme.outlineVariant,
                )
                setOnClickListener { refresh() }
            },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(28) },
        )
    }

    private fun setEmptyCopy(title: Int, body: Int) {
        emptyTitle.setText(title)
        emptyBody.setText(body)
    }

    private fun styleFilter(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) materialTheme.onSurface else materialTheme.onSurfaceVariant)
        view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = roundedRippleBackground(
            if (selected) materialTheme.surfaceContainerLow else Color.TRANSPARENT,
            materialTheme.primary.withAlpha(28),
            dp(18).toFloat(),
        )
        view.elevation = if (selected) dp(1).toFloat() else 0f
    }

    private fun reportError(code: MessageCenterViewErrorCode, message: String, retryable: Boolean) {
        val callback = onError ?: return
        val identity = "${code.name}:$message:$retryable"
        if (identity == lastReportedError) return
        lastReportedError = identity
        callback(MessageCenterViewError(code, message, retryable))
    }

    private fun roundedRippleBackground(fill: Int, ripple: Int, radius: Float, stroke: Int? = null) =
        RippleDrawable(ColorStateList.valueOf(ripple), roundedBackground(fill, radius, stroke), null)

    private fun roundedBackground(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 5
    }
}

internal fun messageCenterHeaderSummary(
    resources: android.content.res.Resources,
    messageCount: Int,
    unreadCount: Int,
): String {
    val messages = resources.getQuantityString(
        R.plurals.engage_message_center_message_count,
        messageCount,
        messageCount,
    )
    val unread = resources.getQuantityString(
        R.plurals.engage_message_center_unread_count,
        unreadCount,
        unreadCount,
    )
    return resources.getString(R.string.engage_message_center_header_summary, messages, unread)
}

private class InboxSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.top = spacing / 2
        outRect.bottom = spacing / 2
    }
}

private class InboxDeleteSwipeCallback(
    context: Context,
    private val adapter: InboxAdapter,
    private val materialTheme: MessageCenterMaterialTheme,
    private val onDeleteRequested: (InboxEntry) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
    private val density = context.resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = materialTheme.error }
    private val deleteIcon = requireNotNull(
        ContextCompat.getDrawable(context, R.drawable.engage_message_center_delete),
    ).mutate().also { DrawableCompat.setTint(it, materialTheme.onError) }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val item = (viewHolder as? InboxAdapter.ViewHolder)?.item
        val entry = inboxDeleteTarget(item) ?: return
        adapter.restore(entry.id)
        onDeleteRequested(entry)
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            val item = viewHolder.itemView
            val cornerRadius = 16f * density
            val swipingLeft = dX < 0f
            canvas.drawRoundRect(
                if (swipingLeft) item.right + dX else item.left.toFloat(),
                item.top.toFloat(),
                if (swipingLeft) item.right.toFloat() else item.left + dX,
                item.bottom.toFloat(),
                cornerRadius,
                cornerRadius,
                background,
            )
            if (kotlin.math.abs(dX) >= 48f * density) {
                val iconSize = (24f * density).toInt()
                val centerX = if (swipingLeft) item.right - 32f * density else item.left + 32f * density
                val centerY = (item.top + item.bottom) / 2f
                deleteIcon.setBounds(
                    (centerX - iconSize / 2f).toInt(),
                    (centerY - iconSize / 2f).toInt(),
                    (centerX + iconSize / 2f).toInt(),
                    (centerY + iconSize / 2f).toInt(),
                )
                deleteIcon.draw(canvas)
            }
        }
        super.onChildDraw(canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
