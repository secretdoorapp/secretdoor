package com.x8bit.bitwarden.ui.platform.feature.overlaynav

import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.base.DeferredBackgroundEvent
import com.x8bit.bitwarden.data.platform.manager.network.NetworkPermissionManager
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Manages the overlay navigation, hosting the root-navigation and any screen that can overlay it.
 */
@HiltViewModel
class OverlayNavViewModel @Inject constructor(
    networkPermissionManager: NetworkPermissionManager,
    vaultRepository: VaultRepository,
) : BaseViewModel<Unit, OverlayNavEvent, OverlayNavAction>(initialState = Unit) {
    init {
        networkPermissionManager
            .isLocalNetworkAccessRequiredStateFlow
            .filter { it }
            .map { OverlayNavAction.Internal.LocalNetworkAccessRequired }
            .onEach(::sendAction)
            .launchIn(viewModelScope)

        vaultRepository
            .shouldRotateBiometricKey
            .map { OverlayNavAction.Internal.RotateBiometricsKey }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: OverlayNavAction) {
        when (action) {
            is OverlayNavAction.Internal -> handleInternal(action)
        }
    }

    private fun handleInternal(action: OverlayNavAction.Internal) {
        when (action) {
            OverlayNavAction.Internal.LocalNetworkAccessRequired -> {
                handleLocalNetworkAccessRequired()
            }

            OverlayNavAction.Internal.RotateBiometricsKey -> handleRotateBiometricsKey()
        }
    }

    private fun handleLocalNetworkAccessRequired() {
        sendEvent(OverlayNavEvent.NavigateToLocalNetworkAccess)
    }

    private fun handleRotateBiometricsKey() {
        sendEvent(OverlayNavEvent.NavigateToUpdateBiometrics)
    }
}

/**
 * Models events for the overlay navigation screen.
 */
sealed class OverlayNavEvent {
    /**
     * Navigate to the Update Biometrics screen.
     */
    data object NavigateToUpdateBiometrics : OverlayNavEvent(), DeferredBackgroundEvent

    /**
     * Navigate to the local network access screen.
     */
    data object NavigateToLocalNetworkAccess : OverlayNavEvent(), DeferredBackgroundEvent
}

/**
 * Models actions for the overlay navigation screen.
 */
sealed class OverlayNavAction {
    /**
     * Internal ViewModel actions.
     */
    sealed class Internal : OverlayNavAction() {

        /**
         * Indicates that the local network access is required.
         */
        data object LocalNetworkAccessRequired : Internal()

        /**
         * Indicates that Biometrics needs to be re-initialized for key rotation.
         */
        data object RotateBiometricsKey : Internal()
    }
}
