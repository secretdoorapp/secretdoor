package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import android.net.Uri
import android.os.Parcelable
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
    }

    override fun handleAction(action: BackupAction) {
        when (action) {
            BackupAction.BackClick -> sendEvent(BackupEvent.NavigateBack)
            BackupAction.SaveBackupClick -> handleSaveBackupClick()
            is BackupAction.BackupLocationReceive -> handleBackupLocationReceive(action)
            BackupAction.AutoBackupLocationClick -> handleAutoBackupLocationClick()
            is BackupAction.AutoBackupLocationReceive -> handleAutoBackupLocationReceive(action)
            is BackupAction.AutoBackupFrequencySelect -> handleAutoBackupFrequencySelect(action)
            BackupAction.BackUpNowClick -> handleBackUpNowClick()
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

    private fun handleInternalAction(action: BackupAction.Internal) {
        when (action) {
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

    private fun showLoading() {
        mutableStateFlow.update {
            it.copy(
                dialogState = BackupState.DialogState.Loading(
                    message = BitwardenString.saving_backup.asText(),
                ),
            )
        }
    }

    private fun backupFileName(): String =
        "secretdoor-backup-${clock.instant().toFormattedPattern("yyyyMMdd-HHmm", clock = clock)}.zip"
}

private const val AUTO_BACKUP_FILE_NAME: String = "secretdoor-auto-backup.zip"

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
    }
}
