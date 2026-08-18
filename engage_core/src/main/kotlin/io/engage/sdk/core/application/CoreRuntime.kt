package io.engage.sdk.core.application

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.engage.sdk.Actions
import io.engage.sdk.EngageConfig
import io.engage.sdk.EngageLogger
import io.engage.sdk.Events
import io.engage.sdk.FeatureFlags
import io.engage.sdk.Installation
import io.engage.sdk.PrivacyState
import io.engage.sdk.PreferenceCenter
import io.engage.sdk.Privacy
import io.engage.sdk.Profile
import io.engage.sdk.SdkFeature
import io.engage.sdk.SdkFeatures
import io.engage.sdk.core.BuildConfig
import io.engage.sdk.core.data.AndroidSessionStore
import io.engage.sdk.core.data.AndroidExposureStore
import io.engage.sdk.core.data.AndroidRevocationStore
import io.engage.sdk.core.data.OkHttpMobileEdgeApi
import io.engage.sdk.core.data.SqliteOperationOutbox
import io.engage.sdk.core.data.SqliteSyncStore
import io.engage.sdk.core.data.migrateLegacyCoreStorage
import io.engage.sdk.core.data.legacyEndpointStorageScope
import io.engage.sdk.core.data.storageScope
import io.engage.sdk.core.domain.DeviceMetadata
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.spi.EngageModule
import io.engage.sdk.spi.EngageHttpMethod
import io.engage.sdk.spi.EngageHttpRequest
import io.engage.sdk.spi.EngageHttpResponse
import io.engage.sdk.spi.EngageModuleContext
import io.engage.sdk.spi.EngageModuleOperation
import io.engage.sdk.spi.EngageRemoteDocument
import io.engage.sdk.spi.EngageSignal
import io.engage.sdk.spi.EngageSyncModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.ZoneId
import java.util.UUID
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

