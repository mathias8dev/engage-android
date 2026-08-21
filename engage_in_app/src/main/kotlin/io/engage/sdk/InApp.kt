package io.engage.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.engage.sdk.inapp.EngageInAppModule
import io.engage.sdk.inapp.EngagePlacement
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

public enum class OverlayFormat { BANNER, MODAL, FULLSCREEN }
public enum class OverlayPosition { TOP, CENTER, BOTTOM }
public enum class BackdropPolicy { NONE, DIMMED }
public enum class DismissalPolicy { REQUIRED_ACTION, USER_DISMISSIBLE, AUTO_DISMISS }
public enum class InAppAnimation { NONE, FADE, SLIDE, SCALE }
public enum class EmptyStatePolicy { COLLAPSE, RESERVE_SPACE }
public enum class InAppContentType { SCENE, IMAGE, WEB, SURVEY }

public sealed interface PresentationSpec

public data class OverlayPresentation(
    val format: OverlayFormat,
    val position: OverlayPosition?,
    val backdrop: BackdropPolicy,
    val dismissal: DismissalPolicy,
    val animation: InAppAnimation,
    val autoDismissAfterSeconds: Int? = null,
) : PresentationSpec

public data class EmbeddedPresentation(
    val placementKey: String,
    val emptyState: EmptyStatePolicy = EmptyStatePolicy.COLLAPSE,
) : PresentationSpec

public data class InAppContent(
    val experienceId: String,
    val messageId: String,
    val variantId: String?,
    val type: InAppContentType,
    val payload: JsonObject,
    val presentation: PresentationSpec,
    val automation: InAppAutomationContext? = null,
)

public data class InAppAutomationContext(
    val automationId: String,
    val automationVersion: Int,
    val runId: String,
    val nodeId: String,
    val experienceVersion: Int,
    val outcomeKeys: Set<String>,
)

public enum class DisplayDecision { ALLOW, DEFER, DISCARD }

public fun interface InAppOverlayDisplayDelegate {
    public fun decide(candidate: InAppContent): DisplayDecision
}

public interface InAppOverlays {
    var displayDelegate: InAppOverlayDisplayDelegate?
    fun pause()
    fun resume()
}

public interface InApp {
    val overlays: InAppOverlays
    fun placement(key: String): StateFlow<InAppContent?>
    suspend fun trackOutcome(
        content: InAppContent,
        key: String,
        properties: JsonObject = JsonObject(emptyMap()),
    ): Boolean = trackOutcome(content.messageId, key, properties)
    suspend fun trackOutcome(
        messageId: String,
        key: String,
        properties: JsonObject = JsonObject(emptyMap()),
    ): Boolean
}

public val Engage.inApp: InApp get() = EngageInAppModule.requireApi()

@Composable
public fun EngageInAppPlacement(
    key: String,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
) {
    EngagePlacement(key, modifier, placeholder)
}
