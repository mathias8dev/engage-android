package io.engage.sdk.core.application

import android.content.Context
import io.engage.sdk.SdkFeature
import io.engage.sdk.SdkFeatureEditor
import io.engage.sdk.SdkFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class DefaultSdkFeatures(context: Context) : SdkFeatures {
    private val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    private val available = CORE_FEATURES.toMutableSet()
    private val disabled = preferences.getStringSet(DISABLED, emptySet()).orEmpty()
        .mapNotNullTo(mutableSetOf()) { runCatching { SdkFeature.valueOf(it) }.getOrNull() }
    private val mutableEnabled = MutableStateFlow(available - disabled)

    override val enabled: StateFlow<Set<SdkFeature>> = mutableEnabled

    override fun edit(block: SdkFeatureEditor.() -> Unit) {
        val requested = SdkFeatureEditor(mutableEnabled.value).apply(block).build()
        val next = requested intersect available
        disabled.clear()
        disabled += available - next
        check(preferences.edit().putStringSet(DISABLED, disabled.mapTo(mutableSetOf(), SdkFeature::name)).commit()) {
            "Could not persist Engage feature settings"
        }
        mutableEnabled.value = next
    }

    fun addAvailable(features: Set<SdkFeature>) {
        val additions = features - available
        if (additions.isEmpty()) return
        available += additions
        mutableEnabled.value = available - disabled
    }

    private companion object {
        const val STORE = "engage_sdk_features"
        const val DISABLED = "disabled"
        val CORE_FEATURES = setOf(
            SdkFeature.ANALYTICS,
            SdkFeature.FEATURE_FLAGS,
            SdkFeature.PREFERENCES,
        )
    }
}

