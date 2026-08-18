package io.engage.sdk.messagecenter.divkit.render

import io.engage.sdk.InboxRenderingSurface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxVisibilityContractTest {
    @Test
    fun `summary visibility never marks the entry read`() {
        assertFalse(
            shouldReportContentVisibility(
                InboxRenderingSurface.SUMMARY,
                requested = true,
                rendered = true,
            ),
        )
    }

    @Test
    fun `only a successfully rendered detail may confirm that the entry was opened`() {
        assertTrue(
            shouldReportContentVisibility(
                InboxRenderingSurface.DETAIL,
                requested = true,
                rendered = true,
            ),
        )
        assertFalse(
            shouldReportContentVisibility(
                InboxRenderingSurface.DETAIL,
                requested = false,
                rendered = true,
            ),
        )
        assertFalse(
            shouldReportContentVisibility(
                InboxRenderingSurface.DETAIL,
                requested = true,
                rendered = false,
            ),
        )
    }
}
