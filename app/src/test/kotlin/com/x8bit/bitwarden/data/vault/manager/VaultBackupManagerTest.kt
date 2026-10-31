package com.x8bit.bitwarden.data.vault.manager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.bitwarden.core.InitUserCryptoRequest
import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.di.CoreModule
import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.data.manager.file.FileManager
import com.bitwarden.network.model.KdfJson
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.MasterPasswordUnlockDataJson
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.UserDecryptionOptionsJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.network.model.createMockFolder
import com.bitwarden.vault.Cipher
import com.bitwarden.vault.Folder
import com.bitwarden.vault.FolderView
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.InitializeCryptoResult
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCipherView
import com.x8bit.bitwarden.data.vault.manager.model.MergeBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

@Suppress("LargeClass")
class VaultBackupManagerTest {

    @TempDir
    lateinit var tempDirectory: File

    private val backupUri: Uri = mockk()
    private var backupBytes = ByteArrayOutputStream()
    private val contentResolver: ContentResolver = mockk {
        every { openOutputStream(backupUri, "wt") } answers {
            ByteArrayOutputStream().also { backupBytes = it }
        }
        every { openInputStream(backupUri) } answers {
            ByteArrayInputStream(backupBytes.toByteArray())
        }
    }
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val vaultDiskSource: VaultDiskSource = mockk {
        coEvery { getFolders(userId = USER_ID) } returns FOLDERS
        coEvery { getCiphers(userId = USER_ID) } returns CIPHERS
        coEvery { saveFolders(userId = any(), folders = any()) } just runs
        coEvery { saveCiphers(userId = any(), ciphers = any()) } just runs
        coEvery { saveFolder(userId = any(), folder = any()) } just runs
        coEvery { saveCipher(userId = any(), cipher = any()) } just runs
        coEvery { deleteVaultData(userId = any()) } just runs
    }

    /**
     * The user key of the vault on the device, and of the vault a backup was made from.
     */
    private var deviceUserKey: ByteArray = USER_KEY_A
    private var backupUserKey: ByteArray = USER_KEY_A
    private var isBackupPasswordCorrect = true
    private val vaultSdkSource: VaultSdkSource = mockk {
        coEvery { getUserEncryptionKey(userId = USER_ID) } answers {
            Result.success(deviceUserKey.toBase64())
        }
        coEvery {
            initializeCrypto(userId = match { it.startsWith("backup-") }, request = any())
        } answers {
            Result.success(
                if (isBackupPasswordCorrect) {
                    InitializeCryptoResult.Success
                } else {
                    InitializeCryptoResult.AuthenticationError(error = Throwable())
                },
            )
        }
        coEvery { getUserEncryptionKey(userId = match { it.startsWith("backup-") }) } answers {
            Result.success(backupUserKey.toBase64())
        }
        every { clearCrypto(userId = any()) } just runs
        coEvery { decryptCipher(userId = USER_ID, cipher = any()) } answers {
            val cipher = secondArg<Cipher>()
            Result.success(createMockCipherView(number = VIEW_NUMBERS.getValue(cipher.id!!)))
        }
        coEvery { decryptFolder(userId = USER_ID, folder = any()) } answers {
            val folder = secondArg<Folder>()
            Result.success(
                FolderView(
                    id = folder.id,
                    name = FOLDER_NAMES[folder.id] ?: folder.name,
                    revisionDate = folder.revisionDate,
                ),
            )
        }
    }
    private var isVaultUnlocked = true
    private val vaultRepository: VaultRepository = mockk {
        every { isVaultUnlocked(userId = USER_ID) } answers { isVaultUnlocked }
    }
    private val userStateManager: UserStateManager = mockk {
        coEvery { userStateTransaction<VaultUnlockResult>(any()) } coAnswers {
            firstArg<suspend () -> VaultUnlockResult>().invoke()
        }
    }
    private val settingsRepository: SettingsRepository = mockk {
        every { hasUserLoggedInOrCreatedAccount = any() } just runs
    }

    private lateinit var filesDirectory: File
    private lateinit var vaultBackupManager: VaultBackupManager

    @BeforeEach
    fun setUp() {
        filesDirectory = File(tempDirectory, "files").apply { mkdirs() }
        val cacheDirectory = File(tempDirectory, "cache").apply { mkdirs() }
        val context: Context = mockk {
            every { this@mockk.contentResolver } returns this@VaultBackupManagerTest.contentResolver
            every { cacheDir } returns cacheDirectory
        }
        val fileManager: FileManager = mockk {
            every { this@mockk.filesDirectory } returns this@VaultBackupManagerTest.filesDirectory.path
        }
        vaultBackupManager = VaultBackupManagerImpl(
            context = context,
            authDiskSource = fakeAuthDiskSource,
            vaultDiskSource = vaultDiskSource,
            vaultSdkSource = vaultSdkSource,
            vaultRepository = vaultRepository,
            userStateManager = userStateManager,
            settingsRepository = settingsRepository,
            fileManager = fileManager,
            json = CoreModule.providesJson(buildInfoManager = mockk(relaxed = true)),
            clock = FIXED_CLOCK,
            dispatcherManager = FakeDispatcherManager(),
        )
    }

