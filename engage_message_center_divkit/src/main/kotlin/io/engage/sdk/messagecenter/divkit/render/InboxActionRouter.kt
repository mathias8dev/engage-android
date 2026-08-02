package io.engage.sdk.messagecenter.divkit.render

import android.net.Uri
import io.engage.sdk.Inbox
import io.engage.sdk.InboxEntryId
import io.engage.sdk.MessageCenterRenderingSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class InboxActionRouter(
    private val inbox: Inbox,
    private val renderingSupport: MessageCenterRenderingSupport,
) {
    fun supports(uri: Uri): Boolean = uri.scheme == ENGAGE_SCHEME && when (uri.host) {
        MARK_READ, MARK_UNREAD, DELETE -> true
        ACTION -> !uri.pathSegments.firstOrNull().isNullOrBlank()
        else -> false
    }

    suspend fun handle(uri: Uri, entryId: InboxEntryId): Boolean {
        if (!supports(uri)) return false
        return when (uri.host) {
            MARK_READ -> {
                inbox.markRead(entryId)
                true
            }
            MARK_UNREAD -> {
                inbox.markUnread(entryId)
                true
            }
            DELETE -> {
                inbox.delete(entryId)
                true
            }
            ACTION -> {
                val name = requireNotNull(uri.pathSegments.firstOrNull())
                val arguments = uri.getQueryParameter(ARGUMENTS)?.let(::decodeArguments) ?: JsonObject(emptyMap())
                inbox.markRead(entryId)
                renderingSupport.executeAction(name, arguments)
                true
            }
            else -> false
        }
    }

    suspend fun markOpened(entryId: InboxEntryId) {
        inbox.markRead(entryId)
    }

    private fun decodeArguments(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Engage action arguments", error)
    }

    private companion object {
        const val ENGAGE_SCHEME = "engage"
        const val MARK_READ = "mark-read"
        const val MARK_UNREAD = "mark-unread"
        const val DELETE = "delete"
        const val ACTION = "action"
        const val ARGUMENTS = "arguments"
    }
}
