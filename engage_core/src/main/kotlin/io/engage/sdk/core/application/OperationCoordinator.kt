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
) {
    private val bootstrapMutex = Mutex()
    private val flushMutex = Mutex()

    suspend fun ensureInstallation() = bootstrapMutex.withLock {
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

    suspend fun enqueue(
        type: OperationType,
        payload: JsonObject,
        allowWhileOptedOut: Boolean = false,
    ): Boolean {
        if (!allowWhileOptedOut && sessions.privacy.value == PrivacyState.OPTED_OUT) return false
        val session = ensureInstallation()
        outbox.enqueue(
            SdkOperation(
                operationId = newId(),
                generation = session.generation,
                type = type,
                occurredAt = Instant.now(clock).toString(),
                payload = payload,
            ),
        )
        return true
    }

    suspend fun flush() = flushMutex.withLock {
        val session = ensureInstallation()
        while (true) {
            val reserved = outbox.reserve(MAX_BATCH_SIZE) ?: break
            val response = api.sendOperations(
                endpoint = endpoint,
                credential = session.credential,
                batch = OperationBatchRequest(reserved.batchId, reserved.operations),
            )
            check(response.batchId == reserved.batchId) { "Mobile edge returned another batchId" }
            if (!outbox.settle(reserved.batchId, response.results)) break
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

