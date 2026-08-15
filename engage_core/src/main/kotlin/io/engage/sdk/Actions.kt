package io.engage.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class EngageAction(
    val name: String,
    val arguments: ActionArguments,
)

public class ActionArguments internal constructor(private val values: JsonObject) {
    public fun getString(key: String): String? = (values[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

    public fun requireString(key: String): String = requireNotNull(getString(key)) {
        "Missing action argument: $key"
    }

    public fun asJson(): JsonObject = values
}

public enum class ActionResult {
    COMPLETED,
    REJECTED,
}

public fun interface ActionHandler {
    public suspend fun execute(action: EngageAction): ActionResult
}

public interface Actions {
    fun register(name: String, handler: ActionHandler): AutoCloseable
}
