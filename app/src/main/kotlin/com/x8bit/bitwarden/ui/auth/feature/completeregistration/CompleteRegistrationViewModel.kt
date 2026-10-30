package com.x8bit.bitwarden.ui.auth.feature.completeregistration

import android.net.Uri
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.resource.BitwardenPlurals
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asPluralsText
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.datasource.sdk.model.PasswordStrength
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.platform.manager.SpecialCircumstanceManager
import com.x8bit.bitwarden.data.tools.generator.repository.GeneratorRepository
import com.x8bit.bitwarden.data.tools.generator.repository.model.GeneratorResult
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManager
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.BackClick
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.CheckDataBreachesToggle
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.ConfirmPasswordInputChange
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.ContinueWithBreachedPasswordClick
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.ErrorDialogDismiss
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.Internal
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.Internal.ReceivePasswordStrengthResult
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.PasswordHintChange
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationAction.PasswordInputChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import javax.inject.Inject

private const val KEY_STATE = "state"
private const val MIN_PASSWORD_LENGTH = 12

/**
 * Models logic for the Complete Registration screen.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class CompleteRegistrationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    generatorRepository: GeneratorRepository,
    private val authRepository: AuthRepository,
    private val specialCircumstanceManager: SpecialCircumstanceManager,
    private val toastManager: ToastManager,
    private val vaultBackupManager: VaultBackupManager,
) : BaseViewModel<CompleteRegistrationState, CompleteRegistrationEvent, CompleteRegistrationAction>(
    initialState = savedStateHandle[KEY_STATE] ?: run {
        val args = savedStateHandle.toCompleteRegistrationArgs()
        CompleteRegistrationState(
            userEmail = args.emailAddress,
            emailVerificationToken = args.verificationToken,
            fromEmail = args.fromEmail,
            passwordInput = "",
            confirmPasswordInput = "",
            passwordHintInput = "",
            isCheckDataBreachesToggled = false,
            dialog = null,
            passwordStrengthState = PasswordStrengthState.NONE,
            minimumPasswordLength = MIN_PASSWORD_LENGTH,
        )
    },
) {

    /**
     * Keeps track of async request to get password strength. Should be cancelled
     * when user input changes.
     */
    private var passwordStrengthJob: Job = Job().apply { complete() }

    /**
     * The backup picked for restoring. It is not saved state because it refers to files in the
     * cache; after process death the user simply picks the file again.
     */
    private var pendingBackup: VaultBackup? = null

    init {
        verifyEmailAddress()
        // As state updates, write to saved state handle:
        stateFlow
            .onEach { savedStateHandle[KEY_STATE] = it }
            .launchIn(viewModelScope)

        generatorRepository
            .generatorResultFlow
            .filterIsInstance<GeneratorResult.Password>()
            .map {
                Internal.GeneratedPasswordResult(generatedPassword = it.password)
            }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
    }

    override fun handleAction(action: CompleteRegistrationAction) {
        when (action) {
            is Internal -> handleInternalAction(action)
            is ConfirmPasswordInputChange -> handleConfirmPasswordInputChanged(action)
            is PasswordHintChange -> handlePasswordHintChanged(action)
            is PasswordInputChange -> handlePasswordInputChanged(action)
            is BackClick -> handleBackClicked()
            is ErrorDialogDismiss -> handleDialogDismiss()
            is CheckDataBreachesToggle -> handleCheckDataBreachesToggle(action)
            ContinueWithBreachedPasswordClick -> handleContinueWithBreachedPasswordClick()
            CompleteRegistrationAction.LearnToPreventLockoutClick -> {
                handlePreventAccountLockoutClickAction()
            }

            CompleteRegistrationAction.MakePasswordStrongClick -> {
                handleMakePasswordStrongClickAction()
            }

            CompleteRegistrationAction.CallToActionClick -> handleCallToActionClick()
            CompleteRegistrationAction.RestoreFromBackupClick -> {
                sendEvent(CompleteRegistrationEvent.NavigateToSelectBackupFile)
            }

            is CompleteRegistrationAction.BackupFileReceive -> handleBackupFileReceive(action)
            is CompleteRegistrationAction.RestoreMasterPasswordSubmit -> {
                handleRestoreMasterPasswordSubmit(action)
            }
        }
    }

    private fun handleInternalAction(action: Internal) {
        when (action) {
            is Internal.GeneratedPasswordResult -> handleGeneratedPasswordResult(action)
            is ReceivePasswordStrengthResult -> handlePasswordStrengthResult(action)
            is Internal.ReceiveRegisterResult -> handleReceiveRegisterAccountResult(action)
            is Internal.ReceiveReadBackupResult -> handleReceiveReadBackupResult(action)
            is Internal.ReceiveRestoreBackupResult -> handleReceiveRestoreBackupResult(action)
        }
    }

    private fun handleBackupFileReceive(action: CompleteRegistrationAction.BackupFileReceive) {
        mutableStateFlow.update { it.copy(dialog = CompleteRegistrationDialog.RestoringVault) }
        viewModelScope.launch {
            sendAction(
                Internal.ReceiveReadBackupResult(
                    result = vaultBackupManager.readBackup(uri = action.uri),
                ),
            )
        }
    }

    private fun handleReceiveReadBackupResult(action: Internal.ReceiveReadBackupResult) {
        when (val result = action.result) {
            is ReadBackupResult.Success -> {
                pendingBackup = result.backup
                mutableStateFlow.update {
                    it.copy(dialog = CompleteRegistrationDialog.RestoreMasterPassword)
                }
            }

            is ReadBackupResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = BitwardenString.this_file_is_not_a_secretdoor_backup.asText(),
                            error = result.error,
                        ),
                    )
                }
            }
        }
    }

    private fun handleRestoreMasterPasswordSubmit(
        action: CompleteRegistrationAction.RestoreMasterPasswordSubmit,
    ) {
        val backup = pendingBackup ?: return clearDialogState()
        mutableStateFlow.update { it.copy(dialog = CompleteRegistrationDialog.RestoringVault) }
        viewModelScope.launch {
            sendAction(
                Internal.ReceiveRestoreBackupResult(
                    result = vaultBackupManager.restoreBackup(
                        backup = backup,
                        masterPassword = action.password,
                    ),
                ),
            )
        }
    }

    private fun handleReceiveRestoreBackupResult(action: Internal.ReceiveRestoreBackupResult) {
        when (val result = action.result) {
            is RestoreBackupResult.Success -> {
                // The restored vault is unlocked, so the app moves on to it by itself.
                pendingBackup = null
                clearDialogState()
            }

            RestoreBackupResult.WrongPassword -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = BitwardenString.invalid_master_password.asText(),
                        ),
                    )
                }
            }

            is RestoreBackupResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = BitwardenString.the_vault_could_not_be_restored.asText(),
                            error = result.error,
                        ),
                    )
                }
            }
        }
    }

    private fun handleGeneratedPasswordResult(
        action: Internal.GeneratedPasswordResult,
    ) {
        val password = action.generatedPassword
        mutableStateFlow.update {
            it.copy(
                passwordInput = password,
                confirmPasswordInput = password,
            )
        }
        checkPasswordStrength(input = password)
    }

    private fun verifyEmailAddress() {
        if (!state.fromEmail) {
            return
        }

        viewModelScope.launch {
            sendEvent(
                CompleteRegistrationEvent.ShowSnackbar(BitwardenString.email_verified.asText()),
            )
        }
    }

    private fun handlePasswordStrengthResult(action: ReceivePasswordStrengthResult) {
        when (val result = action.result) {
            is PasswordStrengthResult.Success -> {
                val updatedState = when (result.passwordStrength) {
                    PasswordStrength.LEVEL_0 -> PasswordStrengthState.WEAK_1
                    PasswordStrength.LEVEL_1 -> PasswordStrengthState.WEAK_2
                    PasswordStrength.LEVEL_2 -> PasswordStrengthState.WEAK_3
                    PasswordStrength.LEVEL_3 -> PasswordStrengthState.GOOD
                    PasswordStrength.LEVEL_4 -> PasswordStrengthState.STRONG
                }
                mutableStateFlow.update { oldState ->
                    oldState.copy(
                        passwordStrengthState = updatedState,
                    )
                }
            }

            is PasswordStrengthResult.Error -> Unit
        }
    }

    @Suppress("LongMethod", "MaxLineLength")
    private fun handleReceiveRegisterAccountResult(
        action: Internal.ReceiveRegisterResult,
    ) {
        when (val registerAccountResult = action.registerResult) {
            is RegisterResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.Error(
                            title = BitwardenString.an_error_has_occurred.asText(),
                            message = registerAccountResult.errorMessage?.asText()
                                ?: BitwardenString.generic_error_message.asText(),
                            error = registerAccountResult.error,
                        ),
                    )
                }
            }

            is RegisterResult.Success -> {
                // The local vault is created and unlocked, so state-based navigation takes over.
                clearDialogState()
                toastManager.show(messageId = BitwardenString.your_new_vault_has_been_created)
                authRepository.setOnboardingStatus(status = OnboardingStatus.NOT_STARTED)
            }

            RegisterResult.DataBreachFound -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.HaveIBeenPwned(
                            title = BitwardenString.exposed_master_password.asText(),
                            message = BitwardenString.password_found_in_a_data_breach_alert_description.asText(),
                        ),
                    )
                }
            }

            RegisterResult.DataBreachAndWeakPassword -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.HaveIBeenPwned(
                            title = BitwardenString.weak_and_exposed_master_password.asText(),
                            message = BitwardenString.weak_password_identified_and_found_in_a_data_breach_alert_description.asText(),
                        ),
                    )
                }
            }

            RegisterResult.WeakPassword -> {
                mutableStateFlow.update {
                    it.copy(
                        dialog = CompleteRegistrationDialog.HaveIBeenPwned(
                            title = BitwardenString.weak_master_password.asText(),
                            message = BitwardenString.weak_password_identified_use_a_strong_password_to_protect_your_account.asText(),
                        ),
                    )
                }
            }
        }
    }

    private fun handleCheckDataBreachesToggle(action: CheckDataBreachesToggle) {
        mutableStateFlow.update {
            it.copy(isCheckDataBreachesToggled = action.newState)
        }
    }

    private fun handleDialogDismiss() {
        clearDialogState()
    }

    private fun handleBackClicked() {
        // clear the special circumstance manager as user has elected not to proceed.
        specialCircumstanceManager.specialCircumstance = null
        sendEvent(CompleteRegistrationEvent.NavigateBack)
    }

    private fun handlePasswordHintChanged(action: PasswordHintChange) {
        mutableStateFlow.update { it.copy(passwordHintInput = action.input) }
    }

    private fun handlePasswordInputChanged(action: PasswordInputChange) {
        // Update input:
        mutableStateFlow.update { it.copy(passwordInput = action.input) }
        checkPasswordStrength(action.input)
    }

    private fun handleConfirmPasswordInputChanged(action: ConfirmPasswordInputChange) {
        mutableStateFlow.update { it.copy(confirmPasswordInput = action.input) }
    }

    private fun handleCallToActionClick() = when {
        state.passwordInput.length < MIN_PASSWORD_LENGTH -> {
            mutableStateFlow.update {
                it.copy(
                    dialog = CompleteRegistrationDialog.Error(
                        title = BitwardenString.an_error_has_occurred.asText(),
                        message = BitwardenPlurals.master_password_length_val_message_x
                            .asPluralsText(
                                quantity = MIN_PASSWORD_LENGTH,
                                args = arrayOf(MIN_PASSWORD_LENGTH),
                            ),
                    ),
                )
            }
        }

        state.passwordInput != state.confirmPasswordInput -> {
            mutableStateFlow.update {
                it.copy(
                    dialog = CompleteRegistrationDialog.Error(
                        title = BitwardenString.an_error_has_occurred.asText(),
                        message = BitwardenString.master_password_confirmation_val_message.asText(),
                    ),
                )
            }
        }

        else -> {
            submitRegisterAccountRequest(
                shouldCheckForDataBreaches = state.isCheckDataBreachesToggled,
                shouldIgnorePasswordStrength = false,
            )
        }
    }

    private fun handleMakePasswordStrongClickAction() {
        sendEvent(CompleteRegistrationEvent.NavigateToMakePasswordStrong)
    }

    private fun handlePreventAccountLockoutClickAction() {
        sendEvent(CompleteRegistrationEvent.NavigateToPreventAccountLockout)
    }

    private fun handleContinueWithBreachedPasswordClick() {
        submitRegisterAccountRequest(
            shouldCheckForDataBreaches = false,
            shouldIgnorePasswordStrength = true,
        )
    }

    private fun submitRegisterAccountRequest(
        shouldCheckForDataBreaches: Boolean,
        shouldIgnorePasswordStrength: Boolean,
    ) {
        mutableStateFlow.update {
            it.copy(dialog = CompleteRegistrationDialog.Loading)
        }
        viewModelScope.launch {
            val result = authRepository.createLocalVault(
                masterPassword = state.passwordInput,
                masterPasswordHint = state.passwordHintInput.ifBlank { null },
                shouldCheckDataBreaches = shouldCheckForDataBreaches,
                isMasterPasswordStrong = shouldIgnorePasswordStrength ||
                    state.isMasterPasswordStrong,
            )
            sendAction(
                Internal.ReceiveRegisterResult(
                    registerResult = result,
                ),
            )
        }
    }

    private fun checkPasswordStrength(input: String) {
        // Update password strength:
        passwordStrengthJob.cancel()
        if (input.isEmpty()) {
            mutableStateFlow.update {
                it.copy(passwordStrengthState = PasswordStrengthState.NONE)
            }
        } else {
            passwordStrengthJob = viewModelScope.launch {
                val result = authRepository.getPasswordStrength(
                    email = state.userEmail,
                    password = input,
                )
                trySendAction(ReceivePasswordStrengthResult(result))
            }
        }
    }

    private fun clearDialogState() {
        mutableStateFlow.update {
            it.copy(dialog = null)
        }
    }
}

