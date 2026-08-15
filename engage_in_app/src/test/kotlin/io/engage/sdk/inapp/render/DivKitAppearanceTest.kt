package io.engage.sdk.inapp.render

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DivKitAppearanceTest {
    @Test
    fun `maps Android night mode to the public DivKit appearance vocabulary`() {
        assertEquals(
            EngageDivKitAppearanceValue.SYSTEM_DARK,
            divKitAppearanceValue(Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(
            EngageDivKitAppearanceValue.SYSTEM_LIGHT,
            divKitAppearanceValue(Configuration.UI_MODE_NIGHT_NO),
        )
        assertEquals("system_dark", EngageDivKitAppearanceValue.SYSTEM_DARK.wireValue)
        assertEquals("system_light", EngageDivKitAppearanceValue.SYSTEM_LIGHT.wireValue)
    }
}
