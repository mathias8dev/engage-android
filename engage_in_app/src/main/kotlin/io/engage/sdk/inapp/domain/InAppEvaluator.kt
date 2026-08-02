package io.engage.sdk.inapp.domain

import io.engage.sdk.OverlayPresentation
import io.engage.sdk.spi.EngageSignal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

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
    private val eligibleAt = mutableMapOf<String, Instant>()
    private var currentScreen: String? = null
    private var isForeground = false

    val foreground: Boolean get() = isForeground

    fun replaceCampaigns(value: List<Campaign>) {
        campaigns = value
        val keys = value.mapTo(mutableSetOf()) { it.key }
        eligibleAt.keys.retainAll(keys)
        value.filter { it.triggers.isEmpty() }.forEach { campaign ->
            eligibleAt.putIfAbsent(campaign.key, campaign.availableAt ?: campaign.publishedAt)
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
        campaigns = emptyList()
        eligibleAt.clear()
        currentScreen = null
        isForeground = false
    }

    fun onSignal(signal: EngageSignal) {
        val now = clock.instant()
        when (signal) {
            EngageSignal.AppOpened -> {
                isForeground = true
                history.beginSession()
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
                campaigns.forEach { campaign ->
                    campaign.triggers.filter { it.type == TriggerType.SCREEN_VIEW && it.screenName == signal.key }
                        .forEach { markEligible(campaign, it, now) }
                }
            }
            EngageSignal.ScreenCleared -> {
                currentScreen = null
                campaigns.filter { campaign -> campaign.triggers.any { it.type == TriggerType.SCREEN_VIEW } }
                    .forEach { eligibleAt.remove(it.key) }
            }
            is EngageSignal.EventOccurred -> campaigns.forEach { campaign ->
                campaign.triggers.filter { it.type == TriggerType.EVENT && it.eventName == signal.name }
                    .forEach { markEligible(campaign, it, now) }
            }
            EngageSignal.AppBackgrounded -> isForeground = false
            EngageSignal.LocalDataWiped -> resetContext()
        }
    }

    fun candidates(): List<ResolvedContent> {
        val now = clock.instant()
        return campaigns.mapNotNull { campaign ->
            val eligible = eligibleAt[campaign.key] ?: return@mapNotNull null
            if (eligible.isAfter(now) || !isScheduled(campaign, now) || !withinLimits(campaign, now)) return@mapNotNull null
            if (campaign.triggers.any { it.type == TriggerType.SCREEN_VIEW } &&
                campaign.triggers.filter { it.type == TriggerType.SCREEN_VIEW }.none { it.screenName == currentScreen }
            ) return@mapNotNull null
            selectVariant(campaign)?.let { ResolvedContent(campaign, it) }
        }.sortedWith(
            compareByDescending<ResolvedContent> { it.campaign.priority }
                .thenBy { it.campaign.publishedAt }
                .thenBy { it.campaign.key },
        )
    }

    fun nextEvaluationDelayMillis(): Long? {
        val now = clock.instant()
        val boundaries = buildList {
            addAll(eligibleAt.values)
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
        eligibleAt.remove(candidate.campaign.key)
    }

    fun recordImpression(candidate: ResolvedContent) {
        history.recordImpression(candidate.campaign.key, clock.instant())
        if (candidate.variant.presentation is OverlayPresentation || candidate.campaign.oneShot) consume(candidate)
    }

    fun recordDismiss(candidate: ResolvedContent) {
        history.recordDismiss(candidate.campaign.key, clock.instant())
        if (!candidate.campaign.displayPolicy.redisplayAfterDismissal) consume(candidate)
    }

    fun remainsContextuallyEligible(candidate: ResolvedContent): Boolean {
        if (campaigns.none { it.key == candidate.campaign.key && it.revision == candidate.campaign.revision }) return false
        val now = clock.instant()
        if (!isScheduled(candidate.campaign, now)) return false
        return candidate.campaign.triggers.none { it.type == TriggerType.SCREEN_VIEW } ||
            candidate.campaign.triggers.filter { it.type == TriggerType.SCREEN_VIEW }.any { it.screenName == currentScreen }
    }

    private fun markEligible(campaign: Campaign, trigger: Trigger, now: Instant) {
        eligibleAt[campaign.key] = now.plusSeconds(trigger.delaySeconds.toLong())
    }

    private fun markEligibleIfAbsent(campaign: Campaign, trigger: Trigger, now: Instant) {
        eligibleAt.putIfAbsent(campaign.key, now.plusSeconds(trigger.delaySeconds.toLong()))
    }

    private fun isScheduled(campaign: Campaign, now: Instant): Boolean =
        campaign.startAt?.let { !now.isBefore(it) } != false &&
            campaign.endAt?.let { now.isBefore(it) } != false &&
            campaign.availableAt?.let { !now.isBefore(it) } != false &&
            campaign.expiresAt?.let { now.isBefore(it) } != false

    private fun withinLimits(campaign: Campaign, now: Instant): Boolean {
        val record = history.history(campaign.key)
        val policy = campaign.displayPolicy
        if (policy.maxTotalImpressions?.let { record.total >= it } == true) return false
        if (policy.maxImpressionsPerSession?.let {
                record.sessionId == history.sessionId && record.sessionCount >= it
            } == true
        ) return false
        val today = LocalDate.ofInstant(now, java.time.ZoneOffset.UTC).toString()
        if (policy.maxImpressionsPerDay?.let { record.day == today && record.dayCount >= it } == true) return false
        if (policy.cooldownMinutes?.let { minutes ->
                record.lastImpressionAt?.let { Duration.between(it, now) < Duration.ofMinutes(minutes.toLong()) }
            } == true
        ) return false
        if (!policy.redisplayAfterDismissal && record.lastDismissedAt != null) return false
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
            if (bucket < upperBound) return variant
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
