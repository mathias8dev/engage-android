package io.engage.sdk.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.spi.migrateLegacyDatabase
import io.engage.sdk.spi.migrateLegacyPreferences
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
        val firstScope = storageScope("eng_app_first", URI.create("https://edge.test/v1/"))
        val secondScope = storageScope("eng_app_second", URI.create("https://edge.test/v1/"))
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
        val scope = storageScope("eng_app_revocations", URI.create("https://edge.test/v1/"))
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
