package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.DeviceMetadata
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationBatchRequest
import io.engage.sdk.core.domain.OperationOutbox
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkOperation
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.toBootstrapRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class OperationCoordinator(
    private val endpoint: URI,
    private val appKey: String,
    private val metadata: DeviceMetadata,
    private val sessions: SessionStore,
    private val outbox: OperationOutbox,
    private val api: MobileEdgeApi,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onEnqueued: () -> Unit = {},
) {
    private val bootstrapMutex = Mutex()
    private val flushMutex = Mutex()
    private var installationEnabled = sessions.privacy.value == PrivacyState.OPTED_IN

    suspend fun ensureInstallation(allowWhileOptedOut: Boolean = false) = bootstrapMutex.withLock {
        check(installationEnabled) { "Engage installation was wiped; call privacy.optIn() first" }
        check(allowWhileOptedOut || sessions.privacy.value == PrivacyState.OPTED_IN) {
            "Engage is opted out"
        }
        sessions.session.value ?: api.bootstrap(
            endpoint = endpoint,
            appKey = appKey,
            request = metadata.toBootstrapRequest(sessions.recoveryToken()),
        ).also { session -> sessions.saveSession(session) }
    }

    suspend fun issueBindingCode(): String {
        val session = ensureInstallation()
        return api.issueBindingCode(endpoint, session.credential).code
    }

    suspend fun prepareWipe() = bootstrapMutex.withLock {
        installationEnabled = false
        sessions.session.value
    }

    suspend fun resumeAfterWipe() = bootstrapMutex.withLock {
        installationEnabled = true
    }

    suspend fun enqueue(
        type: OperationType,
        payload: JsonObject,
        allowWhileOptedOut: Boolean = false,
        operationId: String = newId(),
    ): Boolean {
        if (!allowWhileOptedOut && sessions.privacy.value == PrivacyState.OPTED_OUT) return false
        val session = ensureInstallation(allowWhileOptedOut)
        outbox.enqueue(
            SdkOperation(
                operationId = operationId,
                generation = session.generation,
                type = type,
                occurredAt = Instant.now(clock).toString(),
                payload = payload,
            ),
        )
        onEnqueued()
        return true
    }

    suspend fun flush() = flushMutex.withLock {
        val session = sessions.session.value
            ?: ensureInstallation(allowWhileOptedOut = sessions.privacy.value == PrivacyState.OPTED_OUT)
        while (true) {
            val allowedTypes = if (sessions.privacy.value == PrivacyState.OPTED_OUT) {
                setOf(OperationType.PRIVACY_STATE_SET)
            } else {
                null
            }
            val reserved = outbox.reserve(MAX_BATCH_SIZE, allowedTypes) ?: break
            val response = api.sendOperations(
                endpoint = endpoint,
                credential = session.credential,
                batch = OperationBatchRequest(reserved.batchId, reserved.operations),
            )
            check(response.batchId == reserved.batchId) { "Mobile edge returned another batchId" }
            val expectedOperationIds = reserved.operations.map(SdkOperation::operationId).toSet()
            val returnedOperationIds = response.results.map { it.operationId }
            check(
                returnedOperationIds.size == returnedOperationIds.toSet().size &&
                    returnedOperationIds.toSet() == expectedOperationIds,
            ) { "Mobile edge returned incomplete or duplicate operation results" }
            if (!outbox.settle(reserved.batchId, response.results)) break
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}
