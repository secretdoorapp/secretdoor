package com.x8bit.bitwarden.data.vault.datasource.disk

/**
 * Stores the automatic backup settings on the device.
 */
interface AutoBackupDiskSource {
    /**
     * The document URI the automatic backup is written to.
     */
    var backupUri: String?

    /**
     * The display name of the document at [backupUri].
     */
    var backupLocationName: String?

    /**
     * The name of the selected automatic backup frequency.
     */
    var backupFrequency: String?

    /**
     * When the last automatic backup was attempted, in epoch milliseconds.
     */
    var lastBackupTimeMillis: Long?

    /**
     * Whether the last automatic backup failed.
     */
    var lastBackupFailed: Boolean?
}
