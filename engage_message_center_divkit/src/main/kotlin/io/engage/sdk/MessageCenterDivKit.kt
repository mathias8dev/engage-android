package io.engage.sdk

import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule

/** Opens the optional Engage-provided Message Center Activity. */
public fun MessageCenter.display() {
    EngageMessageCenterDivKitModule.requireRuntime().display(this)
}
