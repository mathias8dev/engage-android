package io.engage.sdk.inapp

import io.engage.sdk.InApp
import io.engage.sdk.InAppContent
import io.engage.sdk.InAppOverlayDisplayDelegate
import io.engage.sdk.InAppOverlays
import io.engage.sdk.spi.EngageModuleContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class DefaultInApp(private val context: EngageModuleContext) : InApp {
    override val overlays: InAppOverlays = DefaultOverlays()
    private val placements = ConcurrentHashMap<String, MutableStateFlow<InAppContent?>>()

    override fun placement(key: String): StateFlow<InAppContent?> {
        require(PLACEMENT_KEY.matches(key)) { "Placement keys must match ${PLACEMENT_KEY.pattern}" }
        return placements.getOrPut(key) { MutableStateFlow(null) }
    }

    private companion object {
        val PLACEMENT_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

private class DefaultOverlays : InAppOverlays {
    private val pauses = AtomicInteger()
    override var displayDelegate: InAppOverlayDisplayDelegate? = null

    override fun pause() {
        pauses.incrementAndGet()
    }

    override fun resume() {
        while (true) {
            val current = pauses.get()
            if (current == 0 || pauses.compareAndSet(current, current - 1)) return
        }
    }
}
