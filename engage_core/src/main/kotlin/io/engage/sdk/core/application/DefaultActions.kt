package io.engage.sdk.core.application

import io.engage.sdk.ActionArguments
import io.engage.sdk.ActionHandler
import io.engage.sdk.ActionResult
import io.engage.sdk.Actions
import io.engage.sdk.EngageAction
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

internal class DefaultActions : Actions {
    private val handlers = ConcurrentHashMap<String, ActionHandler>()

    override fun register(name: String, handler: ActionHandler): AutoCloseable {
        require(ACTION_NAME.matches(name)) { "Action names must match ${ACTION_NAME.pattern}" }
        handlers[name] = handler
        return AutoCloseable { handlers.remove(name, handler) }
    }

    suspend fun execute(name: String, arguments: JsonObject): Boolean {
        val handler = handlers[name] ?: return false
        return handler.execute(EngageAction(name, ActionArguments(arguments))) == ActionResult.COMPLETED
    }

    private companion object {
        val ACTION_NAME = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

