package io.engage.sdk.core.data

import io.engage.sdk.core.domain.BindingCodeResponse
import io.engage.sdk.core.domain.BootstrapRequest
import io.engage.sdk.core.domain.InstallationSession
import io.engage.sdk.core.domain.InstallationStateResponse
import io.engage.sdk.core.domain.MobileEdgeApi
import io.engage.sdk.core.domain.OperationBatchRequest
import io.engage.sdk.core.domain.OperationBatchResponse
import io.engage.sdk.core.domain.SyncRequest
import io.engage.sdk.core.domain.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

internal class OkHttpMobileEdgeApi(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) : MobileEdgeApi {
    override suspend fun bootstrap(endpoint: URI, appKey: String, request: BootstrapRequest): InstallationSession =
        execute(
            Request.Builder()
                .url(endpoint.resolve("sdk/installations").toURL())
                .header("X-Engage-App-Key", appKey)
                .post(json.encodeToString(request).jsonBody())
                .build(),
            InstallationSession.serializer(),
        )

    override suspend fun issueBindingCode(endpoint: URI, credential: String): BindingCodeResponse =
        execute(
            authorized(endpoint.resolve("sdk/installation/binding-code"), credential)
                .post(EMPTY_BODY)
                .build(),
            BindingCodeResponse.serializer(),
        )

    override suspend fun getInstallation(endpoint: URI, credential: String): InstallationStateResponse =
        execute(
            authorized(endpoint.resolve("sdk/installation"), credential)
                .get()
                .build(),
            InstallationStateResponse.serializer(),
        )

    override suspend fun sendOperations(
        endpoint: URI,
        credential: String,
        batch: OperationBatchRequest,
    ): OperationBatchResponse = execute(
        authorized(endpoint.resolve("sdk/operations:batch"), credential)
            .post(json.encodeToString(batch).jsonBody())
            .build(),
        OperationBatchResponse.serializer(),
    )

    override suspend fun synchronize(
        endpoint: URI,
        credential: String,
        request: SyncRequest,
    ): SyncResponse = execute(
        authorized(endpoint.resolve("sdk/sync"), credential)
            .post(json.encodeToString(request).jsonBody())
            .build(),
        SyncResponse.serializer(),
    )

    override suspend fun revoke(
        endpoint: URI,
        revocationCredential: String,
        operationId: String,
    ): Unit = executeNoContent(
        authorized(endpoint.resolve("sdk/privacy/revocations/$operationId"), revocationCredential)
            .put(EMPTY_BODY)
            .build(),
    )

    private fun authorized(uri: URI, credential: String): Request.Builder = Request.Builder()
        .url(uri.toURL())
        .header("Authorization", "Bearer $credential")

    private suspend fun <T> execute(
        request: Request,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MobileEdgeException.from(response.code, body, json)
            try {
                json.decodeFromString(serializer, body)
            } catch (error: SerializationException) {
                throw IOException("Invalid Engage mobile-edge response", error)
            }
        }
    }

    private suspend fun executeNoContent(request: Request): Unit = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MobileEdgeException.from(response.code, body, json)
        }
    }

    private fun String.jsonBody() = toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

internal class MobileEdgeException(
    val statusCode: Int,
    val code: String?,
    override val message: String,
) : IOException(message) {
    companion object {
        fun from(statusCode: Int, body: String, json: Json): MobileEdgeException {
            val problem = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            val code = runCatching { problem?.get("code")?.jsonPrimitive?.content }.getOrNull()
            val message = runCatching { problem?.get("message")?.jsonPrimitive?.content }.getOrNull()
                ?: "Engage mobile edge returned HTTP $statusCode"
            return MobileEdgeException(statusCode, code, message)
        }
    }
}
