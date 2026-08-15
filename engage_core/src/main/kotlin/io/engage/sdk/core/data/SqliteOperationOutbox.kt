package io.engage.sdk.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.SdkOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal class SqliteOperationOutbox(
    context: Context,
    databaseScope: String = "",
    private val newBatchId: () -> String = { UUID.randomUUID().toString() },
) : SQLiteOpenHelper(context, scopedStorageName(DATABASE, databaseScope), null, VERSION), OperationOutbox {
    private val mutex = Mutex()
    private val json = Json
    private val pendingState = lazy { MutableStateFlow(readAll(readableDatabase)) }

    override val pending: StateFlow<List<SdkOperation>> get() = pendingState.value

    override fun onCreate(db: SQLiteDatabase) {
        EngageLogger.info("Outbox", "creating operations database")
        db.execSQL(
            """
            CREATE TABLE operations (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_id TEXT NOT NULL UNIQUE,
                generation INTEGER NOT NULL,
                type TEXT NOT NULL,
                occurred_at TEXT NOT NULL,
                payload TEXT NOT NULL,
                batch_id TEXT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX operations_batch ON operations(batch_id, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override suspend fun enqueue(operation: SdkOperation): Unit = io {
        val values = ContentValues().apply {
            put("operation_id", operation.operationId)
            put("generation", operation.generation)
            put("type", operation.type.name)
            put("occurred_at", operation.occurredAt)
            put("payload", operation.payload.toString())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "operations",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (rowId == -1L) {
            check(readById(writableDatabase, operation.operationId) == operation) {
                "An Engage operationId is already bound to another operation"
            }
        }
        publishPending()
        EngageLogger.verbose(
            "Outbox",
            "operation persisted operationId=${operation.operationId} type=${operation.type.name} " +
                "generation=${operation.generation} inserted=${rowId != -1L}",
        )
    }

    override suspend fun reserve(
        limit: Int,
        allowedTypes: Set<OperationType>?,
    ): ReservedOperationBatch? = io {
        require(limit in 1..100)
        val database = writableDatabase
        database.beginTransaction()
        try {
            val selection = allowedTypes?.takeIf(Set<OperationType>::isNotEmpty)?.let { types ->
                val placeholders = List(types.size) { "?" }.joinToString(",")
                "type IN ($placeholders)" to types.map(OperationType::name).toTypedArray()
            }
            val eligible = selection?.first ?: "1 = 1"
            val arguments = selection?.second
            val existingBatch = database.rawQuery(
                "SELECT batch_id FROM operations WHERE batch_id IS NOT NULL AND $eligible ORDER BY sequence LIMIT 1",
                arguments,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val batchId = existingBatch ?: newBatchId().also { id ->
                val sequences = database.rawQuery(
                    "SELECT sequence FROM operations WHERE batch_id IS NULL AND $eligible ORDER BY sequence LIMIT ?",
                    buildList {
                        addAll(arguments.orEmpty())
                        add(limit.toString())
                    }.toTypedArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
                sequences.forEach { sequence ->
                    database.execSQL(
                        "UPDATE operations SET batch_id = ? WHERE sequence = ?",
                        arrayOf(id, sequence),
                    )
                }
            }
            val operations = readBatch(database, batchId, allowedTypes)
            database.setTransactionSuccessful()
            operations.takeIf(List<SdkOperation>::isNotEmpty)?.let {
                EngageLogger.verbose("Outbox", "batch persisted batchId=$batchId count=${it.size}")
                ReservedOperationBatch(batchId, it)
            }
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun settle(batchId: String, results: List<OperationResult>): Boolean = io {
        if (results.isEmpty()) return@io false
        val database = writableDatabase
        database.beginTransaction()
        try {
            var settled = 0
            results.forEach { result ->
                settled += database.delete(
                    "operations",
                    "batch_id = ? AND operation_id = ?",
                    arrayOf(batchId, result.operationId),
                )
            }
            database.execSQL(
                "UPDATE operations SET batch_id = NULL WHERE batch_id = ?",
                arrayOf(batchId),
            )
            database.setTransactionSuccessful()
            publishPending(database)
            EngageLogger.verbose(
                "Outbox",
                "batch settlement persisted batchId=$batchId resultCount=${results.size} settled=$settled",
            )
            settled > 0
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun clear(): Unit = io {
        val deleted = writableDatabase.delete("operations", null, null)
        publishPending()
        EngageLogger.warn("Outbox", "operations cleared count=$deleted")
    }

    private fun readBatch(
        database: SQLiteDatabase,
        batchId: String,
        allowedTypes: Set<OperationType>?,
    ): List<SdkOperation> {
        val selection = allowedTypes?.takeIf(Set<OperationType>::isNotEmpty)?.let { types ->
            val placeholders = List(types.size) { "?" }.joinToString(",")
            " AND type IN ($placeholders)" to types.map(OperationType::name).toTypedArray()
        }
        return database.rawQuery(
            """
            SELECT operation_id, generation, type, occurred_at, payload
            FROM operations WHERE batch_id = ?${selection?.first.orEmpty()} ORDER BY sequence
            """.trimIndent(),
            buildList {
                add(batchId)
                addAll(selection?.second.orEmpty())
            }.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SdkOperation(
                            operationId = cursor.getString(0),
                            generation = cursor.getLong(1),
                            type = OperationType.valueOf(cursor.getString(2)),
                            occurredAt = cursor.getString(3),
                            payload = json.parseToJsonElement(cursor.getString(4)) as JsonObject,
                        ),
                    )
                }
            }
        }
    }

    private fun readAll(database: SQLiteDatabase): List<SdkOperation> = database.rawQuery(
        """
        SELECT operation_id, generation, type, occurred_at, payload
        FROM operations ORDER BY sequence
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SdkOperation(
                        operationId = cursor.getString(0),
                        generation = cursor.getLong(1),
                        type = OperationType.valueOf(cursor.getString(2)),
                        occurredAt = cursor.getString(3),
                        payload = json.parseToJsonElement(cursor.getString(4)) as JsonObject,
                    ),
                )
            }
        }
    }

    private fun readById(database: SQLiteDatabase, operationId: String): SdkOperation? = database.rawQuery(
        "SELECT operation_id, generation, type, occurred_at, payload FROM operations WHERE operation_id = ?",
        arrayOf(operationId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        SdkOperation(
            operationId = cursor.getString(0),
            generation = cursor.getLong(1),
            type = OperationType.valueOf(cursor.getString(2)),
            occurredAt = cursor.getString(3),
            payload = json.parseToJsonElement(cursor.getString(4)) as JsonObject,
        )
    }

    private fun publishPending(database: SQLiteDatabase = readableDatabase) {
        if (pendingState.isInitialized()) {
            pendingState.value.value = readAll(database)
            EngageLogger.verbose("Outbox", "pending state published count=${pendingState.value.value.size}")
        }
    }

    private suspend fun <T> io(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private companion object {
        const val DATABASE = "engage_operations.db"
        const val VERSION = 1
    }
}
