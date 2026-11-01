package com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.bitwarden.core.util.persistentListOfNotNull
import com.bitwarden.ui.platform.base.util.EventsEffect
import com.bitwarden.ui.platform.base.util.navigateToTabOrRoot
import com.bitwarden.ui.platform.components.navigation.model.NavigationItem
import com.bitwarden.ui.platform.components.scaffold.BitwardenScaffold
import com.bitwarden.ui.platform.components.scaffold.model.ScaffoldNavigationData
import com.bitwarden.ui.platform.theme.RootTransitionProviders
import com.bitwarden.ui.platform.util.toObjectNavigationRoute
import com.x8bit.bitwarden.ui.platform.components.util.rememberBitwardenNavController
import com.x8bit.bitwarden.ui.platform.feature.search.model.SearchType
import com.x8bit.bitwarden.ui.platform.feature.settings.about.navigateToAbout
import com.x8bit.bitwarden.ui.platform.feature.settings.autofill.navigateToAutoFill
import com.x8bit.bitwarden.ui.platform.feature.settings.backup.navigateToBackup
import com.x8bit.bitwarden.ui.platform.feature.settings.navigateToSettingsGraphRoot
import com.x8bit.bitwarden.ui.platform.feature.settings.settingsGraph
import com.x8bit.bitwarden.ui.platform.feature.vaultunlockednavbar.model.VaultUnlockedNavBarTab
import com.x8bit.bitwarden.ui.tools.feature.generator.generatorGraph
import com.x8bit.bitwarden.ui.vault.feature.addedit.VaultAddEditArgs
import com.x8bit.bitwarden.ui.vault.feature.importitems.navigateToImportItemsScreen
import com.x8bit.bitwarden.ui.vault.feature.item.VaultItemArgs
import com.x8bit.bitwarden.ui.vault.feature.vault.VaultGraphRoute
import com.x8bit.bitwarden.ui.vault.feature.vault.vaultGraph

/**
 * Top level composable for the Vault Unlocked Screen.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun VaultUnlockedNavBarScreen(
    viewModel: VaultUnlockedNavBarViewModel = hiltViewModel(),
    navController: NavHostController = rememberBitwardenNavController(
        name = "VaultUnlockedNavBarScreen",
    ),
    onNavigateToVaultAddItem: (args: VaultAddEditArgs) -> Unit,
    onNavigateToVaultItem: (args: VaultItemArgs) -> Unit,
    onNavigateToVaultEditItem: (args: VaultAddEditArgs) -> Unit,
    onNavigateToSearchVault: (searchType: SearchType.Vault) -> Unit,
    onNavigateToDeleteAccount: () -> Unit,
    onNavigateToChangeMasterPassword: () -> Unit,
    onNavigateToExportVault: () -> Unit,
    onNavigateToFolders: () -> Unit,
    onNavigateToPasswordHistory: () -> Unit,
    onNavigateToSetupUnlockScreen: () -> Unit,
    onNavigateToSetupAutoFillScreen: () -> Unit,
    onNavigateToSetupBrowserAutofill: () -> Unit,
    onNavigateToFlightRecorder: () -> Unit,
    onNavigateToRecordedLogs: () -> Unit,
    onNavigateToImportLogins: () -> Unit,
    onNavigateToAddFolderScreen: (selectedFolderId: String?) -> Unit,
    onNavigateToAboutPrivilegedApps: () -> Unit,
    onNavigateToManageDevices: () -> Unit,
    onNavigateToPlan: () -> Unit,
    onNavigateToUpgradedToPremium: () -> Unit,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    EventsEffect(viewModel = viewModel) { event ->
        navController.navigateToTabOrRoot(target = event.tab)
    }

    VaultUnlockedNavBarScaffold(
        state = state,
        navController = navController,
        onNavigateToVaultItem = onNavigateToVaultItem,
        onNavigateToVaultEditItem = onNavigateToVaultEditItem,
        navigateToVaultAddItem = onNavigateToVaultAddItem,
        onNavigateToSearchVault = onNavigateToSearchVault,
        navigateToDeleteAccount = onNavigateToDeleteAccount,
        navigateToChangeMasterPassword = onNavigateToChangeMasterPassword,
        navigateToExportVault = onNavigateToExportVault,
        navigateToFolders = onNavigateToFolders,
        navigateToPasswordHistory = onNavigateToPasswordHistory,
        generatorTabClickedAction = {
            viewModel.trySendAction(VaultUnlockedNavBarAction.GeneratorTabClick)
        },
        vaultTabClickedAction = {
            viewModel.trySendAction(VaultUnlockedNavBarAction.VaultTabClick)
        },
        settingsTabClickedAction = {
            viewModel.trySendAction(VaultUnlockedNavBarAction.SettingsTabClick)
        },
        onNavigateToSetupUnlockScreen = onNavigateToSetupUnlockScreen,
        onNavigateToSetupAutoFillScreen = onNavigateToSetupAutoFillScreen,
        onNavigateToSetupBrowserAutofill = onNavigateToSetupBrowserAutofill,
        onNavigateToImportLogins = onNavigateToImportLogins,
        onNavigateToAddFolderScreen = onNavigateToAddFolderScreen,
        onNavigateToFlightRecorder = onNavigateToFlightRecorder,
        onNavigateToRecordedLogs = onNavigateToRecordedLogs,
        onNavigateToAboutPrivilegedApps = onNavigateToAboutPrivilegedApps,
        onNavigateToManageDevices = onNavigateToManageDevices,
        onNavigateToPlan = onNavigateToPlan,
        onNavigateToUpgradedToPremium = onNavigateToUpgradedToPremium,
    )
}

/**
 * Scaffold that contains the bottom nav bar for the [VaultUnlockedNavBarScreen]
 */
