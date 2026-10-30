package com.x8bit.bitwarden.data.vault.manager

import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.vault.Folder
import com.bitwarden.vault.FolderView
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountTokensJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.model.CreateFolderResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteFolderResult
import com.x8bit.bitwarden.data.vault.repository.model.UpdateFolderResult
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FolderManagerTest {
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val vaultDiskSource = mockk<VaultDiskSource> {
        coEvery { saveFolder(userId = any(), folder = any()) } just runs
        coEvery { saveCipher(userId = any(), cipher = any()) } just runs
        coEvery { deleteFolder(userId = any(), folderId = any()) } just runs
    }
    private val vaultSdkSource = mockk<VaultSdkSource>()

    private val folderManager: FolderManager = FolderManagerImpl(
        authDiskSource = fakeAuthDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        clock = FIXED_CLOCK,
    )

    @BeforeEach
    fun setup() {
        mockkConstructor(NoActiveUserException::class)
        every {
            anyConstructed<NoActiveUserException>() == any<NoActiveUserException>()
        } returns true
        mockkStatic(UUID::randomUUID)
        every { UUID.randomUUID().toString() } returns FOLDER_ID
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(NoActiveUserException::class)
        unmockkStatic(UUID::randomUUID)
    }

    @Test
    fun `createFolder with no active user should return CreateFolderResult failure`() =
        runTest {
            fakeAuthDiskSource.userState = null

            val result = folderManager.createFolder(folderView = mockk())

            assertEquals(CreateFolderResult.Error(error = NoActiveUserException()), result)
        }

    @Test
    fun `createFolder with encryptFolder failure should return failure and not save`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val error = IllegalStateException()
            coEvery {
                vaultSdkSource.encryptFolder(userId = ACTIVE_USER_ID, folder = SAVED_FOLDER_VIEW)
            } returns error.asFailure()

            val result = folderManager.createFolder(folderView = NEW_FOLDER_VIEW)

            assertEquals(CreateFolderResult.Error(error = error), result)
            coVerify(exactly = 0) { vaultDiskSource.saveFolder(userId = any(), folder = any()) }
        }

    @Test
    fun `createFolder success should save the folder locally and return the decrypted folder`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            coEvery {
                vaultSdkSource.encryptFolder(userId = ACTIVE_USER_ID, folder = SAVED_FOLDER_VIEW)
            } returns ENCRYPTED_FOLDER.asSuccess()
            coEvery {
                vaultSdkSource.decryptFolder(userId = ACTIVE_USER_ID, folder = ENCRYPTED_FOLDER)
            } returns SAVED_FOLDER_VIEW.asSuccess()

            val result = folderManager.createFolder(folderView = NEW_FOLDER_VIEW)

            assertEquals(CreateFolderResult.Success(folderView = SAVED_FOLDER_VIEW), result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveFolder(userId = ACTIVE_USER_ID, folder = STORED_FOLDER)
            }
        }

    @Test
    fun `createFolder with decryptFolder failure should save the folder and return failure`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val error = IllegalStateException()
            coEvery {
                vaultSdkSource.encryptFolder(userId = ACTIVE_USER_ID, folder = SAVED_FOLDER_VIEW)
            } returns ENCRYPTED_FOLDER.asSuccess()
            coEvery {
                vaultSdkSource.decryptFolder(userId = ACTIVE_USER_ID, folder = ENCRYPTED_FOLDER)
            } returns error.asFailure()

            val result = folderManager.createFolder(folderView = NEW_FOLDER_VIEW)

            assertEquals(CreateFolderResult.Error(error = error), result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveFolder(userId = ACTIVE_USER_ID, folder = STORED_FOLDER)
            }
        }

    @Test
    fun `deleteFolder with no active user should return DeleteFolderResult failure`() =
        runTest {
            fakeAuthDiskSource.userState = null

            val result = folderManager.deleteFolder(folderId = FOLDER_ID)

            assertEquals(DeleteFolderResult.Error(error = NoActiveUserException()), result)
        }

    @Test
    fun `deleteFolder should remove the folder from ciphers and delete it locally`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        val cipherInFolder = createMockCipher(number = 1).copy(folderId = FOLDER_ID)
        val otherCipher = createMockCipher(number = 2).copy(folderId = "otherFolderId")
        coEvery {
            vaultDiskSource.getCiphers(userId = ACTIVE_USER_ID)
        } returns listOf(cipherInFolder, otherCipher)

        val result = folderManager.deleteFolder(folderId = FOLDER_ID)

        assertEquals(DeleteFolderResult.Success, result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveCipher(
                userId = ACTIVE_USER_ID,
                cipher = cipherInFolder.copy(folderId = null),
            )
            vaultDiskSource.deleteFolder(userId = ACTIVE_USER_ID, folderId = FOLDER_ID)
        }
        coVerify(exactly = 0) {
            vaultDiskSource.saveCipher(userId = ACTIVE_USER_ID, cipher = otherCipher)
        }
    }

    @Test
    fun `updateFolder with no active user should return UpdateFolderResult failure`() =
        runTest {
            fakeAuthDiskSource.userState = null

            val result = folderManager.updateFolder(folderId = FOLDER_ID, folderView = mockk())

            assertEquals(UpdateFolderResult.Error(error = NoActiveUserException()), result)
        }

    @Test
    fun `updateFolder with encryptFolder failure should return failure and not save`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val error = IllegalStateException()
            coEvery {
                vaultSdkSource.encryptFolder(userId = ACTIVE_USER_ID, folder = SAVED_FOLDER_VIEW)
            } returns error.asFailure()

            val result = folderManager.updateFolder(
                folderId = FOLDER_ID,
                folderView = SAVED_FOLDER_VIEW.copy(revisionDate = OLD_DATE),
            )

            assertEquals(UpdateFolderResult.Error(error = error), result)
            coVerify(exactly = 0) { vaultDiskSource.saveFolder(userId = any(), folder = any()) }
        }

    @Test
    fun `updateFolder success should save the folder with a new revision date`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        coEvery {
            vaultSdkSource.encryptFolder(userId = ACTIVE_USER_ID, folder = SAVED_FOLDER_VIEW)
        } returns ENCRYPTED_FOLDER.asSuccess()
        coEvery {
            vaultSdkSource.decryptFolder(userId = ACTIVE_USER_ID, folder = ENCRYPTED_FOLDER)
        } returns SAVED_FOLDER_VIEW.asSuccess()

        val result = folderManager.updateFolder(
            folderId = FOLDER_ID,
            folderView = SAVED_FOLDER_VIEW.copy(revisionDate = OLD_DATE),
        )

        assertEquals(UpdateFolderResult.Success(SAVED_FOLDER_VIEW), result)
        coVerify(exactly = 1) {
            vaultDiskSource.saveFolder(userId = ACTIVE_USER_ID, folder = STORED_FOLDER)
        }
    }
}

