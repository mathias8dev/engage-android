package io.engage.sdk.core.data

import java.net.URI
import android.content.Context
import io.engage.sdk.spi.migrateLegacyDatabase
import io.engage.sdk.spi.migrateLegacyPreferences
import io.engage.sdk.spi.scopedStorageName as scopedSpiStorageName

/** Stable, non-secret namespace for every durable value owned by one Engage application. */
internal fun storageScope(appKey: String, endpoint: URI): String {
    var hash = FNV_OFFSET_BASIS
    "${endpoint.normalize()}\u0000$appKey".toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = (hash xor byte.toUByte().toLong()) * FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

internal fun scopedStorageName(base: String, scope: String): String =
    scopedSpiStorageName(base, scope)

internal fun migrateLegacyCoreStorage(context: Context, scope: String) {
    listOf(
        "engage_core_state",
        "engage_privacy_marker",
        "engage_core_secrets",
        "engage_flag_exposures",
        "engage_revocation_envelope",
        "engage_sdk_features",
    ).forEach { migrateLegacyPreferences(context, it, scope) }
    listOf("engage_operations.db", "engage_sync.db").forEach {
        migrateLegacyDatabase(context, it, scope)
    }
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L
