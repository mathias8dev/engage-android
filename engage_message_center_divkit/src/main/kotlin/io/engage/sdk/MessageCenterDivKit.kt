package io.engage.sdk

import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule

/** Opens the optional Engage-provided Message Center Activity. */
public fun MessageCenter.display() {
    EngageLogger.info("MessageCenter.UI", "display requested")
    EngageMessageCenterDivKitModule.requireRuntime().display(this)
}
