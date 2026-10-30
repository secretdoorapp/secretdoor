package com.x8bit.bitwarden.data.platform.manager.policy

import com.x8bit.bitwarden.data.auth.datasource.disk.model.ForcePasswordResetReason
import com.x8bit.bitwarden.data.auth.repository.model.PasswordStrengthResult

/**
 * A manager for password strength and password reset requirements.
 */
interface PasswordPolicyManager {
    /**
     * The reason for resetting the password.
     */
    val passwordResetReason: ForcePasswordResetReason?

    /**
     * Get the password strength for the given [email] and [password] combo. If no value is
     * passed for the [email] will use the active email of the current active account.
     */
    fun getPasswordStrength(email: String? = null, password: String): PasswordStrengthResult
}