/**
 * UI state for the complete registration screen.
 */
@Parcelize
data class CompleteRegistrationState(
    val userEmail: String,
    val emailVerificationToken: String,
    val fromEmail: Boolean,
    val passwordInput: String,
    val confirmPasswordInput: String,
    val passwordHintInput: String,
    val isCheckDataBreachesToggled: Boolean,
    val dialog: CompleteRegistrationDialog?,
    val passwordStrengthState: PasswordStrengthState,
    val minimumPasswordLength: Int,
) : Parcelable {
    /**
     * Whether the provided master password is considered strong.
     */
    val isMasterPasswordStrong: Boolean
        get() = when (passwordStrengthState) {
            PasswordStrengthState.NONE,
            PasswordStrengthState.WEAK_1,
            PasswordStrengthState.WEAK_2,
            PasswordStrengthState.WEAK_3,
                -> false

            PasswordStrengthState.GOOD,
            PasswordStrengthState.STRONG,
                -> true
        }

    /**
     * Whether the form is valid.
     */
    val validSubmissionReady: Boolean
        get() = passwordInput.isNotBlank() &&
            confirmPasswordInput.isNotBlank() &&
            passwordInput.length >= minimumPasswordLength
}

/**
 * Models dialogs that can be displayed on the complete registration screen.
 */