private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2023-10-27T12:00:00Z"),
    ZoneOffset.UTC,
)

private const val ACTIVE_USER_ID: String = "mockId-1"

private val MOCK_PROFILE = AccountJson.Profile(
    userId = ACTIVE_USER_ID,
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
    activeUserId = ACTIVE_USER_ID,
    accounts = mapOf(
        ACTIVE_USER_ID to MOCK_ACCOUNT,
    ),
)

private const val FOLDER_ID: String = "11111111-2222-3333-4444-555555555555"

private val OLD_DATE: Instant = Instant.parse("2020-01-01T00:00:00Z")

private val NEW_FOLDER_VIEW = FolderView(
    id = null,
    name = "TestName",
    revisionDate = OLD_DATE,
)

private val SAVED_FOLDER_VIEW = NEW_FOLDER_VIEW.copy(
    id = FOLDER_ID,
    revisionDate = FIXED_CLOCK.instant(),
)

private val ENCRYPTED_FOLDER = Folder(
    id = FOLDER_ID,
    name = "encryptedName",
    revisionDate = FIXED_CLOCK.instant(),
)

private val STORED_FOLDER = SyncResponseJson.Folder(
    id = FOLDER_ID,
    name = "encryptedName",
    revisionDate = FIXED_CLOCK.instant(),
)
