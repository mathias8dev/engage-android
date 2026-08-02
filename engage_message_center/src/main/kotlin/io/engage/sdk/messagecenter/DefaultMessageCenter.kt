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

    override suspend fun resolveRenderings(entryIds: List<InboxEntryId>): List<InboxRenderingSnapshot> =
        client.renderings(entryIds.map(InboxEntryId::value)).map { rendering ->
            InboxRenderingSnapshot(
                InboxEntryId(rendering.entryId),
                rendering.renderer,
                rendering.revision,
                rendering.document,
            )
        }

    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean =
        context.executeAction(name, arguments)

    suspend fun wipe() {
        (inbox as DefaultInbox).wipe()
    }
}
