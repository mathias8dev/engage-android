package io.engage.sdk.core.domain

import io.engage.sdk.PrivacyState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class InstallationSession(
    val installationId: String,
    val credential: String,
    val revocationCredential: String,
    val recoveryToken: String,
    val generation: Long,
    val privacy: PrivacyState,
    val pushSubscription: String,
    val serverTime: String,
)

@Serializable
internal data class BootstrapRequest(
    val platform: String = "ANDROID",
    val locale: String,
    val timezone: String,
    val sdkVersion: String,
    val appVersion: String,
    val appBuild: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val recoveryToken: String? = null,
)

@Serializable
internal data class BindingCodeResponse(
    val code: String,
    val expiresAt: String,
)

@Serializable
internal data class SdkOperation(
    val operationId: String,
    val generation: Long,
    val type: OperationType,
    val occurredAt: String,
    val payload: JsonObject,
)

@Serializable
internal enum class OperationType {
    EVENT_TRACKED,
    SCREEN_VIEWED,
    SCREEN_CLEARED,
    INSTALLATION_ATTRIBUTES_EDITED,
    PROFILE_ATTRIBUTES_EDITED,
    PROFILE_TAGS_EDITED,
    INSTALLATION_SUBSCRIPTIONS_EDITED,
    PROFILE_SUBSCRIPTIONS_EDITED,
    PUSH_TOKEN_SET,
    PUSH_SUBSCRIPTION_SET,
    PUSH_PERMISSION_SET,
    PRIVACY_STATE_SET,
    INTERACTION_TRACKED,
    PUSH_RECEIPT_RECORDED,
    FLAG_EXPOSED,
}

internal data class ReservedOperationBatch(
    val batchId: String,
    val operations: List<SdkOperation>,
)

@Serializable
internal data class OperationBatchRequest(
    val batchId: String,
    val operations: List<SdkOperation>,
)

@Serializable
internal data class OperationBatchResponse(
    val batchId: String,
    val results: List<OperationResult>,
    val serverTime: String,
)

@Serializable
internal data class OperationResult(
    val operationId: String,
    val status: OperationStatus,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
internal enum class OperationStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
}

internal data class DeviceMetadata(
    val locale: String,
    val timezone: String,
    val sdkVersion: String,
    val appVersion: String,
    val appBuild: String?,
    val deviceModel: String?,
    val osVersion: String?,
)
