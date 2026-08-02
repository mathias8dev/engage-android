package io.engage.sdk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Entry point shared by every Engage Android module. */
public object Engage {
    private val lifecycle = MutableStateFlow<EngageLifecycle>(EngageLifecycle.NotStarted)

    /** Current initialization state, primarily useful to surface integration failures. */
    public val state: StateFlow<EngageLifecycle> = lifecycle.asStateFlow()

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

        lifecycle.value = EngageLifecycle.Started(
            applicationContext = context.applicationContext,
            config = config,
        )
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
