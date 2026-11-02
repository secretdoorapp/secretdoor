# Google Play listing

Listing text and graphics live in `fastlane/metadata/android/en-US/` (also readable by F-Droid).

| File | Play Console field |
| --- | --- |
| `title.txt` | App name (max 30) |
| `short_description.txt` | Short description (max 80) |
| `full_description.txt` | Full description (max 4000) |
| `images/icon.png` | App icon 512x512 |
| `images/featureGraphic.png` | Feature graphic 1024x500 |
| `changelogs/<versionCode>.txt` | Release notes (max 500) |

Still needed from you: phone screenshots (2 to 8, in `images/phoneScreenshots/`), a public
privacy policy URL (publish `docs/PRIVACY.md`, for example via GitHub Pages), and a developer
account (one-time US$25 fee, identity verification).

## Console answers

- **App category**: Tools (alternative: Productivity). **Contact email**: required, public.
- **Content rating**: questionnaire answers are all "No"; expect Everyone.
- **Target audience**: 18 and over. **Ads**: none.
- **Data safety**: no data collected, no data shared. The vault never leaves the device, and the
  optional breach check sends only a 5-character hash prefix, which is not collected personal data.
  Data is encrypted in transit and users can delete it (delete vault or uninstall).
- **App access**: no login needed; reviewers create a local vault with any master password.
- **Permissions**: Camera (QR scan), Biometrics, Internet, Network state.
- **Accessibility API declaration (the hard one)**: the optional accessibility service fills
  credentials in apps and browsers without autofill support. State that it is off by default, user
  enabled, never stores or transmits screen content, and needs a short demo video of enabling and
  using it. If this declaration is rejected, remove the service from the Play build.
- **Credential Manager / autofill**: declared via the manifest, no extra form.
- **Name and trademark**: the description says it is not affiliated with Bitwarden Inc. Keep it.

## Release steps

1. Create a release keystore (not the debug one), keep it and its password backed up offline.
2. Enroll in Play App Signing and add the upload key.
3. Build the bundle: `./gradlew app:bundleFdroidRelease` (low-memory flags in memory notes).
4. New personal accounts must run a closed test with 12 testers for 14 days before production.
5. Upload the AAB, fill the listing from the table above, submit for review.