@Composable
@Suppress("LongMethod")
private fun VaultUnlockedNavBarScaffold(
    state: VaultUnlockedNavBarState,
    navController: NavHostController,
    vaultTabClickedAction: () -> Unit,
    generatorTabClickedAction: () -> Unit,
    settingsTabClickedAction: () -> Unit,
    navigateToVaultAddItem: (args: VaultAddEditArgs) -> Unit,
    onNavigateToVaultItem: (args: VaultItemArgs) -> Unit,
    onNavigateToVaultEditItem: (args: VaultAddEditArgs) -> Unit,
    onNavigateToSearchVault: (searchType: SearchType.Vault) -> Unit,
    navigateToDeleteAccount: () -> Unit,
    navigateToChangeMasterPassword: () -> Unit,
    navigateToExportVault: () -> Unit,
    navigateToFolders: () -> Unit,
    navigateToPasswordHistory: () -> Unit,
    onNavigateToSetupUnlockScreen: () -> Unit,
    onNavigateToSetupAutoFillScreen: () -> Unit,
    onNavigateToSetupBrowserAutofill: () -> Unit,
    onNavigateToFlightRecorder: () -> Unit,
    onNavigateToRecordedLogs: () -> Unit,
    onNavigateToImportLogins: () -> Unit,
    onNavigateToAddFolderScreen: (selectedFolderId: String?) -> Unit,
    onNavigateToAboutPrivilegedApps: () -> Unit,
    onNavigateToManageDevices: () -> Unit,
    onNavigateToPlan: () -> Unit,
    onNavigateToUpgradedToPremium: () -> Unit,
) {
    var shouldDimNavBar by rememberSaveable { mutableStateOf(value = false) }

    // This scaffold will host screens that contain top bars while not hosting one itself.
    // We need to ignore the all insets here and let the content screens handle it themselves.
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val navigationItems = persistentListOfNotNull<NavigationItem>(
        VaultUnlockedNavBarTab.Vault(labelRes = state.vaultNavBarLabelRes),
        VaultUnlockedNavBarTab.Generator,
        VaultUnlockedNavBarTab.Settings(state.notificationState.settingsTabNotificationCount),
    )
    BitwardenScaffold(
        contentWindowInsets = WindowInsets(0.dp),
        navigationData = ScaffoldNavigationData(
            navigationItems = navigationItems,
            selectedNavigationItem = navigationItems.find {
                navBackStackEntry.isCurrentRoute(route = it.graphRoute)
            },
            onNavigationClick = { navigationItem ->
                when (navigationItem) {
                    VaultUnlockedNavBarTab.Generator -> generatorTabClickedAction()
                    is VaultUnlockedNavBarTab.Settings -> settingsTabClickedAction()
                    is VaultUnlockedNavBarTab.Vault -> vaultTabClickedAction()
                }
            },
            shouldDimNavigation = shouldDimNavBar,
        ),
    ) {
        // Because this Scaffold has a bottom navigation bar, the NavHost will:
        // - consume the vertical navigation bar insets.
        // - consume the IME insets.
        NavHost(
            navController = navController,
            startDestination = VaultGraphRoute,
            enterTransition = RootTransitionProviders.Enter.fadeIn,
            exitTransition = RootTransitionProviders.Exit.fadeOut,
            popEnterTransition = RootTransitionProviders.Enter.fadeIn,
            popExitTransition = RootTransitionProviders.Exit.fadeOut,
        ) {
            vaultGraph(
                navController = navController,
                onNavigateToVaultAddItemScreen = navigateToVaultAddItem,
                onNavigateToVaultItemScreen = onNavigateToVaultItem,
                onNavigateToVaultEditItemScreen = onNavigateToVaultEditItem,
                onNavigateToSearchVault = onNavigateToSearchVault,
                onDimBottomNavBarRequest = { shouldDim -> shouldDimNavBar = shouldDim },
                onNavigateToImportLogins = onNavigateToImportLogins,
                onNavigateToAddFolderScreen = onNavigateToAddFolderScreen,
                onNavigateToAboutScreen = {
                    navController.navigateToSettingsGraphRoot()
                    navController.navigateToAbout(isPreAuth = false)
                },
                onNavigateToAutofillScreen = {
                    navController.navigateToSettingsGraphRoot()
                    navController.navigateToAutoFill()
                },
                onNavigateToBackupScreen = { isImport ->
                    navController.navigateToSettingsGraphRoot()
                    navController.navigateToBackup(isImport = isImport)
                },
                onNavigateToPlan = onNavigateToPlan,
            )
            generatorGraph(
                navigateToVaultAddItem = navigateToVaultAddItem,
                onNavigateToPasswordHistory = { navigateToPasswordHistory() },
                onDimNavBarRequest = { shouldDim -> shouldDimNavBar = shouldDim },
            )
            settingsGraph(
                navController = navController,
                onNavigateToDeleteAccount = navigateToDeleteAccount,
                onNavigateToChangeMasterPassword = navigateToChangeMasterPassword,
                onNavigateToExportVault = navigateToExportVault,
                onNavigateToFolders = navigateToFolders,
                onNavigateToSetupUnlockScreen = onNavigateToSetupUnlockScreen,
                onNavigateToSetupAutoFillScreen = onNavigateToSetupAutoFillScreen,
                onNavigateToSetupBrowserAutofill = onNavigateToSetupBrowserAutofill,
                onNavigateToImportLogins = onNavigateToImportLogins,
                onNavigateToImportItems = { navController.navigateToImportItemsScreen() },
                onNavigateToFlightRecorder = onNavigateToFlightRecorder,
                onNavigateToRecordedLogs = onNavigateToRecordedLogs,
                onNavigateToAboutPrivilegedApps = onNavigateToAboutPrivilegedApps,
                onNavigateToManageDevices = onNavigateToManageDevices,
                onNavigateToUpgradedToPremium = onNavigateToUpgradedToPremium,
            )
        }
    }
}

/**
 * Determine if the current destination is the same as the given tab.
 */
private fun NavBackStackEntry?.isCurrentRoute(route: Any): Boolean =
    this
        ?.destination
        ?.hierarchy
        ?.any { it.route == route.toObjectNavigationRoute() } == true
