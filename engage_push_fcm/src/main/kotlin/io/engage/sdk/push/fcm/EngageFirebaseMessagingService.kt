package io.engage.sdk.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

public class EngageFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Unit
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onMessageReceived(message: RemoteMessage) = Unit
}
