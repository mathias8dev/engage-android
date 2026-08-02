package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public interface Installation {
    val id: StateFlow<String?>

    suspend fun issueBindingCode(): String
    fun editAttributes(block: AttributeEditor.() -> Unit)
    fun editSubscriptions(block: InstallationSubscriptionEditor.() -> Unit)
}

public interface Profile {
    fun editAttributes(block: AttributeEditor.() -> Unit)
    fun editTags(block: TagEditor.() -> Unit)
    fun editSubscriptions(block: ProfileSubscriptionEditor.() -> Unit)
}

public class TagEditor internal constructor() {
    private val additions = linkedSetOf<String>()
    private val removals = linkedSetOf<String>()

    public fun add(tag: String) {
        validateTag(tag)
        removals.remove(tag)
        additions += tag
    }

    public fun remove(tag: String) {
        validateTag(tag)
        additions.remove(tag)
        removals += tag
    }

    internal fun build(): TagChanges = TagChanges(additions.toSet(), removals.toSet())
}

internal data class TagChanges(val additions: Set<String>, val removals: Set<String>) {
    val isEmpty: Boolean get() = additions.isEmpty() && removals.isEmpty()
}

public interface Events {
    fun track(name: String, block: EventEditor.() -> Unit = {})
    fun trackScreen(screenKey: String)
    fun clearScreen()
    suspend fun flush()
}

private fun validateTag(tag: String) {
    require(tag.isNotBlank() && tag.length <= 64) { "Tags must contain between 1 and 64 characters" }
}

