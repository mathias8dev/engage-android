package io.engage.sdk.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.ReservedOperationBatch
import io.engage.sdk.core.domain.SdkOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal class SqliteOperationOutbox(
    context: Context,
    private val newBatchId: () -> String = { UUID.randomUUID().toString() },
) : SQLiteOpenHelper(context, DATABASE, null, VERSION), OperationOutbox {
    private val mutex = Mutex()
    private val json = Json

    override fun onCreate(db: SQLiteDatabase) {
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
        check(writableDatabase.insertOrThrow("operations", null, values) != -1L)
    }

    override suspend fun reserve(limit: Int): ReservedOperationBatch? = io {
        require(limit in 1..100)
        val database = writableDatabase
        database.beginTransaction()
        try {
            val existingBatch = database.rawQuery(
                "SELECT batch_id FROM operations WHERE batch_id IS NOT NULL ORDER BY sequence LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val batchId = existingBatch ?: newBatchId().also { id ->
                val sequences = database.rawQuery(
                    "SELECT sequence FROM operations WHERE batch_id IS NULL ORDER BY sequence LIMIT ?",
                    arrayOf(limit.toString()),
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
            val operations = readBatch(database, batchId)
            database.setTransactionSuccessful()
            operations.takeIf(List<SdkOperation>::isNotEmpty)?.let { ReservedOperationBatch(batchId, it) }
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
            settled > 0
        } finally {
            database.endTransaction()
        }
    }

    override suspend fun clear(): Unit = io {
        writableDatabase.delete("operations", null, null)
    }

    private fun readBatch(database: SQLiteDatabase, batchId: String): List<SdkOperation> =
        database.rawQuery(
            """
            SELECT operation_id, generation, type, occurred_at, payload
            FROM operations WHERE batch_id = ? ORDER BY sequence
            """.trimIndent(),
            arrayOf(batchId),
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

    private suspend fun <T> io(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private companion object {
        const val DATABASE = "engage_operations.db"
        const val VERSION = 1
    }
}

