package io.engage.sdk.messagecenter.divkit.render

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DivKitAppearanceTest {
    @Test
    fun `maps Android night mode to the public DivKit appearance vocabulary`() {
        assertEquals(
            MessageCenterDivKitAppearanceValue.SYSTEM_DARK,
            messageCenterDivKitAppearanceValue(Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(
            MessageCenterDivKitAppearanceValue.SYSTEM_LIGHT,
            messageCenterDivKitAppearanceValue(Configuration.UI_MODE_NIGHT_NO),
        )
        assertEquals("system_dark", MessageCenterDivKitAppearanceValue.SYSTEM_DARK.wireValue)
        assertEquals("system_light", MessageCenterDivKitAppearanceValue.SYSTEM_LIGHT.wireValue)
    }
}
