package io.engage.sdk

import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule

/** Opens the optional Engage-provided Message Center UI, optionally on one entry. */
public fun MessageCenter.display(entryId: InboxEntryId? = null) {
    EngageLogger.info("MessageCenter.UI", "display requested entryId=${entryId ?: "list"}")
    EngageMessageCenterDivKitModule.requireRuntime().display(this, entryId)
}
