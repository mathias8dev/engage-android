package io.engage.sdk.messagecenter.divkit.domain

import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSnapshot

internal sealed interface RenderingResolution {
    val entryId: InboxEntryId

    data class Available(val snapshot: InboxRenderingSnapshot) : RenderingResolution {
        override val entryId: InboxEntryId get() = snapshot.entryId
    }

    data class Unavailable(override val entryId: InboxEntryId) : RenderingResolution
}
