package com.x8bit.bitwarden.data.tools.generator.repository.model

import com.bitwarden.vault.SshKeyView

/**
 * Represents the outcome of generating or importing an SSH key.
 */
sealed class SshKeyResult {
    /**
     * The key was produced.
     */
    data class Success(val sshKey: SshKeyView) : SshKeyResult()

    /**
     * The key is encrypted and needs a passphrase.
     */
    data object PasswordRequired : SshKeyResult()

    /**
     * The passphrase did not unlock the key.
     */
    data object WrongPassword : SshKeyResult()

    /**
     * The text is not an SSH key.
     */
    data object InvalidKey : SshKeyResult()

    /**
     * The key is valid but of a type the app cannot use.
     */
    data object UnsupportedKeyType : SshKeyResult()

    /**
     * Something else went wrong.
     */
    data class Error(val error: Throwable) : SshKeyResult()
}
