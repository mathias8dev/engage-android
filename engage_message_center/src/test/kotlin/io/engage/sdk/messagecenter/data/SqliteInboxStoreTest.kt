package io.engage.sdk.messagecenter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.messagecenter.domain.InboxScope
import io.engage.sdk.messagecenter.domain.MutationResult
import io.engage.sdk.messagecenter.domain.MutationStatus
import io.engage.sdk.messagecenter.domain.MutationType
import io.engage.sdk.messagecenter.domain.PendingMutation
import io.engage.sdk.messagecenter.domain.RemoteInboxEntry
import io.engage.sdk.messagecenter.domain.RemoteInboxPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SqliteInboxStoreTest {
    private lateinit var context: Context
    private lateinit var store: SqliteInboxStore
    private var batchSequence = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("engage_message_center.db")
        store = SqliteInboxStore(context, newId = { "batch-${++batchSequence}" })
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("engage_message_center.db")
    }

    @Test
    fun `optimistic mutation rolls back when the server rejects it`() = runTest {
        store.activateGeneration(7)
        store.savePage(7, 20, null, page(entry("one", read = false), entry("two", read = true)))
        val mutation = PendingMutation(
            "operation-1",
            7,
            MutationType.MARK_READ,
            "one",
            Instant.parse("2026-08-02T12:00:00Z"),
            null,
        )

        store.enqueue(mutation)
        assertNotNull(store.snapshot.value.entries.getValue("one").readAt)
        assertEquals(0, store.snapshot.value.unreadCount)
        val batch = requireNotNull(store.reserve(7))
        assertEquals(batch, store.reserve(7))

        val rejected = store.settle(
            batch,
            listOf(MutationResult("operation-1", MutationStatus.REJECTED, "not_allowed", "No")),
        )

        assertEquals(1, rejected.size)
        assertEquals(null, store.snapshot.value.entries.getValue("one").readAt)
        assertEquals(1, store.snapshot.value.unreadCount)
    }

    @Test
    fun `pages and projections are isolated by generation`() = runTest {
        store.activateGeneration(7)
        store.savePage(7, 20, null, page(entry("old", read = false)))
        store.enqueue(
            PendingMutation("delete-1", 7, MutationType.DELETE, "old", Instant.EPOCH, null),
        )
        assertFalse(store.snapshot.value.entries.containsKey("old"))
        val batch = requireNotNull(store.reserve(7))
        store.settle(batch, listOf(MutationResult("delete-1", MutationStatus.ACCEPTED, null, null)))

        store.savePage(8, 20, null, page(entry("new", read = false)))
        store.activateGeneration(8)

        assertEquals(setOf("new"), store.snapshot.value.entries.keys)
        assertEquals(listOf("new"), store.cachedWindow(8, 20).entryIds)
        assertTrue(store.cachedWindow(7, 20).entryIds.isEmpty())
    }

    @Test
    fun `application storage scopes cannot read each others inbox`() = runTest {
        val first = SqliteInboxStore(context, databaseName = "engage_message_center_first.db")
        val second = SqliteInboxStore(context, databaseName = "engage_message_center_second.db")
        try {
            first.activateGeneration(7)
            first.savePage(7, 20, null, page(entry("tenant-one", read = false)))
            second.activateGeneration(7)

            assertEquals(setOf("tenant-one"), first.snapshot.value.entries.keys)
            assertTrue(second.snapshot.value.entries.isEmpty())
        } finally {
            first.close()
            second.close()
            context.deleteDatabase("engage_message_center_first.db")
            context.deleteDatabase("engage_message_center_second.db")
        }
    }

    private fun page(vararg entries: RemoteInboxEntry) = RemoteInboxPage(
        entries.toList(),
        nextCursor = null,
        hasMore = false,
        unreadCount = entries.count { it.readAt == null },
    )

    private fun entry(id: String, read: Boolean) = RemoteInboxEntry(
        id,
        "test.entry",
        buildJsonObject {},
        InboxScope.PROFILE,
        Instant.parse("2026-08-02T10:00:00Z"),
        null,
        Instant.parse("2026-08-02T11:00:00Z").takeIf { read },
    )
}
