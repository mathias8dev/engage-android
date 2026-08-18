package io.engage.sdk.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.spi.migrateLegacyDatabase
import io.engage.sdk.spi.migrateLegacyPreferences
import io.engage.sdk.spi.migrateScopedDatabase
import io.engage.sdk.spi.migrateScopedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URI
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class StorageIsolationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `application keys use independent durable sessions`() = runTest {
        val firstScope = storageScope("eng_app_first")
        val secondScope = storageScope("eng_app_second")
        assertNotEquals(firstScope, secondScope)
        val firstSecrets = InMemorySecrets()
        val first = AndroidSessionStore(context, firstScope, firstSecrets)
        val second = AndroidSessionStore(context, secondScope, InMemorySecrets())

        first.saveSession(session())

        assertEquals(
            "installation-first",
            AndroidSessionStore(context, firstScope, firstSecrets).session.value?.installationId,
        )
        assertNull(second.session.value)
    }

    @Test
    fun `missing revocation credential cannot poison newer work`() = runTest {
        val scope = storageScope("eng_app_revocations")
        val secrets = InMemorySecrets()
        val store = AndroidRevocationStore(context, scope, secrets)
        store.save(RevocationEnvelope("a-missing", "old-secret"))
        store.save(RevocationEnvelope("b-valid", "new-secret"))
        secrets.remove("credential.a-missing")

        assertEquals(RevocationEnvelope("b-valid", "new-secret"), store.get())
        store.clear("b-valid")
        assertNull(store.get())
    }

    @Test
    fun `endpoint changes preserve the application storage scope`() {
        val appKey = "eng_app_endpoint_change"

        assertEquals(storageScope(appKey), storageScope(appKey))
        assertNotEquals(
            legacyEndpointStorageScope(appKey, URI.create("https://edge-one.test/v1/")),
            legacyEndpointStorageScope(appKey, URI.create("https://edge-two.test/v1/")),
        )
    }

    @Test
    fun `endpoint scoped preferences migrate once into the stable application scope`() {
        val id = UUID.randomUUID().toString()
        val base = "engage_endpoint_scope_$id"
        val migrationStore = "engage_endpoint_scope_migration_$id"
        val oldScope = legacyEndpointStorageScope("eng_app_scope", URI.create("https://old-edge.test/v1/"))
        val newScope = storageScope("eng_app_scope")
        context.getSharedPreferences(scopedStorageName(base, oldScope), Context.MODE_PRIVATE)
            .edit()
            .putString("installation", "installation-1")
            .commit()

        val targetName = migrateScopedPreferences(context, base, oldScope, newScope, migrationStore)

        val target = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        assertEquals("installation-1", target.getString("installation", null))
        target.edit().clear().commit()

        migrateScopedPreferences(context, base, oldScope, newScope, migrationStore)

        assertEquals(emptyMap<String, Any>(), target.all)
    }

    @Test
    fun `endpoint scoped database migrates once into the stable application scope`() {
        val id = UUID.randomUUID().toString()
        val base = "engage_endpoint_database_$id.db"
        val migrationStore = "engage_endpoint_database_migration_$id"
        val oldScope = legacyEndpointStorageScope("eng_app_scope", URI.create("https://old-edge.test/v1/"))
        val newScope = storageScope("eng_app_scope")
        val source = context.getDatabasePath(scopedStorageName(base, oldScope))
        source.parentFile?.mkdirs()
        source.writeText("endpoint-database")
        java.io.File(source.path + "-wal").writeText("endpoint-wal")

        val targetName = migrateScopedDatabase(context, base, oldScope, newScope, migrationStore)

        val target = context.getDatabasePath(targetName)
        assertEquals("endpoint-database", target.readText())
        assertEquals("endpoint-wal", java.io.File(target.path + "-wal").readText())
        target.writeText("stable-database")
        java.io.File(target.path + "-wal").delete()

        migrateScopedDatabase(context, base, oldScope, newScope, migrationStore)

        assertEquals("stable-database", target.readText())
        assertEquals(false, java.io.File(target.path + "-wal").exists())
    }

    @Test
    fun `changing endpoint while upgrading preserves prior endpoint storage`() {
        val appKey = "eng_app_simultaneous_${UUID.randomUUID()}"
        val oldScope = legacyEndpointStorageScope(appKey, URI.create("https://old-edge.test/v1/"))
        val oldEndpoint = URI.create("https://old-edge.test/v1/")
        val currentEndpoint = URI.create("https://new-edge.test/v1/")
        val stableScope = storageScope(appKey)
        val source = context.getSharedPreferences(
            scopedStorageName("engage_core_state", oldScope),
            Context.MODE_PRIVATE,
        )
        source.edit().putString("installation", "installation-before-upgrade").commit()

        migrateLegacyCoreStorage(
            context,
            stableScope,
            endpoints = listOf(currentEndpoint, oldEndpoint),
            appKey = appKey,
        )

        val target = context.getSharedPreferences(
            scopedStorageName("engage_core_state", stableScope),
            Context.MODE_PRIVATE,
        )
        assertEquals("installation-before-upgrade", target.getString("installation", null))
    }

    @Test
    fun `interrupted endpoint database migration replaces an incomplete target`() {
        val id = UUID.randomUUID().toString()
        val base = "engage_interrupted_endpoint_database_$id.db"
        val migrationStore = "engage_interrupted_endpoint_database_migration_$id"
        val oldScope = legacyEndpointStorageScope("eng_app_interrupted", URI.create("https://old-edge.test/v1/"))
        val stableScope = storageScope("eng_app_interrupted_$id")
        val source = context.getDatabasePath(scopedStorageName(base, oldScope))
        val target = context.getDatabasePath(scopedStorageName(base, stableScope))
        source.parentFile?.mkdirs()
        source.writeText("complete-endpoint-database")
        target.writeText("partial")

        migrateScopedDatabase(context, base, oldScope, stableScope, migrationStore)

        assertEquals("complete-endpoint-database", target.readText())
    }

    @Test
    fun `legacy privacy is migrated once and cannot be resurrected after wipe`() {
        val id = UUID.randomUUID().toString()
        val base = "engage_legacy_privacy_$id"
        val migrationStore = "engage_migration_test_$id"
        val scope = "active-scope"
        context.getSharedPreferences(base, Context.MODE_PRIVATE)
            .edit()
            .putString("privacy", PrivacyState.OPTED_OUT.name)
            .commit()

        val targetName = migrateLegacyPreferences(context, base, scope, migrationStore)

        val target = context.getSharedPreferences(targetName, Context.MODE_PRIVATE)
        assertEquals(PrivacyState.OPTED_OUT.name, target.getString("privacy", null))
        target.edit().clear().commit()

        migrateLegacyPreferences(context, base, scope, migrationStore)

        assertEquals(emptyMap<String, Any>(), target.all)
        val otherName = migrateLegacyPreferences(context, base, "another-scope", migrationStore)
        assertEquals(emptyMap<String, Any>(), context.getSharedPreferences(otherName, Context.MODE_PRIVATE).all)
    }

    @Test
    fun `legacy database sidecars never contaminate an existing scoped database`() {
        val id = UUID.randomUUID().toString()
        val base = "engage_legacy_database_$id.db"
        val migrationStore = "engage_database_migration_test_$id"
        val scope = "active-scope"
        val targetName = scopedStorageName(base, scope)
        val source = context.getDatabasePath(base)
        val target = context.getDatabasePath(targetName)
        source.parentFile?.mkdirs()
        source.writeText("legacy-database")
        java.io.File(source.path + "-wal").writeText("legacy-wal")
        target.writeText("current-database")

        migrateLegacyDatabase(context, base, scope, migrationStore)

        assertEquals("current-database", target.readText())
        assertEquals(false, java.io.File(target.path + "-wal").exists())
    }

    private fun session() = InstallationSession(
        installationId = "installation-first",
        credential = "credential",
        revocationCredential = "revocation",
        recoveryToken = "recovery",
        generation = 1,
        privacy = PrivacyState.OPTED_IN,
        pushSubscription = "OPTED_IN",
        serverTime = "2026-08-06T12:00:00Z",
    )

    private class InMemorySecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String): Boolean {
            values[key] = value
            return true
        }
        override fun get(key: String): String? = values[key]
        override fun remove(key: String): Boolean {
            values.remove(key)
            return true
        }
    }
}
