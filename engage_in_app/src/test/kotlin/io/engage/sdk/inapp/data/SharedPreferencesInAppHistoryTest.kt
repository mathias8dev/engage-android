package io.engage.sdk.inapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesInAppHistoryTest {
    @Test
    fun `wipe is visible to a newly opened history before returning`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = SharedPreferencesInAppHistory(context) { 41 }
        history.recordImpression("campaign", Instant.parse("2026-08-06T12:00:00Z"))

        history.clearAll()

        assertEquals(0, SharedPreferencesInAppHistory(context) { 41 }.history("campaign").total)
    }
}
