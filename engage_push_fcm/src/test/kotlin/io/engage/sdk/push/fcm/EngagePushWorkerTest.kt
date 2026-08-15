package io.engage.sdk.push.fcm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngagePushWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun `FCM callback persists one unique work request before returning`() {
        val payload = mapOf(
            "engage_delivery_id" to "delivery-durable-1",
            "engage_message_id" to "message-1",
            "engage_title" to "Hello",
            "engage_body" to "World",
            "custom" to "value",
        )

        assertTrue(PushWorkScheduler.enqueue(context, payload))
        assertTrue(PushWorkScheduler.enqueue(context, payload))

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PushWorkScheduler.workName("delivery-durable-1"))
            .get()
        assertEquals(1, work.size)
    }
}
