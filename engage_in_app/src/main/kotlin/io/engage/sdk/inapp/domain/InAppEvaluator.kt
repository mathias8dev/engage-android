package io.engage.sdk.inapp.domain

import io.engage.sdk.OverlayPresentation
import io.engage.sdk.EngageLogger
import io.engage.sdk.spi.EngageSignal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.JsonObject

internal interface InAppHistory {
    val sessionId: Long
    val sessionCount: Int
    fun beginSession(): Long
    fun history(campaignKey: String): ImpressionHistory
    fun recordImpression(campaignKey: String, at: Instant)
    fun recordDismiss(campaignKey: String, at: Instant)
}

internal class InMemoryInAppHistory : InAppHistory {
    private val records = mutableMapOf<String, ImpressionHistory>()
    override var sessionId: Long = 0
        private set
    override var sessionCount: Int = 0
        private set

    override fun beginSession(): Long {
        sessionId += 1
        sessionCount += 1
        return sessionId
    }

    override fun history(campaignKey: String): ImpressionHistory = records[campaignKey] ?: ImpressionHistory()

    override fun recordImpression(campaignKey: String, at: Instant) {
        val current = history(campaignKey)
        val day = LocalDate.ofInstant(at, java.time.ZoneOffset.UTC).toString()
        records[campaignKey] = current.copy(
            total = current.total + 1,
            sessionId = sessionId,
            sessionCount = if (current.sessionId == sessionId) current.sessionCount + 1 else 1,
            day = day,
            dayCount = if (current.day == day) current.dayCount + 1 else 1,
            lastImpressionAt = at,
        )
    }

    override fun recordDismiss(campaignKey: String, at: Instant) {
        records[campaignKey] = history(campaignKey).copy(lastDismissedAt = at)
    }
}

