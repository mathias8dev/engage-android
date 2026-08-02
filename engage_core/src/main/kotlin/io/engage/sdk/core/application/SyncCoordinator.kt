package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
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
        val remote = reconcileLocked()
        synchronizeLocked(modules, remote)
    }

    suspend fun reconcile(): InstallationStateResponse = mutex.withLock {
        reconcileLocked()
    }

    suspend fun synchronize(modules: Set<SdkModule>, remote: InstallationStateResponse) = mutex.withLock {
        synchronizeLocked(modules, remote)
    }

    private suspend fun reconcileLocked(): InstallationStateResponse {
        check(sessions.privacy.value == PrivacyState.OPTED_IN) { "Engage is opted out" }
        val session = checkNotNull(sessions.session.value) { "Engage installation is unavailable" }
        val remote = api.getInstallation(endpoint, session.credential)
        val identityBoundaryChanged =
            remote.generation != session.generation || remote.privacy != session.privacy
        if (identityBoundaryChanged) store.clear()
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
        if (remote.privacy == PrivacyState.OPTED_OUT || modules.isEmpty()) return
        val session = sessions.session.value ?: return
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
        store.apply(modules, response)
    }
}
