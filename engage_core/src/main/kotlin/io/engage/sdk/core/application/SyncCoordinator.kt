package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.InstallationStateResponse
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

internal class SyncCoordinator(
    private val endpoint: URI,
    private val sessions: SessionStore,
    private val store: SyncStore,
    private val api: MobileEdgeApi,
) {
    private val mutex = Mutex()

    suspend fun refresh(modules: Set<SdkModule>) = mutex.withLock {
        EngageLogger.debug("Sync", "refresh requested modules=${modules.sortedBy { it.name }}")
        val remote = reconcileLocked()
        synchronizeLocked(modules, remote)
    }

    suspend fun reconcile(): InstallationStateResponse = mutex.withLock {
        EngageLogger.verbose("Sync", "reconcile requested")
        reconcileLocked()
    }

    suspend fun synchronize(modules: Set<SdkModule>, remote: InstallationStateResponse) = mutex.withLock {
        EngageLogger.verbose("Sync", "synchronize requested modules=${modules.sortedBy { it.name }}")
        synchronizeLocked(modules, remote)
    }

    private suspend fun reconcileLocked(): InstallationStateResponse {
        check(sessions.privacy.value == PrivacyState.OPTED_IN) { "Engage is opted out" }
        val session = checkNotNull(sessions.session.value) { "Engage installation is unavailable" }
        val remote = api.getInstallation(endpoint, session.credential)
        EngageLogger.info(
            "Sync",
            "installation reconciled installationId=${session.installationId} localGeneration=${session.generation} " +
                "remoteGeneration=${remote.generation} privacy=${remote.privacy}",
        )
        val identityBoundaryChanged =
            remote.generation != session.generation || remote.privacy != session.privacy
        if (identityBoundaryChanged) {
            EngageLogger.warn("Sync", "identity boundary changed; clearing synchronized documents")
            store.clear()
        }
        if (
            identityBoundaryChanged ||
            remote.pushSubscription != session.pushSubscription ||
            remote.updatedAt != session.serverTime
        ) {
            sessions.saveSession(
                session.copy(
                    generation = remote.generation,
                    privacy = remote.privacy,
                    pushSubscription = remote.pushSubscription,
                    serverTime = remote.updatedAt,
                ),
            )
        }
        return remote
    }

    private suspend fun synchronizeLocked(modules: Set<SdkModule>, remote: InstallationStateResponse) {
        if (remote.privacy == PrivacyState.OPTED_OUT || modules.isEmpty()) {
            EngageLogger.debug(
                "Sync",
                "document sync skipped privacy=${remote.privacy} moduleCount=${modules.size}",
            )
            return
        }
        val session = sessions.session.value ?: run {
            EngageLogger.warn("Sync", "document sync skipped because installation session is unavailable")
            return
        }
        val current = store.snapshot.value
        val response = api.synchronize(
            endpoint,
            session.credential,
            SyncRequest(
                cursor = current.cursor.takeIf { current.generation == remote.generation },
                modules = modules,
            ),
        )
        check(response.generation == remote.generation) { "Received stale Engage sync generation" }
        EngageLogger.info(
            "Sync",
            "document sync received generation=${response.generation} revision=${response.revision} " +
                "documents=${response.documents.size} tombstones=${response.tombstones.size} " +
                "fullSnapshot=${response.fullSnapshot}",
        )
        store.apply(modules, response)
    }
}
