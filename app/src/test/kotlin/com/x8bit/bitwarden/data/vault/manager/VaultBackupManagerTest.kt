package com.x8bit.bitwarden.data.vault.manager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.di.CoreModule
import com.bitwarden.data.datasource.disk.model.EnvironmentUrlDataJson
import com.bitwarden.data.manager.file.FileManager
import com.bitwarden.network.model.KdfTypeJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.network.model.createMockFolder
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
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

class VaultBackupManagerTest {

    @TempDir
    lateinit var tempDirectory: File

    private val backupUri: Uri = mockk()
    private val backupBytes = ByteArrayOutputStream()
    private val contentResolver: ContentResolver = mockk {
        every { openOutputStream(backupUri, "wt") } returns backupBytes
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
        coEvery { deleteVaultData(userId = any()) } just runs
    }
    private val vaultRepository: VaultRepository = mockk()
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
    fun `readBackup returns everything writeBackup saved`() = runTest {
        storeVault()
        writeAttachment(directory = filesDirectory, userId = USER_ID)

        assertEquals(
            WriteBackupResult.Success(itemCount = CIPHERS.size),
            vaultBackupManager.writeBackup(uri = backupUri),
        )
        val result = vaultBackupManager.readBackup(uri = backupUri)

        val backup = (result as ReadBackupResult.Success).backup
        assertEquals(FIXED_CLOCK.instant(), backup.createdAt)
        assertEquals(ACCOUNT, backup.account)
        assertEquals(ACCOUNT_KEYS, backup.accountCryptographicState)
        assertEquals(HINT, backup.masterPasswordHint)
        assertEquals(FOLDERS, backup.folders)
        assertEquals(CIPHERS, backup.ciphers)
        assertEquals(
            ATTACHMENT_CONTENT,
            File(backup.attachmentsDirectory, "$CIPHER_ID/$ATTACHMENT_ID").readText(),
        )
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
        assertEquals(HINT, fakeAuthDiskSource.getMasterPasswordHint(USER_ID))
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
    fun `restoreBackup with the wrong password removes everything it stored`() = runTest {
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
    fun `restoreBackup when a vault already exists returns an error`() = runTest {
        val backup = createBackup()

        val result = vaultBackupManager.restoreBackup(backup = backup, masterPassword = PASSWORD)

        assertTrue(result is RestoreBackupResult.Error)
        coVerify(exactly = 0) { vaultDiskSource.saveCiphers(userId = any(), ciphers = any()) }
    }

    private suspend fun createBackup() =
        run {
            storeVault()
            writeAttachment(directory = filesDirectory, userId = USER_ID)
            vaultBackupManager.writeBackup(uri = backupUri)
            // Remove the attachment so a restore has to bring it back.
            File(filesDirectory, "attachments").deleteRecursively()
            (vaultBackupManager.readBackup(uri = backupUri) as ReadBackupResult.Success).backup
        }

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

private const val USER_ID = "userId"
private const val PASSWORD = "password"
private const val HINT = "hint"
private const val CIPHER_ID = "mockId-1"
private const val ATTACHMENT_ID = "attachmentId"
private const val ATTACHMENT_CONTENT = "encrypted attachment"
private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2026-10-30T12:00:00Z"),
    ZoneOffset.UTC,
)
private val ACCOUNT_KEYS = WrappedAccountCryptographicState.V1(privateKey = "privateKey")
private val FOLDERS = listOf(createMockFolder(number = 1))
private val CIPHERS = listOf(createMockCipher(number = 1), createMockCipher(number = 2))
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
        userDecryptionOptions = null,
        isTwoFactorEnabled = false,
        creationDate = Instant.parse("2026-10-29T12:00:00Z"),
    ),
    settings = AccountJson.Settings(environmentUrlData = EnvironmentUrlDataJson.DEFAULT_US),
)
