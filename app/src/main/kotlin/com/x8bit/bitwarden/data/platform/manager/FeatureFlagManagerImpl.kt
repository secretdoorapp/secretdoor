package com.x8bit.bitwarden.data.platform.manager

import com.bitwarden.core.data.manager.model.FlagKey
import com.bitwarden.data.datasource.disk.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private const val CIPHER_KEY_ENCRYPTION_KEY = "enableCipherKeyEncryption"

/**
 * Primary implementation of [FeatureFlagManager].
 *
 * SecretDoor has no server to provide feature flags, so every flag uses its built-in default
 * value and cipher key encryption stays off.
 */
class FeatureFlagManagerImpl : FeatureFlagManager {

    override val sdkFeatureFlags: Map<String, Boolean> = mapOf(CIPHER_KEY_ENCRYPTION_KEY to false)

    override fun <T : Any> getFeatureFlagFlow(key: FlagKey<T>): Flow<T> = flowOf(key.defaultValue)

    override suspend fun <T : Any> getFeatureFlag(
        key: FlagKey<T>,
        forceRefresh: Boolean,
    ): T = key.defaultValue

    override fun <T : Any> getFeatureFlag(key: FlagKey<T>): T = key.defaultValue
}

/**
 * Extract the value of a [FlagKey] from the [ServerConfig]. If there is an issue with retrieving
 * or if the value is null, the default value will be returned.
 */
fun <T : Any> ServerConfig?.getFlagValueOrDefault(key: FlagKey<T>): T {
    val defaultValue = key.defaultValue
    return this
        ?.serverData
        ?.featureStates
        ?.get(key.keyName)
        ?.let {
            try {
                // Suppressed since we are checking the type before doing the cast
                @Suppress("UNCHECKED_CAST")
                when (defaultValue::class) {
                    Boolean::class -> it.content.toBoolean() as T
                    String::class -> it.content as T
                    Int::class -> it.content.toInt() as T
                    else -> defaultValue
                }
            } catch (_: ClassCastException) {
                defaultValue
            } catch (_: NumberFormatException) {
                defaultValue
            }
        }
        ?: defaultValue
}
