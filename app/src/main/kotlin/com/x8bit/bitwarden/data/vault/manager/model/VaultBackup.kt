package com.x8bit.bitwarden.data.vault.manager.model

import java.io.File
import java.time.Instant

/**
 * A SecretDoor backup file that was picked for restoring or importing.
 *
 * Nothing in it is decrypted until the master password of the backup's vault is given.
 *
 * @property createdAt When the backup was made.
 * @property file A private copy of the backup file.
 * @property isEncrypted Whether the whole file is encrypted (version 2 and later). Version 1
 * backups only have their items encrypted.
 */
data class VaultBackup(
    val createdAt: Instant,
    val file: File,
    val isEncrypted: Boolean,
)
