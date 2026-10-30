package com.x8bit.bitwarden.data.vault.datasource.disk

import android.content.SharedPreferences
import com.bitwarden.data.datasource.disk.BaseDiskSource

private const val BACKUP_URI_KEY = "autoBackupUri"
private const val BACKUP_LOCATION_NAME_KEY = "autoBackupLocationName"
private const val BACKUP_FREQUENCY_KEY = "autoBackupFrequency"
private const val LAST_BACKUP_TIME_KEY = "autoBackupLastTime"
private const val LAST_BACKUP_FAILED_KEY = "autoBackupLastFailed"

/**
 * Primary implementation of [AutoBackupDiskSource].
 */
class AutoBackupDiskSourceImpl(
    sharedPreferences: SharedPreferences,
) : BaseDiskSource(sharedPreferences = sharedPreferences),
    AutoBackupDiskSource {
    override var backupUri: String?
        get() = getString(key = BACKUP_URI_KEY)
        set(value) = putString(key = BACKUP_URI_KEY, value = value)

    override var backupLocationName: String?
        get() = getString(key = BACKUP_LOCATION_NAME_KEY)
        set(value) = putString(key = BACKUP_LOCATION_NAME_KEY, value = value)

    override var backupFrequency: String?
        get() = getString(key = BACKUP_FREQUENCY_KEY)
        set(value) = putString(key = BACKUP_FREQUENCY_KEY, value = value)

    override var lastBackupTimeMillis: Long?
        get() = getLong(key = LAST_BACKUP_TIME_KEY)
        set(value) = putLong(key = LAST_BACKUP_TIME_KEY, value = value)

    override var lastBackupFailed: Boolean?
        get() = getBoolean(key = LAST_BACKUP_FAILED_KEY)
        set(value) = putBoolean(key = LAST_BACKUP_FAILED_KEY, value = value)
}
