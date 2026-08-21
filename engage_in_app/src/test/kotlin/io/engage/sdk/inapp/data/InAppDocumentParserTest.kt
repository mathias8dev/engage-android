package io.engage.sdk.inapp.data

import io.engage.sdk.EmbeddedPresentation
import io.engage.sdk.InAppContentType
import io.engage.sdk.spi.EngageRemoteDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InAppDocumentParserTest {
    @Test
    fun `parses an experience document into a typed campaign`() {
        val document = EngageRemoteDocument(
            key = "welcome",
            revision = 4,
            payload = Json.parseToJsonElement(EXPERIENCE).jsonObject,
        )

        val campaign = requireNotNull(InAppDocumentParser.parse(document))

        assertEquals("experience-1", campaign.experienceId)
        assertEquals("experience-1:4", campaign.messageId)
        assertEquals(1, campaign.triggers.size)
        assertEquals(InAppContentType.SCENE, campaign.variants.single().type)
        assertEquals("home.hero", (campaign.variants.single().presentation as EmbeddedPresentation).placementKey)
        assertEquals(
            JsonPrimitive("Ada"),
            campaign.personalization.values["profile"]?.jsonObject?.get("first_name"),
        )
        assertEquals(
            JsonPrimitive("friend"),
            campaign.personalization.fallbacks["profile"]?.jsonObject?.get("first_name"),
        )
    }

    @Test
    fun `rejects malformed remote documents without crashing the host app`() {
        val malformed = EngageRemoteDocument("bad", 1, Json.parseToJsonElement("{}").jsonObject)
        assertEquals(null, InAppDocumentParser.parse(malformed))
    }

    @Test
    fun `rejects auto dismiss overlays without a positive duration`() {
        val malformed = EngageRemoteDocument(
            "bad-auto-dismiss",
            1,
            Json.parseToJsonElement(
                EXPERIENCE.replace(
                    "{\"mode\":\"EMBEDDED\",\"embedded\":{\"placementKey\":\"home.hero\",\"emptyState\":\"COLLAPSE\"}}",
                    "{\"mode\":\"OVERLAY\",\"overlay\":{\"dismissal\":\"AUTO_DISMISS\"}}",
                ),
            ).jsonObject,
        )

        assertEquals(null, InAppDocumentParser.parse(malformed))
    }

    @Test
    fun `preserves automation routing context and declared outcomes`() {
        val document = EngageRemoteDocument(
            key = "automation:message-1",
            revision = 8,
            payload = Json.parseToJsonElement(
                """
                {
                  "source":"AUTOMATION",
                  "experienceId":"experience-1",
                  "experienceVersion":4,
                  "messageId":"message-1",
                  "automationId":"automation-1",
                  "automationVersion":3,
                  "automationRunId":"run-1",
                  "automationNodeId":"node-1",
                  "outcomeKeys":["accepted","declined"],
                  "content":{"type":"SCENE","payload":{"card":{}}},
                  "presentation":{"mode":"EMBEDDED","embedded":{"placementKey":"home.hero"}},
                  "availableAt":"2026-01-01T00:00:00Z",
                  "expiresAt":"2027-01-01T00:00:00Z"
                }
                """.trimIndent(),
            ).jsonObject,
        )

        val automation = requireNotNull(InAppDocumentParser.parse(document)).automation

        requireNotNull(automation)
        assertEquals("automation-1", automation.automationId)
        assertEquals(3, automation.automationVersion)
        assertEquals("run-1", automation.runId)
        assertEquals("node-1", automation.nodeId)
        assertEquals(4, automation.experienceVersion)
        assertEquals(setOf("accepted", "declined"), automation.outcomeKeys)
    }

    @Test
    fun `rejects automation documents with invalid outcome keys without crashing the host app`() {
        val malformed = EngageRemoteDocument(
            key = "automation:invalid-outcome",
            revision = 1,
            payload = Json.parseToJsonElement(
                AUTOMATION.replace(
                    "\"outcomeKeys\":[\"accepted\",\"declined\"]",
                    "\"outcomeKeys\":[\"accepted\",\"Invalid Key\"]",
                ),
            ).jsonObject,
        )

        assertEquals(null, InAppDocumentParser.parse(malformed))
    }

    private companion object {
        val AUTOMATION = """
            {
              "source":"AUTOMATION",
              "experienceId":"experience-1",
              "experienceVersion":4,
              "messageId":"message-1",
              "automationId":"automation-1",
              "automationVersion":3,
              "automationRunId":"run-1",
              "automationNodeId":"node-1",
              "outcomeKeys":["accepted","declined"],
              "content":{"type":"SCENE","payload":{"card":{}}},
              "presentation":{"mode":"EMBEDDED","embedded":{"placementKey":"home.hero"}},
              "availableAt":"2026-01-01T00:00:00Z",
              "expiresAt":"2027-01-01T00:00:00Z"
            }
        """.trimIndent()

        val EXPERIENCE = """
            {
              "experienceId": "experience-1",
              "version": 4,
              "publishedAt": "2026-01-01T00:00:00Z",
              "personalization": {
                "values": {"profile":{"first_name":"Ada"}},
                "fallbacks": {"profile":{"first_name":"friend"}}
              },
              "definition": {
                "triggers": [{"id":"open","type":"APP_OPEN","delaySeconds":0}],
                "schedule": {"startAt":null,"endAt":null,"timezoneMode":"ENVIRONMENT"},
                "priority": 10,
                "conflictPolicy": "QUEUE",
                "displayPolicy": {
                  "maxTotalImpressions": 3,
                  "maxImpressionsPerSession": 1,
                  "maxImpressionsPerDay": 2,
                  "cooldownMinutes": 5,
                  "redisplayAfterDismissal": false
                },
                "defaultLocale": "en",
                "fallbackLocale": null,
                "contentVariants": [{
                  "id":"variant-1","key":"control","locale":"en","allocationPercentage":100,
                  "content":{"type":"SCENE","payload":{"card":{}}},
                  "presentation":{"mode":"EMBEDDED","embedded":{"placementKey":"home.hero","emptyState":"COLLAPSE"}}
                }]
              }
            }
        """.trimIndent()
    }
}
