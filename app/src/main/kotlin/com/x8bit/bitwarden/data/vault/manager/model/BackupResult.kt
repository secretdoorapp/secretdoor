package com.x8bit.bitwarden.data.vault.manager.model

/**
 * The result of writing a backup file.
 */
sealed class WriteBackupResult {
    /**
     * The backup was written and contains [itemCount] items.
     */
    data class Success(val itemCount: Int) : WriteBackupResult()

    /**
     * The backup could not be written.
     */
    data class Error(val error: Throwable) : WriteBackupResult()
}

/**
 * The result of reading a backup file.
 */
sealed class ReadBackupResult {
    /**
     * The file is a valid backup.
     */
    data class Success(val backup: VaultBackup) : ReadBackupResult()

    /**
     * The file is not a SecretDoor backup, is damaged, or was made by a newer version of the app.
     */
    data class Error(val error: Throwable) : ReadBackupResult()
}

/**
 * The result of restoring a vault from a backup.
 */
sealed class RestoreBackupResult {
    /**
     * The vault was restored with [itemCount] items and is unlocked.
     */
    data class Success(val itemCount: Int) : RestoreBackupResult()

    /**
     * The master password does not match the backup. Nothing was restored.
     */
    data object WrongPassword : RestoreBackupResult()

    /**
     * The vault could not be restored. Nothing was restored.
     */
    data class Error(val error: Throwable) : RestoreBackupResult()
}

/**
 * Models the result of importing a backup into the current vault.
 */
sealed class MergeBackupResult {
    /**
     * The backup was imported: [addedCount] items were added, [updatedCount] existing items were
     * replaced by a newer copy and [skippedCount] items were already in the vault.
     */
    data class Success(
        val addedCount: Int,
        val updatedCount: Int,
        val skippedCount: Int,
    ) : MergeBackupResult()

    /**
     * The master password does not match the backup. Nothing was imported.
     */
    data object WrongPassword : MergeBackupResult()

    /**
     * The backup could not be imported. Nothing was imported.
     */
    data class Error(val error: Throwable) : MergeBackupResult()
}
