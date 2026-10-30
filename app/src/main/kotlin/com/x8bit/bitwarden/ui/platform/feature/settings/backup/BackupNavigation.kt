package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.annotation.OmitFromCoverage
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the backup screen.
 */
@OmitFromCoverage
@Serializable
data object BackupRoute

/**
 * Add the backup screen to the nav graph.
 */
fun NavGraphBuilder.backupDestination(
    onNavigateBack: () -> Unit,
) {
    composableWithPushTransitions<BackupRoute> {
        BackupScreen(onNavigateBack = onNavigateBack)
    }
}

/**
 * Navigate to the backup screen.
 */
fun NavController.navigateToBackup(navOptions: NavOptions? = null) {
    this.navigate(route = BackupRoute, navOptions = navOptions)
}
