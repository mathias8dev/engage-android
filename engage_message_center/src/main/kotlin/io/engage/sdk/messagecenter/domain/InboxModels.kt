package io.engage.sdk.messagecenter.domain

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

internal enum class InboxScope { INSTALLATION, PROFILE }
internal enum class MutationType { MARK_READ, MARK_UNREAD, DELETE, MARK_ALL_READ }
internal enum class MutationStatus { ACCEPTED, DUPLICATE, REJECTED }

internal data class RemoteInboxEntry(
    val id: String,
    val key: String,
    val payload: JsonObject,
    val scope: InboxScope,
    val sentAt: Instant,
    val expiresAt: Instant?,
    val readAt: Instant?,
)

internal data class RemoteInboxPage(
    val entries: List<RemoteInboxEntry>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val unreadCount: Int,
)

internal data class PendingMutation(
    val operationId: String,
    val generation: Long,
    val type: MutationType,
    val entryId: String?,
    val occurredAt: Instant,
    val batchId: String?,
)

internal data class ReservedMutationBatch(
    val batchId: String,
    val generation: Long,
    val operations: List<PendingMutation>,
)

internal data class MutationResult(
    val operationId: String,
    val status: MutationStatus,
    val errorCode: String?,
    val message: String?,
)

internal data class InboxRendering(
    val entryId: String,
    val renderer: String,
    val revision: Long,
    val document: JsonObject,
)

internal data class CachedInboxWindow(
    val entryIds: List<String> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

internal data class InboxStoreSnapshot(
    val generation: Long = 0,
    val entries: Map<String, RemoteInboxEntry> = emptyMap(),
    val unreadCount: Int = 0,
    val pendingCount: Int = 0,
)

internal interface InboxStore {
    val snapshot: StateFlow<InboxStoreSnapshot>
    suspend fun activateGeneration(generation: Long)
    suspend fun savePage(generation: Long, pageSize: Int, cursor: String?, page: RemoteInboxPage)
    suspend fun cachedWindow(generation: Long, pageSize: Int): CachedInboxWindow
    suspend fun enqueue(mutation: PendingMutation)
    suspend fun reserve(generation: Long, limit: Int = 100): ReservedMutationBatch?
    suspend fun settle(batch: ReservedMutationBatch, results: List<MutationResult>): List<MutationResult>
    suspend fun clear()
}
