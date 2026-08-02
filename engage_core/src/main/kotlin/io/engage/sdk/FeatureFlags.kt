package io.engage.sdk

import kotlinx.serialization.KSerializer

public interface FeatureFlags {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun getNumber(key: String, default: Double): Double
    fun <T> getJson(key: String, serializer: KSerializer<T>, default: T): T
}

