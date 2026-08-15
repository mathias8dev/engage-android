package io.engage.sdk

import android.util.Log
import androidx.annotation.RestrictTo

/** Minimum severity emitted by Engage to Logcat. */
public enum class EngageLogLevel(internal val priority: Int) {
    VERBOSE(Log.VERBOSE),
    DEBUG(Log.DEBUG),
    INFO(Log.INFO),
    WARN(Log.WARN),
    ERROR(Log.ERROR),
    NONE(Int.MAX_VALUE),
}

/** Logging bridge shared by the official Engage Android artifacts. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object EngageLogger {
    @Volatile
    private var minimumLevel: EngageLogLevel = EngageLogLevel.INFO

    @JvmStatic
    public fun configure(level: EngageLogLevel) {
        minimumLevel = level
        info("Core", "logging configured level=${level.name}")
    }

    @JvmStatic
    public fun verbose(component: String, message: String) = emit(EngageLogLevel.VERBOSE, component, message)

    @JvmStatic
    public fun debug(component: String, message: String) = emit(EngageLogLevel.DEBUG, component, message)

    @JvmStatic
    public fun info(component: String, message: String) = emit(EngageLogLevel.INFO, component, message)

    @JvmStatic
    public fun warn(component: String, message: String, error: Throwable? = null) =
        emit(EngageLogLevel.WARN, component, message, error)

    @JvmStatic
    public fun error(component: String, message: String, error: Throwable? = null) =
        emit(EngageLogLevel.ERROR, component, message, error)

    private fun emit(
        level: EngageLogLevel,
        component: String,
        message: String,
        error: Throwable? = null,
    ) {
        if (minimumLevel == EngageLogLevel.NONE || level.priority < minimumLevel.priority) return
        val rendered = "[$component] $message"
        val complete = if (error == null) {
            rendered
        } else {
            "$rendered errorType=${error.javaClass.name}\n${error.stackTrace.joinToString("\n")}"
        }
        // Plain JVM unit tests do not provide android.util.Log. Keep logging non-throwing so
        // diagnostics can never change SDK behavior.
        runCatching { Log.println(level.priority, TAG, complete) }.onFailure {
            if (level.priority >= Log.ERROR) System.err.println("$TAG $complete") else println("$TAG $complete")
        }
    }

    private const val TAG = "Engage"
}
