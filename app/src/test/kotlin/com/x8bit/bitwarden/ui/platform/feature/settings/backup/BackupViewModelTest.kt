package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import android.net.Uri
import app.cash.turbine.test
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.vault.manager.AutoBackupManager
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManager
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupState
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupViewModelTest : BaseViewModelTest() {
    private val uri: Uri = mockk()
    private val vaultBackupManager: VaultBackupManager = mockk()
    private val mutableAutoBackupStateFlow = MutableStateFlow(DEFAULT_AUTO_BACKUP_STATE)
    private val autoBackupManager: AutoBackupManager = mockk {
        every { isAutoBackupAvailable } returns true
        every { autoBackupStateFlow } returns mutableAutoBackupStateFlow
        every { setBackupLocation(uri = any()) } just runs
        every { setFrequency(frequency = any()) } just runs
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
                    fileName = "secretdoor-backup-20261030-1200.zip",
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

    private fun createViewModel(): BackupViewModel =
        BackupViewModel(
            vaultBackupManager = vaultBackupManager,
            autoBackupManager = autoBackupManager,
            clock = FIXED_CLOCK,
        )
}

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
