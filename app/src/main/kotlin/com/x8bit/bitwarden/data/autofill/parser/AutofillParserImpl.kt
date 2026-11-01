package com.x8bit.bitwarden.data.autofill.parser

import android.app.assist.AssistStructure
import android.service.autofill.FillRequest
import android.view.autofill.AutofillId
import com.bitwarden.core.data.manager.model.FlagKey
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.model.ViewNodeTraversalData
import com.x8bit.bitwarden.data.autofill.parser.util.logViewNode
import com.x8bit.bitwarden.data.autofill.parser.util.logWindowNodeEnd
import com.x8bit.bitwarden.data.autofill.parser.util.logWindowNodeStart
import com.x8bit.bitwarden.data.autofill.util.buildPackageNameOrNull
import com.x8bit.bitwarden.data.autofill.util.buildUriOrNull
import com.x8bit.bitwarden.data.autofill.util.getInlinePresentationSpecs
import com.x8bit.bitwarden.data.autofill.util.getMaxInlineSuggestionsCount
import com.x8bit.bitwarden.data.autofill.util.isEmailField
import com.x8bit.bitwarden.data.autofill.util.isPhoneField
import com.x8bit.bitwarden.data.autofill.util.toAutofillView
import com.x8bit.bitwarden.data.autofill.util.website
import com.x8bit.bitwarden.data.platform.manager.FeatureFlagManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import timber.log.Timber

/**
 * A list of URIs that should never be autofilled.
 */
private val BLOCK_LISTED_URIS: List<String> = listOf(
    "androidapp://android",
    "androidapp://com.android.settings",
    "androidapp://com.x8bit.bitwarden",
    "androidapp://com.x8bit.bitwarden.beta",
    "androidapp://com.x8bit.bitwarden.dev",
    "androidapp://app.secretdoor",
    "androidapp://app.secretdoor.beta",
    "androidapp://app.secretdoor.dev",
    "androidapp://com.oneplus.applocker",
)

/**
 * A map of package ids and the known associated id entry for their url bar.
 */
private val URL_BARS: Map<String, String> = mapOf(
    // Edge Browser Variants
    "com.microsoft.emmx" to "url_bar",
    "com.microsoft.emmx.beta" to "url_bar",
    "com.microsoft.emmx.canary" to "url_bar",
    "com.microsoft.emmx.dev" to "url_bar",
    // Samsung Internet Browser Variants
    "com.sec.android.app.sbrowser" to "location_bar_edit_text",
    "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
    // Opera Browser Variants
    "com.opera.browser" to "url_bar",
    "com.opera.browser.beta" to "url_bar",
    // Brave Browser Variants
    "com.brave.browser" to "url_bar",
    "com.brave.browser_beta" to "url_bar",
    "com.brave.browser_nightly" to "url_bar",
)

/**
 * The default [AutofillParser] implementation for the app. This is a tool for parsing autofill data
 * from the OS into domain models.
 */
