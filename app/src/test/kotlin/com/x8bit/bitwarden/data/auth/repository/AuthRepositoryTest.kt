package com.x8bit.bitwarden.data.auth.repository

import app.cash.turbine.test
import com.bitwarden.auth.PasswordPreloginResponse
import com.bitwarden.core.AuthRequestResponse
import com.bitwarden.core.MasterPasswordAuthenticationData
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.RegisterKeyResponse
import com.bitwarden.core.UpdateKdfResponse
import com.bitwarden.core.UpdatePasswordResponse
import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.crypto.HashPurpose
import com.bitwarden.crypto.Kdf
import com.bitwarden.crypto.RsaKeyPair
import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.data.datasource.disk.model.ServerConfig
import com.bitwarden.data.repository.model.Environment
import com.bitwarden.network.model.ConfigResponseJson
import com.bitwarden.network.model.GetTokenResponseJson
import com.bitwarden.network.model.KdfJson
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.PreLoginResponseJson
import com.bitwarden.network.model.RefreshTokenResponseJson
import com.bitwarden.network.model.TrustedDeviceUserDecryptionOptionsJson
import com.bitwarden.network.model.TwoFactorAuthMethod
import com.bitwarden.network.model.TwoFactorDataModel
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.model.createMockAccountKeysJson
import com.bitwarden.network.model.createMockAccountKeysJsonWithNullFields
import com.bitwarden.network.service.HaveIBeenPwnedService
import com.bitwarden.network.service.IdentityService
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountTokensJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.OnboardingStatus
import com.x8bit.bitwarden.data.auth.datasource.disk.model.PendingAuthRequestJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.manager.AuthStateManager
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.repository.model.BreachCountResult
import com.x8bit.bitwarden.data.auth.repository.model.ChangeMasterPasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.DeleteAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.auth.repository.model.RegisterResult
import com.x8bit.bitwarden.data.auth.repository.model.SwitchAccountResult
import com.x8bit.bitwarden.data.auth.repository.model.UpdateKdfMinimumsResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePasswordResult
import com.x8bit.bitwarden.data.auth.repository.model.ValidatePinResult
import com.x8bit.bitwarden.data.auth.repository.model.createMockWrappedAccountCryptographicState
import com.x8bit.bitwarden.data.auth.repository.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.repository.util.toSdkParams
import com.x8bit.bitwarden.data.auth.repository.util.updateMasterPasswordUnlock
import com.x8bit.bitwarden.data.auth.util.toSdkParams
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.platform.manager.LogsManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.util.FakeEnvironmentRepository
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockData
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Suppress("LargeClass")
class AuthRepositoryTest {

    private val dispatcherManager: DispatcherManager = FakeDispatcherManager()
    private val identityService: IdentityService = mockk()
    private val haveIBeenPwnedService: HaveIBeenPwnedService = mockk()
    private val mutableVaultUnlockDataStateFlow = MutableStateFlow(VAULT_UNLOCK_DATA)
    private val mutableIsActiveUserUnlockingFlow = MutableStateFlow(false)
    private val vaultRepository: VaultRepository = mockk {
        every { vaultUnlockDataStateFlow } returns mutableVaultUnlockDataStateFlow
        every { deleteVaultData(any()) } just runs
        every { isActiveUserUnlockingFlow } returns mutableIsActiveUserUnlockingFlow
    }
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val fakeEnvironmentRepository = FakeEnvironmentRepository().apply {
        environment = Environment.Prod.Us
    }
    private val settingsRepository: SettingsRepository = mockk {
        every { setDefaultsIfNecessary(any()) } just runs
        every { hasUserLoggedInOrCreatedAccount = true } just runs
        every { storeUserHasLoggedInValue(any()) } just runs
    }
    private val authSdkSource = mockk<AuthSdkSource> {
        coEvery { getNewAuthRequest(email = EMAIL) } returns AUTH_REQUEST_RESPONSE.asSuccess()
        coEvery { preLogin(email = EMAIL) } returns PRE_LOGIN_SDK_SUCCESS.asSuccess()
        coEvery {
            hashPassword(
                salt = EMAIL,
                password = PASSWORD,
                kdf = PRE_LOGIN_SUCCESS.kdfParams.toSdkParams(),
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns PASSWORD_HASH.asSuccess()
        coEvery {
            hashPassword(
                salt = PRE_LOGIN_SDK_SUCCESS.salt,
                password = PASSWORD,
                kdf = PRE_LOGIN_SDK_SUCCESS.kdf,
                purpose = HashPurpose.SERVER_AUTHORIZATION,
            )
        } returns PASSWORD_HASH.asSuccess()
        coEvery {
            makeRegisterKeys(
                email = EMAIL,
                password = PASSWORD,
                kdf = Kdf.Pbkdf2(DEFAULT_KDF_ITERATIONS.toUInt()),
            )
        } returns RegisterKeyResponse(
            masterPasswordHash = PASSWORD_HASH,
            encryptedUserKey = ENCRYPTED_USER_KEY,
            keys = RsaKeyPair(
                public = PUBLIC_KEY,
                private = PRIVATE_KEY,
            ),
        )
            .asSuccess()
    }
    private val vaultSdkSource = mockk<VaultSdkSource> {
        coEvery {
            getAuthRequestKey(publicKey = PUBLIC_KEY, userId = USER_ID_1)
        } returns "AsymmetricEncString".asSuccess()
    }
    private val userLogoutManager: UserLogoutManager = mockk {
        every { logout(any(), any()) } just runs
        every { softLogout(any(), any()) } just runs
    }

    private val logsManager: LogsManager = mockk {
        every { setUserData(userId = any(), environmentType = any()) } just runs
    }
    private val mutableHasPendingAccountAdditionStateFlow = MutableStateFlow(false)
    private val userStateManager: UserStateManager = mockk {
        every {
            hasPendingAccountAdditionStateFlow
        } returns mutableHasPendingAccountAdditionStateFlow
        every { hasPendingAccountAddition = any() } just runs
        every {
            hasPendingAccountAddition
        } answers { mutableHasPendingAccountAdditionStateFlow.value }
        every { hasPendingAccountDeletion = any() } just runs
        val blockSlot = slot<suspend () -> VaultUnlockResult>()
        coEvery { userStateTransaction(capture(blockSlot)) } coAnswers { blockSlot.captured() }
    }
    private val kdfManager: KdfManager = mockk {
        every { needsKdfUpdateToMinimums() } returns false
        coEvery {
            updateKdfToMinimumsIfNeeded(password = any())
        } returns UpdateKdfMinimumsResult.Success
    }
    private val authStateManager: AuthStateManager = mockk()

    private val repository: AuthRepository = AuthRepositoryImpl(
        clock = FIXED_CLOCK,
        identityService = identityService,
        haveIBeenPwnedService = haveIBeenPwnedService,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        authDiskSource = fakeAuthDiskSource,
        environmentRepository = fakeEnvironmentRepository,
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        biometricsEncryptionManager = mockk(),
        userLogoutManager = userLogoutManager,
        dispatcherManager = dispatcherManager,
        logsManager = logsManager,
        userStateManager = userStateManager,
        kdfManager = kdfManager,
        authStateManager = authStateManager,
    )

    @BeforeEach
    fun beforeEach() {
        mockkStatic(UserStateJson::updateMasterPasswordUnlock)
        mockkConstructor(
            NoActiveUserException::class,
            MissingPropertyException::class,
        )
        every {
            anyConstructed<NoActiveUserException>() == any<NoActiveUserException>()
        } returns true
        every {
            anyConstructed<MissingPropertyException>() == any<MissingPropertyException>()
        } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(UserStateJson::updateMasterPasswordUnlock)
        mockkConstructor(
            NoActiveUserException::class,
            MissingPropertyException::class,
        )
    }

    @Test
    fun `changeMasterPassword stores the re-wrapped user key and the new password hash`() =
        runTest {
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeMasterPasswordHash(userId = USER_ID_1, passwordHash = "oldHash")
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = USER_ID_1,
                    password = "old password",
                    passwordHash = "oldHash",
                )
            } returns true.asSuccess()
            coEvery {
                vaultSdkSource.updatePassword(userId = USER_ID_1, newPassword = "new password")
            } returns UpdatePasswordResponse(passwordHash = "serverHash", newKey = "newKey").asSuccess()
            coEvery {
                authSdkSource.hashPassword(
                    salt = SALT,
                    password = "new password",
                    kdf = BASE_PROFILE_1.toSdkParams(),
                    purpose = HashPurpose.LOCAL_AUTHORIZATION,
                )
            } returns "newHash".asSuccess()

            val result = repository.changeMasterPassword(
                currentPassword = "old password",
                newPassword = "new password",
            )

            assertEquals(ChangeMasterPasswordResult.Success, result)
            assertEquals(
                "newKey",
                fakeAuthDiskSource
                    .userState
                    ?.activeAccount
                    ?.profile
                    ?.userDecryptionOptions
                    ?.masterPasswordUnlock
                    ?.masterKeyWrappedUserKey,
            )
            fakeAuthDiskSource.assertMasterPasswordHash(userId = USER_ID_1, passwordHash = "newHash")
        }

