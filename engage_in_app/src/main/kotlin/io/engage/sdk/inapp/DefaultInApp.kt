package io.engage.sdk.inapp

import android.app.Application
import android.content.pm.PackageManager
import io.engage.sdk.DisplayDecision
import io.engage.sdk.EmbeddedPresentation
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
    private val history = SharedPreferencesInAppHistory(context.applicationContext) { context.generation.value }
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

    init {
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
                mutex.withLock {
                    if (currentGeneration != state.generation) {
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
        requestEvaluation()
        return flow
    }

    override fun onVisible(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: return
        context.scope.launch {
            mutex.withLock { evaluator.recordImpression(candidate) }
            enqueue(candidate, InteractionType.IMPRESSION)
            evaluate()
        }
    }

    override fun onClicked(content: InAppContent) {
        interaction(content, InteractionType.CLICK)
    }

    override fun onDismissed(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: return
        context.scope.launch {
            mutex.withLock { evaluator.recordDismiss(candidate) }
            enqueue(candidate, InteractionType.DISMISS)
            evaluate()
        }
    }

    override fun onConversion(content: InAppContent) {
        interaction(content, InteractionType.CONVERSION)
    }

    override fun onAction(content: InAppContent, name: String, arguments: JsonObject) {
        context.scope.launch { context.executeAction(name, arguments) }
    }

    override fun onRenderFailed(content: InAppContent) {
        val candidate = resolutions[content.identity()] ?: return
        context.scope.launch {
            mutex.withLock { evaluator.consume(candidate) }
            evaluate()
        }
    }

    private fun interaction(content: InAppContent, type: InteractionType) {
        val candidate = resolutions[content.identity()] ?: return
        context.scope.launch { enqueue(candidate, type) }
    }

    private suspend fun enqueue(candidate: ResolvedContent, type: InteractionType) {
        context.enqueue(
            EngageModuleOperation.Interaction(
                experienceId = candidate.campaign.experienceId,
                messageId = candidate.campaign.messageId,
                variantId = candidate.variant.id ?: candidate.variant.key,
                type = type,
            ),
        )
    }

    private fun requestEvaluation() {
        context.scope.launch { evaluate() }
    }

    private suspend fun evaluate() {
        mutex.withLock {
            delayedEvaluation?.cancel()
            delayedEvaluation = null
            if (!enabled) return
            val candidates = evaluator.candidates()
            updatePlacementsLocked(candidates)
            updateOverlayLocked(candidates)
            evaluator.nextEvaluationDelayMillis()?.let { waitMillis ->
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
            } else {
                activePlacements[key] = selected
                flow.value = selected.toPublic().also(::remember)
            }
        }
    }

    private suspend fun updateOverlayLocked(candidates: List<ResolvedContent>) {
        val overlayCandidates = candidates.filter { it.variant.presentation !is EmbeddedPresentation }
        val activeContent = overlayPresenter.activeContent
        if (activeContent != null) {
            if (activityMonitor?.current == null) {
                withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                return
            }
            val active = resolutions[activeContent.identity()]
            if (active == null || !evaluator.remainsContextuallyEligible(active)) {
                withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                return
            }
            val challenger = overlayCandidates.firstOrNull { it.instanceKey != active.instanceKey } ?: return
            when (challenger.campaign.conflictPolicy) {
                ConflictPolicy.QUEUE -> Unit
                ConflictPolicy.SKIP -> evaluator.consume(challenger)
                ConflictPolicy.REPLACE_LOWER_PRIORITY -> if (challenger.campaign.priority > active.campaign.priority) {
                    withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
                }
            }
            return
        }
        if (defaultOverlays.isPaused) return
        val activity = activityMonitor?.current ?: return
        val selected = overlayCandidates.firstOrNull() ?: return
        val content = selected.toPublic().also(::remember)
        val decision = withContext(Dispatchers.Main.immediate) {
            defaultOverlays.displayDelegate?.decide(content) ?: DisplayDecision.ALLOW
        }
        when (decision) {
            DisplayDecision.DEFER -> return
            DisplayDecision.DISCARD -> evaluator.consume(selected)
            DisplayDecision.ALLOW -> withContext(Dispatchers.Main.immediate) {
                overlayPresenter.show(activity, content, this@DefaultInApp, ::requestEvaluation)
            }
        }
    }

    private suspend fun clearPresentationsLocked() {
        activePlacements.clear()
        placementFlows.values.forEach { it.value = null }
        resolutions.clear()
        withContext(Dispatchers.Main.immediate) { overlayPresenter.dismiss(reportDismissal = false) }
    }

    private fun remember(content: InAppContent) {
        val candidate = activePlacements.values.firstOrNull { it.matches(content) }
            ?: evaluator.candidates().firstOrNull { it.matches(content) }
        if (candidate != null) resolutions[content.identity()] = candidate
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
        pauses.incrementAndGet()
    }

    override fun resume() {
        while (true) {
            val current = pauses.get()
            if (current == 0 || pauses.compareAndSet(current, current - 1)) break
        }
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
