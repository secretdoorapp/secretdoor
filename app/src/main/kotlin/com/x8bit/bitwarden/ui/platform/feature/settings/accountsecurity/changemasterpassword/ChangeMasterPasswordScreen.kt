package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.BitwardenFilledButton
import com.bitwarden.ui.platform.components.dialog.BitwardenBasicDialog
import com.bitwarden.ui.platform.components.dialog.BitwardenLoadingDialog
import com.bitwarden.ui.platform.components.field.BitwardenPasswordField
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme

/**
 * The change master password screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun ChangeMasterPasswordScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChangeMasterPasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            ChangeMasterPasswordEvent.NavigateBack -> onNavigateBack()
        }
    }

    when (val dialog = state.dialog) {
        ChangeMasterPasswordState.DialogState.Loading -> {
            BitwardenLoadingDialog(
                text = stringResource(id = BitwardenString.changing_master_password),
            )
        }

        ChangeMasterPasswordState.DialogState.Changed -> {
            BitwardenBasicDialog(
                title = stringResource(id = BitwardenString.change_master_password),
                message = stringResource(
                    id = BitwardenString
                        .your_master_password_was_changed_backups_made_before_still_use_the_old_one,
                ),
                onDismissRequest = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog)
                },
            )
        }

        is ChangeMasterPasswordState.DialogState.Error -> {
            BitwardenBasicDialog(
                title = stringResource(id = BitwardenString.an_error_has_occurred),
                message = dialog.message(),
                throwable = dialog.error,
                onDismissRequest = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.DismissDialog)
                },
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
                title = stringResource(id = BitwardenString.change_master_password),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_close),
                navigationIconContentDescription = stringResource(id = BitwardenString.close),
                onNavigationIconClick = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.CloseClick)
                },
            )
        },
    ) {
        var showPasswords by rememberSaveable { mutableStateOf(value = false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(height = 12.dp))
            Text(
                text = stringResource(
                    id = BitwardenString
                        .your_vault_key_stays_the_same_only_the_password_that_unlocks_it_changes,
                ),
                style = BitwardenTheme.typography.bodyMedium,
                color = BitwardenTheme.colorScheme.text.primary,
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 16.dp))
            BitwardenPasswordField(
                label = stringResource(id = BitwardenString.current_master_password),
                value = state.currentPassword,
                showPassword = showPasswords,
                showPasswordChange = { showPasswords = it },
                onValueChange = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.CurrentPasswordChange(it))
                },
                supportingContent = null,
                cardStyle = CardStyle.Full,
                passwordFieldTestTag = "CurrentMasterPasswordEntry",
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 16.dp))
            BitwardenPasswordField(
                label = stringResource(id = BitwardenString.new_master_password),
                value = state.newPassword,
                showPassword = showPasswords,
                showPasswordChange = { showPasswords = it },
                onValueChange = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.NewPasswordChange(it))
                },
                supportingContent = null,
                cardStyle = CardStyle.Top(),
                passwordFieldTestTag = "NewMasterPasswordEntry",
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            BitwardenPasswordField(
                label = stringResource(id = BitwardenString.confirm_new_master_password),
                value = state.confirmPassword,
                showPassword = showPasswords,
                showPasswordChange = { showPasswords = it },
                onValueChange = {
                    viewModel.trySendAction(ChangeMasterPasswordAction.ConfirmPasswordChange(it))
                },
                supportingContent = null,
                cardStyle = CardStyle.Bottom,
                passwordFieldTestTag = "ConfirmNewMasterPasswordEntry",
                modifier = Modifier
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 24.dp))
            BitwardenFilledButton(
                label = stringResource(id = BitwardenString.save),
                onClick = { viewModel.trySendAction(ChangeMasterPasswordAction.SaveClick) },
                modifier = Modifier
                    .testTag(tag = "SaveMasterPasswordButton")
                    .standardHorizontalMargin()
                    .fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(height = 16.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}
