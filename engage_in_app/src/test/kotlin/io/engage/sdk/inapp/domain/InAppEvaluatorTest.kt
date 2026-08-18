package io.engage.sdk.inapp.domain

import io.engage.sdk.BackdropPolicy
import io.engage.sdk.DismissalPolicy
import io.engage.sdk.InAppAnimation
import io.engage.sdk.InAppContentType
import io.engage.sdk.OverlayFormat
import io.engage.sdk.OverlayPresentation
import io.engage.sdk.spi.EngageSignal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class InAppEvaluatorTest {
    private val now = Instant.parse("2026-08-02T12:00:00Z")
    private val history = InMemoryInAppHistory()
    private val evaluator = InAppEvaluator(
        history = history,
        installationSeed = { "installation-1" },
        appVersion = "3.2.0",
        locales = { listOf(Locale.forLanguageTag("fr-FR")) },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `screen trigger selects locale then priority deterministically`() {
        evaluator.replaceCampaigns(
            listOf(
                campaign("low", priority = 1),
                campaign("high", priority = 20),
            ),
        )
        evaluator.onSignal(EngageSignal.ScreenViewed("checkout"))

        val candidates = evaluator.candidates()

        assertEquals(listOf("high", "low"), candidates.map { it.campaign.key })
        assertEquals("fr", candidates.first().variant.locale)
    }

    @Test
    fun `impression cap suppresses a second display in the session`() {
        val campaign = campaign("capped", priority = 1)
        evaluator.replaceCampaigns(listOf(campaign))
        evaluator.onSignal(EngageSignal.AppOpened)
        evaluator.onSignal(EngageSignal.ScreenViewed("checkout"))
        val candidate = evaluator.candidates().single()

        evaluator.recordImpression(candidate)
        evaluator.onSignal(EngageSignal.ScreenViewed("checkout"))

        assertTrue(evaluator.candidates().isEmpty())
    }

    @Test
    fun `wrong screen never becomes eligible`() {
        evaluator.replaceCampaigns(listOf(campaign("checkout-only", 1)))
        evaluator.onSignal(EngageSignal.ScreenViewed("home"))
        assertTrue(evaluator.candidates().isEmpty())
    }

    @Test
    fun `event or screen keeps event eligibility across screen changes and clears only screen matches`() {
        val mixed = campaign("mixed", 1).copy(
            triggers = listOf(
                Trigger("checkout", TriggerType.SCREEN_VIEW, 0, "checkout", null, null, null),
                Trigger("purchase", TriggerType.EVENT, 0, null, "purchase", null, null),
            ),
        )
        evaluator.replaceCampaigns(listOf(mixed))
        evaluator.onSignal(EngageSignal.ScreenViewed("home"))
        evaluator.onSignal(EngageSignal.EventOccurred("purchase", buildJsonObject { put("amount", "42") }))

        val candidate = evaluator.candidates().single()
        assertEquals("purchase", candidate.matchedTrigger?.id)

        evaluator.onSignal(EngageSignal.ScreenViewed("checkout"))
        evaluator.onSignal(EngageSignal.ScreenCleared)
        assertEquals("purchase", evaluator.candidates().single().matchedTrigger?.id)
    }

    @Test
    fun `earliest eligible trigger wins deterministically and preserves event properties`() {
        val mixed = campaign("delayed", 1).copy(
            triggers = listOf(
                Trigger("slow-screen", TriggerType.SCREEN_VIEW, 30, "checkout", null, null, null),
                Trigger("fast-event", TriggerType.EVENT, 0, null, "purchase", null, null),
            ),
            personalization = InAppPersonalizationContext(
                fallbacks = buildJsonObject {
                    put("event", buildJsonObject { put("amount", "fallback") })
                },
            ),
            variants = listOf(variant("fr").copy(payload = buildJsonObject {
                put("text", buildJsonObject { put(IN_APP_VALUE_BINDING_MARKER, "event.amount") })
            })),
        )
        evaluator.replaceCampaigns(listOf(mixed))
        evaluator.onSignal(EngageSignal.ScreenViewed("checkout"))
        evaluator.onSignal(EngageSignal.EventOccurred("purchase", buildJsonObject { put("amount", "42") }))

        val candidate = evaluator.candidates().single()
        assertEquals("fast-event", candidate.matchedTrigger?.id)
        assertEquals("42", candidate.payload["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `runtime type mismatch falls back instead of sending an incompatible value to DivKit`() {
        val personalized = campaign("typed", 1).copy(
            triggers = listOf(Trigger("purchase", TriggerType.EVENT, 0, null, "purchase", null, null)),
            personalization = InAppPersonalizationContext(
                fallbacks = buildJsonObject {
                    put("event", buildJsonObject { put("amount", 7) })
                },
            ),
            variants = listOf(variant("fr").copy(payload = buildJsonObject {
                put("font_size", buildJsonObject { put(IN_APP_VALUE_BINDING_MARKER, "event.amount") })
            })),
        )
        evaluator.replaceCampaigns(listOf(personalized))
        evaluator.onSignal(EngageSignal.EventOccurred("purchase", buildJsonObject { put("amount", "large") }))

        assertEquals(7, evaluator.candidates().single().payload["font_size"]?.jsonPrimitive?.content?.toInt())
    }

    private fun campaign(key: String, priority: Int): Campaign = Campaign(
        key = key,
        revision = 1,
        experienceId = key,
        messageId = "$key:1",
        publishedAt = now.minusSeconds(60),
        availableAt = null,
        expiresAt = null,
        triggers = listOf(Trigger("screen", TriggerType.SCREEN_VIEW, 0, "checkout", null, null, null)),
        startAt = null,
        endAt = null,
        priority = priority,
        conflictPolicy = ConflictPolicy.QUEUE,
        displayPolicy = DisplayPolicy(5, 1, 5, null, true),
        defaultLocale = "en",
        fallbackLocale = "fr",
        variants = listOf(
            variant("en"),
            variant("fr"),
        ),
        oneShot = false,
    )

    private fun variant(locale: String) = ContentVariant(
        id = locale,
        key = locale,
        locale = locale,
        allocationPercentage = 100,
        type = InAppContentType.SCENE,
        payload = buildJsonObject {},
        presentation = OverlayPresentation(
            OverlayFormat.MODAL,
            null,
            BackdropPolicy.DIMMED,
            DismissalPolicy.USER_DISMISSIBLE,
            InAppAnimation.FADE,
        ),
    )
}
