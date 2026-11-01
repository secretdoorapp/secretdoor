package com.x8bit.bitwarden.data.auth.manager.di

import android.content.Context
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.manager.toast.ToastManager
import com.bitwarden.data.manager.file.FileManager
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.manager.AuthStateManager
import com.x8bit.bitwarden.data.auth.manager.AuthStateManagerImpl
import com.x8bit.bitwarden.data.auth.manager.AuthTokenManager
import com.x8bit.bitwarden.data.auth.manager.AuthTokenManagerImpl
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.KdfManagerImpl
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManagerImpl
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManagerImpl
import com.x8bit.bitwarden.data.platform.datasource.disk.SettingsDiskSource
import com.x8bit.bitwarden.data.platform.manager.CredentialExchangeRegistryManager
import com.x8bit.bitwarden.data.platform.manager.FirstTimeActionManager
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.GeneratorDiskSource
import com.x8bit.bitwarden.data.tools.generator.datasource.disk.PasswordHistoryDiskSource
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides managers in the auth package.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthManagerModule {

    @Provides
    @Singleton
    fun providesAuthStateManager(
        authDiskSource: AuthDiskSource,
        dispatcherManager: DispatcherManager,
    ): AuthStateManager = AuthStateManagerImpl(
        authDiskSource = authDiskSource,
        dispatcherManager = dispatcherManager,
    )

    @Provides
    @Singleton
    fun provideUserLogoutManager(
        authDiskSource: AuthDiskSource,
        fileManager: FileManager,
        generatorDiskSource: GeneratorDiskSource,
        passwordHistoryDiskSource: PasswordHistoryDiskSource,
        settingsDiskSource: SettingsDiskSource,
        toastManager: ToastManager,
        vaultDiskSource: VaultDiskSource,
        vaultSdkSource: VaultSdkSource,
        dispatcherManager: DispatcherManager,
        credentialExchangeRegistryManager: CredentialExchangeRegistryManager,
    ): UserLogoutManager =
        UserLogoutManagerImpl(
            authDiskSource = authDiskSource,
            fileManager = fileManager,
            generatorDiskSource = generatorDiskSource,
            passwordHistoryDiskSource = passwordHistoryDiskSource,
            settingsDiskSource = settingsDiskSource,
            toastManager = toastManager,
            vaultDiskSource = vaultDiskSource,
            vaultSdkSource = vaultSdkSource,
            dispatcherManager = dispatcherManager,
            credentialExchangeRegistryManager = credentialExchangeRegistryManager,
        )

    @Provides
    @Singleton
    fun providesAuthTokenManager(
        authDiskSource: AuthDiskSource,
    ): AuthTokenManager = AuthTokenManagerImpl(authDiskSource = authDiskSource)

    @Provides
    @Singleton
    fun providesKdfManager(
        authDiskSource: AuthDiskSource,
        vaultSdkSource: VaultSdkSource,
    ): KdfManager = KdfManagerImpl(
        authDiskSource = authDiskSource,
        vaultSdkSource = vaultSdkSource,
    )

    @Provides
    @Singleton
    fun providesUserStateManager(
        authDiskSource: AuthDiskSource,
        firstTimeActionManager: FirstTimeActionManager,
        vaultLockManager: VaultLockManager,
        dispatcherManager: DispatcherManager,
    ): UserStateManager = UserStateManagerImpl(
        authDiskSource = authDiskSource,
        firstTimeActionManager = firstTimeActionManager,
        vaultLockManager = vaultLockManager,
        dispatcherManager = dispatcherManager,
    )
}