internal class AutofillParserImpl(
    private val settingsRepository: SettingsRepository,
    private val featureFlagManager: FeatureFlagManager,
) : AutofillParser {
    override fun parse(
        autofillAppInfo: AutofillAppInfo,
        fillRequest: FillRequest,
    ): AutofillRequest =
        // Attempt to get the most recent autofill context.
        fillRequest
            .fillContexts
            .lastOrNull()
            ?.structure
            ?.let { assistStructure ->
                parseInternal(
                    assistStructure = assistStructure,
                    autofillAppInfo = autofillAppInfo,
                    fillRequest = fillRequest,
                )
            }
            ?: AutofillRequest.Unfillable

    override fun parse(
        autofillAppInfo: AutofillAppInfo,
        assistStructure: AssistStructure,
    ): AutofillRequest =
        parseInternal(
            assistStructure = assistStructure,
            autofillAppInfo = autofillAppInfo,
            fillRequest = null,
        )

    /**
     * Parse the [AssistStructure] into an [AutofillRequest].
     */
    @Suppress("LongMethod")
    private fun parseInternal(
        assistStructure: AssistStructure,
        autofillAppInfo: AutofillAppInfo,
        fillRequest: FillRequest?,
    ): AutofillRequest {
        Timber.d("Parsing AssistStructure -- ${fillRequest?.id}")
        // Identity classification/fulfillment ship together: until this flag is on, every node
        // must classify exactly as it did before identity heuristics existed, so behaviors like
        // updateForMissingUsernameFields's Unused-only promotion keep working unchanged.
        val isIdentityAutofillEnabled = featureFlagManager.getFeatureFlag(FlagKey.IdentityAutofill)
        // Parse the `assistStructure` into internal models.
        val traversalDataList = assistStructure.traverse(
            isIdentityAutofillEnabled = isIdentityAutofillEnabled,
        )
        val urlBarWebsite = traversalDataList
            .flatMap { it.urlBarWebsites }
            .firstOrNull()
        // Heuristic views: the focused node's candidates with unfillable (Unused) fields removed,
        // falling back to all fillable views when nothing has focus.
        val autofillViews = traversalDataList
            .selectCandidateAutofillViews(urlBarWebsite = urlBarWebsite) {
                it !is AutofillView.Unused
            }

        // Find the focused view, or fallback to the first fillable item on the screen (so
        // we at least have something to hook into).
        val focusedView = autofillViews.firstFocusedOrNull()
            ?: return AutofillRequest.Unfillable

        val packageName = traversalDataList.buildPackageNameOrNull(
            assistStructure = assistStructure,
        )
        val uri = focusedView.buildUriOrNull(packageName = packageName)

        // The view is unfillable if the URI is block listed.
        if ((settingsRepository.blockedAutofillUris + BLOCK_LISTED_URIS).contains(uri)) {
            return AutofillRequest.Unfillable
        }

        // Choose the first focused partition of data for fulfillment.
        val partition = when (focusedView) {
            is AutofillView.Card -> {
                AutofillPartition.Card(
                    views = autofillViews.filterIsInstance<AutofillView.Card>(),
                )
            }

            is AutofillView.Login -> {
                AutofillPartition.Login(
                    views = autofillViews.filterIsInstance<AutofillView.Login>(),
                )
            }

            is AutofillView.Identity -> {
                // Gated behind FlagKey.IdentityAutofill until the feature is ready for
                // production; disabled matches this partition's pre-feature behavior.
                if (!isIdentityAutofillEnabled) return AutofillRequest.Unfillable
                AutofillPartition.Identity(
                    views = autofillViews.filterIsInstance<AutofillView.Identity>(),
                )
            }

            is AutofillView.Unused -> {
                // This will never happen: the heuristic path filters out Unused views.
                return AutofillRequest.Unfillable
            }
        }
        // Flatten the ignorable autofill ids.
        val ignoreAutofillIds = traversalDataList
            .map { it.ignoreAutofillIds }
            .flatten()

        // Get inline information if available
        val isInlineAutofillEnabled = settingsRepository.isInlineAutofillEnabled
        Timber.d("Autofill request isInlineEnabled=$isInlineAutofillEnabled -- ${fillRequest?.id}")
        val maxInlineSuggestionsCount = fillRequest.getMaxInlineSuggestionsCount(
            autofillAppInfo = autofillAppInfo,
            isInlineAutofillEnabled = isInlineAutofillEnabled,
        )
        val inlinePresentationSpecs = fillRequest.getInlinePresentationSpecs(
            autofillAppInfo = autofillAppInfo,
            isInlineAutofillEnabled = isInlineAutofillEnabled,
        )

        return AutofillRequest.Fillable(
            inlinePresentationSpecs = inlinePresentationSpecs,
            ignoreAutofillIds = ignoreAutofillIds,
            maxInlineSuggestionsCount = maxInlineSuggestionsCount,
            packageName = packageName,
            partition = partition,
            uri = uri,
        )
    }
}

