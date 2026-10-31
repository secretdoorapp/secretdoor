package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import com.x8bit.bitwarden.data.vault.manager.model.MergeBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult

/**
 * Writes, restores and imports SecretDoor backup files.
 *
 * A backup holds the vault exactly as it is stored on the device (already encrypted), and the
 * whole file is encrypted again with a key derived from the vault's user key. Creating one never
 * needs the master password; restoring or importing one needs the master password of the vault it
 * was made from.
 */
interface VaultBackupManager {
    /**
     * Writes a backup of the active vault to [uri].
     */
    suspend fun writeBackup(uri: Uri): WriteBackupResult

    /**
     * Copies the backup stored at [uri] and reads its unencrypted header.
     */
    suspend fun readBackup(uri: Uri): ReadBackupResult

    /**
     * Restores [backup] as this device's vault and unlocks it with [masterPassword]. Only
     * allowed when no vault exists yet. If the password is wrong nothing is kept.
     */
    suspend fun restoreBackup(backup: VaultBackup, masterPassword: String): RestoreBackupResult

    /**
     * Adds the folders and items of [backup] to the unlocked active vault. [masterPassword] is the
     * master password of the vault the backup was made from. Items already in the vault are
     * kept; when the same item is in both, the newer copy is kept.
     */
    suspend fun mergeBackup(backup: VaultBackup, masterPassword: String): MergeBackupResult
}
