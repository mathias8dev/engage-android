package io.engage.sdk.core.application

import io.engage.sdk.Privacy
import io.engage.sdk.PrivacyState
import io.engage.sdk.EngageLogger
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
        EngageLogger.info("Privacy", "optOut requested current=${state.value}")
        if (state.value == PrivacyState.OPTED_OUT) {
            EngageLogger.debug("Privacy", "optOut ignored because state is already OPTED_OUT")
            return@withLock
        }
        sessions.setPrivacy(PrivacyState.OPTED_OUT)
        coordinator.enqueue(
            type = OperationType.PRIVACY_STATE_SET,
            payload = buildJsonObject { put("state", "OPTED_OUT") },
            allowWhileOptedOut = true,
        )
        scope.launch { coordinator.flush() }
        EngageLogger.info("Privacy", "local state changed state=OPTED_OUT wipe=false")
    }

    override suspend fun optIn(): Unit = mutex.withLock {
        EngageLogger.info("Privacy", "optIn requested current=${state.value}")
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
        EngageLogger.info("Privacy", "local state changed state=OPTED_IN")
    }

    override suspend fun optOutAndWipe(): Unit = mutex.withLock {
        EngageLogger.warn("Privacy", "optOutAndWipe requested")
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
        EngageLogger.warn("Privacy", "local Engage data wiped revocationPending=${session != null}")
    }

    fun replayPendingRevocation() {
        if (revocationJob?.isActive == true) {
            EngageLogger.verbose("Privacy", "revocation replay already active")
            return
        }
        revocationJob = scope.launch {
            var backoffMillis = INITIAL_BACKOFF_MILLIS
            while (isActive) {
                val envelope = revocations.get() ?: break
                EngageLogger.debug("Privacy", "remote revocation attempt operationId=${envelope.operationId}")
                val completed = runCatching {
                    api.revoke(endpoint, envelope.credential, envelope.operationId)
                }.onFailure { error ->
                    EngageLogger.warn("Privacy", "remote revocation failed operationId=${envelope.operationId}", error)
                }.isSuccess
                if (completed) {
                    revocations.clear(envelope.operationId)
                    EngageLogger.info("Privacy", "remote revocation confirmed operationId=${envelope.operationId}")
                    backoffMillis = INITIAL_BACKOFF_MILLIS
                    continue
                }
                delay(backoffMillis)
                EngageLogger.debug("Privacy", "remote revocation retry scheduled delayMillis=$backoffMillis")
                backoffMillis = min(backoffMillis * 2, MAX_BACKOFF_MILLIS)
            }
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 15 * 60 * 1_000L
    }
}