    @Test
    fun `changeMasterPassword with the wrong current password changes nothing`() = runTest {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHash(userId = USER_ID_1, passwordHash = "oldHash")
        coEvery {
            vaultSdkSource.validatePassword(
                userId = USER_ID_1,
                password = "wrong",
                passwordHash = "oldHash",
            )
        } returns false.asSuccess()

        val result = repository.changeMasterPassword(
            currentPassword = "wrong",
            newPassword = "new password",
        )

        assertEquals(ChangeMasterPasswordResult.WrongPassword, result)
        assertEquals(SINGLE_USER_STATE_1, fakeAuthDiskSource.userState)
        coVerify(exactly = 0) { vaultSdkSource.updatePassword(any(), any()) }
    }

    @Test
    fun `changeMasterPassword returns an error and changes nothing when the SDK fails`() =
        runTest {
            val error = Throwable("Fail")
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeMasterPasswordHash(userId = USER_ID_1, passwordHash = "oldHash")
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = USER_ID_1,
                    password = "old password",
                    passwordHash = "oldHash",
                )
            } returns true.asSuccess()
            coEvery {
                vaultSdkSource.updatePassword(userId = USER_ID_1, newPassword = "new password")
            } returns error.asFailure()

            val result = repository.changeMasterPassword(
                currentPassword = "old password",
                newPassword = "new password",
            )

            assertEquals(ChangeMasterPasswordResult.Error(error = error), result)
            assertEquals(SINGLE_USER_STATE_1, fakeAuthDiskSource.userState)
        }

    @Test
    fun `deleteAccountWithMasterPassword with a valid password erases the local vault`() =
        runTest {
            val password = "password"
            val passwordHash = "passwordHash"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeMasterPasswordHash(userId = USER_ID_1, passwordHash = passwordHash)
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = USER_ID_1,
                    password = password,
                    passwordHash = passwordHash,
                )
            } returns true.asSuccess()

            val result = repository.deleteAccountWithMasterPassword(masterPassword = password)

            assertEquals(DeleteAccountResult.Success, result)
            verify(exactly = 1) {
                userStateManager.hasPendingAccountDeletion = true
                userLogoutManager.logout(userId = USER_ID_1, reason = LogoutReason.AccountDelete)
            }
        }

    @Test
    fun `deleteAccountWithMasterPassword with an invalid password keeps the local vault`() =
        runTest {
            val password = "password"
            val passwordHash = "passwordHash"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeMasterPasswordHash(userId = USER_ID_1, passwordHash = passwordHash)
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = USER_ID_1,
                    password = password,
                    passwordHash = passwordHash,
                )
            } returns false.asSuccess()

            val result = repository.deleteAccountWithMasterPassword(masterPassword = password)

            assertEquals(DeleteAccountResult.Error(message = null, error = null), result)
            verify(exactly = 0) {
                userLogoutManager.logout(userId = any(), reason = any())
            }
        }

    @Test
    fun `refreshAccessTokenSynchronously returns failure if not logged in`() = runTest {
        fakeAuthDiskSource.userState = null

        val result = repository.refreshAccessTokenSynchronously(USER_ID_1)

        assertTrue(result.isFailure)
    }

    @Test
    fun `refreshAccessTokenSynchronously returns failure if refreshTokenSynchronously fails`() =
        runTest {
            fakeAuthDiskSource.storeAccountTokens(
                userId = USER_ID_1,
                accountTokens = ACCOUNT_TOKENS_1,
            )
            coEvery {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            } returns Throwable("Fail").asFailure()

            assertTrue(repository.refreshAccessTokenSynchronously(USER_ID_1).isFailure)

            coVerify(exactly = 1) {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `refreshAccessTokenSynchronously returns logs out and returns failure if refreshTokenSynchronously returns invalid_grant`() =
        runTest {
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeAccountTokens(
                userId = USER_ID_1,
                accountTokens = ACCOUNT_TOKENS_1,
            )
            coEvery {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            } returns RefreshTokenResponseJson.Error(error = "invalid_grant").asSuccess()

            assertTrue(repository.refreshAccessTokenSynchronously(USER_ID_1).isFailure)

            coVerify(exactly = 1) {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
                userLogoutManager.softLogout(userId = USER_ID_1, reason = LogoutReason.InvalidGrant)
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `refreshAccessTokenSynchronously returns logs out and returns failure if refreshTokenSynchronously returns Forbidden`() =
        runTest {
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeAccountTokens(
                userId = USER_ID_1,
                accountTokens = ACCOUNT_TOKENS_1,
            )
            coEvery {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            } returns RefreshTokenResponseJson.Forbidden(error = Throwable("Fail!")).asSuccess()

            assertTrue(repository.refreshAccessTokenSynchronously(USER_ID_1).isFailure)

            coVerify(exactly = 1) {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
                userLogoutManager.softLogout(
                    userId = USER_ID_1,
                    reason = LogoutReason.RefreshForbidden,
                )
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `refreshAccessTokenSynchronously returns logs out and returns failure if refreshTokenSynchronously returns Unauthorized`() =
        runTest {
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storeAccountTokens(
                userId = USER_ID_1,
                accountTokens = ACCOUNT_TOKENS_1,
            )
            coEvery {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
            } returns RefreshTokenResponseJson.Unauthorized(error = Throwable("Fail!")).asSuccess()

            assertTrue(repository.refreshAccessTokenSynchronously(USER_ID_1).isFailure)

            coVerify(exactly = 1) {
                identityService.refreshTokenSynchronously(REFRESH_TOKEN)
                userLogoutManager.softLogout(
                    userId = USER_ID_1,
                    reason = LogoutReason.RefreshUnauthorized,
                )
            }
        }

    @Test
    fun `refreshAccessTokenSynchronously returns success and sets account tokens`() = runTest {
        val updatedAccountTokens = AccountTokensJson(
            accessToken = ACCESS_TOKEN_2,
            refreshToken = REFRESH_TOKEN_2,
            expiresAtSec = FIXED_CLOCK.instant().epochSecond + ACCESS_TOKEN_2_EXPIRES_IN,
        )
        fakeAuthDiskSource.storeAccountTokens(
            userId = USER_ID_1,
            accountTokens = ACCOUNT_TOKENS_1,
        )
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        coEvery {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
        } returns REFRESH_TOKEN_RESPONSE_JSON.asSuccess()

        val result = repository.refreshAccessTokenSynchronously(USER_ID_1)

        assertEquals(REFRESH_TOKEN_RESPONSE_JSON.accessToken.asSuccess(), result)
        fakeAuthDiskSource.assertAccountTokens(
            userId = USER_ID_1,
            accountTokens = updatedAccountTokens,
        )
        coVerify(exactly = 1) {
            identityService.refreshTokenSynchronously(REFRESH_TOKEN)
        }
    }

    @Test
    fun `createLocalVault check data breaches found and strong password should return DataBreachFound`() =
        runTest {
            coEvery {
                haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD)
            } returns true.asSuccess()

            val result = repository.createLocalVault(
                masterPassword = PASSWORD,
                masterPasswordHint = null,
                shouldCheckDataBreaches = true,
                isMasterPasswordStrong = true,
            )

            assertEquals(RegisterResult.DataBreachFound, result)
            fakeAuthDiskSource.assertUserState(userState = null)
        }

    @Suppress("MaxLineLength")
    @Test
    fun `createLocalVault check data breaches found and weak password should return DataBreachAndWeakPassword`() =
        runTest {
            coEvery {
                haveIBeenPwnedService.hasPasswordBeenBreached(PASSWORD)
            } returns true.asSuccess()

            val result = repository.createLocalVault(
                masterPassword = PASSWORD,
                masterPasswordHint = null,
                shouldCheckDataBreaches = true,
                isMasterPasswordStrong = false,
            )

            assertEquals(RegisterResult.DataBreachAndWeakPassword, result)
            fakeAuthDiskSource.assertUserState(userState = null)
        }

    @Test
    fun `createLocalVault without data breach check and weak password should return WeakPassword`() =
        runTest {
            val result = repository.createLocalVault(
                masterPassword = PASSWORD,
                masterPasswordHint = null,
                shouldCheckDataBreaches = false,
                isMasterPasswordStrong = false,
            )

            assertEquals(RegisterResult.WeakPassword, result)
            fakeAuthDiskSource.assertUserState(userState = null)
            coVerify(exactly = 0) {
                haveIBeenPwnedService.hasPasswordBeenBreached(password = any())
                authSdkSource.makeRegisterKeys(email = any(), password = any(), kdf = any())
            }
        }

    @Test
    fun `createLocalVault with makeRegisterKeys failure should return Error`() = runTest {
        val error = Throwable("Fail")
        coEvery {
            authSdkSource.makeRegisterKeys(email = any(), password = PASSWORD, kdf = any())
        } returns error.asFailure()

        val result = repository.createLocalVault(
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            shouldCheckDataBreaches = false,
            isMasterPasswordStrong = true,
        )

        assertEquals(RegisterResult.Error(errorMessage = null, error = error), result)
        fakeAuthDiskSource.assertUserState(userState = null)
        coVerify(exactly = 0) {
            vaultRepository.unlockVaultWithMasterPassword(masterPassword = any())
        }
    }

    @Suppress("LongMethod")
    @Test
    fun `createLocalVault success should store local account, unlock vault and return Success`() =
        runTest {
            val emailSlot = slot<String>()
            val kdf = Kdf.Argon2id(iterations = 3u, memory = 64u, parallelism = 4u)
            coEvery {
                authSdkSource.makeRegisterKeys(
                    email = capture(emailSlot),
                    password = PASSWORD,
                    kdf = kdf,
                )
            } returns RegisterKeyResponse(
                masterPasswordHash = PASSWORD_HASH,
                encryptedUserKey = ENCRYPTED_USER_KEY,
                keys = RsaKeyPair(public = PUBLIC_KEY, private = PRIVATE_KEY),
            )
                .asSuccess()
            coEvery {
                vaultRepository.unlockVaultWithMasterPassword(masterPassword = PASSWORD)
            } returns VaultUnlockResult.Success

            val result = repository.createLocalVault(
                masterPassword = PASSWORD,
                masterPasswordHint = "hint",
                shouldCheckDataBreaches = false,
                isMasterPasswordStrong = true,
            )

            assertEquals(RegisterResult.Success, result)
            val userId = requireNotNull(fakeAuthDiskSource.userState?.activeUserId)
            val email = "$userId@secretdoor.local"
            assertEquals(email, emailSlot.captured)
            val kdfJson = KdfJson(
                kdfType = KdfTypeJson.ARGON2_ID,
                iterations = 3,
                memory = 64,
                parallelism = 4,
            )
            fakeAuthDiskSource.assertUserState(
                userState = UserStateJson(
                    activeUserId = userId,
                    accounts = mapOf(
                        userId to AccountJson(
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
                                kdfType = KdfTypeJson.ARGON2_ID,
                                kdfIterations = 3,
                                kdfMemory = 64,
                                kdfParallelism = 4,
                                userDecryptionOptions = UserDecryptionOptionsJson(
                                    hasMasterPassword = true,
                                    masterPasswordUnlock = MasterPasswordUnlockDataJson(
                                        salt = email,
                                        kdf = kdfJson,
                                        masterKeyWrappedUserKey = ENCRYPTED_USER_KEY,
                                    ),
                                    trustedDeviceUserDecryptionOptions = null,
                                    keyConnectorUserDecryptionOptions = null,
                                ),
                                creationDate = FIXED_CLOCK.instant(),
                            ),
                            settings = AccountJson.Settings(
                                environmentUrlData = Environment.Prod.Us.environmentUrlData,
                            ),
                        ),
                    ),
                ),
            )
            fakeAuthDiskSource.assertAccountTokens(
                userId = userId,
                accountTokens = AccountTokensJson(
                    accessToken = "secretdoor-local-vault",
                    refreshToken = null,
                ),
            )
            fakeAuthDiskSource.assertMasterPasswordHash(
                userId = userId,
                passwordHash = PASSWORD_HASH,
            )
            fakeAuthDiskSource.assertMasterPasswordHint(userId = userId, hint = "hint")
            fakeAuthDiskSource.assertAccountCryptographicState(
                userId = userId,
                accountCryptographicState = WrappedAccountCryptographicState.V1(
                    privateKey = PRIVATE_KEY,
                ),
            )
            verify(exactly = 1) {
                settingsRepository.hasUserLoggedInOrCreatedAccount = true
            }
            coVerify(exactly = 0) {
                haveIBeenPwnedService.hasPasswordBeenBreached(password = any())
            }
        }

    @Test
    fun `createLocalVault with unlock failure should return Error`() = runTest {
        val error = Throwable("Fail")
        coEvery {
            authSdkSource.makeRegisterKeys(email = any(), password = PASSWORD, kdf = any())
        } returns RegisterKeyResponse(
            masterPasswordHash = PASSWORD_HASH,
            encryptedUserKey = ENCRYPTED_USER_KEY,
            keys = RsaKeyPair(public = PUBLIC_KEY, private = PRIVATE_KEY),
        )
            .asSuccess()
        coEvery {
            vaultRepository.unlockVaultWithMasterPassword(masterPassword = PASSWORD)
        } returns VaultUnlockResult.GenericError(error = error)

        val result = repository.createLocalVault(
            masterPassword = PASSWORD,
            masterPasswordHint = null,
            shouldCheckDataBreaches = false,
            isMasterPasswordStrong = true,
        )

        assertEquals(RegisterResult.Error(errorMessage = null, error = error), result)
        verify(exactly = 0) {
            settingsRepository.hasUserLoggedInOrCreatedAccount = true
        }
    }

    @Test
    fun `getMasterPasswordHint should return the stored hint for the active user`() {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHint(userId = USER_ID_1, hint = "hint")

        assertEquals("hint", repository.getMasterPasswordHint())
    }

    @Test
    fun `getMasterPasswordHint with no active user should return null`() {
        fakeAuthDiskSource.userState = null

        assertNull(repository.getMasterPasswordHint())
    }

    @Test
    fun `logout for an inactive account should call logout on the UserLogoutManager`() {
        val userId = USER_ID_2
        val reason = LogoutReason.Timeout
        fakeAuthDiskSource.userState = MULTI_USER_STATE

        repository.logout(userId = userId, reason = reason)

        verify { userLogoutManager.logout(userId = userId, reason = reason) }
    }

    @Test
    fun `switchAccount when there is no saved UserState should do nothing`() {
        val updatedUserId = USER_ID_2

        fakeAuthDiskSource.userState = null

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = updatedUserId),
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the given userId is the same as the current activeUserId should reset any pending account additions`() {
        val originalUserId = USER_ID_1
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeEnvironmentRepository.environment = Environment.Prod.Eu
        repository.hasPendingAccountAddition = true

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = originalUserId),
        )

        assertEquals(Environment.Prod.Us, fakeEnvironmentRepository.environment)
        verify(exactly = 1) {
            userStateManager.hasPendingAccountAddition = false
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the given userId does not correspond to a saved account should do nothing`() {
        val invalidId = "invalidId"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeEnvironmentRepository.environment = Environment.Prod.Eu

        assertEquals(
            SwitchAccountResult.NoChange,
            repository.switchAccount(userId = invalidId),
        )

        assertEquals(Environment.Prod.Us, fakeEnvironmentRepository.environment)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `switchAccount when the userId is valid should update the current UserState and reset any pending account additions`() {
        val updatedUserId = USER_ID_2
        fakeAuthDiskSource.userState = MULTI_USER_STATE
        fakeEnvironmentRepository.environment = Environment.Prod.Eu
        repository.hasPendingAccountAddition = true

        assertEquals(
            SwitchAccountResult.AccountSwitched,
            repository.switchAccount(userId = updatedUserId),
        )

        assertEquals(Environment.Prod.Eu, fakeEnvironmentRepository.environment)
        verify(exactly = 1) {
            userStateManager.hasPendingAccountAddition = false
        }
    }

    @Test
    fun `getPasswordBreachCount should return failure when service returns failure`() = runTest {
        val password = "password"
        val error = Throwable("Fail")
        coEvery {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        } returns error.asFailure()

        val result = repository.getPasswordBreachCount(password)

        coVerify(exactly = 1) {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        }
        assertEquals(BreachCountResult.Error(error = error), result)
    }

    @Test
    fun `getPasswordBreachCount should return success when service returns success`() = runTest {
        val password = "password"
        val breachCount = 5
        coEvery {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        } returns breachCount.asSuccess()

        val result = repository.getPasswordBreachCount(password)

        coVerify(exactly = 1) {
            haveIBeenPwnedService.getPasswordBreachCount(password)
        }
        assertEquals(BreachCountResult.Success(breachCount), result)
    }

    @Test
    fun `validatePassword with no current user returns ValidatePasswordResult Error`() = runTest {
        val password = "password"
        fakeAuthDiskSource.userState = null

        val result = repository.validatePassword(password = password)

        assertEquals(
            ValidatePasswordResult.Error(error = NoActiveUserException()),
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePassword with no stored password hash and no stored masterKeyWrappedUserKey returns ValidatePasswordResult Error`() =
        runTest {
            val userId = USER_ID_1
            val password = "password"
            val passwordHash = "passwordHash"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1.copy(
                accounts = mapOf(
                    USER_ID_1 to ACCOUNT_1.copy(
                        profile = PROFILE_1.copy(userDecryptionOptions = null),
                    ),
                ),
            )
            coEvery {
                vaultSdkSource.validatePassword(
                    userId = userId,
                    password = password,
                    passwordHash = passwordHash,
                )
            } returns true.asSuccess()

            val result = repository.validatePassword(password = password)

            assertEquals(
                ValidatePasswordResult.Error(error = MissingPropertyException("UserKey")),
                result,
            )
        }

    @Test
    fun `validatePassword with sdk failure returns a ValidatePasswordResult Error`() = runTest {
        val userId = USER_ID_1
        val password = "password"
        val passwordHash = "passwordHash"
        val error = Throwable("Fail!")
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHash(userId = userId, passwordHash = passwordHash)
        coEvery {
            vaultSdkSource.validatePassword(
                userId = userId,
                password = password,
                passwordHash = passwordHash,
            )
        } returns error.asFailure()

        val result = repository.validatePassword(password = password)

        assertEquals(
            ValidatePasswordResult.Error(error = error),
            result,
        )
    }

    @Test
    fun `validatePassword with sdk success returns a ValidatePasswordResult Success`() = runTest {
        val userId = USER_ID_1
        val password = "password"
        val passwordHash = "passwordHash"
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        fakeAuthDiskSource.storeMasterPasswordHash(userId = userId, passwordHash = passwordHash)
        coEvery {
            vaultSdkSource.validatePassword(
                userId = userId,
                password = password,
                passwordHash = passwordHash,
            )
        } returns true.asSuccess()

        val result = repository.validatePassword(password = password)

        assertEquals(
            ValidatePasswordResult.Success(isValid = true),
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePassword with no stored password hash and a stored masterKeyWrappedUserKey with sdk failure returns ValidatePasswordResult Success invalid`() =
        runTest {
            val userId = USER_ID_1
            val password = "password"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            coEvery {
                vaultSdkSource.validatePasswordUserKey(
                    userId = userId,
                    password = password,
                    encryptedUserKey = ENCRYPTED_USER_KEY,
                )
            } returns Throwable("Fail").asFailure()

            val result = repository.validatePassword(password = password)

            assertEquals(ValidatePasswordResult.Success(isValid = false), result)
        }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePassword with no stored password hash and a stored masterKeyWrappedUserKey with sdk success returns ValidatePasswordResult Success valid`() =
        runTest {
            val userId = USER_ID_1
            val password = "password"
            val passwordHash = "passwordHash"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            coEvery {
                vaultSdkSource.validatePasswordUserKey(
                    userId = userId,
                    password = password,
                    encryptedUserKey = ENCRYPTED_USER_KEY,
                )
            } returns passwordHash.asSuccess()

            val result = repository.validatePassword(password = password)

            assertEquals(ValidatePasswordResult.Success(isValid = true), result)
            fakeAuthDiskSource.assertMasterPasswordHash(
                userId = userId,
                passwordHash = passwordHash,
            )
        }

    @Test
    fun `validatePin returns ValidatePinResult Error when no active account found`() = runTest {
        val pin = "PIN"
        fakeAuthDiskSource.userState = null

        val result = repository.validatePinUserKey(pin = pin)

        assertEquals(
            ValidatePinResult.Error(error = NoActiveUserException()),
            result,
        )
    }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePinUserKey returns ValidatePinResult Error when no pin protected user key found`() =
        runTest {
            val pin = "PIN"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storePinProtectedUserKey(
                userId = SINGLE_USER_STATE_1.activeUserId,
                pinProtectedUserKey = null,
            )

            val result = repository.validatePinUserKey(pin = pin)

            assertEquals(
                ValidatePinResult.Error(MissingPropertyException("Pin Protected User Key")),
                result,
            )
        }

    @Test
    fun `validatePinUserKey returns ValidatePinResult Error when SDK validatePin fails`() =
        runTest {
            val pin = "PIN"
            val pinProtectedUserKey = "pinProtectedUserKey"
            val error = Throwable("Fail!")
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storePersistentPinProtectedUserKeyEnvelope(
                userId = SINGLE_USER_STATE_1.activeUserId,
                pinProtectedUserKeyEnvelope = pinProtectedUserKey,
            )
            coEvery {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            } returns error.asFailure()

            val result = repository.validatePinUserKey(pin = pin)

            assertEquals(
                ValidatePinResult.Error(error = error),
                result,
            )
            coVerify(exactly = 1) {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePinUserKey returns ValidatePinResult Success with valid false when SDK validatePin returns false`() =
        runTest {
            val pin = "PIN"
            val pinProtectedUserKey = "pinProtectedUserKey"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storePersistentPinProtectedUserKeyEnvelope(
                userId = SINGLE_USER_STATE_1.activeUserId,
                pinProtectedUserKeyEnvelope = pinProtectedUserKey,
            )
            coEvery {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            } returns false.asSuccess()

            val result = repository.validatePinUserKey(pin = pin)

            assertEquals(
                ValidatePinResult.Success(isValid = false),
                result,
            )
            coVerify(exactly = 1) {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `validatePinUserKey returns ValidatePinResult Success with valid true when SDK validatePin returns true`() =
        runTest {
            val pin = "PIN"
            val pinProtectedUserKey = "pinProtectedUserKey"
            fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
            fakeAuthDiskSource.storePersistentPinProtectedUserKeyEnvelope(
                userId = SINGLE_USER_STATE_1.activeUserId,
                pinProtectedUserKeyEnvelope = pinProtectedUserKey,
            )
            coEvery {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            } returns true.asSuccess()

            val result = repository.validatePinUserKey(pin = pin)

            assertEquals(
                ValidatePinResult.Success(isValid = true),
                result,
            )
            coVerify(exactly = 1) {
                vaultSdkSource.validatePinUserKey(
                    userId = SINGLE_USER_STATE_1.activeUserId,
                    pin = pin,
                    pinProtectedUserKeyEnvelope = pinProtectedUserKey,
                )
            }
        }

    @Test
    fun `setOnboardingStatus should save the onboarding status to disk`() {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        repository.setOnboardingStatus(status = OnboardingStatus.NOT_STARTED)
        assertEquals(
            OnboardingStatus.NOT_STARTED,
            fakeAuthDiskSource.getOnboardingStatus(USER_ID_1),
        )
    }

    @Test
    fun `setOnboardingStatus with no userId does not change the onboarding state`() {
        fakeAuthDiskSource.userState = SINGLE_USER_STATE_1
        repository.setOnboardingStatus(status = OnboardingStatus.NOT_STARTED)
        assertEquals(
            OnboardingStatus.NOT_STARTED,
            fakeAuthDiskSource.getOnboardingStatus(USER_ID_1),
        )

        fakeAuthDiskSource.userState = null
        repository.setOnboardingStatus(status = OnboardingStatus.COMPLETE)
        val activeUserId = SINGLE_USER_STATE_1.activeUserId
        assertFalse(
            fakeAuthDiskSource.getOnboardingStatus(activeUserId) == OnboardingStatus.COMPLETE,
        )
    }

}

private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2023-10-27T12:00:00Z"),
    ZoneOffset.UTC,
)
private const val DEEPLINK_SCHEME = "https"
private const val UNIQUE_APP_ID = "testUniqueAppId"
private const val NAME = "Example Name"
private const val EMAIL = "test@bitwarden.com"
private const val EMAIL_2 = "test2@bitwarden.com"
private const val EMAIL_VERIFICATION_TOKEN = "thisisanawesometoken"
private const val SALT = "salt"
private const val PASSWORD = "password"
private const val PASSWORD_HASH = "passwordHash"
private const val ACCESS_TOKEN = "accessToken"
private const val ACCESS_TOKEN_2 = "accessToken2"
private const val REFRESH_TOKEN = "refreshToken"
private const val REFRESH_TOKEN_2 = "refreshToken2"
private const val ACCESS_TOKEN_2_EXPIRES_IN = 3600
private const val TWO_FACTOR_CODE = "123456"
private val TWO_FACTOR_METHOD = TwoFactorAuthMethod.EMAIL
private const val TWO_FACTOR_REMEMBER = true
private val TWO_FACTOR_DATA = TwoFactorDataModel(
    code = TWO_FACTOR_CODE,
    method = TWO_FACTOR_METHOD.value.toString(),
    remember = TWO_FACTOR_REMEMBER,
)
private const val SSO_CODE = "ssoCode"
private const val SSO_CODE_VERIFIER = "ssoCodeVerifier"
private const val SSO_REDIRECT_URI = "bitwarden://sso-test"
private const val DEVICE_ACCESS_CODE = "accessCode"
private const val DEVICE_REQUEST_ID = "authRequestId"
private const val DEVICE_ASYMMETRICAL_KEY = "asymmetricalKey"
private const val DEVICE_REQUEST_PRIVATE_KEY = "requestPrivateKey"

private const val DEFAULT_KDF_ITERATIONS = 600000
private const val ENCRYPTED_USER_KEY = "encryptedUserKey"
private const val PUBLIC_KEY = "PublicKey"
private const val PRIVATE_KEY = "privateKey"
private const val USER_ID_1 = "2a135b23-e1fb-42c9-bec3-573857bc8181"
private const val USER_ID_2 = "b9d32ec0-6497-4582-9798-b350f53bfa02"
private const val ORGANIZATION_IDENTIFIER = "organizationIdentifier"
private val ACCOUNT_KEYS = createMockAccountKeysJson(number = 1)
private val ACCOUNT_KEYS_WITH_NULL_FIELDS = createMockAccountKeysJsonWithNullFields(number = 1)
private val ACCOUNT_CRYPTOGRAPHIC_STATE_V2 = createMockWrappedAccountCryptographicState(number = 1)
private val ACCOUNT_CRYPTOGRAPHIC_STATE_V1 = WrappedAccountCryptographicState.V1(
    privateKey = "privateKey",
)
private val TWO_FACTOR_AUTH_METHODS_DATA = mapOf(
    TwoFactorAuthMethod.EMAIL to JsonObject(mapOf("Email" to JsonPrimitive("ex***@email.com"))),
    TwoFactorAuthMethod.AUTHENTICATOR_APP to JsonObject(mapOf("Email" to JsonNull)),
)
private val PRE_LOGIN_SUCCESS = PreLoginResponseJson(
    kdfParams = PreLoginResponseJson.KdfParams.Pbkdf2(iterations = 1u),
)
private val PRE_LOGIN_SDK_SUCCESS = PasswordPreloginResponse(
    kdf = Kdf.Pbkdf2(iterations = 1u),
    salt = SALT,
)
private val AUTH_REQUEST_RESPONSE = AuthRequestResponse(
    privateKey = PRIVATE_KEY,
    publicKey = PUBLIC_KEY,
    accessCode = "accessCode",
    fingerprint = "fingerprint",
)
private val REFRESH_TOKEN_RESPONSE_JSON = RefreshTokenResponseJson.Success(
    accessToken = ACCESS_TOKEN_2,
    expiresIn = ACCESS_TOKEN_2_EXPIRES_IN,
    refreshToken = REFRESH_TOKEN_2,
    tokenType = "Bearer",
)
private val TRUSTED_DEVICE_DECRYPTION_OPTIONS = TrustedDeviceUserDecryptionOptionsJson(
    encryptedPrivateKey = null,
    encryptedUserKey = null,
    hasAdminApproval = false,
    hasLoginApprovingDevice = false,
    hasManageResetPasswordPermission = false,
)
private val USER_DECRYPTION_OPTIONS = UserDecryptionOptionsJson(
    hasMasterPassword = false,
    trustedDeviceUserDecryptionOptions = TRUSTED_DEVICE_DECRYPTION_OPTIONS,
    keyConnectorUserDecryptionOptions = null,
    masterPasswordUnlock = null,
)


@Deprecated(
    message = "Use GET_TOKEN_WITH_ACCOUNT_KEYS_RESPONSE_SUCCESS instead",
    replaceWith = ReplaceWith(expression = "GET_TOKEN_WITH_ACCOUNT_KEYS_RESPONSE_SUCCESS"),
)
private val GET_TOKEN_RESPONSE_SUCCESS = GetTokenResponseJson.Success(
    accessToken = ACCESS_TOKEN,
    refreshToken = "refreshToken",
    tokenType = "Bearer",
    expiresInSeconds = 3600,
    key = "key",
    kdfType = KdfTypeJson.ARGON2_ID,
    kdfIterations = 600000,
    kdfMemory = 16,
    kdfParallelism = 4,
    privateKey = "privateKey",
    accountKeys = null,
    shouldForcePasswordReset = true,
    twoFactorToken = null,
    masterPasswordPolicyOptions = null,
    userDecryptionOptions = null,
)
private val GET_TOKEN_WITH_ACCOUNT_KEYS_RESPONSE_SUCCESS = GetTokenResponseJson.Success(
    accessToken = ACCESS_TOKEN,
    refreshToken = "refreshToken",
    tokenType = "Bearer",
    expiresInSeconds = 3600,
    key = "key",
    kdfType = KdfTypeJson.ARGON2_ID,
    kdfIterations = 600000,
    kdfMemory = 16,
    kdfParallelism = 4,
    privateKey = "privateKey",
    accountKeys = ACCOUNT_KEYS,
    shouldForcePasswordReset = true,
    twoFactorToken = null,
    masterPasswordPolicyOptions = null,
    userDecryptionOptions = UserDecryptionOptionsJson(
        hasMasterPassword = true,
        trustedDeviceUserDecryptionOptions = null,
        keyConnectorUserDecryptionOptions = null,
        masterPasswordUnlock = MasterPasswordUnlockDataJson(
            kdf = KdfJson(
                kdfType = KdfTypeJson.ARGON2_ID,
                iterations = 600000,
                memory = 16,
                parallelism = 4,
            ),
            masterKeyWrappedUserKey = "key",
            salt = SALT,
        ),
    ),
)
private val BASE_PROFILE_1 = AccountJson.Profile(
    userId = USER_ID_1,
    email = EMAIL,
    isEmailVerified = true,
    name = "Bitwarden Tester",
    hasPremiumPersonally = false,
    hasPremiumFromOrganization = null,
    stamp = null,
    organizationId = null,
    avatarColorHex = null,
    forcePasswordResetReason = null,
    kdfType = KdfTypeJson.ARGON2_ID,
    kdfIterations = 600000,
    kdfMemory = 16,
    kdfParallelism = 4,
    userDecryptionOptions = null,
    isTwoFactorEnabled = false,
    creationDate = Instant.parse("2024-09-13T01:00:00.00Z"),
)

private val PROFILE_1 = BASE_PROFILE_1.copy(
    userDecryptionOptions = UserDecryptionOptionsJson(
        hasMasterPassword = true,
        trustedDeviceUserDecryptionOptions = null,
        keyConnectorUserDecryptionOptions = null,
        masterPasswordUnlock = MasterPasswordUnlockDataJson(
            kdf = BASE_PROFILE_1.toSdkParams().toKdfRequestModel(),
            masterKeyWrappedUserKey = ENCRYPTED_USER_KEY,
            salt = SALT,
        ),
    ),
)

private val PROFILE_1_WITH_EMAIL_SALT = BASE_PROFILE_1.copy(
    userDecryptionOptions = UserDecryptionOptionsJson(
        hasMasterPassword = true,
        trustedDeviceUserDecryptionOptions = null,
        keyConnectorUserDecryptionOptions = null,
        masterPasswordUnlock = MasterPasswordUnlockDataJson(
            kdf = BASE_PROFILE_1.toSdkParams().toKdfRequestModel(),
            masterKeyWrappedUserKey = ENCRYPTED_USER_KEY,
            salt = EMAIL,
        ),
    ),
)
private val ACCOUNT_1 = AccountJson(
    profile = PROFILE_1,
    settings = AccountJson.Settings(environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US),
)
private val ACCOUNT_2 = AccountJson(
    profile = AccountJson.Profile(
        userId = USER_ID_2,
        email = EMAIL_2,
        isEmailVerified = true,
        name = "Bitwarden Tester 2",
        hasPremiumPersonally = false,
        hasPremiumFromOrganization = null,
        stamp = null,
        organizationId = null,
        avatarColorHex = null,
        forcePasswordResetReason = null,
        kdfType = KdfTypeJson.PBKDF2_SHA256,
        kdfIterations = 400000,
        kdfMemory = null,
        kdfParallelism = null,
        userDecryptionOptions = null,
        isTwoFactorEnabled = true,
        creationDate = Instant.parse("2024-09-13T01:00:00.00Z"),
    ),
    settings = AccountJson.Settings(
        environmentUrlData = EnvironmentUrlDataJson.DEFAULT_EU,
    ),
)
private val SINGLE_USER_STATE_1 = UserStateJson(
    activeUserId = USER_ID_1,
    accounts = mapOf(USER_ID_1 to ACCOUNT_1),
)
private val SINGLE_USER_STATE_1_WITH_PASS = UserStateJson(
    activeUserId = USER_ID_1,
    accounts = mapOf(
        USER_ID_1 to ACCOUNT_1.copy(
            profile = ACCOUNT_1.profile.copy(
                userDecryptionOptions = UserDecryptionOptionsJson(
                    hasMasterPassword = true,
                    keyConnectorUserDecryptionOptions = null,
                    trustedDeviceUserDecryptionOptions = null,
                    masterPasswordUnlock = MasterPasswordUnlockDataJson(
                        kdf = BASE_PROFILE_1.toSdkParams().toKdfRequestModel(),
                        masterKeyWrappedUserKey = ENCRYPTED_USER_KEY,
                        salt = EMAIL,
                    ),
                ),
            ),
        ),
    ),
)

private val MOCK_MASTER_PASSWORD_UNLOCK = MasterPasswordUnlockData(
    kdf = ACCOUNT_1.profile.toSdkParams(),
    masterKeyWrappedUserKey = "key",
    salt = SALT,
)

private val SINGLE_USER_STATE_2 = UserStateJson(
    activeUserId = USER_ID_2,
    accounts = mapOf(USER_ID_2 to ACCOUNT_2),
)
private val MULTI_USER_STATE = UserStateJson(
    activeUserId = USER_ID_1,
    accounts = mapOf(
        USER_ID_1 to ACCOUNT_1,
        USER_ID_2 to ACCOUNT_2,
    ),
)
private val ACCOUNT_TOKENS_1: AccountTokensJson = AccountTokensJson(
    accessToken = ACCESS_TOKEN,
    refreshToken = REFRESH_TOKEN,
)
private val ACCOUNT_TOKENS_2: AccountTokensJson = AccountTokensJson(
    accessToken = ACCESS_TOKEN_2,
    refreshToken = "refreshToken",
)
private val VAULT_UNLOCK_DATA = listOf(
    VaultUnlockData(
        userId = USER_ID_1,
        status = VaultUnlockData.Status.UNLOCKED,
    ),
)

private val SERVER_CONFIG_DEFAULT = ServerConfig(
    lastSync = 0L,
    serverData = ConfigResponseJson(
        type = "mockType",
        version = "mockVersion",
        gitHash = "mockGitHash",
        server = null,
        environment = ConfigResponseJson.EnvironmentJson(
            cloudRegion = "mockCloudRegion",
            vaultUrl = "mockVaultUrl",
            apiUrl = "mockApiUrl",
            identityUrl = "mockIdentityUrl",
            notificationsUrl = "mockNotificationsUrl",
            ssoUrl = "mockSsoUrl",
            fillAssistRulesUrl = null,
        ),
        featureStates = emptyMap(),
        communication = null,
        settings = null,
    ),
)

private val SERVER_CONFIG_UNOFFICIAL = SERVER_CONFIG_DEFAULT.copy(
    serverData = SERVER_CONFIG_DEFAULT.serverData.copy(
        server = ConfigResponseJson.ServerJson(
            name = "mockUnofficialServerName",
            url = "mockUnofficialServerUrl",
        ),
    ),
)

private val UPDATE_KDF_RESPONSE = UpdateKdfResponse(
    masterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = mockk<Kdf>(relaxed = true),
        salt = SALT,
        masterPasswordAuthenticationHash = "mockHash",
    ),
    masterPasswordUnlockData = MasterPasswordUnlockData(
        kdf = mockk<Kdf>(relaxed = true),
        masterKeyWrappedUserKey = "mockKey",
        salt = SALT,
    ),
    oldMasterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = mockk<Kdf>(relaxed = true),
        salt = SALT,
        masterPasswordAuthenticationHash = "mockHash",
    ),
)
