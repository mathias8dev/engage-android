package io.engage.sdk.messagecenter.divkit.data

import io.engage.sdk.InboxEntryId
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RenderingRepository(
    private val store: RenderingStore,
    private val generation: StateFlow<Long>,
    private val support: () -> MessageCenterRenderingSupport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val resolveMutex = Mutex()

    suspend fun activateGeneration(value: Long) = withContext(ioDispatcher) {
        store.activateGeneration(value)
    }

    suspend fun clear() = withContext(ioDispatcher) { store.clear() }

    suspend fun cached(entryIds: Collection<InboxEntryId>): Map<InboxEntryId, RenderingResolution> {
        val currentGeneration = generation.value
        return withContext(ioDispatcher) {
            store.activateGeneration(currentGeneration)
            store.read(currentGeneration, entryIds)
        }
    }

    suspend fun resolve(entryIds: Collection<InboxEntryId>): Map<InboxEntryId, RenderingResolution> =
        resolveMutex.withLock {
            val requested = entryIds.distinct()
            if (requested.isEmpty()) return@withLock emptyMap()
            val currentGeneration = generation.value
            val existing = withContext(ioDispatcher) {
                store.activateGeneration(currentGeneration)
                store.read(currentGeneration, requested)
            }.toMutableMap()
            val missing = requested.filterNot(existing::containsKey)

            missing.chunked(MAX_BATCH_SIZE).forEach { batch ->
                val snapshots = support().resolveRenderings(batch)
                if (generation.value != currentGeneration) throw RenderingGenerationChangedException()
                val byId = snapshots.associateBy { snapshot -> snapshot.entryId }
                val resolved = batch.map { entryId ->
                    val snapshot = byId[entryId]
                    if (snapshot == null) {
                        RenderingResolution.Unavailable(entryId)
                    } else {
                        require(snapshot.renderer == DIVKIT_RENDERER) {
                            "Unsupported Inbox renderer ${snapshot.renderer}"
                        }
                        require(snapshot.revision > 0) { "Inbox rendering revision must be positive" }
                        RenderingResolution.Available(snapshot)
                    }
                }
                val stored = withContext(ioDispatcher) { store.write(currentGeneration, resolved) }
                if (!stored || generation.value != currentGeneration) throw RenderingGenerationChangedException()
                resolved.forEach { resolution -> existing[resolution.entryId] = resolution }
            }
            existing
        }

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val DIVKIT_RENDERER = "DIVKIT"
    }
}

internal class RenderingGenerationChangedException : IllegalStateException("Inbox generation changed")
