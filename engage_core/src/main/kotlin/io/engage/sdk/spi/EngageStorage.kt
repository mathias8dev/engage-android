package io.engage.sdk.spi

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.RestrictTo
import java.io.File
import java.io.RandomAccessFile

/** Returns a scoped preferences name after safely migrating legacy unscoped values once. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun EngageModuleContext.scopedPreferencesName(base: String): String =
    endpointStorageScopes().fold(scopedStorageName(base, storageScope)) { _, sourceScope ->
        migrateScopedPreferences(applicationContext, base, sourceScope, storageScope)
    }.also { migrateLegacyPreferences(applicationContext, base, storageScope) }

/** Returns a scoped database name after safely copying a legacy unscoped database once. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun EngageModuleContext.scopedDatabaseName(base: String): String =
    endpointStorageScopes().fold(scopedStorageName(base, storageScope)) { _, sourceScope ->
        migrateScopedDatabase(applicationContext, base, sourceScope, storageScope)
    }.also { migrateLegacyDatabase(applicationContext, base, storageScope) }

private fun EngageModuleContext.endpointStorageScopes(): List<String> =
    (listOf(config.endpoint) + config.legacyEndpoints)
        .map { legacyEndpointStorageScope(it.normalize().toString(), config.appKey) }
        .distinct()

internal fun migrateScopedPreferences(
    context: Context,
    base: String,
    sourceScope: String,
    targetScope: String,
    migrationStore: String = MIGRATION_STORE,
): String {
    val targetName = scopedStorageName(base, targetScope)
    if (sourceScope == targetScope || sourceScope.isBlank() || targetScope.isBlank()) return targetName
    val marker = "preferences.$base.$sourceScope.$targetScope"
    val migration = context.getSharedPreferences(migrationStore, Context.MODE_PRIVATE)
    synchronized(StorageMigrationLock) {
        if (migration.getBoolean(marker, false)) return@synchronized
        val source = context.getSharedPreferences(scopedStorageName(base, sourceScope), Context.MODE_PRIVATE)
        val target = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        if (target.all.isEmpty() && source.all.isNotEmpty()) {
            check(copyPreferences(source, target)) { "Could not migrate scoped Engage preferences: $base" }
        }
        check(migration.edit().putBoolean(marker, true).commit()) {
            "Could not persist scoped Engage preferences migration marker: $base"
        }
    }
    return targetName
}

internal fun migrateScopedDatabase(
    context: Context,
    base: String,
    sourceScope: String,
    targetScope: String,
    migrationStore: String = MIGRATION_STORE,
): String {
    val targetName = scopedStorageName(base, targetScope)
    if (sourceScope == targetScope || sourceScope.isBlank() || targetScope.isBlank()) return targetName
    val marker = "database.$base.$sourceScope.$targetScope"
    val migration = context.getSharedPreferences(migrationStore, Context.MODE_PRIVATE)
    synchronized(StorageMigrationLock) {
        if (migration.getBoolean(marker, false)) return@synchronized
        val source = context.getDatabasePath(scopedStorageName(base, sourceScope))
        val target = context.getDatabasePath(targetName)
        if (source.exists()) {
            target.parentFile?.mkdirs()
            replaceDatabaseParts(source, target)
        }
        check(migration.edit().putBoolean(marker, true).commit()) {
            "Could not persist scoped Engage database migration marker: $base"
        }
    }
    return targetName
}

internal fun scopedStorageName(base: String, scope: String): String {
    if (scope.isBlank()) return base
    val extension = base.lastIndexOf('.').takeIf { it > 0 }
    return if (extension == null) {
        "${base}_$scope"
    } else {
        "${base.substring(0, extension)}_$scope${base.substring(extension)}"
    }
}

internal fun migrateLegacyPreferences(
    context: Context,
    base: String,
    scope: String,
    migrationStore: String = MIGRATION_STORE,
): String {
    val targetName = scopedStorageName(base, scope)
    if (scope.isBlank()) return targetName
    val migration = context.getSharedPreferences(migrationStore, Context.MODE_PRIVATE)
    if (!ownsLegacyStorage(migration, scope)) return targetName
    val marker = "preferences.$base"
    if (migration.getBoolean(marker, false)) return targetName

    synchronized(StorageMigrationLock) {
        if (migration.getBoolean(marker, false)) return@synchronized
        val source = context.getSharedPreferences(base, Context.MODE_PRIVATE)
        val target = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        if (target.all.isEmpty() && source.all.isNotEmpty()) {
            check(copyPreferences(source, target)) { "Could not migrate legacy Engage preferences: $base" }
        }
        check(migration.edit().putBoolean(marker, true).commit()) {
            "Could not persist Engage preferences migration marker: $base"
        }
    }
    return targetName
}

internal fun migrateLegacyDatabase(
    context: Context,
    base: String,
    scope: String,
    migrationStore: String = MIGRATION_STORE,
): String {
    val targetName = scopedStorageName(base, scope)
    if (scope.isBlank()) return targetName
    val migration = context.getSharedPreferences(migrationStore, Context.MODE_PRIVATE)
    if (!ownsLegacyStorage(migration, scope)) return targetName
    val marker = "database.$base"
    if (migration.getBoolean(marker, false)) return targetName

    synchronized(StorageMigrationLock) {
        if (migration.getBoolean(marker, false)) return@synchronized
        val source = context.getDatabasePath(base)
        val target = context.getDatabasePath(targetName)
        if (source.exists() && !target.exists()) {
            target.parentFile?.mkdirs()
            copyDatabasePart(source, target)
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                copyDatabasePart(File(source.path + suffix), File(target.path + suffix))
            }
        }
        check(migration.edit().putBoolean(marker, true).commit()) {
            "Could not persist Engage database migration marker: $base"
        }
    }
    return targetName
}

private fun ownsLegacyStorage(migration: SharedPreferences, scope: String): Boolean =
    synchronized(StorageMigrationLock) {
        val owner = migration.getString(LEGACY_OWNER, null)
        if (owner == null) {
            check(migration.edit().putString(LEGACY_OWNER, scope).commit()) {
                "Could not claim legacy Engage storage"
            }
            true
        } else {
            owner == scope
        }
    }

private fun copyPreferences(source: SharedPreferences, target: SharedPreferences): Boolean {
    val editor = target.edit()
    source.all.forEach { (key, value) ->
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    return editor.commit()
}

private fun copyDatabasePart(source: File, target: File) {
    if (source.exists() && !target.exists()) source.copyTo(target)
}

/**
 * Stages every SQLite file before replacing the destination, with the main database moved last.
 * If the process stops mid-migration the marker remains absent and the complete source is staged
 * again on the next start instead of accepting a partial target as authoritative.
 */
