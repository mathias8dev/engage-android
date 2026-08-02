package io.engage.sdk.core.data

import io.engage.sdk.core.domain.BootstrapRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OkHttpMobileEdgeApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpMobileEdgeApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = OkHttpMobileEdgeApi(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `bootstrap uses public app key and decodes opaque credentials`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """
                {
                  "installationId":"installation-1",
                  "credential":"credential",
                  "revocationCredential":"revocation",
                  "recoveryToken":"recovery",
                  "generation":0,
                  "privacy":"OPTED_IN",
                  "pushSubscription":"OPTED_IN",
                  "serverTime":"2026-08-02T10:00:00Z"
                }
                """.trimIndent(),
            ),
        )

        val session = api.bootstrap(
            endpoint = server.url("/v1/").toUri(),
            appKey = "eng_app_android",
            request = BootstrapRequest(
                locale = "fr-FR",
                timezone = "Europe/Paris",
                sdkVersion = "1.0.0",
                appVersion = "2.0.0",
            ),
        )

        assertEquals("installation-1", session.installationId)
        val request = server.takeRequest()
        assertEquals("/v1/sdk/installations", request.path)
        assertEquals("eng_app_android", request.getHeader("X-Engage-App-Key"))
    }
}

