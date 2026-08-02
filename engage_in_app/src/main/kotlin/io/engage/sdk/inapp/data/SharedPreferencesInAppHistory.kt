package io.engage.sdk.inapp.data

import android.content.Context
import android.content.SharedPreferences
import io.engage.sdk.inapp.domain.ImpressionHistory
import io.engage.sdk.inapp.domain.InAppHistory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

internal class SharedPreferencesInAppHistory(
    context: Context,
    private val generation: () -> Long,
) : InAppHistory {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("engage_in_app_history", Context.MODE_PRIVATE)

    override val sessionId: Long
        @Synchronized get() = preferences.getLong(globalKey("session_id"), 0)

    override val sessionCount: Int
        @Synchronized get() = preferences.getInt(globalKey("session_count"), 0)

    @Synchronized
    override fun beginSession(): Long {
        val nextId = sessionId + 1
        preferences.edit()
            .putLong(globalKey("session_id"), nextId)
            .putInt(globalKey("session_count"), sessionCount + 1)
            .apply()
        return nextId
    }

    @Synchronized
    override fun history(campaignKey: String): ImpressionHistory {
        val key = campaignKey(campaignKey)
        return ImpressionHistory(
            total = preferences.getInt("$key.total", 0),
            sessionId = preferences.getLong("$key.session_id", -1),
            sessionCount = preferences.getInt("$key.session_count", 0),
            day = preferences.getString("$key.day", null),
            dayCount = preferences.getInt("$key.day_count", 0),
            lastImpressionAt = preferences.getLong("$key.impression_at", -1).toInstantOrNull(),
            lastDismissedAt = preferences.getLong("$key.dismissed_at", -1).toInstantOrNull(),
        )
    }

    @Synchronized
    override fun recordImpression(campaignKey: String, at: Instant) {
        val current = history(campaignKey)
        val key = campaignKey(campaignKey)
        val day = at.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        preferences.edit()
            .putInt("$key.total", current.total + 1)
            .putLong("$key.session_id", sessionId)
            .putInt("$key.session_count", if (current.sessionId == sessionId) current.sessionCount + 1 else 1)
            .putString("$key.day", day)
            .putInt("$key.day_count", if (current.day == day) current.dayCount + 1 else 1)
            .putLong("$key.impression_at", at.toEpochMilli())
            .apply()
    }

    @Synchronized
    override fun recordDismiss(campaignKey: String, at: Instant) {
        preferences.edit().putLong("${campaignKey(campaignKey)}.dismissed_at", at.toEpochMilli()).apply()
    }

    @Synchronized
    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private fun globalKey(suffix: String): String = "g${generation()}.$suffix"
    private fun campaignKey(value: String): String = "g${generation()}.campaign.${value.sha256()}"
}

private fun Long.toInstantOrNull(): Instant? = takeIf { it >= 0 }?.let(Instant::ofEpochMilli)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
