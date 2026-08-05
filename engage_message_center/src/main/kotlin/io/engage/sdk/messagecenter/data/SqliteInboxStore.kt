package io.engage.sdk.messagecenter.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.engage.sdk.EngageLogger
import io.engage.sdk.messagecenter.domain.CachedInboxWindow
import io.engage.sdk.messagecenter.domain.InboxScope
import io.engage.sdk.messagecenter.domain.InboxStore
import io.engage.sdk.messagecenter.domain.InboxStoreSnapshot
import io.engage.sdk.messagecenter.domain.MutationResult
import io.engage.sdk.messagecenter.domain.MutationStatus
import io.engage.sdk.messagecenter.domain.MutationType
import io.engage.sdk.messagecenter.domain.PendingMutation
import io.engage.sdk.messagecenter.domain.RemoteInboxEntry
import io.engage.sdk.messagecenter.domain.RemoteInboxPage
import io.engage.sdk.messagecenter.domain.ReservedMutationBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class SqliteInboxStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), InboxStore {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(InboxStoreSnapshot())
    private var activeGeneration: Long? = null
    override val snapshot: StateFlow<InboxStoreSnapshot> = mutableSnapshot.asStateFlow()

    override fun onCreate(database: SQLiteDatabase) {
        EngageLogger.debug("MessageCenter.Store", "database schema creating version=$DATABASE_VERSION")
        database.execSQL(
            """
            CREATE TABLE inbox_entries (
                generation INTEGER NOT NULL,
                id TEXT NOT NULL,
                entry_key TEXT NOT NULL,
                payload TEXT NOT NULL,
                scope TEXT NOT NULL,
                sent_at TEXT NOT NULL,
                expires_at TEXT,
                read_at TEXT,
                PRIMARY KEY (generation, id)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE inbox_page_state (
                generation INTEGER NOT NULL,
                page_size INTEGER NOT NULL,
                cursor_key TEXT NOT NULL,
                next_cursor TEXT,
                has_more INTEGER NOT NULL,
                PRIMARY KEY (generation, page_size, cursor_key)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE inbox_page_entries (
                generation INTEGER NOT NULL,
                page_size INTEGER NOT NULL,
                cursor_key TEXT NOT NULL,
                position INTEGER NOT NULL,
                entry_id TEXT NOT NULL,
                PRIMARY KEY (generation, page_size, cursor_key, position)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE inbox_meta (
                generation INTEGER PRIMARY KEY,
                unread_count INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE inbox_mutations (
                operation_id TEXT PRIMARY KEY,
                generation INTEGER NOT NULL,
                mutation_type TEXT NOT NULL,
                entry_id TEXT,
                occurred_at TEXT NOT NULL,
                batch_id TEXT
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX inbox_mutations_generation ON inbox_mutations(generation)")
        EngageLogger.debug("MessageCenter.Store", "database schema created")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override suspend fun activateGeneration(generation: Long) = mutex.withLock {
        EngageLogger.debug("MessageCenter.Store", "generation activating generation=$generation")
        writableDatabase.transaction {
            listOf(
                "inbox_page_entries",
                "inbox_page_state",
                "inbox_entries",
                "inbox_meta",
                "inbox_mutations",
            ).forEach { table ->
                delete(table, "generation != ?", arrayOf(generation.toString()))
            }
        }
        activeGeneration = generation
        publishLocked(generation)
        EngageLogger.info("MessageCenter.Store", "generation active generation=$generation")
    }

    override suspend fun savePage(
        generation: Long,
        pageSize: Int,
        cursor: String?,
        page: RemoteInboxPage,
    ) = mutex.withLock {
        EngageLogger.debug(
            "MessageCenter.Store",
            "page saving generation=$generation pageSize=$pageSize hasCursor=${cursor != null} " +
                "entries=${page.entries.size} hasMore=${page.hasMore}",
        )
        writableDatabase.transaction {
            page.entries.forEach { entry -> upsertEntry(generation, entry) }
            val cursorKey = cursor.orEmpty()
            insertWithOnConflict(
                "inbox_page_state",
                null,
                ContentValues().apply {
                    put("generation", generation)
                    put("page_size", pageSize)
                    put("cursor_key", cursorKey)
                    put("next_cursor", page.nextCursor)
                    put("has_more", if (page.hasMore) 1 else 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            delete(
                "inbox_page_entries",
                "generation = ? AND page_size = ? AND cursor_key = ?",
                arrayOf(generation.toString(), pageSize.toString(), cursorKey),
            )
            page.entries.forEachIndexed { index, entry ->
                insertOrThrow(
                    "inbox_page_entries",
                    null,
                    ContentValues().apply {
                        put("generation", generation)
                        put("page_size", pageSize)
                        put("cursor_key", cursorKey)
                        put("position", index)
                        put("entry_id", entry.id)
                    },
                )
            }
            insertWithOnConflict(
                "inbox_meta",
                null,
                ContentValues().apply {
                    put("generation", generation)
                    put("unread_count", page.unreadCount)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        publishIfActiveLocked(generation)
        EngageLogger.debug("MessageCenter.Store", "page saved generation=$generation entries=${page.entries.size}")
    }

    override suspend fun cachedWindow(generation: Long, pageSize: Int): CachedInboxWindow = mutex.withLock {
        EngageLogger.verbose("MessageCenter.Store", "cached window reading generation=$generation pageSize=$pageSize")
        val ids = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        var cursorKey = ""
        var nextCursor: String? = null
        var hasMore = false
        while (visited.add(cursorKey)) {
            val state = readableDatabase.query(
                "inbox_page_state",
                arrayOf("next_cursor", "has_more"),
                "generation = ? AND page_size = ? AND cursor_key = ?",
                arrayOf(generation.toString(), pageSize.toString(), cursorKey),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.nullableString(0) to (cursor.getInt(1) != 0)
            } ?: break
            readableDatabase.query(
                "inbox_page_entries",
                arrayOf("entry_id"),
                "generation = ? AND page_size = ? AND cursor_key = ?",
                arrayOf(generation.toString(), pageSize.toString(), cursorKey),
                null,
                null,
                "position ASC",
            ).use { cursor -> while (cursor.moveToNext()) ids += cursor.getString(0) }
            nextCursor = state.first
            hasMore = state.second
            if (!hasMore || nextCursor == null) break
            cursorKey = nextCursor
        }
        CachedInboxWindow(ids.toList(), nextCursor, hasMore).also {
            EngageLogger.debug(
                "MessageCenter.Store",
                "cached window read generation=$generation entries=${it.entryIds.size} hasMore=${it.hasMore}",
            )
        }
    }

    override suspend fun enqueue(mutation: PendingMutation) = mutex.withLock {
        EngageLogger.debug(
            "MessageCenter.Store",
            "mutation persisting operationId=${mutation.operationId} generation=${mutation.generation} " +
                "type=${mutation.type} entryId=${mutation.entryId}",
        )
        writableDatabase.insertWithOnConflict(
            "inbox_mutations",
            null,
            ContentValues().apply {
                put("operation_id", mutation.operationId)
                put("generation", mutation.generation)
                put("mutation_type", mutation.type.name)
                put("entry_id", mutation.entryId)
                put("occurred_at", mutation.occurredAt.toString())
                put("batch_id", mutation.batchId)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        publishIfActiveLocked(mutation.generation)
        EngageLogger.debug("MessageCenter.Store", "mutation persisted operationId=${mutation.operationId}")
    }

    override suspend fun reserve(generation: Long, limit: Int): ReservedMutationBatch? = mutex.withLock {
        EngageLogger.verbose("MessageCenter.Store", "mutation reserve requested generation=$generation limit=$limit")
        writableDatabase.transactionWithResult {
            var batchId = query(
                "inbox_mutations",
                arrayOf("batch_id"),
                "generation = ? AND batch_id IS NOT NULL",
                arrayOf(generation.toString()),
                null,
                null,
                "rowid ASC",
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (batchId == null) {
                val operationIds = query(
                    "inbox_mutations",
                    arrayOf("operation_id"),
                    "generation = ? AND batch_id IS NULL",
                    arrayOf(generation.toString()),
                    null,
                    null,
                    "rowid ASC",
                    limit.coerceIn(1, 100).toString(),
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                if (operationIds.isEmpty()) return@transactionWithResult null
                batchId = newId()
                operationIds.forEach { operationId ->
                    update(
                        "inbox_mutations",
                        ContentValues().apply { put("batch_id", batchId) },
                        "operation_id = ? AND batch_id IS NULL",
                        arrayOf(operationId),
                    )
                }
            }
            val operations = readMutations("generation = ? AND batch_id = ?", arrayOf(generation.toString(), batchId))
            ReservedMutationBatch(batchId, generation, operations)
        }.also { batch ->
            EngageLogger.debug(
                "MessageCenter.Store",
                "mutation reserve result generation=$generation batchId=${batch?.batchId} count=${batch?.operations?.size ?: 0}",
            )
        }
    }

    override suspend fun settle(
        batch: ReservedMutationBatch,
        results: List<MutationResult>,
    ): List<MutationResult> = mutex.withLock {
        EngageLogger.debug(
            "MessageCenter.Store",
            "mutation batch settling batchId=${batch.batchId} results=${results.size}",
        )
        val byId = results.associateBy(MutationResult::operationId)
        val rejected = mutableListOf<MutationResult>()
        writableDatabase.transaction {
            batch.operations.forEach { operation ->
                val result = byId[operation.operationId] ?: return@forEach
                if (result.status == MutationStatus.ACCEPTED || result.status == MutationStatus.DUPLICATE) {
                    commitMutation(batch.generation, operation)
                } else {
                    rejected += result
                }
                delete("inbox_mutations", "operation_id = ? AND batch_id = ?", arrayOf(operation.operationId, batch.batchId))
            }
        }
        publishIfActiveLocked(batch.generation)
        EngageLogger.info(
            "MessageCenter.Store",
            "mutation batch settled batchId=${batch.batchId} rejected=${rejected.size}",
        )
        rejected
    }

    override suspend fun clear() = mutex.withLock {
        EngageLogger.warn("MessageCenter.Store", "all local inbox data clearing")
        writableDatabase.transaction {
            delete("inbox_page_entries", null, null)
            delete("inbox_page_state", null, null)
            delete("inbox_entries", null, null)
            delete("inbox_meta", null, null)
            delete("inbox_mutations", null, null)
        }
        mutableSnapshot.value = InboxStoreSnapshot()
        activeGeneration = null
        EngageLogger.warn("MessageCenter.Store", "all local inbox data cleared")
    }

    private fun SQLiteDatabase.upsertEntry(generation: Long, entry: RemoteInboxEntry) {
        insertWithOnConflict(
            "inbox_entries",
            null,
            ContentValues().apply {
                put("generation", generation)
                put("id", entry.id)
                put("entry_key", entry.key)
                put("payload", entry.payload.toString())
                put("scope", entry.scope.name)
                put("sent_at", entry.sentAt.toString())
                put("expires_at", entry.expiresAt?.toString())
                put("read_at", entry.readAt?.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun SQLiteDatabase.commitMutation(generation: Long, mutation: PendingMutation) {
        EngageLogger.verbose(
            "MessageCenter.Store",
            "mutation committing operationId=${mutation.operationId} generation=$generation type=${mutation.type} " +
                "entryId=${mutation.entryId}",
        )
        val unread = queryUnreadCount(generation)
        when (mutation.type) {
            MutationType.MARK_ALL_READ -> {
                update(
                    "inbox_entries",
                    ContentValues().apply { put("read_at", mutation.occurredAt.toString()) },
                    "generation = ? AND read_at IS NULL",
                    arrayOf(generation.toString()),
                )
                writeUnreadCount(generation, 0)
            }
            MutationType.MARK_READ -> mutation.entryId?.let { entryId ->
                val wasUnread = entryReadAt(generation, entryId) == null
                update(
                    "inbox_entries",
                    ContentValues().apply { put("read_at", mutation.occurredAt.toString()) },
                    "generation = ? AND id = ?",
                    arrayOf(generation.toString(), entryId),
                )
                if (wasUnread) writeUnreadCount(generation, (unread - 1).coerceAtLeast(0))
            }
            MutationType.MARK_UNREAD -> mutation.entryId?.let { entryId ->
                val wasRead = entryReadAt(generation, entryId) != null
                update(
                    "inbox_entries",
                    ContentValues().apply { putNull("read_at") },
                    "generation = ? AND id = ?",
                    arrayOf(generation.toString(), entryId),
                )
                if (wasRead) writeUnreadCount(generation, unread + 1)
            }
            MutationType.DELETE -> mutation.entryId?.let { entryId ->
                val wasUnread = entryReadAt(generation, entryId) == null
                val deleted = delete(
                    "inbox_entries",
                    "generation = ? AND id = ?",
                    arrayOf(generation.toString(), entryId),
                )
                if (deleted > 0 && wasUnread) writeUnreadCount(generation, (unread - 1).coerceAtLeast(0))
            }
        }
    }

    private fun publishLocked(generation: Long) {
        val entries = readableDatabase.readEntries(generation).associateBy(RemoteInboxEntry::id).toMutableMap()
        var unreadCount = readableDatabase.queryUnreadCount(generation)
        val pending = readableDatabase.readMutations("generation = ?", arrayOf(generation.toString()))
        pending.forEach { mutation ->
            when (mutation.type) {
                MutationType.MARK_ALL_READ -> {
                    entries.keys.toList().forEach { id ->
                        entries[id] = requireNotNull(entries[id]).copy(readAt = mutation.occurredAt)
                    }
                    unreadCount = 0
                }
                MutationType.MARK_READ -> mutation.entryId?.let { id ->
                    entries[id]?.let { entry ->
                        if (entry.readAt == null) unreadCount = (unreadCount - 1).coerceAtLeast(0)
                        entries[id] = entry.copy(readAt = mutation.occurredAt)
                    }
                }
                MutationType.MARK_UNREAD -> mutation.entryId?.let { id ->
                    entries[id]?.let { entry ->
                        if (entry.readAt != null) unreadCount += 1
                        entries[id] = entry.copy(readAt = null)
                    }
                }
                MutationType.DELETE -> mutation.entryId?.let { id ->
                    entries.remove(id)?.let { entry -> if (entry.readAt == null) unreadCount = (unreadCount - 1).coerceAtLeast(0) }
                }
            }
        }
        val now = clock.instant()
        entries.entries.iterator().let { iterator ->
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                if (entry.expiresAt?.let { !now.isBefore(it) } == true) {
                    if (entry.readAt == null) unreadCount = (unreadCount - 1).coerceAtLeast(0)
                    iterator.remove()
                }
            }
        }
        mutableSnapshot.value = InboxStoreSnapshot(generation, entries, unreadCount, pending.size)
        EngageLogger.verbose(
            "MessageCenter.Store",
            "snapshot published generation=$generation entries=${entries.size} unread=$unreadCount pending=${pending.size}",
        )
    }

    private fun publishIfActiveLocked(generation: Long) {
        if (generation == activeGeneration) publishLocked(generation)
    }

    private fun SQLiteDatabase.readEntries(generation: Long): List<RemoteInboxEntry> = query(
        "inbox_entries",
        arrayOf("id", "entry_key", "payload", "scope", "sent_at", "expires_at", "read_at"),
        "generation = ?",
        arrayOf(generation.toString()),
        null,
        null,
        "sent_at DESC, id DESC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            add(
                RemoteInboxEntry(
                    id = cursor.getString(0),
                    key = cursor.getString(1),
                    payload = json.parseToJsonElement(cursor.getString(2)).jsonObject,
                    scope = InboxScope.valueOf(cursor.getString(3)),
                    sentAt = Instant.parse(cursor.getString(4)),
                    expiresAt = cursor.nullableString(5)?.let(Instant::parse),
                    readAt = cursor.nullableString(6)?.let(Instant::parse),
                ),
            )
        }
    } }

    private fun SQLiteDatabase.readMutations(selection: String, args: Array<String>): List<PendingMutation> = query(
        "inbox_mutations",
        arrayOf("operation_id", "generation", "mutation_type", "entry_id", "occurred_at", "batch_id"),
        selection,
        args,
        null,
        null,
        "rowid ASC",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            add(
                PendingMutation(
                    operationId = cursor.getString(0),
                    generation = cursor.getLong(1),
                    type = MutationType.valueOf(cursor.getString(2)),
                    entryId = cursor.nullableString(3),
                    occurredAt = Instant.parse(cursor.getString(4)),
                    batchId = cursor.nullableString(5),
                ),
            )
        }
    } }

    private fun SQLiteDatabase.queryUnreadCount(generation: Long): Int = query(
        "inbox_meta",
        arrayOf("unread_count"),
        "generation = ?",
        arrayOf(generation.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0).coerceAtLeast(0) else 0 }

    private fun SQLiteDatabase.writeUnreadCount(generation: Long, count: Int) {
        insertWithOnConflict(
            "inbox_meta",
            null,
            ContentValues().apply { put("generation", generation); put("unread_count", count.coerceAtLeast(0)) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun SQLiteDatabase.entryReadAt(generation: Long, entryId: String): String? = query(
        "inbox_entries",
        arrayOf("read_at"),
        "generation = ? AND id = ?",
        arrayOf(generation.toString(), entryId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.nullableString(0) else MISSING_ENTRY }

    private companion object {
        const val DATABASE_NAME = "engage_message_center.db"
        const val DATABASE_VERSION = 1
        const val MISSING_ENTRY = "__missing__"
    }
}

private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private inline fun <T> SQLiteDatabase.transactionWithResult(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        return block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}
