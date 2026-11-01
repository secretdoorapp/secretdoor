package com.x8bit.bitwarden.data.auth.repository

import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.core.data.util.flatMap
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.data.repository.util.toEnvironmentUrls
import com.bitwarden.data.repository.util.toEnvironmentUrlsOrDefault
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.RefreshTokenResponseJson
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.service.HaveIBeenPwnedService
import com.bitwarden.network.service.IdentityService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountTokensJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdf
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.manager.AuthStateManager
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteVaultResult
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePinResult
import com.x8bit.bitwarden.data.auth.repository.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_ITERATIONS
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_MEMORY
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_PARALLELISM
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.platform.manager.BiometricsEncryptionManager
import com.x8bit.bitwarden.data.platform.manager.LogsManager
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockError
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.Clock
import java.util.UUID
import javax.inject.Singleton

/**
 * Placeholder access token stored for a local vault account so that it is treated as logged in.
 * It is not a JWT and is never accepted by any server.
 */
internal const val LOCAL_VAULT_ACCESS_TOKEN: String = "secretdoor-local-vault"

/**
 * Suffix used to build the local-only email (master key salt) of a local vault account.
 */
private const val LOCAL_VAULT_EMAIL_SUFFIX: String = "@secretdoor.local"

/**
 * Default implementation of [AuthRepository].
 */
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
@Singleton
internal class AuthRepositoryImpl(
    private val clock: Clock,
    private val haveIBeenPwnedService: HaveIBeenPwnedService,
    private val identityService: IdentityService,
    private val authSdkSource: AuthSdkSource,
    private val vaultSdkSource: VaultSdkSource,
    private val authDiskSource: AuthDiskSource,
    private val environmentRepository: EnvironmentRepository,
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val biometricsEncryptionManager: BiometricsEncryptionManager,
    private val userLogoutManager: UserLogoutManager,
    private val userStateManager: UserStateManager,
    private val kdfManager: KdfManager,
    authStateManager: AuthStateManager,
    logsManager: LogsManager,
    dispatcherManager: DispatcherManager,
) : AuthRepository,
    AuthStateManager by authStateManager,
    BiometricsEncryptionManager by biometricsEncryptionManager,
    KdfManager by kdfManager,
    UserStateManager by userStateManager {
    /**
     * A scope intended for use when simply collecting multiple flows in order to combine them. The
     * use of [Dispatchers.Unconfined] allows for this to happen synchronously whenever any of
     * these flows changes.
     */
    private val unconfinedScope = CoroutineScope(dispatcherManager.unconfined)

    override val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    init {
        combine(
            userStateManager.hasPendingAccountAdditionStateFlow,
            authDiskSource.userStateFlow,
            environmentRepository.environmentStateFlow,
        ) { hasPendingAddition, userState, environment ->
            logsManager.setUserData(
                userId = userState?.activeUserId.takeUnless { hasPendingAddition },
                environmentType = userState
                    ?.activeAccount
                    ?.settings
                    ?.environmentUrlData
                    ?.toEnvironmentUrls()
                    ?.type
                    .takeUnless { hasPendingAddition }
                    ?: environment.type,
            )
        }
            .launchIn(unconfinedScope)
    }

    override fun renameVault(userId: String, name: String) {
        val userState = authDiskSource.userState ?: return
        val account = userState.accounts[userId] ?: return
        val renamed = account.copy(
            profile = account.profile.copy(name = name.trim().takeIf { it.isNotEmpty() }),
        )
        authDiskSource.userState = userState.copy(
            accounts = userState.accounts + (userId to renamed),
        )
    }

    override suspend fun deleteVault(userId: String, masterPassword: String): DeleteVaultResult {
        val previousActiveUserId = activeUserId
        if (userId != previousActiveUserId) {
            // The password can only be checked against the active vault, so it is made active.
            if (switchAccount(userId = userId) != SwitchAccountResult.AccountSwitched) {
                return DeleteVaultResult.Error(
                    error = IllegalStateException("The vault to delete was not found"),
                )
            }
        }
        val isPasswordValid = if (vaultRepository.isVaultUnlocked(userId = userId)) {
            when (val result = validatePassword(password = masterPassword)) {
                is ValidatePasswordResult.Success -> result.isValid
                is ValidatePasswordResult.Error -> {
                    restoreActiveVault(previousActiveUserId = previousActiveUserId, userId = userId)
                    return DeleteVaultResult.Error(error = result.error)
                }
            }
        } else {
            // Unlocking only succeeds with the right password, which is the check needed here.
            vaultRepository
                .unlockVaultWithMasterPassword(masterPassword = masterPassword) is
                VaultUnlockResult.Success
        }
        if (!isPasswordValid) {
            restoreActiveVault(previousActiveUserId = previousActiveUserId, userId = userId)
            return DeleteVaultResult.WrongPassword
        }
        logout(userId = userId, reason = LogoutReason.AccountDelete)
        return DeleteVaultResult.Success
    }

    private fun restoreActiveVault(previousActiveUserId: String?, userId: String) {
        if (previousActiveUserId != null && previousActiveUserId != userId) {
            switchAccount(userId = previousActiveUserId)
        }
    }

    override suspend fun changeMasterPassword(
        currentPassword: String,
        newPassword: String,
    ): ChangeMasterPasswordResult {
        val userState = authDiskSource.userState
            ?: return ChangeMasterPasswordResult.Error(error = NoActiveUserException())
        val userId = userState.activeUserId
        val account = userState.activeAccount
        val decryptionOptions = account.profile.userDecryptionOptions
        val masterPasswordUnlock = decryptionOptions?.masterPasswordUnlock
            ?: return ChangeMasterPasswordResult.Error(
                error = MissingPropertyException("MasterPasswordUnlock data"),
            )
        when (val validation = validatePassword(password = currentPassword)) {
            is ValidatePasswordResult.Error -> {
                return ChangeMasterPasswordResult.Error(error = validation.error)
            }

            is ValidatePasswordResult.Success -> {
                if (!validation.isValid) return ChangeMasterPasswordResult.WrongPassword
            }
        }
        // The SDK wraps the unchanged user key with a master key made from the new password
        // (same KDF settings and salt), so items, PIN and biometric unlock keep working.
        val response = vaultSdkSource
            .updatePassword(userId = userId, newPassword = newPassword)
            .getOrElse { return ChangeMasterPasswordResult.Error(error = it) }
        val updatedProfile = account.profile.copy(
            userDecryptionOptions = decryptionOptions.copy(
                masterPasswordUnlock = masterPasswordUnlock.copy(
                    masterKeyWrappedUserKey = response.newKey,
                ),
            ),
        )
        authDiskSource.userState = userState.copy(
            accounts = userState.accounts + (userId to account.copy(profile = updatedProfile)),
        )
        authSdkSource
            .hashPassword(
                salt = masterPasswordUnlock.salt,
                password = newPassword,
                kdf = masterPasswordUnlock.kdf.toKdf(),
                purpose = HashPurpose.LOCAL_AUTHORIZATION,
            )
            .onSuccess { authDiskSource.storeMasterPasswordHash(userId = userId, passwordHash = it) }
        return ChangeMasterPasswordResult.Success
    }

    override suspend fun deleteAccountWithMasterPassword(
        masterPassword: String,
    ): DeleteAccountResult =
        // SecretDoor: there is no server account. Deleting the account erases the local vault
        // once the master password has been confirmed.
        when (val result = validatePassword(password = masterPassword)) {
            is ValidatePasswordResult.Error -> {
                DeleteAccountResult.Error(message = null, error = result.error)
            }

            is ValidatePasswordResult.Success -> {
                if (result.isValid) {
                    userStateManager.hasPendingAccountDeletion = true
                    logout(reason = LogoutReason.AccountDelete)
                    DeleteAccountResult.Success
                } else {
                    DeleteAccountResult.Error(message = null, error = null)
                }
            }
        }

    override fun refreshAccessTokenSynchronously(
        userId: String,
    ): Result<String> {
        val refreshToken = authDiskSource
            .getAccountTokens(userId = userId)
            ?.refreshToken
            ?: return IllegalStateException("Must be logged in.").asFailure()
        return identityService
            .refreshTokenSynchronously(refreshToken)
            .flatMap { refreshTokenResponse ->
                // Check to make sure the user is still logged in after making the request
                authDiskSource
                    .userState
                    ?.accounts
                    ?.get(userId)
                    ?.let { refreshTokenResponse.asSuccess() }
                    ?: IllegalStateException("Must be logged in.").asFailure()
            }
            .flatMap { refreshTokenResponse ->
                when (refreshTokenResponse) {
                    is RefreshTokenResponseJson.Error -> {
                        if (refreshTokenResponse.isInvalidGrant) {
                            userLogoutManager.softLogout(
                                userId = userId,
                                reason = LogoutReason.InvalidGrant,
                            )
                        }
                        IllegalStateException(refreshTokenResponse.error).asFailure()
                    }

                    is RefreshTokenResponseJson.Forbidden -> {
                        userLogoutManager.softLogout(
                            userId = userId,
                            reason = LogoutReason.RefreshForbidden,
                        )
                        refreshTokenResponse.error.asFailure()
                    }

                    is RefreshTokenResponseJson.Unauthorized -> {
                        userLogoutManager.softLogout(
                            userId = userId,
                            reason = LogoutReason.RefreshUnauthorized,
                        )
                        refreshTokenResponse.error.asFailure()
                    }

                    is RefreshTokenResponseJson.Success -> {
                        // Store the new token information
                        authDiskSource.storeAccountTokens(
                            userId = userId,
                            accountTokens = AccountTokensJson(
                                accessToken = refreshTokenResponse.accessToken,
                                refreshToken = refreshTokenResponse.refreshToken,
                                expiresAtSec = clock.instant().epochSecond +
                                    refreshTokenResponse.expiresIn,
                            ),
                        )
                        refreshTokenResponse.accessToken.asSuccess()
                    }
                }
            }
    }

    override fun logout(reason: LogoutReason) {
        activeUserId?.let { userId -> logout(userId = userId, reason = reason) }
    }

    override fun logout(userId: String, reason: LogoutReason) {
        userLogoutManager.logout(userId = userId, reason = reason)
    }

    override fun switchAccount(userId: String): SwitchAccountResult {
        val currentUserState = authDiskSource.userState ?: return SwitchAccountResult.NoChange
        val previousActiveUserId = currentUserState.activeUserId
        val updateEnvironment: () -> Unit = {
            environmentRepository.environment = currentUserState
                .activeAccount
                .settings
                .environmentUrlData
                .toEnvironmentUrlsOrDefault()
        }

        if (userId == previousActiveUserId) {
            // We need to make sure that the environment is set back to the correct spot.
            updateEnvironment()
            // No switching to do but clear any pending account additions
            userStateManager.hasPendingAccountAddition = false
            return SwitchAccountResult.NoChange
        }

        if (userId !in currentUserState.accounts.keys) {
            // We need to make sure that the environment is set back to the correct spot.
            updateEnvironment()
            // The requested user is not currently stored
            return SwitchAccountResult.NoChange
        }

        // Switch to the new user
        authDiskSource.userState = currentUserState.copy(activeUserId = userId)

        // Clear any pending account additions
        userStateManager.hasPendingAccountAddition = false

        return SwitchAccountResult.AccountSwitched
    }

    @Suppress("LongMethod")
    override suspend fun createLocalVault(
        masterPassword: String,
        masterPasswordHint: String?,
        shouldCheckDataBreaches: Boolean,
        isMasterPasswordStrong: Boolean,
    ): RegisterResult {
        if (shouldCheckDataBreaches) {
            haveIBeenPwnedService
                .hasPasswordBeenBreached(password = masterPassword)
                .onSuccess { foundDataBreaches ->
                    if (foundDataBreaches) {
                        return if (isMasterPasswordStrong) {
                            RegisterResult.DataBreachFound
                        } else {
                            RegisterResult.DataBreachAndWeakPassword
                        }
                    }
                }
        }
        if (!isMasterPasswordStrong) {
            return RegisterResult.WeakPassword
        }

        val userId = UUID.randomUUID().toString()
        // The email is only used locally as the master key salt, so it is unique per vault.
        val email = "$userId$LOCAL_VAULT_EMAIL_SUFFIX"
        // Argon2id makes guessing the master password far more expensive than PBKDF2, which
        // matters because backups of the vault may be stored in the cloud.
        val kdf = Kdf.Argon2id(
            iterations = DEFAULT_ARGON2_ITERATIONS.toUInt(),
            memory = DEFAULT_ARGON2_MEMORY.toUInt(),
            parallelism = DEFAULT_ARGON2_PARALLELISM.toUInt(),
        )
        val registerKeyResponse = authSdkSource
            .makeRegisterKeys(email = email, password = masterPassword, kdf = kdf)
            .getOrElse { return RegisterResult.Error(errorMessage = null, error = it) }

        val kdfJson = kdf.toKdfRequestModel()
        val account = AccountJson(
            profile = AccountJson.Profile(
                userId = userId,
                email = email,
                isEmailVerified = true,
                isTwoFactorEnabled = false,
                name = null,
                stamp = null,
                organizationId = null,
                avatarColorHex = null,
                hasPremiumPersonally = true,
                hasPremiumFromOrganization = null,
                forcePasswordResetReason = null,
                kdfType = kdfJson.kdfType,
                kdfIterations = kdfJson.iterations,
                kdfMemory = kdfJson.memory,
                kdfParallelism = kdfJson.parallelism,
                userDecryptionOptions = UserDecryptionOptionsJson(
                    hasMasterPassword = true,
                    masterPasswordUnlock = MasterPasswordUnlockDataJson(
                        salt = email,
                        kdf = kdfJson,
                        masterKeyWrappedUserKey = registerKeyResponse.encryptedUserKey,
                    ),
                    trustedDeviceUserDecryptionOptions = null,
                    keyConnectorUserDecryptionOptions = null,
                ),
                creationDate = clock.instant(),
            ),
            settings = AccountJson.Settings(
                environmentUrlData = environmentRepository.environment.environmentUrlData,
            ),
        )

        val unlockResult = userStateManager.userStateTransaction {
            authDiskSource.storeAccountCryptographicState(
                userId = userId,
                accountCryptographicState = WrappedAccountCryptographicState.V1(
                    privateKey = registerKeyResponse.keys.private,
                ),
            )
            authDiskSource.storeMasterPasswordHash(
                userId = userId,
                passwordHash = registerKeyResponse.masterPasswordHash,
            )
            authDiskSource.storeMasterPasswordHint(userId = userId, hint = masterPasswordHint)
            // A local vault has no server session; this token only marks the account as active.
            authDiskSource.storeAccountTokens(
                userId = userId,
                accountTokens = AccountTokensJson(
                    accessToken = LOCAL_VAULT_ACCESS_TOKEN,
                    refreshToken = null,
                ),
            )
            // Other vaults on the device are kept; the new one becomes the active vault.
            authDiskSource.userState = UserStateJson(
                activeUserId = userId,
                accounts = authDiskSource.userState?.accounts.orEmpty() + (userId to account),
            )
            userStateManager.hasPendingAccountAddition = false
            vaultRepository.unlockVaultWithMasterPassword(masterPassword = masterPassword)
        }
        return when (unlockResult) {
            is VaultUnlockResult.Success -> {
                settingsRepository.hasUserLoggedInOrCreatedAccount = true
                RegisterResult.Success
            }

            is VaultUnlockError -> {
                RegisterResult.Error(errorMessage = null, error = unlockResult.error)
            }
        }
    }

    override fun getMasterPasswordHint(): String? =
        activeUserId?.let { authDiskSource.getMasterPasswordHint(userId = it) }

    override suspend fun getPasswordBreachCount(password: String): BreachCountResult =
        haveIBeenPwnedService
            .getPasswordBreachCount(password)
            .fold(
                onFailure = { BreachCountResult.Error(error = it) },
                onSuccess = { BreachCountResult.Success(it) },
            )

    override fun getPasswordStrength(
        email: String?,
        password: String,
    ): PasswordStrengthResult =
        authSdkSource
            .passwordStrength(
                email = email
                    ?: userStateFlow
                        .value
                        ?.activeAccount
                        ?.email
                        .orEmpty(),
                password = password,
            )
            .fold(
                onSuccess = { PasswordStrengthResult.Success(passwordStrength = it) },
                onFailure = { PasswordStrengthResult.Error(error = it) },
            )

    override suspend fun validatePassword(password: String): ValidatePasswordResult {
        val profile = authDiskSource
            .userState
            ?.activeAccount
            ?.profile
            ?: return ValidatePasswordResult.Error(error = NoActiveUserException())
        val userId = profile.userId
        return authDiskSource
            .getMasterPasswordHash(userId = userId)
            ?.let { masterPasswordHash ->
                vaultSdkSource
                    .validatePassword(
                        userId = userId,
                        password = password,
                        passwordHash = masterPasswordHash,
                    )
                    .fold(
                        onSuccess = { ValidatePasswordResult.Success(isValid = it) },
                        onFailure = { ValidatePasswordResult.Error(error = it) },
                    )
            }
            ?: run {
                val encryptedKey = profile
                    .userDecryptionOptions
                    ?.masterPasswordUnlock
                    ?.masterKeyWrappedUserKey
                    ?: return ValidatePasswordResult.Error(MissingPropertyException("UserKey"))
                vaultSdkSource
                    .validatePasswordUserKey(
                        userId = userId,
                        password = password,
                        encryptedUserKey = encryptedKey,
                    )
                    .onSuccess { masterPasswordHash ->
                        authDiskSource.storeMasterPasswordHash(
                            userId = userId,
                            passwordHash = masterPasswordHash,
                        )
                    }
                    .fold(
                        onSuccess = { ValidatePasswordResult.Success(isValid = true) },
                        onFailure = {
                            // We currently assume that all errors are caused by the user entering
                            // an invalid password, this is not necessarily the case, but we have
                            // no way to differentiate between the different errors.
                            ValidatePasswordResult.Success(isValid = false)
                        },
                    )
            }
    }

    override suspend fun validatePinUserKey(pin: String): ValidatePinResult {
        val activeAccount = authDiskSource
            .userState
            ?.activeAccount
            ?.profile
            ?: return ValidatePinResult.Error(error = NoActiveUserException())
        val pinProtectedUserKeyEnvelope = authDiskSource
            .getPinProtectedUserKeyEnvelope(userId = activeAccount.userId)
            ?: return ValidatePinResult.Error(
                error = MissingPropertyException("Pin Protected User Key Envelope"),
            )
        return vaultSdkSource
            .validatePinUserKey(
                userId = activeAccount.userId,
                pin = pin,
                pinProtectedUserKeyEnvelope = pinProtectedUserKeyEnvelope,
            )
            .fold(
                onSuccess = { ValidatePinResult.Success(isValid = it) },
                onFailure = { ValidatePinResult.Error(error = it) },
            )
    }

    override fun setOnboardingStatus(status: OnboardingStatus) {
        activeUserId?.let { userId ->
            authDiskSource.storeOnboardingStatus(
                userId = userId,
                onboardingStatus = status,
            )
        }
    }
}
