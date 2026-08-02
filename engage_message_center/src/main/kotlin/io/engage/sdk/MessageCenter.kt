package io.engage.sdk

import io.engage.sdk.messagecenter.EngageMessageCenterModule
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.time.Instant

@JvmInline
public value class InboxEntryId(public val value: String) {
    init {
        require(value.isNotBlank()) { "InboxEntryId must not be blank" }
    }
    override fun toString(): String = value
}

public data class InboxEntry(
    val id: InboxEntryId,
    val key: String,
    val payload: JsonObject,
    val sentAt: Instant,
    val expiresAt: Instant?,
    val readAt: Instant?,
)

public enum class InboxErrorCode {
    NETWORK,
    UNAUTHORIZED,
    GENERATION_CHANGED,
    SERVER,
    INVALID_RESPONSE,
}

public data class InboxError(
    val code: InboxErrorCode,
    val message: String,
    val isRetryable: Boolean,
)

public data class InboxPagerState(
    val entries: List<InboxEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: InboxError? = null,
)

public interface InboxPager : AutoCloseable {
    val state: StateFlow<InboxPagerState>
    suspend fun refresh()
    suspend fun loadNextPage()
    override fun close()
}

public interface Inbox {
    val unreadCount: StateFlow<Int>
    fun pager(pageSize: Int = 20): InboxPager
    suspend fun markRead(entryId: InboxEntryId)
    suspend fun markUnread(entryId: InboxEntryId)
    suspend fun markAllRead()
    suspend fun delete(entryId: InboxEntryId)
}

public interface MessageCenter {
    val inbox: Inbox
}

public val Engage.messageCenter: MessageCenter get() = EngageMessageCenterModule.requireApi()

