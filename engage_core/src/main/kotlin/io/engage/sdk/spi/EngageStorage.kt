package io.engage.sdk.spi

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.RestrictTo
import java.io.File

/** Returns a scoped preferences name after safely migrating legacy unscoped values once. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun EngageModuleContext.scopedPreferencesName(base: String): String =
    migrateLegacyPreferences(applicationContext, base, storageScope)

/** Returns a scoped database name after safely copying a legacy unscoped database once. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public fun EngageModuleContext.scopedDatabaseName(base: String): String =
    migrateLegacyDatabase(applicationContext, base, storageScope)

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

private object StorageMigrationLock

private const val MIGRATION_STORE = "engage_storage_migration_v2"
private const val LEGACY_OWNER = "legacy_owner_scope"
