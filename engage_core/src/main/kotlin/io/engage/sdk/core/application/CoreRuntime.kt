package io.engage.sdk.core.application

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.engage.sdk.Actions
import io.engage.sdk.EngageConfig
import io.engage.sdk.Events
import io.engage.sdk.FeatureFlags
import io.engage.sdk.Installation
import io.engage.sdk.PrivacyState
import io.engage.sdk.PreferenceCenter
import io.engage.sdk.Privacy
import io.engage.sdk.Profile
import io.engage.sdk.SdkFeatures
import io.engage.sdk.core.BuildConfig
import io.engage.sdk.core.data.AndroidSessionStore
import io.engage.sdk.core.data.AndroidExposureStore
import io.engage.sdk.core.data.AndroidRevocationStore
import io.engage.sdk.core.data.OkHttpMobileEdgeApi
import io.engage.sdk.core.data.SqliteOperationOutbox
import io.engage.sdk.core.data.SqliteSyncStore
import io.engage.sdk.core.domain.DeviceMetadata
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageRemoteDocument
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.Locale

internal class CoreRuntime(
    override val applicationContext: Context,
    override val config: EngageConfig,
) : EngageModuleContext, DefaultLifecycleObserver {
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = AndroidSessionStore(applicationContext)
    private val outbox = SqliteOperationOutbox(applicationContext)
    private val syncStore = SqliteSyncStore(applicationContext)
    private val exposures = AndroidExposureStore(applicationContext)
    private val revocations = AndroidRevocationStore(applicationContext)
    private val api = OkHttpMobileEdgeApi(OkHttpClient.Builder().build())
    private val features = DefaultSdkFeatures(applicationContext)
    private val actionsDelegate = DefaultActions()
    private val mutableSignals = MutableSharedFlow<EngageSignal>(extraBufferCapacity = 64)
    private val syncModules = CORE_SYNC_MODULES.toMutableSet()
    private val startedModules = mutableSetOf<String>()
    private val operationCoordinator = OperationCoordinator(
        endpoint = config.endpoint,
        appKey = config.appKey,
        metadata = deviceMetadata(applicationContext),
        sessions = sessions,
        outbox = outbox,
        api = api,
    )
    private val syncCoordinator = SyncCoordinator(config.endpoint, sessions, syncStore, api)

    val installation: Installation = DefaultInstallation(sessions, operationCoordinator, scope)
    val profile: Profile = DefaultProfile(operationCoordinator, scope)
    val eventsDelegate = DefaultEvents(operationCoordinator, features.enabled, mutableSignals, scope)
    val events: Events = eventsDelegate
    val actions: Actions = actionsDelegate
    val sdkFeatures: SdkFeatures = features
    val flags: FeatureFlags = DefaultFeatureFlags(
        sessions,
        syncStore,
        features.enabled,
        operationCoordinator,
        exposures,
        scope,
    )
    val preferenceCenter: PreferenceCenter = DefaultPreferenceCenter(
        applicationContext,
        sessions,
        syncStore,
        outbox,
        features.enabled,
        scope,
    )
    private val privacyDelegate = DefaultPrivacy(
        config.endpoint,
        sessions,
        outbox,
        syncStore,
        exposures,
        revocations,
        operationCoordinator,
        api,
        scope,
        onLocalDataWiped = { mutableSignals.tryEmit(EngageSignal.LocalDataWiped) },
    )
    val privacyApi: Privacy = privacyDelegate

    override val installationId: StateFlow<String?> = installation.id
    override val generation: StateFlow<Long> = sessions.session
        .map { it?.generation ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, sessions.session.value?.generation ?: 0L)
    override val privacy: StateFlow<PrivacyState> = sessions.privacy
    override val enabledFeatures: StateFlow<Set<io.engage.sdk.SdkFeature>> = features.enabled
    override val signals = mutableSignals

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        privacyDelegate.replayPendingRevocation()
        if (privacy.value == PrivacyState.OPTED_IN) {
            refreshInBackground()
        } else {
            scope.launch { runCatching { operationCoordinator.flush() } }
        }
    }

    @Synchronized
    fun startModule(module: EngageModule) {
        if (!startedModules.add(module.id)) return
        features.addAvailable(module.features)
        syncModules += module.syncModules.map { it.toInternal() }
        module.start(this)
        refreshInBackground()
    }

    override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> =
        syncStore.snapshot.map { snapshot ->
            snapshot.documents.filter { it.module == module.toInternal() }.map { document ->
                EngageRemoteDocument(document.key, document.revision, document.payload)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun enqueue(operation: EngageModuleOperation) {
        val (type, payload) = operation.toWire()
        operationCoordinator.enqueue(type, payload)
    }

    override suspend fun refresh() {
        if (privacy.value != PrivacyState.OPTED_IN) return
        operationCoordinator.ensureInstallation()
        operationCoordinator.flush()
        syncCoordinator.refresh(syncModules.toSet())
    }

    override suspend fun executeAction(name: String, arguments: kotlinx.serialization.json.JsonObject): Boolean =
        actionsDelegate.execute(name, arguments)

    override fun onStart(owner: LifecycleOwner) {
        eventsDelegate.onForeground()
        mutableSignals.tryEmit(EngageSignal.AppOpened)
        refreshInBackground()
    }

    override fun onStop(owner: LifecycleOwner) {
        eventsDelegate.onBackground()
        mutableSignals.tryEmit(EngageSignal.AppBackgrounded)
    }

    private fun refreshInBackground() {
        scope.launch { runCatching { refresh() } }
    }

    private fun EngageModuleOperation.toWire() = when (this) {
        is EngageModuleOperation.PushTokenChanged -> OperationType.PUSH_TOKEN_SET to buildJsonObject {
            put("token", token)
        }
        is EngageModuleOperation.PushSubscriptionChanged -> OperationType.PUSH_SUBSCRIPTION_SET to buildJsonObject {
            put("state", if (optedIn) "OPTED_IN" else "OPTED_OUT")
        }
        is EngageModuleOperation.Interaction -> OperationType.INTERACTION_TRACKED to buildJsonObject {
            put("experienceId", experienceId)
            put("messageId", messageId)
            variantId?.let { put("variantId", it) }
            put("type", type.name)
        }
        is EngageModuleOperation.PushReceipt -> OperationType.PUSH_RECEIPT_RECORDED to buildJsonObject {
            put("deliveryId", deliveryId)
            put("type", type.name)
        }
    }

    private companion object {
        val CORE_SYNC_MODULES = mutableSetOf(SdkModule.PREFERENCES, SdkModule.FEATURE_FLAGS)

        fun deviceMetadata(context: Context): DeviceMetadata {
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            return DeviceMetadata(
                locale = Locale.getDefault().toLanguageTag(),
                timezone = ZoneId.systemDefault().id,
                sdkVersion = BuildConfig.ENGAGE_SDK_VERSION,
                appVersion = info.versionName ?: "0",
                appBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toString()
                },
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                osVersion = Build.VERSION.RELEASE,
            )
        }
    }
}

private fun EngageSyncModule.toInternal(): SdkModule = SdkModule.valueOf(name)
