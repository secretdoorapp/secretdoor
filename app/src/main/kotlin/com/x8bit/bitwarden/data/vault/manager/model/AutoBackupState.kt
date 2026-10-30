package com.x8bit.bitwarden.data.vault.manager.model

import java.time.Instant

/**
 * The automatic backup settings and the outcome of the most recent automatic backup.
 *
 * @property locationName The display name of the backup file, or `null` when none is chosen.
 * @property frequency How often backups are written.
 * @property lastBackupTime When the last automatic backup was attempted, if ever.
 * @property lastBackupFailed Whether the last automatic backup failed.
 */
data class AutoBackupState(
    val locationName: String?,
    val frequency: AutoBackupFrequency,
    val lastBackupTime: Instant?,
    val lastBackupFailed: Boolean,
)
