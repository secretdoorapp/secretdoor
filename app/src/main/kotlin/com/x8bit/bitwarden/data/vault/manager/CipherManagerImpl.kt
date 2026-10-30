package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import androidx.core.net.toUri
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.core.data.util.flatMap
import com.bitwarden.data.manager.file.FileManager
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.vault.AttachmentView
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.EncryptionContext
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.SettingsDiskSource
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.platform.manager.ReviewPromptManager
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.model.GetCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.ArchiveCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.CreateAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.CreateCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.DownloadAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.RestoreCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.ShareCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.UnarchiveCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.UpdateCipherResult
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkCipherResponse
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkCipher
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * The directory, relative to the app's files directory, that holds encrypted attachment files.
 */
private const val ATTACHMENTS_DIRECTORY: String = "attachments"

/**
 * The default implementation of the [CipherManager].
 *
 * SecretDoor keeps the vault only on this device: ciphers are encrypted by the SDK and written
 * straight to the local database, and encrypted attachment files are kept in app-private storage.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class CipherManagerImpl(
    private val fileManager: FileManager,
    private val authDiskSource: AuthDiskSource,
    private val settingsDiskSource: SettingsDiskSource,
    private val vaultDiskSource: VaultDiskSource,
    private val vaultSdkSource: VaultSdkSource,
    private val clock: Clock,
    private val reviewPromptManager: ReviewPromptManager,
) : CipherManager {
    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    override suspend fun createCipher(cipherView: CipherView): CreateCipherResult {
        val userId = activeUserId
            ?: return CreateCipherResult.Error(error = NoActiveUserException())
        val now = clock.instant()
        return saveCipher(
            userId = userId,
            cipherView = cipherView.copy(
                id = UUID.randomUUID().toString(),
                creationDate = now,
                revisionDate = now,
            ),
        )
            .fold(
                onFailure = { CreateCipherResult.Error(error = it) },
                onSuccess = {
                    reviewPromptManager.registerAddCipherAction()
                    CreateCipherResult.Success(cipherId = it.id)
                },
            )
    }

    override suspend fun createCipherInOrganization(
        cipherView: CipherView,
        collectionIds: List<String>,
    ): CreateCipherResult = CreateCipherResult.Error(error = organizationsNotSupported())

    override suspend fun archiveCipher(
        cipherId: String,
        cipherView: CipherView,
    ): ArchiveCipherResult {
        val userId = activeUserId
            ?: return ArchiveCipherResult.Error(error = NoActiveUserException())
        return saveCipher(
            userId = userId,
            cipherView = cipherView.copy(archivedDate = clock.instant()),
        )
            .onSuccess {
                settingsDiskSource.storeIntroducingArchiveActionCardDismissed(
                    userId = userId,
                    isDismissed = true,
                )
            }
            .fold(
                onSuccess = { ArchiveCipherResult.Success },
                onFailure = { ArchiveCipherResult.Error(error = it) },
            )
    }

    override suspend fun unarchiveCipher(
        cipherId: String,
        cipherView: CipherView,
    ): UnarchiveCipherResult {
        val userId = activeUserId
            ?: return UnarchiveCipherResult.Error(error = NoActiveUserException())
        return saveCipher(userId = userId, cipherView = cipherView.copy(archivedDate = null))
            .fold(
                onSuccess = { UnarchiveCipherResult.Success },
                onFailure = { UnarchiveCipherResult.Error(error = it) },
            )
    }

    override suspend fun hardDeleteCipher(cipherId: String): DeleteCipherResult {
        val userId = activeUserId
            ?: return DeleteCipherResult.Error(error = NoActiveUserException())
        vaultDiskSource.deleteCipher(userId = userId, cipherId = cipherId)
        cipherAttachmentsDirectory(userId = userId, cipherId = cipherId).deleteRecursively()
        return DeleteCipherResult.Success
    }

    override suspend fun softDeleteCipher(
        cipherId: String,
        cipherView: CipherView,
    ): DeleteCipherResult {
        val userId = activeUserId
            ?: return DeleteCipherResult.Error(error = NoActiveUserException())
        return saveCipher(
            userId = userId,
            cipherView = cipherView.copy(deletedDate = clock.instant()),
        )
            .fold(
                onSuccess = { DeleteCipherResult.Success },
                onFailure = { DeleteCipherResult.Error(error = it) },
            )
    }

    override suspend fun deleteCipherAttachment(
        cipherId: String,
        attachmentId: String,
        cipherView: CipherView,
    ): DeleteAttachmentResult =
        deleteCipherAttachmentForResult(
            cipherId = cipherId,
            attachmentId = attachmentId,
            cipherView = cipherView,
        )
            .fold(
                onSuccess = { DeleteAttachmentResult.Success },
                onFailure = { DeleteAttachmentResult.Error(error = it) },
            )

    private suspend fun deleteCipherAttachmentForResult(
        cipherId: String,
        attachmentId: String,
        cipherView: CipherView,
    ): Result<EncryptionContext> {
        val userId = activeUserId ?: return NoActiveUserException().asFailure()
        return encryptCipher(
            userId = userId,
            cipherView = cipherView.copy(
                attachments = cipherView.attachments?.filterNot { it.id == attachmentId },
            ),
        )
            .onSuccess { encryptionContext ->
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = encryptionContext.toLocalCipher(),
                )
                attachmentFile(
                    userId = userId,
                    cipherId = cipherId,
                    attachmentId = attachmentId,
                )
                    .delete()
            }
    }

    override suspend fun getCipher(cipherId: String): GetCipherResult {
        val userId = activeUserId ?: return GetCipherResult.Failure(NoActiveUserException())
        return vaultDiskSource
            .getCipher(userId = userId, cipherId = cipherId)
            ?.let { syncResponseCipher ->
                vaultSdkSource
                    .decryptCipher(
                        userId = userId,
                        cipher = syncResponseCipher.toEncryptedSdkCipher(),
                    )
                    .fold(
                        onSuccess = { GetCipherResult.Success(it) },
                        onFailure = { GetCipherResult.Failure(it) },
                    )
            }
            ?: GetCipherResult.CipherNotFound
    }

    override suspend fun restoreCipher(
        cipherId: String,
        cipherView: CipherView,
    ): RestoreCipherResult {
        val userId = activeUserId
            ?: return RestoreCipherResult.Error(error = NoActiveUserException())
        return saveCipher(userId = userId, cipherView = cipherView.copy(deletedDate = null))
            .fold(
                onSuccess = { RestoreCipherResult.Success },
                onFailure = { RestoreCipherResult.Error(error = it) },
            )
    }

    override suspend fun updateCipher(
        cipherId: String,
        cipherView: CipherView,
    ): UpdateCipherResult {
        val userId = activeUserId
            ?: return UpdateCipherResult.Error(error = NoActiveUserException())
        return saveCipher(userId = userId, cipherView = cipherView.copy(id = cipherId))
            .fold(
                onFailure = { UpdateCipherResult.Error(error = it) },
                onSuccess = { UpdateCipherResult.Success },
            )
    }

    override suspend fun shareCipher(
        cipherId: String,
        organizationId: String,
        cipherView: CipherView,
        collectionIds: List<String>,
    ): ShareCipherResult = ShareCipherResult.Error(error = organizationsNotSupported())

    override suspend fun updateCipherCollections(
        cipherId: String,
        collectionIds: List<String>,
    ): ShareCipherResult = ShareCipherResult.Error(error = organizationsNotSupported())

    override suspend fun createAttachment(
        cipherId: String,
        cipherView: CipherView,
        fileSizeBytes: String,
        fileName: String,
        fileUri: Uri,
    ): CreateAttachmentResult =
        createAttachmentForResult(
            cipherId = cipherId,
            cipherView = cipherView,
            fileSizeBytes = fileSizeBytes,
            fileName = fileName,
            fileUri = fileUri,
        )
            .fold(
                onFailure = {
                    CreateAttachmentResult.Error(
                        error = it,
                        message = when (it) {
                            is IllegalStateException -> it.message
                            else -> null
                        },
                    )
                },
                onSuccess = { CreateAttachmentResult.Success(cipherView = it) },
            )

    private suspend fun createAttachmentForResult(
        cipherId: String,
        cipherView: CipherView,
        fileSizeBytes: String?,
        fileName: String?,
        fileUri: Uri,
    ): Result<CipherView> {
        val userId = activeUserId ?: return NoActiveUserException().asFailure()
        val attachmentId = UUID.randomUUID().toString()
        val attachmentView = AttachmentView(
            id = null,
            url = null,
            size = fileSizeBytes,
            sizeName = null,
            fileName = fileName,
            key = null,
        )
        val encryptedFile = attachmentFile(
            userId = userId,
            cipherId = cipherId,
            attachmentId = attachmentId,
        )
        encryptedFile.parentFile?.mkdirs()
        return encryptCipher(userId = userId, cipherView = cipherView.copy(id = cipherId))
            .flatMap { encryptionContext ->
                fileManager
                    .writeUriToCache(fileUri = fileUri)
                    .flatMap { cacheFile ->
                        vaultSdkSource
                            .encryptAttachment(
                                userId = userId,
                                cipher = encryptionContext.cipher,
                                attachmentView = attachmentView,
                                decryptedFilePath = cacheFile.absolutePath,
                                encryptedFilePath = encryptedFile.absolutePath,
                            )
                            .also { fileManager.delete(cacheFile) }
                    }
                    .map { attachment ->
                        val cipher = encryptionContext.cipher
                        encryptionContext.copy(
                            cipher = cipher.copy(
                                attachments = cipher.attachments.orEmpty() +
                                    attachment.copy(id = attachmentId, url = null),
                            ),
                        )
                    }
            }
            .onFailure { encryptedFile.delete() }
            .map { it.toLocalCipher() }
            .onSuccess { vaultDiskSource.saveCipher(userId = userId, cipher = it) }
            .flatMap {
                vaultSdkSource.decryptCipher(
                    userId = userId,
                    cipher = it.toEncryptedSdkCipher(),
                )
            }
    }

    override suspend fun downloadAttachment(
        cipherView: CipherView,
        attachmentId: String,
    ): DownloadAttachmentResult =
        downloadAttachmentForResult(
            cipherView = cipherView,
            attachmentId = attachmentId,
        )
            .fold(
                onSuccess = { DownloadAttachmentResult.Success(file = it) },
                onFailure = { DownloadAttachmentResult.Failure(error = it) },
            )

    private suspend fun downloadAttachmentForResult(
        cipherView: CipherView,
        attachmentId: String,
    ): Result<File> {
        val userId = activeUserId ?: return NoActiveUserException().asFailure()
        val cipherId = cipherView.id
            ?: return IllegalStateException("CipherView must have an ID").asFailure()
        val cipher = encryptCipher(userId = userId, cipherView = cipherView)
            .fold(
                onSuccess = { it.cipher },
                onFailure = { return it.asFailure() },
            )
        val attachmentView = cipherView.attachments?.find { it.id == attachmentId }
            ?: return IllegalStateException("No attachment to download").asFailure()
        val storedFile = attachmentFile(
            userId = userId,
            cipherId = cipherId,
            attachmentId = attachmentId,
        )
        if (!storedFile.exists()) {
            return IllegalStateException("Attachment file is missing").asFailure()
        }

        // Decrypt from a copy in the cache so the decrypted file never sits next to the vault.
        val encryptedFile = fileManager
            .streamFileToCache(stream = storedFile.inputStream())
            .getOrElse { return it.asFailure() }
        val decryptedFile = File(encryptedFile.path + "_decrypted")
        return vaultSdkSource
            .decryptFile(
                userId = userId,
                cipher = cipher,
                attachmentView = attachmentView,
                encryptedFilePath = encryptedFile.path,
                decryptedFilePath = decryptedFile.path,
            )
            .onSuccess { fileManager.delete(encryptedFile) }
            .onFailure { fileManager.delete(encryptedFile) }
            .map { decryptedFile }
    }

    override suspend fun migrateAttachments(
        userId: String,
        cipherView: CipherView,
    ): Result<CipherView> {
        // Only run the migrations if we have attachments that do not have their own 'key'
        val attachmentViewsToMigrate = cipherView.attachments.orEmpty().filter { it.key == null }
        if (attachmentViewsToMigrate.none()) return cipherView.asSuccess()

        val cipherViewId = cipherView.id
            ?: return IllegalStateException("CipherView must have an ID").asFailure()
        var migratedCipherView = cipherView
        attachmentViewsToMigrate
            .map { attachmentView ->
                attachmentView
                    .id
                    ?.let { attachmentId ->
                        // This process decrypts the attachment file and creates an entirely
                        // new attachment before deleting the original one
                        downloadAttachmentForResult(
                            cipherView = migratedCipherView,
                            attachmentId = attachmentId,
                        )
                            .flatMap { file ->
                                createAttachmentForResult(
                                    cipherId = cipherViewId,
                                    cipherView = migratedCipherView,
                                    fileSizeBytes = attachmentView.size,
                                    fileName = attachmentView.fileName,
                                    fileUri = file.toUri(),
                                )
                                    .onSuccess { fileManager.delete(file) }
                            }
                            .flatMap { cipherView ->
                                deleteCipherAttachmentForResult(
                                    cipherView = cipherView,
                                    attachmentId = attachmentId,
                                    cipherId = cipherViewId,
                                )
                            }
                            .flatMap {
                                vaultSdkSource.decryptCipher(
                                    userId = userId,
                                    cipher = it.cipher,
                                )
                            }
                            .onSuccess { migratedCipherView = it }
                    }
                    ?: IllegalStateException("AttachmentView must have an ID").asFailure()
            }
            .onEach { result ->
                // If anything fails, we consider the entire process to be a failure
                // The attachments will be partially migrated and that is OK
                result.onFailure { return it.asFailure() }
            }
        return migratedCipherView.asSuccess()
    }

    /**
     * Encrypts the [cipherView] with a new revision date and saves it to disk.
     */
    private suspend fun saveCipher(
        userId: String,
        cipherView: CipherView,
    ): Result<SyncResponseJson.Cipher> =
        encryptCipher(userId = userId, cipherView = cipherView)
            .map { it.toLocalCipher() }
            .onSuccess { vaultDiskSource.saveCipher(userId = userId, cipher = it) }

    /**
     * Encrypts the [cipherView]. If the SDK created a new cipher key during encryption, the
     * migrated cipher is saved to disk right away so the stored copy uses the same key.
     */
    private suspend fun encryptCipher(
        userId: String,
        cipherView: CipherView,
    ): Result<EncryptionContext> =
        vaultSdkSource
            .encryptCipher(userId = userId, cipherView = cipherView)
            .onSuccess { encryptionContext ->
                if (encryptionContext.cipher.key != null && cipherView.key == null) {
                    vaultDiskSource.saveCipher(
                        userId = userId,
                        cipher = encryptionContext.toLocalCipher(),
                    )
                }
            }

    /**
     * Converts the encrypted cipher into its stored form, stamping a new revision date. A local
     * vault has a single owner, so the cipher is always editable and viewable.
     */
    private fun EncryptionContext.toLocalCipher(): SyncResponseJson.Cipher =
        toEncryptedNetworkCipherResponse().copy(
            revisionDate = clock.instant(),
            shouldEdit = true,
            shouldViewPassword = true,
        )

    private fun cipherAttachmentsDirectory(userId: String, cipherId: String): File =
        File(fileManager.filesDirectory, "$ATTACHMENTS_DIRECTORY/$userId/$cipherId")

    private fun attachmentFile(userId: String, cipherId: String, attachmentId: String): File =
        File(cipherAttachmentsDirectory(userId = userId, cipherId = cipherId), attachmentId)

    private fun organizationsNotSupported(): Throwable =
        UnsupportedOperationException("SecretDoor does not support organizations.")
}
