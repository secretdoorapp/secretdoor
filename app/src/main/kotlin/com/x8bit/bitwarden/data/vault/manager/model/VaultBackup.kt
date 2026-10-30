package com.x8bit.bitwarden.data.vault.manager.model

import com.bitwarden.core.WrappedAccountCryptographicState
import com.bitwarden.network.model.SyncResponseJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import java.io.File
import java.time.Instant

/**
 * The contents of a SecretDoor backup file.
 *
 * Everything sensitive stays encrypted exactly as it is stored on the device: items, folders and
 * attachments are encrypted with the vault's user key, which is itself only available after the
 * master password unlocks [account]'s master-key-wrapped user key.
 *
 * @property createdAt When the backup was made.
 * @property account The vault account, including its KDF settings and wrapped user key.
 * @property accountCryptographicState The account's wrapped private key material.
 * @property masterPasswordHint The optional master password hint stored on the device.
 * @property folders The encrypted folders.
 * @property ciphers The encrypted items.
 * @property attachmentsDirectory A directory holding the encrypted attachment files laid out as
 * `<cipherId>/<attachmentId>`, or `null` when the backup has no attachments.
 */
data class VaultBackup(
    val createdAt: Instant,
    val account: AccountJson,
    val accountCryptographicState: WrappedAccountCryptographicState,
    val masterPasswordHint: String?,
    val folders: List<SyncResponseJson.Folder>,
    val ciphers: List<SyncResponseJson.Cipher>,
    val attachmentsDirectory: File?,
)
