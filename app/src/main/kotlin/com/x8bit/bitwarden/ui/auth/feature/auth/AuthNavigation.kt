package com.x8bit.bitwarden.ui.auth.feature.auth

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptions
import androidx.navigation.navigation
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.CompleteRegistrationRoute
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.completeRegistrationDestination
import com.x8bit.bitwarden.ui.auth.feature.completeregistration.popUpToCompleteRegistration
import com.x8bit.bitwarden.ui.auth.feature.masterpasswordgenerator.masterPasswordGeneratorDestination
import com.x8bit.bitwarden.ui.auth.feature.masterpasswordgenerator.navigateToMasterPasswordGenerator
import com.x8bit.bitwarden.ui.auth.feature.masterpasswordguidance.masterPasswordGuidanceDestination
import com.x8bit.bitwarden.ui.auth.feature.masterpasswordguidance.navigateToMasterPasswordGuidance
import com.x8bit.bitwarden.ui.auth.feature.preventaccountlockout.navigateToPreventAccountLockout
import com.x8bit.bitwarden.ui.auth.feature.preventaccountlockout.preventAccountLockoutDestination
import kotlinx.serialization.Serializable

/**
 * The type-safe route for the auth graph.
 */
@Serializable
data object AuthGraphRoute

/**
 * Add auth destinations to the nav graph.
 */
fun NavGraphBuilder.authGraph(
    navController: NavHostController,
) {
    navigation<AuthGraphRoute>(
        // SecretDoor has no server accounts, so onboarding starts by creating a local vault.
        startDestination = CompleteRegistrationRoute(
            emailAddress = "",
            verificationToken = "",
            fromEmail = false,
        ),
    ) {
        completeRegistrationDestination(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToPasswordGuidance = {
                navController.navigateToMasterPasswordGuidance()
            },
            onNavigateToPreventAccountLockout = {
                navController.navigateToPreventAccountLockout(isPasswordReset = false)
            },
        )
        masterPasswordGuidanceDestination(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToGeneratePassword = { navController.navigateToMasterPasswordGenerator() },
        )
        preventAccountLockoutDestination(
            isPasswordReset = false,
            onNavigateBack = { navController.popBackStack() },
        )
        masterPasswordGeneratorDestination(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToPreventLockout = {
                navController.navigateToPreventAccountLockout(isPasswordReset = false)
            },
            onNavigateBackWithPassword = {
                navController.popUpToCompleteRegistration()
            },
        )
    }
}

/**
 * Navigate to the auth screen. Note this will only work if auth destination was added
 * via [authGraph].
 */
fun NavController.navigateToAuthGraph(
    navOptions: NavOptions? = null,
) {
    navigate(route = AuthGraphRoute, navOptions = navOptions)
}
