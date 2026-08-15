package io.engage.sdk.inapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderedVisibilityGateTest {
    @Test
    fun `visibility is reported only after content is ready and only once`() {
        val gate = RenderedVisibilityGate()

        assertFalse(gate.shouldReport(isVisible = true))
        gate.markReady()
        assertFalse(gate.shouldReport(isVisible = false))
        assertTrue(gate.shouldReport(isVisible = true))
        assertFalse(gate.shouldReport(isVisible = true))
    }

    @Test
    fun `a failed render cannot become visible from a late ready callback`() {
        val gate = RenderedVisibilityGate()

        gate.markFailed()
        gate.markReady()

        assertFalse(gate.shouldReport(isVisible = true))
    }
}
