package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import com.x8bit.bitwarden.data.vault.manager.model.ReadBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.RestoreBackupResult
import com.x8bit.bitwarden.data.vault.manager.model.VaultBackup
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult

/**
 * Writes and reads SecretDoor backup files.
 *
 * A backup holds the vault exactly as it is stored on the device (already encrypted), so creating
 * one never needs the vault to be unlocked, and restoring one needs the vault's master password.
 */
interface VaultBackupManager {
    /**
     * Writes a backup of the active vault to [uri].
     */
    suspend fun writeBackup(uri: Uri): WriteBackupResult

    /**
     * Reads the backup stored at [uri] without decrypting anything.
     */
    suspend fun readBackup(uri: Uri): ReadBackupResult

    /**
     * Restores [backup] as this device's vault and unlocks it with [masterPassword]. Only
     * allowed when no vault exists yet. If the password is wrong nothing is kept.
     */
    suspend fun restoreBackup(backup: VaultBackup, masterPassword: String): RestoreBackupResult
}
