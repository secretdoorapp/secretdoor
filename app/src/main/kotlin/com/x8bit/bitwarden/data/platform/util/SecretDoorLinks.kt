package com.x8bit.bitwarden.data.platform.util

/**
 * The one place the app learns where its source code and documentation live.
 *
 * SecretDoor is free software (GNU GPL v3, a fork of the Bitwarden Android app), so the app must
 * always be able to point users at the exact source code it was built from. The documentation
 * pages are plain Markdown files in the same repository, which GitHub renders in the browser.
 * Moving the project only requires changing [REPOSITORY_URL].
 */
object SecretDoorLinks {
    /** The project's source code repository. */
    const val REPOSITORY_URL: String = "https://github.com/secretdoorapp/secretdoor"

    /** The license the app is distributed under. */
    const val LICENSE_URL: String = "$REPOSITORY_URL/blob/main/LICENSE.txt"

    /** Where the notices of the upstream project and third-party components are listed. */
    const val NOTICES_URL: String = "$REPOSITORY_URL/blob/main/NOTICE.md"

    private const val DOCS_URL: String = "$REPOSITORY_URL/blob/main/docs"
    private const val HELP_URL: String = "$DOCS_URL/help"

    /** The documentation start page. */
    const val HELP_CENTER_URL: String = "$DOCS_URL/README.md"

    /** The privacy policy: what the app stores, and what leaves the device. */
    const val PRIVACY_URL: String = "$DOCS_URL/PRIVACY.md"

    /** Explains the "ask for the master password again" protection on an item. */
    const val MASTER_PASSWORD_REPROMPT_URL: String = "$HELP_URL/master-password-reprompt.md"

    /** Explains authenticator (TOTP) codes and the authenticator key field. */
    const val AUTHENTICATOR_CODES_URL: String = "$HELP_URL/authenticator-codes.md"

    /** Explains how the app decides which saved logins match a website or app. */
    const val URI_MATCH_DETECTION_URL: String = "$HELP_URL/uri-match-detection.md"

    /** Explains the password, passphrase and username generator. */
    const val GENERATOR_URL: String = "$HELP_URL/generator.md"

    /** Explains the username types of the generator. */
    const val GENERATOR_USERNAME_TYPES_URL: String = "$GENERATOR_URL#username-types"

    /** Explains autofill and how to set it up. */
    const val AUTOFILL_URL: String = "$HELP_URL/autofill.md"

    /** Explains how to set up autofill in browsers. */
    const val AUTOFILL_BROWSERS_URL: String = "$AUTOFILL_URL#browsers"

    /** Explains what to do when autofill does not work. */
    const val AUTOFILL_TROUBLESHOOTING_URL: String = "$AUTOFILL_URL#troubleshooting"

    /** Explains how to bring items into SecretDoor. */
    const val IMPORTING_DATA_URL: String = "$HELP_URL/importing-data.md"

    /** Explains the flight recorder (diagnostic log). */
    const val FLIGHT_RECORDER_URL: String = "$HELP_URL/flight-recorder.md"

    /** Explains what Premium is and what it includes. */
    const val PREMIUM_URL: String = "$HELP_URL/premium.md"
}
