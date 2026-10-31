package com.x8bit.bitwarden.ui.platform.feature.settings.backup

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.toRoute
import com.bitwarden.annotation.OmitFromCoverage
import com.bitwarden.ui.platform.base.util.composableWithPushTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the backup screen.
 *
 * @property isImport Whether the screen should start by picking a backup file to import.
 */
@OmitFromCoverage
@Serializable
data class BackupRoute(
    val isImport: Boolean,
)

/**
 * Class to retrieve backup arguments from the [SavedStateHandle].
 */
data class BackupArgs(
    val isImport: Boolean,
)

/**
 * Constructs a [BackupArgs] from the [SavedStateHandle] and internal route data.
 */
fun SavedStateHandle.toBackupArgs(): BackupArgs =
    BackupArgs(isImport = this.toRoute<BackupRoute>().isImport)

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
 * Navigate to the backup screen, optionally starting with importing a backup ([isImport]).
 */
fun NavController.navigateToBackup(
    isImport: Boolean = false,
    navOptions: NavOptions? = null,
) {
    this.navigate(route = BackupRoute(isImport = isImport), navOptions = navOptions)
}
