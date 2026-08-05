package io.engage.sdk.core.application

import io.engage.sdk.EngageLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class RefreshScheduler(
    private val scope: CoroutineScope,
    refreshAfterSeconds: Flow<Long>,
    private val refresh: suspend () -> Unit,
    private val mutationDelayMillis: Long = DEFAULT_MUTATION_DELAY_MILLIS,
    private val minimumIntervalSeconds: Long = MINIMUM_INTERVAL_SECONDS,
    private val maximumIntervalSeconds: Long = MAXIMUM_INTERVAL_SECONDS,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val foreground = MutableStateFlow(false)
    private var mutationJob: Job? = null

    init {
        scope.launch {
            for (ignored in requests) {
                EngageLogger.verbose("Refresh", "scheduled refresh started")
                try {
                    refresh()
                    EngageLogger.verbose("Refresh", "scheduled refresh finished")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // The transport layer owns the detailed stack trace. Keep this boundary log
                    // concise so one failed refresh does not print the same exception twice.
                    EngageLogger.warn(
                        "Refresh",
                        "scheduled refresh failed errorType=${error.javaClass.name}",
                    )
                }
            }
        }
        scope.launch {
            combine(
                foreground,
                refreshAfterSeconds.distinctUntilChanged(),
            ) { active, seconds -> active to seconds.coerceIn(minimumIntervalSeconds, maximumIntervalSeconds) }
                .collectLatest { (active, seconds) ->
                    if (!active) return@collectLatest
                    EngageLogger.debug("Refresh", "foreground cadence seconds=$seconds")
                    while (currentCoroutineContext().isActive) {
                        delay(seconds * MILLIS_PER_SECOND)
                        requestImmediate()
                    }
                }
        }
    }

    @Synchronized
    fun requestImmediate() {
        EngageLogger.verbose("Refresh", "immediate refresh requested")
        mutationJob?.cancel()
        mutationJob = null
        requests.trySend(Unit)
    }

    @Synchronized
    fun requestAfterMutation() {
        if (mutationJob?.isActive == true) {
            EngageLogger.verbose("Refresh", "mutation refresh already scheduled")
            return
        }
        EngageLogger.verbose("Refresh", "mutation refresh scheduled delayMillis=$mutationDelayMillis")
        mutationJob = scope.launch {
            delay(mutationDelayMillis)
            requests.trySend(Unit)
        }
    }

    fun setForeground(active: Boolean) {
        foreground.value = active
        EngageLogger.debug("Refresh", "foreground=$active")
    }

    private companion object {
        const val DEFAULT_MUTATION_DELAY_MILLIS = 1_000L
        const val MINIMUM_INTERVAL_SECONDS = 30L
        const val MAXIMUM_INTERVAL_SECONDS = 24 * 60 * 60L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
