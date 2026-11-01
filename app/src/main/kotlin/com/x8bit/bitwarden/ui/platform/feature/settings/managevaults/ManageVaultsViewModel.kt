package com.x8bit.bitwarden.ui.platform.feature.settings.managevaults

import android.os.Parcelable
import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.DeleteVaultResult
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.ui.vault.feature.vault.util.initials
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

/**
 * View model for the manage vaults screen: lists every vault on the device and lets the user
 * rename, delete and add vaults.
 */
@HiltViewModel
class ManageVaultsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : BaseViewModel<ManageVaultsState, ManageVaultsEvent, ManageVaultsAction>(
    initialState = ManageVaultsState(
        vaults = authRepository.userStateFlow.value.toVaultItems(),
        dialog = null,
    ),
) {
    init {
        authRepository
            .userStateFlow
            .map { ManageVaultsAction.Internal.UserStateReceive(userState = it) }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: ManageVaultsAction) {
        when (action) {
            ManageVaultsAction.BackClick -> sendEvent(ManageVaultsEvent.NavigateBack)
            is ManageVaultsAction.RenameClick -> handleRenameClick(action)
            is ManageVaultsAction.RenameSubmit -> handleRenameSubmit(action)
            is ManageVaultsAction.DeleteClick -> handleDeleteClick(action)
            is ManageVaultsAction.DeleteSubmit -> handleDeleteSubmit(action)
            ManageVaultsAction.CreateVaultClick -> authRepository.hasPendingAccountAddition = true
            ManageVaultsAction.ImportBackupClick -> {
                sendEvent(ManageVaultsEvent.NavigateToImportBackup)
            }

            ManageVaultsAction.DismissDialog -> mutableStateFlow.update { it.copy(dialog = null) }
            is ManageVaultsAction.Internal.UserStateReceive -> {
                mutableStateFlow.update {
                    it.copy(vaults = action.userState.toVaultItems())
                }
            }

            is ManageVaultsAction.Internal.DeleteResultReceive -> handleDeleteResult(action)
        }
    }

    private fun handleRenameClick(action: ManageVaultsAction.RenameClick) {
        val vault = state.vaults.firstOrNull { it.userId == action.userId } ?: return
        mutableStateFlow.update {
            it.copy(
                dialog = ManageVaultsState.DialogState.Rename(
                    userId = vault.userId,
                    currentName = vault.name.orEmpty(),
                ),
            )
        }
    }

    private fun handleRenameSubmit(action: ManageVaultsAction.RenameSubmit) {
        val dialog = state.dialog as? ManageVaultsState.DialogState.Rename ?: return
        authRepository.renameVault(userId = dialog.userId, name = action.name)
        mutableStateFlow.update { it.copy(dialog = null) }
    }

    private fun handleDeleteClick(action: ManageVaultsAction.DeleteClick) {
        val vault = state.vaults.firstOrNull { it.userId == action.userId } ?: return
        mutableStateFlow.update {
            it.copy(
                dialog = ManageVaultsState.DialogState.DeleteMasterPassword(
                    userId = vault.userId,
                    vaultName = vault.name,
                ),
            )
        }
    }

    private fun handleDeleteSubmit(action: ManageVaultsAction.DeleteSubmit) {
        val dialog = state.dialog as? ManageVaultsState.DialogState.DeleteMasterPassword ?: return
        mutableStateFlow.update { it.copy(dialog = ManageVaultsState.DialogState.Loading) }
        viewModelScope.launch {
            sendAction(
                ManageVaultsAction.Internal.DeleteResultReceive(
                    result = authRepository.deleteVault(
                        userId = dialog.userId,
                        masterPassword = action.masterPassword,
                    ),
                ),
            )
        }
    }

    private fun handleDeleteResult(action: ManageVaultsAction.Internal.DeleteResultReceive) {
        mutableStateFlow.update {
            it.copy(
                dialog = when (val result = action.result) {
                    DeleteVaultResult.Success -> null
                    DeleteVaultResult.WrongPassword -> {
                        ManageVaultsState.DialogState.Error(
                            message = BitwardenString.invalid_master_password.asText(),
                        )
                    }

                    is DeleteVaultResult.Error -> {
                        ManageVaultsState.DialogState.Error(
                            message = BitwardenString.the_vault_could_not_be_deleted.asText(),
                            error = result.error,
                        )
                    }
                },
            )
        }
    }
}

