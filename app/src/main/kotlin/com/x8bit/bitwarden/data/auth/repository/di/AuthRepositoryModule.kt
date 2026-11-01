package com.x8bit.bitwarden.data.auth.repository.di

import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.network.service.AccountsService
import com.bitwarden.network.service.HaveIBeenPwnedService
import com.bitwarden.network.service.IdentityService
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.AuthStateManager
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.AuthRepositoryImpl
import com.x8bit.bitwarden.data.platform.manager.BiometricsEncryptionManager
import com.x8bit.bitwarden.data.platform.manager.LogsManager
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides repositories in the auth package.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthRepositoryModule {

    @Provides
    @Singleton
    fun providesAuthRepository(
        clock: Clock,
        accountsService: AccountsService,
        identityService: IdentityService,
        haveIBeenPwnedService: HaveIBeenPwnedService,
        authSdkSource: AuthSdkSource,
        vaultSdkSource: VaultSdkSource,
        authDiskSource: AuthDiskSource,
        dispatcherManager: DispatcherManager,
        environmentRepository: EnvironmentRepository,
        settingsRepository: SettingsRepository,
        vaultRepository: VaultRepository,
        biometricsEncryptionManager: BiometricsEncryptionManager,
        userLogoutManager: UserLogoutManager,
        logsManager: LogsManager,
        userStateManager: UserStateManager,
        kdfManager: KdfManager,
        authStateManager: AuthStateManager,
    ): AuthRepository = AuthRepositoryImpl(
        clock = clock,
        accountsService = accountsService,
        identityService = identityService,
        authSdkSource = authSdkSource,
        vaultSdkSource = vaultSdkSource,
        authDiskSource = authDiskSource,
        haveIBeenPwnedService = haveIBeenPwnedService,
        dispatcherManager = dispatcherManager,
        environmentRepository = environmentRepository,
        settingsRepository = settingsRepository,
        vaultRepository = vaultRepository,
        biometricsEncryptionManager = biometricsEncryptionManager,
        userLogoutManager = userLogoutManager,
        logsManager = logsManager,
        userStateManager = userStateManager,
        kdfManager = kdfManager,
        authStateManager = authStateManager,
    )
}
