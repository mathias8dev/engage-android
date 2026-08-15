package io.engage.sdk.core.application

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshSchedulerTest {
    @Test
    fun `mutations are debounced and periodic refresh only runs in foreground`() = runTest {
        val interval = MutableStateFlow(2L)
        var refreshes = 0
        val scheduler = RefreshScheduler(
            scope = backgroundScope,
            refreshAfterSeconds = interval,
            refresh = { refreshes += 1 },
            mutationDelayMillis = 1_000,
            minimumIntervalSeconds = 1,
            maximumIntervalSeconds = 60,
        )
        runCurrent()

        repeat(3) { scheduler.requestAfterMutation() }
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, refreshes)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, refreshes)

        scheduler.setForeground(true)
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, refreshes)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, refreshes)

        scheduler.setForeground(false)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, refreshes)
    }
}
