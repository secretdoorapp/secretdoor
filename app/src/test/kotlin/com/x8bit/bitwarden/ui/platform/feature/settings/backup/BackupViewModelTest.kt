package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.vault.manager.AutoBackupManager
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManager
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupState
import com.x8bit.bitwarden.data.vault.manager.model.MergeBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupViewModelTest : BaseViewModelTest() {
    private val uri: Uri = mockk()
    private val vaultBackupManager: VaultBackupManager = mockk()
    private val authRepository: AuthRepository = mockk {
        every { userStateFlow } returns MutableStateFlow(
            mockk<UserState> { every { activeAccount.name } returns "Work Vault" },
        )
    }
    private val mutableAutoBackupStateFlow = MutableStateFlow(DEFAULT_AUTO_BACKUP_STATE)
    private val autoBackupManager: AutoBackupManager = mockk {
        every { isAutoBackupAvailable } returns true
        every { autoBackupStateFlow } returns mutableAutoBackupStateFlow
        every { setBackupLocation(uri = any()) } just runs
        every { setFrequency(frequency = any()) } just runs
    }

    @BeforeEach
    fun setup() {
        mockkStatic(SavedStateHandle::toBackupArgs)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SavedStateHandle::toBackupArgs)
    }

    @Test
    fun `initial state should reflect the automatic backup settings`() {
        val viewModel = createViewModel()

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `BackClick should emit NavigateBack`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(BackupAction.BackClick)
            assertEquals(BackupEvent.NavigateBack, awaitItem())
        }
    }

    @Test
    fun `SaveBackupClick should ask for a location with a dated file name`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(BackupAction.SaveBackupClick)
            assertEquals(
                BackupEvent.NavigateToSelectBackupLocation(
                    fileName = "Work-Vault_2026-10-30_12-00.secretdoor",
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `BackupLocationReceive success should show a snackbar`() = runTest {
        coEvery { vaultBackupManager.writeBackup(uri = uri) } returns WriteBackupResult.Success(3)
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(BackupAction.BackupLocationReceive(uri = uri))
            assertEquals(
                BackupEvent.ShowSnackbar(
                    data = BitwardenSnackbarData(message = BitwardenString.backup_saved.asText()),
                ),
                awaitItem(),
            )
        }
        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `BackupLocationReceive failure should show an error dialog`() = runTest {
        val error = Throwable("Fail")
        coEvery { vaultBackupManager.writeBackup(uri = uri) } returns WriteBackupResult.Error(error)
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.BackupLocationReceive(uri = uri))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.Error(
                    message = BitwardenString.the_backup_could_not_be_saved.asText(),
                    error = error,
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `AutoBackupLocationReceive should store the location and back up right away`() = runTest {
        coEvery { autoBackupManager.backUpNow() } returns WriteBackupResult.Success(1)
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.AutoBackupLocationReceive(uri = uri))

        verify { autoBackupManager.setBackupLocation(uri = uri) }
        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
    }

    @Test
    fun `AutoBackupFrequencySelect should update the frequency`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.AutoBackupFrequencySelect(AutoBackupFrequency.DAILY))

        verify { autoBackupManager.setFrequency(frequency = AutoBackupFrequency.DAILY) }
    }

    @Test
    fun `a new automatic backup state should update the screen`() {
        val viewModel = createViewModel()

        mutableAutoBackupStateFlow.value = DEFAULT_AUTO_BACKUP_STATE.copy(
            locationName = "backup.zip",
            frequency = AutoBackupFrequency.WEEKLY,
        )

        assertEquals(
            DEFAULT_STATE.copy(
                autoBackupLocationName = "backup.zip",
                autoBackupFrequency = AutoBackupFrequency.WEEKLY,
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `opening the screen for importing should ask for a backup file`() = runTest {
        val viewModel = createViewModel(isImport = true)
        viewModel.eventFlow.test {
            assertEquals(BackupEvent.NavigateToSelectImportFile, awaitItem())
        }
    }

    @Test
    fun `AutoBackupLocationClick should suggest a file named after the vault`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(BackupAction.AutoBackupLocationClick)
            assertEquals(
                BackupEvent.NavigateToSelectAutoBackupLocation(
                    fileName = "Work-Vault_auto-backup.secretdoor",
                ),
                awaitItem(),
            )
        }
    }

    @Test
    fun `ImportBackupClick should ask for a backup file`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            viewModel.trySendAction(BackupAction.ImportBackupClick)
            assertEquals(BackupEvent.NavigateToSelectImportFile, awaitItem())
        }
    }

    @Test
    fun `ImportFileReceive with a backup should ask how to import it`() {
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Success(backup = BACKUP)
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))

        assertEquals(
            DEFAULT_STATE.copy(dialogState = BackupState.DialogState.ImportModeSelection),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportIntoThisVaultClick should ask for the backup's master password`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.ImportIntoThisVaultClick)

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.ImportMasterPassword(vaultName = null),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportAsSeparateVaultClick then VaultNameSubmit should ask for the master password`() {
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.ImportAsSeparateVaultClick)
        assertEquals(
            DEFAULT_STATE.copy(dialogState = BackupState.DialogState.VaultName),
            viewModel.stateFlow.value,
        )
        viewModel.trySendAction(BackupAction.VaultNameSubmit(name = "Work"))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.ImportMasterPassword(vaultName = "Work"),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportMasterPasswordSubmit for a separate vault should add the vault`() {
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Success(backup = BACKUP)
        coEvery {
            vaultBackupManager.addBackupAsVault(
                backup = BACKUP,
                masterPassword = "password",
                vaultName = "Work",
            )
        } returns RestoreBackupResult.Success(itemCount = 3)
        val viewModel = createViewModel()
        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))
        viewModel.trySendAction(BackupAction.ImportAsSeparateVaultClick)
        viewModel.trySendAction(BackupAction.VaultNameSubmit(name = "Work"))

        viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit("password"))

        assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        coVerify(exactly = 0) { vaultBackupManager.mergeBackup(any(), any()) }
    }

    @Test
    fun `ImportMasterPasswordSubmit for a separate vault with the wrong password shows an error`() {
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Success(backup = BACKUP)
        coEvery {
            vaultBackupManager.addBackupAsVault(
                backup = BACKUP,
                masterPassword = "wrong",
                vaultName = "Work",
            )
        } returns RestoreBackupResult.WrongPassword
        val viewModel = createViewModel()
        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))
        viewModel.trySendAction(BackupAction.ImportAsSeparateVaultClick)
        viewModel.trySendAction(BackupAction.VaultNameSubmit(name = "Work"))

        viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit("wrong"))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.Error(
                    message = BitwardenString.invalid_master_password.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportFileReceive with a file that is not a backup should show an error`() {
        val error = Throwable("Not a backup")
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Error(error = error)
        val viewModel = createViewModel()

        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.Error(
                    message = BitwardenString.this_file_is_not_a_secretdoor_backup.asText(),
                    error = error,
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportMasterPasswordSubmit success should show how many items were imported`() =
        runTest {
            coEvery { vaultBackupManager.readBackup(uri = uri) } returns
                ReadBackupResult.Success(backup = BACKUP)
            coEvery {
                vaultBackupManager.mergeBackup(backup = BACKUP, masterPassword = "password")
            } returns MergeBackupResult.Success(addedCount = 3, updatedCount = 1, skippedCount = 2)
            val viewModel = createViewModel()
            viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))

            viewModel.eventFlow.test {
                viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit("password"))
                assertEquals(
                    BackupEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.items_imported_x_already_in_your_vault_y
                                .asText(4, 2),
                        ),
                    ),
                    awaitItem(),
                )
            }
            assertEquals(DEFAULT_STATE, viewModel.stateFlow.value)
        }

    @Test
    fun `ImportMasterPasswordSubmit with the wrong password should show an error`() {
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Success(backup = BACKUP)
        coEvery {
            vaultBackupManager.mergeBackup(backup = BACKUP, masterPassword = "wrong")
        } returns MergeBackupResult.WrongPassword
        val viewModel = createViewModel()
        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))

        viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit("wrong"))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.Error(
                    message = BitwardenString.invalid_master_password.asText(),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `ImportMasterPasswordSubmit failure should show an error`() {
        val error = Throwable("Fail")
        coEvery { vaultBackupManager.readBackup(uri = uri) } returns
            ReadBackupResult.Success(backup = BACKUP)
        coEvery {
            vaultBackupManager.mergeBackup(backup = BACKUP, masterPassword = "password")
        } returns MergeBackupResult.Error(error = error)
        val viewModel = createViewModel()
        viewModel.trySendAction(BackupAction.ImportFileReceive(uri = uri))

        viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit("password"))

        assertEquals(
            DEFAULT_STATE.copy(
                dialogState = BackupState.DialogState.Error(
                    message = BitwardenString.the_backup_could_not_be_imported.asText(),
                    error = error,
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    private fun createViewModel(isImport: Boolean = false): BackupViewModel =
        BackupViewModel(
            vaultBackupManager = vaultBackupManager,
            authRepository = authRepository,
            autoBackupManager = autoBackupManager,
            clock = FIXED_CLOCK,
            savedStateHandle = SavedStateHandle().apply {
                every { toBackupArgs() } returns BackupArgs(isImport = isImport)
            },
        )
}

private val BACKUP = VaultBackup(
    createdAt = Instant.parse("2026-10-29T12:00:00Z"),
    file = File("backup.bin"),
    isEncrypted = true,
)

private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2026-10-30T12:00:00Z"),
    ZoneOffset.UTC,
)

private val DEFAULT_AUTO_BACKUP_STATE = AutoBackupState(
    locationName = null,
    frequency = AutoBackupFrequency.OFF,
    lastBackupTime = null,
    lastBackupFailed = false,
)

private val DEFAULT_STATE = BackupState(
    isAutoBackupAvailable = true,
    autoBackupLocationName = null,
    autoBackupFrequency = AutoBackupFrequency.OFF,
    lastAutoBackupText = null,
    dialogState = null,
)
