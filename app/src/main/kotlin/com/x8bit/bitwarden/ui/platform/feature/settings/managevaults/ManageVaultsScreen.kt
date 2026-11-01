package com.x8bit.bitwarden.ui.platform.feature.settings.managevaults

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.appbar.action.BitwardenOverflowActionItem
import com.bitwarden.ui.platform.components.appbar.model.OverflowMenuItemData
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.button.BitwardenOutlinedButton
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenTextEntryDialog
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenTextRow
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.x8bit.bitwarden.ui.platform.components.dialog.BitwardenMasterPasswordDialog
import kotlinx.collections.immutable.persistentListOf

/**
 * The manage vaults screen: every vault on the device, with rename and delete for each of them
 * and ways to add another vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun ManageVaultsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToImportBackup: () -> Unit,
    viewModel: ManageVaultsViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            ManageVaultsEvent.NavigateBack -> onNavigateBack()
            ManageVaultsEvent.NavigateToImportBackup -> onNavigateToImportBackup()
        }
    }

    ManageVaultsDialog(
        dialog = state.dialog,
        onAction = viewModel::trySendAction,
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.manage_vaults),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = { viewModel.trySendAction(ManageVaultsAction.BackClick) },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(height = 12.dp))
            val lastIndex = state.vaults.lastIndex
            state.vaults.forEachIndexed { index, vault ->
                val renameText = stringResource(id = BitwardenString.rename_vault)
                val deleteText = stringResource(id = BitwardenString.delete_vault)
                BitwardenTextRow(
                    text = vault.name ?: stringResource(id = BitwardenString.my_vault),
                    description = if (vault.isActive) {
                        AnnotatedString(stringResource(id = BitwardenString.current_vault))
                    } else {
                        null
                    },
                    onClick = { },
                    clickable = false,
                    cardStyle = when {
                        state.vaults.size == 1 -> CardStyle.Full
                        index == 0 -> CardStyle.Top()
                        index == lastIndex -> CardStyle.Bottom
                        else -> CardStyle.Middle()
                    },
                    modifier = Modifier
                        .testTag("Vault_$index")
                        .standardHorizontalMargin()
                        .fillMaxWidth(),
                    content = {
                        BitwardenOverflowActionItem(
                            menuItemDataList = persistentListOf(
                                OverflowMenuItemData(
                                    text = renameText,
                                    onClick = {
                                        viewModel.trySendAction(
                                            ManageVaultsAction.RenameClick(vault.userId),
                                        )
                                    },
                                ),
                                OverflowMenuItemData(
                                    text = deleteText,
                                    onClick = {
                                        viewModel.trySendAction(
                                            ManageVaultsAction.DeleteClick(vault.userId),
                                        )
                                    },
                                ),
                            ),
                            testTag = "VaultOptionsButton_$index",
                        )
                    },
                )
            }
            Spacer(modifier = Modifier.height(height = 24.dp))
            BitwardenFilledButton(
                label = stringResource(id = BitwardenString.create_a_new_vault),
                onClick = { viewModel.trySendAction(ManageVaultsAction.CreateVaultClick) },
                modifier = Modifier
                    .testTag("CreateVaultButton")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 12.dp))
            BitwardenOutlinedButton(
                label = stringResource(id = BitwardenString.import_backup),
                onClick = { viewModel.trySendAction(ManageVaultsAction.ImportBackupClick) },
                modifier = Modifier
                    .testTag("ImportBackupButton")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 16.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ManageVaultsDialog(
    dialog: ManageVaultsState.DialogState?,
    onAction: (ManageVaultsAction) -> Unit,
) {
    when (dialog) {
        is ManageVaultsState.DialogState.Rename -> {
            BitwardenTextEntryDialog(
                title = stringResource(id = BitwardenString.rename_vault),
                textFieldLabel = stringResource(id = BitwardenString.vault_name),
                initialText = dialog.currentName,
                onConfirmClick = { onAction(ManageVaultsAction.RenameSubmit(it)) },
                onDismissRequest = { onAction(ManageVaultsAction.DismissDialog) },
                autoFocus = true,
            )
        }

        is ManageVaultsState.DialogState.DeleteMasterPassword -> {
            BitwardenMasterPasswordDialog(
                title = dialog.vaultName ?: stringResource(id = BitwardenString.my_vault),
                message = stringResource(
                    id = BitwardenString
                        .deleting_a_vault_removes_all_its_items_from_this_device_enter_its_master_password_to_confirm,
                ),
                confirmButtonText = stringResource(id = BitwardenString.delete_vault),
                onConfirmClick = { onAction(ManageVaultsAction.DeleteSubmit(it)) },
                onDismissRequest = { onAction(ManageVaultsAction.DismissDialog) },
            )
        }

        ManageVaultsState.DialogState.Loading -> {
            BitwardenLoadingDialog(text = stringResource(id = BitwardenString.loading))
        }

        is ManageVaultsState.DialogState.Error -> {
            BitwardenBasicDialog(
                title = stringResource(id = BitwardenString.an_error_has_occurred),
                message = dialog.message(),
                throwable = dialog.error,
                onDismissRequest = { onAction(ManageVaultsAction.DismissDialog) },
            )
        }

        null -> Unit
    }
}
