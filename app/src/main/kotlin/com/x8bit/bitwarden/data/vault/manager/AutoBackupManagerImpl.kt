package com.x8bit.bitwarden.data.vault.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.x8bit.bitwarden.data.vault.datasource.disk.AutoBackupDiskSource
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupFrequency
import com.x8bit.bitwarden.data.vault.manager.model.AutoBackupState
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val AUTO_BACKUP_WORK_NAME: String = "secretdoorAutoBackup"

/**
 * Default implementation of [AutoBackupManager].
 */
class AutoBackupManagerImpl(
    private val context: Context,
    private val autoBackupDiskSource: AutoBackupDiskSource,
    private val vaultBackupManager: VaultBackupManager,
    private val clock: Clock,
) : AutoBackupManager {
    // Billing is not set up yet, so automatic backups are available to everyone for now.
    override val isAutoBackupAvailable: Boolean = true

    private val mutableAutoBackupStateFlow = MutableStateFlow(readState())

    override val autoBackupStateFlow: StateFlow<AutoBackupState>
        get() = mutableAutoBackupStateFlow.asStateFlow()

    override fun setBackupLocation(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        autoBackupDiskSource.backupUri = uri.toString()
        autoBackupDiskSource.backupLocationName = queryDisplayName(uri)
        mutableAutoBackupStateFlow.update { readState() }
        schedule()
    }

    override fun setFrequency(frequency: AutoBackupFrequency) {
        autoBackupDiskSource.backupFrequency = frequency.name
        mutableAutoBackupStateFlow.update { readState() }
        schedule()
    }

    override suspend fun backUpNow(): WriteBackupResult {
        val uri = autoBackupDiskSource.backupUri?.toUri()
            ?: return WriteBackupResult.Error(error = IllegalStateException("No backup location"))
        val result = vaultBackupManager.writeBackup(uri = uri)
        autoBackupDiskSource.lastBackupTimeMillis = clock.millis()
        autoBackupDiskSource.lastBackupFailed = result is WriteBackupResult.Error
        mutableAutoBackupStateFlow.update { readState() }
        return result
    }

    private fun schedule() {
        val workManager = WorkManager.getInstance(context)
        val intervalDays = readState().frequency.intervalDays
        if (intervalDays == null || autoBackupDiskSource.backupUri == null) {
            workManager.cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            AUTO_BACKUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun readState(): AutoBackupState =
        AutoBackupState(
            locationName = autoBackupDiskSource.backupLocationName,
            frequency = AutoBackupFrequency
                .entries
                .firstOrNull { it.name == autoBackupDiskSource.backupFrequency }
                ?: AutoBackupFrequency.OFF,
            lastBackupTime = autoBackupDiskSource.lastBackupTimeMillis?.let(Instant::ofEpochMilli),
            lastBackupFailed = autoBackupDiskSource.lastBackupFailed == true,
        )

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }
            .getOrNull()
            ?: uri.lastPathSegment
}
