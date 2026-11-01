package com.x8bit.bitwarden.data.platform.repository.di

import android.view.autofill.AutofillManager
import com.bitwarden.core.data.manager.BuildInfoManager
import com.bitwarden.core.data.manager.dispatcher.DispatcherManager
import com.bitwarden.data.manager.flightrecorder.FlightRecorderManager
import com.bitwarden.data.repository.ServerConfigRepository
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.autofill.accessibility.manager.AccessibilityEnabledManager
import com.x8bit.bitwarden.data.autofill.manager.AutofillEnabledManager
import com.x8bit.bitwarden.data.platform.datasource.disk.CookieDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.EnvironmentDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.FeatureFlagOverrideDiskSource
import com.x8bit.bitwarden.data.platform.datasource.disk.SettingsDiskSource
import com.x8bit.bitwarden.data.platform.repository.DebugMenuRepository
import com.x8bit.bitwarden.data.platform.repository.DebugMenuRepositoryImpl
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepository
import com.x8bit.bitwarden.data.platform.repository.EnvironmentRepositoryImpl
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import com.x8bit.bitwarden.data.platform.repository.SettingsRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides repositories in the auth package.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformRepositoryModule {

    @Provides
    @Singleton
    fun provideEnvironmentRepository(
        environmentDiskSource: EnvironmentDiskSource,
        authDiskSource: AuthDiskSource,
        dispatcherManager: DispatcherManager,
    ): EnvironmentRepository =
        EnvironmentRepositoryImpl(
            environmentDiskSource = environmentDiskSource,
            authDiskSource = authDiskSource,
            dispatcherManager = dispatcherManager,
        )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        autofillManager: AutofillManager,
        autofillEnabledManager: AutofillEnabledManager,
        authDiskSource: AuthDiskSource,
        buildInfoManager: BuildInfoManager,
        settingsDiskSource: SettingsDiskSource,
        accessibilityEnabledManager: AccessibilityEnabledManager,
        dispatcherManager: DispatcherManager,
        flightRecorderManager: FlightRecorderManager,
    ): SettingsRepository =
        SettingsRepositoryImpl(
            autofillManager = autofillManager,
            autofillEnabledManager = autofillEnabledManager,
            authDiskSource = authDiskSource,
            buildInfoManager = buildInfoManager,
            settingsDiskSource = settingsDiskSource,
            accessibilityEnabledManager = accessibilityEnabledManager,
            dispatcherManager = dispatcherManager,
            flightRecorderManager = flightRecorderManager,
        )

    @Provides
    @Singleton
    fun provideDebugMenuRepository(
        featureFlagOverrideDiskSource: FeatureFlagOverrideDiskSource,
        serverConfigRepository: ServerConfigRepository,
        authDiskSource: AuthDiskSource,
        settingsDiskSource: SettingsDiskSource,
        cookieDiskSource: CookieDiskSource,
    ): DebugMenuRepository = DebugMenuRepositoryImpl(
        featureFlagOverrideDiskSource = featureFlagOverrideDiskSource,
        serverConfigRepository = serverConfigRepository,
        authDiskSource = authDiskSource,
        settingsDiskSource = settingsDiskSource,
        cookieDiskSource = cookieDiskSource,
    )
}
