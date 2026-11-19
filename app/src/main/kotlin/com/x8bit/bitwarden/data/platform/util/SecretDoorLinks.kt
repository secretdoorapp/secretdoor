package com.x8bit.bitwarden.data.platform.util

/**
 * The one place the app learns where its source code and documentation live.
 *
 * SecretDoor is free software (GNU GPL v3, a fork of the Bitwarden Android app), so the app must
 * always be able to point users at the exact source code it was built from. That, the license and
 * the notices stay on GitHub. The manual, help pages and privacy policy are on the project website
 * (the `website/` folder of the same repository, generated from `docs/`).
 */
object SecretDoorLinks {
    /** The project's source code repository. */
    const val REPOSITORY_URL: String = "https://github.com/secretdoorapp/secretdoor"

    /** The license the app is distributed under. */
    const val LICENSE_URL: String = "$REPOSITORY_URL/blob/main/LICENSE.txt"

    /** Where the notices of the upstream project and third-party components are listed. */
    const val NOTICES_URL: String = "$REPOSITORY_URL/blob/main/NOTICE.md"

    /** The project website. */
    const val WEBSITE_URL: String = "https://secretdoor.fyi"

    private const val HELP_URL: String = "$WEBSITE_URL/manual"

    /** The manual start page. */
    const val HELP_CENTER_URL: String = "$HELP_URL/"

    /** The privacy policy: what the app stores, and what leaves the device. */
    const val PRIVACY_URL: String = "$WEBSITE_URL/privacy/"

    /** Explains the "ask for the master password again" protection on an item. */
    const val MASTER_PASSWORD_REPROMPT_URL: String = "$HELP_URL/master-password-reprompt/"

    /** Explains authenticator (TOTP) codes and the authenticator key field. */
    const val AUTHENTICATOR_CODES_URL: String = "$HELP_URL/authenticator-codes/"

    /** Explains how the app decides which saved logins match a website or app. */
    const val URI_MATCH_DETECTION_URL: String = "$HELP_URL/uri-match-detection/"

    /** Explains the password, passphrase and username generator. */
    const val GENERATOR_URL: String = "$HELP_URL/generator/"

    /** Explains the username types of the generator. */
    const val GENERATOR_USERNAME_TYPES_URL: String = "$GENERATOR_URL#username-types"

    /** Explains autofill and how to set it up. */
    const val AUTOFILL_URL: String = "$HELP_URL/autofill/"

    /** Explains how to set up autofill in browsers. */
    const val AUTOFILL_BROWSERS_URL: String = "$AUTOFILL_URL#browsers"

    /** Explains what to do when autofill does not work. */
    const val AUTOFILL_TROUBLESHOOTING_URL: String = "$AUTOFILL_URL#troubleshooting"

    /** Explains how to bring items into SecretDoor. */
    const val IMPORTING_DATA_URL: String = "$HELP_URL/importing-data/"

    /** Explains the flight recorder (diagnostic log). */
    const val FLIGHT_RECORDER_URL: String = "$HELP_URL/flight-recorder/"

    /** Explains what Premium is and what it includes. */
    const val PREMIUM_URL: String = "$HELP_URL/premium/"
}