sealed class CompleteRegistrationDialog : Parcelable {
    /**
     * Loading dialog.
     */
    @Parcelize
    data object Loading : CompleteRegistrationDialog()

    /**
     * Asks for the master password of the backup being restored.
     */
    @Parcelize
    data object RestoreMasterPassword : CompleteRegistrationDialog()

    /**
     * Shown while a backup is read or restored.
     */
    @Parcelize
    data object RestoringVault : CompleteRegistrationDialog()

    /**
     * Confirm the user wants to continue with potentially breached password.
     *
     * @param title The title for the HaveIBeenPwned dialog.
     * @param message The message for the HaveIBeenPwned dialog.
     */
    @Parcelize
    data class HaveIBeenPwned(
        val title: Text,
        val message: Text,
    ) : CompleteRegistrationDialog()

    /**
     * General error dialog with an OK button.
     */
    @Parcelize
    data class Error(
        val title: Text?,
        val message: Text,
        val error: Throwable? = null,
    ) : CompleteRegistrationDialog()
}

/**
 * Models events for the complete registration screen.
 */
sealed class CompleteRegistrationEvent {

    /**
     * Asks the user to pick a backup file to restore.
     */
    data object NavigateToSelectBackupFile : CompleteRegistrationEvent()

    /**
     * Navigate back to previous screen.
     */
    data object NavigateBack : CompleteRegistrationEvent()

