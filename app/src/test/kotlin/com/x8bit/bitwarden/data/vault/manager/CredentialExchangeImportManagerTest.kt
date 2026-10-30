package com.x8bit.bitwarden.data.vault.manager

import androidx.credentials.providerevents.exception.ImportCredentialsInvalidJsonException
import com.bitwarden.core.data.util.asFailure
import com.bitwarden.core.data.util.asSuccess
import com.bitwarden.cxf.model.CredentialExchangePayload
import com.bitwarden.cxf.parser.CredentialExchangePayloadParser
import com.bitwarden.network.model.SyncResponseJson
import com.bitwarden.policies.PolicyType
import com.bitwarden.vault.EncryptionContext
import com.x8bit.bitwarden.data.platform.manager.PolicyManager
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockEncryptionContext
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkCipher
import com.x8bit.bitwarden.data.vault.datasource.sdk.model.createMockSdkPolicy
import com.x8bit.bitwarden.data.vault.manager.model.ImportCxfPayloadResult
import com.x8bit.bitwarden.data.vault.manager.model.SyncVaultDataResult
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkCipherResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CredentialExchangeImportManagerTest {

    private val vaultSdkSource: VaultSdkSource = mockk()
    private val vaultDiskSource: VaultDiskSource = mockk {
        coEvery { saveCiphers(userId = any(), ciphers = any()) } just runs
    }
    private val vaultSyncManager: VaultSyncManager = mockk()
    private val policyManager: PolicyManager = mockk {
        every {
            getActivePolicies(any())
        } returns emptyList()
    }
    private val credentialExchangePayloadParser: CredentialExchangePayloadParser = mockk {
        every { parse(DEFAULT_PAYLOAD) } returns CredentialExchangePayload.Importable(
            accountsJsonList = listOf(DEFAULT_ACCOUNT_JSON),
        )
    }

    private val importManager = CredentialExchangeImportManagerImpl(
        vaultSdkSource = vaultSdkSource,
        vaultDiskSource = vaultDiskSource,
        clock = FIXED_CLOCK,
        vaultSyncManager = vaultSyncManager,
        policyManager = policyManager,
        credentialExchangePayloadParser = credentialExchangePayloadParser,
    )

    @BeforeEach
    fun setup() {
        mockkStatic(UUID::randomUUID)
        every { UUID.randomUUID().toString() } returns IMPORTED_CIPHER_ID
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(UUID::randomUUID)
    }

    @Nested
    inner class ParserResultHandling {
        @Test
        fun `when parser returns Error, should return Error`() = runTest {
            val parserException = ImportCredentialsInvalidJsonException("Invalid JSON")
            every {
                credentialExchangePayloadParser.parse(DEFAULT_PAYLOAD)
            } returns CredentialExchangePayload.Error(parserException)

            val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

            assertTrue(result is ImportCxfPayloadResult.Error)
            assertEquals(parserException, (result as ImportCxfPayloadResult.Error).error)
        }

        @Test
        fun `when parser returns NoItems, should return NoItems`() = runTest {
            every {
                credentialExchangePayloadParser.parse(DEFAULT_PAYLOAD)
            } returns CredentialExchangePayload.NoItems

            val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

            assertEquals(ImportCxfPayloadResult.NoItems, result)
        }
    }

    @Nested
    inner class ImportFlow {
        @Test
        fun `when vaultSdkSource importCxf fails, should return Error`() = runTest {
            val exception = RuntimeException("SDK import failed")
            coEvery {
                vaultSdkSource.importCxf(
                    userId = DEFAULT_USER_ID,
                    payload = DEFAULT_ACCOUNT_JSON,
                )
            } returns exception.asFailure()


            val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

            assertEquals(ImportCxfPayloadResult.Error(exception), result)
            coVerify(exactly = 1) {
                vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
            }
            coVerify(exactly = 0) {
                vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
            }
        }

        @Suppress("MaxLineLength")
        @Test
        fun `when ciphers are saved and sync fails should return SyncFailed`() =
            runTest {
                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns DEFAULT_CIPHER_LIST.asSuccess()

                val throwable = Throwable("Error!")
                coEvery {
                    vaultSyncManager.syncForResult(forced = true)
                } returns SyncVaultDataResult.Error(throwable)

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(
                    ImportCxfPayloadResult.SyncFailed(throwable),
                    result,
                )
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                    vaultSyncManager.syncForResult(forced = true)
                }
            }

        @Test
        fun `when all steps succeed, should return Success`() = runTest {
            coEvery {
                vaultSdkSource.importCxf(
                    userId = DEFAULT_USER_ID,
                    payload = DEFAULT_ACCOUNT_JSON,
                )
            } returns DEFAULT_CIPHER_LIST.asSuccess()

            coEvery {
                vaultSyncManager.syncForResult(forced = true)
            } returns SyncVaultDataResult.Success(itemsAvailable = true)

            val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

            assertEquals(ImportCxfPayloadResult.Success(itemCount = 1), result)
            coVerify(exactly = 1) {
                vaultDiskSource.saveCiphers(
                    userId = DEFAULT_USER_ID,
                    ciphers = listOf(
                        DEFAULT_CIPHER
                            .toEncryptedNetworkCipherResponse()
                            .copy(
                                id = IMPORTED_CIPHER_ID,
                                creationDate = FIXED_CLOCK.instant(),
                                revisionDate = FIXED_CLOCK.instant(),
                                shouldEdit = true,
                                shouldViewPassword = true,
                            ),
                    ),
                )
            }
            coVerify(exactly = 1) {
                vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                vaultSyncManager.syncForResult(forced = true)
            }
        }

        @Suppress("MaxLineLength")
        @Test
        fun `when importCxf returns empty cipher list, should skip saving and sync and return NoItems`() =
            runTest {
                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns emptyList<EncryptionContext>().asSuccess()

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.NoItems, result)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                }
                coVerify(exactly = 0) {
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                }
            }

        @Suppress("MaxLineLength")
        @Test
        fun `when multiple accounts, should call importCxf for each and aggregate ciphers`() =
            runTest {
                every {
                    credentialExchangePayloadParser.parse(DEFAULT_PAYLOAD)
                } returns CredentialExchangePayload.Importable(
                    accountsJsonList = listOf(DEFAULT_ACCOUNT_JSON, DEFAULT_ACCOUNT_JSON_2),
                )

                val cipher1 = createMockEncryptionContext(number = 1)
                val cipher2 = createMockEncryptionContext(number = 2)
                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns listOf(cipher1).asSuccess()
                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON_2,
                    )
                } returns listOf(cipher2).asSuccess()

                val capturedCiphers = slot<List<SyncResponseJson.Cipher>>()
                coEvery {
                    vaultDiskSource.saveCiphers(
                        userId = DEFAULT_USER_ID,
                        ciphers = capture(capturedCiphers),
                    )
                } just runs
                coEvery {
                    vaultSyncManager.syncForResult(forced = true)
                } returns SyncVaultDataResult.Success(itemsAvailable = true)

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.Success(itemCount = 2), result)
                assertEquals(2, capturedCiphers.captured.size)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON_2)
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                    vaultSyncManager.syncForResult(forced = true)
                }
            }

        @Suppress("MaxLineLength")
        @Test
        fun `when second account importCxf fails, should return Error without saving`() =
            runTest {
                every {
                    credentialExchangePayloadParser.parse(DEFAULT_PAYLOAD)
                } returns CredentialExchangePayload.Importable(
                    accountsJsonList = listOf(DEFAULT_ACCOUNT_JSON, DEFAULT_ACCOUNT_JSON_2),
                )

                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns listOf(createMockEncryptionContext(number = 1)).asSuccess()

                val exception = RuntimeException("SDK import failed on second account")
                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON_2,
                    )
                } returns exception.asFailure()


                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.Error(exception), result)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON_2)
                }
                coVerify(exactly = 0) {
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                }
            }
    }

    @Nested
    inner class PolicyFiltering {
        @Test
        fun `when user has restrict item types policy, card ciphers should be filtered out`() =
            runTest {
                every {
                    policyManager.getActivePolicies(PolicyType.RESTRICTED_ITEM_TYPES)
                } returns listOf(
                    createMockSdkPolicy(
                        organizationId = "mockId-1",
                        type = PolicyType.RESTRICTED_ITEM_TYPES,
                        enabled = true,
                        data = null,
                    ),
                )

                val loginCipher = createMockEncryptionContext(number = 1)
                val cardCipher = createMockEncryptionContext(
                    number = 2,
                    cipher = createMockSdkCipher(number = 2).copy(
                        type = com.bitwarden.vault.CipherType.CARD,
                    ),
                )
                val mixedCipherList = listOf(loginCipher, cardCipher)

                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns mixedCipherList.asSuccess()

                val capturedCiphers = slot<List<SyncResponseJson.Cipher>>()
                coEvery {
                    vaultDiskSource.saveCiphers(
                        userId = DEFAULT_USER_ID,
                        ciphers = capture(capturedCiphers),
                    )
                } just runs

                coEvery {
                    vaultSyncManager.syncForResult(forced = true)
                } returns SyncVaultDataResult.Success(itemsAvailable = true)

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.Success(itemCount = 1), result)
                // Verify only the login cipher was imported, card was filtered out
                assertEquals(1, capturedCiphers.captured.size)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                    vaultSyncManager.syncForResult(forced = true)
                }
            }

        @Test
        fun `when user has no restrict item types policy, card ciphers should not be filtered`() =
            runTest {
                every {
                    policyManager.getActivePolicies(PolicyType.RESTRICTED_ITEM_TYPES)
                } returns emptyList()

                val loginCipher = createMockEncryptionContext(number = 1)
                val cardCipher = createMockEncryptionContext(
                    number = 2,
                    cipher = createMockSdkCipher(number = 2).copy(
                        type = com.bitwarden.vault.CipherType.CARD,
                    ),
                )
                val mixedCipherList = listOf(loginCipher, cardCipher)

                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns mixedCipherList.asSuccess()

                val capturedCiphers = slot<List<SyncResponseJson.Cipher>>()
                coEvery {
                    vaultDiskSource.saveCiphers(
                        userId = DEFAULT_USER_ID,
                        ciphers = capture(capturedCiphers),
                    )
                } just runs

                coEvery {
                    vaultSyncManager.syncForResult(forced = true)
                } returns SyncVaultDataResult.Success(itemsAvailable = true)

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.Success(itemCount = 2), result)
                // Verify both ciphers were imported
                assertEquals(2, capturedCiphers.captured.size)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                    vaultSyncManager.syncForResult(forced = true)
                }
            }

        @Test
        fun `when user has restrict policy disabled, card ciphers should not be filtered`() =
            runTest {
                every {
                    policyManager.getActivePolicies(PolicyType.RESTRICTED_ITEM_TYPES)
                } returns listOf(
                    createMockSdkPolicy(
                        organizationId = "mockId-1",
                        type = PolicyType.RESTRICTED_ITEM_TYPES,
                        enabled = false,
                        data = null,
                    ),
                )

                val loginCipher = createMockEncryptionContext(number = 1)
                val cardCipher = createMockEncryptionContext(
                    number = 2,
                    cipher = createMockSdkCipher(number = 2).copy(
                        type = com.bitwarden.vault.CipherType.CARD,
                    ),
                )
                val mixedCipherList = listOf(loginCipher, cardCipher)

                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns mixedCipherList.asSuccess()

                val capturedCiphers = slot<List<SyncResponseJson.Cipher>>()
                coEvery {
                    vaultDiskSource.saveCiphers(
                        userId = DEFAULT_USER_ID,
                        ciphers = capture(capturedCiphers),
                    )
                } just runs

                coEvery {
                    vaultSyncManager.syncForResult(forced = true)
                } returns SyncVaultDataResult.Success(itemsAvailable = true)

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.Success(itemCount = 2), result)
                // Verify both ciphers were imported when policy is disabled
                assertEquals(2, capturedCiphers.captured.size)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                    vaultSyncManager.syncForResult(forced = true)
                }
            }

        @Test
        fun `when user has restrict policy and all ciphers are cards, should return NoItems`() =
            runTest {
                every {
                    policyManager.getActivePolicies(PolicyType.RESTRICTED_ITEM_TYPES)
                } returns listOf(
                    createMockSdkPolicy(
                        organizationId = "mockId-1",
                        type = PolicyType.RESTRICTED_ITEM_TYPES,
                        enabled = true,
                        data = null,
                    ),
                )

                val cardCipher1 = createMockEncryptionContext(
                    number = 1,
                    cipher = createMockSdkCipher(number = 1).copy(
                        type = com.bitwarden.vault.CipherType.CARD,
                    ),
                )
                val cardCipher2 = createMockEncryptionContext(
                    number = 2,
                    cipher = createMockSdkCipher(number = 2).copy(
                        type = com.bitwarden.vault.CipherType.CARD,
                    ),
                )
                val allCardsList = listOf(cardCipher1, cardCipher2)

                coEvery {
                    vaultSdkSource.importCxf(
                        userId = DEFAULT_USER_ID,
                        payload = DEFAULT_ACCOUNT_JSON,
                    )
                } returns allCardsList.asSuccess()

                val result = importManager.importCxfPayload(DEFAULT_USER_ID, DEFAULT_PAYLOAD)

                assertEquals(ImportCxfPayloadResult.NoItems, result)
                coVerify(exactly = 1) {
                    vaultSdkSource.importCxf(DEFAULT_USER_ID, DEFAULT_ACCOUNT_JSON)
                }
                // Verify nothing was saved since all items were filtered
                coVerify(exactly = 0) {
                    vaultDiskSource.saveCiphers(userId = DEFAULT_USER_ID, ciphers = any())
                }
            }
    }
}

