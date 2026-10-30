package com.x8bit.bitwarden.data.platform.manager

import app.cash.turbine.test
import com.bitwarden.core.data.manager.model.FlagKey
import com.x8bit.bitwarden.data.platform.repository.util.FakeServerConfigRepository
import com.x8bit.bitwarden.data.platform.util.isServerVersionAtLeast
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class FeatureFlagManagerTest {
    private val fakeServerConfigRepository = FakeServerConfigRepository()

    private val manager = FeatureFlagManagerImpl()

    @Test
    fun `sdkFeatureFlags should keep cipher key encryption off`() {
        assertEquals(mapOf("enableCipherKeyEncryption" to false), manager.sdkFeatureFlags)
    }

    @Test
    fun `getFeatureFlag should always return the default value`() = runTest {
        assertEquals(
            FlagKey.DummyBoolean.defaultValue,
            manager.getFeatureFlag(FlagKey.DummyBoolean),
        )
        assertEquals(FlagKey.DummyInt.defaultValue, manager.getFeatureFlag(FlagKey.DummyInt))
        assertEquals(FlagKey.DummyString.defaultValue, manager.getFeatureFlag(FlagKey.DummyString))
        assertEquals(
            FlagKey.DummyBoolean.defaultValue,
            manager.getFeatureFlag(key = FlagKey.DummyBoolean, forceRefresh = true),
        )
    }

    @Test
    fun `getFeatureFlagFlow should emit the default value`() = runTest {
        manager.getFeatureFlagFlow(FlagKey.DummyString).test {
            assertEquals(FlagKey.DummyString.defaultValue, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `server version is at least supplied version`() {
        val result =
            isServerVersionAtLeast(
                fakeServerConfigRepository.serverConfigStateFlow.value,
                "2024.2.0",
            )

        // This relies on the fake server version being "2024.7.0"
        assertTrue(result)
    }

    @Test
    fun `server version is not at least supplied version`() {
        val result =
            isServerVersionAtLeast(
                fakeServerConfigRepository.serverConfigStateFlow.value,
                "2024.12.0-suffix",
            )

        // This relies on the fake server version being "2024.7.0"
        assertFalse(result)
    }

    @Test
    fun `server version is the same as supplied version`() {
        val result =
            isServerVersionAtLeast(
                fakeServerConfigRepository.serverConfigStateFlow.value,
                "2024.7.0",
            )

        // This relies on the fake server version being "2024.7.0"
        assertTrue(result)
    }

    @Test
    fun `server version is not the same as blank supplied version`() {
        val result =
            isServerVersionAtLeast(
                fakeServerConfigRepository.serverConfigStateFlow.value,
                "",
            )

        assertFalse(result)
    }
}
