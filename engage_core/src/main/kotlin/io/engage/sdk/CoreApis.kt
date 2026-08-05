package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public interface Installation {
    val id: StateFlow<String?>

    suspend fun issueBindingCode(): String
    suspend fun editAttributes(block: AttributeEditor.() -> Unit)
    suspend fun editSubscriptions(block: InstallationSubscriptionEditor.() -> Unit)
}

public interface Profile {
    suspend fun editAttributes(block: AttributeEditor.() -> Unit)
    suspend fun editTags(block: TagEditor.() -> Unit)
    suspend fun editSubscriptions(block: ProfileSubscriptionEditor.() -> Unit)
}

public class TagEditor internal constructor() {
    private val additions = linkedSetOf<String>()
    private val removals = linkedSetOf<String>()

    public fun add(tag: String) {
        validateTag(tag)
        removals.remove(tag)
        additions += tag
        EngageLogger.verbose("Profile", "tag add requested length=${tag.length}")
    }

    public fun remove(tag: String) {
        validateTag(tag)
        additions.remove(tag)
        removals += tag
        EngageLogger.verbose("Profile", "tag remove requested length=${tag.length}")
    }

    internal fun build(): TagChanges = TagChanges(additions.toSet(), removals.toSet())
}

internal data class TagChanges(val additions: Set<String>, val removals: Set<String>) {
    val isEmpty: Boolean get() = additions.isEmpty() && removals.isEmpty()
}

public interface Events {
    suspend fun track(name: String, block: EventEditor.() -> Unit = {})
    suspend fun trackScreen(screenKey: String)
    suspend fun clearScreen()
    suspend fun flush()
}

private fun validateTag(tag: String) {
    require(tag.isNotBlank() && tag.length <= 64) { "Tags must contain between 1 and 64 characters" }
}
