package io.engage.sdk.messagecenter.divkit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSnapshot
import io.engage.sdk.InboxRenderer
import io.engage.sdk.InboxRenderingSurface
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RenderingRepositoryTest {
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
    fun `resolution batches missing ids and persists negative results`() = runTest {
        val generation = MutableStateFlow(7L)
        val support = FakeRenderingSupport()
        val repository = RenderingRepository(store, generation, { support }, Dispatchers.Unconfined)
        val first = InboxEntryId("entry-1")
        val second = InboxEntryId("entry-2")

        val resolved = repository.resolve(listOf(first, second))
        val cached = repository.resolve(listOf(first, second))

        assertTrue(resolved[first] is RenderingResolution.Available)
        assertTrue(resolved[second] is RenderingResolution.Unavailable)
        assertEquals(resolved, cached)
        assertEquals(1, support.calls)
    }

    @Test
    fun `a generation change cannot repopulate the previous recipient cache`() {
        assertThrows(RenderingGenerationChangedException::class.java) {
            runTest {
                val generation = MutableStateFlow(7L)
                val support = FakeRenderingSupport { generation.value = 8L }
                val repository = RenderingRepository(store, generation, { support }, Dispatchers.Unconfined)

                repository.resolve(listOf(InboxEntryId("entry-1")))
            }
        }
    }
}

private class FakeRenderingSupport(
    private val afterResolution: () -> Unit = {},
) : MessageCenterRenderingSupport {
    var calls = 0

    override suspend fun resolveRenderings(entryIds: List<InboxEntryId>): List<InboxRenderingSnapshot> {
        calls += 1
        afterResolution()
        return entryIds.firstOrNull()?.let { entryId ->
            listOf(
                InboxRenderingSnapshot(
                    entryId,
                    InboxRenderer.DIVKIT,
                    1,
                    InboxRenderingSurface.entries.associateWith { buildJsonObject {} },
                ),
            )
        }.orEmpty()
    }

    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean = true
}
