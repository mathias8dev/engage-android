package io.engage.sdk.push.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

public class EngagePushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngagePushModule.onAction(intent)
    }
}
