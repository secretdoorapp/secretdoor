# Notices

## SecretDoor

SecretDoor is free software: you can redistribute it and modify it under the terms of the
[GNU General Public License, version 3](LICENSE.txt) (version 3 only). It is distributed in the hope that it will be
useful, but **without any warranty**; without even the implied warranty of merchantability or
fitness for a particular purpose. See the license for details.

Source code: <https://github.com/secretdoorapp/secretdoor>

SecretDoor is an independent project. It is **not affiliated with, endorsed by or sponsored by
Bitwarden, Inc.**

## SecretDoor for iPhone

The iPhone app in [`SecretDoor/`](SecretDoor/) is licensed under the GNU GPL version 3 **with an
additional permission** (GPL v3 section 7) to distribute it through Apple's App Store and
TestFlight. See [`SecretDoor/LICENSE.md`](SecretDoor/LICENSE.md). It was written for SecretDoor
and contains no code copied from Bitwarden; it only uses the same data formats as the Android app.

## Based on Bitwarden for Android

SecretDoor is a fork, and therefore a modified (derivative) version, of the
[Bitwarden Android app](https://github.com/bitwarden/android), which is licensed under the
GNU General Public License v3. The original work is

> Copyright © Bitwarden, Inc. and the Bitwarden contributors

and the parts of SecretDoor that come from it remain under that license. Bitwarden's source code
and history are available at the link above; SecretDoor was started from it on 2026-10-29
(`2026.11.0` development line) and every later change is recorded in this repository's history.

### What was changed (GPL v3 section 5(a))

The changes are made by the SecretDoor contributors, from 2026-10-29. In summary:

- The app no longer connects to Bitwarden's servers or any server. Accounts, login, sync,
  organizations, collections, policies, Send, push notifications, device management, Key Connector
  and trusted-device login, the Authenticator app bridge, Fill Assist and server configuration were
  removed. Vaults are created and unlocked on the device only.
- New: encrypted `.secretdoor` backups (manual and automatic), restore and import of backups,
  several vaults on one device with rename and delete, changing the master password on the device,
  attachments on the add/edit screen, SSH key creation.
- Changed: new name, icons and artwork, documentation links, privacy behaviour (see
  [docs/PRIVACY.md](docs/PRIVACY.md)). Master passwords of new vaults use Argon2id.
- Network access is restricted to two optional uses (see the privacy page).

The detailed plan and progress log is in [docs/SECRETDOOR_PLAN.md](docs/SECRETDOOR_PLAN.md).
Internal code packages still use the name `com.x8bit.bitwarden`, which keeps merging upstream
security fixes manageable; this is not a claim of affiliation.

### Trademarks

"Bitwarden" and the Bitwarden logo are trademarks of Bitwarden, Inc. They are not licensed to
SecretDoor and are not used as the name or logo of this app. Where the app and its source code
refer to Bitwarden, they do so only to state where the code comes from.

## Bitwarden SDK

All cryptography is done by the Bitwarden SDK (`com.bitwarden:sdk-android`, native library
`libbitwarden_uniffi.so`), built from
[bitwarden/sdk-internal](https://github.com/bitwarden/sdk-internal), the version used by this
release: commit `d9119f7af71bce0543b571bf000e18a67ad362ee` (artifact `4.0.0-9737-d9119f7a`).
Its source code is the *Corresponding Source* for that part of the app and is available at the link
above.

### Which license applies to the SDK

Bitwarden publishes two kinds of licensed code in its repositories:

- **GPL code**, in each repository's normal source folders. For `sdk-internal` the crates under
  `crates/` declare the license expression `GPL-3.0-only OR LicenseRef-Bitwarden-SDK`. That is a
  dual license: **anyone may use that code under the GNU GPL v3 only** instead of Bitwarden's SDK
  license. SecretDoor uses it under the GPL v3 (and so SecretDoor as a whole is distributed under
  version 3 of the GPL only, not "or later").
- **Bitwarden's commercial / source-available license** (the "Bitwarden License"), which is limited
  to code kept in separate `bitwarden_license/` folders. In `sdk-internal` these are the crates
  `bitwarden-commercial-marker`, `bitwarden-commercial-vault`, `bitwarden-pam` and
  `bitwarden-pam-sealed`, which ship only in Bitwarden's separate commercially licensed packages.
  The `sdk-internal` README states that "the OSS-licensed packages that are published from
  sdk-internal include only code licensed under the GPL license".

What was checked for this release (2026-11-01):

- The Android library `libbitwarden_uniffi.so` in `com.bitwarden:sdk-android 4.0.0-9737-d9119f7a` is
  built from the `bitwarden-uniffi` crate, whose dependencies are `crates/*` only. It does not
  depend on any of the commercial crates.
- The compiled library was inspected: it contains the source paths of `crates/*` crates and **no**
  trace of the commercial crates (`bitwarden-commercial-*`, `bitwarden-pam*`).
- The SecretDoor repository (inherited from Bitwarden Android, GPL v3) contains no
  `bitwarden_license` folder and no files under the Bitwarden License.
- The `sdk-android` artifact's Maven metadata names no license, so this conclusion rests on the
  upstream source and the binary, not on a license field. Bitwarden has not given written
  confirmation. Before commercial distribution, re-check when the SDK version is updated (see
  "Updating the SDK" below), or ask Bitwarden to confirm.

### Updating the SDK

Every time `bitwardenSdk` in `gradle/libs.versions.toml` changes:

1. Update the version and commit id above.
2. Check that `crates/bitwarden-uniffi/Cargo.toml` still depends only on `crates/*` and that the
   crates' `license` is still `GPL-3.0-only OR LicenseRef-Bitwarden-SDK`.
3. Check that the compiled library has no `bitwarden-commercial` / `bitwarden-pam` strings.

## Third-party components

SecretDoor includes other open source libraries (AndroidX, Jetpack Compose, Kotlin, Dagger, OkHttp,
Retrofit, Coil, CameraX, JNA and others), nearly all under the Apache License 2.0, which is
compatible with the GPL v3. The complete list with licenses is in
[docs/THIRD_PARTY_LICENSES.md](docs/THIRD_PARTY_LICENSES.md).

### Which build to distribute

The Gradle project has a single product flavor, `fdroid`. It contains only free software (plus the
Bitwarden SDK above) and no Google services, and it is the build to publish on F-Droid, on GitHub
and on Google Play. An earlier `standard` flavor, which bundled proprietary Google libraries (Google
Play Services, ML Kit text recognition, Play Billing, Play Core), was removed because those libraries
are not GPL-compatible and send usage data to Google, which contradicts SecretDoor's privacy
promise. If paid features need Play Billing later, add it to a separate Play-only build and review
its licenses first.

## Distributing SecretDoor (including on app stores)

The GPL allows selling copies, including on Google Play, as long as every recipient also receives
the rights the GPL gives: the license text, and access to the complete corresponding source code of
the exact version they received, with no extra restrictions.

- Keep this notice, `LICENSE.txt` and the in-app *About* links (source code, license, notices).
- Tag every release in the repository and publish the matching source. Give the store listing a
  link to the repository.
- Do not add terms that forbid copying, modifying or redistributing the app.
- Apple's App Store terms are generally considered incompatible with the GPL; do not publish the
  Android code (or anything containing Bitwarden's code) there. The iPhone app has an explicit
  App Store permission, see [`SecretDoor/LICENSE.md`](SecretDoor/LICENSE.md); its source must still
  be published for every release.
- If you add code of your own, it must be released under the GPL v3 too.
- Anyone can build the app from the source, so what you sell is the convenience (a signed build,
  updates, support, a hosted service), not exclusivity.

This summary is not legal advice.
