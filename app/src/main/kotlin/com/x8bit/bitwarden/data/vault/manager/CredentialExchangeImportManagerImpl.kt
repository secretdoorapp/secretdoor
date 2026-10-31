package com.x8bit.bitwarden.data.vault.manager

import com.bitwarden.cxf.model.CredentialExchangePayload
import com.bitwarden.cxf.parser.CredentialExchangePayloadParser
import com.bitwarden.vault.EncryptionContext
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.model.ImportCxfPayloadResult
import com.x8bit.bitwarden.data.vault.manager.model.SyncVaultDataResult
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkCipherResponse
import java.time.Clock
import java.util.UUID

/**
 * Default implementation of [CredentialExchangeImportManager].
 *
 * Imported items are encrypted by the SDK and saved straight to the local vault.
 */
class CredentialExchangeImportManagerImpl(
    private val vaultSdkSource: VaultSdkSource,
    private val vaultDiskSource: VaultDiskSource,
    private val clock: Clock,
    private val vaultSyncManager: VaultSyncManager,
    private val credentialExchangePayloadParser: CredentialExchangePayloadParser,
) : CredentialExchangeImportManager {

    override suspend fun importCxfPayload(
        userId: String,
        payload: String,
    ): ImportCxfPayloadResult =
        when (val exportResponse = credentialExchangePayloadParser.parse(payload)) {
            is CredentialExchangePayload.Importable -> {
                import(
                    userId = userId,
                    accountsJsonList = exportResponse.accountsJsonList,
                )
            }

            CredentialExchangePayload.NoItems -> {
                ImportCxfPayloadResult.NoItems
            }

            is CredentialExchangePayload.Error -> {
                ImportCxfPayloadResult.Error(exportResponse.throwable)
            }
        }

    private suspend fun import(
        userId: String,
        accountsJsonList: List<String>,
    ): ImportCxfPayloadResult {
        val allCiphers = accountsJsonList.flatMap { accountJson ->
            vaultSdkSource
                .importCxf(userId = userId, payload = accountJson)
                .getOrElse { return ImportCxfPayloadResult.Error(error = it) }
        }

        if (allCiphers.isEmpty()) {
            return ImportCxfPayloadResult.NoItems
        }

        return syncVault(saveCiphers(userId = userId, ciphers = allCiphers))
    }

    private suspend fun saveCiphers(
        userId: String,
        ciphers: List<EncryptionContext>,
    ): ImportCxfPayloadResult.Success {
        val now = clock.instant()
        vaultDiskSource.saveCiphers(
            userId = userId,
            ciphers = ciphers.map {
                it
                    .toEncryptedNetworkCipherResponse()
                    .copy(
                        id = UUID.randomUUID().toString(),
                        creationDate = now,
                        revisionDate = now,
                        shouldEdit = true,
                        shouldViewPassword = true,
                    )
            },
        )
        return ImportCxfPayloadResult.Success(itemCount = ciphers.size)
    }

    private suspend fun syncVault(result: ImportCxfPayloadResult): ImportCxfPayloadResult =
        when (val syncResult = vaultSyncManager.syncForResult(forced = true)) {
            is SyncVaultDataResult.Success -> result
            is SyncVaultDataResult.Error -> {
                ImportCxfPayloadResult.SyncFailed(error = syncResult.throwable)
            }
        }
}
