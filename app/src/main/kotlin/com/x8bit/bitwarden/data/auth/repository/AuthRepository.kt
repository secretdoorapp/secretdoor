package com.x8bit.bitwarden.data.auth.repository

import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.manager.AuthStateManager
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteVaultResult
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePinResult
import com.x8bit.bitwarden.data.platform.datasource.network.authenticator.AuthenticatorProvider
import com.x8bit.bitwarden.data.platform.manager.BiometricsEncryptionManager

/**
 * Provides an API for observing and modifying authentication state.
 */
@Suppress("TooManyFunctions")
interface AuthRepository :
    AuthenticatorProvider,
    AuthStateManager,
    BiometricsEncryptionManager,
    KdfManager,
    UserStateManager {
    /**
     * Get the password strength for the given [email] and [password] combo. If no value is
     * passed for the [email] will use the active email of the current active account.
     */
    fun getPasswordStrength(email: String? = null, password: String): PasswordStrengthResult

    /**
     * Renames the vault with the given [userId] to [name]. A blank [name] clears the name, so the
     * vault is shown as "My vault" again.
     */
    fun renameVault(userId: String, name: String)

    /**
     * Permanently deletes the vault with the given [userId] and everything stored for it on this
     * device, after checking that [masterPassword] is that vault's master password. If the vault
     * was not the active one it becomes the active vault while its password is checked, and the
     * previously active vault is restored when the password is wrong.
     */
    suspend fun deleteVault(userId: String, masterPassword: String): DeleteVaultResult

    /**
     * Changes the active vault's master password from [currentPassword] to [newPassword]. The
     * vault's user key does not change; it is only wrapped with the new master password, on this
     * device only.
     */
    suspend fun changeMasterPassword(
        currentPassword: String,
        newPassword: String,
    ): ChangeMasterPasswordResult

    /**
     * Attempt to delete the current account using the [masterPassword] and log them out
     * upon success.
     */
    suspend fun deleteAccountWithMasterPassword(masterPassword: String): DeleteAccountResult

    /**
     * Log out the current user.
     */
    fun logout(reason: LogoutReason)

    /**
     * Switches to the account corresponding to the given [userId] if possible.
     */
    fun switchAccount(userId: String): SwitchAccountResult

    /**
     * Creates a new vault that exists only on this device, protected by the given
     * [masterPassword], and unlocks it. The optional [masterPasswordHint] is stored only on this
     * device. No server is contacted unless [shouldCheckDataBreaches] is `true`.
     */
    suspend fun createLocalVault(
        masterPassword: String,
        masterPasswordHint: String?,
        shouldCheckDataBreaches: Boolean,
        isMasterPasswordStrong: Boolean,
    ): RegisterResult

    /**
     * Returns the locally stored master password hint for the active user, or `null` if there is
     * no active user or no hint was set.
     */
    fun getMasterPasswordHint(): String?

    /**
     * Attempts to get the number of times the given [password] has been breached.
     */
    suspend fun getPasswordBreachCount(password: String): BreachCountResult

    /**
     * Validates the master password for the current logged-in user.
     */
    suspend fun validatePassword(password: String): ValidatePasswordResult

    /**
     * Validates the PIN for the current logged-in user.
     */
    suspend fun validatePinUserKey(pin: String): ValidatePinResult

    /**
     * Update the value of the onboarding status for the user.
     */
    fun setOnboardingStatus(status: OnboardingStatus)
}
