package io.engage.sdk.core.domain

import io.engage.sdk.PrivacyState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class InstallationStateResponse(
    val installationId: String,
    val generation: Long,
    val bindingState: String,
    val privacy: PrivacyState,
    val pushSubscription: String,
    val bound: Boolean,
    val updatedAt: String,
)

@Serializable
internal enum class SdkModule {
    PUSH,
    IN_APP,
    PREFERENCES,
    FEATURE_FLAGS,
}

@Serializable
internal data class SyncRequest(
    val cursor: String? = null,
    val modules: Set<SdkModule>,
)

@Serializable
internal data class SyncResponse(
    val cursor: String,
    val generation: Long,
    val revision: Long,
    val fullSnapshot: Boolean,
    val documents: List<SyncDocument>,
    val tombstones: List<SyncTombstone>,
    val serverTime: String,
    val refreshAfterSeconds: Long,
)

@Serializable
internal data class SyncDocument(
    val module: SdkModule,
    val key: String,
    val revision: Long,
    val payload: JsonObject,
)

@Serializable
internal data class SyncTombstone(
    val module: SdkModule,
    val key: String,
    val revision: Long,
)

internal data class StoredSyncSnapshot(
    val cursor: String? = null,
    val generation: Long? = null,
    val revision: Long = 0,
    val documents: List<SyncDocument> = emptyList(),
    val refreshAfterSeconds: Long = 900,
)

internal data class RevocationEnvelope(
    val operationId: String,
    val credential: String,
)
