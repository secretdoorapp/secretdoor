package com.x8bit.bitwarden.data.vault.repository.model

import com.bitwarden.collections.CollectionView
import com.bitwarden.vault.DecryptCipherListResult
import com.bitwarden.vault.FolderView

/**
 * Represents decrypted vault data.
 *
 * @param decryptCipherListResult Contains the result of decrypting ciphers for display in a list.
 * @param collectionViewList List of decrypted collections.
 * @param folderViewList List of decrypted folders.
 */
data class VaultData(
    val decryptCipherListResult: DecryptCipherListResult,
    val collectionViewList: List<CollectionView>,
    val folderViewList: List<FolderView>,
)
