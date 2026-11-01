package com.x8bit.bitwarden.data.auth.datasource.network.di

import com.bitwarden.network.BitwardenServiceClient
import com.bitwarden.network.service.HaveIBeenPwnedService
import com.bitwarden.network.service.IdentityService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides network dependencies in the auth package.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthNetworkModule {

    @Provides
    @Singleton
    fun providesIdentityService(
        bitwardenServiceClient: BitwardenServiceClient,
    ): IdentityService = bitwardenServiceClient.identityService

    @Provides
    @Singleton
    fun providesHaveIBeenPwnedService(
        bitwardenServiceClient: BitwardenServiceClient,
    ): HaveIBeenPwnedService = bitwardenServiceClient.haveIBeenPwnedService
}
