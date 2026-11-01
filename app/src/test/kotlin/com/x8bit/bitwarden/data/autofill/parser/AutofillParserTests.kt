package com.x8bit.bitwarden.data.autofill.parser

import android.app.assist.AssistStructure
import android.net.Uri.parse
import android.service.autofill.FillContext
import android.service.autofill.FillRequest
import android.view.View
import android.view.autofill.AutofillId
import android.widget.inline.InlinePresentationSpec
import com.bitwarden.core.data.manager.model.FlagKey
import com.x8bit.bitwarden.data.autofill.model.AutofillAppInfo
import com.x8bit.bitwarden.data.autofill.model.AutofillPartition
import com.x8bit.bitwarden.data.autofill.model.AutofillRequest
import com.x8bit.bitwarden.data.autofill.model.AutofillView
import com.x8bit.bitwarden.data.autofill.model.ViewNodeTraversalData
import com.x8bit.bitwarden.data.autofill.util.buildPackageNameOrNull
import com.x8bit.bitwarden.data.autofill.util.buildUriOrNull
import com.x8bit.bitwarden.data.autofill.util.getInlinePresentationSpecs
import com.x8bit.bitwarden.data.autofill.util.getMaxInlineSuggestionsCount
import com.x8bit.bitwarden.data.autofill.util.toAutofillView
import com.x8bit.bitwarden.data.autofill.util.toAutofillViewData
import com.x8bit.bitwarden.data.autofill.util.website
import com.x8bit.bitwarden.data.platform.manager.FeatureFlagManager
import com.x8bit.bitwarden.data.platform.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class AutofillParserTests {
    private lateinit var parser: AutofillParser

    private val autofillAppInfo: AutofillAppInfo = mockk()
    private val assistStructure: AssistStructure = mockk()
    private val cardAutofillHint = View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR
    private val cardAutofillId: AutofillId = mockk()
    private val cardViewNode: AssistStructure.ViewNode = mockk {
        every { this@mockk.autofillHints } returns arrayOf(cardAutofillHint)
        every { this@mockk.autofillId } returns cardAutofillId
        every { this@mockk.childCount } returns 0
        every { this@mockk.htmlInfo } returns mockk(relaxed = true)
        every { this@mockk.idPackage } returns ID_PACKAGE
        every { this@mockk.idEntry } returns null
        every { this@mockk.hint } returns null
    }
    private val loginAutofillHint = View.AUTOFILL_HINT_USERNAME
    private val loginAutofillId: AutofillId = mockk()
    private val loginViewNode: AssistStructure.ViewNode = mockk {
        every { this@mockk.autofillHints } returns arrayOf(loginAutofillHint)
        every { this@mockk.autofillId } returns loginAutofillId
        every { this@mockk.childCount } returns 0
        every { this@mockk.htmlInfo } returns mockk(relaxed = true)
        every { this@mockk.idPackage } returns ID_PACKAGE
        every { this@mockk.idEntry } returns null
        every { this@mockk.hint } returns null
    }
    private val identityAutofillHint = View.AUTOFILL_HINT_NAME
    private val identityAutofillId: AutofillId = mockk()
    private val identityViewNode: AssistStructure.ViewNode = mockk {
        every { this@mockk.autofillHints } returns arrayOf(identityAutofillHint)
        every { this@mockk.autofillId } returns identityAutofillId
        every { this@mockk.childCount } returns 0
        every { this@mockk.htmlInfo } returns mockk(relaxed = true)
        every { this@mockk.idPackage } returns ID_PACKAGE
        every { this@mockk.idEntry } returns null
        every { this@mockk.hint } returns null
    }
    private val cardWindowNode: AssistStructure.WindowNode = mockk {
        every { this@mockk.rootViewNode } returns cardViewNode
    }
    private val loginWindowNode: AssistStructure.WindowNode = mockk {
        every { this@mockk.rootViewNode } returns loginViewNode
    }
    private val identityWindowNode: AssistStructure.WindowNode = mockk {
        every { this@mockk.rootViewNode } returns identityViewNode
    }
    private val fillContext: FillContext = mockk {
        every { this@mockk.structure } returns assistStructure
    }
    private val fillRequest: FillRequest = mockk {
        every { id } returns 55
        every { this@mockk.fillContexts } returns listOf(fillContext)
    }
    private val inlinePresentationSpecs: List<InlinePresentationSpec> = mockk()
    private val settingsRepository: SettingsRepository = mockk {
        every { isInlineAutofillEnabled } answers { mockIsInlineAutofillEnabled }
        every { blockedAutofillUris } returns emptyList()
    }
    private val mutableIdentityAutofillFlagFlow = MutableStateFlow(false)
    private val featureFlagManager: FeatureFlagManager = mockk {
        every {
            getFeatureFlag(FlagKey.IdentityAutofill)
        } answers {
            mutableIdentityAutofillFlagFlow.value
        }
    }

    private var mockIsInlineAutofillEnabled = true

    @BeforeEach
    fun setup() {
        // toAutofillView, website, and toAutofillViewData all compile into the same
        // ViewNodeExtensionsKt class, so one mockkStatic call covers all three.
        mockkStatic(AssistStructure.ViewNode::toAutofillView)
        // Default stub for toAutofillViewData (same mocked class — no separate mockkStatic needed).
        // Note: this is a mocked extension function, so the receiver occupies arg position 0 --
        // `autofillId` and `website` are therefore the second and third args, not first/second.
        every {
            any<AssistStructure.ViewNode>().toAutofillViewData(autofillId = any(), website = any())
        } answers {
            AutofillView.Data(
                autofillId = secondArg(),
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = thirdArg(),
            )
        }
        mockkStatic(
            FillRequest::getMaxInlineSuggestionsCount,
            FillRequest::getInlinePresentationSpecs,
            AutofillView::buildUriOrNull,
            List<ViewNodeTraversalData>::buildPackageNameOrNull,
        )
        every { cardViewNode.website } returns WEBSITE
        every { loginViewNode.website } returns WEBSITE
        every { identityViewNode.website } returns WEBSITE
        every {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
        } returns inlinePresentationSpecs
        every {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
        } returns emptyList()
        every {
            null.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
        } returns emptyList()
        every {
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
        } returns MAX_INLINE_SUGGESTION_COUNT
        every {
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
        } returns 0
        every {
            null.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
        } returns 0
        every {
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
        } returns PACKAGE_NAME
        every { any<AutofillView>().buildUriOrNull(PACKAGE_NAME) } returns URI
        parser = AutofillParserImpl(
            settingsRepository = settingsRepository,
            featureFlagManager = featureFlagManager,
        )
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(AssistStructure.ViewNode::toAutofillView)
        unmockkStatic(
            FillRequest::getMaxInlineSuggestionsCount,
            FillRequest::getInlinePresentationSpecs,
            AutofillView::buildUriOrNull,
            List<ViewNodeTraversalData>::buildPackageNameOrNull,
        )
    }

    @Test
    fun `parse should return Unfillable when no contexts`() {
        // Setup
        val expected = AutofillRequest.Unfillable
        every { fillRequest.fillContexts } returns emptyList()

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
    }

    @Test
    fun `parse should return Unfillable when windowNodeCount is 0`() {
        // Setup
        val expected = AutofillRequest.Unfillable
        every { assistStructure.windowNodeCount } returns 0

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should return Fillable with website in AutofillView from url bar for compatible browser`() {
        // Setup
        val website = "https://m.facebook.com"
        val packageName = "com.microsoft.emmx"
        every {
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
        } returns packageName
        every { assistStructure.windowNodeCount } returns 2
        // Override the idPackage to be Edge's package name.
        every { loginViewNode.idPackage } returns packageName
        every { assistStructure.getWindowNodeAt(0) } returns loginWindowNode
        val urlBarNode: AssistStructure.ViewNode = mockk {
            every { autofillHints } returns emptyArray()
            every { autofillId } returns null
            every { childCount } returns 0
            every { idEntry } returns "url_bar"
            every { idPackage } returns packageName
            every { webDomain } returns "m.facebook.com"
            every { webScheme } returns null
        }
        val urlBarWindowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns urlBarNode
        }
        every { assistStructure.getWindowNodeAt(1) } returns urlBarWindowNode
        val loginAutofillView: AutofillView.Login.Username = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = null,
            ),
        )
        every {
            loginViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns loginAutofillView
        val autofillPartition = AutofillPartition.Login(
            views = listOf(
                loginAutofillView.copy(data = loginAutofillView.data.copy(website = website)),
            ),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = packageName,
            partition = autofillPartition,
            uri = website,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(packageName)
        }
    }

    @Test
    fun `parse should return Fillable when at least one node valid, ignores the invalid nodes`() {
        // Setup
        val childAutofillHint = View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH
        val childAutofillId: AutofillId = mockk()
        val childViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns arrayOf(childAutofillHint)
            every { this@mockk.autofillId } returns childAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns null
            every { this@mockk.isFocused } returns false
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns null
            every { this@mockk.website } returns null
        }
        // `invalidChildViewNode` simulates the OS assigning a node's idPackage to "android", which
        // is not considered a valid app package name.
        val invalidChildAutofillHint = View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE
        val invalidChildAutofillId: AutofillId = mockk()
        val invalidChildViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns arrayOf(invalidChildAutofillHint)
            every { this@mockk.autofillId } returns invalidChildAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE_ANDROID
            every { this@mockk.isFocused } returns false
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns null
            every { this@mockk.website } returns null
        }
        val parentAutofillHint = View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR
        val parentAutofillId: AutofillId = mockk()
        val parentAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = parentAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val parentViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns arrayOf(parentAutofillHint)
            every { this@mockk.autofillId } returns parentAutofillId
            every { this@mockk.idPackage } returns null
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns parentAutofillView
            every { this@mockk.childCount } returns 2
            every { this@mockk.getChildAt(0) } returns childViewNode
            every { this@mockk.getChildAt(1) } returns invalidChildViewNode
            every { this@mockk.website } returns null
        }
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns parentViewNode
        }
        val autofillPartition = AutofillPartition.Card(
            views = listOf(parentAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = listOf(childAutofillId, invalidChildAutofillId),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            partition = autofillPartition,
            packageName = PACKAGE_NAME,
            uri = URI,
        )
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
        verify(exactly = 0) {
            any<AutofillView>().buildUriOrNull(ID_PACKAGE_ANDROID)
        }
    }

    @Test
    fun `parse should choose AutofillPartition Card when a Card view is focused`() {
        // Setup
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(card = cardAutofillView, login = loginAutofillView)
        val autofillPartition = AutofillPartition.Card(
            views = listOf(cardAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Test
    fun `parse should choose AutofillPartition Login when a Login view is focused`() {
        // Setup
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(card = cardAutofillView, login = loginAutofillView)
        val autofillPartition = AutofillPartition.Login(
            views = listOf(loginAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Test
    fun `parse should choose AutofillPartition Identity when an Identity view is focused`() {
        // Setup
        mutableIdentityAutofillFlagFlow.value = true
        val identityAutofillView: AutofillView.Identity = AutofillView.Identity.PersonNameGiven(
            data = AutofillView.Data(
                autofillId = identityAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(login = loginAutofillView, identity = identityAutofillView)
        val autofillPartition = AutofillPartition.Identity(
            views = listOf(identityAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should return Unfillable when an Identity view is focused and IdentityAutofill is disabled`() {
        // Setup
        every { featureFlagManager.getFeatureFlag(FlagKey.IdentityAutofill) } returns false
        val identityAutofillView: AutofillView.Identity = AutofillView.Identity.PersonNameGiven(
            data = AutofillView.Data(
                autofillId = identityAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(login = loginAutofillView, identity = identityAutofillView)

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(AutofillRequest.Unfillable, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should keep the Identity dual-classification sibling of a nested email field in the Identity partition`() {
        // Setup — a registration-style form: a focused Name field plus a (non-focused) email
        // field, both nested under a container. The email field is classified as Login.Username
        // and gets a dual-classification Identity.Email sibling (same autofillId). Focusing the
        // Name field builds an Identity partition, which must include the email's Identity.Email
        // sibling so a whole-identity fill also populates the email field. Regression guard: the
        // container-redirect dedup must not drop that sibling just because its id is already
        // claimed by the Login.Username primary.
        mutableIdentityAutofillFlagFlow.value = true
        val nameAutofillId: AutofillId = mockk()
        val emailAutofillId: AutofillId = mockk()
        val nameView: AutofillView.Identity = AutofillView.Identity.PersonNameGiven(
            data = AutofillView.Data(
                autofillId = nameAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val emailLoginView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = emailAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val nameViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillId } returns nameAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns null
            every { this@mockk.website } returns null
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns nameView
        }
        val emailViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillId } returns emailAutofillId
            every { this@mockk.autofillHints } returns arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns null
            every { this@mockk.idEntry } returns null
            every { this@mockk.hint } returns null
            every { this@mockk.htmlInfo } returns mockk(relaxed = true)
            every { this@mockk.website } returns null
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns emailLoginView
        }
        val rootAutofillId: AutofillId = mockk()
        val rootViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillId } returns rootAutofillId
            every { this@mockk.childCount } returns 2
            every { this@mockk.getChildAt(0) } returns nameViewNode
            every { this@mockk.getChildAt(1) } returns emailViewNode
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns null
            every {
                this@mockk.toAutofillView(
                    parentWebsite = any(),
                    isIdentityAutofillEnabled = any(),
                )
            } returns null
        }
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns rootViewNode
        }
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = listOf(rootAutofillId),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Identity(
                views = listOf(
                    nameView,
                    AutofillView.Identity.Email(data = emailLoginView.data),
                ),
            ),
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should have Password AutofillView when the Password field is invalid, contains no other Password fields, and contains a password term`() {
        // Setup
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns loginWindowNode
        val unusedAutofillView: AutofillView.Unused = AutofillView.Unused(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = true,
                website = URI,
            ),
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Password(
            data = unusedAutofillView.data,
        )
        val autofillPartition = AutofillPartition.Login(
            views = listOf(loginAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )
        every {
            loginViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns unusedAutofillView

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should have Username and Password AutofillView when the Username field is not identifiable but directly above the Password field in the hierarchy`() {
        // Setup
        val hiddenUserNameViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns loginAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
        }
        val passwordAutofillId = mockk<AutofillId>()
        val passwordViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns passwordAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
        }
        val rootAutofillId = mockk<AutofillId>()
        val rootViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns rootAutofillId
            every { this@mockk.childCount } returns 2
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
            every { this@mockk.getChildAt(0) } returns hiddenUserNameViewNode
            every { this@mockk.getChildAt(1) } returns passwordViewNode
        }
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns rootViewNode
        }
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode
        val unusedAutofillView: AutofillView.Unused = AutofillView.Unused(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginUsernameAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginPasswordAutofillView: AutofillView.Login = AutofillView.Login.Password(
            data = AutofillView.Data(
                autofillId = passwordAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val autofillPartition = AutofillPartition.Login(
            views = listOf(loginUsernameAutofillView, loginPasswordAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = listOf(rootAutofillId),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )
        every {
            rootViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns null
        every {
            hiddenUserNameViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns unusedAutofillView
        every {
            passwordViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns loginPasswordAutofillView

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Test
    fun `parse should choose first focused AutofillView for partition when there are multiple`() {
        // Setup
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val identityAutofillView = AutofillView.Identity.PersonNameGiven(
            data = AutofillView.Data(
                autofillId = identityAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = FILL_ASSIST_URI,
            ),
        )
        setupAssistStructure(
            card = cardAutofillView,
            login = loginAutofillView,
            identity = identityAutofillView,
        )
        val autofillPartition = AutofillPartition.Card(
            views = listOf(cardAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should choose first fillable AutofillView for partition when there is no focused view`() {
        // Setup
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(card = cardAutofillView, login = loginAutofillView)
        val autofillPartition = AutofillPartition.Card(
            views = listOf(cardAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should fall back to another window's fillable view when the focused window yields only Unused views`() {
        // Setup: two window nodes. The focused window (e.g. a browser url bar EditText with no
        // hint) contributes only an Unused view, while a second, unfocused window holds the real
        // Login.Username field -- a shape that occurs for the browsers in URL_BARS. The focused
        // window's emptiness check must run AFTER Unused views are filtered out, so the request
        // falls back to the fillable view in the other window instead of short-circuiting to
        // Unfillable. Fill-assist stays disabled (default), so there is no rescue path.
        val urlBarAutofillId: AutofillId = mockk()
        val urlBarViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns urlBarAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.htmlInfo } returns mockk(relaxed = true)
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.idEntry } returns null
            every { this@mockk.website } returns null
        }
        val urlBarWindowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns urlBarViewNode
        }
        every { assistStructure.windowNodeCount } returns 2
        every { assistStructure.getWindowNodeAt(0) } returns urlBarWindowNode
        every { assistStructure.getWindowNodeAt(1) } returns loginWindowNode
        val unusedFocusedView = AutofillView.Unused(
            data = AutofillView.Data(
                autofillId = urlBarAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = null,
            ),
        )
        val loginAutofillView = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        every {
            urlBarViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns unusedFocusedView
        every {
            loginViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns loginAutofillView

        // Test
        val actual = parser.parse(autofillAppInfo = autofillAppInfo, fillRequest = fillRequest)

        // Verify: the request falls back to the fillable Login.Username in the unfocused window.
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Login(views = listOf(loginAutofillView)),
            uri = URI,
        )
        assertEquals(expected, actual)
    }

    @Test
    fun `parse should return empty inline suggestions when inline autofill is disabled`() {
        // Setup
        mockIsInlineAutofillEnabled = false
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(card = cardAutofillView, login = loginAutofillView)
        val autofillPartition = AutofillPartition.Card(
            views = listOf(cardAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = emptyList(),
            maxInlineSuggestionsCount = 0,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Test
    fun `parse should return empty inline suggestions when parsing an AssistStructure directly`() {
        // Setup
        mockIsInlineAutofillEnabled = false
        val cardAutofillView: AutofillView.Card = AutofillView.Card.ExpirationMonth(
            data = AutofillView.Data(
                autofillId = cardAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
            monthValue = null,
        )
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(card = cardAutofillView, login = loginAutofillView)
        val autofillPartition = AutofillPartition.Card(
            views = listOf(cardAutofillView),
        )
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = emptyList(),
            maxInlineSuggestionsCount = 0,
            packageName = PACKAGE_NAME,
            partition = autofillPartition,
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            assistStructure = assistStructure,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            null.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
            null.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = false,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Test
    fun `parse should skip block listed URIs Login when a Login view is focused`() {
        // Setup all tests
        val loginAutofillView: AutofillView.Login = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        setupAssistStructure(login = loginAutofillView)
        val remoteBlockList = listOf(
            "blockListedUri.com",
            "blockListedAgainUri.com",
        )
        every { settingsRepository.blockedAutofillUris } returns remoteBlockList

        // A function for asserting that a block listed URI results in an unfillable request.
        fun testBlockListedUri(blockListedUri: String) {
            // Setup
            every { any<AutofillView>().buildUriOrNull(PACKAGE_NAME) } returns blockListedUri

            // Test
            val actual = parser.parse(
                autofillAppInfo = autofillAppInfo,
                fillRequest = fillRequest,
            )

            // Verify
            assertEquals(AutofillRequest.Unfillable, actual)
        }

        // Test all block listed URIs
        BLOCK_LISTED_URIS.forEach(::testBlockListedUri)
        remoteBlockList.forEach(::testBlockListedUri)

        // Verify all tests
        verify(exactly = BLOCK_LISTED_URIS.size + remoteBlockList.size) {
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should return Unfillable when fill-assist is disabled even though heuristics classify the focused view as Unused`() {
        // Setup: fill-assist stays disabled (default). The rescue path must never activate when
        // the feature flag/setting are off, regardless of what fill-assist rules would say.
        every { any<AutofillView>().buildUriOrNull(PACKAGE_NAME) } returns FILL_ASSIST_URI
        val unusedLoginView = AutofillView.Unused(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = null,
            ),
        )
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns loginWindowNode
        every {
            loginViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns unusedLoginView

        // Test
        val actual = parser.parse(autofillAppInfo = autofillAppInfo, fillRequest = fillRequest)

        // Verify
        assertEquals(AutofillRequest.Unfillable, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should deduplicate when a child node returns an AutofillView with the same autofillId already claimed by a non-Unused parent view`() {
        // Setup
        val sharedAutofillId: AutofillId = mockk()
        val childViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns null
            every { this@mockk.website } returns null
        }
        val parentViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.childCount } returns 1
            every { this@mockk.getChildAt(0) } returns childViewNode
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
        }
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns parentViewNode
        }
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode

        val sharedAutofillView = AutofillView.Login.Password(
            data = AutofillView.Data(
                autofillId = sharedAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        every {
            parentViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns sharedAutofillView
        every {
            childViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns sharedAutofillView

        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Login(views = listOf(sharedAutofillView)),
            uri = URI,
        )

        // Test
        val actual = parser.parse(
            autofillAppInfo = autofillAppInfo,
            fillRequest = fillRequest,
        )

        // Verify
        assertEquals(expected, actual)
        verify(exactly = 1) {
            fillRequest.getInlinePresentationSpecs(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            fillRequest.getMaxInlineSuggestionsCount(
                autofillAppInfo = autofillAppInfo,
                isInlineAutofillEnabled = true,
            )
            any<List<ViewNodeTraversalData>>().buildPackageNameOrNull(assistStructure)
            any<AutofillView>().buildUriOrNull(PACKAGE_NAME)
        }
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should choose AutofillPartition Identity when an Identity view is focused even though a Login view is fillable in another window`() {
        // Setup: a focused field heuristics classify as Identity (e.g. "First name" on a signup
        // form) sits in one window, while a fillable, unfocused Login.Username field exists in
        // another window on the same screen. The Login view never enters the candidate pool --
        // selectCandidateAutofillViews only considers a window whose own views have a focused
        // entry -- so the focused Identity view wins and builds a real Identity partition.
        mutableIdentityAutofillFlagFlow.value = true
        val identityAutofillId: AutofillId = mockk()
        val identityViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns identityAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns null
        }
        val identityWindowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns identityViewNode
        }
        every { assistStructure.windowNodeCount } returns 2
        every { assistStructure.getWindowNodeAt(0) } returns identityWindowNode
        every { assistStructure.getWindowNodeAt(1) } returns loginWindowNode
        val identityAutofillView = AutofillView.Identity.PersonNameGiven(
            data = AutofillView.Data(
                autofillId = identityAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = null,
            ),
        )
        val loginAutofillView = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        every {
            identityViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns identityAutofillView
        every {
            loginViewNode.toAutofillView(parentWebsite = any(), isIdentityAutofillEnabled = any())
        } returns loginAutofillView

        // Test
        val actual = parser.parse(autofillAppInfo = autofillAppInfo, fillRequest = fillRequest)

        // Verify: the focused Identity view wins; the Login view in the other window is never a
        // candidate.
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = emptyList(),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Identity(views = listOf(identityAutofillView)),
            uri = URI,
        )
        assertEquals(expected, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should promote a phone-hinted field to Login Username via updateForMissingUsernameFields when IdentityAutofill is disabled`() {
        // Setup: a node that heuristics would classify as Identity PhoneFull once identity
        // detection is active, sitting directly above a password field. Before identity
        // detection existed, this same node fell through to Unused and was promoted to
        // Login.Username via updateForMissingUsernameFields. With IdentityAutofill disabled,
        // toAutofillView must still resolve it to Unused, so that promotion continues to work
        // exactly as it did before identity heuristics existed.
        mutableIdentityAutofillFlagFlow.value = false
        val (rootViewNode, phoneHintedViewNode, passwordViewNode, passwordAutofillId) =
            setupPhoneHintedFieldAbovePassword()
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns rootViewNode
        }
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode
        val unusedPhoneView = AutofillView.Unused(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginPasswordAutofillView = AutofillView.Login.Password(
            data = AutofillView.Data(
                autofillId = passwordAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        every {
            phoneHintedViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = false,
            )
        } returns unusedPhoneView
        every {
            passwordViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns loginPasswordAutofillView

        // Test
        val actual = parser.parse(autofillAppInfo = autofillAppInfo, fillRequest = fillRequest)

        // Verify: promoted to Login.Username, so both fields are present in the partition.
        val loginUsernameAutofillView = AutofillView.Login.Username(data = unusedPhoneView.data)
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = listOf(rootViewNode.autofillId!!),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Login(
                views = listOf(loginUsernameAutofillView, loginPasswordAutofillView),
            ),
            uri = URI,
        )
        assertEquals(expected, actual)
    }

    @Suppress("MaxLineLength")
    @Test
    fun `parse should classify a phone-hinted field as Login Username with an Identity PhoneFull sibling when IdentityAutofill is enabled`() {
        // Setup: same shape as the disabled case above, but IdentityAutofill is enabled and the
        // node genuinely declares the phone autofill hint, so the real isPhoneField check (not a
        // mocked toAutofillView return) fires. The phone hint always maps to Login.Username, so
        // traverse() adds an Identity.PhoneFull dual-classification sibling alongside it. That
        // sibling doesn't change partition selection here -- Login.Username is focused and
        // precedes it in the views list, so it still wins, producing the same Login partition as
        // the disabled-flag case.
        mutableIdentityAutofillFlagFlow.value = true
        val (rootViewNode, phoneHintedViewNode, passwordViewNode, passwordAutofillId) =
            setupPhoneHintedFieldAbovePassword()
        every { phoneHintedViewNode.autofillHints } returns arrayOf(View.AUTOFILL_HINT_PHONE)
        // isEmailField is also checked alongside isPhoneField now that the real toAutofillView
        // return (Login.Username) is used instead of a mocked bypass.
        every { phoneHintedViewNode.idEntry } returns null
        every { phoneHintedViewNode.hint } returns null
        every { phoneHintedViewNode.htmlInfo } returns mockk(relaxed = true)
        val windowNode: AssistStructure.WindowNode = mockk {
            every { this@mockk.rootViewNode } returns rootViewNode
        }
        every { assistStructure.windowNodeCount } returns 1
        every { assistStructure.getWindowNodeAt(0) } returns windowNode
        val loginUsernameAutofillView = AutofillView.Login.Username(
            data = AutofillView.Data(
                autofillId = loginAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = true,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        val loginPasswordAutofillView = AutofillView.Login.Password(
            data = AutofillView.Data(
                autofillId = passwordAutofillId,
                autofillOptions = emptyList(),
                autofillType = AUTOFILL_TYPE,
                isFocused = false,
                textValue = null,
                hasPasswordTerms = false,
                website = URI,
            ),
        )
        every {
            phoneHintedViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = true,
            )
        } returns loginUsernameAutofillView
        every {
            passwordViewNode.toAutofillView(
                parentWebsite = any(),
                isIdentityAutofillEnabled = any(),
            )
        } returns loginPasswordAutofillView

        // Test
        val actual = parser.parse(autofillAppInfo = autofillAppInfo, fillRequest = fillRequest)

        // Verify: Login.Username still wins partition selection, same as the disabled-flag case.
        val expected = AutofillRequest.Fillable(
            ignoreAutofillIds = listOf(rootViewNode.autofillId!!),
            inlinePresentationSpecs = inlinePresentationSpecs,
            maxInlineSuggestionsCount = MAX_INLINE_SUGGESTION_COUNT,
            packageName = PACKAGE_NAME,
            partition = AutofillPartition.Login(
                views = listOf(loginUsernameAutofillView, loginPasswordAutofillView),
            ),
            uri = URI,
        )
        assertEquals(expected, actual)
    }

    /**
     * Sets up a root node with two children -- a phone-hinted field and a password field
     * directly below it -- for testing [updateForMissingUsernameFields]'s Unused-only promotion
     * against a field that heuristics may classify as Identity.PhoneFull.
     */
    private fun setupPhoneHintedFieldAbovePassword(): PhoneAbovePasswordNodes {
        val phoneHintedViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns loginAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
        }
        val passwordAutofillId = mockk<AutofillId>()
        val passwordViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns passwordAutofillId
            every { this@mockk.childCount } returns 0
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
        }
        val rootAutofillId = mockk<AutofillId>()
        val rootViewNode: AssistStructure.ViewNode = mockk {
            every { this@mockk.autofillHints } returns emptyArray()
            every { this@mockk.autofillId } returns rootAutofillId
            every { this@mockk.childCount } returns 2
            every { this@mockk.idPackage } returns ID_PACKAGE
            every { this@mockk.website } returns WEBSITE
            every { this@mockk.getChildAt(0) } returns phoneHintedViewNode
            every { this@mockk.getChildAt(1) } returns passwordViewNode
            every {
                this@mockk.toAutofillView(parentWebsite = any(), isIdentityAutofillEnabled = any())
            } returns null
        }
        return PhoneAbovePasswordNodes(
            rootViewNode = rootViewNode,
            phoneHintedViewNode = phoneHintedViewNode,
            passwordViewNode = passwordViewNode,
            passwordAutofillId = passwordAutofillId,
        )
    }

    /**
     * Sets up [assistStructure] with one window node per non-null argument, in card → login →
     * identity order, each stubbed to return the given view from `toAutofillView`. A window is
     * omitted entirely (not present in the mocked structure) when its argument is null — there is
     * no filler/default view for an omitted window.
     */
    private fun setupAssistStructure(
        card: AutofillView.Card? = null,
        login: AutofillView.Login? = null,
        identity: AutofillView.Identity? = null,
    ) {
        val windowNodes = buildList {
            card?.let {
                every {
                    cardViewNode.toAutofillView(
                        parentWebsite = any(),
                        isIdentityAutofillEnabled = any(),
                    )
                } returns it
                add(cardWindowNode)
            }
            login?.let {
                every {
                    loginViewNode.toAutofillView(
                        parentWebsite = any(),
                        isIdentityAutofillEnabled = any(),
                    )
                } returns it
                add(loginWindowNode)
            }
            identity?.let {
                every {
                    identityViewNode.toAutofillView(
                        parentWebsite = any(),
                        isIdentityAutofillEnabled = any(),
                    )
                } returns it
                add(identityWindowNode)
            }
        }
        every { assistStructure.windowNodeCount } returns windowNodes.size
        windowNodes.forEachIndexed { index, node ->
            every { assistStructure.getWindowNodeAt(index) } returns node
        }
    }
}

/**
 * The nodes built by `setupPhoneHintedFieldAbovePassword`, returned so each test can stub
 * [AssistStructure.ViewNode.toAutofillView] differently for the phone-hinted node depending on
 * whether IdentityAutofill is enabled.
 */
private data class PhoneAbovePasswordNodes(
    val rootViewNode: AssistStructure.ViewNode,
    val phoneHintedViewNode: AssistStructure.ViewNode,
    val passwordViewNode: AssistStructure.ViewNode,
    val passwordAutofillId: AutofillId,
)

private const val FILL_ASSIST_URI: String = "https://example.com"

private val BLOCK_LISTED_URIS: List<String> = listOf(
    "androidapp://android",
    "androidapp://com.android.settings",
    "androidapp://com.x8bit.bitwarden",
    "androidapp://com.oneplus.applocker",
)
private const val AUTOFILL_TYPE: Int = View.AUTOFILL_TYPE_TEXT
private const val ID_PACKAGE: String = "com.x8bit.bitwarden"
private const val MAX_INLINE_SUGGESTION_COUNT: Int = 42
private const val PACKAGE_NAME: String = "com.google"
private const val URI: String = "androidapp://com.google"
private const val WEBSITE: String = "https://www.google.com"

// ID package assigned to some nodes in the autofill view hierarchy by the OS.
private const val ID_PACKAGE_ANDROID = "android"