internal class CoreRuntime(
    override val applicationContext: Context,
    override val config: EngageConfig,
) : EngageModuleContext, DefaultLifecycleObserver {
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceScope = storageScope(config.appKey)
    init {
        migrateLegacyCoreStorage(
            applicationContext,
            persistenceScope,
            listOf(config.endpoint) + config.legacyEndpoints,
            config.appKey,
        )
    }
    private val sessions = AndroidSessionStore(applicationContext, persistenceScope)
    private val outbox = SqliteOperationOutbox(applicationContext, databaseScope = persistenceScope)
    private val syncStore = SqliteSyncStore(applicationContext, persistenceScope)
    private val exposures = AndroidExposureStore(applicationContext, persistenceScope)
    private val revocations = AndroidRevocationStore(applicationContext, persistenceScope)
    private val api = OkHttpMobileEdgeApi(OkHttpClient.Builder().build())
    private val features = DefaultSdkFeatures(applicationContext, persistenceScope)
    private val actionsDelegate = DefaultActions()
    private val mutableSignals = MutableSharedFlow<EngageSignal>(extraBufferCapacity = 64)
    private val syncModules = CopyOnWriteArraySet(CORE_SYNC_MODULES)
    private val startedModules = mutableSetOf<String>()
    private val moduleInstances = CopyOnWriteArraySet<EngageModule>()
    private val refreshMutex = Mutex()
    private var bindingPollJob: Job? = null
    private val refreshScheduler: RefreshScheduler by lazy {
        RefreshScheduler(
            scope = scope,
            refreshAfterSeconds = syncStore.snapshot.map { it.refreshAfterSeconds },
            refresh = ::refresh,
        )
    }
    private val operationCoordinator: OperationCoordinator = OperationCoordinator(
        endpoint = config.endpoint,
        appKey = config.appKey,
        metadata = deviceMetadata(applicationContext),
        sessions = sessions,
        outbox = outbox,
        api = api,
        onEnqueued = { refreshScheduler.requestAfterMutation() },
        onBindingCodeIssued = ::startBindingPoll,
    )
    private val syncCoordinator = SyncCoordinator(config.endpoint, sessions, syncStore, api)
    @Suppress("unused")
    private lateinit var connectivityMonitor: ConnectivityMonitor

    val installation: Installation = DefaultInstallation(sessions, operationCoordinator, scope)
    val profile: Profile = DefaultProfile(operationCoordinator)
    val eventsDelegate = DefaultEvents(
        operationCoordinator,
        features.enabled,
        sessions.privacy,
        mutableSignals,
        initiallyForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.STARTED,
        ),
    )
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
        refreshRemoteState = ::refresh,
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
        onLocalDataWiped = {
            moduleInstances.forEach { module -> module.wipe() }
            mutableSignals.emit(EngageSignal.LocalDataWiped)
        },
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
        EngageLogger.info(
            "Core",
            "runtime initializing privacy=${privacy.value} installationId=${installationId.value} " +
                "generation=${generation.value} enabledFeatures=${enabledFeatures.value.sortedBy { it.name }}",
        )
        connectivityMonitor = ConnectivityMonitor(applicationContext, refreshScheduler::requestImmediate)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        privacyDelegate.replayPendingRevocation()
        scope.launch {
            installationId.collect { id ->
                if (id == null) {
                    EngageLogger.info("Installation", "installationId unavailable")
                } else {
                    EngageLogger.info("Installation", "installationId=$id generation=${generation.value}")
                }
            }
        }
        scope.launch {
            generation.collect { value ->
                EngageLogger.info("Installation", "generation=$value installationId=${installationId.value}")
            }
        }
        scope.launch {
            features.enabled.drop(1).collect { enabled ->
                EngageLogger.info("Features", "state changed enabled=${enabled.sortedBy { it.name }}")
                if (SdkFeature.IN_APP !in enabled && SdkFeature.ANALYTICS !in enabled) {
                    eventsDelegate.resetScreenContext()
                }
                refreshScheduler.requestImmediate()
            }
        }
        scope.launch {
            privacy.drop(1).collect { state ->
                EngageLogger.info("Privacy", "state changed state=$state")
                if (state == PrivacyState.OPTED_IN) {
                    refreshScheduler.requestImmediate()
                } else {
                    bindingPollJob?.cancel()
                    bindingPollJob = null
                    eventsDelegate.resetScreenContext()
                }
            }
        }
        if (privacy.value == PrivacyState.OPTED_IN) {
            refreshScheduler.requestImmediate()
        } else {
            EngageLogger.info("Privacy", "startup is opted out; scheduling durable OPTED_OUT marker")
            scope.launch {
                runCatching {
                    operationCoordinator.enqueue(
                        type = OperationType.PRIVACY_STATE_SET,
                        payload = buildJsonObject { put("state", "OPTED_OUT") },
                        allowWhileOptedOut = true,
                    )
                    operationCoordinator.flush()
                }.onFailure { error ->
                    EngageLogger.warn("Privacy", "startup OPTED_OUT marker could not be flushed", error)
                }
            }
        }
    }

    @Synchronized
    fun startModule(module: EngageModule) {
        if (!startedModules.add(module.id)) {
            EngageLogger.verbose("Core", "module start ignored id=${module.id} reason=already_started")
            return
        }
        moduleInstances += module
        features.addAvailable(module.features)
        syncModules += module.syncModules.map { it.toInternal() }
        module.start(this)
        EngageLogger.info(
            "Core",
            "module started id=${module.id} features=${module.features.sortedBy { it.name }} " +
                "syncModules=${module.syncModules.sortedBy { it.name }}",
        )
        refreshScheduler.requestImmediate()
    }

    override fun documents(module: EngageSyncModule): StateFlow<List<EngageRemoteDocument>> =
        combine(syncStore.snapshot, generation) { snapshot, activeGeneration ->
            if (snapshot.generation != activeGeneration) {
                emptyList()
            } else {
                snapshot.documents.filter { it.module == module.toInternal() }.map { document ->
                    EngageRemoteDocument(document.key, document.revision, document.payload)
                }
            }
        }.onEach { documents ->
            EngageLogger.verbose(
                "Sync",
                "documents emitted module=${module.name} count=${documents.size} generation=${generation.value}",
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun enqueue(operation: EngageModuleOperation): Boolean {
        val (type, payload) = operation.toWire()
        val operationId = (operation as? EngageModuleOperation.PushReceipt)?.let { receipt ->
            UUID.nameUUIDFromBytes(
                "engage:push-receipt:${receipt.deliveryId}:${receipt.type.name}".toByteArray(),
            ).toString()
        }
        EngageLogger.verbose("Core", "module operation received type=${operation::class.simpleName}")
        return if (operationId == null) {
            operationCoordinator.enqueue(type, payload)
        } else {
            operationCoordinator.enqueue(type, payload, operationId = operationId)
        }.also { accepted ->
            EngageLogger.debug("Core", "module operation type=${type.name} accepted=$accepted")
        }
    }

    override suspend fun refresh() = refreshMutex.withLock {
        if (privacy.value != PrivacyState.OPTED_IN) {
            EngageLogger.debug("Sync", "refresh skipped reason=privacy_opted_out")
            return
        }
        EngageLogger.info("Sync", "refresh started")
        operationCoordinator.ensureInstallation()
        val enabled = enabledFeatures.value
        val modules = syncModules.filterTo(mutableSetOf()) { it.requiredFeature() in enabled }
        val remote = syncCoordinator.reconcile()
        operationCoordinator.flush()
        syncCoordinator.synchronize(modules, remote)
        EngageLogger.info(
            "Sync",
            "refresh finished installationId=${installationId.value} generation=${generation.value} " +
                "modules=${modules.sortedBy { it.name }}",
        )
    }

    override suspend fun executeAction(name: String, arguments: kotlinx.serialization.json.JsonObject): Boolean =
        actionsDelegate.execute(name, arguments).also { completed ->
            EngageLogger.debug("Core", "module action name=$name completed=$completed")
        }

    override suspend fun authorizedRequest(request: EngageHttpRequest): EngageHttpResponse {
        EngageLogger.debug(
            "HTTP",
            "module request method=${request.method} path=${request.path} queryKeys=${request.query.keys.sorted()} " +
                "bodyKeys=${request.body?.keys?.sorted().orEmpty()}",
        )
        require(!request.path.startsWith('/') && !request.path.contains("://") && request.path.startsWith("sdk/")) {
            "Optional Engage modules may only call relative sdk/ paths"
        }
        check(privacy.value == PrivacyState.OPTED_IN) { "Engage is opted out" }
        val session = operationCoordinator.ensureInstallation()
        val response = api.authorizedRequest(
            config.endpoint,
            session.credential,
            io.engage.sdk.core.domain.AuthorizedRequest(
                method = when (request.method) {
                    EngageHttpMethod.GET -> io.engage.sdk.core.domain.AuthorizedMethod.GET
                    EngageHttpMethod.POST -> io.engage.sdk.core.domain.AuthorizedMethod.POST
                },
                path = request.path,
                query = request.query,
                body = request.body,
            ),
        )
        return EngageHttpResponse(response.statusCode, response.body).also {
            EngageLogger.debug("HTTP", "module response path=${request.path} status=${response.statusCode}")
        }
    }

    private fun startBindingPoll(initialGeneration: Long, expiresAt: String) {
        EngageLogger.info(
            "Installation",
            "binding poll started initialGeneration=$initialGeneration expiresAt=$expiresAt",
        )
        bindingPollJob?.cancel()
        val expiration = runCatching { Instant.parse(expiresAt) }.getOrNull()
            ?: Instant.now().plusSeconds(BINDING_CODE_FALLBACK_TTL_SECONDS)
        bindingPollJob = scope.launch {
            while (
                sessions.privacy.value == PrivacyState.OPTED_IN &&
                sessions.session.value?.generation == initialGeneration &&
                Instant.now().isBefore(expiration)
            ) {
                delay(BINDING_POLL_INTERVAL_MILLIS)
                EngageLogger.verbose("Installation", "binding poll tick initialGeneration=$initialGeneration")
                runCatching { refresh() }.onFailure { error ->
                    EngageLogger.warn("Installation", "binding poll refresh failed", error)
                }
            }
            bindingPollJob = null
            EngageLogger.info(
                "Installation",
                "binding poll stopped initialGeneration=$initialGeneration activeGeneration=${generation.value}",
            )
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        EngageLogger.info("Lifecycle", "application entered foreground")
        eventsDelegate.onForeground()
        if (privacy.value == PrivacyState.OPTED_IN) mutableSignals.tryEmit(EngageSignal.AppOpened)
        refreshScheduler.setForeground(true)
        refreshScheduler.requestImmediate()
    }

    override fun onStop(owner: LifecycleOwner) {
        EngageLogger.info("Lifecycle", "application entered background")
        eventsDelegate.onBackground()
        if (privacy.value == PrivacyState.OPTED_IN) mutableSignals.tryEmit(EngageSignal.AppBackgrounded)
        refreshScheduler.setForeground(false)
    }

    private fun EngageModuleOperation.toWire() = when (this) {
        is EngageModuleOperation.PushTokenChanged -> OperationType.PUSH_TOKEN_SET to buildJsonObject {
            put("token", token)
        }
        is EngageModuleOperation.PushSubscriptionChanged -> OperationType.PUSH_SUBSCRIPTION_SET to buildJsonObject {
            put("state", if (optedIn) "OPTED_IN" else "OPTED_OUT")
        }
        is EngageModuleOperation.PushPermissionChanged -> OperationType.PUSH_PERMISSION_SET to buildJsonObject {
            put("state", permission)
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
        const val BINDING_POLL_INTERVAL_MILLIS = 2_000L
        const val BINDING_CODE_FALLBACK_TTL_SECONDS = 5 * 60L
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

private fun SdkModule.requiredFeature(): SdkFeature = when (this) {
    SdkModule.PUSH -> SdkFeature.PUSH
    SdkModule.IN_APP -> SdkFeature.IN_APP
    SdkModule.PREFERENCES -> SdkFeature.PREFERENCES
    SdkModule.FEATURE_FLAGS -> SdkFeature.FEATURE_FLAGS
}

private class ConnectivityMonitor(context: Context, notifyAvailable: () -> Unit) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            EngageLogger.debug("Connectivity", "network became available")
            notifyAvailable()
        }
    }

    init {
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
        }.onSuccess {
            EngageLogger.debug("Connectivity", "network callback registered")
        }.onFailure { error ->
            EngageLogger.warn("Connectivity", "network callback registration failed", error)
        }
    }
}
