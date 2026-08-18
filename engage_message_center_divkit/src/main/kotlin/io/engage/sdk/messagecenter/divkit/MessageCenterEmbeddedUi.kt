package io.engage.sdk.messagecenter.divkit

/** Closed error vocabulary emitted by the reusable Message Center views. */
public enum class MessageCenterViewErrorCode {
    INBOX,
    RENDERING,
}

public data class MessageCenterViewError(
    val code: MessageCenterViewErrorCode,
    val message: String,
    val isRetryable: Boolean,
)