private fun replaceDatabaseParts(source: File, target: File) {
    val suffixes = listOf("-wal", "-shm", "-journal", "")
    val staged = suffixes.associateWith { suffix -> File(target.path + suffix + ".engage-migrating") }

    suffixes.forEach { suffix ->
        val sourcePart = File(source.path + suffix)
        val stagedPart = checkNotNull(staged[suffix])
        if (stagedPart.exists()) check(stagedPart.delete()) { "Could not clear staged Engage database" }
        if (sourcePart.exists()) {
            sourcePart.copyTo(stagedPart, overwrite = true)
            RandomAccessFile(stagedPart, "rw").use { it.fd.sync() }
        }
    }

    suffixes.forEach { suffix ->
        val targetPart = File(target.path + suffix)
        val stagedPart = checkNotNull(staged[suffix])
        if (stagedPart.exists()) {
            check(stagedPart.renameTo(targetPart)) { "Could not atomically replace Engage database" }
        } else if (suffix.isNotEmpty() && targetPart.exists()) {
            check(targetPart.delete()) { "Could not clear stale Engage database sidecar" }
        }
    }
}

private fun legacyEndpointStorageScope(endpoint: String, appKey: String): String {
    var hash = -3750763034362895579L
    "$endpoint\u0000$appKey".toByteArray(Charsets.UTF_8).forEach { byte ->
        hash = (hash xor byte.toUByte().toLong()) * 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private object StorageMigrationLock

private const val MIGRATION_STORE = "engage_storage_migration_v2"
private const val LEGACY_OWNER = "legacy_owner_scope"
