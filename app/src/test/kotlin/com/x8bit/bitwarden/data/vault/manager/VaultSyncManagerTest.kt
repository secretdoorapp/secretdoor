package com.x8bit.bitwarden.data.vault.manager

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.bitwarden.collections.CollectionView
import com.bitwarden.core.data.manager.dispatcher.FakeDispatcherManager
import com.bitwarden.core.data.repository.error.MissingPropertyException
import com.bitwarden.core.data.repository.model.DataState
import com.bitwarden.core.data.repository.util.bufferedMutableSharedFlow
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.network.model.createMockCipher
import com.bitwarden.network.model.createMockCollection
import com.bitwarden.network.model.createMockDomains
import com.bitwarden.network.model.createMockFolder
import com.bitwarden.network.service.SyncService
import com.bitwarden.vault.DecryptCipherListResult
import com.bitwarden.vault.FolderView
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountTokensJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.UserStateJson
import com.x8bit.bitwarden.data.auth.datasource.disk.util.FakeAuthDiskSource
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.repository.model.LogoutReason
import com.x8bit.bitwarden.data.platform.datasource.disk.SettingsDiskSource
import com.x8bit.bitwarden.data.platform.error.NoActiveUserException
import com.x8bit.bitwarden.data.platform.manager.DatabaseSchemeManager
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockCollectionView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockDecryptCipherListResult
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockFolderView
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkCipher
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkCollection
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkFolder
import com.x8bit.bitwarden.data.vault.manager.model.SyncVaultDataResult
import com.x8bit.bitwarden.data.vault.repository.model.DomainsData
import com.x8bit.bitwarden.data.vault.repository.model.VaultData
import com.x8bit.bitwarden.data.vault.repository.model.VaultUnlockData
import com.x8bit.bitwarden.data.vault.repository.model.createMockDomainsData
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkCipherList
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkCollectionList
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkFolderList
import io.mockk.awaits
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Suppress("LargeClass")
class VaultSyncManagerTest {

    private val clock: Clock = Clock.fixed(
        Instant.parse("2023-10-27T12:00:00Z"),
        ZoneOffset.UTC,
    )
    private val syncService: SyncService = mockk {
        coEvery {
            getAccountRevisionDateMillis()
        } returns clock.instant().plus(1, ChronoUnit.MINUTES).toEpochMilli().asSuccess()
    }
    private val fakeAuthDiskSource = FakeAuthDiskSource()
    private val settingsDiskSource = mockk<SettingsDiskSource> {
        every { getLastSyncTime(userId = any()) } returns clock.instant()
        every { storeLastSyncTime(userId = any(), lastSyncTime = any()) } just runs
    }
    private val mutableGetCiphersFlow: MutableStateFlow<List<SyncResponseJson.Cipher>> =
        MutableStateFlow(listOf(createMockCipher(1)))
    private val vaultDiskSource: VaultDiskSource = mockk {
        coEvery { resyncVaultData(any()) } just runs
        every { getCiphersFlow(any()) } returns mutableGetCiphersFlow
    }
    private val vaultSdkSource: VaultSdkSource = mockk {
        every { clearCrypto(userId = any()) } just runs
        coEvery {
            reinitializeCrypto(userId = any(), request = any())
        } returns Unit.asSuccess()
    }
    private val mutableVaultStateFlow = MutableStateFlow<List<VaultUnlockData>>(emptyList())
    private val mutableUnlockedUserIdsStateFlow = MutableStateFlow<Set<String>>(emptySet())
    private val vaultLockManager: VaultLockManager = mockk {
        every { vaultUnlockDataStateFlow } returns mutableVaultStateFlow
        every { isVaultUnlocked(userId = any()) } answers { call ->
            call.invocation.args.first() in mutableUnlockedUserIdsStateFlow.value
        }
        every { isVaultUnlocking(userId = any()) } returns false
        every { lockVault(userId = any(), isUserInitiated = any()) } just runs
        every { lockVaultForCurrentUser(isUserInitiated = any()) } just runs
        coEvery { waitUntilUnlocked(userId = any()) } coAnswers { call ->
            mutableUnlockedUserIdsStateFlow.first { call.invocation.args.first() in it }
        }
    }
    private val userLogoutManager: UserLogoutManager = mockk {
        every { logout(userId = any(), reason = LogoutReason.SecurityStamp) } just runs
    }
    private val userStateManager: UserStateManager = mockk {
        val blockSlot = slot<suspend () -> SyncVaultDataResult>()
        coEvery { userStateTransaction(capture(blockSlot)) } coAnswers { blockSlot.captured() }
    }
    private val mutableDatabaseSchemeChangeFlow = bufferedMutableSharedFlow<Unit>()
    private val databaseSchemeManager: DatabaseSchemeManager = mockk {
        every { databaseSchemeChangeFlow } returns mutableDatabaseSchemeChangeFlow
    }

