package io.engage.sdk.messagecenter.divkit.render

import io.engage.sdk.InboxEntryId
import io.engage.sdk.MessageCenterPresentationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailLifecycleContractTest {
    private val entryId = InboxEntryId("entry-1")

    @Test
    fun `identity transition invalidates an already requested detail`() {
        assertTrue(shouldInvalidateMessageCenterDetail(entryId, 4, state(revision = 5), false, false))
    }

    @Test
    fun `known entry removal invalidates its rendered detail`() {
        assertTrue(shouldInvalidateMessageCenterDetail(entryId, 4, state(entryIds = emptySet()), true, true))
    }

    @Test
    fun `cold direct detail may resolve before the entry is paged`() {
        assertFalse(shouldInvalidateMessageCenterDetail(entryId, 4, state(entryIds = emptySet()), false, false))
    }

    @Test
    fun `pending deletion invalidates a cold direct detail`() {
        assertTrue(
            shouldInvalidateMessageCenterDetail(
                entryId,
                4,
                state(entryIds = emptySet(), deletedEntryIds = setOf(entryId)),
                false,
                true,
            ),
        )
    }

    @Test
    fun `privacy disable invalidates content only after it has rendered`() {
        assertFalse(shouldInvalidateMessageCenterDetail(entryId, 4, state(enabled = false), false, false))
        assertTrue(shouldInvalidateMessageCenterDetail(entryId, 4, state(enabled = false), false, true))
    }

    private fun state(
        revision: Long = 4,
        enabled: Boolean = true,
        entryIds: Set<InboxEntryId> = setOf(entryId),
        deletedEntryIds: Set<InboxEntryId> = emptySet(),
    ) = MessageCenterPresentationState(revision, 7, enabled, entryIds, deletedEntryIds)
}
