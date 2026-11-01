package com.x8bit.bitwarden.ui.vault.feature.importlogins

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import com.bitwarden.ui.platform.manager.IntentManager
import com.x8bit.bitwarden.data.platform.util.SecretDoorLinks
import com.x8bit.bitwarden.ui.platform.base.BitwardenComposeTest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImportLoginsScreenTest : BitwardenComposeTest() {

    private var onNavigateBackCalled = false
    private val intentManager = mockk<IntentManager> {
        every { launchUri(uri = any()) } just runs
    }

    @Before
    fun setUp() {
        setContent(intentManager = intentManager) {
            ImportLoginsScreen(
                onNavigateBack = { onNavigateBackCalled = true },
                intentManager = intentManager,
            )
        }
    }

    @Test
    fun `the screen should explain the import options`() {
        composeTestRule.onNodeWithText("Import items").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Restore a SecretDoor backup, or transfer items from another app that " +
                    "supports Credential Exchange.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `back click should call onNavigateBack`() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(onNavigateBackCalled)
    }

    @Test
    fun `read the import guide click should launch the import guide`() {
        composeTestRule.onNodeWithText("Read the import guide").performClick()
        verify(exactly = 1) {
            intentManager.launchUri(SecretDoorLinks.IMPORTING_DATA_URL.toUri())
        }
    }
}
