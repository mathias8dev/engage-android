package io.engage.sdk.messagecenter.divkit.render

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MessageCenterHeaderTest {
    @Test
    fun `english summary respects singular and plural counts`() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

        assertEquals("1 message · 1 unread", messageCenterHeaderSummary(resources, 1, 1))
        assertEquals("3 messages · 2 unread", messageCenterHeaderSummary(resources, 3, 2))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `french summary and filters use native resources`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("3 messages · 2 non lus", messageCenterHeaderSummary(context.resources, 3, 2))
        assertEquals("Tous", context.getString(io.engage.sdk.messagecenter.divkit.R.string.engage_message_center_filter_all))
        assertEquals("Non lus", context.getString(io.engage.sdk.messagecenter.divkit.R.string.engage_message_center_filter_unread))
    }
}
