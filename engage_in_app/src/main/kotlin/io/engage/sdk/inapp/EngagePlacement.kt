package io.engage.sdk.inapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import io.engage.sdk.EmbeddedPresentation
import io.engage.sdk.EngageLogger
import io.engage.sdk.EmptyStatePolicy
import io.engage.sdk.inapp.render.EngageContentView

@Composable
internal fun EngagePlacement(
    key: String,
    modifier: Modifier,
    placeholder: (@Composable () -> Unit)?,
) {
    val content by EngageInAppModule.requireApi().placement(key).collectAsState()
    var reservedHeightPx by remember(key) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val current = content
    if (current == null) {
        SideEffect { EngageLogger.verbose("InApp.Placement", "empty key=$key reservedHeightPx=$reservedHeightPx") }
        if (placeholder != null) {
            placeholder.invoke()
        } else if (reservedHeightPx > 0) {
            Spacer(modifier.height(with(density) { reservedHeightPx.toDp() }))
        }
    } else {
        val reserveSpace = (current.presentation as? EmbeddedPresentation)?.emptyState == EmptyStatePolicy.RESERVE_SPACE
        SideEffect {
            if (!reserveSpace) reservedHeightPx = 0
            EngageLogger.debug(
                "InApp.Placement",
                "rendering key=$key messageId=${current.messageId} reserveSpace=$reserveSpace",
            )
        }
        key(current.experienceId, current.messageId, current.variantId) {
            AndroidView(
                factory = { context ->
                    EngageLogger.debug("InApp.Placement", "view created key=$key messageId=${current.messageId}")
                    EngageContentView(context, current, EngageInAppModule.requireRenderCallbacks())
                },
                modifier = if (reserveSpace) {
                    modifier.onSizeChanged {
                        reservedHeightPx = it.height
                        EngageLogger.verbose("InApp.Placement", "size changed key=$key heightPx=${it.height}")
                    }
                } else {
                    modifier
                },
            )
        }
    }
}
