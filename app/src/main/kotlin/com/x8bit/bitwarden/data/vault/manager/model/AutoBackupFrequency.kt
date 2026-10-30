package com.x8bit.bitwarden.data.vault.manager.model

/**
 * How often SecretDoor writes an automatic backup.
 *
 * @property intervalDays The number of days between backups, or `null` when turned off.
 */
enum class AutoBackupFrequency(val intervalDays: Long?) {
    OFF(intervalDays = null),
    DAILY(intervalDays = 1),
    WEEKLY(intervalDays = 7),
}
