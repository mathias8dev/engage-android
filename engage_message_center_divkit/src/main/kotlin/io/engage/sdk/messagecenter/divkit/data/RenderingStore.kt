package io.engage.sdk.messagecenter.divkit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSnapshot
import io.engage.sdk.EngageLogger
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal class RenderingStore(
    context: Context,
    private val json: Json = Json,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    private var activeGeneration: Long? = null

    override fun onCreate(database: SQLiteDatabase) {
        EngageLogger.debug("MessageCenter.RenderingStore", "database schema creating version=$DATABASE_VERSION")
        database.execSQL(
            """
            CREATE TABLE inbox_renderings (
                generation INTEGER NOT NULL,
                entry_id TEXT NOT NULL,
                available INTEGER NOT NULL,
                renderer TEXT,
                revision INTEGER,
                document TEXT,
                PRIMARY KEY (generation, entry_id)
            )
            """.trimIndent(),
        )
        EngageLogger.debug("MessageCenter.RenderingStore", "database schema created")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun activateGeneration(generation: Long) {
        EngageLogger.debug("MessageCenter.RenderingStore", "generation activating generation=$generation")
        writableDatabase.delete(
            "inbox_renderings",
            "generation != ?",
            arrayOf(generation.toString()),
        )
        activeGeneration = generation
        EngageLogger.verbose("MessageCenter.RenderingStore", "generation active generation=$generation")
    }

    @Synchronized
    fun read(generation: Long, entryIds: Collection<InboxEntryId>): Map<InboxEntryId, RenderingResolution> {
        if (generation != activeGeneration || entryIds.isEmpty()) {
            EngageLogger.verbose(
                "MessageCenter.RenderingStore",
                "read skipped generation=$generation active=$activeGeneration count=${entryIds.size}",
            )
            return emptyMap()
        }
        EngageLogger.verbose("MessageCenter.RenderingStore", "read started generation=$generation count=${entryIds.size}")
        val result = linkedMapOf<InboxEntryId, RenderingResolution>()
        entryIds.chunked(MAX_SQL_ARGUMENTS).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val arguments = arrayOf(generation.toString()) + chunk.map { it.value }
            readableDatabase.query(
                "inbox_renderings",
                arrayOf("entry_id", "available", "renderer", "revision", "document"),
                "generation = ? AND entry_id IN ($placeholders)",
                arguments,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val entryId = InboxEntryId(cursor.getString(0))
                    if (cursor.getInt(1) == 0) {
                        result[entryId] = RenderingResolution.Unavailable(entryId)
                    } else {
                        val resolution = runCatching {
                            RenderingResolution.Available(
                                InboxRenderingSnapshot(
                                    entryId = entryId,
                                    renderer = cursor.getString(2),
                                    revision = cursor.getLong(3),
                                    document = json.parseToJsonElement(cursor.getString(4)).jsonObject,
                                ),
                            )
                        }.getOrNull()
                        if (resolution == null) {
                            EngageLogger.warn(
                                "MessageCenter.RenderingStore",
                                "corrupt rendering evicted entryId=$entryId generation=$generation",
                            )
                            writableDatabase.delete(
                                "inbox_renderings",
                                "generation = ? AND entry_id = ?",
                                arrayOf(generation.toString(), entryId.value),
                            )
                        } else {
                            result[entryId] = resolution
                        }
                    }
                }
            }
        }
        EngageLogger.debug("MessageCenter.RenderingStore", "read completed generation=$generation hits=${result.size}")
        return result
    }

    @Synchronized
    fun write(generation: Long, resolutions: Collection<RenderingResolution>): Boolean {
        if (generation != activeGeneration) {
            EngageLogger.debug(
                "MessageCenter.RenderingStore",
                "write rejected generation=$generation active=$activeGeneration count=${resolutions.size}",
            )
            return false
        }
        EngageLogger.debug("MessageCenter.RenderingStore", "write started generation=$generation count=${resolutions.size}")
        writableDatabase.transaction {
            resolutions.forEach { resolution ->
                insertWithOnConflict(
                    "inbox_renderings",
                    null,
                    ContentValues().apply {
                        put("generation", generation)
                        put("entry_id", resolution.entryId.value)
                        when (resolution) {
                            is RenderingResolution.Available -> {
                                put("available", 1)
                                put("renderer", resolution.snapshot.renderer)
                                put("revision", resolution.snapshot.revision)
                                put("document", resolution.snapshot.document.toString())
                            }
                            is RenderingResolution.Unavailable -> {
                                put("available", 0)
                                putNull("renderer")
                                putNull("revision")
                                putNull("document")
                            }
                        }
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
        EngageLogger.debug("MessageCenter.RenderingStore", "write completed generation=$generation count=${resolutions.size}")
        return true
    }

    @Synchronized
    fun clear() {
        EngageLogger.warn("MessageCenter.RenderingStore", "all cached renderings clearing")
        writableDatabase.delete("inbox_renderings", null, null)
        activeGeneration = null
        EngageLogger.warn("MessageCenter.RenderingStore", "all cached renderings cleared")
    }

    private companion object {
        const val DATABASE_NAME = "engage_message_center_divkit.db"
        const val DATABASE_VERSION = 1
        const val MAX_SQL_ARGUMENTS = 900
    }
}

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
