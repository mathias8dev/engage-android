package io.engage.sdk

public enum class Channel {
    PUSH,
    EMAIL,
    SMS,
    WHATSAPP,
}

public class ProfileSubscriptionEditor internal constructor() {
    private val changes = linkedMapOf<Pair<String, Channel>, Boolean>()

    public fun subscribe(list: String, channels: Set<Channel>) {
        edit(list, channels, subscribed = true)
    }

    public fun unsubscribe(list: String, channels: Set<Channel>) {
        edit(list, channels, subscribed = false)
    }

    internal fun build(): List<ProfileSubscriptionChange> = changes.map { (target, subscribed) ->
        ProfileSubscriptionChange(target.first, target.second, subscribed)
    }

    private fun edit(list: String, channels: Set<Channel>, subscribed: Boolean) {
        validateSubscriptionKey(list)
        require(channels.isNotEmpty()) { "At least one channel is required" }
        channels.forEach { channel -> changes[list to channel] = subscribed }
    }
}

public class InstallationSubscriptionEditor internal constructor() {
    private val changes = linkedMapOf<String, Boolean>()

    public fun subscribe(list: String) {
        validateSubscriptionKey(list)
        changes[list] = true
    }

    public fun unsubscribe(list: String) {
        validateSubscriptionKey(list)
        changes[list] = false
    }

    internal fun build(): List<InstallationSubscriptionChange> = changes.map { (list, subscribed) ->
        InstallationSubscriptionChange(list, subscribed)
    }
}

internal data class ProfileSubscriptionChange(
    val list: String,
    val channel: Channel,
    val subscribed: Boolean,
)

internal data class InstallationSubscriptionChange(
    val list: String,
    val subscribed: Boolean,
)

internal fun validateSubscriptionKey(key: String) {
    require(SUBSCRIPTION_KEY.matches(key)) {
        "Subscription keys must match ${SUBSCRIPTION_KEY.pattern}"
    }
}

private val SUBSCRIPTION_KEY = Regex("^[a-z][a-z0-9_.-]{0,127}$")

