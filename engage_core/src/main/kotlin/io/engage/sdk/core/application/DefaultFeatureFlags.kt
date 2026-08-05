package io.engage.sdk.core.application

import io.engage.sdk.FeatureFlags
import io.engage.sdk.EngageLogger
import io.engage.sdk.PrivacyState
import io.engage.sdk.SdkFeature
import io.engage.sdk.core.domain.ExposureStore
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkModule
import io.engage.sdk.core.domain.SessionStore
import io.engage.sdk.core.domain.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class DefaultFeatureFlags(
    private val sessions: SessionStore,
    private val syncStore: SyncStore,
    private val features: StateFlow<Set<SdkFeature>>,
    private val coordinator: OperationCoordinator,
    private val exposures: ExposureStore,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : FeatureFlags {
    override fun getBoolean(key: String, default: Boolean): Boolean {
        val resolved = resolve(key, "BOOLEAN")
        val value = resolved?.value?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() }
        logEvaluation(key, "BOOLEAN", resolved, value != null)
        return value ?: default
    }

    override fun getString(key: String, default: String): String {
        val resolved = resolve(key, "STRING")
        val value = resolved?.value?.let { (it as? JsonPrimitive)?.contentOrNull }
        logEvaluation(key, "STRING", resolved, value != null)
        return value ?: default
    }

    override fun getNumber(key: String, default: Double): Double {
        val resolved = resolve(key, "NUMBER")
        val value = resolved?.value?.let { (it as? JsonPrimitive)?.doubleOrNull }
        logEvaluation(key, "NUMBER", resolved, value != null)
        return value ?: default
    }

    override fun <T> getJson(key: String, serializer: KSerializer<T>, default: T): T {
        val resolved = resolve(key, "JSON")
        val value = resolved?.value ?: run {
            logEvaluation(key, "JSON", null, false)
            return default
        }
        return runCatching { json.decodeFromJsonElement(serializer, value) }
            .onSuccess { logEvaluation(key, "JSON", resolved, true) }
            .onFailure { error -> EngageLogger.warn("Flags", "JSON decode failed key=$key; using default", error) }
            .getOrDefault(default)
    }

    private fun resolve(key: String, expectedType: String): ResolvedFlag? {
        require(FLAG_KEY.matches(key)) { "Flag keys must match ${FLAG_KEY.pattern}" }
        if (sessions.privacy.value != PrivacyState.OPTED_IN || SdkFeature.FEATURE_FLAGS !in features.value) {
            EngageLogger.debug("Flags", "resolution unavailable key=$key reason=disabled_or_opted_out")
            return null
        }
        val session = sessions.session.value ?: run {
            EngageLogger.debug("Flags", "resolution unavailable key=$key reason=no_installation")
            return null
        }
        val stored = syncStore.snapshot.value
        if (stored.generation != session.generation) {
            EngageLogger.debug(
                "Flags",
                "resolution unavailable key=$key reason=stale_generation stored=${stored.generation} " +
                    "active=${session.generation}",
            )
            return null
        }
        val snapshot = stored.documents.firstOrNull {
            it.module == SdkModule.FEATURE_FLAGS && it.key == "snapshot"
        }?.payload ?: return null
        val flag = snapshot["flags"]?.jsonObject?.get(key)?.jsonObject ?: return null
        val type = (flag["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (type != expectedType) {
            EngageLogger.warn("Flags", "type mismatch key=$key expected=$expectedType actual=$type; using default")
            return null
        }
        val value = flag["value"] ?: return null
        val resolved = ResolvedFlag(
            value = value,
            revision = (flag["revision"] as? JsonPrimitive)?.longOrNull ?: return null,
            variantKey = (flag["variantKey"] as? JsonPrimitive)?.contentOrNull,
            experimentId = (flag["experimentId"] as? JsonPrimitive)?.contentOrNull,
        )
        recordExposure(key, resolved)
        return resolved
    }

    private fun recordExposure(key: String, flag: ResolvedFlag) {
        val experimentId = flag.experimentId ?: return
        val variantKey = flag.variantKey ?: return
        val session = sessions.session.value ?: return
        val exposureId = deterministicUuid(
            listOf(
                session.installationId,
                session.generation.toString(),
                experimentId,
                flag.revision.toString(),
                variantKey,
            ).joinToString("\u0000"),
        )
        if (exposures.contains(exposureId)) {
            EngageLogger.verbose("Flags", "exposure deduplicated key=$key id=$exposureId")
            return
        }
        EngageLogger.debug(
            "Flags",
            "exposure enqueue key=$key id=$exposureId revision=${flag.revision} variantKey=$variantKey",
        )
        scope.launch {
            val queued = coordinator.enqueue(
                type = OperationType.FLAG_EXPOSED,
                operationId = exposureId,
                payload = buildJsonObject {
                    put("flagKey", key)
                    put("experimentId", experimentId)
                    put("variantKey", variantKey)
                    put("revision", flag.revision)
                },
            )
            if (queued) exposures.mark(exposureId)
            EngageLogger.debug("Flags", "exposure enqueue result key=$key id=$exposureId accepted=$queued")
        }
    }

    private fun logEvaluation(key: String, type: String, flag: ResolvedFlag?, resolved: Boolean) {
        EngageLogger.info(
            "Flags",
            "evaluated key=$key type=$type source=${if (resolved) "REMOTE" else "DEFAULT"} " +
                "revision=${flag?.revision} variantKey=${flag?.variantKey}",
        )
    }

    private data class ResolvedFlag(
        val value: JsonElement,
        val revision: Long,
        val variantKey: String?,
        val experimentId: String?,
    )

    private companion object {
        val FLAG_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")

        fun deterministicUuid(value: String): String =
            UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
    }
}
