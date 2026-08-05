package io.engage.sdk.core.application

import io.engage.sdk.PrivacyState
import io.engage.sdk.EngageLogger
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    private val onBindingCodeIssued: (generation: Long, expiresAt: String) -> Unit = { _, _ -> },
) {
    private val bootstrapMutex = Mutex()
    private val flushMutex = Mutex()
    // A persisted session means the installation still exists remotely, even when privacy is
    // opted out. Only a wiped installation (no session + opted out marker) starts disabled.
    private var installationEnabled =
        sessions.session.value != null || sessions.privacy.value == PrivacyState.OPTED_IN
    @Volatile private var pendingBinding: PendingBinding? = null

    suspend fun ensureInstallation(allowWhileOptedOut: Boolean = false) = bootstrapMutex.withLock {
        EngageLogger.debug(
            "Installation",
            "ensure requested allowWhileOptedOut=$allowWhileOptedOut hasSession=${sessions.session.value != null}",
        )
        check(installationEnabled) { "Engage installation was wiped; call privacy.optIn() first" }
        check(allowWhileOptedOut || sessions.privacy.value == PrivacyState.OPTED_IN) {
            "Engage is opted out"
        }
        sessions.session.value?.also { session ->
            EngageLogger.verbose(
                "Installation",
                "existing session installationId=${session.installationId} generation=${session.generation}",
            )
        } ?: run {
            val recoveryToken = sessions.recoveryToken()
            EngageLogger.info("Installation", "bootstrap started recovery=${recoveryToken != null}")
            api.bootstrap(
                endpoint = endpoint,
                appKey = appKey,
                request = metadata.toBootstrapRequest(recoveryToken),
            ).also { session ->
                sessions.saveSession(session)
                EngageLogger.info(
                    "Installation",
                    "bootstrap completed installationId=${session.installationId} generation=${session.generation}",
                )
            }
        }
    }

    suspend fun issueBindingCode(): String {
        val session = ensureInstallation()
        EngageLogger.debug(
            "Installation",
            "binding request started installationId=${session.installationId} generation=${session.generation}",
        )
        val response = api.issueBindingCode(endpoint, session.credential)
        pendingBinding = PendingBinding(session.generation, parseExpiration(response.expiresAt))
        onBindingCodeIssued(session.generation, response.expiresAt)
        EngageLogger.info(
            "Installation",
            "binding request completed generation=${session.generation} expiresAt=${response.expiresAt}",
        )
        return response.code
    }

    suspend fun prepareWipe() = bootstrapMutex.withLock {
        EngageLogger.warn("Installation", "installation disabled for wipe")
        installationEnabled = false
        sessions.session.value
    }

    suspend fun resumeAfterWipe() = bootstrapMutex.withLock {
        installationEnabled = true
        EngageLogger.info("Installation", "installation enabled after opt-in")
    }

    suspend fun enqueue(
        type: OperationType,
        payload: JsonObject,
        allowWhileOptedOut: Boolean = false,
        operationId: String = newId(),
    ): Boolean {
        EngageLogger.verbose(
            "Outbox",
            "enqueue requested operationId=$operationId type=${type.name} payloadKeys=${payload.keys.sorted()} " +
                "allowWhileOptedOut=$allowWhileOptedOut",
        )
        awaitBindingIfProfileScoped(type)
        return bootstrapMutex.withLock {
            check(installationEnabled) { "Engage installation was wiped; call privacy.optIn() first" }
            if (!allowWhileOptedOut && sessions.privacy.value == PrivacyState.OPTED_OUT) {
                EngageLogger.debug("Outbox", "enqueue rejected operationId=$operationId reason=privacy_opted_out")
                return@withLock false
            }
            val generation = sessions.session.value?.generation ?: INITIAL_GENERATION
            outbox.enqueue(
                SdkOperation(
                    operationId = operationId,
                    // Generation zero is the backend's initial anonymous generation. This lets the
                    // durable outbox accept work before the first network bootstrap.
                    generation = generation,
                    type = type,
                    occurredAt = Instant.now(clock).toString(),
                    payload = payload,
                ),
            )
            onEnqueued()
            EngageLogger.debug(
                "Outbox",
                "enqueue accepted operationId=$operationId type=${type.name} generation=$generation",
            )
            true
        }
    }

    private suspend fun awaitBindingIfProfileScoped(type: OperationType) {
        if (type !in PROFILE_SCOPED_TYPES) return
        val pending = pendingBinding ?: return
        EngageLogger.debug(
            "Installation",
            "profile operation waiting for binding transition type=${type.name} generation=${pending.initialGeneration}",
        )
        val remainingMillis = java.time.Duration.between(Instant.now(clock), pending.expiresAt).toMillis()
        if (remainingMillis <= 0) {
            pendingBinding = null
            EngageLogger.debug("Installation", "binding wait expired type=${type.name}")
            return
        }
        withTimeoutOrNull(remainingMillis) {
            sessions.session.first { session -> session == null || session.generation != pending.initialGeneration }
        }
        if (sessions.session.value?.generation != pending.initialGeneration) pendingBinding = null
        EngageLogger.debug(
            "Installation",
            "binding wait finished type=${type.name} activeGeneration=${sessions.session.value?.generation}",
        )
    }

    private fun parseExpiration(value: String): Instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: Instant.now(clock).plusSeconds(DEFAULT_BINDING_TTL_SECONDS)

    suspend fun flush() = flushMutex.withLock {
        EngageLogger.info("Outbox", "flush started pending=${outbox.pending.value.size}")
        val session = sessions.session.value
            ?: ensureInstallation(allowWhileOptedOut = sessions.privacy.value == PrivacyState.OPTED_OUT)
        while (true) {
            val allowedTypes = if (sessions.privacy.value == PrivacyState.OPTED_OUT) {
                setOf(OperationType.PRIVACY_STATE_SET)
            } else {
                null
            }
            val reserved = outbox.reserve(MAX_BATCH_SIZE, allowedTypes) ?: break
            EngageLogger.debug(
                "Outbox",
                "batch reserved batchId=${reserved.batchId} count=${reserved.operations.size} " +
                    "types=${reserved.operations.map { it.type.name }.distinct().sorted()}",
            )
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
            if (!outbox.settle(reserved.batchId, response.results)) {
                EngageLogger.warn("Outbox", "batch settlement made no progress batchId=${reserved.batchId}")
                break
            }
            EngageLogger.info(
                "Outbox",
                "batch settled batchId=${reserved.batchId} count=${response.results.size}",
            )
        }
        EngageLogger.info("Outbox", "flush finished pending=${outbox.pending.value.size}")
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val INITIAL_GENERATION = 0L
        const val DEFAULT_BINDING_TTL_SECONDS = 5 * 60L
        val PROFILE_SCOPED_TYPES = setOf(
            OperationType.PROFILE_ATTRIBUTES_EDITED,
            OperationType.PROFILE_TAGS_EDITED,
            OperationType.PROFILE_SUBSCRIPTIONS_EDITED,
        )
    }

    private data class PendingBinding(val initialGeneration: Long, val expiresAt: Instant)
}