private const val DEFAULT_USER_ID = "mockId-1"
private const val IMPORTED_CIPHER_ID = "11111111-2222-3333-4444-555555555555"
private val FIXED_CLOCK: Clock = Clock.fixed(
    Instant.parse("2023-10-27T12:00:00Z"),
    ZoneOffset.UTC,
)
private const val DEFAULT_PAYLOAD = "mockPayload-1"
private val DEFAULT_CIPHER: EncryptionContext = createMockEncryptionContext(number = 1)
private val DEFAULT_CIPHER_LIST: List<EncryptionContext> = listOf(DEFAULT_CIPHER)

private val DEFAULT_ACCOUNT_JSON = """
    {
      "id": "$DEFAULT_USER_ID",
      "username": "username-1",
      "email": "mockEmail-1",
      "fullName": "fullName-1",
      "collections": [],
      "items": [
        {
          "id": "mockId-1",
          "creationAt": 1759783057,
          "modifiedAt": 1759783057,
          "title": "mockTitle-1",
          "favorite": false,
          "scope": {
            "urls": [
              "mockUrl-1"
            ],
            "androidApps": []
          },
          "credentials": [
            {
              "type": "mockType-1",
              "username": {
                "fieldType": "mockUsernameFieldType-1",
                "value": "mockUsernameValue-1"
              },
              "password": {
                "fieldType": "mockPasswordFieldType-1",
                "value": "mockPasswordValue-1"
              }
            }
          ]
        }
      ]
    }
"""
    .trimIndent()

private val DEFAULT_ACCOUNT_JSON_2 = """
    {
      "id": "mockId-2",
      "username": "username-2",
      "email": "mockEmail-2",
      "fullName": "fullName-2",
      "collections": [],
      "items": [
        {
          "id": "mockId-2",
          "creationAt": 1759783057,
          "modifiedAt": 1759783057,
          "title": "mockTitle-2",
          "favorite": false,
          "scope": {
            "urls": [
              "mockUrl-2"
            ],
            "androidApps": []
          },
          "credentials": [
            {
              "type": "mockType-2",
              "username": {
                "fieldType": "mockUsernameFieldType-2",
                "value": "mockUsernameValue-2"
              },
              "password": {
                "fieldType": "mockPasswordFieldType-2",
                "value": "mockPasswordValue-2"
              }
            }
          ]
        }
      ]
    }
"""
    .trimIndent()
