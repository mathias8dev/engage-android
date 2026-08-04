package io.engage.sdk.push.fcm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal fun interface PushImageLoader {
    suspend fun load(url: String): Bitmap?
}

internal object HttpPushImageLoader : PushImageLoader {
    override suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = URI.create(url)
            require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true))
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = true
                connection.connect()
                require(connection.responseCode in 200..299)
                val declaredSize = connection.contentLength
                require(declaredSize < 0 || declaredSize.toLong() <= MAX_IMAGE_BYTES)
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private const val CONNECT_TIMEOUT_MILLIS = 5_000
    private const val READ_TIMEOUT_MILLIS = 10_000
    private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
}
