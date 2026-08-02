package io.engage.sdk.messagecenter

import io.engage.sdk.Inbox
import io.engage.sdk.MessageCenter
import io.engage.sdk.spi.EngageModuleContext

internal class DefaultMessageCenter(context: EngageModuleContext) : MessageCenter {
    override val inbox: Inbox = DefaultInbox(context)
}

