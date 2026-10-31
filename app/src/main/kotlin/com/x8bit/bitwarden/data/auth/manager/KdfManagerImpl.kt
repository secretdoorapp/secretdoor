package com.x8bit.bitwarden.data.auth.manager

import com.bitwarden.crypto.Kdf
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.util.toKdfRequestModel
import com.x8bit.bitwarden.data.auth.repository.model.UpdateKdfMinimumsResult
import com.x8bit.bitwarden.data.auth.repository.util.updateKdf
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_ITERATIONS
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_MEMORY
import com.x8bit.bitwarden.data.auth.util.KdfParamsConstants.DEFAULT_ARGON2_PARALLELISM
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import timber.log.Timber

/**
 * Default implementation of [KdfManager].
 *
 * SecretDoor vaults are local, so a KDF change only has to be stored on the device: vaults that
 * still use PBKDF2 are moved to Argon2id the next time they are unlocked with the master
 * password. The user key itself does not change, so items, PIN and biometric unlock and backups
 * are not affected.
 */
class KdfManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val vaultSdkSource: VaultSdkSource,
) : KdfManager {

    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    /**
     * The upgrade happens silently while unlocking with the master password, so there is never a
     * need to ask for the password separately.
     */
    override fun needsKdfUpdateToMinimums(): Boolean = false

    override suspend fun updateKdfToMinimumsIfNeeded(password: String): UpdateKdfMinimumsResult {
        val userId = activeUserId ?: return UpdateKdfMinimumsResult.ActiveAccountNotFound
        val account = authDiskSource.userState?.accounts?.get(userId)
            ?: return UpdateKdfMinimumsResult.ActiveAccountNotFound
        val decryptionOptions = account.profile.userDecryptionOptions
        if (decryptionOptions?.hasMasterPassword != true ||
            decryptionOptions.masterPasswordUnlock == null ||
            account.profile.kdfType != KdfTypeJson.PBKDF2_SHA256
        ) {
            return UpdateKdfMinimumsResult.Success
        }
        return vaultSdkSource
            .makeUpdateKdf(
                userId = userId,
                password = password,
                kdf = Kdf.Argon2id(
                    iterations = DEFAULT_ARGON2_ITERATIONS.toUInt(),
                    memory = DEFAULT_ARGON2_MEMORY.toUInt(),
                    parallelism = DEFAULT_ARGON2_PARALLELISM.toUInt(),
                ),
            )
            .fold(
                onSuccess = { response ->
                    val unlockData = response.masterPasswordUnlockData
                    val kdfJson = unlockData.kdf.toKdfRequestModel()
                    val masterPasswordUnlock = MasterPasswordUnlockDataJson(
                        salt = unlockData.salt,
                        kdf = kdfJson,
                        masterKeyWrappedUserKey = unlockData.masterKeyWrappedUserKey,
                        containedKeyId = unlockData.containedKeyId,
                    )
                    // The KDF settings and the re-wrapped user key are stored together, so the
                    // vault always unlocks with one consistent set.
                    authDiskSource.userState = authDiskSource.userState
                        ?.updateKdf(userId = userId, kdf = kdfJson)
                        ?.let { userState ->
                            val updatedAccount = userState.accounts.getValue(userId).let {
                                it.copy(
                                    profile = it.profile.copy(
                                        userDecryptionOptions = decryptionOptions.copy(
                                            masterPasswordUnlock = masterPasswordUnlock,
                                        ),
                                    ),
                                )
                            }
                            userState.copy(accounts = userState.accounts + (userId to updatedAccount))
                        }
                    Timber.d("[Auth] Moved the vault's KDF to Argon2id")
                    UpdateKdfMinimumsResult.Success
                },
                onFailure = { UpdateKdfMinimumsResult.Error(error = it) },
            )
    }
}
