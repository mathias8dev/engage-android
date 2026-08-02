package io.engage.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

public class AttributeEditor internal constructor() {
    private val values = linkedMapOf<String, JsonElement>()
    private val removals = linkedSetOf<String>()

    public fun set(key: String, value: String): Unit = setValue(key, JsonPrimitive(value))
    public fun set(key: String, value: Boolean): Unit = setValue(key, JsonPrimitive(value))
    public fun set(key: String, value: Int): Unit = setValue(key, JsonPrimitive(value))
    public fun set(key: String, value: Long): Unit = setValue(key, JsonPrimitive(value))
    public fun set(key: String, value: Double): Unit = setValue(key, JsonPrimitive(value))
    public fun set(key: String, value: LocalDate): Unit = setValue(key, JsonPrimitive(value.toString()))
    public fun set(key: String, value: Instant): Unit = setValue(key, JsonPrimitive(value.toString()))
    public fun setJson(key: String, value: JsonElement): Unit = setValue(key, value)

    public fun remove(key: String) {
        validateAttributeKey(key)
        values.remove(key)
        removals += key
    }

    internal fun build(): AttributeChanges = AttributeChanges(values.toMap(), removals.toSet())

    private fun setValue(key: String, value: JsonElement) {
        validateAttributeKey(key)
        removals.remove(key)
        values[key] = value
    }
}

internal data class AttributeChanges(
    val values: Map<String, JsonElement>,
    val removals: Set<String>,
) {
    val isEmpty: Boolean get() = values.isEmpty() && removals.isEmpty()
}

internal fun validateAttributeKey(key: String) {
    require(ATTRIBUTE_KEY.matches(key)) {
        "Attribute keys must match ${ATTRIBUTE_KEY.pattern}"
    }
}

private val ATTRIBUTE_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")