internal class InAppEvaluator(
    private val history: InAppHistory,
    private val installationSeed: () -> String,
    private val appVersion: String,
    private val locales: () -> List<Locale> = { Locale.getDefault().let(::listOf) },
    private val clock: Clock = Clock.systemUTC(),
) {
    private var campaigns = emptyList<Campaign>()
    private data class Eligibility(val at: Instant, val trigger: Trigger?, val event: JsonObject)
    private val eligibility = mutableMapOf<String, MutableMap<String, Eligibility>>()
    private var currentScreen: String? = null
    private var isForeground = false

    val foreground: Boolean get() = isForeground

    fun replaceCampaigns(value: List<Campaign>) {
        val previousRevisions = campaigns.associate { it.key to it.revision }
        campaigns = value
        EngageLogger.debug("InAppEvaluator", "campaigns replaced count=${value.size}")
        val keys = value.mapTo(mutableSetOf()) { it.key }
        eligibility.keys.retainAll(keys)
        value.forEach { campaign ->
            if (previousRevisions[campaign.key] != null && previousRevisions[campaign.key] != campaign.revision) {
                eligibility.remove(campaign.key)
            }
            val triggerIds = campaign.triggers.mapTo(mutableSetOf()) { it.id }
            eligibility[campaign.key]?.keys?.retainAll(triggerIds + NO_TRIGGER)
        }
        value.filter { it.triggers.isEmpty() }.forEach { campaign ->
            eligibility.getOrPut(campaign.key, ::mutableMapOf).putIfAbsent(
                NO_TRIGGER,
                Eligibility(campaign.availableAt ?: campaign.publishedAt, null, JsonObject(emptyMap())),
            )
        }
        if (isForeground) {
            val now = clock.instant()
            value.forEach { campaign ->
                campaign.triggers.filter { trigger ->
                    trigger.type == TriggerType.APP_OPEN ||
                        (trigger.type == TriggerType.SESSION_COUNT && history.sessionCount >= (trigger.minimumSessions ?: 1)) ||
                        (trigger.type == TriggerType.APP_UPDATE && versionMatches(appVersion, trigger.versionConstraint)) ||
                        (trigger.type == TriggerType.SCREEN_VIEW && trigger.screenName == currentScreen)
                }.forEach { trigger -> markEligibleIfAbsent(campaign, trigger, now) }
            }
        }
    }

    fun resetContext() {
        EngageLogger.debug("InAppEvaluator", "context reset campaigns=${campaigns.size} eligible=${eligibility.size}")
        campaigns = emptyList()
        eligibility.clear()
        currentScreen = null
        isForeground = false
    }

    fun onSignal(signal: EngageSignal) {
        EngageLogger.verbose("InAppEvaluator", "signal=${signal::class.simpleName}")
        val now = clock.instant()
        when (signal) {
            EngageSignal.AppOpened -> {
                isForeground = true
                history.beginSession()
                EngageLogger.info(
                    "InAppEvaluator",
                    "session started id=${history.sessionId} count=${history.sessionCount}",
                )
                campaigns.forEach { campaign ->
                    campaign.triggers.filter { trigger ->
                        trigger.type == TriggerType.APP_OPEN ||
                            (trigger.type == TriggerType.SESSION_COUNT && history.sessionCount >= (trigger.minimumSessions ?: 1)) ||
                            (trigger.type == TriggerType.APP_UPDATE && versionMatches(appVersion, trigger.versionConstraint))
                    }.forEach { markEligible(campaign, it, now) }
                }
            }
            is EngageSignal.ScreenViewed -> {
                currentScreen = signal.key
                removeScreenEligibilityExcept(signal.key)
                campaigns.forEach { campaign ->
                    campaign.triggers.filter { it.type == TriggerType.SCREEN_VIEW && it.screenName == signal.key }
                        .forEach { markEligible(campaign, it, now) }
                }
            }
            EngageSignal.ScreenCleared -> {
                currentScreen = null
                removeScreenEligibilityExcept(null)
            }
            is EngageSignal.EventOccurred -> campaigns.forEach { campaign ->
                campaign.triggers.filter { it.type == TriggerType.EVENT && it.eventName == signal.name }
                    .forEach { markEligible(campaign, it, now, signal.properties) }
            }
            EngageSignal.AppBackgrounded -> isForeground = false
            EngageSignal.LocalDataWiped -> resetContext()
        }
    }

    fun candidates(): List<ResolvedContent> {
        val now = clock.instant()
        return campaigns.mapNotNull { campaign ->
            val eligible = eligibility[campaign.key]?.values
                ?.asSequence()
                ?.filter { !it.at.isAfter(now) && contextMatches(it.trigger) }
                ?.minWithOrNull(compareBy<Eligibility>({ it.at }, { it.trigger?.id.orEmpty() }))
                ?: return@mapNotNull null
            if (!isScheduled(campaign, now) || !withinLimits(campaign, now)) return@mapNotNull null
            selectVariant(campaign)?.let { variant ->
                val liveValues = InAppPersonalization.values(
                    campaign.personalization.values,
                    eligible.event,
                    appVersion,
                    locales().firstOrNull()?.toLanguageTag() ?: "und",
                    currentScreen,
                    history.sessionCount,
                )
                ResolvedContent(
                    campaign,
                    variant,
                    InAppPersonalization.resolve(variant.payload, liveValues, campaign.personalization.fallbacks),
                    eligible.trigger,
                )
            }
        }.sortedWith(
            compareByDescending<ResolvedContent> { it.campaign.priority }
                .thenBy { it.campaign.publishedAt }
                .thenBy { it.campaign.key },
        ).also { resolved ->
            EngageLogger.debug(
                "InAppEvaluator",
                "candidates evaluated campaigns=${campaigns.size} eligible=${eligibility.values.sumOf { it.size }} " +
                    "resolved=${resolved.map { it.campaign.messageId }}",
            )
        }
    }

    fun nextEvaluationDelayMillis(): Long? {
        val now = clock.instant()
        val boundaries = buildList {
            addAll(eligibility.values.flatMap { it.values }.map { it.at })
            campaigns.forEach { campaign ->
                campaign.startAt?.let(::add)
                campaign.endAt?.let(::add)
                campaign.availableAt?.let(::add)
                campaign.expiresAt?.let(::add)
                val cooldown = campaign.displayPolicy.cooldownMinutes
                val lastImpression = history.history(campaign.key).lastImpressionAt
                if (cooldown != null && lastImpression != null) {
                    add(lastImpression.plus(Duration.ofMinutes(cooldown.toLong())))
                }
            }
        }
        return boundaries.asSequence()
            .filter { it.isAfter(now) }
            .map { Duration.between(now, it).toMillis().coerceAtLeast(1) + 1 }
            .minOrNull()
    }

    fun consume(candidate: ResolvedContent) {
        eligibility.remove(candidate.campaign.key)
        EngageLogger.debug("InAppEvaluator", "candidate consumed messageId=${candidate.campaign.messageId}")
    }

    fun recordImpression(candidate: ResolvedContent) {
        history.recordImpression(candidate.campaign.key, clock.instant())
        EngageLogger.info("InAppEvaluator", "impression recorded messageId=${candidate.campaign.messageId}")
        if (candidate.variant.presentation is OverlayPresentation || candidate.campaign.oneShot) consume(candidate)
    }

    fun recordDismiss(candidate: ResolvedContent) {
        history.recordDismiss(candidate.campaign.key, clock.instant())
        EngageLogger.info("InAppEvaluator", "dismiss recorded messageId=${candidate.campaign.messageId}")
        if (!candidate.campaign.displayPolicy.redisplayAfterDismissal) consume(candidate)
    }

    fun remainsContextuallyEligible(candidate: ResolvedContent): Boolean {
        if (campaigns.none { it.key == candidate.campaign.key && it.revision == candidate.campaign.revision }) return false
        val now = clock.instant()
        if (!isScheduled(candidate.campaign, now)) return false
        return contextMatches(candidate.matchedTrigger)
    }

    private fun markEligible(
        campaign: Campaign,
        trigger: Trigger,
        now: Instant,
        event: JsonObject = JsonObject(emptyMap()),
    ) {
        eligibility.getOrPut(campaign.key, ::mutableMapOf)[trigger.id] =
            Eligibility(now.plusSeconds(trigger.delaySeconds.toLong()), trigger, event)
        EngageLogger.debug(
            "InAppEvaluator",
            "campaign eligible messageId=${campaign.messageId} trigger=${trigger.type} delaySeconds=${trigger.delaySeconds}",
        )
    }

    private fun markEligibleIfAbsent(campaign: Campaign, trigger: Trigger, now: Instant) {
        val previous = eligibility.getOrPut(campaign.key, ::mutableMapOf).putIfAbsent(
            trigger.id,
            Eligibility(now.plusSeconds(trigger.delaySeconds.toLong()), trigger, JsonObject(emptyMap())),
        )
        EngageLogger.verbose(
            "InAppEvaluator",
            "campaign eligibility checked messageId=${campaign.messageId} trigger=${trigger.type} alreadyEligible=${previous != null}",
        )
    }

    private fun removeScreenEligibilityExcept(screenName: String?) {
        eligibility.values.forEach { matches ->
            matches.entries.removeAll { (_, value) ->
                value.trigger?.type == TriggerType.SCREEN_VIEW && value.trigger.screenName != screenName
            }
        }
        eligibility.entries.removeAll { it.value.isEmpty() }
    }

    private fun contextMatches(trigger: Trigger?): Boolean =
        trigger?.type != TriggerType.SCREEN_VIEW || trigger.screenName == currentScreen

    private fun isScheduled(campaign: Campaign, now: Instant): Boolean =
        campaign.startAt?.let { !now.isBefore(it) } != false &&
            campaign.endAt?.let { now.isBefore(it) } != false &&
            campaign.availableAt?.let { !now.isBefore(it) } != false &&
            campaign.expiresAt?.let { now.isBefore(it) } != false

    private fun withinLimits(campaign: Campaign, now: Instant): Boolean {
        val record = history.history(campaign.key)
        val policy = campaign.displayPolicy
        if (policy.maxTotalImpressions?.let { record.total >= it } == true) {
            EngageLogger.verbose("InAppEvaluator", "limited messageId=${campaign.messageId} reason=max_total")
            return false
        }
        if (policy.maxImpressionsPerSession?.let {
                record.sessionId == history.sessionId && record.sessionCount >= it
            } == true
        ) {
            EngageLogger.verbose("InAppEvaluator", "limited messageId=${campaign.messageId} reason=max_session")
            return false
        }
        val today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).toString()
        if (policy.maxImpressionsPerDay?.let { record.day == today && record.dayCount >= it } == true) {
            EngageLogger.verbose("InAppEvaluator", "limited messageId=${campaign.messageId} reason=max_day")
            return false
        }
        if (policy.cooldownMinutes?.let { minutes ->
                record.lastImpressionAt?.let { Duration.between(it, now) < Duration.ofMinutes(minutes.toLong()) }
            } == true
        ) {
            EngageLogger.verbose("InAppEvaluator", "limited messageId=${campaign.messageId} reason=cooldown")
            return false
        }
        if (!policy.redisplayAfterDismissal && record.lastDismissedAt != null) {
            EngageLogger.verbose("InAppEvaluator", "limited messageId=${campaign.messageId} reason=dismissed")
            return false
        }
        return true
    }

    private fun selectVariant(campaign: Campaign): ContentVariant? {
        val selectedLocale = selectLocale(campaign) ?: return null
        val variants = campaign.variants.filter { normalizeLocale(it.locale) == selectedLocale }
        if (variants.isEmpty()) return null
        val bucket = stableBucket("${installationSeed()}:${campaign.experienceId}")
        var upperBound = 0
        variants.forEach { variant ->
            upperBound += variant.allocationPercentage
            if (bucket < upperBound) {
                EngageLogger.debug(
                    "InAppEvaluator",
                    "variant selected messageId=${campaign.messageId} variant=${variant.id ?: variant.key} " +
                        "locale=$selectedLocale bucket=$bucket",
                )
                return variant
            }
        }
        return null
    }

    private fun selectLocale(campaign: Campaign): String? {
        val available = campaign.variants.mapTo(linkedSetOf()) { normalizeLocale(it.locale) }
        locales().forEach { locale ->
            val exact = normalizeLocale(locale.toLanguageTag())
            if (exact in available) return exact
            val language = normalizeLocale(locale.language)
            if (language in available) return language
        }
        campaign.fallbackLocale?.let(::normalizeLocale)?.takeIf { it in available }?.let { return it }
        normalizeLocale(campaign.defaultLocale).takeIf { it in available }?.let { return it }
        return "und".takeIf { it in available }
    }

    private fun stableBucket(value: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        val positive = ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
        return positive % 100
    }

    private companion object {
        const val NO_TRIGGER = "__no_trigger__"
    }
}

private fun normalizeLocale(value: String): String = value.replace('_', '-').lowercase(Locale.ROOT)

private fun versionMatches(current: String, constraint: String?): Boolean {
    if (constraint.isNullOrBlank()) return true
    val match = Regex("^(>=|<=|>|<|=|==)?\\s*([0-9]+(?:\\.[0-9]+){0,3})$").matchEntire(constraint.trim()) ?: return false
    val operator = match.groupValues[1].ifBlank { "=" }
    val expected = match.groupValues[2]
    val actualParts = current.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
    val expectedParts = expected.split('.').mapNotNull(String::toIntOrNull)
    val comparison = (0 until maxOf(actualParts.size, expectedParts.size)).firstNotNullOfOrNull { index ->
        val result = (actualParts.getOrElse(index) { 0 }).compareTo(expectedParts.getOrElse(index) { 0 })
        result.takeIf { it != 0 }
    } ?: 0
    return when (operator) {
        ">" -> comparison > 0
        ">=" -> comparison >= 0
        "<" -> comparison < 0
        "<=" -> comparison <= 0
        else -> comparison == 0
    }
}
