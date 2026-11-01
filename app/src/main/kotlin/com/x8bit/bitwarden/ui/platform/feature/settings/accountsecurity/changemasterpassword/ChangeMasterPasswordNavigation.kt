package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.changemasterpassword

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import com.bitwarden.annotation.OmitFromCoverage
import com.bitwarden.ui.platform.base.util.composableWithSlideTransitions
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the change master password screen.
 */
@OmitFromCoverage
@Serializable
data object ChangeMasterPasswordRoute

/**
 * Add the change master password screen to the nav graph.
 */
fun NavGraphBuilder.changeMasterPasswordDestination(
    onNavigateBack: () -> Unit,
) {
    composableWithSlideTransitions<ChangeMasterPasswordRoute> {
        ChangeMasterPasswordScreen(onNavigateBack = onNavigateBack)
    }
}

/**
 * Navigate to the change master password screen.
 */
fun NavController.navigateToChangeMasterPassword(navOptions: NavOptions? = null) {
    this.navigate(route = ChangeMasterPasswordRoute, navOptions = navOptions)
}
