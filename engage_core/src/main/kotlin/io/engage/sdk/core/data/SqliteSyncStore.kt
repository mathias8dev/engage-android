package io.engage.sdk.core.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.StoredSyncSnapshot
import io.engage.sdk.core.domain.SyncDocument
import io.engage.sdk.core.domain.SyncResponse
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class SqliteSyncStore(context: Context) : SQLiteOpenHelper(context, DATABASE, null, VERSION), SyncStore {
    private val mutex = Mutex()
    private val json = Json
    private val mutableSnapshot by lazy { MutableStateFlow(readSnapshot(readableDatabase)) }

    override val snapshot: StateFlow<StoredSyncSnapshot> get() = mutableSnapshot

    override fun onCreate(db: SQLiteDatabase) {
        EngageLogger.info("SyncStore", "creating synchronization database")
        db.execSQL(
            """
            CREATE TABLE sync_metadata (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                cursor TEXT NULL,
                generation INTEGER NULL,
                revision INTEGER NOT NULL,
                refresh_after_seconds INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_documents (
                module TEXT NOT NULL,
                document_key TEXT NOT NULL,
                revision INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(module, document_key)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse): Unit = io {
        EngageLogger.debug(
            "SyncStore",
            "applying generation=${response.generation} revision=${response.revision} " +
                "requestedModules=${requestedModules.sortedBy { it.name }} documents=${response.documents.size} " +
                "tombstones=${response.tombstones.size} fullSnapshot=${response.fullSnapshot}",
        )
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previousGeneration = readGeneration(database)
            if (previousGeneration != null && previousGeneration != response.generation) {
                database.delete("sync_documents", null, null)
            } else if (response.fullSnapshot) {
                requestedModules.forEach { module ->
                    database.delete("sync_documents", "module = ?", arrayOf(module.name))
                }
            }
            response.tombstones.forEach { tombstone ->
                database.delete(
                    "sync_documents",
                    "module = ? AND document_key = ? AND revision <= ?",
                    arrayOf(tombstone.module.name, tombstone.key, tombstone.revision.toString()),
                )
            }
            response.documents.forEach { document ->
                val values = ContentValues().apply {
                    put("module", document.module.name)
                    put("document_key", document.key)
                    put("revision", document.revision)
                    put("payload", document.payload.toString())
                }
                database.insertWithOnConflict(
                    "sync_documents",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.insertWithOnConflict(
                "sync_metadata",
                null,
                ContentValues().apply {
                    put("singleton", 1)
                    put("cursor", response.cursor)
                    put("generation", response.generation)
                    put("revision", response.revision)
                    put("refresh_after_seconds", response.refreshAfterSeconds)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        mutableSnapshot.value = readSnapshot(database)
        EngageLogger.info(
            "SyncStore",
            "snapshot applied generation=${mutableSnapshot.value.generation} revision=${mutableSnapshot.value.revision} " +
                "documents=${mutableSnapshot.value.documents.size}",
        )
    }

    override suspend fun clear(): Unit = io {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete("sync_documents", null, null)
            database.delete("sync_metadata", null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        mutableSnapshot.value = StoredSyncSnapshot()
        EngageLogger.warn("SyncStore", "synchronized snapshot cleared")
    }

    private fun readSnapshot(database: SQLiteDatabase): StoredSyncSnapshot {
        val metadata = database.rawQuery(
            "SELECT cursor, generation, revision, refresh_after_seconds FROM sync_metadata WHERE singleton = 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else StoredSyncSnapshot(
                cursor = if (cursor.isNull(0)) null else cursor.getString(0),
                generation = if (cursor.isNull(1)) null else cursor.getLong(1),
                revision = cursor.getLong(2),
                refreshAfterSeconds = cursor.getLong(3),
            )
        } ?: StoredSyncSnapshot()
        val documents = database.rawQuery(
            "SELECT module, document_key, revision, payload FROM sync_documents ORDER BY module, document_key",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SyncDocument(
                            module = SdkModule.valueOf(cursor.getString(0)),
                            key = cursor.getString(1),
                            revision = cursor.getLong(2),
                            payload = json.parseToJsonElement(cursor.getString(3)) as JsonObject,
                        ),
                    )
                }
            }
        }
        return metadata.copy(documents = documents)
    }

    private fun readGeneration(database: SQLiteDatabase): Long? = database.rawQuery(
        "SELECT generation FROM sync_metadata WHERE singleton = 1",
        null,
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private suspend fun <T> io(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private companion object {
        const val DATABASE = "engage_sync.db"
        const val VERSION = 1
    }
}
