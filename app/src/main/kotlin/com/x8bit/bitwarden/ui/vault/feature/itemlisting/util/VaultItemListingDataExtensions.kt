@file:Suppress("TooManyFunctions")

package com.x8bit.bitwarden.ui.vault.feature.itemlisting.util

import androidx.annotation.DrawableRes
import com.bitwarden.collections.CollectionView
import com.bitwarden.core.data.util.toFormattedDateTimeStyle
import com.bitwarden.ui.platform.base.util.toHostOrPathOrNull
import com.bitwarden.ui.platform.components.icon.model.IconData
import com.bitwarden.ui.platform.model.TotpData
import com.bitwarden.ui.platform.resource.BitwardenDrawable
import com.bitwarden.ui.platform.resource.BitwardenString
import com.bitwarden.ui.util.asText
import com.bitwarden.vault.CipherListView
import com.bitwarden.vault.CipherListViewType
import com.bitwarden.vault.CipherRepromptType
import com.bitwarden.vault.CipherView
import com.bitwarden.vault.FolderView
import com.x8bit.bitwarden.data.autofill.model.AutofillSelectionData
import com.x8bit.bitwarden.data.autofill.util.isActiveWithFido2Credentials
import com.x8bit.bitwarden.data.autofill.util.login
import com.x8bit.bitwarden.data.credentials.model.CreateCredentialRequest
import com.x8bit.bitwarden.data.platform.util.isActive
import com.x8bit.bitwarden.data.vault.repository.model.VaultData
import com.x8bit.bitwarden.data.vault.repository.util.toFailureCipherListView
import com.x8bit.bitwarden.ui.vault.feature.itemlisting.VaultItemListingState
import com.x8bit.bitwarden.ui.vault.feature.util.getFolders
import com.x8bit.bitwarden.ui.vault.feature.util.toFolderDisplayName
import com.x8bit.bitwarden.ui.vault.feature.util.toLabelIcons
import com.x8bit.bitwarden.ui.vault.feature.util.toOverflowActions
import com.x8bit.bitwarden.ui.vault.feature.vault.model.VaultFilterType
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toFilteredList
import com.x8bit.bitwarden.ui.vault.feature.vault.util.toLoginIconData
import com.x8bit.bitwarden.ui.vault.util.toSdkCipherType
import java.time.Clock
import java.time.format.FormatStyle

/**
 * Determines a predicate to filter a list of [CipherView] based on the
 * [VaultItemListingState.ItemListingType].
 */
