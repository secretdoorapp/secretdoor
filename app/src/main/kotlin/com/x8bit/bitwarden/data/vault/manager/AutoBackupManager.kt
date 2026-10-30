package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupState
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages automatic backups: SecretDoor writing a backup file by itself, on a schedule, to a
 * location the user picked once (for example a file in Google Drive).
 */
interface AutoBackupManager {
    /**
     * Whether automatic backups are available to the user. This is the paid part of SecretDoor;
     * manually saving a backup file is always free.
     */
    val isAutoBackupAvailable: Boolean

    /**
     * The current automatic backup settings and last result.
     */
    val autoBackupStateFlow: StateFlow<AutoBackupState>

    /**
     * Sets the document automatic backups are written to and keeps permission to write it.
     */
    fun setBackupLocation(uri: Uri)

    /**
     * Sets how often automatic backups run, scheduling or cancelling the background work.
     */
    fun setFrequency(frequency: AutoBackupFrequency)

    /**
     * Writes an automatic backup right away and records the result.
     */
    suspend fun backUpNow(): WriteBackupResult
}