    /**
     * Show a snackbar with the given message.
     */
    data class ShowSnackbar(
        val message: Text,
    ) : CompleteRegistrationEvent()

    /**
     * Navigates to prevent account lockout info screen
     */
    data object NavigateToPreventAccountLockout : CompleteRegistrationEvent()

    /**
     * Navigates to make password strong screen
     */
    data object NavigateToMakePasswordStrong : CompleteRegistrationEvent()
}

/**
 * Models actions for the complete registration screen.
 */
sealed class CompleteRegistrationAction {

    /**
     * User clicked back.
     */
    data object BackClick : CompleteRegistrationAction()

    /**
     * User clicked "Yes" when being asked if they are sure they want to use a breached password.
     */
    data object ContinueWithBreachedPasswordClick : CompleteRegistrationAction()

    /**
     * Password input changed.
     */
    data class PasswordInputChange(val input: String) : CompleteRegistrationAction()

    /**
     * Confirm password input changed.
     */
    data class ConfirmPasswordInputChange(val input: String) : CompleteRegistrationAction()

    /**
     * Password hint input changed.
     */
    data class PasswordHintChange(val input: String) : CompleteRegistrationAction()

    /**
     * User dismissed the error dialog.
     */
    data object ErrorDialogDismiss : CompleteRegistrationAction()

