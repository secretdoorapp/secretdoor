package com.x8bit.bitwarden.data.vault.manager.di

import android.content.Context
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.core.data.manager.realtime.RealtimeManager
import com.bitwarden.cxf.parser.CredentialExchangePayloadParser
import com.bitwarden.data.manager.appstate.AppStateManager
import com.bitwarden.data.manager.file.FileManager
import com.bitwarden.network.service.SyncService
import com.bitwarden.ui.platform.feature.cardscanner.manager.CardScanManager
import com.bitwarden.ui.platform.feature.cardscanner.manager.CardScanManagerImpl
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.manager.KdfManager
import com.x8bit.bitwarden.data.auth.manager.TrustedDeviceManager
import com.x8bit.bitwarden.data.auth.manager.UserLogoutManager
import com.x8bit.bitwarden.data.auth.manager.UserStateManager
import com.x8bit.bitwarden.data.autofill.manager.FillAssistManager
import com.x8bit.bitwarden.data.platform.datasource.disk.SettingsDiskSource
import com.x8bit.bitwarden.data.platform.manager.DatabaseSchemeManager
import com.x8bit.bitwarden.data.platform.manager.PushManager
import com.x8bit.bitwarden.data.platform.manager.ReviewPromptManager
import com.x8bit.bitwarden.data.platform.manager.keyrotation.KeyRotationManager
import com.x8bit.bitwarden.data.platform.manager.policy.PasswordPolicyManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.vault.datasource.disk.AutoBackupDiskSource
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.AutoBackupManager
import com.x8bit.bitwarden.data.vault.manager.AutoBackupManagerImpl
import com.x8bit.bitwarden.data.vault.manager.CipherManager
import com.x8bit.bitwarden.data.vault.manager.CipherManagerImpl
import com.x8bit.bitwarden.data.vault.manager.CredentialExchangeImportManager
import com.x8bit.bitwarden.data.vault.manager.CredentialExchangeImportManagerImpl
import com.x8bit.bitwarden.data.vault.manager.FolderManager
import com.x8bit.bitwarden.data.vault.manager.FolderManagerImpl
import com.x8bit.bitwarden.data.vault.manager.PinProtectedUserKeyManager
import com.x8bit.bitwarden.data.vault.manager.PinProtectedUserKeyManagerImpl
import com.x8bit.bitwarden.data.vault.manager.TotpCodeManager
import com.x8bit.bitwarden.data.vault.manager.TotpCodeManagerImpl
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManager
import com.x8bit.bitwarden.data.vault.manager.VaultBackupManagerImpl
import com.x8bit.bitwarden.data.vault.manager.VaultLockManager
import com.x8bit.bitwarden.data.vault.manager.VaultLockManagerImpl
import com.x8bit.bitwarden.data.vault.manager.VaultSyncManager
import com.x8bit.bitwarden.data.vault.manager.VaultSyncManagerImpl
import com.x8bit.bitwarden.data.vault.repository.VaultRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Singleton