/**
 * Traverse the [AssistStructure] and convert it into a list of [ViewNodeTraversalData]s.
 */
private fun AssistStructure.traverse(
    isIdentityAutofillEnabled: Boolean,
): List<ViewNodeTraversalData> =
    (0 until windowNodeCount)
        .map { getWindowNodeAt(it) }
        .mapNotNull { windowNode ->
            windowNode.logWindowNodeStart()
            windowNode
                .rootViewNode
                ?.traverse(
                    parentWebsite = null,
                    isIdentityAutofillEnabled = isIdentityAutofillEnabled,
                    depth = 1,
                )
                ?.updateForMissingPasswordFields()
                ?.updateForMissingUsernameFields()
                .also { windowNode.logWindowNodeEnd() }
        }

/**
 * Selects the autofill views from the node that currently has focus, or falls back to all
 * fillable views if nothing has focus. The optional [predicate] filters the views *before* the
 * emptiness/fallback check, so callers that only want fillable fields (e.g. excluding
 * [AutofillView.Unused]) still get the multi-window fallback applied to the filtered set. By
 * default no views are filtered out.
 */
private fun List<ViewNodeTraversalData>.selectCandidateAutofillViews(
    urlBarWebsite: String?,
    predicate: (AutofillView) -> Boolean = { true },
): List<AutofillView> {
    val viewsLists = map { it.autofillViews }
    val candidates = viewsLists
        .filter { views -> views.any { it.data.isFocused } }
        .flatten()
        .filter(predicate)
        .takeUnless { it.isEmpty() }
        ?: viewsLists.flatten().filter(predicate)
    return candidates.map { it.updateWebsiteIfNecessary(website = urlBarWebsite) }
}

/**
 * Returns the focused [AutofillView], preferring a non-Identity view so a real Identity partition
 * is only built when Identity is the only classification available. Falls back to the first entry
 * if nothing is focused.
 */
private fun List<AutofillView>.firstFocusedOrNull(): AutofillView? {
    return this.firstOrNull { it.data.isFocused && it !is AutofillView.Identity }
        ?: this.firstOrNull { it.data.isFocused }
        ?: this.firstOrNull { it !is AutofillView.Identity }
        ?: this.firstOrNull()
}

/**
 * This helper function updates the [ViewNodeTraversalData] if necessary for missing password
 * fields that were marked invalid because they contained a specific `hint` or `idEntry`. If the
 * current `ViewNodeTraversalData` contains at least one password fields, we do not add any fields.
 */
private fun ViewNodeTraversalData.updateForMissingPasswordFields(): ViewNodeTraversalData =
    if (this.autofillViews.none { it is AutofillView.Login.Password }) {
        this.copyAndMapAutofillViews { _, autofillView ->
            if (autofillView is AutofillView.Unused && autofillView.data.hasPasswordTerms) {
                AutofillView.Login.Password(data = autofillView.data)
            } else {
                autofillView
            }
        }
    } else {
        // We already have password fields available, so no need to add more.
        this
    }

/**
 * This helper function updates the [ViewNodeTraversalData] if necessary for missing username
 * fields that could have been missed. If the current `ViewNodeTraversalData` contains password
 * fields but no username fields, we check to see if there are any unused fields directly above
 * the password fields and we assume that those are the missing username fields.
 */
private fun ViewNodeTraversalData.updateForMissingUsernameFields(): ViewNodeTraversalData {
    val passwordPositions = this.autofillViews.mapIndexedNotNull { index, autofillView ->
        (autofillView as? AutofillView.Login.Password)?.let { index }
    }
    return if (passwordPositions.any() &&
        this.autofillViews.none { it is AutofillView.Login.Username }
    ) {
        this.copyAndMapAutofillViews { index, autofillView ->
            if (autofillView is AutofillView.Unused && passwordPositions.contains(index + 1)) {
                AutofillView.Login.Username(data = autofillView.data)
            } else {
                autofillView
            }
        }
    } else {
        // We already have username fields available or there are no password fields, so no need
        // to search for them.
        this
    }
}

