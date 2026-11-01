package com.x8bit.bitwarden.ui.platform.model

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Immutable
import com.bitwarden.annotation.OmitFromCoverage

/**
 * Contains all the callbacks for the Auth Tabs.
 */
@OmitFromCoverage
@Immutable
class AuthTabLaunchers(
    val premiumCheckout: ActivityResultLauncher<Intent>,
    val stripePortal: ActivityResultLauncher<Intent>,
)
