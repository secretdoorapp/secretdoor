package com.x8bit.bitwarden.ui.platform.feature.settings.managevaults

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.annotation.OmitFromCoverage
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the manage vaults screen.
 */
@OmitFromCoverage
@Serializable
data object ManageVaultsRoute

/**
 * Add the manage vaults screen to the nav graph.
 */
fun NavGraphBuilder.manageVaultsDestination(
    onNavigateBack: () -> Unit,
    onNavigateToImportBackup: () -> Unit,
) {
    composableWithPushTransitions<ManageVaultsRoute> {
        ManageVaultsScreen(
            onNavigateBack = onNavigateBack,
            onNavigateToImportBackup = onNavigateToImportBackup,
        )
    }
}

/**
 * Navigate to the manage vaults screen.
 */
fun NavController.navigateToManageVaults(navOptions: NavOptions? = null) {
    this.navigate(route = ManageVaultsRoute, navOptions = navOptions)
}
