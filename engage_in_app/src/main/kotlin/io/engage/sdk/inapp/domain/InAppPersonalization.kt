package io.engage.sdk.inapp.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal const val IN_APP_VALUE_BINDING_MARKER = "\$engageValue"

internal data class InAppPersonalizationContext(
    val values: JsonObject = JsonObject(emptyMap()),
    val fallbacks: JsonObject = JsonObject(emptyMap()),
)

internal object InAppPersonalization {
    fun resolve(payload: JsonObject, values: JsonObject, fallbacks: JsonObject): JsonObject =
        resolveElement(payload, values, fallbacks) as JsonObject

    fun values(
        base: JsonObject,
        event: JsonObject,
        appVersion: String,
        locale: String,
        screenName: String?,
        sessionCount: Int,
    ): JsonObject {
        val roots = base.toMutableMap()
        roots["event"] = merge(roots["event"] as? JsonObject, event)
        roots["runtime"] = merge(
            roots["runtime"] as? JsonObject,
            JsonObject(buildMap {
                put("app_version", JsonPrimitive(appVersion))
                put("locale", JsonPrimitive(locale))
                screenName?.let { put("screen_name", JsonPrimitive(it)) }
                put("session_count", JsonPrimitive(sessionCount))
            }),
        )
        return JsonObject(roots)
    }

    private fun resolveElement(value: JsonElement, values: JsonObject, fallbacks: JsonObject): JsonElement {
        if (value is JsonObject && value.size == 1) {
            val marker = value[IN_APP_VALUE_BINDING_MARKER] as? JsonPrimitive
            val path = marker?.takeIf(JsonPrimitive::isString)?.content
            if (path != null) {
                val fallback = read(fallbacks, path)
                val live = read(values, path)
                return when {
                    live != null && fallback != null && sameType(live, fallback) -> live
                    fallback != null -> fallback
                    else -> JsonNull
                }
            }
        }
        return when (value) {
            is JsonArray -> JsonArray(value.map { resolveElement(it, values, fallbacks) })
            is JsonObject -> JsonObject(value.mapValues { (_, child) -> resolveElement(child, values, fallbacks) })
            else -> value
        }
    }

    private fun read(context: JsonObject, path: String): JsonElement? {
        var current: JsonElement = context
        for (segment in path.split('.')) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private fun sameType(left: JsonElement, right: JsonElement): Boolean = type(left) == type(right)

    private fun type(value: JsonElement): ValueType = when (value) {
        JsonNull -> ValueType.NULL
        is JsonArray -> ValueType.ARRAY
        is JsonObject -> ValueType.OBJECT
        is JsonPrimitive -> when {
            value.isString -> ValueType.STRING
            value.booleanOrNull != null -> ValueType.BOOLEAN
            value.doubleOrNull != null -> ValueType.NUMBER
            else -> ValueType.NULL
        }
    }

    private fun merge(base: JsonObject?, override: JsonObject): JsonObject = JsonObject(
        (base?.toMutableMap() ?: mutableMapOf()).apply {
            override.forEach { (key, value) ->
                if (value === JsonNull) return@forEach
                val current = this[key]
                this[key] = if (current is JsonObject && value is JsonObject) merge(current, value) else value
            }
        },
    )

    private enum class ValueType { NULL, BOOLEAN, NUMBER, STRING, ARRAY, OBJECT }
}
