package com.x8bit.bitwarden.data.vault.datasource.disk.database

import androidx.room3.AutoMigration
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.DeleteTable
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import com.x8bit.bitwarden.data.vault.datasource.disk.convertor.InstantTypeConverter
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.CiphersDao
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.CollectionsDao
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.DomainsDao
import com.x8bit.bitwarden.data.vault.datasource.disk.dao.FoldersDao
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.CipherEntity
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.CollectionEntity
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.DomainsEntity
import com.x8bit.bitwarden.data.vault.datasource.disk.entity.FolderEntity

/**
 * Room database for storing any persisted data from the vault sync.
 */
@Database(
    entities = [
        CipherEntity::class,
        CollectionEntity::class,
        DomainsEntity::class,
        FolderEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10, RemoveTotpAutoMigration::class),
        AutoMigration(from = 10, to = 11, RemoveSendsAutoMigration::class),
    ],
)
@ColumnTypeConverters(InstantTypeConverter::class)
abstract class VaultDatabase : RoomDatabase() {

    /**
     * Provides the DAO for accessing cipher data.
     */
    abstract fun cipherDao(): CiphersDao

    /**
     * Provides the DAO for accessing collection data.
     */
    abstract fun collectionDao(): CollectionsDao

    /**
     * Provides the DAO for accessing domains data.
     */
    abstract fun domainsDao(): DomainsDao

    /**
     * Provides the DAO for accessing folder data.
     */
    abstract fun folderDao(): FoldersDao
}

/**
 * A defined migration to remove the `has_totp` field from the `cipher` table.
 */
@DeleteColumn.Entries(DeleteColumn(tableName = "ciphers", columnName = "has_totp"))
class RemoveTotpAutoMigration : AutoMigrationSpec

/**
 * A defined migration to drop the `sends` table: SecretDoor has no Send feature, so the table
 * only ever held data synced from a server.
 */
@DeleteTable.Entries(DeleteTable(tableName = "sends"))
class RemoveSendsAutoMigration : AutoMigrationSpec
