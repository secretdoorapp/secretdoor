package com.x8bit.bitwarden.data.platform.manager.policy

import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.auth.datasource.disk.model.AccountJson
import com.x8bit.bitwarden.data.auth.datasource.disk.model.ForcePasswordResetReason
import com.x8bit.bitwarden.data.auth.datasource.sdk.AuthSdkSource
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult

/**
 * The default [PasswordPolicyManager] implementation.
 */
internal class PasswordPolicyManagerImpl(
    private val authDiskSource: AuthDiskSource,
    private val authSdkSource: AuthSdkSource,
) : PasswordPolicyManager {
    private val activeAccountProfile: AccountJson.Profile?
        get() = authDiskSource.userState?.activeAccount?.profile

    override val passwordResetReason: ForcePasswordResetReason?
        get() = activeAccountProfile?.forcePasswordResetReason

    override fun getPasswordStrength(
        email: String?,
        password: String,
    ): PasswordStrengthResult = authSdkSource
        .passwordStrength(
            email = email ?: activeAccountProfile?.email.orEmpty(),
            password = password,
        )
        .fold(
            onSuccess = { PasswordStrengthResult.Success(passwordStrength = it) },
            onFailure = { PasswordStrengthResult.Error(error = it) },
        )
}
