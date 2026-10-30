# SecretDoor

SecretDoor is an offline password manager for Android. Your vault lives only on your phone,
encrypted with your master password, and is never sent to any server. You decide where its
backups go.

SecretDoor is a fork of [Bitwarden for Android](https://github.com/bitwarden/android) and uses the
Bitwarden SDK for all encryption. It is not affiliated with or endorsed by Bitwarden Inc.

## Contents

- [Features](#features)
- [Privacy](#privacy)
- [Backups](#backups)
- [Compatibility](#compatibility)
- [Building](#building)
- [License](#license)
- [Dependencies](#dependencies)

## Features

- Logins, cards, identities, secure notes, SSH keys, passkeys and attachments, organized in folders.
- Password and passphrase generator, password history and TOTP verification codes.
- Unlock with your master password, a PIN or biometrics.
- Autofill for apps and browsers, and passkeys through Android Credential Manager.
- Encrypted backups you can save anywhere, plus optional automatic backups.

## Privacy

- **No account and no server.** Creating a vault happens entirely on the device. There is no
  sign-in, sync or telemetry.
- **Zero knowledge.** Everything in the vault is encrypted on the device with a key that only your
  master password can unlock. SecretDoor cannot recover a lost master password.
- **No website icons.** Icons are never downloaded, because those requests would reveal which
  websites you have logins for.
- **No crash reporting or analytics.** SecretDoor contains no Firebase, Crashlytics or advertising
  libraries.
- **Network use.** SecretDoor blocks every connection except two:
  - The optional "check known data breaches" option for the master password (off by default). It
    sends only the first five characters of the password's SHA-1 hash to
    [Have I Been Pwned](https://haveibeenpwned.com/Passwords).
  - When an app (not a browser) asks for a passkey, SecretDoor asks Google's
    [Digital Asset Links](https://developers.google.com/digital-asset-links) service whether the
    app belongs to that website, to protect against phishing apps. Only the website, the app's
    package name and its signing certificate fingerprint are sent; no vault data.

## Backups

Because the vault exists only on your phone, losing the phone or uninstalling the app loses the
vault unless you have a backup.

- **Save backup file** (Settings > Vault > Backup) writes a `.zip` file to any location you pick,
  for example Google Drive. The file holds your vault exactly as it is stored on the phone:
  encrypted, including folders and attachments. You need your master password to restore it.
- **Automatic backup** writes the backup to a file you choose once, daily or weekly, even when
  the vault is locked. Google Drive keeps earlier versions of the file.
- **Restore from backup** is on the "Create vault" screen of a fresh install. Pick the backup file
  and enter the vault's master password.

**Export vault** (Settings > Vault) is separate: it produces Bitwarden-compatible files (CSV, JSON
or password-protected JSON) for moving to another password manager.

## Compatibility

- **Minimum SDK**: 29 (Android 10)
- **Target SDK**: 37 (Android 17)
- **Device Types Supported**: Phone and Tablet
- **Orientations Supported**: Portrait and Landscape

## Building

1. Clone the repository:

    ```sh
    $ git clone https://github.com/zcoklin/secretdoor
    ```

2. Create a `user.properties` file in the root directory with a `gitHubToken` property: a
   "classic" GitHub Personal Access Token with the `read:packages` scope (ex:
   `gitHubToken=ghp_xx...xx`). It is needed to download the Bitwarden SDK from GitHub Packages.
   Tokens can be created on the [GitHub tokens page](https://github.com/settings/tokens).

3. Use JDK 21 for Gradle.

4. Build the F-Droid flavor (no Google Play services):

    ```sh
    $ ./gradlew app:assembleFdroidDebug
    ```

    Run the unit tests with `./gradlew app:testFdroidDebugUnitTest`. On machines with less
    memory, add `-PtestMaxHeapSize=1280m -PtestForkEvery=2 --max-workers=1`.

Code style follows [docs/STYLE_AND_BEST_PRACTICES.md](docs/STYLE_AND_BEST_PRACTICES.md).

## License

SecretDoor is licensed under the [GNU General Public License v3.0](LICENSE.txt), like the
Bitwarden Android app it is based on. Bitwarden is a trademark of Bitwarden Inc.; SecretDoor is an
independent project.

## Dependencies

### Application Dependencies

The following is a list of all third-party dependencies included as part of the application beyond the standard Android SDK.

- **AndroidX Activity**
    - https://developer.android.com/jetpack/androidx/releases/activity
    - Purpose: Allows access composable APIs built on top of Activity.
    - License: Apache 2.0

- **AndroidX Appcompat**
    - https://developer.android.com/jetpack/androidx/releases/appcompat
    - Purpose: Allows access to new APIs on older API versions.
    - License: Apache 2.0

- **AndroidX Autofill**
    - https://developer.android.com/jetpack/androidx/releases/autofill
    - Purpose: Allows access to tools for building inline autofill UI.
    - License: Apache 2.0

- **AndroidX Biometrics**
    - https://developer.android.com/jetpack/androidx/releases/biometric
    - Purpose: Authenticate with biometrics or device credentials.
    - License: Apache 2.0

- **AndroidX Browser**
    - https://developer.android.com/jetpack/androidx/releases/browser
    - Purpose: Displays webpages with the user's default browser.
    - License: Apache 2.0

- **AndroidX Camera**
    - https://developer.android.com/jetpack/androidx/releases/camera
    - Purpose: Display and capture images for barcode scanning.
    - License: Apache 2.0

- **AndroidX Compose**
    - https://developer.android.com/jetpack/androidx/releases/compose
    - Purpose: A Kotlin-based declarative UI framework.
    - License: Apache 2.0

- **AndroidX Core**
    - https://developer.android.com/jetpack/androidx/releases/core
    - Purpose: Backwards compatible platform features and APIs.
    - License: Apache 2.0

- **AndroidX Credentials**
    - https://developer.android.com/jetpack/androidx/releases/credentials
    - Purpose: Unified access to user's credentials.
    - License: Apache 2.0

- **AndroidX Lifecycle**
    - https://developer.android.com/jetpack/androidx/releases/lifecycle
    - Purpose: Lifecycle aware components and tooling.
    - License: Apache 2.0

- **AndroidX Navigation**
    - https://developer.android.com/jetpack/androidx/releases/navigation
    - Purpose: Provides a consistent API for navigating between Android components.
    - License: Apache 2.0

- **AndroidX Room3**
    - https://developer.android.com/jetpack/androidx/releases/room3
    - Purpose: A convenient SQLite-based persistence layer for Android.
    - License: Apache 2.0

- **AndroidX Security**
    - https://developer.android.com/jetpack/androidx/releases/security
    - Purpose: Safely manage keys and encrypt files and sharedpreferences.
    - License: Apache 2.0

- **AndroidX WorkManager**
    - https://developer.android.com/jetpack/androidx/releases/work
    - Purpose: The WorkManager is used to schedule deferrable, asynchronous tasks that must be run reliably.
    - License: Apache 2.0

- **Coil**
    - https://github.com/coil-kt/coil
    - Purpose: Image loading and caching.
    - License: Apache 2.0

- **Dagger Hilt**
    - https://github.com/google/dagger
    - Purpose: Dependency injection framework.
    - License: Apache 2.0

- **kotlinx.collections.immutable**
    - https://github.com/Kotlin/kotlinx.collections.immutable
    - Purpose: Immutable collection interfaces and implementation prototypes for Kotlin.
    - License: Apache 2.0

- **kotlinx.coroutines**
    - https://github.com/Kotlin/kotlinx.coroutines
    - Purpose: Kotlin coroutines library for asynchronous and reactive code.
    - License: Apache 2.0

- **kotlinx.serialization**
    - https://github.com/Kotlin/kotlinx.serialization/
    - Purpose: JSON serialization library for Kotlin.
    - License: Apache 2.0

- **OkHttp 3**
    - https://github.com/square/okhttp
    - Purpose: An HTTP client used by the library to intercept and log traffic.
    - License: Apache 2.0

- **Retrofit 2**
    - https://github.com/square/retrofit
    - Purpose: A networking layer interface.
    - License: Apache 2.0

- **Timber**
    - https://github.com/JakeWharton/timber
    - Purpose: Extensible logging library for Android.
    - License: Apache 2.0

- **ZXing**
    - https://github.com/zxing/zxing
    - Purpose: Barcode scanning and generation.
    - License: Apache 2.0

The following is an additional list of third-party dependencies that are only included in the non-F-Droid build variants of the application.

- **Firebase Cloud Messaging**
    - https://github.com/firebase/firebase-android-sdk
    - Purpose: Allows for push notification support.
    - License: Apache 2.0

- **Firebase Crashlytics**
    - https://github.com/firebase/firebase-android-sdk
    - Purpose: SDK for crash and non-fatal error reporting.
    - License: Apache 2.0

- **Google Play Reviews**
    - https://developer.android.com/reference/com/google/android/play/core/release-notes
    - Purpose: On standard builds provide an interface to add a review for the password manager application in Google Play.
    - License: Apache 2.0

### Development Environment Dependencies

The following is a list of additional third-party dependencies used as part of the local development environment. This includes test-related artifacts as well as tools related to code quality and linting. These are not present in the final packaged application.

- **detekt**
    - https://github.com/detekt/detekt
    - Purpose: A static code analysis tool for the Kotlin programming language.
    - License: Apache 2.0

- **JUnit 5**
    - https://github.com/junit-team/junit5
    - Purpose: Unit Testing framework for testing application code.
    - License: Eclipse Public License 2.0

- **MockK**
    - https://github.com/mockk/mockk
    - Purpose: Kotlin-friendly mocking library.
    - License: Apache 2.0

- **Robolectric**
    - https://github.com/robolectric/robolectric
    - Purpose: A unit testing framework for code directly depending on the Android framework.
    - License: MIT

- **Turbine**
    - https://github.com/cashapp/turbine
    - Purpose: A small testing library for kotlinx.coroutine's Flow.
    - License: Apache 2.0

### CI/CD Dependencies

The following is a list of additional third-party dependencies used as part of the CI/CD workflows. These are not present in the final packaged application.

- **Fastlane**
    - https://fastlane.tools/
    - Purpose: Automates building, signing, and distributing applications.
    - License: MIT

- **Kover**
    - https://github.com/Kotlin/kotlinx-kover
    - Purpose: Kotlin code coverage toolset.
    - License: Apache 2.0
