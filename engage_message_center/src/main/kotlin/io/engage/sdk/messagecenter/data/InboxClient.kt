package io.engage.sdk.messagecenter.data

import io.engage.sdk.messagecenter.domain.InboxRendering
import io.engage.sdk.messagecenter.domain.InboxScope
import io.engage.sdk.messagecenter.domain.MutationResult
import io.engage.sdk.messagecenter.domain.MutationStatus
import io.engage.sdk.messagecenter.domain.RemoteInboxEntry
import io.engage.sdk.messagecenter.domain.RemoteInboxPage
import io.engage.sdk.messagecenter.domain.ReservedMutationBatch
import io.engage.sdk.spi.EngageHttpMethod
import io.engage.sdk.spi.EngageHttpRequest
import io.engage.sdk.spi.EngageModuleContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant

internal class InboxClient(private val context: EngageModuleContext) {
    suspend fun page(cursor: String?, pageSize: Int): RemoteInboxPage {
        val response = context.authorizedRequest(
            EngageHttpRequest(
                method = EngageHttpMethod.GET,
                path = "sdk/inbox",
                query = buildMap {
                    put("pageSize", pageSize.toString())
                    cursor?.let { put("cursor", it) }
                },
            ),
        )
        val body = response.requireSuccess()
        return RemoteInboxPage(
            entries = body.requiredArray("entries").map { element ->
                val entry = element as? JsonObject ?: invalidResponse("Inbox entry must be an object")
                RemoteInboxEntry(
                    id = entry.requiredString("id"),
                    key = entry.requiredString("key"),
                    payload = entry["payload"] as? JsonObject ?: invalidResponse("Inbox payload must be an object"),
                    scope = entry.enum("scope", InboxScope.INSTALLATION),
                    sentAt = entry.requiredInstant("sentAt"),
                    expiresAt = entry.instant("expiresAt"),
                    readAt = entry.instant("readAt"),
                )
            },
            nextCursor = body.string("nextCursor"),
            hasMore = body.boolean("hasMore") ?: invalidResponse("Inbox hasMore is missing"),
            unreadCount = body.int("unreadCount")?.coerceAtLeast(0)
                ?: invalidResponse("Inbox unreadCount is missing"),
        )
    }

    suspend fun mutate(batch: ReservedMutationBatch): List<MutationResult> {
        val response = context.authorizedRequest(
            EngageHttpRequest(
                EngageHttpMethod.POST,
                "sdk/inbox/operations:batch",
                body = buildJsonObject {
                    put("batchId", batch.batchId)
                    put("generation", batch.generation)
                    put("operations", buildJsonArray {
                        batch.operations.forEach { operation ->
                            add(buildJsonObject {
                                put("operationId", operation.operationId)
                                put("type", operation.type.name)
                                operation.entryId?.let { put("entryId", it) }
                            })
                        }
                    })
                },
            ),
        )
        val body = response.requireSuccess()
        require(body.requiredString("batchId") == batch.batchId) { "Inbox returned another batchId" }
        return body.requiredArray("results").map { element ->
            val result = element as? JsonObject ?: invalidResponse("Inbox mutation result must be an object")
            MutationResult(
                operationId = result.requiredString("operationId"),
                status = result.enum("status", MutationStatus.REJECTED),
                errorCode = result.string("errorCode"),
                message = result.string("message"),
            )
        }
    }

    suspend fun renderings(entryIds: List<String>): List<InboxRendering> {
        if (entryIds.isEmpty()) return emptyList()
        val response = context.authorizedRequest(
            EngageHttpRequest(
                EngageHttpMethod.POST,
                "sdk/inbox/renderings:resolve",
                body = buildJsonObject {
                    put("entryIds", buildJsonArray {
                        entryIds.distinct().take(100).forEach { add(JsonPrimitive(it)) }
                    })
                },
            ),
        )
        return response.requireSuccess().requiredArray("renderings").map { element ->
            val rendering = element as? JsonObject ?: invalidResponse("Inbox rendering must be an object")
            InboxRendering(
                entryId = rendering.requiredString("entryId"),
                renderer = rendering.requiredString("renderer"),
                revision = rendering.long("revision") ?: invalidResponse("Rendering revision is missing"),
                document = rendering["document"] as? JsonObject ?: invalidResponse("Rendering document is missing"),
            )
        }
    }
}

internal class InboxHttpException(
    val statusCode: Int,
    val code: String?,
    override val message: String,
) : IOException(message)

internal class InboxInvalidResponseException(message: String, cause: Throwable? = null) : IOException(message, cause)

private fun io.engage.sdk.spi.EngageHttpResponse.requireSuccess(): JsonObject {
    val value = body
    if (!isSuccessful) {
        throw InboxHttpException(
            statusCode,
            value?.string("code"),
            value?.string("message") ?: "Engage Inbox returned HTTP $statusCode",
        )
    }
    return value ?: invalidResponse("Engage Inbox returned an empty response")
}

private fun JsonObject.requiredArray(key: String): JsonArray = get(key) as? JsonArray ?: invalidResponse("Missing $key")
private fun JsonObject.string(key: String): String? {
    val element = get(key)
    if (element == null || element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}
private fun JsonObject.requiredString(key: String): String = string(key) ?: invalidResponse("Missing $key")
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.instant(key: String): Instant? = string(key)?.let { raw ->
    try {
        Instant.parse(raw)
    } catch (error: Exception) {
        throw InboxInvalidResponseException("Invalid $key timestamp", error)
    }
}
private fun JsonObject.requiredInstant(key: String): Instant = instant(key) ?: invalidResponse("Missing $key")
private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T =
    string(key)?.let { raw ->
        runCatching { enumValueOf<T>(raw) }.getOrElse { invalidResponse("Invalid $key") }
    } ?: default
private fun invalidResponse(message: String): Nothing = throw InboxInvalidResponseException(message)
