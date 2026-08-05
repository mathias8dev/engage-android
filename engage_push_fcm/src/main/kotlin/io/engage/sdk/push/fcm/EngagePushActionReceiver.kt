package io.engage.sdk.push.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.engage.sdk.EngageLogger

public class EngagePushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngageLogger.debug("Push", "notification action broadcast received")
        EngagePushModule.onAction(intent)
    }
}
