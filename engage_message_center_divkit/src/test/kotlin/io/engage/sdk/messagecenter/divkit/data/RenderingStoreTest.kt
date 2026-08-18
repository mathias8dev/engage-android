package io.engage.sdk.messagecenter.divkit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSnapshot
import io.engage.sdk.InboxRenderer
import io.engage.sdk.InboxRenderingSurface
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RenderingStoreTest {
    private lateinit var context: Context
    private lateinit var store: RenderingStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("engage_message_center_divkit.db")
        store = RenderingStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("engage_message_center_divkit.db")
    }

    @Test
    fun `available and unavailable results are cached by generation`() {
        val availableId = InboxEntryId("entry-1")
        val unavailableId = InboxEntryId("entry-2")
        store.activateGeneration(7)

        assertTrue(
            store.write(
                7,
                listOf(
                    RenderingResolution.Available(
                        InboxRenderingSnapshot(
                            availableId,
                            InboxRenderer.DIVKIT,
                            3,
                            InboxRenderingSurface.entries.associateWith { surface ->
                                buildJsonObject { put("surface", surface.name) }
                            },
                        ),
                    ),
                    RenderingResolution.Unavailable(unavailableId),
                ),
            ),
        )

        val cached = store.read(7, listOf(availableId, unavailableId))
        val snapshot = (cached[availableId] as RenderingResolution.Available).snapshot
        assertEquals(3, snapshot.revision)
        assertEquals(
            InboxRenderingSurface.SUMMARY.name,
            snapshot.surfaces.getValue(InboxRenderingSurface.SUMMARY)["surface"]?.jsonPrimitive?.content,
        )
        assertEquals(
            InboxRenderingSurface.DETAIL.name,
            snapshot.surfaces.getValue(InboxRenderingSurface.DETAIL)["surface"]?.jsonPrimitive?.content,
        )
        assertTrue(cached[unavailableId] is RenderingResolution.Unavailable)

        store.activateGeneration(8)
        assertTrue(store.read(7, listOf(availableId)).isEmpty())
        assertFalse(store.write(7, listOf(RenderingResolution.Unavailable(availableId))))
    }

    @Test
    fun `application storage scopes cannot read each others renderings`() {
        val firstName = "engage_message_center_divkit_first.db"
        val secondName = "engage_message_center_divkit_second.db"
        val first = RenderingStore(context, databaseName = firstName)
        val second = RenderingStore(context, databaseName = secondName)
        val entryId = InboxEntryId("tenant-one")
        try {
            first.activateGeneration(7)
            assertTrue(first.write(7, listOf(RenderingResolution.Unavailable(entryId))))
            second.activateGeneration(7)

            assertTrue(first.read(7, listOf(entryId)).containsKey(entryId))
            assertTrue(second.read(7, listOf(entryId)).isEmpty())
        } finally {
            first.close()
            second.close()
            context.deleteDatabase(firstName)
            context.deleteDatabase(secondName)
        }
    }
}
