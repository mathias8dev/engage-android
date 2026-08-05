package io.engage.sdk.core.data

import android.content.Context
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.RevocationEnvelope
import io.engage.sdk.core.domain.RevocationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidRevocationStore(context: Context) : RevocationStore {
    private val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val secrets = KeystoreSecretStore(preferences, REVOCATION_KEY_ALIAS)
    private val mutex = Mutex()

    override suspend fun get(): RevocationEnvelope? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val operationId = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty().minOrNull()
                ?: return@withContext null
            val credential = secrets.get(credentialKey(operationId)) ?: return@withContext null
            RevocationEnvelope(operationId, credential).also {
                EngageLogger.verbose("Privacy", "pending revocation loaded operationId=$operationId")
            }
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
            secrets.remove(credentialKey(operationId))
            val ids = preferences.getStringSet(OPERATION_IDS, emptySet()).orEmpty() - operationId
            check(preferences.edit().putStringSet(OPERATION_IDS, ids).commit()) {
                "Could not clear revocation operation"
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
