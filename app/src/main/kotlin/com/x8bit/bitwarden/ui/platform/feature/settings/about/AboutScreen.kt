package com.x8bit.bitwarden.ui.platform.feature.settings.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.mirrorIfRtl
import com.bitwarden.ui.platform.base.util.standardHorizontalMargin
import com.bitwarden.ui.platform.components.appbar.BitwardenTopAppBar
import com.bitwarden.ui.platform.components.button.model.BitwardenHelpButtonData
import com.bitwarden.ui.platform.components.model.CardStyle
import com.bitwarden.ui.platform.components.row.BitwardenExternalLinkRow
import com.bitwarden.ui.platform.components.row.BitwardenPushRow
import com.bitwarden.ui.platform.components.row.BitwardenTextRow
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.toggle.BitwardenSwitch
import com.bitwarden.ui.platform.components.util.rememberVectorPainter
import com.bitwarden.ui.platform.composition.LocalIntentManager
import com.bitwarden.ui.platform.manager.IntentManager
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.platform.theme.BitwardenTheme
import com.bitwarden.ui.util.Text
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.platform.util.SecretDoorLinks

/**
 * Displays the about screen.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToFlightRecorder: () -> Unit,
    onNavigateToRecordedLogs: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
    intentManager: IntentManager = LocalIntentManager.current,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    EventsEffect(viewModel = viewModel) { event ->
        when (event) {
            AboutEvent.NavigateBack -> onNavigateBack.invoke()
            AboutEvent.NavigateToFlightRecorder -> onNavigateToFlightRecorder()
            AboutEvent.NavigateToRecordedLogs -> onNavigateToRecordedLogs()

            AboutEvent.NavigateToFlightRecorderHelp -> {
                intentManager.launchUri(SecretDoorLinks.FLIGHT_RECORDER_URL.toUri())
            }

            AboutEvent.NavigateToHelpCenter -> {
                intentManager.launchUri(SecretDoorLinks.HELP_CENTER_URL.toUri())
            }

            AboutEvent.NavigateToPrivacyPolicy -> {
                intentManager.launchUri(SecretDoorLinks.PRIVACY_URL.toUri())
            }

            AboutEvent.NavigateToSourceCode -> {
                intentManager.launchUri(SecretDoorLinks.REPOSITORY_URL.toUri())
            }

            AboutEvent.NavigateToLicense -> {
                intentManager.launchUri(SecretDoorLinks.LICENSE_URL.toUri())
            }

            AboutEvent.NavigateToNotices -> {
                intentManager.launchUri(SecretDoorLinks.NOTICES_URL.toUri())
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    BitwardenScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BitwardenTopAppBar(
                title = stringResource(id = BitwardenString.about),
                scrollBehavior = scrollBehavior,
                navigationIcon = rememberVectorPainter(id = BitwardenDrawable.ic_back),
                navigationIconContentDescription = stringResource(id = BitwardenString.back),
                onNavigationIconClick = { viewModel.trySendAction(AboutAction.BackClick) },
            )
        },
    ) {
        AboutScreenContent(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onHelpCenterClick = { viewModel.trySendAction(AboutAction.HelpCenterClick) },
            onPrivacyPolicyClick = { viewModel.trySendAction(AboutAction.PrivacyPolicyClick) },
            onSourceCodeClick = { viewModel.trySendAction(AboutAction.SourceCodeClick) },
            onLicenseClick = { viewModel.trySendAction(AboutAction.LicenseClick) },
            onNoticesClick = { viewModel.trySendAction(AboutAction.NoticesClick) },
            onSubmitCrashLogsCheckedChange = {
                viewModel.trySendAction(AboutAction.SubmitCrashLogsClick(it))
            },
            onFlightRecorderCheckedChange = {
                viewModel.trySendAction(AboutAction.FlightRecorderCheckedChange(it))
            },
            onFlightRecorderTooltipClick = {
                viewModel.trySendAction(AboutAction.FlightRecorderTooltipClick)
            },
            onViewRecordedLogsClick = {
                viewModel.trySendAction(AboutAction.ViewRecordedLogsClick)
            },
            onVersionClick = { viewModel.trySendAction(AboutAction.VersionClick) },
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun AboutScreenContent(
    state: AboutState,
    onHelpCenterClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onSourceCodeClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onNoticesClick: () -> Unit,
    onSubmitCrashLogsCheckedChange: (Boolean) -> Unit,
    onFlightRecorderCheckedChange: (Boolean) -> Unit,
    onFlightRecorderTooltipClick: () -> Unit,
    onViewRecordedLogsClick: () -> Unit,
    onVersionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(height = 12.dp))
        CrashLogsCard(
            isVisible = state.shouldShowCrashLogsButton,
            isEnabled = state.isSubmitCrashLogsEnabled,
            onSubmitCrashLogsCheckedChange = onSubmitCrashLogsCheckedChange,
        )
        FlightRecorderCard(
            isFlightRecorderEnabled = state.isFlightRecorderEnabled,
            logExpiration = state.flightRecorderSubtext,
            onFlightRecorderCheckedChange = onFlightRecorderCheckedChange,
            onFlightRecorderTooltipClick = onFlightRecorderTooltipClick,
            onViewRecordedLogsClick = onViewRecordedLogsClick,
        )
        BitwardenExternalLinkRow(
            text = stringResource(id = BitwardenString.bitwarden_help_center),
            onConfirmClick = onHelpCenterClick,
            dialogTitle = stringResource(id = BitwardenString.continue_to_help_center),
            dialogMessage = stringResource(
                id = BitwardenString.learn_more_about_how_to_use_bitwarden_on_the_help_center,
            ),
            withDivider = false,
            cardStyle = CardStyle.Top(),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag(tag = "BitwardenHelpCenterRow"),
        )
        BitwardenExternalLinkRow(
            text = stringResource(id = BitwardenString.privacy_policy),
            onConfirmClick = onPrivacyPolicyClick,
            dialogTitle = stringResource(id = BitwardenString.continue_to_privacy_policy),
            dialogMessage = stringResource(
                id = BitwardenString.privacy_policy_description_long,
            ),
            withDivider = false,
            cardStyle = CardStyle.Middle(),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag(tag = "PrivacyPolicyRow"),
        )
        BitwardenExternalLinkRow(
            text = stringResource(id = BitwardenString.source_code),
            onConfirmClick = onSourceCodeClick,
            dialogTitle = stringResource(id = BitwardenString.continue_to_the_source_code),
            dialogMessage = stringResource(
                id = BitwardenString.see_the_source_code_of_secretdoor_on_github,
            ),
            withDivider = false,
            cardStyle = CardStyle.Middle(),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag(tag = "SourceCodeRow"),
        )
        BitwardenExternalLinkRow(
            text = stringResource(id = BitwardenString.license_gnu_gpl_v3),
            onConfirmClick = onLicenseClick,
            dialogTitle = stringResource(id = BitwardenString.continue_to_the_license),
            dialogMessage = stringResource(
                id = BitwardenString.read_the_terms_secretdoor_is_distributed_under,
            ),
            withDivider = false,
            cardStyle = CardStyle.Middle(),
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag(tag = "LicenseRow"),
        )
        BitwardenExternalLinkRow(
            text = stringResource(id = BitwardenString.open_source_notices),
            onConfirmClick = onNoticesClick,
            dialogTitle = stringResource(id = BitwardenString.continue_to_the_notices),
            dialogMessage = stringResource(
                id = BitwardenString
                    .see_where_the_code_comes_from_and_the_licenses_of_the_libraries_it_uses,
            ),
            withDivider = false,
            cardStyle = CardStyle.Bottom,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag(tag = "NoticesRow"),
        )
        CopyRow(
            text = state.version,
            onClick = onVersionClick,
            modifier = Modifier
                .standardHorizontalMargin()
                .fillMaxWidth()
                .testTag("CopyAboutInfoRow"),
        )
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 60.dp)
                .standardHorizontalMargin()
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                modifier = Modifier.padding(end = 16.dp),
                text = state.copyrightInfo.invoke(),
                style = BitwardenTheme.typography.bodySmall,
                color = BitwardenTheme.colorScheme.text.primary,
            )
        }
        Spacer(modifier = Modifier.height(height = 16.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ColumnScope.CrashLogsCard(
    isVisible: Boolean,
    isEnabled: Boolean,
    onSubmitCrashLogsCheckedChange: (Boolean) -> Unit,
) {
    if (!isVisible) return
    BitwardenSwitch(
        label = stringResource(id = BitwardenString.submit_crash_logs),
        contentDescription = stringResource(id = BitwardenString.submit_crash_logs),
        isChecked = isEnabled,
        onCheckedChange = onSubmitCrashLogsCheckedChange,
        cardStyle = CardStyle.Full,
        modifier = Modifier
            .testTag(tag = "SubmitCrashLogsSwitch")
            .fillMaxWidth()
            .standardHorizontalMargin(),
    )
    Spacer(modifier = Modifier.height(height = 8.dp))
}

@Composable
private fun ColumnScope.FlightRecorderCard(
    isFlightRecorderEnabled: Boolean,
    logExpiration: Text?,
    onFlightRecorderCheckedChange: (Boolean) -> Unit,
    onFlightRecorderTooltipClick: () -> Unit,
    onViewRecordedLogsClick: () -> Unit,
) {
    BitwardenSwitch(
        label = stringResource(id = BitwardenString.flight_recorder),
        isChecked = isFlightRecorderEnabled,
        onCheckedChange = onFlightRecorderCheckedChange,
        helpData = BitwardenHelpButtonData(
            contentDescription = stringResource(id = BitwardenString.flight_recorder_help),
            onClick = onFlightRecorderTooltipClick,
            isExternalLink = true,
        ),
        subtext = logExpiration?.invoke(),
        cardStyle = CardStyle.Top(),
        modifier = Modifier
            .testTag(tag = "FlightRecorderSwitch")
            .fillMaxWidth()
            .standardHorizontalMargin(),
    )
    BitwardenPushRow(
        text = stringResource(id = BitwardenString.view_recorded_logs),
        onClick = onViewRecordedLogsClick,
        cardStyle = CardStyle.Bottom,
        modifier = Modifier
            .testTag(tag = "ViewRecordedLogs")
            .fillMaxWidth()
            .standardHorizontalMargin(),
    )
    Spacer(modifier = Modifier.height(height = 8.dp))
}

@Composable
private fun CopyRow(
    text: Text,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BitwardenTextRow(
        text = text(),
        onClick = onClick,
        withDivider = false,
        cardStyle = CardStyle.Bottom,
        modifier = modifier,
    ) {
        Icon(
            painter = rememberVectorPainter(id = BitwardenDrawable.ic_copy),
            contentDescription = stringResource(id = BitwardenString.copy),
            tint = BitwardenTheme.colorScheme.icon.primary,
            modifier = Modifier.mirrorIfRtl(),
        )
    }
}

@Preview
@Composable
private fun AboutScreenContent_preview() {
    BitwardenTheme {
        AboutScreenContent(
            state = AboutState(
                version = "Version: 1.0.0 (1)".asText(),
                sdkVersion = "\uD83E\uDD80 SDK: 1.0.0-20250708.105256-238".asText(),
                serverData = "\uD83C\uDF29 Server: 2025.7.1 @ US".asText(),
                deviceData = "device_data".asText(),
                ciData = "ci_data".asText(),
                isSubmitCrashLogsEnabled = false,
                copyrightInfo = "".asText(),
                shouldShowCrashLogsButton = true,
                isFlightRecorderEnabled = true,
                flightRecorderSubtext = "Expires 3/21/25 at 3:20 PM".asText(),
            ),
            onHelpCenterClick = {},
            onPrivacyPolicyClick = {},
            onSourceCodeClick = {},
            onLicenseClick = {},
            onNoticesClick = {},
            onSubmitCrashLogsCheckedChange = { },
            onFlightRecorderCheckedChange = { },
            onFlightRecorderTooltipClick = {},
            onViewRecordedLogsClick = {},
            onVersionClick = {},
        )
    }
}