/**
 * This helper function loops through all the [ViewNodeTraversalData.autofillViews] and returns the
 * fully updated `ViewNodeTraversalData`.
 */
private fun ViewNodeTraversalData.copyAndMapAutofillViews(
    mapper: (index: Int, autofillView: AutofillView) -> AutofillView,
): ViewNodeTraversalData {
    val updatedAutofillViews = autofillViews.mapIndexed(mapper)
    val previousUnusedIds = autofillViews
        .filterIsInstance<AutofillView.Unused>()
        .map { it.data.autofillId }
        .toSet()
    val currentUnusedIds = updatedAutofillViews
        .filterIsInstance<AutofillView.Unused>()
        .map { it.data.autofillId }
        .toSet()
    val unignoredAutofillIds = previousUnusedIds - currentUnusedIds
    return this.copy(
        autofillViews = updatedAutofillViews,
        ignoreAutofillIds = this.ignoreAutofillIds - unignoredAutofillIds,
    )
}

/**
 * Recursively traverse this [AssistStructure.ViewNode] and all of its descendants. Convert the
 * data into [ViewNodeTraversalData].
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun AssistStructure.ViewNode.traverse(
    parentWebsite: String?,
    isIdentityAutofillEnabled: Boolean,
    depth: Int,
): ViewNodeTraversalData {
    // Set up mutable lists for collecting valid AutofillViews and ignorable view ids.
    val mutableAutofillViewList: MutableList<AutofillView> = mutableListOf()
    val mutableIgnoreAutofillIdList: MutableList<AutofillId> = mutableListOf()
    // Tracks autofill IDs already claimed by a non-Unused view so that child nodes whose IDs
    // were redirected to by a container ancestor are not added a second time.
    val claimedAutofillIds: MutableSet<AutofillId> = mutableSetOf()
    // OS sometimes defaults node.idPackage to "android", which is not a valid
    // package name so it is ignored to prevent auto-filling unknown applications.
    var storedIdPackage: String? = this.idPackage?.takeUnless { it.isBlank() || it == "android" }
    val storedUrlBarId = storedIdPackage?.let { URL_BARS[it] }
    val storedUrlBarWebsites: MutableList<String> = this
        .website
        ?.takeIf { _ -> storedUrlBarId != null && storedUrlBarId == this.idEntry }
        ?.let { mutableListOf(it) }
        ?: mutableListOf()

    // Try converting this `ViewNode` into an `AutofillView`. If a valid instance is returned, add
    // it to the list. Otherwise, ignore the `AutofillId` associated with this `ViewNode`.
    this
        .toAutofillView(
            parentWebsite = parentWebsite,
            isIdentityAutofillEnabled = isIdentityAutofillEnabled,
        )
        ?.also { view ->
            if (view !is AutofillView.Unused) {
                claimedAutofillIds.add(view.data.autofillId)
            }
            mutableAutofillViewList.add(view)

            if (isIdentityAutofillEnabled) {
                // An email-hinted or email-heuristic field is offered as both a Login candidate
                // (above) and an Identity candidate, since the two partitions aren't mutually
                // exclusive for this field. Reuses the same (container-redirect-corrected) data as
                // the primary view rather than re-deriving it.
                if (view is AutofillView.Login.Username && this.isEmailField) {
                    mutableAutofillViewList.add(AutofillView.Identity.Email(data = view.data))
                }

                // A phone-hinted or phone-heuristic field resolves to Login.Username above (see
                // supportedAutofillHint), so it needs the same dual-classification as email.
                if (view is AutofillView.Login.Username && this.isPhoneField) {
                    mutableAutofillViewList.add(AutofillView.Identity.PhoneFull(data = view.data))
                }
            }
        }
        ?: autofillId?.run(mutableIgnoreAutofillIdList::add)
    this.logViewNode(depth = depth)
    // Recursively traverse all of this view node's children.
    for (i in 0 until childCount) {
        // Extract the traversal data from each child view node and add it to the lists.
        getChildAt(i)
            .traverse(
                parentWebsite = website,
                isIdentityAutofillEnabled = isIdentityAutofillEnabled,
                depth = depth + 1,
            )
            .let { viewNodeTraversalData ->
                // Ids already claimed by an ancestor's own view (e.g. a container-redirect
                // target) before this child's results are considered. A primary always precedes
                // its Identity dual-classification sibling in this same child's results, so the
                // sibling's id is still fresh here and passes the check below.
                val idsClaimedByAncestor = claimedAutofillIds.toSet()
                // Flatten child views into this node, keeping the first view seen for each autofill
                // id and dropping later duplicates (e.g. a container-redirect leftover).
                viewNodeTraversalData.autofillViews
                    .filter { view ->
                        val id = view.data.autofillId
                        when (view) {
                            // Never claims an id, so a real view for that id can still be kept.
                            is AutofillView.Unused -> id !in claimedAutofillIds
                            // Kept if the id is fresh, i.e. it's a dual-classification sibling of
                            // a primary view from this same child. Dropped if the id was already
                            // claimed by an ancestor's own view -- that means this is a stale
                            // container-redirect leftover, not an intentional sibling.
                            is AutofillView.Identity -> {
                                if (id !in idsClaimedByAncestor) {
                                    claimedAutofillIds.add(id)
                                    true
                                } else {
                                    false
                                }
                            }
                            // Kept only the first time its id is seen (add returns false if known).
                            else -> claimedAutofillIds.add(id)
                        }
                    }
                    .forEach(mutableAutofillViewList::add)
                viewNodeTraversalData.ignoreAutofillIds
                    .filter { it !in claimedAutofillIds }
                    .forEach(mutableIgnoreAutofillIdList::add)

                // Get the first non-null idPackage.
                if (storedIdPackage == null) {
                    storedIdPackage = viewNodeTraversalData.idPackage
                }
                // Add all url bar websites. We will deal with this later if
                // there is somehow more than one.
                storedUrlBarWebsites.addAll(viewNodeTraversalData.urlBarWebsites)
            }
    }

    // Build a new traversal data structure with this view node's data, and that of all of its
    // descendant's.
    return ViewNodeTraversalData(
        autofillViews = mutableAutofillViewList,
        idPackage = storedIdPackage,
        urlBarWebsites = storedUrlBarWebsites,
        ignoreAutofillIds = mutableIgnoreAutofillIdList,
    )
}

/**
 * This updates the underlying [AutofillView.data] with the given [website] if it does not already
 * have a website associated with it.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun AutofillView.updateWebsiteIfNecessary(website: String?): AutofillView {
    val site = website ?: return this
    if (this.data.website != null) return this
    return when (this) {
        is AutofillView.Card.Brand -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.CardholderName -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.ExpirationDate -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.ExpirationMonth -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.ExpirationYear -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.Number -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Card.SecurityCode -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Login.Email -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Login.Password -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Login.Username -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.AddressCountry -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.AddressLocality -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.AddressRegion -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.AddressStreet -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.AddressExtended -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.Company -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.Email -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.LicenseNumber -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.PassportNumber -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.PersonNameFamily -> {
            this.copy(data = this.data.copy(website = site))
        }

        is AutofillView.Identity.PersonNameFull -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.PersonNameGiven -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.PersonNameMiddle -> {
            this.copy(data = this.data.copy(website = site))
        }

        is AutofillView.Identity.PersonNamePrefix -> {
            this.copy(data = this.data.copy(website = site))
        }

        is AutofillView.Identity.PhoneFull -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.PostalAddressFull -> {
            this.copy(data = this.data.copy(website = site))
        }

        is AutofillView.Identity.PostalCode -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Identity.Ssn -> this.copy(data = this.data.copy(website = site))
        is AutofillView.Unused -> this.copy(data = this.data.copy(website = site))
    }
}
