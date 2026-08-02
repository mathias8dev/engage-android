package io.engage.sdk.inapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

@Composable
internal fun EngagePlacement(
    key: String,
    modifier: Modifier,
    placeholder: (@Composable () -> Unit)?,
) {
    val content by EngageInAppModule.requireApi().placement(key).collectAsState()
    if (content == null) placeholder?.invoke()
}

