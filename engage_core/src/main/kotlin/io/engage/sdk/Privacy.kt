package io.engage.sdk

import kotlinx.coroutines.flow.StateFlow

public enum class PrivacyState {
    OPTED_IN,
    OPTED_OUT,
}

public interface Privacy {
    val state: StateFlow<PrivacyState>

    suspend fun optIn()
    suspend fun optOut()
    suspend fun optOutAndWipe()
}

