package io.engage.sdk.core.data

import android.content.Context
import io.engage.sdk.EngageLogger
import io.engage.sdk.core.domain.ExposureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidExposureStore(context: Context) : ExposureStore {
    private val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    override fun contains(exposureId: String): Boolean = preferences.contains(exposureId).also { present ->
        EngageLogger.verbose("Flags", "exposure lookup id=$exposureId present=$present")
    }

    override suspend fun mark(exposureId: String): Unit = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean(exposureId, true).commit()) {
            "Could not persist feature-flag exposure"
        }
        EngageLogger.debug("Flags", "exposure persisted id=$exposureId")
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) { "Could not clear feature-flag exposures" }
        EngageLogger.warn("Flags", "exposures cleared")
    }

    private companion object {
        const val STORE = "engage_flag_exposures"
    }
}
