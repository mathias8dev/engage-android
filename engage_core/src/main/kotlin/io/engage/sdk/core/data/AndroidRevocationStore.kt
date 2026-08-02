package io.engage.sdk.core.data

import android.content.Context
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
            val operationId = preferences.getString(OPERATION_ID, null) ?: return@withContext null
            val credential = secrets.get(CREDENTIAL) ?: return@withContext null
            RevocationEnvelope(operationId, credential)
        }
    }

    override suspend fun save(envelope: RevocationEnvelope): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(secrets.put(CREDENTIAL, envelope.credential)) { "Could not persist revocation credential" }
            check(preferences.edit().putString(OPERATION_ID, envelope.operationId).commit()) {
                "Could not persist revocation operation"
            }
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            secrets.remove(CREDENTIAL)
            check(preferences.edit().remove(OPERATION_ID).commit()) {
                "Could not clear revocation operation"
            }
        }
    }

    private companion object {
        const val STORE = "engage_revocation_envelope"
        const val OPERATION_ID = "operation_id"
        const val CREDENTIAL = "credential"
        const val REVOCATION_KEY_ALIAS = "io.engage.sdk.revocation.v1"
    }
}

