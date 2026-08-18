package io.engage.sdk.messagecenter.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.InboxSortOrder
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

        store.activateGeneration(8)
        store.savePage(8, 20, null, page(entry("new", read = false)))

        assertEquals(setOf("new"), store.snapshot.value.entries.keys)
        assertEquals(listOf("new"), store.cachedWindow(8, 20).entryIds)
        assertTrue(store.cachedWindow(7, 20).entryIds.isEmpty())
    }

    @Test
    fun `cached windows are isolated by sort order`() = runTest {
        store.activateGeneration(7)
        store.savePage(
            7,
            20,
            null,
            page(entry("newest", read = false)),
            InboxSortOrder.NEWEST_FIRST,
        )
        store.savePage(
            7,
            20,
            null,
            page(entry("oldest", read = false)),
            InboxSortOrder.OLDEST_FIRST,
        )

        assertEquals(
            listOf("newest"),
            store.cachedWindow(7, 20, InboxSortOrder.NEWEST_FIRST).entryIds,
        )
        assertEquals(
            listOf("oldest"),
            store.cachedWindow(7, 20, InboxSortOrder.OLDEST_FIRST).entryIds,
        )
    }

    @Test
    fun `stale network writes cannot resurrect data after a generation transition`() = runTest {
        store.activateGeneration(7)
        assertTrue(store.savePage(7, 20, null, page(entry("old", read = false))))
        assertTrue(store.enqueue(
            PendingMutation("read-old", 7, MutationType.MARK_READ, "old", Instant.EPOCH, null),
        ))
        val oldBatch = requireNotNull(store.reserve(7))

        store.activateGeneration(8)

        assertFalse(store.savePage(7, 20, null, page(entry("late-old", read = false))))
        assertFalse(store.enqueue(
            PendingMutation("late-read", 7, MutationType.MARK_READ, "late-old", Instant.EPOCH, null),
        ))
        store.settle(oldBatch, listOf(MutationResult("read-old", MutationStatus.ACCEPTED, null, null)))

        store.activateGeneration(7)
        assertTrue(store.snapshot.value.entries.isEmpty())
        assertTrue(store.cachedWindow(7, 20).entryIds.isEmpty())
        assertEquals(null, store.reserve(7))
    }

    @Test
    fun `stale network writes cannot repopulate an inbox after wipe`() = runTest {
        store.activateGeneration(7)
        store.clear()

        assertFalse(store.savePage(7, 20, null, page(entry("late-old", read = false))))
        assertFalse(store.enqueue(
            PendingMutation("late-read", 7, MutationType.MARK_READ, "late-old", Instant.EPOCH, null),
        ))

        store.activateGeneration(7)
        assertTrue(store.snapshot.value.entries.isEmpty())
        assertEquals(null, store.reserve(7))
    }

    @Test
    fun `uncached pending deletion is exposed to presentation consumers`() = runTest {
        store.activateGeneration(7)

        assertTrue(store.enqueue(
            PendingMutation("delete-direct", 7, MutationType.DELETE, "direct-entry", Instant.EPOCH, null),
        ))

        assertEquals(setOf("direct-entry"), store.snapshot.value.pendingDeletedEntryIds)
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
