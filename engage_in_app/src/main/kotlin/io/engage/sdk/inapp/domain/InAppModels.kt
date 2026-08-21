package io.engage.sdk.inapp.domain

import io.engage.sdk.InAppContentType
import io.engage.sdk.InAppAutomationContext
import io.engage.sdk.PresentationSpec
import kotlinx.serialization.json.JsonObject
import java.time.Instant

internal enum class TriggerType { APP_OPEN, SCREEN_VIEW, EVENT, SESSION_COUNT, APP_UPDATE }
internal enum class ConflictPolicy { REPLACE_LOWER_PRIORITY, QUEUE, SKIP }

internal data class Trigger(
    val id: String,
    val type: TriggerType,
    val delaySeconds: Int,
    val screenName: String?,
    val eventName: String?,
    val minimumSessions: Int?,
    val versionConstraint: String?,
)

internal data class DisplayPolicy(
    val maxTotalImpressions: Int?,
    val maxImpressionsPerSession: Int?,
    val maxImpressionsPerDay: Int?,
    val cooldownMinutes: Int?,
    val redisplayAfterDismissal: Boolean,
)

internal data class ContentVariant(
    val id: String?,
    val key: String?,
    val locale: String,
    val allocationPercentage: Int,
    val type: InAppContentType,
    val payload: JsonObject,
    val presentation: PresentationSpec,
)

internal data class Campaign(
    val key: String,
    val revision: Long,
    val experienceId: String,
    val messageId: String,
    val publishedAt: Instant,
    val availableAt: Instant?,
    val expiresAt: Instant?,
    val triggers: List<Trigger>,
    val startAt: Instant?,
    val endAt: Instant?,
    val priority: Int,
    val conflictPolicy: ConflictPolicy,
    val displayPolicy: DisplayPolicy,
    val defaultLocale: String,
    val fallbackLocale: String?,
    val variants: List<ContentVariant>,
    val personalization: InAppPersonalizationContext = InAppPersonalizationContext(),
    val oneShot: Boolean,
    val automation: InAppAutomationContext? = null,
)

internal data class ResolvedContent(
    val campaign: Campaign,
    val variant: ContentVariant,
    val payload: JsonObject,
    val matchedTrigger: Trigger?,
) {
    val instanceKey: String =
        "${campaign.key}:${campaign.revision}:${variant.id ?: variant.key.orEmpty()}:${matchedTrigger?.id.orEmpty()}"
}

internal data class ImpressionHistory(
    val total: Int = 0,
    val sessionId: Long = -1,
    val sessionCount: Int = 0,
    val day: String? = null,
    val dayCount: Int = 0,
    val lastImpressionAt: Instant? = null,
    val lastDismissedAt: Instant? = null,
)
