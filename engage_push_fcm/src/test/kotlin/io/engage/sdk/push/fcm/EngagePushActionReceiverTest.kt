package io.engage.sdk.push.fcm

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class EngagePushActionReceiverTest {
    @Test
    fun `receiver keeps the broadcast alive for asynchronous durable work`() {
        val receiver = EngagePushActionReceiver()

        receiver.onReceive(ApplicationProvider.getApplicationContext<Context>(), Intent())

        assertTrue(shadowOf(receiver).wentAsync())
    }
}
