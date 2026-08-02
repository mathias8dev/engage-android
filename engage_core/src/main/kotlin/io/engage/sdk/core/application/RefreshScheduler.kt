package io.engage.sdk.core.application

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
            for (ignored in requests) runCatching { refresh() }
        }
        scope.launch {
            combine(
                foreground,
                refreshAfterSeconds.distinctUntilChanged(),
            ) { active, seconds -> active to seconds.coerceIn(minimumIntervalSeconds, maximumIntervalSeconds) }
                .collectLatest { (active, seconds) ->
                    if (!active) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        delay(seconds * MILLIS_PER_SECOND)
                        requestImmediate()
                    }
                }
        }
    }

    @Synchronized
    fun requestImmediate() {
        mutationJob?.cancel()
        mutationJob = null
        requests.trySend(Unit)
    }

    @Synchronized
    fun requestAfterMutation() {
        if (mutationJob?.isActive == true) return
        mutationJob = scope.launch {
            delay(mutationDelayMillis)
            requests.trySend(Unit)
        }
    }

    fun setForeground(active: Boolean) {
        foreground.value = active
    }

    private companion object {
        const val DEFAULT_MUTATION_DELAY_MILLIS = 1_000L
        const val MINIMUM_INTERVAL_SECONDS = 30L
        const val MAXIMUM_INTERVAL_SECONDS = 24 * 60 * 60L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
