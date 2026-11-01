package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.lifecycle.viewModelScope
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.resource.BitwardenPlurals
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asPluralsText
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.ChangeMasterPasswordResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val MIN_PASSWORD_LENGTH = 12

/**
 * View model for the change master password screen.
 */
@HiltViewModel
class ChangeMasterPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : BaseViewModel<ChangeMasterPasswordState, ChangeMasterPasswordEvent, ChangeMasterPasswordAction>(
    initialState = ChangeMasterPasswordState(
        currentPassword = "",
        newPassword = "",
        confirmPassword = "",
        dialog = null,
    ),
) {
    override fun handleAction(action: ChangeMasterPasswordAction) {
        when (action) {
            ChangeMasterPasswordAction.CloseClick -> sendEvent(ChangeMasterPasswordEvent.NavigateBack)
            is ChangeMasterPasswordAction.CurrentPasswordChange -> {
                mutableStateFlow.update { it.copy(currentPassword = action.input) }
            }

            is ChangeMasterPasswordAction.NewPasswordChange -> {
                mutableStateFlow.update { it.copy(newPassword = action.input) }
            }

            is ChangeMasterPasswordAction.ConfirmPasswordChange -> {
                mutableStateFlow.update { it.copy(confirmPassword = action.input) }
            }

            ChangeMasterPasswordAction.SaveClick -> handleSaveClick()
            ChangeMasterPasswordAction.DismissDialog -> handleDismissDialog()
            is ChangeMasterPasswordAction.Internal.ChangeResultReceive -> {
                handleChangeResultReceive(action)
            }
        }
    }

    private fun handleSaveClick() {
        val errorMessage = when {
            state.currentPassword.isEmpty() -> {
                BitwardenString.validation_field_required.asText(
                    BitwardenString.current_master_password.asText(),
                )
            }

            state.newPassword.length < MIN_PASSWORD_LENGTH -> {
                BitwardenPlurals.master_password_length_val_message_x.asPluralsText(
                    quantity = MIN_PASSWORD_LENGTH,
                    args = arrayOf(MIN_PASSWORD_LENGTH),
                )
            }

            state.newPassword != state.confirmPassword -> {
                BitwardenString.master_password_confirmation_val_message.asText()
            }

            else -> null
        }
        if (errorMessage != null) {
            showDialog(ChangeMasterPasswordState.DialogState.Error(message = errorMessage))
            return
        }
        showDialog(ChangeMasterPasswordState.DialogState.Loading)
        viewModelScope.launch {
            sendAction(
                ChangeMasterPasswordAction.Internal.ChangeResultReceive(
                    result = authRepository.changeMasterPassword(
                        currentPassword = state.currentPassword,
                        newPassword = state.newPassword,
                    ),
                ),
            )
        }
    }

    private fun handleDismissDialog() {
        val wasChanged = state.dialog is ChangeMasterPasswordState.DialogState.Changed
        showDialog(null)
        if (wasChanged) sendEvent(ChangeMasterPasswordEvent.NavigateBack)
    }

    private fun handleChangeResultReceive(
        action: ChangeMasterPasswordAction.Internal.ChangeResultReceive,
    ) {
        when (val result = action.result) {
            ChangeMasterPasswordResult.Success -> {
                mutableStateFlow.update {
                    it.copy(
                        currentPassword = "",
                        newPassword = "",
                        confirmPassword = "",
                        dialog = ChangeMasterPasswordState.DialogState.Changed,
                    )
                }
            }

            ChangeMasterPasswordResult.WrongPassword -> {
                showDialog(
                    ChangeMasterPasswordState.DialogState.Error(
                        message = BitwardenString.invalid_master_password.asText(),
                    ),
                )
            }

            is ChangeMasterPasswordResult.Error -> {
                showDialog(
                    ChangeMasterPasswordState.DialogState.Error(
                        message = BitwardenString.generic_error_message.asText(),
                        error = result.error,
                    ),
                )
            }
        }
    }

    private fun showDialog(dialog: ChangeMasterPasswordState.DialogState?) {
        mutableStateFlow.update { it.copy(dialog = dialog) }
    }
}

/**
 * Models the state of the change master password screen. It is deliberately not saved, so the
 * passwords never reach the saved instance state.
 */
data class ChangeMasterPasswordState(
    val currentPassword: String,
    val newPassword: String,
    val confirmPassword: String,
    val dialog: DialogState?,
) {
    /**
     * Dialogs shown on the change master password screen.
     */
    sealed class DialogState {
        /**
         * The password is being changed.
         */
        data object Loading : DialogState()

        /**
         * The password was changed.
         */
        data object Changed : DialogState()

        /**
         * An error with [message].
         */
        data class Error(val message: Text, val error: Throwable? = null) : DialogState()
    }
}

/**
 * One-off events for the change master password screen.
 */
sealed class ChangeMasterPasswordEvent {
    /**
     * Navigates back.
     */
    data object NavigateBack : ChangeMasterPasswordEvent()
}

/**
 * User and internal actions for the change master password screen.
 */
sealed class ChangeMasterPasswordAction {
    /**
     * The user clicked close.
     */
    data object CloseClick : ChangeMasterPasswordAction()

    /**
     * The current master password input changed.
     */
    data class CurrentPasswordChange(val input: String) : ChangeMasterPasswordAction()

    /**
     * The new master password input changed.
     */
    data class NewPasswordChange(val input: String) : ChangeMasterPasswordAction()

    /**
     * The confirmation input changed.
     */
    data class ConfirmPasswordChange(val input: String) : ChangeMasterPasswordAction()

    /**
     * The user clicked save.
     */
    data object SaveClick : ChangeMasterPasswordAction()

    /**
     * The user dismissed the dialog.
     */
    data object DismissDialog : ChangeMasterPasswordAction()

    /**
     * Actions sent by the view model to itself.
     */
    sealed class Internal : ChangeMasterPasswordAction() {
        /**
         * The password change finished with [result].
         */
        data class ChangeResultReceive(val result: ChangeMasterPasswordResult) : Internal()
    }
}
