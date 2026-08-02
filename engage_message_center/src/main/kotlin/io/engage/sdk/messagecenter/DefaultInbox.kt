package io.engage.sdk.messagecenter

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import io.engage.sdk.Inbox
import io.engage.sdk.InboxEntry
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxError
import io.engage.sdk.InboxErrorCode
import io.engage.sdk.InboxPager
import io.engage.sdk.InboxPagerState
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.messagecenter.data.InboxClient
import io.engage.sdk.messagecenter.data.InboxHttpException
import io.engage.sdk.messagecenter.data.InboxInvalidResponseException
import io.engage.sdk.messagecenter.data.SqliteInboxStore
import io.engage.sdk.messagecenter.domain.CachedInboxWindow
import io.engage.sdk.messagecenter.domain.InboxStore
import io.engage.sdk.messagecenter.domain.MutationResult
import io.engage.sdk.messagecenter.domain.MutationType
import io.engage.sdk.messagecenter.domain.PendingMutation
import io.engage.sdk.messagecenter.domain.RemoteInboxEntry
import io.engage.sdk.messagecenter.domain.RemoteInboxPage
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

internal class DefaultInbox(
    private val context: EngageModuleContext,
    private val store: InboxStore = SqliteInboxStore(context.applicationContext),
    private val client: InboxClient = InboxClient(context),
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Inbox {
    private val enabled = MutableStateFlow(false)
    private val activeGeneration = MutableStateFlow(context.generation.value)
    private val globalError = MutableStateFlow<InboxError?>(null)
    private val pagers = CopyOnWriteArraySet<DefaultInboxPager>()
    private val inFlightMutex = Mutex()
    private val inFlightPages = mutableMapOf<PageRequest, Deferred<RemoteInboxPage>>()
    private val flushMutex = Mutex()
    private var expiryJob: Job? = null

    internal val projectionState: StateFlow<InboxProjectionState> = combine(
        store.snapshot,
        enabled,
        globalError,
        activeGeneration,
    ) { snapshot, isEnabled, error, generation ->
        InboxProjectionState(snapshot, isEnabled, error, generation)
    }.stateIn(
        context.scope,
        SharingStarted.Eagerly,
        InboxProjectionState(store.snapshot.value, false, null, activeGeneration.value),
    )

    override val unreadCount: StateFlow<Int> = combine(
        store.snapshot,
        enabled,
        activeGeneration,
    ) { snapshot, isEnabled, generation ->
        if (isEnabled && snapshot.generation == generation) snapshot.unreadCount else 0
    }.stateIn(context.scope, SharingStarted.Eagerly, 0)

    init {
        context.scope.launch {
            combine(
                context.generation,
                context.privacy,
                context.enabledFeatures,
                context.installationId,
            ) { generation, privacy, features, installationId ->
                RuntimeState(
                    generation,
                    installationId != null && privacy == PrivacyState.OPTED_IN && SdkFeature.MESSAGE_CENTER in features,
                    installationId != null,
                )
            }.distinctUntilChanged().collect { runtime ->
                if (runtime.hasInstallation) {
                    val changed = activeGeneration.value != runtime.generation ||
                        store.snapshot.value.generation != runtime.generation
                    activeGeneration.value = runtime.generation
                    store.activateGeneration(runtime.generation)
                    enabled.value = runtime.enabled
                    if (changed) pagers.forEach { it.onGenerationChanged(runtime.generation) }
                    if (runtime.enabled) catchUp()
                } else {
                    enabled.value = false
                }
            }
        }
        context.scope.launch {
            context.signals.collect { signal ->
                when (signal) {
                    EngageSignal.AppOpened -> catchUp()
                    EngageSignal.LocalDataWiped -> {
                        enabled.value = false
                        store.clear()
                        pagers.forEach { it.onGenerationChanged(context.generation.value) }
                    }
                    else -> Unit
                }
            }
        }
        context.scope.launch {
            store.snapshot.collect { snapshot -> scheduleExpiry(snapshot.generation, snapshot.entries.values) }
        }
        ConnectivityMonitor(context.applicationContext) { catchUp() }
    }

    override fun pager(pageSize: Int): InboxPager {
        require(pageSize in 1..100) { "Inbox pageSize must be between 1 and 100" }
        return DefaultInboxPager(this, pageSize, context.scope).also { pager ->
            pagers += pager
            pager.initialize()
        }
    }

    override suspend fun markRead(entryId: InboxEntryId) {
        val entry = store.snapshot.value.entries[entryId.value] ?: return
        if (entry.readAt != null) return
        enqueue(MutationType.MARK_READ, entryId.value)
    }

    override suspend fun markUnread(entryId: InboxEntryId) {
        val entry = store.snapshot.value.entries[entryId.value] ?: return
        if (entry.readAt == null) return
        enqueue(MutationType.MARK_UNREAD, entryId.value)
    }

    override suspend fun markAllRead() {
        if (store.snapshot.value.unreadCount == 0) return
        enqueue(MutationType.MARK_ALL_READ, null)
    }

    override suspend fun delete(entryId: InboxEntryId) {
        if (entryId.value !in store.snapshot.value.entries) return
        enqueue(MutationType.DELETE, entryId.value)
    }

    suspend fun wipe() {
        enabled.value = false
        store.clear()
        pagers.forEach { it.onGenerationChanged(context.generation.value) }
    }

    private suspend fun enqueue(type: MutationType, entryId: String?) {
        if (!enabled.value) return
        globalError.value = null
        val generation = activeGeneration.value
        store.enqueue(
            PendingMutation(
                operationId = newId(),
                generation = generation,
                type = type,
                entryId = entryId,
                occurredAt = clock.instant(),
                batchId = null,
            ),
        )
        context.scope.launch { flushMutations() }
    }

    private fun catchUp() {
        if (!enabled.value) return
        context.scope.launch { flushMutations() }
        context.scope.launch { refreshUnreadCount() }
        pagers.forEach { pager -> context.scope.launch { pager.refresh() } }
    }

    private suspend fun refreshUnreadCount() {
        val generation = activeGeneration.value
        try {
            fetchPage(generation, DEFAULT_PAGE_SIZE, null)
            clearRetryableGlobalError()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            globalError.value = error.toInboxError()
            if (error is InboxHttpException && error.statusCode == 409) {
                runCatching { context.refresh() }
            }
        }
    }

    private suspend fun flushMutations() = flushMutex.withLock {
        if (!enabled.value) return
        val generation = activeGeneration.value
        while (enabled.value && activeGeneration.value == generation) {
            val batch = store.reserve(generation) ?: break
            try {
                val results = client.mutate(batch)
                val rejected = store.settle(batch, results)
                if (rejected.isNotEmpty()) globalError.value = rejected.toInboxError()
                else clearRetryableGlobalError()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                globalError.value = error.toInboxError()
                if (error is InboxHttpException && error.statusCode == 409) {
                    runCatching { context.refresh() }
                }
                break
            }
        }
    }

    internal suspend fun fetchPage(generation: Long, pageSize: Int, cursor: String?): RemoteInboxPage {
        check(enabled.value) { "Engage Message Center is disabled" }
        val key = PageRequest(generation, pageSize, cursor)
        val deferred = inFlightMutex.withLock {
            inFlightPages[key] ?: context.scope.async {
                val page = client.page(cursor, pageSize)
                store.savePage(generation, pageSize, cursor, page)
                page
            }.also { created ->
                inFlightPages[key] = created
                created.invokeOnCompletion {
                    context.scope.launch {
                        inFlightMutex.withLock {
                            if (inFlightPages[key] === created) inFlightPages.remove(key)
                        }
                    }
                }
            }
        }
        return deferred.await()
    }

    internal suspend fun cachedWindow(pageSize: Int): CachedInboxWindow =
        store.cachedWindow(activeGeneration.value, pageSize)

    internal fun project(window: PagerWindow): InboxPagerState {
        val snapshot = store.snapshot.value
        val entries = if (enabled.value && snapshot.generation == window.generation) {
            window.entryIds.mapNotNull(snapshot.entries::get).map(RemoteInboxEntry::toPublic)
        } else {
            emptyList()
        }
        return InboxPagerState(
            entries = entries,
            isRefreshing = window.isRefreshing,
            isLoadingMore = window.isLoadingMore,
            hasMore = enabled.value && window.hasMore,
            error = window.error ?: globalError.value,
        )
    }

    internal fun generation(): Long = activeGeneration.value
    internal fun isEnabled(): Boolean = enabled.value
    internal fun unregister(pager: DefaultInboxPager) { pagers -= pager }
    internal suspend fun contextRefresh() = context.refresh()
    internal fun clearRetryableGlobalError() {
        if (globalError.value?.isRetryable == true) globalError.value = null
    }

    private fun scheduleExpiry(generation: Long, entries: Collection<RemoteInboxEntry>) {
        expiryJob?.cancel()
        val now = clock.instant()
        val next = entries.mapNotNull(RemoteInboxEntry::expiresAt).filter { it.isAfter(now) }.minOrNull() ?: return
        expiryJob = context.scope.launch {
            delay(Duration.between(now, next).toMillis().coerceAtLeast(1) + 1)
            store.activateGeneration(generation)
        }
    }

    private data class RuntimeState(val generation: Long, val enabled: Boolean, val hasInstallation: Boolean)
    private data class PageRequest(val generation: Long, val pageSize: Int, val cursor: String?)

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}

internal data class InboxProjectionState(
    val snapshot: io.engage.sdk.messagecenter.domain.InboxStoreSnapshot,
    val enabled: Boolean,
    val error: InboxError?,
    val generation: Long,
)

internal data class PagerWindow(
    val generation: Long,
    val entryIds: List<String> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: InboxError? = null,
)

internal class DefaultInboxPager(
    private val inbox: DefaultInbox,
    private val pageSize: Int,
    parentScope: CoroutineScope,
) : InboxPager {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val commandMutex = Mutex()
    private val inFlightCommandMutex = Mutex()
    private val inFlightCommands = mutableMapOf<PagerCommand, Deferred<Unit>>()
    private val window = MutableStateFlow(PagerWindow(inbox.generation()))

    override val state: StateFlow<InboxPagerState> = combine(
        window,
        inbox.projectionState,
    ) { currentWindow, _ ->
        inbox.project(currentWindow)
    }.stateIn(scope, SharingStarted.Eagerly, InboxPagerState())

    fun initialize() {
        scope.launch {
            restoreCachedWindow()
            if (inbox.isEnabled()) refresh()
        }
    }

    fun onGenerationChanged(generation: Long) {
        if (closed.get()) return
        window.value = PagerWindow(generation)
        scope.launch {
            restoreCachedWindow()
            if (inbox.isEnabled()) refresh()
        }
    }

    override suspend fun refresh() = runCoalesced(PagerCommand.REFRESH) {
        ensureOpen()
        if (!inbox.isEnabled()) return@runCoalesced
        val generation = inbox.generation()
        val targetSize = maxOf(pageSize, window.value.entryIds.size)
        window.value = window.value.copy(generation = generation, isRefreshing = true, error = null)
        try {
            val ids = linkedSetOf<String>()
            val visited = mutableSetOf<String?>()
            var cursor: String? = null
            var hasMore: Boolean
            do {
                if (!visited.add(cursor)) throw InboxInvalidResponseException("Inbox cursor loop detected")
                val page = inbox.fetchPage(generation, pageSize, cursor)
                page.entries.forEach { ids += it.id }
                cursor = page.nextCursor
                hasMore = page.hasMore
            } while (hasMore && ids.size < targetSize)
            if (inbox.generation() != generation) throw InboxGenerationChangedException()
            window.value = PagerWindow(generation, ids.toList(), cursor, hasMore)
            inbox.clearRetryableGlobalError()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            window.value = window.value.copy(isRefreshing = false, error = error.toInboxError())
            if (error is InboxHttpException && error.statusCode == 409) runCatching { inbox.contextRefresh() }
        }
    }

    override suspend fun loadNextPage() = runCoalesced(PagerCommand.LOAD_NEXT_PAGE) {
        ensureOpen()
        if (!inbox.isEnabled() || !window.value.hasMore) return@runCoalesced
        val generation = inbox.generation()
        val cursor = window.value.nextCursor ?: return@runCoalesced
        window.value = window.value.copy(isLoadingMore = true, error = null)
        try {
            val page = inbox.fetchPage(generation, pageSize, cursor)
            if (inbox.generation() != generation) throw InboxGenerationChangedException()
            window.value = PagerWindow(
                generation = generation,
                entryIds = (window.value.entryIds + page.entries.map(RemoteInboxEntry::id)).distinct(),
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
            )
            inbox.clearRetryableGlobalError()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            window.value = window.value.copy(isLoadingMore = false, error = error.toInboxError())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inbox.unregister(this)
        scope.cancel()
    }

    private suspend fun restoreCachedWindow() {
        val generation = inbox.generation()
        val cached = inbox.cachedWindow(pageSize)
        if (!closed.get() && inbox.generation() == generation) {
            window.value = PagerWindow(generation, cached.entryIds, cached.nextCursor, cached.hasMore)
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "InboxPager is closed" }
    }

    private suspend fun runCoalesced(command: PagerCommand, block: suspend () -> Unit) {
        val request = inFlightCommandMutex.withLock {
            inFlightCommands[command]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
                commandMutex.withLock { block() }
            }.also { inFlightCommands[command] = it }
        }
        request.start()
        try {
            request.await()
        } finally {
            inFlightCommandMutex.withLock {
                if (request.isCompleted && inFlightCommands[command] === request) {
                    inFlightCommands.remove(command)
                }
            }
        }
    }

    private enum class PagerCommand {
        REFRESH,
        LOAD_NEXT_PAGE,
    }
}

private class InboxGenerationChangedException : IOException("Inbox generation changed")

private fun RemoteInboxEntry.toPublic() = InboxEntry(
    id = InboxEntryId(id),
    key = key,
    payload = payload,
    sentAt = sentAt,
    expiresAt = expiresAt,
    readAt = readAt,
)

private fun Throwable.toInboxError(): InboxError = when (this) {
    is InboxGenerationChangedException -> InboxError(
        InboxErrorCode.GENERATION_CHANGED,
        message.orEmpty(),
        true,
    )
    is InboxInvalidResponseException -> InboxError(InboxErrorCode.INVALID_RESPONSE, message.orEmpty(), false)
    is InboxHttpException -> when (statusCode) {
        401 -> InboxError(InboxErrorCode.UNAUTHORIZED, message, false)
        409 -> InboxError(InboxErrorCode.GENERATION_CHANGED, message, true)
        in 500..599 -> InboxError(InboxErrorCode.SERVER, message, true)
        else -> InboxError(InboxErrorCode.SERVER, message, false)
    }
    is IOException -> InboxError(InboxErrorCode.NETWORK, message ?: "Inbox network error", true)
    else -> InboxError(InboxErrorCode.SERVER, message ?: "Inbox error", false)
}

private fun List<MutationResult>.toInboxError(): InboxError {
    val first = first()
    return InboxError(
        InboxErrorCode.SERVER,
        first.message ?: first.errorCode ?: "Inbox mutation was rejected",
        false,
    )
}

private class ConnectivityMonitor(context: Context, notifyAvailable: () -> Unit) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = notifyAvailable()
    }

    init {
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
        }
    }
}
