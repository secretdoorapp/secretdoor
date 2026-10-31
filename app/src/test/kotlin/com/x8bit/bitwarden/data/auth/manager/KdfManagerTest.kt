package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.core.MasterPasswordAuthenticationData
import com.bitwarden.core.MasterPasswordUnlockData
import com.bitwarden.core.UpdateKdfResponse
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.crypto.Kdf
import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.network.model.KdfJson
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.repository.model.UpdateKdfMinimumsResult
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class KdfManagerTest {

    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val vaultSdkSource: VaultSdkSource = mockk()

    private val manager: KdfManager = KdfManagerImpl(
        authDiskSource = fakeAuthDiskSource,
        vaultSdkSource = vaultSdkSource,
    )

    @Test
    fun `needsKdfUpdateToMinimums is always false because the update happens on unlock`() {
        fakeAuthDiskSource.userState = USER_STATE

        assertFalse(manager.needsKdfUpdateToMinimums())
    }

    @Test
    fun `updateKdfToMinimumsIfNeeded with no active user should return ActiveAccountNotFound`() =
        runTest {
            fakeAuthDiskSource.userState = null

            assertEquals(
                UpdateKdfMinimumsResult.ActiveAccountNotFound,
                manager.updateKdfToMinimumsIfNeeded(password = PASSWORD),
            )
        }

    @Test
    fun `updateKdfToMinimumsIfNeeded with Argon2id should do nothing`() = runTest {
        val argon2State = USER_STATE.withProfile {
            it.copy(kdfType = KdfTypeJson.ARGON2_ID, kdfIterations = 3)
        }
        fakeAuthDiskSource.userState = argon2State

        assertEquals(
            UpdateKdfMinimumsResult.Success,
            manager.updateKdfToMinimumsIfNeeded(password = PASSWORD),
        )
        assertEquals(argon2State, fakeAuthDiskSource.userState)
        coVerify(exactly = 0) { vaultSdkSource.makeUpdateKdf(any(), any(), any()) }
    }

    @Test
    fun `updateKdfToMinimumsIfNeeded without a master password should do nothing`() = runTest {
        fakeAuthDiskSource.userState = USER_STATE.withProfile {
            it.copy(userDecryptionOptions = null)
        }

        assertEquals(
            UpdateKdfMinimumsResult.Success,
            manager.updateKdfToMinimumsIfNeeded(password = PASSWORD),
        )
        coVerify(exactly = 0) { vaultSdkSource.makeUpdateKdf(any(), any(), any()) }
    }

    @Test
    fun `updateKdfToMinimumsIfNeeded with an SDK error should return Error and keep PBKDF2`() =
        runTest {
            val error = Throwable("Fail")
            fakeAuthDiskSource.userState = USER_STATE
            coEvery {
                vaultSdkSource.makeUpdateKdf(userId = USER_ID, password = PASSWORD, kdf = ARGON2)
            } returns error.asFailure()

            assertEquals(
                UpdateKdfMinimumsResult.Error(error = error),
                manager.updateKdfToMinimumsIfNeeded(password = PASSWORD),
            )
            assertEquals(USER_STATE, fakeAuthDiskSource.userState)
        }

    @Test
    fun `updateKdfToMinimumsIfNeeded with PBKDF2 should store Argon2id and the new wrapped key`() =
        runTest {
            fakeAuthDiskSource.userState = USER_STATE
            coEvery {
                vaultSdkSource.makeUpdateKdf(userId = USER_ID, password = PASSWORD, kdf = ARGON2)
            } returns UPDATE_KDF_RESPONSE.asSuccess()

            assertEquals(
                UpdateKdfMinimumsResult.Success,
                manager.updateKdfToMinimumsIfNeeded(password = PASSWORD),
            )

            val argon2Json = KdfJson(
                kdfType = KdfTypeJson.ARGON2_ID,
                iterations = 3,
                memory = 64,
                parallelism = 4,
            )
            val profile = fakeAuthDiskSource.userState!!.accounts.getValue(USER_ID).profile
            assertEquals(KdfTypeJson.ARGON2_ID, profile.kdfType)
            assertEquals(3, profile.kdfIterations)
            assertEquals(64, profile.kdfMemory)
            assertEquals(4, profile.kdfParallelism)
            assertEquals(
                MasterPasswordUnlockDataJson(
                    salt = EMAIL,
                    kdf = argon2Json,
                    masterKeyWrappedUserKey = "newWrappedUserKey",
                    containedKeyId = "keyId",
                ),
                profile.userDecryptionOptions?.masterPasswordUnlock,
            )
            assertTrue(profile.userDecryptionOptions?.hasMasterPassword == true)
        }
}

private fun UserStateJson.withProfile(
    update: (AccountJson.Profile) -> AccountJson.Profile,
): UserStateJson {
    val account = accounts.getValue(activeUserId)
    return copy(
        accounts = mapOf(activeUserId to account.copy(profile = update(account.profile))),
    )
}

private const val EMAIL = "vault@secretdoor.local"
private const val PASSWORD = "password"
private const val USER_ID = "2a135b23-e1fb-42c9-bec3-573857bc8181"
private val ARGON2 = Kdf.Argon2id(iterations = 3u, memory = 64u, parallelism = 4u)
private val PBKDF2_JSON = KdfJson(
    kdfType = KdfTypeJson.PBKDF2_SHA256,
    iterations = 600000,
    memory = null,
    parallelism = null,
)
private val USER_STATE = UserStateJson(
    activeUserId = USER_ID,
    accounts = mapOf(
        USER_ID to AccountJson(
            profile = AccountJson.Profile(
                userId = USER_ID,
                email = EMAIL,
                isEmailVerified = true,
                name = null,
                hasPremiumPersonally = true,
                hasPremiumFromOrganization = null,
                stamp = null,
                organizationId = null,
                avatarColorHex = null,
                forcePasswordResetReason = null,
                kdfType = KdfTypeJson.PBKDF2_SHA256,
                kdfIterations = 600000,
                kdfMemory = null,
                kdfParallelism = null,
                userDecryptionOptions = UserDecryptionOptionsJson(
                    hasMasterPassword = true,
                    masterPasswordUnlock = MasterPasswordUnlockDataJson(
                        salt = EMAIL,
                        kdf = PBKDF2_JSON,
                        masterKeyWrappedUserKey = "oldWrappedUserKey",
                    ),
                    trustedDeviceUserDecryptionOptions = null,
                    keyConnectorUserDecryptionOptions = null,
                ),
                isTwoFactorEnabled = false,
                creationDate = Instant.parse("2024-09-13T01:00:00.00Z"),
            ),
            settings = AccountJson.Settings(environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US),
        ),
    ),
)

private val UPDATE_KDF_RESPONSE = UpdateKdfResponse(
    masterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = ARGON2,
        salt = EMAIL,
        masterPasswordAuthenticationHash = "newHash",
    ),
    masterPasswordUnlockData = MasterPasswordUnlockData(
        kdf = ARGON2,
        masterKeyWrappedUserKey = "newWrappedUserKey",
        salt = EMAIL,
        containedKeyId = "keyId",
    ),
    oldMasterPasswordAuthenticationData = MasterPasswordAuthenticationData(
        kdf = Kdf.Pbkdf2(iterations = 600000u),
        salt = EMAIL,
        masterPasswordAuthenticationHash = "oldHash",
    ),
)
