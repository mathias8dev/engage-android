package io.engage.sdk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class EngageConfigTest {
    @Test
    fun `uses production mobile edge by default`() {
        val config = EngageConfig(appKey = "eng_app_test")

        assertEquals(URI.create("https://api.engage.io/v1/"), config.endpoint)
    }
}

