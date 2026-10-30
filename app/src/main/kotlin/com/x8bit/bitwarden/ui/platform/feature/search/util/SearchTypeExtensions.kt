package com.x8bit.bitwarden.ui.platform.feature.search.util

import com.x8bit.bitwarden.ui.platform.feature.search.SearchTypeData
import com.x8bit.bitwarden.ui.platform.feature.search.model.SearchType

/**
 * Transforms a [SearchType] into a [SearchTypeData].
 */
fun SearchType.toSearchTypeData(): SearchTypeData =
    when (this) {
        SearchType.Vault.All -> SearchTypeData.Vault.All
        SearchType.Vault.Archive -> SearchTypeData.Vault.Archive
        SearchType.Vault.Cards -> SearchTypeData.Vault.Cards
        is SearchType.Vault.Folder -> SearchTypeData.Vault.Folder(folderId)
        SearchType.Vault.Identities -> SearchTypeData.Vault.Identities
        SearchType.Vault.Logins -> SearchTypeData.Vault.Logins
        SearchType.Vault.NoFolder -> SearchTypeData.Vault.NoFolder
        SearchType.Vault.SecureNotes -> SearchTypeData.Vault.SecureNotes
        SearchType.Vault.Trash -> SearchTypeData.Vault.Trash
        SearchType.Vault.VerificationCodes -> SearchTypeData.Vault.VerificationCodes
        SearchType.Vault.SshKeys -> SearchTypeData.Vault.SshKeys
        SearchType.Vault.BankAccounts -> SearchTypeData.Vault.BankAccounts
        SearchType.Vault.Licenses -> SearchTypeData.Vault.Licenses
        SearchType.Vault.Passports -> SearchTypeData.Vault.Passports
    }
