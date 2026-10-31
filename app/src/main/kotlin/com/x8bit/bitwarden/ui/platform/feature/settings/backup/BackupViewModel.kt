package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import android.net.Uri
import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bitwarden.core.data.util.toFormattedDateTimeStyle
import com.bitwarden.core.data.util.toFormattedPattern
import com.bitwarden.ui.platform.base.BaseViewModel
import com.bitwarden.ui.platform.components.snackbar.model.BitwardenSnackbarData
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.vault.manager.AutoBackupManager
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManager
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupState
import com.x8bit.bitwarden.data.vault.manager.model.MergeBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.time.Clock
import java.time.format.FormatStyle
import javax.inject.Inject

/**
 * View model for the backup screen.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val vaultBackupManager: VaultBackupManager,
    private val autoBackupManager: AutoBackupManager,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<BackupState, BackupEvent, BackupAction>(
    initialState = BackupState(
        isAutoBackupAvailable = autoBackupManager.isAutoBackupAvailable,
        autoBackupLocationName = null,
        autoBackupFrequency = AutoBackupFrequency.OFF,
        lastAutoBackupText = null,
        dialogState = null,
    ),
) {
    init {
        autoBackupManager
            .autoBackupStateFlow
            .map { BackupAction.Internal.AutoBackupStateReceive(it) }
            .onEach(::sendAction)
            .launchIn(viewModelScope)
        if (savedStateHandle.toBackupArgs().isImport) {
            sendEvent(BackupEvent.NavigateToSelectImportFile)
        }
    }

    /**
     * The backup picked for importing. It is not saved state because it refers to files in the
     * cache directory; after process death the user picks the file again.
     */
    private var pendingImport: VaultBackup? = null

    override fun handleAction(action: BackupAction) {
        when (action) {
            BackupAction.BackClick -> sendEvent(BackupEvent.NavigateBack)
            BackupAction.SaveBackupClick -> handleSaveBackupClick()
            is BackupAction.BackupLocationReceive -> handleBackupLocationReceive(action)
            BackupAction.AutoBackupLocationClick -> handleAutoBackupLocationClick()
            is BackupAction.AutoBackupLocationReceive -> handleAutoBackupLocationReceive(action)
            is BackupAction.AutoBackupFrequencySelect -> handleAutoBackupFrequencySelect(action)
            BackupAction.BackUpNowClick -> handleBackUpNowClick()
            BackupAction.ImportBackupClick -> sendEvent(BackupEvent.NavigateToSelectImportFile)
            is BackupAction.ImportFileReceive -> handleImportFileReceive(action)
            BackupAction.ImportIntoThisVaultClick -> showImportMasterPassword(vaultName = null)
            BackupAction.ImportAsSeparateVaultClick -> {
                mutableStateFlow.update { it.copy(dialogState = BackupState.DialogState.VaultName) }
            }

            is BackupAction.VaultNameSubmit -> showImportMasterPassword(vaultName = action.name)
            is BackupAction.ImportMasterPasswordSubmit -> handleImportMasterPasswordSubmit(action)
            BackupAction.DialogDismiss -> mutableStateFlow.update { it.copy(dialogState = null) }
            is BackupAction.Internal -> handleInternalAction(action)
        }
    }

    private fun handleSaveBackupClick() {
        sendEvent(BackupEvent.NavigateToSelectBackupLocation(fileName = backupFileName()))
    }

    private fun handleBackupLocationReceive(action: BackupAction.BackupLocationReceive) {
        showLoading()
        viewModelScope.launch {
            sendAction(
                BackupAction.Internal.WriteBackupResultReceive(
                    result = vaultBackupManager.writeBackup(uri = action.uri),
                ),
            )
        }
    }

    private fun handleAutoBackupLocationClick() {
        sendEvent(BackupEvent.NavigateToSelectAutoBackupLocation(fileName = AUTO_BACKUP_FILE_NAME))
    }

    private fun handleAutoBackupLocationReceive(action: BackupAction.AutoBackupLocationReceive) {
        autoBackupManager.setBackupLocation(uri = action.uri)
        handleBackUpNowClick()
    }

    private fun handleAutoBackupFrequencySelect(action: BackupAction.AutoBackupFrequencySelect) {
        autoBackupManager.setFrequency(frequency = action.frequency)
    }

    private fun handleBackUpNowClick() {
        showLoading()
        viewModelScope.launch {
            sendAction(
                BackupAction.Internal.WriteBackupResultReceive(
                    result = autoBackupManager.backUpNow(),
                ),
            )
        }
    }

    private fun handleImportFileReceive(action: BackupAction.ImportFileReceive) {
        showLoading(message = BitwardenString.importing_backup.asText())
        viewModelScope.launch {
            sendAction(
                BackupAction.Internal.ReadBackupResultReceive(
                    result = vaultBackupManager.readBackup(uri = action.uri),
                ),
            )
        }
    }

    private fun showImportMasterPassword(vaultName: String?) {
        mutableStateFlow.update {
            it.copy(dialogState = BackupState.DialogState.ImportMasterPassword(vaultName = vaultName))
        }
    }

    private fun handleImportMasterPasswordSubmit(
        action: BackupAction.ImportMasterPasswordSubmit,
    ) {
        val backup = pendingImport ?: return mutableStateFlow.update { it.copy(dialogState = null) }
        val vaultName = (state.dialogState as? BackupState.DialogState.ImportMasterPassword)
            ?.vaultName
        showLoading(message = BitwardenString.importing_backup.asText())
        viewModelScope.launch {
            sendAction(
                if (vaultName == null) {
                    BackupAction.Internal.MergeBackupResultReceive(
                        result = vaultBackupManager.mergeBackup(
                            backup = backup,
                            masterPassword = action.password,
                        ),
                    )
                } else {
                    BackupAction.Internal.AddVaultResultReceive(
                        result = vaultBackupManager.addBackupAsVault(
                            backup = backup,
                            masterPassword = action.password,
                            vaultName = vaultName,
                        ),
                    )
                },
            )
        }
    }

    private fun handleInternalAction(action: BackupAction.Internal) {
        when (action) {
            is BackupAction.Internal.ReadBackupResultReceive -> {
                handleReadBackupResultReceive(action.result)
            }

            is BackupAction.Internal.MergeBackupResultReceive -> {
                handleMergeBackupResultReceive(action.result)
            }

            is BackupAction.Internal.AddVaultResultReceive -> {
                handleAddVaultResultReceive(action.result)
            }

            is BackupAction.Internal.AutoBackupStateReceive -> {
                handleAutoBackupStateReceive(action.state)
            }

            is BackupAction.Internal.WriteBackupResultReceive -> {
                handleWriteBackupResultReceive(action.result)
            }
        }
    }

    private fun handleAutoBackupStateReceive(autoBackupState: AutoBackupState) {
        mutableStateFlow.update {
            it.copy(
                autoBackupLocationName = autoBackupState.locationName,
                autoBackupFrequency = autoBackupState.frequency,
                lastAutoBackupText = autoBackupState.lastBackupTime?.let { time ->
                    val formattedTime = time.toFormattedDateTimeStyle(
                        dateStyle = FormatStyle.MEDIUM,
                        timeStyle = FormatStyle.SHORT,
                        clock = clock,
                    )
                    if (autoBackupState.lastBackupFailed) {
                        BitwardenString.last_backup_failed_x.asText(formattedTime)
                    } else {
                        BitwardenString.last_backup_x.asText(formattedTime)
                    }
                },
            )
        }
    }

    private fun handleWriteBackupResultReceive(result: WriteBackupResult) {
        when (result) {
            is WriteBackupResult.Success -> {
                mutableStateFlow.update { it.copy(dialogState = null) }
                sendEvent(
                    BackupEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.backup_saved.asText(),
                        ),
                    ),
                )
            }

            is WriteBackupResult.Error -> {
                mutableStateFlow.update {
                    it.copy(
                        dialogState = BackupState.DialogState.Error(
                            message = BitwardenString.the_backup_could_not_be_saved.asText(),
                            error = result.error,
                        ),
                    )
                }
            }
        }
    }

    private fun handleReadBackupResultReceive(result: ReadBackupResult) {
        when (result) {
            is ReadBackupResult.Success -> {
                pendingImport = result.backup
                mutableStateFlow.update {
                    it.copy(dialogState = BackupState.DialogState.ImportModeSelection)
                }
            }

            is ReadBackupResult.Error -> {
                showError(
                    message = BitwardenString.this_file_is_not_a_secretdoor_backup.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun handleMergeBackupResultReceive(result: MergeBackupResult) {
        when (result) {
            is MergeBackupResult.Success -> {
                pendingImport = null
                mutableStateFlow.update { it.copy(dialogState = null) }
                sendEvent(
                    BackupEvent.ShowSnackbar(
                        data = BitwardenSnackbarData(
                            message = BitwardenString.items_imported_x_already_in_your_vault_y
                                .asText(
                                    result.addedCount + result.updatedCount,
                                    result.skippedCount,
                                ),
                        ),
                    ),
                )
            }

            MergeBackupResult.WrongPassword -> {
                showError(message = BitwardenString.invalid_master_password.asText())
            }

            is MergeBackupResult.Error -> {
                pendingImport = null
                showError(
                    message = BitwardenString.the_backup_could_not_be_imported.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun handleAddVaultResultReceive(result: RestoreBackupResult) {
        when (result) {
            is RestoreBackupResult.Success -> {
                // The new vault is now the active, unlocked vault; the app switches to it.
                pendingImport = null
                mutableStateFlow.update { it.copy(dialogState = null) }
            }

            RestoreBackupResult.WrongPassword -> {
                showError(message = BitwardenString.invalid_master_password.asText())
            }

            is RestoreBackupResult.Error -> {
                pendingImport = null
                showError(
                    message = BitwardenString.the_backup_could_not_be_imported.asText(),
                    error = result.error,
                )
            }
        }
    }

    private fun showError(message: Text, error: Throwable? = null) {
        mutableStateFlow.update {
            it.copy(dialogState = BackupState.DialogState.Error(message = message, error = error))
        }
    }

    private fun showLoading(message: Text = BitwardenString.saving_backup.asText()) {
        mutableStateFlow.update {
            it.copy(dialogState = BackupState.DialogState.Loading(message = message))
        }
    }

    private fun backupFileName(): String =
        "secretdoor-backup-${clock.instant().toFormattedPattern("yyyyMMdd-HHmm", clock = clock)}" +
            BACKUP_FILE_EXTENSION
}

private const val BACKUP_FILE_EXTENSION: String = ".secretdoor"
private const val AUTO_BACKUP_FILE_NAME: String = "secretdoor-auto-backup$BACKUP_FILE_EXTENSION"

/**
 * Models the state of the backup screen.
 *
 * @property isAutoBackupAvailable Whether automatic backups can be used.
 * @property autoBackupLocationName The name of the automatic backup file, if chosen.
 * @property autoBackupFrequency How often automatic backups run.
 * @property lastAutoBackupText A description of the last automatic backup, if any.
 * @property dialogState The dialog to show, if any.
 */
@Parcelize
data class BackupState(
    val isAutoBackupAvailable: Boolean,
    val autoBackupLocationName: String?,
    val autoBackupFrequency: AutoBackupFrequency,
    val lastAutoBackupText: Text?,
    val dialogState: DialogState?,
) : Parcelable {
    /**
     * Dialogs shown on the backup screen.
     */
    sealed class DialogState : Parcelable {
        /**
         * A loading dialog with [message].
         */
        @Parcelize
        data class Loading(val message: Text) : DialogState()

        /**
         * An error dialog with [message].
         */
        @Parcelize
        data class Error(val message: Text, val error: Throwable? = null) : DialogState()

        /**
         * Asks whether to merge the picked backup into this vault or add it as a separate vault.
         */
        @Parcelize
        data object ImportModeSelection : DialogState()

        /**
         * Asks for the name of the separate vault the backup is added as.
         */
        @Parcelize
        data object VaultName : DialogState()

        /**
         * Asks for the master password of the backup being imported. The backup is added as a
         * separate vault named [vaultName], or merged into this vault when it is `null`.
         */
        @Parcelize
        data class ImportMasterPassword(val vaultName: String?) : DialogState()
    }
}

/**
 * One-off events for the backup screen.
 */
sealed class BackupEvent {
    /**
     * Navigates back.
     */
    data object NavigateBack : BackupEvent()

    /**
     * Asks the user where to save a one-time backup named [fileName].
     */
    data class NavigateToSelectBackupLocation(val fileName: String) : BackupEvent()

    /**
     * Asks the user where automatic backups should be written, suggesting [fileName].
     */
    data class NavigateToSelectAutoBackupLocation(val fileName: String) : BackupEvent()

    /**
     * Asks the user to pick a backup file to import.
     */
    data object NavigateToSelectImportFile : BackupEvent()

    /**
     * Shows a snackbar with [data].
     */
    data class ShowSnackbar(val data: BitwardenSnackbarData) : BackupEvent()
}

/**
 * User and internal actions for the backup screen.
 */
sealed class BackupAction {
    /**
     * The user clicked back.
     */
    data object BackClick : BackupAction()

    /**
     * The user clicked "Save backup file".
     */
    data object SaveBackupClick : BackupAction()

    /**
     * The user picked [uri] for a one-time backup.
     */
    data class BackupLocationReceive(val uri: Uri) : BackupAction()

    /**
     * The user clicked the automatic backup file row.
     */
    data object AutoBackupLocationClick : BackupAction()

    /**
     * The user picked [uri] for automatic backups.
     */
    data class AutoBackupLocationReceive(val uri: Uri) : BackupAction()

    /**
     * The user picked a new automatic backup [frequency].
     */
    data class AutoBackupFrequencySelect(val frequency: AutoBackupFrequency) : BackupAction()

    /**
     * The user clicked "Back up now".
     */
    data object BackUpNowClick : BackupAction()

    /**
     * The user clicked "Import backup".
     */
    data object ImportBackupClick : BackupAction()

    /**
     * The user picked the backup file at [uri] to import.
     */
    data class ImportFileReceive(val uri: Uri) : BackupAction()

    /**
     * The user chose to merge the picked backup into this vault.
     */
    data object ImportIntoThisVaultClick : BackupAction()

    /**
     * The user chose to add the picked backup as a separate vault.
     */
    data object ImportAsSeparateVaultClick : BackupAction()

    /**
     * The user entered the [name] of the separate vault.
     */
    data class VaultNameSubmit(val name: String) : BackupAction()

    /**
     * The user entered the master [password] of the backup being imported.
     */
    data class ImportMasterPasswordSubmit(val password: String) : BackupAction()

    /**
     * The user dismissed the dialog.
     */
    data object DialogDismiss : BackupAction()

    /**
     * Actions sent by the view model to itself.
     */
    sealed class Internal : BackupAction() {
        /**
         * The automatic backup settings changed.
         */
        data class AutoBackupStateReceive(val state: AutoBackupState) : Internal()

        /**
         * A backup finished writing.
         */
        data class WriteBackupResultReceive(val result: WriteBackupResult) : Internal()

        /**
         * A backup file picked for importing was read.
         */
        data class ReadBackupResultReceive(val result: ReadBackupResult) : Internal()

        /**
         * A backup finished importing.
         */
        data class MergeBackupResultReceive(val result: MergeBackupResult) : Internal()

        /**
         * A backup finished being added as a separate vault.
         */
        data class AddVaultResultReceive(val result: RestoreBackupResult) : Internal()
    }
}
