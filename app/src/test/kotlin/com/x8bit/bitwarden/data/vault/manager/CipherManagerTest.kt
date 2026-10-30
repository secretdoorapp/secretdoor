package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.data.manager.file.FileManager
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.EncryptionContext
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountTokensJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.util.FakeSettingsDiskSource
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.platform.manager.ReviewPromptManager
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockAttachmentView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockEncryptionContext
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkAttachment
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Suppress("LargeClass")
class CipherManagerTest {

    @TempDir
    lateinit var tempDir: File

    private val clock: Clock = Clock.fixed(
        Instant.parse("2023-10-27T12:00:00Z"),
        ZoneOffset.UTC,
    )
    private val fileManager: FileManager = mockk {
        every { filesDirectory } answers { tempDir.absolutePath }
        coEvery { delete(*anyVararg()) } just runs
    }
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val fakeSettingsDiskSource = FakeSettingsDiskSource()
    private val vaultDiskSource: VaultDiskSource = mockk {
        coEvery { saveCipher(userId = any(), cipher = any()) } just runs
        coEvery { deleteCipher(userId = any(), cipherId = any()) } just runs
    }
    private val vaultSdkSource: VaultSdkSource = mockk()
    private val reviewPromptManager: ReviewPromptManager = mockk {
        every { registerAddCipherAction() } just runs
    }

    private val cipherManager: CipherManager = CipherManagerImpl(
        fileManager = fileManager,
        authDiskSource = fakeAuthDiskSource,
        settingsDiskSource = fakeSettingsDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        clock = clock,
        reviewPromptManager = reviewPromptManager,
    )

    @BeforeEach
    fun setup() {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        mockkStatic(UUID::randomUUID)
        every { UUID.randomUUID().toString() } returns GENERATED_ID
        mockkConstructor(NoActiveUserException::class)
        every {
            anyConstructed<NoActiveUserException>() == any<NoActiveUserException>()
        } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(UUID::randomUUID)
        unmockkConstructor(NoActiveUserException::class)
    }

    @Test
    fun `createCipher with no active user should return CreateCipherResult failure`() = runTest {
        fakeAuthDiskSource.userState = null

        val result = cipherManager.createCipher(cipherView = mockk())

        assertEquals(CreateCipherResult.Error(error = NoActiveUserException()), result)
    }

    @Test
    fun `createCipher with encryptCipher failure should return failure and not save`() = runTest {
        val cipherView = createMockCipherView(number = 1)
        val error = IllegalStateException()
        coEvery {
            vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView.asNewCipher())
        } returns error.asFailure()

        val result = cipherManager.createCipher(cipherView = cipherView)