    @Test
    fun `writeBackup without a vault returns an error`() = runTest {
        val result = vaultBackupManager.writeBackup(uri = backupUri)

        assertTrue(result is WriteBackupResult.Error)
    }

    @Test
    fun `writeBackup encrypts the whole file`() = runTest {
        storeVault()

        assertEquals(
            WriteBackupResult.Success(itemCount = CIPHERS.size),
            vaultBackupManager.writeBackup(uri = backupUri),
        )

        val content = backupBytes.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(content.startsWith("SECRETDOOR"))
        assertFalse(content.contains("ciphers.json"))
        assertFalse(content.contains("mockName-1"))
        assertFalse(content.contains(HINT))
    }

    @Test
    fun `writeBackup while the vault is locked uses the key remembered while unlocked`() =
        runTest {
            storeVault()
            isVaultUnlocked = false

            assertTrue(vaultBackupManager.writeBackup(uri = backupUri) is WriteBackupResult.Error)

            isVaultUnlocked = true
            vaultBackupManager.writeBackup(uri = backupUri)
            isVaultUnlocked = false

            assertEquals(
                WriteBackupResult.Success(itemCount = CIPHERS.size),
                vaultBackupManager.writeBackup(uri = backupUri),
            )
            assertTrue(readBackup().isEncrypted)
        }

    @Test
    fun `readBackup returns when the backup was made`() = runTest {
        storeVault()
        vaultBackupManager.writeBackup(uri = backupUri)

        val backup = readBackup()

        assertEquals(FIXED_CLOCK.instant(), backup.createdAt)
        assertTrue(backup.isEncrypted)
    }

    @Test
    fun `readBackup of a file that is not a backup returns an error`() = runTest {
        backupBytes.write("not a backup".toByteArray())

        val result = vaultBackupManager.readBackup(uri = backupUri)

        assertTrue(result is ReadBackupResult.Error)
    }

    @Test
    fun `restoreBackup with the right password stores the vault and unlocks it`() = runTest {
        val backup = createBackup()
        fakeAuthDiskSource.userState = null
        coEvery {
            vaultRepository.unlockVaultWithMasterPassword(masterPassword = PASSWORD)
        } returns VaultUnlockResult.Success

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertEquals(RestoreBackupResult.Success(itemCount = CIPHERS.size), result)
        assertEquals(
            UserStateJson(activeUserId = USER_ID, accounts = mapOf(USER_ID to ACCOUNT)),
            fakeAuthDiskSource.userState,
        )
        assertEquals(ACCOUNT_KEYS, fakeAuthDiskSource.getAccountCryptographicState(USER_ID))
        // The hint is not part of a backup.
        assertNull(fakeAuthDiskSource.getMasterPasswordHint(USER_ID))
        assertEquals(
            ATTACHMENT_CONTENT,
            File(filesDirectory, "attachments/$USER_ID/$CIPHER_ID/$ATTACHMENT_ID").readText(),
        )
        coVerify {
            vaultDiskSource.saveFolders(userId = USER_ID, folders = FOLDERS)
            vaultDiskSource.saveCiphers(userId = USER_ID, ciphers = CIPHERS)
        }
        verify { settingsRepository.hasUserLoggedInOrCreatedAccount = true }
    }

    @Test
    fun `restoreBackup with the wrong password stores nothing`() = runTest {
        val backup = createBackup()
        fakeAuthDiskSource.userState = null
        isBackupPasswordCorrect = false

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertEquals(RestoreBackupResult.WrongPassword, result)
        assertNull(fakeAuthDiskSource.userState)
        coVerify(exactly = 0) { vaultDiskSource.saveCiphers(userId = any(), ciphers = any()) }
    }

    @Test
    fun `restoreBackup removes everything it stored when the vault does not unlock`() = runTest {
        val backup = createBackup()
        fakeAuthDiskSource.userState = null
        coEvery {
            vaultRepository.unlockVaultWithMasterPassword(masterPassword = PASSWORD)
        } returns VaultUnlockResult.AuthenticationError(error = null)

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertEquals(RestoreBackupResult.WrongPassword, result)
        assertNull(fakeAuthDiskSource.userState)
        assertNull(fakeAuthDiskSource.getAccountCryptographicState(USER_ID))
        assertFalse(File(filesDirectory, "attachments/$USER_ID").exists())
        coVerify { vaultDiskSource.deleteVaultData(userId = USER_ID) }
    }

