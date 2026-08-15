package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public enum class SdkFeature {
    PUSH,
    IN_APP,
    MESSAGE_CENTER,
    ANALYTICS,
    FEATURE_FLAGS,
    PREFERENCES,
}

public class SdkFeatureEditor internal constructor(enabled: Set<SdkFeature>) {
    private val edited = enabled.toMutableSet()

    public fun enable(feature: SdkFeature) {
        edited += feature
        EngageLogger.verbose("Features", "enable requested feature=${feature.name}")
    }

    public fun disable(feature: SdkFeature) {
        edited -= feature
        EngageLogger.verbose("Features", "disable requested feature=${feature.name}")
    }

    internal fun build(): Set<SdkFeature> = edited.toSet()
}

public interface SdkFeatures {
    val enabled: StateFlow<Set<SdkFeature>>

    fun edit(block: SdkFeatureEditor.() -> Unit)
}
