package io.engage.sdk.push.fcm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.engage.sdk.EngageLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

public class EngagePushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngageLogger.debug("Push", "notification action broadcast received")
        val pending = goAsync()
        receiverScope.launch {
            try {
                EngagePushModule.onAction(intent)
            } catch (error: Throwable) {
                EngageLogger.error("Push", "notification action processing failed", error)
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