    @Test
    fun `restoreBackup of a modified file returns an error`() = runTest {
        storeVault()
        vaultBackupManager.writeBackup(uri = backupUri)
        val bytes = backupBytes.toByteArray()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20] + 1).toByte()
        backupBytes = ByteArrayOutputStream().apply { write(bytes) }
        val backup = readBackup()
        fakeAuthDiskSource.userState = null

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertTrue(result is RestoreBackupResult.Error)
        coVerify(exactly = 0) { vaultDiskSource.saveCiphers(userId = any(), ciphers = any()) }
    }

    @Test
    fun `restoreBackup when a vault already exists returns an error`() = runTest {
        val backup = createBackup()

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertTrue(result is RestoreBackupResult.Error)
        coVerify(exactly = 0) { vaultDiskSource.saveCiphers(userId = any(), ciphers = any()) }
    }

    @Test
    fun `mergeBackup of this vault adds new items and replaces older copies`() = runTest {
        val backup = createBackup()
        val olderCipher = CIPHERS[0].copy(revisionDate = OLDER_INSTANT)
        coEvery { vaultDiskSource.getCiphers(userId = USER_ID) } returns listOf(olderCipher)

        val result = vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

        assertEquals(
            MergeBackupResult.Success(addedCount = 1, updatedCount = 1, skippedCount = 0),
            result,
        )
        coVerify {
            vaultDiskSource.saveCipher(userId = USER_ID, cipher = CIPHERS[0])
            vaultDiskSource.saveCipher(userId = USER_ID, cipher = CIPHERS[1])
        }
        // The backup's folder has the same name as the existing one, so no folder is added.
        coVerify(exactly = 0) { vaultDiskSource.saveFolder(userId = any(), folder = any()) }
        assertEquals(
            ATTACHMENT_CONTENT,
            File(filesDirectory, "attachments/$USER_ID/$CIPHER_ID/$ATTACHMENT_ID").readText(),
        )
    }

    @Test
    fun `mergeBackup skips items that are newer in the vault or have the same content`() =
        runTest {
            val backup = createBackup()
            val newerCipher = CIPHERS[0].copy(revisionDate = NEWER_INSTANT)
            val sameContentCipher = CIPHERS[1].copy(id = "otherId-2")
            coEvery { vaultDiskSource.getCiphers(userId = USER_ID) } returns
                listOf(newerCipher, sameContentCipher)

            val result = vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

            assertEquals(
                MergeBackupResult.Success(addedCount = 0, updatedCount = 0, skippedCount = 2),
                result,
            )
            coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
        }

    @Test
    fun `mergeBackup of another vault re-encrypts its items and folders for this vault`() =
        runTest {
            val backupFolder = createMockFolder(
                number = 3,
                name = encString(plainText = "Work", userKey = USER_KEY_B),
            )
            val backupCipher = createMockCipher(
                number = 3,
                key = null,
                folderId = backupFolder.id,
                name = encString(plainText = "Bank", userKey = USER_KEY_B),
            )
            deviceUserKey = USER_KEY_B
            val backup = createBackup(folders = listOf(backupFolder), ciphers = listOf(backupCipher))
            deviceUserKey = USER_KEY_A
            backupUserKey = USER_KEY_B
            coEvery { vaultDiskSource.getFolders(userId = USER_ID) } returns emptyList()
            coEvery { vaultDiskSource.getCiphers(userId = USER_ID) } returns emptyList()
            val savedCipher = slot<SyncResponseJson.Cipher>()
            val savedFolder = slot<SyncResponseJson.Folder>()
            coEvery {
                vaultDiskSource.saveCipher(userId = USER_ID, cipher = capture(savedCipher))
            } just runs
            coEvery {
                vaultDiskSource.saveFolder(userId = USER_ID, folder = capture(savedFolder))
            } just runs

            val result = vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

            assertEquals(
                MergeBackupResult.Success(addedCount = 1, updatedCount = 0, skippedCount = 0),
                result,
            )
            assertEquals("Work", decryptEncString(savedFolder.captured.name!!, USER_KEY_A))
            assertEquals("Bank", decryptEncString(savedCipher.captured.name!!, USER_KEY_A))
            assertEquals(savedFolder.captured.id, savedCipher.captured.folderId)
            assertEquals(backupCipher.id, savedCipher.captured.id)
        }

    @Test
    fun `mergeBackup unlocks the backup with a UUID as the SDK requires`() = runTest {
        val backup = createBackup()
        val request = slot<InitUserCryptoRequest>()
        coEvery {
            vaultSdkSource.initializeCrypto(
                userId = match { it.startsWith("backup-") },
                request = capture(request),
            )
        } returns Result.success(InitializeCryptoResult.Success)

        vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

        // Throws if the user ID is not a UUID.
        UUID.fromString(request.captured.userId)
    }

    @Test
    fun `mergeBackup with the wrong password imports nothing`() = runTest {
        val backup = createBackup()
        isBackupPasswordCorrect = false

        val result = vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

        assertEquals(MergeBackupResult.WrongPassword, result)
        coVerify(exactly = 0) { vaultDiskSource.saveCipher(userId = any(), cipher = any()) }
    }

    @Test
    fun `mergeBackup while the vault is locked returns an error`() = runTest {
        val backup = createBackup()
        isVaultUnlocked = false

        val result = vaultBackupManager.mergeBackup(backup = backup, masterPassword = PASSWORD)

        assertTrue(result is MergeBackupResult.Error)
    }

    private suspend fun createBackup(
        folders: List<SyncResponseJson.Folder> = FOLDERS,
        ciphers: List<SyncResponseJson.Cipher> = CIPHERS,
    ): VaultBackup {
        storeVault()
        writeAttachment(directory = filesDirectory, userId = USER_ID)
        coEvery { vaultDiskSource.getFolders(userId = USER_ID) } returns folders
        coEvery { vaultDiskSource.getCiphers(userId = USER_ID) } returns ciphers
        vaultBackupManager.writeBackup(uri = backupUri)
        // Remove the attachment so a restore has to bring it back.
        File(filesDirectory, "attachments").deleteRecursively()
        coEvery { vaultDiskSource.getFolders(userId = USER_ID) } returns FOLDERS
        coEvery { vaultDiskSource.getCiphers(userId = USER_ID) } returns CIPHERS
        return readBackup()
    }

    private suspend fun readBackup(): VaultBackup =
        (vaultBackupManager.readBackup(uri = backupUri) as ReadBackupResult.Success).backup

    private fun storeVault() {
        fakeAuthDiskSource.userState = UserStateJson(
            activeUserId = USER_ID,
            accounts = mapOf(USER_ID to ACCOUNT),
        )
        fakeAuthDiskSource.storeAccountCryptographicState(
            userId = USER_ID,
            accountCryptographicState = ACCOUNT_KEYS,
        )
        fakeAuthDiskSource.storeMasterPasswordHint(userId = USER_ID, hint = HINT)
    }

    private fun writeAttachment(directory: File, userId: String) {
        File(directory, "attachments/$userId/$CIPHER_ID/$ATTACHMENT_ID").apply {
            parentFile?.mkdirs()
            writeText(ATTACHMENT_CONTENT)
        }
    }
}

