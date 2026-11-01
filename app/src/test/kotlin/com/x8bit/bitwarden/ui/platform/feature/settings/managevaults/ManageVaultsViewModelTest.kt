package com.x8bit.bitwarden.ui.platform.feature.settings.managevaults

import app.cash.turbine.test
import com.bitwarden.data.repository.model.Environment
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.DeleteVaultResult
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.platform.manager.model.FirstTimeState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManageVaultsViewModelTest : BaseViewModelTest() {

    private val mutableUserStateFlow = MutableStateFlow<UserState?>(USER_STATE)
    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns mutableUserStateFlow
        every { renameVault(userId = any(), name = any()) } just runs
        every { hasPendingAccountAddition = any() } just runs
    }

    @Test
    fun `initial state should list every vault and mark the active one`() {
        assertEquals(
            ManageVaultsState(
                vaults = persistentListOf(
                    ManageVaultsState.VaultItem(
                        userId = USER_ID_1,
                        name = "Personal",
                        initials = "PE",
                        isActive = true,
                    ),
                    ManageVaultsState.VaultItem(
                        userId = USER_ID_2,
                        name = null,
                        initials = "MV",
                        isActive = false,
                    ),
                ),
                dialog = null,
            ),
            createViewModel().stateFlow.value,
        )
    }

    @Test
    fun `user state changes should update the vault list`() = runTest {
        val viewModel = createViewModel()
        mutableUserStateFlow.value = USER_STATE.copy(
            accounts = USER_STATE.accounts.filter { it.userId == USER_ID_1 },
        )
        assertEquals(listOf(USER_ID_1), viewModel.stateFlow.value.vaults.map { it.userId })
    }

    @Test
    fun `BackClick should emit NavigateBack`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(ManageVaultsAction.BackClick)
            assertEquals(ManageVaultsEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `RenameClick should show the rename dialog with the current name`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.RenameClick(userId = USER_ID_1))
        assertEquals(
            ManageVaultsState.DialogState.Rename(userId = USER_ID_1, currentName = "Personal"),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `RenameClick for an unnamed vault should start with an empty name`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.RenameClick(userId = USER_ID_2))
        assertEquals(
            ManageVaultsState.DialogState.Rename(userId = USER_ID_2, currentName = ""),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `RenameSubmit should rename the vault and close the dialog`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.RenameClick(userId = USER_ID_2))
        viewModel.trySendAction(ManageVaultsAction.RenameSubmit(name = "Work"))

        verify(exactly = 1) { authRepository.renameVault(userId = USER_ID_2, name = "Work") }
        assertEquals(null, viewModel.stateFlow.value.dialog)
    }

    @Test
    fun `RenameSubmit without a rename dialog should do nothing`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.RenameSubmit(name = "Work"))

        verify(exactly = 0) { authRepository.renameVault(userId = any(), name = any()) }
    }

    @Test
    fun `DeleteClick should ask for the master password of that vault`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.DeleteClick(userId = USER_ID_2))
        assertEquals(
            ManageVaultsState.DialogState.DeleteMasterPassword(
                userId = USER_ID_2,
                vaultName = null,
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `DeleteSubmit success should delete the vault and close the dialog`() = runTest {
        coEvery {
            authRepository.deleteVault(userId = USER_ID_2, masterPassword = PASSWORD)
        } returns DeleteVaultResult.Success
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.DeleteClick(userId = USER_ID_2))
        viewModel.trySendAction(ManageVaultsAction.DeleteSubmit(masterPassword = PASSWORD))

        coVerify(exactly = 1) {
            authRepository.deleteVault(userId = USER_ID_2, masterPassword = PASSWORD)
        }
        assertEquals(null, viewModel.stateFlow.value.dialog)
    }

    @Test
    fun `DeleteSubmit with a wrong password should show an invalid password error`() = runTest {
        coEvery {
            authRepository.deleteVault(userId = USER_ID_2, masterPassword = PASSWORD)
        } returns DeleteVaultResult.WrongPassword
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.DeleteClick(userId = USER_ID_2))
        viewModel.trySendAction(ManageVaultsAction.DeleteSubmit(masterPassword = PASSWORD))

        assertEquals(
            ManageVaultsState.DialogState.Error(
                message = BitwardenString.invalid_master_password.asText(),
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `DeleteSubmit failure should show a generic error with the cause`() = runTest {
        val error = IllegalStateException("boom")
        coEvery {
            authRepository.deleteVault(userId = USER_ID_2, masterPassword = PASSWORD)
        } returns DeleteVaultResult.Error(error = error)
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.DeleteClick(userId = USER_ID_2))
        viewModel.trySendAction(ManageVaultsAction.DeleteSubmit(masterPassword = PASSWORD))

        assertEquals(
            ManageVaultsState.DialogState.Error(
                message = BitwardenString.the_vault_could_not_be_deleted.asText(),
                error = error,
            ),
            viewModel.stateFlow.value.dialog,
        )
    }

    @Test
    fun `DismissDialog should close the dialog`() {
        val viewModel = createViewModel()
        viewModel.trySendAction(ManageVaultsAction.DeleteClick(userId = USER_ID_1))
        viewModel.trySendAction(ManageVaultsAction.DismissDialog)
        assertEquals(null, viewModel.stateFlow.value.dialog)
    }

    @Test
    fun `CreateVaultClick should start adding a vault`() {
        createViewModel().trySendAction(ManageVaultsAction.CreateVaultClick)
        verify(exactly = 1) { authRepository.hasPendingAccountAddition = true }
    }

    @Test
    fun `ImportBackupClick should emit NavigateToImportBackup`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(ManageVaultsAction.ImportBackupClick)
            assertEquals(ManageVaultsEvent.NavigateToImportBackup, awaitItem())
        }
    }

    private fun createViewModel(): ManageVaultsViewModel =
        ManageVaultsViewModel(authRepository = authRepository)
}

private const val PASSWORD = "a long master password"
private const val USER_ID_1 = "userId1"
private const val USER_ID_2 = "userId2"

private fun createAccount(userId: String, name: String?) = UserState.Account(
    userId = userId,
    name = name,
    email = "$userId@secretdoor.local",
    avatarColorHex = "#aa00aa",
    environment = Environment.Prod.Us,
    isPremium = true,
    isPremiumFromSelf = true,
    isLoggedIn = true,
    isVaultUnlocked = true,
    needsPasswordReset = false,
    isBiometricsEnabled = false,
    organizations = emptyList(),
    needsMasterPassword = false,
    trustedDevice = null,
    hasMasterPassword = true,
    isUsingKeyConnector = false,
    onboardingStatus = OnboardingStatus.COMPLETE,
    firstTimeState = FirstTimeState(showImportLoginsCard = true),
    isExportable = true,
    creationDate = null,
)

private val USER_STATE = UserState(
    activeUserId = USER_ID_1,
    accounts = listOf(
        createAccount(userId = USER_ID_1, name = "Personal"),
        createAccount(userId = USER_ID_2, name = null),
    ),
)
