package io.engage.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

public class EventEditor internal constructor() {
    private val properties = linkedMapOf<String, JsonElement>()

    public var value: Double? = null
    public var transactionId: String? = null

    public fun put(key: String, value: String): Unit = putValue(key, JsonPrimitive(value))
    public fun put(key: String, value: Boolean): Unit = putValue(key, JsonPrimitive(value))
    public fun put(key: String, value: Int): Unit = putValue(key, JsonPrimitive(value))
    public fun put(key: String, value: Long): Unit = putValue(key, JsonPrimitive(value))
    public fun put(key: String, value: Double): Unit = putValue(key, JsonPrimitive(value))
    public fun putJson(key: String, value: JsonElement): Unit = putValue(key, value)

    internal fun build(): EventData {
        require(transactionId == null || transactionId!!.length <= 255) {
            "transactionId must contain at most 255 characters"
        }
        return EventData(properties.toMap(), value, transactionId)
    }

    private fun putValue(key: String, value: JsonElement) {
        validateAttributeKey(key)
        properties[key] = value
    }
}

internal data class EventData(
    val properties: Map<String, JsonElement>,
    val value: Double?,
    val transactionId: String?,
)

internal fun validateEventName(name: String) {
    require(EVENT_NAME.matches(name)) { "Event names must match ${EVENT_NAME.pattern}" }
}

internal fun validateScreenKey(key: String) {
    require(SCREEN_KEY.matches(key)) { "Screen keys must match ${SCREEN_KEY.pattern}" }
}

private val EVENT_NAME = Regex("^[a-z][a-z0-9_]{1,63}$")
private val SCREEN_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")

