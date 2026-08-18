package io.engage.sdk.messagecenter

import io.engage.sdk.Inbox
import io.engage.sdk.MessageCenter
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSnapshot
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.messagecenter.data.InboxClient
import io.engage.sdk.spi.EngageModuleContext
import kotlinx.serialization.json.JsonObject

internal class DefaultMessageCenter(private val context: EngageModuleContext) : MessageCenter, MessageCenterRenderingSupport {
    private val client = InboxClient(context)
    override val inbox: Inbox = DefaultInbox(context, client = client)

    init {
        context.logInfo("MessageCenter", "initialized generation=${context.generation.value}")
    }

    override suspend fun resolveRenderings(entryIds: List<InboxEntryId>): List<InboxRenderingSnapshot> {
        context.logDebug("MessageCenter", "renderings resolving count=${entryIds.size}")
        return client.renderings(entryIds.map(InboxEntryId::value)).map { rendering ->
            InboxRenderingSnapshot(
                InboxEntryId(rendering.entryId),
                rendering.renderer,
                rendering.revision,
                rendering.surfaces,
            )
        }
            .also { context.logInfo("MessageCenter", "renderings resolved count=${it.size}") }
    }

    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean {
        context.logInfo("MessageCenter", "action requested name=$name argumentKeys=${arguments.keys.sorted()}")
        return context.executeAction(name, arguments).also { completed ->
            context.logInfo("MessageCenter", "action finished name=$name completed=$completed")
        }
    }

    suspend fun wipe() {
        context.logWarn("MessageCenter", "local state wipe started")
        (inbox as DefaultInbox).wipe()
        context.logWarn("MessageCenter", "local state wiped")
    }
}