    private val vaultSyncManager: VaultSyncManager = VaultSyncManagerImpl(
        syncService = syncService,
        settingsDiskSource = settingsDiskSource,
        authDiskSource = fakeAuthDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        userLogoutManager = userLogoutManager,
        userStateManager = userStateManager,
        vaultLockManager = vaultLockManager,
        clock = clock,
        databaseSchemeManager = databaseSchemeManager,
        dispatcherManager = FakeDispatcherManager(),
    )

    @BeforeEach
    fun setup() {
        mockkConstructor(NoActiveUserException::class, MissingPropertyException::class)
        every {
            anyConstructed<NoActiveUserException>() == any<NoActiveUserException>()
        } returns true
        every {
            anyConstructed<MissingPropertyException>() == any<MissingPropertyException>()
        } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(NoActiveUserException::class, MissingPropertyException::class)
    }

    @Test
    fun `userSwitchingChangesFlow should cancel any pending sync call`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        coEvery { vaultDiskSource.resyncVaultData(any()) } just awaits

        vaultSyncManager.sync()
        vaultSyncManager.sync()
        coVerify(exactly = 1) {
            // Despite being called twice, we only allow 1 sync
            vaultDiskSource.resyncVaultData(userId = MOCK_USER_STATE.activeUserId)
        }

