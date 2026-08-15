package io.engage.sdk.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.engage.sdk.EngageLogger

public class EngageFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        EngageLogger.info("Push", "Firebase issued a token; value redacted")
        EngagePushModule.onNewToken(token)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onMessageReceived(message: RemoteMessage) {
        EngageLogger.debug("Push", "Firebase message received dataKeys=${message.data.keys.sorted()}")
        PushWorkScheduler.enqueue(applicationContext, message.data)
    }
}