fun CipherListView.determineListingPredicate(
    itemListingType: VaultItemListingState.ItemListingType.Vault,
): Boolean =
    when (itemListingType) {
        is VaultItemListingState.ItemListingType.Vault.Card -> {
            type is CipherListViewType.Card && isActive
        }


        is VaultItemListingState.ItemListingType.Vault.Folder -> {
            folderId == itemListingType.folderId && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.Identity -> {
            type is CipherListViewType.Identity && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.Login -> {
            type is CipherListViewType.Login && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.SecureNote -> {
            type is CipherListViewType.SecureNote && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.SshKey -> {
            type is CipherListViewType.SshKey && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.BankAccount -> {
            type is CipherListViewType.BankAccount && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.License -> {
            type is CipherListViewType.DriversLicense && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.Passport -> {
            type is CipherListViewType.Passport && isActive
        }

        is VaultItemListingState.ItemListingType.Vault.Trash -> {
            deletedDate != null
        }

        is VaultItemListingState.ItemListingType.Vault.Archive -> {
            archivedDate != null && deletedDate == null
        }
    }

/**
 * Transforms a list of [CipherListView] into [VaultItemListingState.ViewState].
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "NestedBlockDepth")
fun VaultData.toViewState(
    itemListingType: VaultItemListingState.ItemListingType.Vault,
    vaultFilterType: VaultFilterType,
    hasMasterPassword: Boolean,
    baseIconUrl: String,
    isIconLoadingDisabled: Boolean,
    autofillSelectionData: AutofillSelectionData?,
    createCredentialRequestData: CreateCredentialRequest?,
    totpData: TotpData?,
    isPremiumUser: Boolean,
    isVfo1FoundationEnabled: Boolean = false,
): VaultItemListingState.ViewState {
    val filteredCipherViewList = decryptCipherListResult
        .successes
        .applyFilters(
            itemListingType = itemListingType,
            vaultFilterType = vaultFilterType,
        )

    val failuresCipherViewList = decryptCipherListResult
        .failures
        .map { cipher ->
            cipher.toFailureCipherListView()
        }

    val filteredFailuresCipherViewList = failuresCipherViewList
        .applyFilters(
            itemListingType = itemListingType,
            vaultFilterType = vaultFilterType,
        )

    val allFilteredCipherViewList = filteredFailuresCipherViewList
        .plus(filteredCipherViewList)

    val folderList =
        (itemListingType as? VaultItemListingState.ItemListingType.Vault.Folder)
            ?.folderId
            ?.let { folderViewList.getFolders(it) }
            .orEmpty()


    return if (folderList.isNotEmpty() || allFilteredCipherViewList.isNotEmpty()) {
        VaultItemListingState.ViewState.Content(
            displayItemList = filteredFailuresCipherViewList
                .toDisplayItemListDecryptionError(isVfo1FoundationEnabled = isVfo1FoundationEnabled)
                .plus(
                    filteredCipherViewList.toDisplayItemList(
                        baseIconUrl = baseIconUrl,
                        hasMasterPassword = hasMasterPassword,
                        isIconLoadingDisabled = isIconLoadingDisabled,
                        isAutofill = autofillSelectionData != null,
                        isFido2Creation = createCredentialRequestData != null,
                        isPremiumUser = isPremiumUser,
                        isVfo1FoundationEnabled = isVfo1FoundationEnabled,
                    ),
                ),
            displayFolderList = folderList.map { folderView ->
                VaultItemListingState.FolderDisplayItem(
                    id = requireNotNull(folderView.id),
                    name = folderView.name,
                    count = decryptCipherListResult
                        .successes
                        .count {
                            it.deletedDate == null &&
                                !it.id.isNullOrBlank() &&
                                folderView.id == it.folderId
                        },
                )
            },
        )
    } else {
        // Use the autofill empty message if necessary, otherwise use normal type-specific message
        val message = autofillSelectionData
            ?.uri
            ?.toHostOrPathOrNull()
            ?.let { BitwardenString.no_items_for_uri.asText(it) }
            ?: createCredentialRequestData
                ?.relyingPartyIdOrNull
                ?.toHostOrPathOrNull()
                ?.let { BitwardenString.no_items_for_uri.asText(it) }
            ?: totpData?.let { BitwardenString.search_for_a_login_or_add_a_new_login.asText() }
            ?: run {
                when (itemListingType) {
                    is VaultItemListingState.ItemListingType.Vault.Folder -> {
                        BitwardenString.no_items_folder
                    }


                    VaultItemListingState.ItemListingType.Vault.Trash -> {
                        BitwardenString.no_items_trash
                    }

                    VaultItemListingState.ItemListingType.Vault.Card -> {
                        BitwardenString.no_cards
                    }

                    VaultItemListingState.ItemListingType.Vault.Identity -> {
                        BitwardenString.no_identities
                    }

                    VaultItemListingState.ItemListingType.Vault.Login -> {
                        BitwardenString.no_logins
                    }

                    VaultItemListingState.ItemListingType.Vault.SecureNote -> {
                        BitwardenString.no_notes
                    }

                    VaultItemListingState.ItemListingType.Vault.SshKey -> {
                        BitwardenString.no_ssh_keys
                    }

                    VaultItemListingState.ItemListingType.Vault.BankAccount -> {
                        BitwardenString.no_bank_accounts
                    }

                    VaultItemListingState.ItemListingType.Vault.License -> {
                        BitwardenString.no_licenses
                    }

                    VaultItemListingState.ItemListingType.Vault.Passport -> {
                        BitwardenString.no_passports
                    }

                    VaultItemListingState.ItemListingType.Vault.Archive -> {
                        BitwardenString.no_archives_message
                    }
                }
                    .asText()
            }

        val shouldShowAddButton = itemListingType.hasFab

        VaultItemListingState.ViewState.NoItems(
            header = totpData
                ?.let {
                    BitwardenString.no_items_for_vault
                        .asText(it.issuer ?: it.accountName ?: "--")
                }
                ?: run {
                    when (itemListingType) {
                        is VaultItemListingState.ItemListingType.Vault.Folder,
                        VaultItemListingState.ItemListingType.Vault.Trash,
                        VaultItemListingState.ItemListingType.Vault.Card,
                        VaultItemListingState.ItemListingType.Vault.Identity,
                        VaultItemListingState.ItemListingType.Vault.Login,
                        VaultItemListingState.ItemListingType.Vault.SecureNote,
                        VaultItemListingState.ItemListingType.Vault.SshKey,
                        VaultItemListingState.ItemListingType.Vault.BankAccount,
                        VaultItemListingState.ItemListingType.Vault.License,
                        VaultItemListingState.ItemListingType.Vault.Passport,
                            -> null

                        VaultItemListingState.ItemListingType.Vault.Archive -> {
                            BitwardenString.no_archives_title.asText()
                        }
                    }
                },
            message = message,
            shouldShowAddButton = shouldShowAddButton,
            buttonText = createCredentialRequestData
                ?.let { BitwardenString.save_passkey_as_new_login.asText() }
                ?: run {
                    when (itemListingType) {
                        VaultItemListingState.ItemListingType.Vault.Card -> {
                            BitwardenString.add_card
                        }

                        VaultItemListingState.ItemListingType.Vault.Identity -> {
                            BitwardenString.add_identity
                        }

                        VaultItemListingState.ItemListingType.Vault.Login -> {
                            BitwardenString.add_login
                        }

                        VaultItemListingState.ItemListingType.Vault.SecureNote -> {
                            BitwardenString.add_note
                        }

                        VaultItemListingState.ItemListingType.Vault.SshKey -> {
                            BitwardenString.add_ssh_key
                        }

                        VaultItemListingState.ItemListingType.Vault.BankAccount -> {
                            BitwardenString.add_bank_account
                        }

                        VaultItemListingState.ItemListingType.Vault.License -> {
                            BitwardenString.add_license
                        }

                        VaultItemListingState.ItemListingType.Vault.Passport -> {
                            BitwardenString.add_passport
                        }

                        else -> BitwardenString.add_item
                    }
                        .asText()
                },
            vectorRes = totpData
                ?.let { BitwardenDrawable.ill_folder_question }
                ?: run {
                    when (itemListingType) {
                        is VaultItemListingState.ItemListingType.Vault.Folder,
                        VaultItemListingState.ItemListingType.Vault.Trash,
                        VaultItemListingState.ItemListingType.Vault.Card,
                        VaultItemListingState.ItemListingType.Vault.Identity,
                        VaultItemListingState.ItemListingType.Vault.Login,
                        VaultItemListingState.ItemListingType.Vault.SecureNote,
                        VaultItemListingState.ItemListingType.Vault.SshKey,
                        VaultItemListingState.ItemListingType.Vault.BankAccount,
                        VaultItemListingState.ItemListingType.Vault.License,
                        VaultItemListingState.ItemListingType.Vault.Passport,
                            -> null

                        VaultItemListingState.ItemListingType.Vault.Archive -> {
                            BitwardenDrawable.ill_open_source
                        }
                    }
                },
        )
    }
}

/**
 * Updates a [VaultItemListingState.ItemListingType] with the given data if necessary.
 */
fun VaultItemListingState.ItemListingType.updateWithAdditionalDataIfNecessary(
    folderList: List<FolderView>,
    collectionList: List<CollectionView>,
): VaultItemListingState.ItemListingType =
    when (this) {
        is VaultItemListingState.ItemListingType.Vault.Card -> this

        is VaultItemListingState.ItemListingType.Vault.Folder -> {
            val fullyQualifiedName = folderList
                .find { it.id == folderId }
                ?.name
            copy(
                folderName = fullyQualifiedName
                    ?.toFolderDisplayName()
                    .orEmpty(),
                fullyQualifiedName = fullyQualifiedName.orEmpty(),
            )
        }

        is VaultItemListingState.ItemListingType.Vault.Identity -> this
        is VaultItemListingState.ItemListingType.Vault.Login -> this
        is VaultItemListingState.ItemListingType.Vault.SecureNote -> this
        is VaultItemListingState.ItemListingType.Vault.Trash -> this
        is VaultItemListingState.ItemListingType.Vault.SshKey -> this
        is VaultItemListingState.ItemListingType.Vault.BankAccount -> this
        is VaultItemListingState.ItemListingType.Vault.License -> this
        is VaultItemListingState.ItemListingType.Vault.Passport -> this
        is VaultItemListingState.ItemListingType.Vault.Archive -> this
    }

@Suppress("LongParameterList")
private fun List<CipherListView>.toDisplayItemList(
    baseIconUrl: String,
    hasMasterPassword: Boolean,
    isIconLoadingDisabled: Boolean,
    isAutofill: Boolean,
    isFido2Creation: Boolean,
    isPremiumUser: Boolean,
    isVfo1FoundationEnabled: Boolean = false,
): List<VaultItemListingState.DisplayItem> =
    this.map {
        it.toDisplayItem(
            baseIconUrl = baseIconUrl,
            hasMasterPassword = hasMasterPassword,
            isIconLoadingDisabled = isIconLoadingDisabled,
            isAutofill = isAutofill,
            isFido2Creation = isFido2Creation,
            isPremiumUser = isPremiumUser,
            isVfo1FoundationEnabled = isVfo1FoundationEnabled,
        )
    }

@Suppress("LongParameterList")
private fun CipherListView.toDisplayItem(
    baseIconUrl: String,
    hasMasterPassword: Boolean,
    isIconLoadingDisabled: Boolean,
    isAutofill: Boolean,
    isFido2Creation: Boolean,
    isPremiumUser: Boolean,
    isVfo1FoundationEnabled: Boolean = false,
): VaultItemListingState.DisplayItem =
    VaultItemListingState.DisplayItem(
        id = id.orEmpty(),
        title = name.asText(),
        titleTestTag = "CipherNameLabel",
        secondSubtitle = this.toSecondSubtitle(
            fido2CredentialRpId = login?.fido2Credentials?.firstOrNull()?.rpId,
        ),
        secondSubtitleTestTag = "PasskeySite",
        subtitle = this.subtitle,
        subtitleTestTag = this.toSubtitleTestTag(
            isAutofill = isAutofill,
            isFido2Creation = isFido2Creation,
        ),
        iconData = this.toIconData(
            baseIconUrl = baseIconUrl,
            isIconLoadingDisabled = isIconLoadingDisabled,
            usePasskeyDefaultIcon = (isAutofill || isFido2Creation) &&
                this.isActiveWithFido2Credentials,
        ),
        iconTestTag = this.toIconTestTag(),
        extraIconList = this.toLabelIcons(isVfo1FoundationEnabled = isVfo1FoundationEnabled),
        overflowOptions = this.toOverflowActions(
            hasMasterPassword = hasMasterPassword,
            isPremiumUser = isPremiumUser,
        ),
        optionsTestTag = "CipherOptionsButton",
        isAutofill = isAutofill,
        isCredentialCreation = isFido2Creation,
        shouldShowMasterPasswordReprompt = (reprompt == CipherRepromptType.PASSWORD) &&
            hasMasterPassword,
        itemType = VaultItemListingState
            .DisplayItem
            .ItemType
            .Vault(
                type = this.type.toSdkCipherType(),
            ),
    )

@Suppress("MaxLineLength")
private fun List<CipherListView>.toDisplayItemListDecryptionError(
    isVfo1FoundationEnabled: Boolean = false,
): List<VaultItemListingState.DisplayItem> =
    this.map {
        it.toDisplayItemDecryptionError(isVfo1FoundationEnabled = isVfo1FoundationEnabled)
    }

private fun CipherListView.toDisplayItemDecryptionError(
    isVfo1FoundationEnabled: Boolean = false,
): VaultItemListingState.DisplayItem =
    VaultItemListingState.DisplayItem(
        id = id.orEmpty(),
        title = BitwardenString.error_cannot_decrypt.asText(),
        titleTestTag = "CipherNameLabel",
        secondSubtitle = null,
        secondSubtitleTestTag = null,
        subtitle = null,
        subtitleTestTag = "",
        iconData = IconData.Local(iconRes = BitwardenDrawable.ic_globe),
        iconTestTag = this.toIconTestTag(),
        extraIconList = this.toLabelIcons(isVfo1FoundationEnabled = isVfo1FoundationEnabled),
        overflowOptions = emptyList(),
        optionsTestTag = "CipherOptionsButton",
        isAutofill = false,
        isCredentialCreation = false,
        shouldShowMasterPasswordReprompt = false,
        itemType = VaultItemListingState.DisplayItem.ItemType.DecryptionError,
    )

private fun CipherListView.toSecondSubtitle(fido2CredentialRpId: String?): String? =
    fido2CredentialRpId
        ?.takeIf { this.type is CipherListViewType.Login && it.isNotEmpty() && it != this.name }

private fun CipherListView.toSubtitleTestTag(
    isAutofill: Boolean,
    isFido2Creation: Boolean,
): String =
    if ((isAutofill || isFido2Creation)) {
        if (this.isActiveWithFido2Credentials) "PasskeyName" else "PasswordName"
    } else {
        "CipherSubTitleLabel"
    }

private fun CipherListView.toIconTestTag(): String =
    when (type) {
        is CipherListViewType.Login -> "LoginCipherIcon"
        CipherListViewType.SecureNote -> "SecureNoteCipherIcon"
        is CipherListViewType.Card -> "CardCipherIcon"
        CipherListViewType.Identity -> "IdentityCipherIcon"
        CipherListViewType.SshKey -> "SshKeyCipherIcon"
        is CipherListViewType.BankAccount -> "BankAccountCipherIcon"
        CipherListViewType.DriversLicense -> "LicenseCipherIcon"
        CipherListViewType.Passport -> "PassportCipherIcon"
    }

private fun CipherListView.toIconData(
    baseIconUrl: String,
    isIconLoadingDisabled: Boolean,
    usePasskeyDefaultIcon: Boolean,
): IconData {
    return when (this.type) {
        is CipherListViewType.Login -> {
            login?.uris.toLoginIconData(
                baseIconUrl = baseIconUrl,
                isIconLoadingDisabled = isIconLoadingDisabled,
                usePasskeyDefaultIcon = usePasskeyDefaultIcon,
            )
        }

        else -> {
            IconData.Local(iconRes = this.type.iconRes)
        }
    }
}

@get:DrawableRes
private val CipherListViewType.iconRes: Int
    get() = when (this) {
        is CipherListViewType.Login -> BitwardenDrawable.ic_globe
        CipherListViewType.SecureNote -> BitwardenDrawable.ic_note
        is CipherListViewType.Card -> BitwardenDrawable.ic_payment_card
        CipherListViewType.Identity -> BitwardenDrawable.ic_id_card
        CipherListViewType.SshKey -> BitwardenDrawable.ic_ssh_key
        is CipherListViewType.BankAccount -> BitwardenDrawable.ic_bank_account
        CipherListViewType.DriversLicense -> BitwardenDrawable.ic_drivers_license
        CipherListViewType.Passport -> BitwardenDrawable.ic_passport
    }

private fun List<CipherListView>.applyFilters(
    itemListingType: VaultItemListingState.ItemListingType.Vault,
    vaultFilterType: VaultFilterType,
): List<CipherListView> = this
    .filter { it.determineListingPredicate(itemListingType) }
    .toFilteredList(vaultFilterType)
