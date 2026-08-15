package io.engage.sdk.core.application

import android.content.Context
import io.engage.sdk.SdkFeature
import io.engage.sdk.SdkFeatureEditor
import io.engage.sdk.SdkFeatures
import io.engage.sdk.EngageLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class DefaultSdkFeatures(
    context: Context,
    storageScope: String = "",
) : SdkFeatures {
    private val preferences = context.getSharedPreferences(
        io.engage.sdk.core.data.scopedStorageName(STORE, storageScope),
        Context.MODE_PRIVATE,
    )
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
        EngageLogger.info(
            "Features",
            "enabled=${next.sortedBy { it.name }} disabled=${disabled.sorted()}",
        )
    }

    fun addAvailable(features: Set<SdkFeature>) {
        val additions = features - available
        if (additions.isEmpty()) return
        available += additions
        mutableEnabled.value = available - disabled
        EngageLogger.debug("Features", "available modules added=${additions.sortedBy { it.name }}")
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
