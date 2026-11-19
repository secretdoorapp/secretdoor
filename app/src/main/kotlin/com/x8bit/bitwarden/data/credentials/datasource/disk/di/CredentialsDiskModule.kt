package com.x8bit.bitwarden.data.credentials.datasource.disk.di

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.x8bit.bitwarden.data.credentials.datasource.disk.PrivilegedAppDiskSource
import com.x8bit.bitwarden.data.credentials.datasource.disk.PrivilegedAppDiskSourceImpl
import com.x8bit.bitwarden.data.credentials.datasource.disk.dao.PrivilegedAppDao
import com.x8bit.bitwarden.data.credentials.datasource.disk.database.PrivilegedAppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides persistence-related dependencies in the credentials package.
 */
@Module
@InstallIn(SingletonComponent::class)
object CredentialsDiskModule {

    /**
     * Provides the database of apps the user chose to trust.
     *
     * There is intentionally no destructive-migration fallback: a missing migration must fail
     * loudly instead of silently erasing what the user has set up. Every schema change needs a
     * migration (see `PrivilegedAppDatabaseMigrationTest`).
     */
    @Provides
    @Singleton
    fun providePrivilegedAppDatabase(
        app: Application,
    ): PrivilegedAppDatabase =
        Room
            .databaseBuilder<PrivilegedAppDatabase>(
                context = app,
                name = "privileged_apps_database",
            )
            .setDriver(driver = AndroidSQLiteDriver())
            .build()

    @Provides
    @Singleton
    fun providePrivilegedAppDao(
        database: PrivilegedAppDatabase,
    ): PrivilegedAppDao = database.privilegedAppDao()

    @Provides
    @Singleton
    fun providePrivilegedAppDiskSource(
        privilegedAppDao: PrivilegedAppDao,
    ): PrivilegedAppDiskSource =
        PrivilegedAppDiskSourceImpl(
            privilegedAppDao = privilegedAppDao,
        )
}
