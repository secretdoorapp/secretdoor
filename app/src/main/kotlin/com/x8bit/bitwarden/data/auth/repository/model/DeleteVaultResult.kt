package com.x8bit.bitwarden.data.auth.repository.model

/**
 * Models the result of deleting a vault from this device.
 */
sealed class DeleteVaultResult {
    /**
     * The vault and everything stored for it was deleted.
     */
    data object Success : DeleteVaultResult()

    /**
     * The master password was not correct. Nothing was deleted.
     */
    data object WrongPassword : DeleteVaultResult()

    /**
     * The vault could not be deleted. Nothing was deleted.
     */
    data class Error(val error: Throwable?) : DeleteVaultResult()
}
