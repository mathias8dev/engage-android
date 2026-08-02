package io.engage.sdk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.engage.sdk.core.application.CoreRuntime
import io.engage.sdk.spi.EngageModule
import java.net.URI

/** Entry point shared by every Engage Android module. */
public object Engage {
    private val lifecycle = MutableStateFlow<EngageLifecycle>(EngageLifecycle.NotStarted)
    private val modules = linkedMapOf<String, EngageModule>()
    private var runtime: CoreRuntime? = null

    /** Current initialization state, primarily useful to surface integration failures. */
    public val state: StateFlow<EngageLifecycle> = lifecycle.asStateFlow()

    public val installation: Installation get() = requireRuntime().installation
    public val profile: Profile get() = requireRuntime().profile
    public val events: Events get() = requireRuntime().events
    public val actions: Actions get() = requireRuntime().actions
    public val sdkFeatures: SdkFeatures get() = requireRuntime().sdkFeatures
    public val flags: FeatureFlags get() = requireRuntime().flags
    public val preferenceCenter: PreferenceCenter get() = requireRuntime().preferenceCenter
    public val privacy: Privacy get() = requireRuntime().privacyApi

    /**
     * Starts Engage once for the application process.
     *
     * Repeating the call with an equivalent configuration is safe. A different configuration is
     * rejected because mixing App identities in one process would corrupt durable SDK state.
     */
    @Synchronized
    public fun start(context: Context, config: EngageConfig) {
        require(config.appKey.startsWith("eng_app_")) {
            "EngageConfig.appKey must start with eng_app_"
        }

        val current = lifecycle.value
        if (current is EngageLifecycle.Started) {
            require(current.config == config) { "Engage is already started with another config" }
            return
        }

        val started = CoreRuntime(context.applicationContext, config)
        runtime = started
        modules.values.forEach(started::startModule)
        lifecycle.value = EngageLifecycle.Started(
            applicationContext = context.applicationContext,
            config = config,
        )
    }

    /** Internal registration hook invoked by optional official module ContentProviders. */
    @JvmStatic
    @Synchronized
    public fun registerModule(module: EngageModule) {
        modules[module.id] = module
        runtime?.startModule(module)
    }

    internal fun requireRuntime(): CoreRuntime = checkNotNull(runtime) {
        "Engage.start(context, config) must be called before using the SDK"
    }
}

public data class EngageConfig(
    val appKey: String,
    val endpoint: URI = URI.create("https://api.engage.io/v1/"),
    val push: PushConfig = PushConfig(),
)

public sealed interface EngageLifecycle {
    public data object NotStarted : EngageLifecycle

    public class Started internal constructor(
        internal val applicationContext: Context,
        val config: EngageConfig,
    ) : EngageLifecycle
}
