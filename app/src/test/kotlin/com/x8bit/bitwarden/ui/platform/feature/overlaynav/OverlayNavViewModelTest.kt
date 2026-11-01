package com.x8bit.bitwarden.ui.platform.feature.overlaynav

import app.cash.turbine.test
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.x8bit.bitwarden.data.platform.manager.network.NetworkPermissionManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OverlayNavViewModelTest : BaseViewModelTest() {
    private val mutableAccessibilityDisclaimerFlow = MutableStateFlow(true)
    private val settingsRepository: SettingsRepository = mockk {
        every { hasShownAccessibilityDisclaimerFlow } returns mutableAccessibilityDisclaimerFlow
    }
    private val mutableLocalNetworkAccessFlow = MutableStateFlow(false)
    private val networkPermissionManager: NetworkPermissionManager = mockk {
        every { isLocalNetworkAccessRequiredStateFlow } returns mutableLocalNetworkAccessFlow
    }
    private val mutableShouldRotateBiometricKeyFlow = bufferedMutableSharedFlow<Unit>()
    private val vaultRepository: VaultRepository = mockk {
        every { shouldRotateBiometricKey } returns mutableShouldRotateBiometricKeyFlow
    }

    @Suppress("MaxLineLength")
    @Test
    fun `when accessibility disclaimer flow is false should emit NavigateToAccessibilityDisclosure`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.eventFlow.test {
                expectNoEvents()
                mutableAccessibilityDisclaimerFlow.value = false
                assertEquals(OverlayNavEvent.NavigateToAccessibilityDisclosure, awaitItem())
            }
        }

    @Test
    fun `when local network access flow is true should emit NavigateToLocalNetworkAccess`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.eventFlow.test {
                expectNoEvents()
                mutableLocalNetworkAccessFlow.value = true
                assertEquals(OverlayNavEvent.NavigateToLocalNetworkAccess, awaitItem())
            }
        }

    @Test
    fun `when the biometric key should be rotated should emit NavigateToUpdateBiometrics`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.eventFlow.test {
                expectNoEvents()
                mutableShouldRotateBiometricKeyFlow.tryEmit(Unit)
                assertEquals(OverlayNavEvent.NavigateToUpdateBiometrics, awaitItem())
            }
        }

    private fun createViewModel(): OverlayNavViewModel = OverlayNavViewModel(
        networkPermissionManager = networkPermissionManager,
        vaultRepository = vaultRepository,
        settingsRepository = settingsRepository,
    )
}
