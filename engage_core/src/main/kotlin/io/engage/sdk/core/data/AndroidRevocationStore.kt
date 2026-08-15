package io.engage.sdk.core.data

import android.content.Context
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.core.domain.RevocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidRevocationStore(
    context: Context,
    storageScope: String = "",
    secretStore: SecretStore? = null,
) : RevocationStore {
    private val preferences = context.getSharedPreferences(
        scopedStorageName(STORE, storageScope),
        Context.MODE_PRIVATE,
    )
    private val secrets = secretStore ?: KeystoreSecretStore(preferences, REVOCATION_KEY_ALIAS)
    private val mutex = Mutex()

    override suspend fun get(): RevocationEnvelope? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ids = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty().sorted()
            for (operationId in ids) {
                val credential = secrets.get(credentialKey(operationId))
                if (credential == null) {
                    val remaining = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty() - operationId
                    check(preferences.edit().putStringSet(OPERATION_IDS, remaining).commit()) {
                        "Could not discard invalid revocation operation"
                    }
                    EngageLogger.warn(
                        "Privacy",
                        "invalid revocation discarded operationId=$operationId reason=missing_credential",
                    )
                    continue
                }
                return@withContext RevocationEnvelope(operationId, credential).also {
                    EngageLogger.verbose("Privacy", "pending revocation loaded operationId=$operationId")
                }
            }
            null
        }
    }

    override suspend fun save(envelope: RevocationEnvelope): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(secrets.put(credentialKey(envelope.operationId), envelope.credential)) {
                "Could not persist revocation credential"
            }
            val ids = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty() + envelope.operationId
            check(preferences.edit().putStringSet(OPERATION_IDS, ids).commit()) {
                "Could not persist revocation operation"
            }
            EngageLogger.warn("Privacy", "revocation persisted operationId=${envelope.operationId}")
        }
    }

    override suspend fun clear(operationId: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val ids = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty() - operationId
            check(preferences.edit().putStringSet(OPERATION_IDS, ids).commit()) {
                "Could not clear revocation operation"
            }
            if (!secrets.remove(credentialKey(operationId))) {
                EngageLogger.warn("Privacy", "revocation credential cleanup failed operationId=$operationId")
            }
            EngageLogger.info("Privacy", "revocation cleared operationId=$operationId")
        }
    }

    private fun credentialKey(operationId: String): String = "$CREDENTIAL_PREFIX$operationId"

    private companion object {
        const val STORE = "engage_revocation_envelope"
        const val OPERATION_IDS = "operation_ids"
        const val CREDENTIAL_PREFIX = "credential."
        const val REVOCATION_KEY_ALIAS = "io.engage.sdk.revocation.v1"
    }
}
