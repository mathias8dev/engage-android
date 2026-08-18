package io.engage.sdk.messagecenter.divkit.data

import io.engage.sdk.InboxEntryId
import io.engage.sdk.EngageLogger
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.InboxRenderer
import io.engage.sdk.InboxRenderingSurface
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
        EngageLogger.debug("MessageCenter.Rendering", "generation activating generation=$value")
        store.activateGeneration(value)
    }

    suspend fun clear() = withContext(ioDispatcher) {
        EngageLogger.warn("MessageCenter.Rendering", "cache clear requested")
        store.clear()
    }

    suspend fun cached(entryIds: Collection<InboxEntryId>): Map<InboxEntryId, RenderingResolution> {
        val currentGeneration = generation.value
        EngageLogger.debug(
            "MessageCenter.Rendering",
            "cache lookup generation=$currentGeneration count=${entryIds.size}",
        )
        return withContext(ioDispatcher) {
            store.activateGeneration(currentGeneration)
            store.read(currentGeneration, entryIds)
        }.also { cached ->
            EngageLogger.debug("MessageCenter.Rendering", "cache lookup completed hits=${cached.size}")
        }
    }

    suspend fun resolve(entryIds: Collection<InboxEntryId>): Map<InboxEntryId, RenderingResolution> =
        resolveMutex.withLock {
            val requested = entryIds.distinct()
            if (requested.isEmpty()) {
                EngageLogger.verbose("MessageCenter.Rendering", "resolution skipped reason=no_entries")
                return@withLock emptyMap()
            }
            val currentGeneration = generation.value
            EngageLogger.debug(
                "MessageCenter.Rendering",
                "resolution started generation=$currentGeneration requested=${requested.size}",
            )
            val existing = withContext(ioDispatcher) {
                store.activateGeneration(currentGeneration)
                store.read(currentGeneration, requested)
            }.toMutableMap()
            val missing = requested.filterNot(existing::containsKey)
            EngageLogger.debug(
                "MessageCenter.Rendering",
                "resolution cache result hits=${existing.size} misses=${missing.size}",
            )

            missing.chunked(MAX_BATCH_SIZE).forEach { batch ->
                EngageLogger.debug("MessageCenter.Rendering", "remote batch resolving count=${batch.size}")
                val snapshots = support().resolveRenderings(batch)
                if (generation.value != currentGeneration) {
                    EngageLogger.warn("MessageCenter.Rendering", "remote batch discarded reason=generation_changed")
                    throw RenderingGenerationChangedException()
                }
                val byId = snapshots.associateBy { snapshot -> snapshot.entryId }
                val resolved = batch.map { entryId ->
                    val snapshot = byId[entryId]
                    if (snapshot == null) {
                        RenderingResolution.Unavailable(entryId)
                    } else {
                        require(snapshot.renderer == InboxRenderer.DIVKIT) {
                            "Unsupported Inbox renderer ${snapshot.renderer}"
                        }
                        require(snapshot.revision > 0) { "Inbox rendering revision must be positive" }
                        require(InboxRenderingSurface.entries.all(snapshot.surfaces::containsKey)) {
                            "Inbox rendering must contain Summary and Detail surfaces"
                        }
                        RenderingResolution.Available(snapshot)
                    }
                }
                val stored = withContext(ioDispatcher) { store.write(currentGeneration, resolved) }
                if (!stored || generation.value != currentGeneration) {
                    EngageLogger.warn("MessageCenter.Rendering", "resolved batch discarded reason=generation_changed")
                    throw RenderingGenerationChangedException()
                }
                resolved.forEach { resolution -> existing[resolution.entryId] = resolution }
                EngageLogger.info(
                    "MessageCenter.Rendering",
                    "remote batch resolved available=${resolved.count { it is RenderingResolution.Available }} " +
                        "unavailable=${resolved.count { it is RenderingResolution.Unavailable }}",
                )
            }
            EngageLogger.info("MessageCenter.Rendering", "resolution completed total=${existing.size}")
            existing
        }

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

internal class RenderingGenerationChangedException : IllegalStateException("Inbox generation changed")
