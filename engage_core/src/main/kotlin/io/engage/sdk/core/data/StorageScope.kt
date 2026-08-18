package io.engage.sdk.core.data

import android.content.Context
import io.engage.sdk.spi.migrateLegacyDatabase
import io.engage.sdk.spi.migrateLegacyPreferences
import io.engage.sdk.spi.migrateScopedDatabase
import io.engage.sdk.spi.migrateScopedPreferences
import io.engage.sdk.spi.scopedStorageName as scopedSpiStorageName
import java.net.URI

/** Stable, non-secret namespace for every durable value owned by one Engage application. */
internal fun storageScope(appKey: String): String {
    var hash = FNV_OFFSET_BASIS
    appKey.toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = (hash xor byte.toUByte().toLong()) * FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

internal fun legacyEndpointStorageScope(appKey: String, endpoint: URI): String {
    var hash = FNV_OFFSET_BASIS
    "${endpoint.normalize()}\u0000$appKey".toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = (hash xor byte.toUByte().toLong()) * FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

internal fun scopedStorageName(base: String, scope: String): String =
    scopedSpiStorageName(base, scope)

internal fun migrateLegacyCoreStorage(
    context: Context,
    scope: String,
    endpoints: List<URI>,
    appKey: String,
) {
    val endpointScopes = endpoints
        .map { legacyEndpointStorageScope(appKey, it) }
        .distinct()
    listOf(
        "engage_core_state",
        "engage_privacy_marker",
        "engage_core_secrets",
        "engage_flag_exposures",
        "engage_revocation_envelope",
        "engage_sdk_features",
    ).forEach {
        endpointScopes.forEach { endpointScope ->
            migrateScopedPreferences(context, it, endpointScope, scope)
        }
        migrateLegacyPreferences(context, it, scope)
    }
    listOf("engage_operations.db", "engage_sync.db").forEach {
        endpointScopes.forEach { endpointScope ->
            migrateScopedDatabase(context, it, endpointScope, scope)
        }
        migrateLegacyDatabase(context, it, scope)
    }
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