private fun UserState?.toVaultItems(): ImmutableList<ManageVaultsState.VaultItem> =
    this
        ?.accounts
        .orEmpty()
        .map { account ->
            ManageVaultsState.VaultItem(
                userId = account.userId,
                name = account.name,
                initials = account.initials,
                isActive = account.userId == this?.activeUserId,
            )
        }
        .toImmutableList()

/**
 * Models the state of the manage vaults screen.
 *
 * @property vaults Every vault on the device.
 * @property dialog The dialog currently shown, if any.
 */
@Parcelize
data class ManageVaultsState(
    val vaults: ImmutableList<VaultItem>,
    val dialog: DialogState?,
) : Parcelable {
    /**
     * One vault in the list. A `null` [name] is shown as "My vault".
     */
    @Parcelize
    data class VaultItem(
        val userId: String,
        val name: String?,
        val initials: String,
        val isActive: Boolean,
    ) : Parcelable

    /**
     * Dialogs shown on the manage vaults screen.
     */
    sealed class DialogState : Parcelable {
        /**
         * Asks for a new name for the vault with [userId], starting from [currentName].
         */
        @Parcelize
        data class Rename(val userId: String, val currentName: String) : DialogState()

        /**
         * Asks for the master password needed to delete the vault with [userId].
         */
        @Parcelize
        data class DeleteMasterPassword(
            val userId: String,
            val vaultName: String?,
        ) : DialogState()

        /**
         * A vault is being deleted.
         */
        @Parcelize
        data object Loading : DialogState()

        /**
         * Something went wrong; [message] is shown to the user.
         */
        @Parcelize
        data class Error(val message: Text, val error: Throwable? = null) : DialogState()
    }
}

/**
 * One-off events for the manage vaults screen.
 */
sealed class ManageVaultsEvent {
    /**
     * Navigates back.
     */
    data object NavigateBack : ManageVaultsEvent()

    /**
     * Opens the backup screen to import a backup as another vault.
     */
    data object NavigateToImportBackup : ManageVaultsEvent()
}

/**
 * User and internal actions for the manage vaults screen.
 */
sealed class ManageVaultsAction {
    /**
     * The user clicked back.
     */
    data object BackClick : ManageVaultsAction()

    /**
     * The user chose to rename the vault with [userId].
     */
    data class RenameClick(val userId: String) : ManageVaultsAction()

    /**
     * The user entered a new [name] for the vault being renamed.
     */
    data class RenameSubmit(val name: String) : ManageVaultsAction()

    /**
     * The user chose to delete the vault with [userId].
     */
    data class DeleteClick(val userId: String) : ManageVaultsAction()

    /**
     * The user entered the [masterPassword] to delete the vault.
     */
    data class DeleteSubmit(val masterPassword: String) : ManageVaultsAction()

    /**
     * The user chose to create a new, empty vault.
     */
    data object CreateVaultClick : ManageVaultsAction()

    /**
     * The user chose to import a backup as another vault.
     */
    data object ImportBackupClick : ManageVaultsAction()

    /**
     * The user dismissed the dialog.
     */
    data object DismissDialog : ManageVaultsAction()

    /**
     * Actions sent by the view model to itself.
     */
    sealed class Internal : ManageVaultsAction() {
        /**
         * The vaults on the device changed.
         */
        data class UserStateReceive(val userState: UserState?) : Internal()

        /**
         * Deleting a vault finished with [result].
         */
        data class DeleteResultReceive(val result: DeleteVaultResult) : Internal()
    }
}