        assertEquals(CreateCipherResult.Error(error = error), result)
        coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
        verify(exactly = 0) { reviewPromptManager.registerAddCipherAction() }
    }

    @Test
    fun `createCipher success should generate an ID and save the cipher locally`() = runTest {
        val cipherView = createMockCipherView(number = 1)
        val encryptionContext = createMockEncryptionContext(number = 1)
        coEvery {
            vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView.asNewCipher())
        } returns encryptionContext.asSuccess()

        val result = cipherManager.createCipher(cipherView = cipherView)

        assertEquals(CreateCipherResult.Success(cipherId = "mockId-1"), result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = USER_ID,
                cipher = encryptionContext.toStoredCipher(),
            )
        }
        verify(exactly = 1) { reviewPromptManager.registerAddCipherAction() }
    }

    @Test
    fun `createCipherInOrganization should return an unsupported error`() = runTest {
        val result = cipherManager.createCipherInOrganization(
            cipherView = createMockCipherView(number = 1),
            collectionIds = listOf("mockId-1"),
        )

        assertTrue(
            (result as CreateCipherResult.Error).error is UnsupportedOperationException,
        )
        coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
    }

    @Test
    fun `updateCipher success should save the cipher locally with a new revision date`() =
        runTest {
            val cipherView = createMockCipherView(number = 1)
            val encryptionContext = createMockEncryptionContext(number = 1)
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns encryptionContext.asSuccess()

            val result = cipherManager.updateCipher(cipherId = "mockId-1", cipherView = cipherView)

            assertEquals(UpdateCipherResult.Success, result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveCipher(
                    userId = USER_ID,
                    cipher = encryptionContext.toStoredCipher(),
                )
            }
        }

    @Test
    fun `updateCipher with encryptCipher failure should return failure and not save`() =
        runTest {
            val cipherView = createMockCipherView(number = 1)
            val error = IllegalStateException()
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns error.asFailure()

            val result = cipherManager.updateCipher(cipherId = "mockId-1", cipherView = cipherView)

            assertEquals(UpdateCipherResult.Error(error = error), result)
            coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
        }

    @Test
    fun `updateCipher when the SDK creates a cipher key should save the migrated cipher`() =
        runTest {
            val cipherView = createMockCipherView(number = 1).copy(key = null)
            val encryptionContext = createMockEncryptionContext(number = 1)
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns encryptionContext.asSuccess()

            val result = cipherManager.updateCipher(cipherId = "mockId-1", cipherView = cipherView)

            assertEquals(UpdateCipherResult.Success, result)
            // Once for the key migration and once for the update itself.
            coVerify(exactly = 2) {
                vaultDiskSource.saveCipher(
                    userId = USER_ID,
                    cipher = encryptionContext.toStoredCipher(),
                )
            }
        }

    @Test
    fun `archiveCipher should save the cipher with an archived date`() = runTest {
        val cipherView = createMockCipherView(number = 1)
        val encryptionContext = createMockEncryptionContext(number = 1)
        coEvery {
            vaultSdkSource.encryptCipher(
                userId = USER_ID,
                cipherView = cipherView.copy(archivedDate = clock.instant()),
            )
        } returns encryptionContext.asSuccess()

        val result = cipherManager.archiveCipher(cipherId = "mockId-1", cipherView = cipherView)

        assertEquals(ArchiveCipherResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = USER_ID,
                cipher = encryptionContext.toStoredCipher(),
            )
        }
        assertEquals(
            true,
            fakeSettingsDiskSource.getIntroducingArchiveActionCardDismissed(userId = USER_ID),
        )
    }

    @Test
    fun `unarchiveCipher should save the cipher without an archived date`() = runTest {
        val cipherView = createMockCipherView(number = 1, isArchived = true)
        val encryptionContext = createMockEncryptionContext(number = 1)
        coEvery {
            vaultSdkSource.encryptCipher(
                userId = USER_ID,
                cipherView = cipherView.copy(archivedDate = null),
            )
        } returns encryptionContext.asSuccess()

        val result = cipherManager.unarchiveCipher(cipherId = "mockId-1", cipherView = cipherView)

        assertEquals(UnarchiveCipherResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = USER_ID,
                cipher = encryptionContext.toStoredCipher(),
            )
        }
    }

    @Test
    fun `softDeleteCipher should save the cipher with a deleted date`() = runTest {
        val cipherView = createMockCipherView(number = 1)
        val encryptionContext = createMockEncryptionContext(number = 1)
        coEvery {
            vaultSdkSource.encryptCipher(
                userId = USER_ID,
                cipherView = cipherView.copy(deletedDate = clock.instant()),
            )
        } returns encryptionContext.asSuccess()

        val result = cipherManager.softDeleteCipher(cipherId = "mockId-1", cipherView = cipherView)

        assertEquals(DeleteCipherResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = USER_ID,
                cipher = encryptionContext.toStoredCipher(),
            )
        }
    }

    @Test
    fun `restoreCipher should save the cipher without a deleted date`() = runTest {
        val cipherView = createMockCipherView(number = 1, isDeleted = true)
        val encryptionContext = createMockEncryptionContext(number = 1)
        coEvery {
            vaultSdkSource.encryptCipher(
                userId = USER_ID,
                cipherView = cipherView.copy(deletedDate = null),
            )
        } returns encryptionContext.asSuccess()

        val result = cipherManager.restoreCipher(cipherId = "mockId-1", cipherView = cipherView)

        assertEquals(RestoreCipherResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = USER_ID,
                cipher = encryptionContext.toStoredCipher(),
            )
        }
    }

    @Test
    fun `hardDeleteCipher should delete the cipher and its attachment files`() = runTest {
        val attachmentFile = storedAttachmentFile(cipherId = "mockId-1", attachmentId = "a1")

        val result = cipherManager.hardDeleteCipher(cipherId = "mockId-1")

        assertEquals(DeleteCipherResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.deleteCipher(userId = USER_ID, cipherId = "mockId-1")
        }
        assertFalse(attachmentFile.exists())
    }

    @Test
    fun `shareCipher should return an unsupported error`() = runTest {
        val result = cipherManager.shareCipher(
            cipherId = "mockId-1",
            organizationId = "mockOrganizationId-1",
            cipherView = createMockCipherView(number = 1),
            collectionIds = listOf("mockId-1"),
        )

        assertTrue((result as ShareCipherResult.Error).error is UnsupportedOperationException)
    }

    @Test
    fun `updateCipherCollections should return an unsupported error`() = runTest {
        val result = cipherManager.updateCipherCollections(
            cipherId = "mockId-1",
            collectionIds = listOf("mockId-1"),
        )

        assertTrue((result as ShareCipherResult.Error).error is UnsupportedOperationException)
    }

    @Test
    fun `getCipher should return the decrypted cipher from disk`() = runTest {
        val cipher = createMockCipher(number = 1)
        val cipherView = createMockCipherView(number = 1)
        coEvery {
            vaultDiskSource.getCipher(userId = USER_ID, cipherId = "mockId-1")
        } returns cipher
        coEvery {
            vaultSdkSource.decryptCipher(userId = USER_ID, cipher = cipher.toEncryptedSdkCipher())
        } returns cipherView.asSuccess()

        val result = cipherManager.getCipher(cipherId = "mockId-1")

        assertEquals(GetCipherResult.Success(cipherView), result)
    }

    @Test
    fun `getCipher should return CipherNotFound when the cipher is not on disk`() = runTest {
        coEvery {
            vaultDiskSource.getCipher(userId = USER_ID, cipherId = "mockId-1")
        } returns null

        val result = cipherManager.getCipher(cipherId = "mockId-1")

        assertEquals(GetCipherResult.CipherNotFound, result)
    }

    @Test
    fun `createAttachment should add the encrypted attachment and save the cipher locally`() =
        runTest {
            val cipherView = createMockCipherView(number = 1)
            val encryptionContext = createMockEncryptionContext(number = 1)
            val fileUri = mockk<Uri>()
            val cacheFile = File(tempDir, "cacheFile").apply { writeText("data") }
            val encryptedAttachment = createMockSdkAttachment(number = 2)
            val decryptedCipherView = createMockCipherView(number = 2)
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns encryptionContext.asSuccess()
            coEvery { fileManager.writeUriToCache(fileUri = fileUri) } returns cacheFile.asSuccess()
            coEvery {
                vaultSdkSource.encryptAttachment(
                    userId = USER_ID,
                    cipher = encryptionContext.cipher,
                    attachmentView = any(),
                    decryptedFilePath = cacheFile.absolutePath,
                    encryptedFilePath = storedAttachmentPath(
                        cipherId = "mockId-1",
                        attachmentId = GENERATED_ID,
                    ),
                )
            } returns encryptedAttachment.asSuccess()
            val expectedCipher = encryptionContext
                .copy(
                    cipher = encryptionContext.cipher.copy(
                        attachments = encryptionContext.cipher.attachments.orEmpty() +
                            encryptedAttachment.copy(id = GENERATED_ID, url = null),
                    ),
                )
                .toStoredCipher()
            coEvery {
                vaultSdkSource.decryptCipher(
                    userId = USER_ID,
                    cipher = expectedCipher.toEncryptedSdkCipher(),
                )
            } returns decryptedCipherView.asSuccess()

            val result = cipherManager.createAttachment(
                cipherId = "mockId-1",
                cipherView = cipherView,
                fileSizeBytes = "1",
                fileName = "file.txt",
                fileUri = fileUri,
            )

            assertEquals(CreateAttachmentResult.Success(cipherView = decryptedCipherView), result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveCipher(userId = USER_ID, cipher = expectedCipher)
                fileManager.delete(cacheFile)
            }
        }

    @Test
    fun `createAttachment with encryptAttachment failure should return error and not save`() =
        runTest {
            val cipherView = createMockCipherView(number = 1)
            val encryptionContext = createMockEncryptionContext(number = 1)
            val fileUri = mockk<Uri>()
            val cacheFile = File(tempDir, "cacheFile").apply { writeText("data") }
            val error = Throwable("fail")
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns encryptionContext.asSuccess()
            coEvery { fileManager.writeUriToCache(fileUri = fileUri) } returns cacheFile.asSuccess()
            coEvery {
                vaultSdkSource.encryptAttachment(
                    userId = USER_ID,
                    cipher = any(),
                    attachmentView = any(),
                    decryptedFilePath = any(),
                    encryptedFilePath = any(),
                )
            } returns error.asFailure()

            val result = cipherManager.createAttachment(
                cipherId = "mockId-1",
                cipherView = cipherView,
                fileSizeBytes = "1",
                fileName = "file.txt",
                fileUri = fileUri,
            )

            assertEquals(CreateAttachmentResult.Error(error = error, message = null), result)
            coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
        }

    @Test
    fun `deleteCipherAttachment should save the cipher without it and delete the file`() =
        runTest {
            val attachment = createMockAttachmentView(number = 1)
            val cipherView = createMockCipherView(number = 1)
            val encryptionContext = createMockEncryptionContext(number = 1)
            val attachmentFile = storedAttachmentFile(
                cipherId = "mockId-1",
                attachmentId = requireNotNull(attachment.id),
            )
            coEvery {
                vaultSdkSource.encryptCipher(
                    userId = USER_ID,
                    cipherView = cipherView.copy(attachments = emptyList()),
                )
            } returns encryptionContext.asSuccess()

            val result = cipherManager.deleteCipherAttachment(
                cipherId = "mockId-1",
                attachmentId = requireNotNull(attachment.id),
                cipherView = cipherView,
            )

            assertEquals(DeleteAttachmentResult.Success, result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveCipher(
                    userId = USER_ID,
                    cipher = encryptionContext.toStoredCipher(),
                )
            }
            assertFalse(attachmentFile.exists())
        }

    @Test
    fun `downloadAttachment when the attachment file is missing should return Failure`() =
        runTest {
            val cipherView = createMockCipherView(number = 1)
            coEvery {
                vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
            } returns createMockEncryptionContext(number = 1).asSuccess()

            val result = cipherManager.downloadAttachment(
                cipherView = cipherView,
                attachmentId = "mockId-1",
            )

            assertTrue(result is DownloadAttachmentResult.Failure)
        }

    @Test
    fun `downloadAttachment should decrypt a cached copy of the stored file`() = runTest {
        val cipherView = createMockCipherView(number = 1)
        val encryptionContext = createMockEncryptionContext(number = 1)
        storedAttachmentFile(cipherId = "mockId-1", attachmentId = "mockId-1")
        val cachedFile = File(tempDir, "cached").apply { writeText("encrypted") }
        val decryptedFile = File(cachedFile.path + "_decrypted")
        coEvery {
            vaultSdkSource.encryptCipher(userId = USER_ID, cipherView = cipherView)
        } returns encryptionContext.asSuccess()
        coEvery { fileManager.streamFileToCache(stream = any()) } returns cachedFile.asSuccess()
        coEvery {
            vaultSdkSource.decryptFile(
                userId = USER_ID,
                cipher = encryptionContext.cipher,
                attachmentView = createMockAttachmentView(number = 1),
                encryptedFilePath = cachedFile.path,
                decryptedFilePath = decryptedFile.path,
            )
        } returns Unit.asSuccess()

        val result = cipherManager.downloadAttachment(
            cipherView = cipherView,
            attachmentId = "mockId-1",
        )

        assertEquals(DownloadAttachmentResult.Success(file = decryptedFile), result)
        coVerify(exactly = 1) { fileManager.delete(cachedFile) }
    }

    private fun CipherView.asNewCipher(): CipherView =
        copy(
            id = GENERATED_ID,
            creationDate = clock.instant(),
            revisionDate = clock.instant(),
        )

    private fun EncryptionContext.toStoredCipher(): SyncResponseJson.Cipher =
        toEncryptedNetworkCipherResponse().copy(
            revisionDate = clock.instant(),
            shouldEdit = true,
            shouldViewPassword = true,
        )

    private fun storedAttachmentPath(cipherId: String, attachmentId: String): String =
        File(tempDir, "attachments/$USER_ID/$cipherId/$attachmentId").absolutePath

    private fun storedAttachmentFile(cipherId: String, attachmentId: String): File =
        File(storedAttachmentPath(cipherId = cipherId, attachmentId = attachmentId)).apply {
            parentFile?.mkdirs()
            writeText("encrypted")
        }
}

private const val USER_ID: String = "mockId-1"
private const val GENERATED_ID: String = "11111111-2222-3333-4444-555555555555"

private val MOCK_PROFILE = AccountJson.Profile(
    userId = USER_ID,
    email = "email",
    isEmailVerified = true,
    name = null,
    stamp = "mockSecurityStamp-1",
    organizationId = null,
    avatarColorHex = null,
    hasPremiumPersonally = false,
    hasPremiumFromOrganization = null,
    forcePasswordResetReason = null,
    kdfType = null,
    kdfIterations = null,
    kdfMemory = null,
    kdfParallelism = null,
    userDecryptionOptions = null,
    isTwoFactorEnabled = false,
    creationDate = Instant.parse("2024-09-13T01:00:00.00Z"),
)

private val MOCK_ACCOUNT = AccountJson(
    profile = MOCK_PROFILE,
    tokens = AccountTokensJson(
        accessToken = "accessToken",
        refreshToken = "refreshToken",
    ),
    settings = AccountJson.Settings(
        environmentUrlData = null,
    ),
)

private val MOCK_USER_STATE = UserStateJson(
    activeUserId = USER_ID,
    accounts = mapOf(USER_ID to MOCK_ACCOUNT),
)
