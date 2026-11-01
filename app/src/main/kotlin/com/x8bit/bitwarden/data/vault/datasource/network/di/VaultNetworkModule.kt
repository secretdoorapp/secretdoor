package com.x8bit.bitwarden.data.vault.datasource.network.di

import com.bitwarden.network.BitwardenServiceClient
import com.bitwarden.network.service.SyncService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides network dependencies in the vault package.
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultNetworkModule {

    @Provides
    @Singleton
    fun provideSyncService(
        bitwardenServiceClient: BitwardenServiceClient,
    ): SyncService = bitwardenServiceClient.syncService
}
