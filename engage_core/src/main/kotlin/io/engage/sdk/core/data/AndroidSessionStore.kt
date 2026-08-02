package io.engage.sdk.core.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.engage.sdk.PrivacyState
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidSessionStore(context: Context) : SessionStore {
    private val statePreferences = context.getSharedPreferences(STATE_STORE, Context.MODE_PRIVATE)
    private val privacyPreferences = context.getSharedPreferences(PRIVACY_STORE, Context.MODE_PRIVATE)
    private val secrets = KeystoreSecretStore(
        context.getSharedPreferences(SECRET_STORE, Context.MODE_PRIVATE),
    )
    private val mutablePrivacy = MutableStateFlow(readPrivacy())
    private val mutableSession = MutableStateFlow(readSession())
    private val mutex = Mutex()

    override val session: StateFlow<InstallationSession?> = mutableSession
    override val privacy: StateFlow<PrivacyState> = mutablePrivacy

    override suspend fun recoveryToken(): String? = mutex.withLock { secrets.get(RECOVERY_TOKEN) }

    override suspend fun saveSession(session: InstallationSession): Unit = mutex.withLock {
        check(secrets.put(CREDENTIAL, session.credential)) { "Could not persist installation credential" }
        check(secrets.put(REVOCATION_CREDENTIAL, session.revocationCredential)) {
            "Could not persist revocation credential"
        }
        check(secrets.put(RECOVERY_TOKEN, session.recoveryToken)) { "Could not persist recovery token" }
        check(
            statePreferences.edit()
                .putString(INSTALLATION_ID, session.installationId)
                .putLong(GENERATION, session.generation)
                .putString(PUSH_SUBSCRIPTION, session.pushSubscription)
                .putString(SERVER_TIME, session.serverTime)
                .commit(),
        ) { "Could not persist installation metadata" }
        writePrivacy(session.privacy)
        mutableSession.value = session
    }

    override suspend fun setPrivacy(state: PrivacyState): Unit = mutex.withLock {
        writePrivacy(state)
    }

    private fun writePrivacy(state: PrivacyState) {
        check(privacyPreferences.edit().putString(PRIVACY, state.name).commit()) {
            "Could not persist Engage privacy state"
        }
        mutablePrivacy.value = state
        mutableSession.value = mutableSession.value?.copy(privacy = state)
    }

    override suspend fun clearSession(): Unit = mutex.withLock {
        check(statePreferences.edit().clear().commit()) { "Could not clear installation metadata" }
        secrets.remove(CREDENTIAL)
        secrets.remove(REVOCATION_CREDENTIAL)
        secrets.remove(RECOVERY_TOKEN)
        mutableSession.value = null
    }

    private fun readPrivacy(): PrivacyState = privacyPreferences.getString(PRIVACY, null)
        ?.let { stored -> runCatching { PrivacyState.valueOf(stored) }.getOrNull() }
        ?: PrivacyState.OPTED_IN

    private fun readSession(): InstallationSession? {
        val installationId = statePreferences.getString(INSTALLATION_ID, null) ?: return null
        val credential = secrets.get(CREDENTIAL) ?: return null
        val revocationCredential = secrets.get(REVOCATION_CREDENTIAL) ?: return null
        val recoveryToken = secrets.get(RECOVERY_TOKEN) ?: return null
        return InstallationSession(
            installationId = installationId,
            credential = credential,
            revocationCredential = revocationCredential,
            recoveryToken = recoveryToken,
            generation = statePreferences.getLong(GENERATION, 0),
            privacy = mutablePrivacy.value,
            pushSubscription = statePreferences.getString(PUSH_SUBSCRIPTION, "OPTED_IN") ?: "OPTED_IN",
            serverTime = statePreferences.getString(SERVER_TIME, "1970-01-01T00:00:00Z")
                ?: "1970-01-01T00:00:00Z",
        )
    }

    private companion object {
        const val STATE_STORE = "engage_core_state"
        const val PRIVACY_STORE = "engage_privacy_marker"
        const val SECRET_STORE = "engage_core_secrets"
        const val INSTALLATION_ID = "installation_id"
        const val GENERATION = "generation"
        const val PUSH_SUBSCRIPTION = "push_subscription"
        const val SERVER_TIME = "server_time"
        const val PRIVACY = "privacy"
        const val CREDENTIAL = "credential"
        const val REVOCATION_CREDENTIAL = "revocation_credential"
        const val RECOVERY_TOKEN = "recovery_token"
    }
}

internal class KeystoreSecretStore(
    private val preferences: SharedPreferences,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    fun put(key: String, value: String): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        return preferences.edit().putString(key, encoded).commit()
    }

    fun get(key: String): String? = preferences.getString(key, null)?.let { encoded ->
        runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "io.engage.sdk.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
