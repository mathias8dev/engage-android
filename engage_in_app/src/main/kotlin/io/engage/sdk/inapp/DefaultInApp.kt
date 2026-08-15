package io.engage.sdk.inapp

import android.app.Application
import android.content.pm.PackageManager
import io.engage.sdk.DisplayDecision
import io.engage.sdk.EmbeddedPresentation
import io.engage.sdk.EngageLogger
import io.engage.sdk.InApp
import io.engage.sdk.InAppContent
import io.engage.sdk.InAppOverlayDisplayDelegate
import io.engage.sdk.InAppOverlays
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.inapp.data.InAppDocumentParser
import io.engage.sdk.inapp.data.SharedPreferencesInAppHistory
import io.engage.sdk.inapp.domain.ConflictPolicy
import io.engage.sdk.inapp.domain.InAppEvaluator
import io.engage.sdk.inapp.domain.ResolvedContent
import io.engage.sdk.inapp.render.ActivityMonitor
import io.engage.sdk.inapp.render.InAppRenderCallbacks
import io.engage.sdk.inapp.render.OverlayPresenter
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageSyncModule
import io.engage.sdk.spi.InteractionType
import io.engage.sdk.spi.scopedPreferencesName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class DefaultInApp(private val context: EngageModuleContext) : InApp, InAppRenderCallbacks {
    private val mutex = Mutex()
    private val history = SharedPreferencesInAppHistory(
        context.applicationContext,
        preferencesName = context.scopedPreferencesName("engage_in_app_history"),
        generation = { context.generation.value },
    )
    private val evaluator = InAppEvaluator(
        history = history,
        installationSeed = { context.installationId.value ?: context.config.appKey },
        appVersion = appVersion(),
        locales = { currentLocales() },
    )
    private val placementFlows = ConcurrentHashMap<String, MutableStateFlow<InAppContent?>>()
    private val activePlacements = mutableMapOf<String, ResolvedContent>()
    private val resolutions = ConcurrentHashMap<String, ResolvedContent>()
    private val overlayPresenter = OverlayPresenter()
    private val defaultOverlays = DefaultOverlays(::requestEvaluation)
    private var enabled = false
    private var currentGeneration = context.generation.value
    private var delayedEvaluation: Job? = null
    private val activityMonitor = (context.applicationContext as? Application)?.let { application ->
        ActivityMonitor(application, ::requestEvaluation)
    }

    override val overlays: InAppOverlays = defaultOverlays

    suspend fun wipe() = mutex.withLock {
        context.logWarn("InApp", "local state wipe started")
        history.clearAll()
        evaluator.resetContext()
        clearPresentationsLocked()
        context.logWarn("InApp", "local state wiped")
    }

    init {
        context.logInfo(
            "InApp",
            "initialized generation=$currentGeneration installationId=${context.installationId.value} " +
                "appVersion=${appVersion()} locales=${currentLocales().map(Locale::toLanguageTag)}",
        )
        context.scope.launch {
            combine(
                context.documents(EngageSyncModule.IN_APP),
                context.privacy,
                context.enabledFeatures,
                context.generation,
                context.installationId,
            ) { documents, privacy, features, generation, installationId ->
                SyncState(
                    campaigns = documents.mapNotNull(InAppDocumentParser::parse),
                    enabled = privacy == PrivacyState.OPTED_IN && SdkFeature.IN_APP in features && installationId != null,
                    generation = generation,
                )
            }.collect { state ->
                context.logDebug(
                    "InApp",
                    "sync state campaigns=${state.campaigns.size} enabled=${state.enabled} generation=${state.generation}",
                )
                mutex.withLock {
                    if (currentGeneration != state.generation) {
                        context.logInfo(
                            "InApp",
                            "generation changed previous=$currentGeneration next=${state.generation}; resetting context",
                        )
                        currentGeneration = state.generation
                        evaluator.resetContext()
                    }
                    enabled = state.enabled
                    evaluator.replaceCampaigns(if (enabled) state.campaigns else emptyList())
                    if (enabled && activityMonitor?.current != null && !evaluator.foreground) {
                        evaluator.onSignal(io.engage.sdk.spi.EngageSignal.AppOpened)
                    }
                    if (!enabled) clearPresentationsLocked()
                }
                evaluate()
            }
        }
        context.scope.launch {
            context.signals.collect { signal ->
                context.logDebug("InApp", "signal received type=${signal::class.simpleName}")
                mutex.withLock {
                    if (signal == io.engage.sdk.spi.EngageSignal.LocalDataWiped) history.clearAll()
                    evaluator.onSignal(signal)
                    if (signal == io.engage.sdk.spi.EngageSignal.LocalDataWiped) clearPresentationsLocked()
                }
                evaluate()
            }
        }
    }

    override fun placement(key: String): StateFlow<InAppContent?> {
        require(PLACEMENT_KEY.matches(key)) { "Placement keys must match ${PLACEMENT_KEY.pattern}" }
        val flow = placementFlows.getOrPut(key) { MutableStateFlow(null) }
        context.logInfo("InApp", "placement subscribed key=$key existing=${flow.value?.messageId}")
        requestEvaluation()
        return flow
    }

    override fun onVisible(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: run {
            context.logWarn("InApp", "visibility ignored messageId=${content.messageId} reason=unknown_resolution")
            return
        }
        context.logInfo("InApp", "content visible messageId=${content.messageId} variant=${content.variantId}")
        context.scope.launch {
            mutex.withLock { evaluator.recordImpression(candidate) }
            enqueue(candidate, InteractionType.IMPRESSION)
            evaluate()
        }
    }

    override fun onClicked(content: InAppContent) {
        context.logInfo("InApp", "content clicked messageId=${content.messageId} variant=${content.variantId}")
        interaction(content, InteractionType.CLICK)
    }

    override fun onDismissed(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: run {
            context.logWarn("InApp", "dismiss ignored messageId=${content.messageId} reason=unknown_resolution")
            return
        }
        context.logInfo("InApp", "content dismissed messageId=${content.messageId} variant=${content.variantId}")
        context.scope.launch {
            mutex.withLock { evaluator.recordDismiss(candidate) }
            enqueue(candidate, InteractionType.DISMISS)
            evaluate()
        }
    }

    override fun onConversion(content: InAppContent) {
        context.logInfo("InApp", "conversion reported messageId=${content.messageId} variant=${content.variantId}")
        interaction(content, InteractionType.CONVERSION)
    }

    override fun onAction(content: InAppContent, name: String, arguments: JsonObject) {
        context.logInfo(
            "InApp",
            "action requested messageId=${content.messageId} name=$name argumentKeys=${arguments.keys.sorted()}",
        )
        context.scope.launch {
            val completed = context.executeAction(name, arguments)
            context.logInfo("InApp", "action finished messageId=${content.messageId} name=$name completed=$completed")
        }
    }

    override fun onRenderFailed(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: run {
            context.logWarn("InApp", "render failure ignored messageId=${content.messageId} reason=unknown_resolution")
            return
        }
        context.logWarn("InApp", "render failed messageId=${content.messageId} variant=${content.variantId}")
        context.scope.launch {
            mutex.withLock { evaluator.consume(candidate) }
            evaluate()
        }
    }

    private fun interaction(content: InAppContent, type: InteractionType) {
        val candidate = resolutions[content.identity()] ?: run {
            context.logWarn(
                "InApp",
                "interaction ignored messageId=${content.messageId} type=$type reason=unknown_resolution",
            )
            return
        }
        context.scope.launch { enqueue(candidate, type) }
    }

    private suspend fun enqueue(candidate: ResolvedContent, type: InteractionType) {
        val accepted = context.enqueue(
            EngageModuleOperation.Interaction(
                experienceId = candidate.campaign.experienceId,
                messageId = candidate.campaign.messageId,
                variantId = candidate.variant.id ?: candidate.variant.key,
                type = type,
            ),
        )
        context.logDebug(
            "InApp",
            "interaction enqueued experienceId=${candidate.campaign.experienceId} " +
                "messageId=${candidate.campaign.messageId} type=$type accepted=$accepted",
        )
    }

    private fun requestEvaluation() {
        context.logVerbose("InApp", "evaluation requested")
        context.scope.launch { evaluate() }
    }

    private suspend fun evaluate() {
        mutex.withLock {
            delayedEvaluation?.cancel()
            delayedEvaluation = null
            if (!enabled) {
                context.logVerbose("InApp", "evaluation skipped reason=disabled")
                return
            }
            val candidates = evaluator.candidates()
            context.logDebug(
                "InApp",
                "evaluation candidates=${candidates.size} placements=${placementFlows.keys.sorted()} " +
                    "overlayActive=${overlayPresenter.activeContent?.messageId}",
            )
            updatePlacementsLocked(candidates)
            updateOverlayLocked(candidates)
            evaluator.nextEvaluationDelayMillis()?.let { waitMillis ->
                context.logVerbose("InApp", "next evaluation scheduled delayMillis=$waitMillis")
                delayedEvaluation = context.scope.launch {
                    delay(waitMillis)
                    evaluate()
                }
            }
        }
    }

    private fun updatePlacementsLocked(candidates: List<ResolvedContent>) {
        placementFlows.forEach { (key, flow) ->
            val current = activePlacements[key]
            if (current != null && evaluator.remainsContextuallyEligible(current)) return@forEach
            val selected = candidates.firstOrNull {
                (it.variant.presentation as? EmbeddedPresentation)?.placementKey == key
            }
            if (selected == null) {
                activePlacements.remove(key)
                flow.value = null
                context.logDebug("InApp", "placement cleared key=$key")
            } else {
                activePlacements[key] = selected
                flow.value = selected.toPublic().also(::remember)
                context.logInfo(
                    "InApp",
                    "placement selected key=$key experienceId=${selected.campaign.experienceId} " +
                        "messageId=${selected.campaign.messageId}",
                )
            }
        }
    }

    private suspend fun updateOverlayLocked(candidates: List<ResolvedContent>) {
        val overlayCandidates = candidates.filter { it.variant.presentation !is EmbeddedPresentation }
        val activeContent = overlayPresenter.activeContent
        if (activeContent != null) {
            if (activityMonitor?.current == null) {
                withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                context.logDebug("InApp", "active overlay dismissed reason=no_activity")
                return
            }
            val active = resolutions[activeContent.identity()]
            if (active == null || !evaluator.remainsContextuallyEligible(active)) {
                withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                context.logDebug("InApp", "active overlay dismissed reason=no_longer_eligible")
                return
            }
            val challenger = overlayCandidates.firstOrNull { it.instanceKey != active.instanceKey } ?: return
            when (challenger.campaign.conflictPolicy) {
                ConflictPolicy.QUEUE -> context.logDebug("InApp", "challenger queued messageId=${challenger.campaign.messageId}")
                ConflictPolicy.SKIP -> {
                    evaluator.consume(challenger)
                    context.logDebug("InApp", "challenger skipped messageId=${challenger.campaign.messageId}")
                }
                ConflictPolicy.REPLACE_LOWER_PRIORITY -> if (challenger.campaign.priority > active.campaign.priority) {
                    withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                    context.logInfo(
                        "InApp",
                        "active overlay replaced active=${active.campaign.messageId} " +
                            "challenger=${challenger.campaign.messageId}",
                    )
                }
            }
            return
        }
        if (defaultOverlays.isPaused) {
            context.logDebug("InApp", "overlay selection deferred reason=paused")
            return
        }
        val activity = activityMonitor?.current ?: run {
            context.logVerbose("InApp", "overlay selection deferred reason=no_activity")
            return
        }
        val selected = overlayCandidates.firstOrNull() ?: return
        val content = selected.toPublic().also(::remember)
        val decision = withContext(Dispatchers.Main.immediate) {
            defaultOverlays.displayDelegate?.decide(content) ?: DisplayDecision.ALLOW
        }
        context.logInfo(
            "InApp",
            "overlay decision experienceId=${content.experienceId} messageId=${content.messageId} decision=$decision",
        )
        when (decision) {
            DisplayDecision.DEFER -> return
            DisplayDecision.DISCARD -> evaluator.consume(selected)
            DisplayDecision.ALLOW -> withContext(Dispatchers.Main.immediate) {
                overlayPresenter.show(activity, content, this@DefaultInApp, ::requestEvaluation)
                context.logInfo("InApp", "overlay show requested messageId=${content.messageId}")
            }
        }
    }

    private suspend fun clearPresentationsLocked() {
        context.logDebug(
            "InApp",
            "clearing presentations placements=${activePlacements.size} resolutions=${resolutions.size}",
        )
        activePlacements.clear()
        placementFlows.values.forEach { it.value = null }
        resolutions.clear()
        withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
    }

    private fun remember(content: InAppContent) {
        val candidate = activePlacements.values.firstOrNull { it.matches(content) }
            ?: evaluator.candidates().firstOrNull { it.matches(content) }
        if (candidate != null) resolutions[content.identity()] = candidate
        context.logVerbose(
            "InApp",
            "resolution remembered messageId=${content.messageId} found=${candidate != null}",
        )
    }

    private fun appVersion(): String = runCatching {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.packageManager.getPackageInfo(
                context.applicationContext.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.packageManager.getPackageInfo(context.applicationContext.packageName, 0)
        }
        packageInfo.versionName ?: "0"
    }.getOrDefault("0")

    private fun currentLocales(): List<Locale> {
        val configuration = context.applicationContext.resources.configuration
        return if (android.os.Build.VERSION.SDK_INT >= 24) {
            (0 until configuration.locales.size()).map(configuration.locales::get)
        } else {
            @Suppress("DEPRECATION")
            listOf(configuration.locale)
        }
    }

    private data class SyncState(
        val campaigns: List<io.engage.sdk.inapp.domain.Campaign>,
        val enabled: Boolean,
        val generation: Long,
    )

    private companion object {
        val PLACEMENT_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

private class DefaultOverlays(private val onChanged: () -> Unit) : InAppOverlays {
    private val pauses = AtomicInteger()
    override var displayDelegate: InAppOverlayDisplayDelegate? = null
        set(value) {
            field = value
            onChanged()
        }

    val isPaused: Boolean get() = pauses.get() > 0

    override fun pause() {
        val count = pauses.incrementAndGet()
        EngageLogger.info("InApp", "overlays paused depth=$count")
    }

    override fun resume() {
        while (true) {
            val current = pauses.get()
            if (current == 0 || pauses.compareAndSet(current, current - 1)) break
        }
        EngageLogger.info("InApp", "overlays resumed depth=${pauses.get()}")
        onChanged()
    }
}

private fun ResolvedContent.toPublic() = InAppContent(
    experienceId = campaign.experienceId,
    messageId = campaign.messageId,
    variantId = variant.id ?: variant.key,
    type = variant.type,
    payload = variant.payload,
    presentation = variant.presentation,
)

private fun ResolvedContent.matches(content: InAppContent): Boolean =
    campaign.experienceId == content.experienceId &&
        campaign.messageId == content.messageId &&
        (variant.id ?: variant.key) == content.variantId

private fun InAppContent.identity(): String = "$experienceId\u0000$messageId\u0000${variantId.orEmpty()}"
