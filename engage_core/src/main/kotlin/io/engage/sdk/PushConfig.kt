package io.engage.sdk

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes

public data class PushConfig(
    val foregroundPresentation: ForegroundPresentation = ForegroundPresentation.SHOW,
    val android: AndroidPushConfig? = null,
)

public enum class ForegroundPresentation {
    SHOW,
    SILENT,
}

public data class AndroidPushConfig(
    @DrawableRes val smallIcon: Int,
    @ColorRes val accentColor: Int? = null,
    val defaultChannelKey: String,
    val channels: List<AndroidPushChannel>,
    val categories: List<AndroidPushCategory> = emptyList(),
)

public data class AndroidPushCategory(
    val key: String,
    val actions: List<AndroidPushAction>,
)

public data class AndroidPushAction(
    val key: String,
    @StringRes val title: Int,
    val opensApp: Boolean = false,
)

public data class AndroidPushChannel(
    val key: String,
    @StringRes val name: Int,
    @StringRes val description: Int? = null,
    val importance: NotificationImportance = NotificationImportance.DEFAULT,
    val showBadge: Boolean = true,
    val sound: AndroidPushSound = AndroidPushSound.Default,
)

public enum class NotificationImportance {
    MIN,
    LOW,
    DEFAULT,
    HIGH,
    MAX,
}

public sealed interface AndroidPushSound {
    public data object Default : AndroidPushSound
    public data object Silent : AndroidPushSound
    public data class Resource(@RawRes val resourceId: Int) : AndroidPushSound
}
