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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class DirectDetailUnreadCountRegressionTest {
    private lateinit var context: Context
    private lateinit var store: SqliteInboxStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE)
        store = SqliteInboxStore(context, databaseName = DATABASE, newId = { "batch-direct" })
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DATABASE)
    }

    @Test
    fun `accepted mark read for uncached already-read entry preserves authoritative unread count`() = runTest {
        store.activateGeneration(7)
        store.savePage(
            generation = 7,
            pageSize = 20,
            cursor = null,
            page = RemoteInboxPage(
                entries = listOf(entry("known-unread")),
                nextCursor = null,
                hasMore = false,
                unreadCount = 1,
            ),
        )
        store.enqueue(
            PendingMutation(
                operationId = "read-direct",
                generation = 7,
                type = MutationType.MARK_READ,
                entryId = "uncached-already-read",
                occurredAt = Instant.parse("2026-08-18T12:00:00Z"),
                batchId = null,
            ),
        )

        val batch = requireNotNull(store.reserve(7))
        store.settle(
            batch,
            listOf(MutationResult("read-direct", MutationStatus.ACCEPTED, null, null)),
        )

        assertEquals(1, store.snapshot.value.unreadCount)
    }

    private fun entry(id: String) = RemoteInboxEntry(
        id = id,
        key = "test.entry",
        payload = buildJsonObject {},
        scope = InboxScope.PROFILE,
        sentAt = Instant.parse("2026-08-18T10:00:00Z"),
        expiresAt = null,
        readAt = null,
    )

    private companion object {
        const val DATABASE = "engage_message_center_review.db"
    }
}
