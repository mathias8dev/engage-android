package io.engage.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class InAppPresentationTest {
    @Test
    fun `embedded placement is independent from overlay format`() {
        val embedded = EmbeddedPresentation("home.hero")
        assertEquals("home.hero", embedded.placementKey)
        assertEquals(EmptyStatePolicy.COLLAPSE, embedded.emptyState)
    }
}