private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

private const val USER_ID = "userId"
private const val PASSWORD = "password"
private const val HINT = "my secret hint"
private const val CIPHER_ID = "mockId-1"
private const val ATTACHMENT_ID = "attachmentId"
private const val ATTACHMENT_CONTENT = "encrypted attachment"
private val OLDER_INSTANT: Instant = Instant.parse("2000-01-01T00:00:00Z")
private val NEWER_INSTANT: Instant = Instant.parse("2099-01-01T00:00:00Z")

/**
 * Which mock item view each stored item decrypts to.
 */
private val VIEW_NUMBERS = mapOf("mockId-1" to 1, "mockId-2" to 2, "otherId-2" to 2, "mockId-3" to 3)

/**
 * Decrypted folder names; folders not listed here keep their stored name.
 */
private val FOLDER_NAMES = mapOf("mockId-3" to "Work")
private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2026-10-30T12:00:00Z"),
    ZoneOffset.UTC,
)
private val ACCOUNT_KEYS = WrappedAccountCryptographicState.V1(privateKey = "privateKey")
private val FOLDERS = listOf(createMockFolder(number = 1))
private val CIPHERS = listOf(
    createMockCipher(number = 1, folderId = "mockId-1"),
    createMockCipher(number = 2, folderId = null),
)
private val ACCOUNT = AccountJson(
    profile = AccountJson.Profile(
        userId = USER_ID,
        email = "$USER_ID@secretdoor.local",
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
                salt = "$USER_ID@secretdoor.local",
                kdf = KdfJson(
                    kdfType = KdfTypeJson.PBKDF2_SHA256,
                    iterations = 600000,
                    memory = null,
                    parallelism = null,
                ),
                masterKeyWrappedUserKey = "masterKeyWrappedUserKey",
            ),
            trustedDeviceUserDecryptionOptions = null,
            keyConnectorUserDecryptionOptions = null,
        ),
        isTwoFactorEnabled = false,
        creationDate = Instant.parse("2026-10-29T12:00:00Z"),
    ),
    settings = AccountJson.Settings(environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US),
)
