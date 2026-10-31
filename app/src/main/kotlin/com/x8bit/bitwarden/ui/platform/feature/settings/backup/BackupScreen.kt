package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenSelectionDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenTextEntryDialog
import com.bitwarden.ui.platform.components.dialog.row.BitwardenBasicDialogRow
import com.bitwarden.ui.platform.components.dropdown.BitwardenMultiSelectButton
import com.x8bit.bitwarden.ui.platform.components.dialog.BitwardenMasterPasswordDialog
import com.bitwarden.ui.platform.components.header.BitwardenListHeaderText
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenTextRow
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.snackbar.BitwardenSnackbarHost
import com.bitwarden.ui.platform.components.snackbar.model.rememberBitwardenSnackbarHostState
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.composition.LocalIntentManager
import com.bitwarden.ui.platform.manager.IntentManager
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import kotlinx.collections.immutable.toImmutableList

/**
 * The backup screen: save a backup file now, or set up automatic backups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    intentManager: IntentManager = LocalIntentManager.current,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val backupLocationReceived: (Uri) -> Unit = remember {
        { viewModel.trySendAction(BackupAction.BackupLocationReceive(it)) }
    }
    val autoBackupLocationReceived: (Uri) -> Unit = remember {
        { viewModel.trySendAction(BackupAction.AutoBackupLocationReceive(it)) }
    }
    val backupLauncher = intentManager.getActivityResultLauncher { activityResult ->
        intentManager.getFileDataFromActivityResult(activityResult)?.let {
            backupLocationReceived(it.uri)
        }
    }
    val autoBackupLauncher = intentManager.getActivityResultLauncher { activityResult ->
        intentManager.getFileDataFromActivityResult(activityResult)?.let {
            autoBackupLocationReceived(it.uri)
        }
    }
    val importFileReceived: (Uri) -> Unit = remember {
        { viewModel.trySendAction(BackupAction.ImportFileReceive(it)) }
    }
    val importLauncher = intentManager.getActivityResultLauncher { activityResult ->
        intentManager.getFileDataFromActivityResult(activityResult)?.let {
            importFileReceived(it.uri)
        }
    }
    val snackbarHostState = rememberBitwardenSnackbarHostState()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            BackupEvent.NavigateBack -> onNavigateBack()
            is BackupEvent.NavigateToSelectBackupLocation -> {
                backupLauncher.launch(intentManager.createDocumentIntent(event.fileName))
            }

            is BackupEvent.NavigateToSelectAutoBackupLocation -> {
                autoBackupLauncher.launch(intentManager.createDocumentIntent(event.fileName))
            }

            BackupEvent.NavigateToSelectImportFile -> {
                importLauncher.launch(
                    intentManager.createFileChooserIntent(withCameraIntents = false),
                )
            }

            is BackupEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.data)
        }
    }

    when (val dialog = state.dialogState) {
        is BackupState.DialogState.Error -> {
            BitwardenBasicDialog(
                title = stringResource(id = BitwardenString.an_error_has_occurred),
                message = dialog.message(),
                throwable = dialog.error,
                onDismissRequest = { viewModel.trySendAction(BackupAction.DialogDismiss) },
            )
        }

        is BackupState.DialogState.Loading -> BitwardenLoadingDialog(text = dialog.message())
        BackupState.DialogState.ImportModeSelection -> {
            BitwardenSelectionDialog(
                title = stringResource(id = BitwardenString.import_backup),
                onDismissRequest = { viewModel.trySendAction(BackupAction.DialogDismiss) },
            ) {
                BitwardenBasicDialogRow(
                    text = stringResource(id = BitwardenString.merge_into_this_vault),
                    onClick = { viewModel.trySendAction(BackupAction.ImportIntoThisVaultClick) },
                )
                BitwardenBasicDialogRow(
                    text = stringResource(id = BitwardenString.add_as_a_separate_vault),
                    onClick = {
                        viewModel.trySendAction(BackupAction.ImportAsSeparateVaultClick)
                    },
                )
            }
        }

        BackupState.DialogState.VaultName -> {
            BitwardenTextEntryDialog(
                title = stringResource(id = BitwardenString.add_as_a_separate_vault),
                textFieldLabel = stringResource(id = BitwardenString.vault_name),
                onConfirmClick = { viewModel.trySendAction(BackupAction.VaultNameSubmit(it)) },
                onDismissRequest = { viewModel.trySendAction(BackupAction.DialogDismiss) },
                autoFocus = true,
            )
        }

        is BackupState.DialogState.ImportMasterPassword -> {
            BitwardenMasterPasswordDialog(
                title = stringResource(id = BitwardenString.import_backup),
                message = stringResource(
                    id = BitwardenString.enter_the_master_password_of_the_vault_in_this_backup,
                ),
                confirmButtonText = stringResource(id = BitwardenString.import_vault),
                onConfirmClick = {
                    viewModel.trySendAction(BackupAction.ImportMasterPasswordSubmit(it))
                },
                onDismissRequest = { viewModel.trySendAction(BackupAction.DialogDismiss) },
            )
        }

        null -> Unit
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.backup),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = { viewModel.trySendAction(BackupAction.BackClick) },
            )
        },
        snackbarHost = { BitwardenSnackbarHost(bitwardenHostState = snackbarHostState) },
    ) {
        BackupContent(
            state = state,
            onSaveBackupClick = { viewModel.trySendAction(BackupAction.SaveBackupClick) },
            onAutoBackupLocationClick = {
                viewModel.trySendAction(BackupAction.AutoBackupLocationClick)
            },
            onAutoBackupFrequencySelect = {
                viewModel.trySendAction(BackupAction.AutoBackupFrequencySelect(it))
            },
            onBackUpNowClick = { viewModel.trySendAction(BackupAction.BackUpNowClick) },
            onImportBackupClick = { viewModel.trySendAction(BackupAction.ImportBackupClick) },
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun BackupContent(
    state: BackupState,
    onSaveBackupClick: () -> Unit,
    onAutoBackupLocationClick: () -> Unit,
    onAutoBackupFrequencySelect: (AutoBackupFrequency) -> Unit,
    onBackUpNowClick: () -> Unit,
    onImportBackupClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(height = 12.dp))
        Text(
            text = stringResource(
                id = BitwardenString.a_backup_holds_your_encrypted_vault_and_needs_your_master_password,
            ),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenFilledButton(
            label = stringResource(id = BitwardenString.save_backup_file),
            onClick = onSaveBackupClick,
            modifier = Modifier
                .testTag("SaveBackupButton")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )

        if (state.isAutoBackupAvailable) {
            Spacer(modifier = Modifier.height(height = 24.dp))
            BitwardenListHeaderText(
                label = stringResource(id = BitwardenString.automatic_backup),
                modifier = Modifier
                    .standardHorizontalMargin()
                    .padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(height = 8.dp))
            BitwardenTextRow(
                text = stringResource(id = BitwardenString.backup_file),
                onClick = onAutoBackupLocationClick,
                cardStyle = CardStyle.Top(),
                modifier = Modifier
                    .testTag("AutoBackupLocationRow")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            ) {
                Text(
                    text = state.autoBackupLocationName
                        ?: stringResource(id = BitwardenString.not_set),
                    style = BitwardenTheme.typography.labelSmall,
                    color = BitwardenTheme.colorScheme.text.secondary,
                )
            }
            val frequencyLabels = AutoBackupFrequency.entries.associateWith {
                it.displayLabel().invoke(resources).toString()
            }
            BitwardenMultiSelectButton(
                label = stringResource(id = BitwardenString.frequency),
                options = frequencyLabels.values.toImmutableList(),
                selectedOption = frequencyLabels.getValue(state.autoBackupFrequency),
                onOptionSelected = { label ->
                    frequencyLabels.entries
                        .firstOrNull { it.value == label }
                        ?.let { onAutoBackupFrequencySelect(it.key) }
                },
                isEnabled = state.autoBackupLocationName != null,
                supportingText = state.lastAutoBackupText?.invoke(),
                cardStyle = CardStyle.Middle(),
                modifier = Modifier
                    .testTag("AutoBackupFrequencyChooser")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            BitwardenTextRow(
                text = stringResource(id = BitwardenString.back_up_now),
                onClick = onBackUpNowClick,
                isEnabled = state.autoBackupLocationName != null,
                cardStyle = CardStyle.Bottom,
                modifier = Modifier
                    .testTag("BackUpNowRow")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 8.dp))
            Text(
                text = stringResource(id = BitwardenString.automatic_backups_overwrite_the_chosen_file),
                style = BitwardenTheme.typography.bodySmall,
                color = BitwardenTheme.colorScheme.text.secondary,
                modifier = Modifier
                    .standardHorizontalMargin()
                    .padding(horizontal = 16.dp),
            )
        }
        Spacer(modifier = Modifier.height(height = 24.dp))
        BitwardenListHeaderText(
            label = stringResource(id = BitwardenString.import_backup),
            modifier = Modifier
                .standardHorizontalMargin()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(height = 8.dp))
        Text(
            text = stringResource(id = BitwardenString.adds_the_items_from_a_backup_to_this_vault),
            style = BitwardenTheme.typography.bodyMedium,
            color = BitwardenTheme.colorScheme.text.primary,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(height = 16.dp))
        BitwardenOutlinedButton(
            label = stringResource(id = BitwardenString.import_backup),
            onClick = onImportBackupClick,
            modifier = Modifier
                .testTag("ImportBackupButton")
                .standardHorizontalMargin()
                .fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(height = 16.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

private fun AutoBackupFrequency.displayLabel() =
    when (this) {
        AutoBackupFrequency.OFF -> BitwardenString.off.asText()
        AutoBackupFrequency.DAILY -> BitwardenString.daily.asText()
        AutoBackupFrequency.WEEKLY -> BitwardenString.weekly.asText()
    }