    /**
     * User tapped check data breaches toggle.
     */
    data class CheckDataBreachesToggle(val newState: Boolean) : CompleteRegistrationAction()

    /**
     * User clicked on the make password strong card.
     */
    data object MakePasswordStrongClick : CompleteRegistrationAction()

    /**
     * User clicked on learn to prevent lockout text.
     */
    data object LearnToPreventLockoutClick : CompleteRegistrationAction()

    /**
     * User clicked on the "CTA" button.
     */
    data object CallToActionClick : CompleteRegistrationAction()

    /**
     * User clicked "Restore from backup".
     */
    data object RestoreFromBackupClick : CompleteRegistrationAction()

    /**
     * User picked the backup file at [uri].
     */
    data class BackupFileReceive(val uri: Uri) : CompleteRegistrationAction()

    /**
     * User entered the master [password] of the backup.
     */
    data class RestoreMasterPasswordSubmit(val password: String) : CompleteRegistrationAction()

    /**
     * Models actions that the [CompleteRegistrationViewModel] itself might send.
     */
    sealed class Internal : CompleteRegistrationAction() {
        /**
         * Indicates a [RegisterResult] has been received.
         */
        data class ReceiveRegisterResult(
            val registerResult: RegisterResult,
        ) : Internal()

        /**
         * Indicates a password strength result has been received.
         */
        data class ReceivePasswordStrengthResult(
            val result: PasswordStrengthResult,
        ) : Internal()

        /**
         * Indicates a generated password has been received.
         */
        data class GeneratedPasswordResult(val generatedPassword: String) : Internal()

        /**
         * Indicates a backup file has been read.
         */
        data class ReceiveReadBackupResult(val result: ReadBackupResult) : Internal()

        /**
         * Indicates a backup has been restored, or failed to.
         */
        data class ReceiveRestoreBackupResult(val result: RestoreBackupResult) : Internal()
    }
}
