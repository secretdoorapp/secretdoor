package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenPlurals
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asPluralsText
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.ChangeMasterPasswordResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChangeMasterPasswordViewModelTest : BaseViewModelTest() {

    private val authRepository: AuthRepository = mockk()

    @Test
    fun `CloseClick should navigate back`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(ChangeMasterPasswordAction.CloseClick)
            assertEquals(ChangeMasterPasswordEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `SaveClick with a short new password should show an error and change nothing`() {
        val viewModel = createViewModel(newPassword = "short", confirmPassword = "short")

        viewModel.trySendAction(ChangeMasterPasswordAction.SaveClick)

        assertEquals(
            ChangeMasterPasswordState.DialogState.Error(
                message = BitwardenPlurals.master_password_length_val_message_x.asPluralsText(
                    quantity = 12,
                    args = arrayOf(12),
                ),
            ),
            viewModel.stateFlow.value.dialog,
        )
        coVerify(exactly = 0) { authRepository.changeMasterPassword(any(), any()) }
    }

    @Test
    fun `SaveClick with a confirmation that does not match should show an error`() {
        val viewModel = createViewModel(confirmPassword = "something else entirely")

        viewModel.trySendAction(ChangeMasterPasswordAction.SaveClick)

        assertEquals(
            ChangeMasterPasswordState.DialogState.Error(
                message = BitwardenString.master_password_confirmation_val_message.asText(),
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `SaveClick success should clear the fields, confirm, and navigate back on dismiss`() =
        runTest {
            coEvery {
                authRepository.changeMasterPassword(
                    currentPassword = CURRENT,
                    newPassword = NEW,
                )
            } returns ChangeMasterPasswordResult.Success
            val viewModel = createViewModel()

            viewModel.trySendAction(ChangeMasterPasswordAction.SaveClick)

            assertEquals(
                ChangeMasterPasswordState(
                    currentPassword = "",
                    newPassword = "",
                    confirmPassword = "",
                    dialog = ChangeMasterPasswordState.DialogState.Changed,
                ),
                viewModel.stateFlow.value,
            )
            viewModel.eventFlow.test {
                viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog)
                assertEquals(ChangeMasterPasswordEvent.NavigateBack, awaitItem())
            }
        }

    @Test
    fun `SaveClick with the wrong current password should show an error`() {
        coEvery {
            authRepository.changeMasterPassword(currentPassword = CURRENT, newPassword = NEW)
        } returns ChangeMasterPasswordResult.WrongPassword
        val viewModel = createViewModel()

        viewModel.trySendAction(ChangeMasterPasswordAction.SaveClick)

        assertEquals(
            ChangeMasterPasswordState.DialogState.Error(
                message = BitwardenString.invalid_master_password.asText(),
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    private fun createViewModel(
        currentPassword: String = CURRENT,
        newPassword: String = NEW,
        confirmPassword: String = NEW,
    ): ChangeMasterPasswordViewModel =
        ChangeMasterPasswordViewModel(authRepository = authRepository).also {
            it.trySendAction(ChangeMasterPasswordAction.CurrentPasswordChange(currentPassword))
            it.trySendAction(ChangeMasterPasswordAction.NewPasswordChange(newPassword))
            it.trySendAction(ChangeMasterPasswordAction.ConfirmPasswordChange(confirmPassword))
        }
}

private const val CURRENT = "current master password"
private const val NEW = "a brand new long passphrase"
