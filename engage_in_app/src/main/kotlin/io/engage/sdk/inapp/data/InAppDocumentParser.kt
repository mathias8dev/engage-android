package io.engage.sdk.inapp.data

import io.engage.sdk.BackdropPolicy
import io.engage.sdk.EngageLogger
import io.engage.sdk.DismissalPolicy
import io.engage.sdk.EmbeddedPresentation
import io.engage.sdk.EmptyStatePolicy
import io.engage.sdk.InAppAnimation
import io.engage.sdk.InAppContentType
import io.engage.sdk.OverlayFormat
import io.engage.sdk.OverlayPosition
import io.engage.sdk.OverlayPresentation
import io.engage.sdk.PresentationSpec
import io.engage.sdk.inapp.domain.Campaign
import io.engage.sdk.inapp.domain.ConflictPolicy
import io.engage.sdk.inapp.domain.ContentVariant
import io.engage.sdk.inapp.domain.DisplayPolicy
import io.engage.sdk.inapp.domain.Trigger
import io.engage.sdk.inapp.domain.TriggerType
import io.engage.sdk.spi.EngageRemoteDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

internal object InAppDocumentParser {
    fun parse(document: EngageRemoteDocument): Campaign? = runCatching {
        val payload = document.payload
        if (payload.string("source") == "AUTOMATION") parseAutomation(document) else parseExperience(document)
    }.onSuccess { campaign ->
        EngageLogger.debug(
            "InAppParser",
            "document parsed key=${document.key} revision=${document.revision} messageId=${campaign.messageId} " +
                "variants=${campaign.variants.size}",
        )
    }.onFailure { error ->
        EngageLogger.warn(
            "InAppParser",
            "document rejected key=${document.key} revision=${document.revision}",
            error,
        )
    }.getOrNull()

    private fun parseExperience(document: EngageRemoteDocument): Campaign {
        val payload = document.payload
        val experienceId = payload.requiredString("experienceId")
        val definition = payload.requiredObject("definition")
        val schedule = definition.requiredObject("schedule")
        val variants = definition.requiredArray("contentVariants").map { parseVariant(it.requiredObject()) }
        require(variants.isNotEmpty())
        return Campaign(
            key = document.key,
            revision = document.revision,
            experienceId = experienceId,
            messageId = "$experienceId:${payload.int("version") ?: document.revision}",
            publishedAt = payload.instant("publishedAt") ?: Instant.EPOCH,
            availableAt = null,
            expiresAt = null,
            triggers = definition.requiredArray("triggers").map { parseTrigger(it.requiredObject()) },
            startAt = schedule.instant("startAt"),
            endAt = schedule.instant("endAt"),
            priority = definition.int("priority") ?: 0,
            conflictPolicy = definition.enum("conflictPolicy", ConflictPolicy.QUEUE),
            displayPolicy = parseDisplayPolicy(definition.requiredObject("displayPolicy")),
            defaultLocale = definition.string("defaultLocale") ?: "und",
            fallbackLocale = definition.string("fallbackLocale"),
            variants = variants,
            oneShot = false,
        )
    }

    private fun parseAutomation(document: EngageRemoteDocument): Campaign {
        val payload = document.payload
        val experienceId = payload.requiredString("experienceId")
        val content = payload.requiredObject("content")
        val variant = ContentVariant(
            id = null,
            key = null,
            locale = "und",
            allocationPercentage = 100,
            type = content.enum("type", InAppContentType.SCENE),
            payload = content.requiredObject("payload"),
            presentation = parsePresentation(payload.requiredObject("presentation")),
        )
        return Campaign(
            key = document.key,
            revision = document.revision,
            experienceId = experienceId,
            messageId = payload.requiredString("messageId"),
            publishedAt = payload.instant("availableAt") ?: Instant.EPOCH,
            availableAt = payload.instant("availableAt"),
            expiresAt = payload.instant("expiresAt"),
            triggers = emptyList(),
            startAt = null,
            endAt = null,
            priority = 0,
            conflictPolicy = ConflictPolicy.QUEUE,
            displayPolicy = DisplayPolicy(1, 1, 1, null, false),
            defaultLocale = "und",
            fallbackLocale = null,
            variants = listOf(variant),
            oneShot = true,
        )
    }

    private fun parseTrigger(value: JsonObject) = Trigger(
        id = value.requiredString("id"),
        type = value.enum("type", TriggerType.APP_OPEN),
        delaySeconds = (value.int("delaySeconds") ?: 0).coerceAtLeast(0),
        screenName = value.string("screenName"),
        eventName = value.string("eventName"),
        minimumSessions = value.int("minimumSessions"),
        versionConstraint = value.string("versionConstraint"),
    )

    private fun parseDisplayPolicy(value: JsonObject) = DisplayPolicy(
        maxTotalImpressions = value.int("maxTotalImpressions"),
        maxImpressionsPerSession = value.int("maxImpressionsPerSession"),
        maxImpressionsPerDay = value.int("maxImpressionsPerDay"),
        cooldownMinutes = value.int("cooldownMinutes"),
        redisplayAfterDismissal = value.boolean("redisplayAfterDismissal") ?: false,
    )

    private fun parseVariant(value: JsonObject): ContentVariant {
        val content = value.requiredObject("content")
        return ContentVariant(
            id = value.string("id"),
            key = value.string("key"),
            locale = value.string("locale") ?: "und",
            allocationPercentage = (value.int("allocationPercentage") ?: 0).coerceIn(0, 100),
            type = content.enum("type", InAppContentType.SCENE),
            payload = content.requiredObject("payload"),
            presentation = parsePresentation(value.requiredObject("presentation")),
        )
    }

    private fun parsePresentation(value: JsonObject): PresentationSpec = when (value.requiredString("mode")) {
        "OVERLAY" -> value.requiredObject("overlay").let { overlay ->
            OverlayPresentation(
                format = overlay.enum("format", OverlayFormat.MODAL),
                position = overlay.string("position")?.let(OverlayPosition::valueOf),
                backdrop = overlay.enum("backdrop", BackdropPolicy.NONE),
                dismissal = overlay.enum("dismissal", DismissalPolicy.USER_DISMISSIBLE),
                animation = overlay.enum("animation", InAppAnimation.NONE),
                autoDismissAfterSeconds = overlay.int("autoDismissAfterSeconds"),
            )
        }
        "EMBEDDED" -> value.requiredObject("embedded").let { embedded ->
            EmbeddedPresentation(
                placementKey = embedded.requiredString("placementKey"),
                emptyState = embedded.enum("emptyState", EmptyStatePolicy.COLLAPSE),
            )
        }
        else -> error("Unsupported presentation mode")
    }
}

private fun JsonElement.requiredObject(): JsonObject = this as? JsonObject ?: error("Expected object")
private fun JsonObject.requiredObject(key: String): JsonObject = get(key) as? JsonObject ?: error("Expected object: $key")
private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray ?: error("Expected array: $key")
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.requiredString(key: String): String = requireNotNull(string(key)) { "Missing $key" }
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.instant(key: String): Instant? = get(key).let { element ->
    if (element == null || element is JsonNull) null else string(key)?.let(Instant::parse)
}
private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T =
    string(key)?.let { enumValueOf<T>(it) } ?: default
