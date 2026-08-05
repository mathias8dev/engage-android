package io.engage.sdk.core.application

import io.engage.sdk.ActionArguments
import io.engage.sdk.ActionHandler
import io.engage.sdk.ActionResult
import io.engage.sdk.Actions
import io.engage.sdk.EngageAction
import io.engage.sdk.EngageLogger
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

internal class DefaultActions : Actions {
    private val handlers = ConcurrentHashMap<String, ActionHandler>()

    override fun register(name: String, handler: ActionHandler): AutoCloseable {
        require(ACTION_NAME.matches(name)) { "Action names must match ${ACTION_NAME.pattern}" }
        handlers[name] = handler
        EngageLogger.info("Actions", "handler registered name=$name")
        return AutoCloseable {
            val removed = handlers.remove(name, handler)
            EngageLogger.info("Actions", "handler unregistered name=$name removed=$removed")
        }
    }

    suspend fun execute(name: String, arguments: JsonObject): Boolean {
        val handler = handlers[name] ?: run {
            EngageLogger.warn("Actions", "execution rejected name=$name reason=no_handler")
            return false
        }
        EngageLogger.debug("Actions", "execution started name=$name argumentKeys=${arguments.keys.sorted()}")
        return runCatching {
            handler.execute(EngageAction(name, ActionArguments(arguments))) == ActionResult.COMPLETED
        }.onSuccess { completed ->
            EngageLogger.info("Actions", "execution finished name=$name completed=$completed")
        }.onFailure { error ->
            EngageLogger.error("Actions", "execution failed name=$name", error)
        }.getOrThrow()
    }

    private companion object {
        val ACTION_NAME = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}
