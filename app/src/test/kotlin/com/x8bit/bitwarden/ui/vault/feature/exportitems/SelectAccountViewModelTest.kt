package com.x8bit.bitwarden.ui.vault.feature.exportitems

import app.cash.turbine.test
import com.bitwarden.cxf.model.ImportCredentialsRequestData
import com.bitwarden.data.repository.model.Environment
import com.bitwarden.ui.platform.base.BaseViewModelTest
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.UserState
import com.x8bit.bitwarden.data.auth.repository.model.createMockOrganization
import com.x8bit.bitwarden.data.platform.manager.SpecialCircumstanceManager
import com.x8bit.bitwarden.data.platform.manager.model.FirstTimeState
import com.x8bit.bitwarden.data.platform.manager.model.SpecialCircumstance
import com.x8bit.bitwarden.ui.vault.feature.exportitems.model.AccountSelectionListItem
import com.x8bit.bitwarden.ui.vault.feature.exportitems.selectaccount.SelectAccountAction
import com.x8bit.bitwarden.ui.vault.feature.exportitems.selectaccount.SelectAccountEvent
import com.x8bit.bitwarden.ui.vault.feature.exportitems.selectaccount.SelectAccountState
import com.x8bit.bitwarden.ui.vault.feature.exportitems.selectaccount.SelectAccountViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectAccountViewModelTest : BaseViewModelTest() {

    private val mutableUserUserStateFlow = MutableStateFlow(DEFAULT_USER_STATE)

    private val authRepository = mockk<AuthRepository> {
        every { userStateFlow } returns mutableUserUserStateFlow
    }
    private val specialCircumstanceManager = mockk<SpecialCircumstanceManager> {
        every { specialCircumstance } returns SpecialCircumstance.CredentialExchangeExport(
            data = DEFAULT_IMPORT_REQUEST,
        )
    }

    @Test
    fun `initial state should be correct`() = runTest {
        val viewModel = createViewModel()
        assertEquals(
            SelectAccountState(
                importRequest = DEFAULT_IMPORT_REQUEST,
                viewState = SelectAccountState.ViewState.Loading,
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `initial load should emit ValidateImportRequest event before observing selection data`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.eventFlow.test {
                assertEquals(
                    SelectAccountEvent.ValidateImportRequest(
                        importCredentialsRequestData = DEFAULT_IMPORT_REQUEST,
                    ),
                    awaitItem(),
                )

                verify(exactly = 0) {
                    authRepository.userStateFlow
                }
            }
        }

    @Test
    fun `ValidateImportRequestResultReceive should show Error content when request is invalid`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.trySendAction(
                SelectAccountAction.ValidateImportRequestResultReceive(
                    isValid = false,
                ),
            )

            assertEquals(
                SelectAccountState(
                    importRequest = DEFAULT_IMPORT_REQUEST,
                    viewState = SelectAccountState.ViewState.Error(
                        message = BitwardenString
                            .the_import_request_could_not_be_processed
                            .asText(),
                    ),
                ),
                viewModel.stateFlow.value,
            )
        }

    @Test
    fun `ValidateImportRequestResultReceive should observe selection data when request is valid`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.trySendAction(
                SelectAccountAction.ValidateImportRequestResultReceive(
                    isValid = true,
                ),
            )

            verify { authRepository.userStateFlow }
        }

    @Test
    fun `state is updated when single account with no organization is emitted`() = runTest {
        val viewModel = createViewModel()
        val expectedItem = AccountSelectionListItem(
            userId = DEFAULT_ACCOUNT.userId,
            email = DEFAULT_ACCOUNT.email,
            initials = "AU",
            avatarColorHex = "#FF00FF00",
        )

        viewModel.trySendAction(
            SelectAccountAction.Internal.SelectionDataReceive(
                userState = DEFAULT_USER_STATE,
            ),
        )

        assertEquals(
            SelectAccountState(
                importRequest = DEFAULT_IMPORT_REQUEST,
                viewState = SelectAccountState.ViewState.Content(
                    accountSelectionListItems = persistentListOf(expectedItem),
                ),
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `accounts that are not exportable are filtered out`() = runTest {
        val viewModel = createViewModel()
        val organizationId = "mockOrganizationId-1"
        val accountInOrg = DEFAULT_ACCOUNT.copy(
            isExportable = false,
            creationDate = null,
            organizations = listOf(
                createMockOrganization(
                    number = 1,
                    id = organizationId,
                    name = "organizationName",
                    keyConnectorUrl = null,
                ),
            ),
        )

        viewModel.trySendAction(
            SelectAccountAction.Internal.SelectionDataReceive(
                userState = DEFAULT_USER_STATE.copy(accounts = listOf(accountInOrg)),
            ),
        )
        assertEquals(
            SelectAccountState(
                importRequest = DEFAULT_IMPORT_REQUEST,
                viewState = SelectAccountState.ViewState.NoItems,
            ),
            viewModel.stateFlow.value,
        )
    }

    @Test
    fun `when CloseClick action is sent, CancelExport event is emitted`() = runTest {
        val viewModel = createViewModel()
        viewModel.eventFlow.test {
            // Skip the request validation event
            skipItems(1)
            viewModel.trySendAction(SelectAccountAction.CloseClick)
            assertEquals(SelectAccountEvent.CancelExport, awaitItem())
        }
    }

    @Test
    fun `when AccountClick action is sent, NavigateToPasswordVerification event is emitted`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.eventFlow.test {
                // Skip the request validation event
                skipItems(1)
                viewModel.trySendAction(
                    SelectAccountAction.AccountClick(
                        DEFAULT_ACCOUNT.userId,
                    ),
                )
                assertEquals(
                    SelectAccountEvent.NavigateToPasswordVerification(
                        userId = DEFAULT_ACCOUNT.userId,
                        hasOtherAccounts = true,
                    ),
                    awaitItem(),
                )
            }
        }

    private fun createViewModel(): SelectAccountViewModel = SelectAccountViewModel(
        authRepository = authRepository,
        specialCircumstanceManager = specialCircumstanceManager,
    )
}

private val DEFAULT_ACCOUNT = UserState.Account(
    userId = "activeUserId",
    name = "Active User",
    email = "active@bitwarden.com",
    environment = Environment.Prod.Us,
    avatarColorHex = "#FF00FF00",
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

private val DEFAULT_USER_STATE = UserState(
    activeUserId = "activeUserId",
    accounts = listOf(DEFAULT_ACCOUNT),
)
private val DEFAULT_IMPORT_REQUEST = ImportCredentialsRequestData(
    uri = mockk(),
    credentialTypes = setOf("mockCredentialType-1"),
    knownExtensions = setOf(),
)
