package io.engage.sdk.core.data

import androidx.test.core.app.ApplicationProvider
import io.engage.sdk.core.domain.OperationResult
import io.engage.sdk.core.domain.OperationStatus
import io.engage.sdk.core.domain.OperationType
import io.engage.sdk.core.domain.SdkOperation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteOperationOutboxTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val outbox = SqliteOperationOutbox(context) { "batch-stable" }

    @After
    fun close() {
        outbox.close()
        context.deleteDatabase("engage_operations.db")
    }

    @Test
    fun `reservation is stable until terminal result`() = runTest {
        outbox.enqueue(operation("operation-1"))

        val first = outbox.reserve(100)
        val retry = outbox.reserve(100)

        assertEquals(first, retry)
        assertEquals("batch-stable", first?.batchId)

        outbox.settle(
            "batch-stable",
            listOf(OperationResult("operation-1", OperationStatus.ACCEPTED)),
        )
        assertNull(outbox.reserve(100))
    }

    @Test
    fun `an idempotency key cannot silently replace another operation`() = runTest {
        outbox.enqueue(operation("operation-1"))

        val failure = runCatching {
            outbox.enqueue(
                operation("operation-1").copy(
                    payload = buildJsonObject { put("name", "another_event") },
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("order_completed", outbox.pending.value.single().payload["name"].toString().trim('"'))
    }

    private fun operation(id: String) = SdkOperation(
        operationId = id,
        generation = 2,
        type = OperationType.EVENT_TRACKED,
        occurredAt = "2026-08-02T12:00:00Z",
        payload = buildJsonObject { put("name", "order_completed") },
    )
}
