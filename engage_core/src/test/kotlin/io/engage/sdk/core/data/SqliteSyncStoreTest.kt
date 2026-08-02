package io.engage.sdk.core.data

import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SyncDocument
import io.engage.sdk.core.domain.SyncResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteSyncStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = SqliteSyncStore(context)

    @After
    fun close() {
        store.close()
        context.deleteDatabase("engage_sync.db")
    }

    @Test
    fun `new binding generation atomically replaces previous documents`() = runTest {
        store.apply(setOf(SdkModule.FEATURE_FLAGS), response(generation = 2, key = "old"))
        store.apply(setOf(SdkModule.FEATURE_FLAGS), response(generation = 3, key = "new"))

        assertEquals(3, store.snapshot.value.generation)
        assertEquals(listOf("new"), store.snapshot.value.documents.map(SyncDocument::key))
    }

    private fun response(generation: Long, key: String) = SyncResponse(
        cursor = "cursor-$generation",
        generation = generation,
        revision = generation,
        fullSnapshot = true,
        documents = listOf(
            SyncDocument(
                module = SdkModule.FEATURE_FLAGS,
                key = key,
                revision = generation,
                payload = buildJsonObject { put("revision", generation) },
            ),
        ),
        tombstones = emptyList(),
        serverTime = "2026-08-02T12:00:00Z",
        refreshAfterSeconds = 900,
    )
}

