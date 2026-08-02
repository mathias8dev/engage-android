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
    suspend fun enqueue(operation: SdkOperation)
    suspend fun reserve(limit: Int): ReservedOperationBatch?

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

    suspend fun sendOperations(
        endpoint: URI,
        credential: String,
        batch: OperationBatchRequest,
    ): OperationBatchResponse
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

