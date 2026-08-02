package io.engage.sdk.core.application

import io.engage.sdk.Privacy
import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.ExposureStore
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.core.domain.RevocationStore
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.UUID
import kotlin.math.min

internal class DefaultPrivacy(
    private val endpoint: URI,
    private val sessions: SessionStore,
    private val outbox: OperationOutbox,
    private val syncStore: SyncStore,
    private val exposures: ExposureStore,
    private val revocations: RevocationStore,
    private val coordinator: OperationCoordinator,
    private val api: MobileEdgeApi,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onLocalDataWiped: suspend () -> Unit = {},
) : Privacy {
    private val mutex = Mutex()
    private var revocationJob: Job? = null

    override val state: StateFlow<PrivacyState> = sessions.privacy

    override suspend fun optOut(): Unit = mutex.withLock {
        if (state.value == PrivacyState.OPTED_OUT) return@withLock
        sessions.setPrivacy(PrivacyState.OPTED_OUT)
        coordinator.enqueue(
            type = OperationType.PRIVACY_STATE_SET,
            payload = buildJsonObject { put("state", "OPTED_OUT") },
            allowWhileOptedOut = true,
        )
        scope.launch { coordinator.flush() }
    }

    override suspend fun optIn(): Unit = mutex.withLock {
        coordinator.resumeAfterWipe()
        if (state.value != PrivacyState.OPTED_IN) sessions.setPrivacy(PrivacyState.OPTED_IN)
        scope.launch {
            coordinator.ensureInstallation()
            coordinator.enqueue(
                OperationType.PRIVACY_STATE_SET,
                buildJsonObject { put("state", "OPTED_IN") },
            )
            coordinator.flush()
        }
        replayPendingRevocation()
    }

    override suspend fun optOutAndWipe(): Unit = mutex.withLock {
        val session = coordinator.prepareWipe()
        if (session != null) {
            revocations.save(RevocationEnvelope(newId(), session.revocationCredential))
        }
        sessions.setPrivacy(PrivacyState.OPTED_OUT)
        outbox.clear()
        syncStore.clear()
        exposures.clear()
        sessions.clearSession()
        onLocalDataWiped()
        replayPendingRevocation()
    }

    fun replayPendingRevocation() {
        if (revocationJob?.isActive == true) return
        revocationJob = scope.launch {
            var backoffMillis = INITIAL_BACKOFF_MILLIS
            while (isActive) {
                val envelope = revocations.get() ?: break
                val completed = runCatching {
                    api.revoke(endpoint, envelope.credential, envelope.operationId)
                }.isSuccess
                if (completed) {
                    revocations.clear(envelope.operationId)
                    backoffMillis = INITIAL_BACKOFF_MILLIS
                    continue
                }
                delay(backoffMillis)
                backoffMillis = min(backoffMillis * 2, MAX_BACKOFF_MILLIS)
            }
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 15 * 60 * 1_000L
    }
}
