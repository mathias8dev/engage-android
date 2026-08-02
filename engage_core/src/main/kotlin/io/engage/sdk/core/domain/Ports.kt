package io.engage.sdk.core.domain

import io.engage.sdk.PrivacyState
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

internal interface SessionStore {
    val session: StateFlow<InstallationSession?>
    val privacy: StateFlow<PrivacyState>

    suspend fun recoveryToken(): String?
    suspend fun saveSession(session: InstallationSession)
    suspend fun setPrivacy(state: PrivacyState)
    suspend fun clearSession()
}

internal interface OperationOutbox {
    val pending: StateFlow<List<SdkOperation>>

    suspend fun enqueue(operation: SdkOperation)
    suspend fun reserve(limit: Int, allowedTypes: Set<OperationType>? = null): ReservedOperationBatch?

    /** Returns true when at least one queued operation was settled. */
    suspend fun settle(batchId: String, results: List<OperationResult>): Boolean

    suspend fun clear()
}

internal interface MobileEdgeApi {
    suspend fun bootstrap(
        endpoint: URI,
        appKey: String,
        request: BootstrapRequest,
    ): InstallationSession

    suspend fun issueBindingCode(
        endpoint: URI,
        credential: String,
    ): BindingCodeResponse

    suspend fun getInstallation(
        endpoint: URI,
        credential: String,
    ): InstallationStateResponse

    suspend fun sendOperations(
        endpoint: URI,
        credential: String,
        batch: OperationBatchRequest,
    ): OperationBatchResponse

    suspend fun synchronize(
        endpoint: URI,
        credential: String,
        request: SyncRequest,
    ): SyncResponse

    suspend fun revoke(
        endpoint: URI,
        revocationCredential: String,
        operationId: String,
    )

    suspend fun authorizedRequest(
        endpoint: URI,
        credential: String,
        request: AuthorizedRequest,
    ): AuthorizedResponse = error("Authorized optional-module transport is not implemented")
}

internal enum class AuthorizedMethod { GET, POST }

internal data class AuthorizedRequest(
    val method: AuthorizedMethod,
    val path: String,
    val query: Map<String, String>,
    val body: kotlinx.serialization.json.JsonObject?,
)

internal data class AuthorizedResponse(
    val statusCode: Int,
    val body: kotlinx.serialization.json.JsonObject?,
)

internal interface SyncStore {
    val snapshot: StateFlow<StoredSyncSnapshot>

    suspend fun apply(requestedModules: Set<SdkModule>, response: SyncResponse)
    suspend fun clear()
}

internal interface RevocationStore {
    suspend fun get(): RevocationEnvelope?
    suspend fun save(envelope: RevocationEnvelope)
    suspend fun clear()
}

internal interface ExposureStore {
    fun contains(exposureId: String): Boolean
    suspend fun mark(exposureId: String)
    suspend fun clear()
}

internal fun DeviceMetadata.toBootstrapRequest(recoveryToken: String?): BootstrapRequest =
    BootstrapRequest(
        locale = locale,
        timezone = timezone,
        sdkVersion = sdkVersion,
        appVersion = appVersion,
        appBuild = appBuild,
        deviceModel = deviceModel,
        osVersion = osVersion,
        recoveryToken = recoveryToken,
    )
