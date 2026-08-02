package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.MobileEdgeApi
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
        if (modules.isEmpty() || sessions.privacy.value == PrivacyState.OPTED_OUT) return@withLock
        val session = sessions.session.value ?: return@withLock
        val remote = api.getInstallation(endpoint, session.credential)
        if (remote.generation != session.generation || remote.privacy != session.privacy) {
            sessions.saveSession(
                session.copy(
                    generation = remote.generation,
                    privacy = remote.privacy,
                    pushSubscription = remote.pushSubscription,
                    serverTime = remote.updatedAt,
                ),
            )
            store.clear()
        }
        if (remote.privacy == PrivacyState.OPTED_OUT) return@withLock
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