/**
 * Provides managers in the vault package.
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultManagerModule {

    @Provides
    @Singleton
    fun provideCardScanManager(): CardScanManager = CardScanManagerImpl()

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideVaultBackupManager(
        @ApplicationContext context: Context,
        authDiskSource: AuthDiskSource,
        vaultDiskSource: VaultDiskSource,
        vaultRepository: VaultRepository,
        userStateManager: UserStateManager,
        settingsRepository: SettingsRepository,
        fileManager: FileManager,
        json: Json,
        clock: Clock,
        dispatcherManager: DispatcherManager,
    ): VaultBackupManager = VaultBackupManagerImpl(
        context = context,
        authDiskSource = authDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultRepository = vaultRepository,
        userStateManager = userStateManager,
        settingsRepository = settingsRepository,
        fileManager = fileManager,
        json = json,
        clock = clock,
        dispatcherManager = dispatcherManager,
    )

    @Provides
    @Singleton
    fun provideAutoBackupManager(
        @ApplicationContext context: Context,
        autoBackupDiskSource: AutoBackupDiskSource,
        vaultBackupManager: VaultBackupManager,
        clock: Clock,
    ): AutoBackupManager = AutoBackupManagerImpl(
        context = context,
        autoBackupDiskSource = autoBackupDiskSource,
        vaultBackupManager = vaultBackupManager,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideCipherManager(
        settingsDiskSource: SettingsDiskSource,
        vaultDiskSource: VaultDiskSource,
        vaultSdkSource: VaultSdkSource,
        authDiskSource: AuthDiskSource,
        fileManager: FileManager,
        clock: Clock,
        reviewPromptManager: ReviewPromptManager,
    ): CipherManager = CipherManagerImpl(
        fileManager = fileManager,
        settingsDiskSource = settingsDiskSource,
        authDiskSource = authDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        clock = clock,
        reviewPromptManager = reviewPromptManager,
    )

    @Provides
    @Singleton
    fun provideFolderManager(
        vaultDiskSource: VaultDiskSource,
        vaultSdkSource: VaultSdkSource,
        authDiskSource: AuthDiskSource,
        clock: Clock,
    ): FolderManager = FolderManagerImpl(
        authDiskSource = authDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideVaultLockManager(
        @ApplicationContext context: Context,
        clock: Clock,
        realtimeManager: RealtimeManager,
        authDiskSource: AuthDiskSource,
        authSdkSource: AuthSdkSource,
        vaultSdkSource: VaultSdkSource,
        settingsRepository: SettingsRepository,
        appStateManager: AppStateManager,
        userLogoutManager: UserLogoutManager,
        dispatcherManager: DispatcherManager,
        trustedDeviceManager: TrustedDeviceManager,
        kdfManager: KdfManager,
        pinProtectedUserKeyManager: PinProtectedUserKeyManager,
        passwordPolicyManager: PasswordPolicyManager,
        keyRotationManager: KeyRotationManager,
    ): VaultLockManager =
        VaultLockManagerImpl(
            context = context,
            clock = clock,
            realtimeManager = realtimeManager,
            authDiskSource = authDiskSource,
            authSdkSource = authSdkSource,
            vaultSdkSource = vaultSdkSource,
            settingsRepository = settingsRepository,
            appStateManager = appStateManager,
            userLogoutManager = userLogoutManager,
            dispatcherManager = dispatcherManager,
            trustedDeviceManager = trustedDeviceManager,
            kdfManager = kdfManager,
            pinProtectedUserKeyManager = pinProtectedUserKeyManager,
            passwordPolicyManager = passwordPolicyManager,
            keyRotationManager = keyRotationManager,
        )

    @Provides
    @Singleton
    fun providePinProtectedUserKeyManager(
        authDiskSource: AuthDiskSource,
        vaultSdkSource: VaultSdkSource,
    ): PinProtectedUserKeyManager =
        PinProtectedUserKeyManagerImpl(
            authDiskSource = authDiskSource,
            vaultSdkSource = vaultSdkSource,
        )

    @Provides
    @Singleton
    fun provideTotpManager(
        vaultSdkSource: VaultSdkSource,
        dispatcherManager: DispatcherManager,
        clock: Clock,
    ): TotpCodeManager =
        TotpCodeManagerImpl(
            vaultSdkSource = vaultSdkSource,
            dispatcherManager = dispatcherManager,
            clock = clock,
        )

    @Provides
    @Singleton
    fun provideVaultSyncManager(
        fillAssistManager: FillAssistManager,
        syncService: SyncService,
        settingsDiskSource: SettingsDiskSource,
        authDiskSource: AuthDiskSource,
        vaultDiskSource: VaultDiskSource,
        vaultSdkSource: VaultSdkSource,
        userLogoutManager: UserLogoutManager,
        userStateManager: UserStateManager,
        vaultLockManager: VaultLockManager,
        clock: Clock,
        databaseSchemeManager: DatabaseSchemeManager,
        pushManager: PushManager,
        dispatcherManager: DispatcherManager,
    ): VaultSyncManager = VaultSyncManagerImpl(
        fillAssistManager = fillAssistManager,
        syncService = syncService,
        settingsDiskSource = settingsDiskSource,
        authDiskSource = authDiskSource,
        vaultDiskSource = vaultDiskSource,
        vaultSdkSource = vaultSdkSource,
        userLogoutManager = userLogoutManager,
        userStateManager = userStateManager,
        vaultLockManager = vaultLockManager,
        clock = clock,
        databaseSchemeManager = databaseSchemeManager,
        pushManager = pushManager,
        dispatcherManager = dispatcherManager,
    )

    @Provides
    @Singleton
    fun provideCredentialExchangeImportManager(
        vaultSdkSource: VaultSdkSource,
        vaultDiskSource: VaultDiskSource,
        clock: Clock,
        vaultSyncManager: VaultSyncManager,
        credentialExchangePayloadParser: CredentialExchangePayloadParser,
    ): CredentialExchangeImportManager = CredentialExchangeImportManagerImpl(
        vaultSdkSource = vaultSdkSource,
        vaultDiskSource = vaultDiskSource,
        clock = clock,
        vaultSyncManager = vaultSyncManager,
        credentialExchangePayloadParser = credentialExchangePayloadParser,
    )
}