        fakeAuthDiskSource.userState = UserStateJson(
            activeUserId = "mockId-2",
            accounts = mapOf("mockId-2" to mockk()),
        )
        vaultSyncManager.sync()
        coVerify(exactly = 1) {
            // A second sync should have happened now since it was cancelled by the userState change
            vaultDiskSource.resyncVaultData(userId = "mockId-2")
        }
    }

    @Test
    fun `userSwitchingChangesFlow should clear unlocked data`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        val userId = "mockId-1"
        coEvery {
            vaultSdkSource.decryptCipherListWithFailures(
                userId = userId,
                cipherList = listOf(createMockSdkCipher(number = 1, clock = clock)),
            )
        } returns createMockDecryptCipherListResult(number = 1).asSuccess()
        coEvery {
            vaultSdkSource.decryptFolderList(
                userId = userId,
                folderList = listOf(createMockSdkFolder(number = 1)),
            )
        } returns listOf(createMockFolderView(number = 1)).asSuccess()
        coEvery {
            vaultSdkSource.decryptCollectionList(
                userId = userId,
                collectionList = listOf(createMockSdkCollection(number = 1)),
            )
        } returns listOf(createMockCollectionView(number = 1)).asSuccess()
        val ciphersFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Cipher>>()
        val collectionsFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Collection>>()
        val foldersFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Folder>>()
        val domainsFlow = bufferedMutableSharedFlow<SyncResponseJson.Domains>()
        setupVaultDiskSourceFlows(
            ciphersFlow = ciphersFlow,
            collectionsFlow = collectionsFlow,
            foldersFlow = foldersFlow,
            domainsFlow = domainsFlow,
        )

        turbineScope {
            val ciphersStateFlow = vaultSyncManager
                .decryptCipherListResultStateFlow
                .testIn(backgroundScope)
            val collectionsStateFlow = vaultSyncManager.collectionsStateFlow.testIn(backgroundScope)
            val foldersStateFlow = vaultSyncManager.foldersStateFlow.testIn(backgroundScope)
            val domainsStateFlow = vaultSyncManager.domainsStateFlow.testIn(backgroundScope)

            assertEquals(DataState.Loading, ciphersStateFlow.awaitItem())
            assertEquals(DataState.Loading, collectionsStateFlow.awaitItem())
            assertEquals(DataState.Loading, foldersStateFlow.awaitItem())
            assertEquals(DataState.Loading, domainsStateFlow.awaitItem())

            ciphersFlow.tryEmit(listOf(createMockCipher(number = 1)))
            collectionsFlow.tryEmit(listOf(createMockCollection(number = 1)))
            foldersFlow.tryEmit(listOf(createMockFolder(number = 1)))
            domainsFlow.tryEmit(createMockDomains(number = 1))

            // No events received until unlocked
            ciphersStateFlow.expectNoEvents()
            collectionsStateFlow.expectNoEvents()
            foldersStateFlow.expectNoEvents()
            // Domains does not care about being unlocked
            assertEquals(
                DataState.Loaded(createMockDomainsData(number = 1)),
                domainsStateFlow.awaitItem(),
            )

            setVaultToUnlocked(userId = userId)

            ciphersFlow.tryEmit(listOf(createMockCipher(number = 1)))
            collectionsFlow.tryEmit(listOf(createMockCollection(number = 1)))
            foldersFlow.tryEmit(listOf(createMockFolder(number = 1)))
            domainsFlow.tryEmit(createMockDomains(number = 1))

            assertEquals(
                DataState.Loaded(createMockDecryptCipherListResult(number = 1)),
                ciphersStateFlow.awaitItem(),
            )
            assertEquals(
                DataState.Loaded(listOf(createMockCollectionView(number = 1))),
                collectionsStateFlow.awaitItem(),
            )
            assertEquals(
                DataState.Loaded(listOf(createMockFolderView(number = 1))),
                foldersStateFlow.awaitItem(),
            )
            // Domain data has not changed
            domainsStateFlow.expectNoEvents()

            fakeAuthDiskSource.userState = null

            assertEquals(DataState.Loading, ciphersStateFlow.awaitItem())
            assertEquals(DataState.Loading, collectionsStateFlow.awaitItem())
            assertEquals(DataState.Loading, foldersStateFlow.awaitItem())
            assertEquals(DataState.Loading, domainsStateFlow.awaitItem())
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `mutableVaultStateFlow should clear unlocked data when it does not contain the active user`() =
        runTest {
            fakeAuthDiskSource.userState = UserStateJson(
                activeUserId = "mockId-1",
                accounts = mapOf(
                    "mockId-1" to MOCK_ACCOUNT,
                    "mockId-2" to mockk(),
                ),
            )
            mutableVaultStateFlow.value = listOf(
                VaultUnlockData(userId = "mockId-1", status = VaultUnlockData.Status.UNLOCKING),
                VaultUnlockData(userId = "mockId-2", status = VaultUnlockData.Status.UNLOCKED),
            )
            val userId = "mockId-1"
            coEvery {
                vaultSdkSource.decryptCipherListWithFailures(
                    userId = userId,
                    cipherList = listOf(createMockSdkCipher(number = 1, clock = clock)),
                )
            } returns createMockDecryptCipherListResult(number = 1).asSuccess()
            coEvery {
                vaultSdkSource.decryptFolderList(
                    userId = userId,
                    folderList = listOf(createMockSdkFolder(number = 1)),
                )
            } returns listOf(createMockFolderView(number = 1)).asSuccess()
            coEvery {
                vaultSdkSource.decryptCollectionList(
                    userId = userId,
                    collectionList = listOf(createMockSdkCollection(1)),
                )
            } returns listOf(createMockCollectionView(number = 1)).asSuccess()
            val ciphersFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Cipher>>()
            val collectionsFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Collection>>()
            val foldersFlow = bufferedMutableSharedFlow<List<SyncResponseJson.Folder>>()
            val domainsFlow = bufferedMutableSharedFlow<SyncResponseJson.Domains>()
            setupVaultDiskSourceFlows(
                ciphersFlow = ciphersFlow,
                collectionsFlow = collectionsFlow,
                foldersFlow = foldersFlow,
                domainsFlow = domainsFlow,
            )

            turbineScope {
                val ciphersStateFlow = vaultSyncManager
                    .decryptCipherListResultStateFlow
                    .testIn(backgroundScope)
                val collectionsStateFlow = vaultSyncManager
                    .collectionsStateFlow
                    .testIn(backgroundScope)
                val foldersStateFlow = vaultSyncManager.foldersStateFlow.testIn(backgroundScope)
                val domainsStateFlow = vaultSyncManager.domainsStateFlow.testIn(backgroundScope)

                assertEquals(DataState.Loading, ciphersStateFlow.awaitItem())
                assertEquals(DataState.Loading, collectionsStateFlow.awaitItem())
                assertEquals(DataState.Loading, foldersStateFlow.awaitItem())
                assertEquals(DataState.Loading, domainsStateFlow.awaitItem())

                ciphersFlow.tryEmit(listOf(createMockCipher(number = 1)))
                collectionsFlow.tryEmit(listOf(createMockCollection(number = 1)))
                foldersFlow.tryEmit(listOf(createMockFolder(number = 1)))
                domainsFlow.tryEmit(createMockDomains(number = 1))

                // No events received until unlocked
                ciphersStateFlow.expectNoEvents()
                collectionsStateFlow.expectNoEvents()
                foldersStateFlow.expectNoEvents()
                // Domains does not care about being unlocked
                assertEquals(
                    DataState.Loaded(createMockDomainsData(number = 1)),
                    domainsStateFlow.awaitItem(),
                )
                setVaultToUnlocked(userId = userId)

                assertEquals(
                    DataState.Loaded(createMockDecryptCipherListResult(number = 1)),
                    ciphersStateFlow.awaitItem(),
                )
                assertEquals(
                    DataState.Loaded(listOf(createMockCollectionView(number = 1))),
                    collectionsStateFlow.awaitItem(),
                )
                assertEquals(
                    DataState.Loaded(listOf(createMockFolderView(number = 1))),
                    foldersStateFlow.awaitItem(),
                )
                // Domain data has not changed
                domainsStateFlow.expectNoEvents()

                mutableVaultStateFlow.value = listOf(
                    VaultUnlockData(userId = "mockId-2", status = VaultUnlockData.Status.UNLOCKED),
                )

                assertEquals(DataState.Loading, ciphersStateFlow.awaitItem())
                assertEquals(DataState.Loading, collectionsStateFlow.awaitItem())
                assertEquals(DataState.Loading, foldersStateFlow.awaitItem())
                domainsStateFlow.expectNoEvents()
            }
        }

    @Test
    fun `ciphersStateFlow should emit decrypted list of ciphers when decryptCipherList succeeds`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val userId = "mockId-1"
            val mockCipherList = listOf(createMockCipher(number = 1))
            val mockEncryptedCipherList = mockCipherList.toEncryptedSdkCipherList()
            val mockDecryptCipherListResult = createMockDecryptCipherListResult(number = 1)
            val mutableCiphersStateFlow =
                bufferedMutableSharedFlow<List<SyncResponseJson.Cipher>>(replay = 1)
            setupVaultDiskSourceFlows(ciphersFlow = mutableCiphersStateFlow)
            coEvery {
                vaultSdkSource.decryptCipherListWithFailures(
                    userId = userId,
                    cipherList = mockEncryptedCipherList,
                )
            } returns mockDecryptCipherListResult.asSuccess()

            vaultSyncManager
                .decryptCipherListResultStateFlow
                .test {
                    assertEquals(DataState.Loading, awaitItem())
                    mutableCiphersStateFlow.tryEmit(mockCipherList)

                    // No additional emissions until vault is unlocked
                    expectNoEvents()
                    setVaultToUnlocked(userId = userId)

                    assertEquals(DataState.Loaded(mockDecryptCipherListResult), awaitItem())
                }
        }

    @Test
    fun `ciphersStateFlow should emit an error when decryptCipherList fails`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        val userId = "mockId-1"
        val throwable = Throwable("Fail")
        val mockCipherList = listOf(createMockCipher(number = 1))
        val mockEncryptedCipherList = mockCipherList.toEncryptedSdkCipherList()
        val mutableCiphersStateFlow =
            bufferedMutableSharedFlow<List<SyncResponseJson.Cipher>>(replay = 1)
        setupVaultDiskSourceFlows(ciphersFlow = mutableCiphersStateFlow)
        coEvery {
            vaultSdkSource.decryptCipherListWithFailures(
                userId = userId,
                cipherList = mockEncryptedCipherList,
            )
        } returns throwable.asFailure()

        vaultSyncManager
            .decryptCipherListResultStateFlow
            .test {
                assertEquals(DataState.Loading, awaitItem())
                mutableCiphersStateFlow.tryEmit(mockCipherList)

                // No additional emissions until vault is unlocked
                expectNoEvents()
                setVaultToUnlocked(userId = userId)

                assertEquals(DataState.Error<DecryptCipherListResult>(throwable), awaitItem())
            }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `collectionsStateFlow should emit decrypted list of collections when decryptCollectionList succeeds`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val userId = "mockId-1"
            val mockCollectionList = listOf(createMockCollection(number = 1))
            val mockEncryptedCollectionList = mockCollectionList.toEncryptedSdkCollectionList()
            val mockCollectionViewList = listOf(createMockCollectionView(number = 1))
            val mutableCollectionsStateFlow =
                bufferedMutableSharedFlow<List<SyncResponseJson.Collection>>(replay = 1)
            setupVaultDiskSourceFlows(collectionsFlow = mutableCollectionsStateFlow)
            coEvery {
                vaultSdkSource.decryptCollectionList(
                    userId = userId,
                    collectionList = mockEncryptedCollectionList,
                )
            } returns mockCollectionViewList.asSuccess()

            vaultSyncManager
                .collectionsStateFlow
                .test {
                    assertEquals(DataState.Loading, awaitItem())
                    mutableCollectionsStateFlow.tryEmit(mockCollectionList)

                    // No additional emissions until vault is unlocked
                    expectNoEvents()
                    setVaultToUnlocked(userId = userId)

                    assertEquals(DataState.Loaded(mockCollectionViewList), awaitItem())
                }
        }

    @Test
    fun `collectionsStateFlow should emit an error when decryptCollectionList fails`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        val userId = "mockId-1"
        val throwable = Throwable("Fail")
        val mockCollectionList = listOf(createMockCollection(number = 1))
        val mockEncryptedCollectionList = mockCollectionList.toEncryptedSdkCollectionList()
        val mutableCollectionStateFlow =
            bufferedMutableSharedFlow<List<SyncResponseJson.Collection>>(replay = 1)
        setupVaultDiskSourceFlows(collectionsFlow = mutableCollectionStateFlow)
        coEvery {
            vaultSdkSource.decryptCollectionList(
                userId = userId,
                collectionList = mockEncryptedCollectionList,
            )
        } returns throwable.asFailure()

        vaultSyncManager
            .collectionsStateFlow
            .test {
                assertEquals(DataState.Loading, awaitItem())
                mutableCollectionStateFlow.tryEmit(mockCollectionList)

                // No additional emissions until vault is unlocked
                expectNoEvents()
                setVaultToUnlocked(userId = userId)

                assertEquals(DataState.Error<List<CollectionView>>(throwable), awaitItem())
            }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `foldersStateFlow should emit decrypted list of folders when decryptFolderList succeeds`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            val userId = "mockId-1"
            val mockFolderList = listOf(createMockFolder(number = 1))
            val mockEncryptedFolderList = mockFolderList.toEncryptedSdkFolderList()
            val mockFolderViewList = listOf(createMockFolderView(number = 1))
            val mutableFoldersStateFlow =
                bufferedMutableSharedFlow<List<SyncResponseJson.Folder>>(replay = 1)
            setupVaultDiskSourceFlows(foldersFlow = mutableFoldersStateFlow)
            coEvery {
                vaultSdkSource.decryptFolderList(
                    userId = userId,
                    folderList = mockEncryptedFolderList,
                )
            } returns mockFolderViewList.asSuccess()

            vaultSyncManager
                .foldersStateFlow
                .test {
                    assertEquals(DataState.Loading, awaitItem())
                    mutableFoldersStateFlow.tryEmit(mockFolderList)

                    // No additional emissions until vault is unlocked
                    expectNoEvents()
                    setVaultToUnlocked(userId = userId)

                    assertEquals(DataState.Loaded(mockFolderViewList), awaitItem())
                }
        }

    @Test
    fun `foldersStateFlow should emit an error when decryptFolderList fails`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        val userId = "mockId-1"
        val throwable = Throwable("Fail")
        val mockFolderList = listOf(createMockFolder(number = 1))
        val mockEncryptedFolderList = mockFolderList.toEncryptedSdkFolderList()
        val mutableFoldersStateFlow =
            bufferedMutableSharedFlow<List<SyncResponseJson.Folder>>(replay = 1)
        setupVaultDiskSourceFlows(foldersFlow = mutableFoldersStateFlow)
        coEvery {
            vaultSdkSource.decryptFolderList(userId = userId, folderList = mockEncryptedFolderList)
        } returns throwable.asFailure()

        vaultSyncManager
            .foldersStateFlow
            .test {
                assertEquals(DataState.Loading, awaitItem())
                mutableFoldersStateFlow.tryEmit(mockFolderList)

                // No additional emissions until vault is unlocked
                expectNoEvents()
                setVaultToUnlocked(userId = userId)

                assertEquals(DataState.Error<List<FolderView>>(throwable), awaitItem())
            }
    }

    @Test
    fun `databaseSchemeChangeFlow should trigger sync on emission`() = runTest {
        fakeAuthDiskSource.userState = MOCK_USER_STATE

        mutableDatabaseSchemeChangeFlow.tryEmit(Unit)

        coVerify(exactly = 1) {
            vaultDiskSource.resyncVaultData(userId = MOCK_USER_STATE.activeUserId)
        }
    }

    @Test
    fun `sync should resync local vault data without contacting the server`() {
        val userId = "mockId-1"
        fakeAuthDiskSource.userState = MOCK_USER_STATE

        vaultSyncManager.sync()

        verify(exactly = 1) {
            settingsDiskSource.storeLastSyncTime(userId = userId, lastSyncTime = clock.instant())
        }
        coVerify(exactly = 1) { vaultDiskSource.resyncVaultData(userId = userId) }
        coVerify(exactly = 0) {
            syncService.getAccountRevisionDateMillis()
            syncService.sync()
        }
    }

    @Test
    fun `sync with forced should resync local vault data without contacting the server`() {
        val userId = "mockId-1"
        fakeAuthDiskSource.userState = MOCK_USER_STATE

        vaultSyncManager.sync(forced = true)

        coVerify(exactly = 1) { vaultDiskSource.resyncVaultData(userId = userId) }
        coVerify(exactly = 0) {
            syncService.getAccountRevisionDateMillis()
            syncService.sync()
        }
    }

    @Test
    fun `sync should not modify the stored account or log out`() {
        fakeAuthDiskSource.userState = MOCK_USER_STATE

        vaultSyncManager.sync()

        fakeAuthDiskSource.assertUserState(userState = MOCK_USER_STATE)
        verify(exactly = 0) {
            userLogoutManager.logout(userId = any(), reason = any())
        }
    }

    @Test
    fun `syncIfNecessary when there is no last sync time should sync the vault`() {
        val userId = "mockId-1"
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        every { settingsDiskSource.getLastSyncTime(userId = userId) } returns null

        vaultSyncManager.syncIfNecessary()

        coVerify(exactly = 1) { vaultDiskSource.resyncVaultData(userId = userId) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `syncIfNecessary when the current time is greater than 30 minutes after the last sync time should sync the vault`() {
        val userId = "mockId-1"
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        every {
            settingsDiskSource.getLastSyncTime(userId = userId)
        } returns clock.instant().minus(31, ChronoUnit.MINUTES)

        vaultSyncManager.syncIfNecessary()

        coVerify(exactly = 1) { vaultDiskSource.resyncVaultData(userId = userId) }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `syncIfNecessary when the current time is less than 30 minutes after the last sync time should not sync the vault`() {
        val userId = "mockId-1"
        fakeAuthDiskSource.userState = MOCK_USER_STATE
        every {
            settingsDiskSource.getLastSyncTime(userId = userId)
        } returns clock.instant().minus(29, ChronoUnit.MINUTES)

        vaultSyncManager.syncIfNecessary()

        coVerify(exactly = 0) { vaultDiskSource.resyncVaultData(userId = any()) }
    }

    @Test
    fun `vaultDataStateFlow should return empty when last sync time is populated`() =
        runTest {
            val userId = "mockId-1"
            coEvery { vaultLockManager.waitUntilUnlocked(userId = userId) } just runs
            every { settingsDiskSource.getLastSyncTime(userId = userId) } returns clock.instant()

            mutableVaultStateFlow.update {
                listOf(VaultUnlockData(userId = userId, status = VaultUnlockData.Status.UNLOCKED))
            }
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            setupEmptyDecryptionResults()
            setupVaultDiskSourceFlows(
                ciphersFlow = flowOf(emptyList()),
                collectionsFlow = flowOf(emptyList()),
                domainsFlow = flowOf(
                    SyncResponseJson.Domains(
                        globalEquivalentDomains = emptyList(),
                        equivalentDomains = emptyList(),
                    ),
                ),
                foldersFlow = flowOf(emptyList()),
            )

            turbineScope {
                val ciphersStateFlow = vaultSyncManager
                    .decryptCipherListResultStateFlow
                    .testIn(backgroundScope)
                val collectionsStateFlow = vaultSyncManager
                    .collectionsStateFlow
                    .testIn(backgroundScope)
                val foldersStateFlow = vaultSyncManager.foldersStateFlow.testIn(backgroundScope)
                val domainsStateFlow = vaultSyncManager.domainsStateFlow.testIn(backgroundScope)

                assertEquals(
                    DataState.Loaded(
                        createMockDecryptCipherListResult(
                            number = 1,
                            successes = emptyList(),
                        ),
                    ),
                    ciphersStateFlow.awaitItem(),
                )
                assertEquals(
                    DataState.Loaded(emptyList<CollectionView>()),
                    collectionsStateFlow.awaitItem(),
                )
                assertEquals(
                    DataState.Loaded(emptyList<FolderView>()),
                    foldersStateFlow.awaitItem(),
                )
                assertEquals(
                    DataState.Loaded(
                        DomainsData(
                            equivalentDomains = emptyList(),
                            globalEquivalentDomains = emptyList(),
                        ),
                    ),
                    domainsStateFlow.awaitItem(),
                )
            }
        }

    @Test
    fun `vaultDataStateFlow should return loading when last sync time is null`() =
        runTest {
            val userId = "mockId-1"
            coEvery { vaultLockManager.waitUntilUnlocked(userId = userId) } just runs
            every { settingsDiskSource.getLastSyncTime(userId = userId) } returns null
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            setupEmptyDecryptionResults()
            setupVaultDiskSourceFlows(
                ciphersFlow = flowOf(emptyList()),
                collectionsFlow = flowOf(emptyList()),
                domainsFlow = flowOf(),
                foldersFlow = flowOf(emptyList()),
            )
            turbineScope {
                val ciphersStateFlow = vaultSyncManager
                    .decryptCipherListResultStateFlow
                    .testIn(backgroundScope)
                val collectionsStateFlow = vaultSyncManager
                    .collectionsStateFlow
                    .testIn(backgroundScope)
                val foldersStateFlow = vaultSyncManager.foldersStateFlow.testIn(backgroundScope)
                val domainsStateFlow = vaultSyncManager.domainsStateFlow.testIn(backgroundScope)

                assertEquals(DataState.Loading, ciphersStateFlow.awaitItem())
                assertEquals(DataState.Loading, collectionsStateFlow.awaitItem())
                assertEquals(DataState.Loading, foldersStateFlow.awaitItem())
                assertEquals(DataState.Loading, domainsStateFlow.awaitItem())
            }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `syncForResult should return success result with itemsAvailable = true when ciphers list is not empty`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE

            val syncResult = vaultSyncManager.syncForResult()

            assertEquals(SyncVaultDataResult.Success(itemsAvailable = true), syncResult)
            coVerify(exactly = 0) { syncService.sync() }
        }

    @Suppress("MaxLineLength")
    @Test
    fun `syncForResult should return success result with itemsAvailable = false when ciphers list is empty`() =
        runTest {
            fakeAuthDiskSource.userState = MOCK_USER_STATE
            mutableGetCiphersFlow.update { emptyList() }

            val syncResult = vaultSyncManager.syncForResult()

            assertEquals(SyncVaultDataResult.Success(itemsAvailable = false), syncResult)
            coVerify(exactly = 0) { syncService.sync() }
        }

    @Test
    fun `syncForResult with no active user should return error`() = runTest {
        fakeAuthDiskSource.userState = null

        val syncResult = vaultSyncManager.syncForResult()

        assertEquals(SyncVaultDataResult.Error(throwable = NoActiveUserException()), syncResult)
    }

    //region Helper functions

    /**
     * Ensures the vault for the given [userId] is unlocked and can pass any
     * [VaultLockManager.waitUntilUnlocked] or [VaultLockManager.isVaultUnlocked] checks.
     */
    private fun setVaultToUnlocked(userId: String) {
        mutableUnlockedUserIdsStateFlow.update { it + userId }
        mutableVaultStateFlow.tryEmit(
            listOf(VaultUnlockData(userId, VaultUnlockData.Status.UNLOCKED)),
        )
    }

    /**
     * Helper setup all flows required to properly subscribe to the
     * [VaultSyncManager.vaultDataStateFlow].
     */
    @Suppress("LongParameterList")
    private fun setupVaultDiskSourceFlows(
        userId: String = MOCK_USER_STATE.activeUserId,
        ciphersFlow: Flow<List<SyncResponseJson.Cipher>> = bufferedMutableSharedFlow(),
        collectionsFlow: Flow<List<SyncResponseJson.Collection>> = bufferedMutableSharedFlow(),
        domainsFlow: Flow<SyncResponseJson.Domains> = bufferedMutableSharedFlow(),
        foldersFlow: Flow<List<SyncResponseJson.Folder>> = bufferedMutableSharedFlow(),
    ) {
        coEvery { vaultDiskSource.getCiphersFlow(userId = userId) } returns ciphersFlow
        coEvery { vaultDiskSource.getCollectionsFlow(userId = userId) } returns collectionsFlow
        coEvery { vaultDiskSource.getDomainsFlow(userId = userId) } returns domainsFlow
        coEvery { vaultDiskSource.getFoldersFlow(userId = userId) } returns foldersFlow
    }

    private fun setupEmptyDecryptionResults(
        userId: String = MOCK_USER_STATE.activeUserId,
    ) {
        coEvery {
            vaultSdkSource.decryptCipherListWithFailures(userId = userId, cipherList = emptyList())
        } returns createMockDecryptCipherListResult(number = 1, successes = emptyList()).asSuccess()
        coEvery {
            vaultSdkSource.decryptFolderList(userId = userId, folderList = emptyList())
        } returns emptyList<FolderView>().asSuccess()
        coEvery {
            vaultSdkSource.decryptCollectionList(userId = userId, collectionList = emptyList())
        } returns emptyList<CollectionView>().asSuccess()
    }
    //endregion Helper functions
}

private val MOCK_PROFILE = AccountJson.Profile(
    userId = "mockId-1",
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
    activeUserId = "mockId-1",
    accounts = mapOf(
        "mockId-1" to MOCK_ACCOUNT,
    ),
)
