package com.x8bit.bitwarden.data.auth.repository.model

/**
 * Models the result of changing the master password.
 */
sealed class ChangeMasterPasswordResult {
    /**
     * The master password was changed.
     */
    data object Success : ChangeMasterPasswordResult()

    /**
     * The current master password was not correct. Nothing was changed.
     */
    data object WrongPassword : ChangeMasterPasswordResult()

    /**
     * The master password could not be changed. Nothing was changed.
     */
    data class Error(val error: Throwable) : ChangeMasterPasswordResult()
}
