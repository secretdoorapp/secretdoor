package com.x8bit.bitwarden.data.vault.manager

import com.bitwarden.core.data.util.flatMap
import com.bitwarden.vault.FolderView
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.model.CreateFolderResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteFolderResult
import com.x8bit.bitwarden.data.vault.repository.model.UpdateFolderResult
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkFolderResponse
import java.time.Clock
import java.util.UUID

/**
 * The default implementation of the [FolderManager].
 *
 * SecretDoor keeps the vault only on this device, so folders are encrypted by the SDK and written
 * straight to the local database.
 */
class FolderManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val vaultDiskSource: VaultDiskSource,
    private val vaultSdkSource: VaultSdkSource,
    private val clock: Clock,
) : FolderManager {
    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    override suspend fun createFolder(folderView: FolderView): CreateFolderResult {
        val userId = activeUserId
            ?: return CreateFolderResult.Error(error = NoActiveUserException())
        return saveFolder(
            userId = userId,
            folderView = folderView.copy(id = UUID.randomUUID().toString()),
        )
            .fold(
                onSuccess = { CreateFolderResult.Success(folderView = it) },
                onFailure = { CreateFolderResult.Error(error = it) },
            )
    }

    override suspend fun deleteFolder(folderId: String): DeleteFolderResult {
        val userId = activeUserId
            ?: return DeleteFolderResult.Error(error = NoActiveUserException())
        clearFolderIdFromCiphers(userId = userId, folderId = folderId)
        vaultDiskSource.deleteFolder(userId = userId, folderId = folderId)
        return DeleteFolderResult.Success
    }

    override suspend fun updateFolder(
        folderId: String,
        folderView: FolderView,
    ): UpdateFolderResult {
        val userId = activeUserId
            ?: return UpdateFolderResult.Error(error = NoActiveUserException())
        return saveFolder(userId = userId, folderView = folderView.copy(id = folderId))
            .fold(
                onSuccess = { UpdateFolderResult.Success(it) },
                onFailure = { UpdateFolderResult.Error(error = it) },
            )
    }

    /**
     * Encrypts the [folderView] with a new revision date, saves it to disk and returns the
     * decrypted result.
     */
    private suspend fun saveFolder(
        userId: String,
        folderView: FolderView,
    ): Result<FolderView> =
        vaultSdkSource
            .encryptFolder(
                userId = userId,
                folder = folderView.copy(revisionDate = clock.instant()),
            )
            .onSuccess {
                vaultDiskSource.saveFolder(
                    userId = userId,
                    folder = it.toEncryptedNetworkFolderResponse(),
                )
            }
            .flatMap { vaultSdkSource.decryptFolder(userId = userId, folder = it) }

    private suspend fun clearFolderIdFromCiphers(userId: String, folderId: String) {
        vaultDiskSource.getCiphers(userId = userId).forEach {
            if (it.folderId == folderId) {
                vaultDiskSource.saveCipher(userId = userId, cipher = it.copy(folderId = null))
            }
        }
    }
}
