package com.x8bit.bitwarden.data.vault.manager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.x8bit.bitwarden.data.vault.manager.model.WriteBackupResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Writes the scheduled automatic backup. The vault does not need to be unlocked, since the backup
 * only contains data that is already encrypted on the device.
 */
class AutoBackupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val autoBackupManager = EntryPointAccessors
            .fromApplication(applicationContext, AutoBackupWorkerEntryPoint::class.java)
            .autoBackupManager()
        return when (autoBackupManager.backUpNow()) {
            is WriteBackupResult.Success -> Result.success()
            is WriteBackupResult.Error -> Result.retry()
        }
    }

    /**
     * Gives [AutoBackupWorker] access to app singletons.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoBackupWorkerEntryPoint {
        /**
         * The app's [AutoBackupManager].
         */
        fun autoBackupManager(): AutoBackupManager
    }
}
