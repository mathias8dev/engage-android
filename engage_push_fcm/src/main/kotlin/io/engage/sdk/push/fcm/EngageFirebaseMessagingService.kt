package io.engage.sdk.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

public class EngageFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        EngagePushModule.onNewToken(token)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onMessageReceived(message: RemoteMessage) {
        EngagePushModule.onMessage(message)
    }
}
