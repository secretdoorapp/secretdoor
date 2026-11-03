# Privacy

SecretDoor keeps your vault on your phone and nowhere else. This page lists exactly what is
stored, what can leave the device and what permissions the app asks for.

## What is stored on your phone

- **Your vault**: logins, cards, identities, notes, SSH keys, passkeys, folders and attachments.
  Everything is encrypted with a key that only your master password can unlock (see
  [Security](help/security.md)). The vault never exists in readable form on disk.
- **Settings**: your preferences (theme, timeout, autofill options and so on).
- **Unlock helpers you choose to turn on**: a PIN or biometric unlock keeps an encrypted copy of
  the vault key protected by the Android keystore.
- **Backups you create**: encrypted files saved wherever you choose.

## What can leave your phone

SecretDoor has no account, no server, no sync, no analytics and no crash reporting. The app
refuses every network connection except the two below.

1. **Known-breach check for a new master password (optional, off by default).** If you tick
   "Check known data breaches" when creating a vault, the app sends
   the first 5 characters of the SHA-1 hash of the password to
   [Have I Been Pwned](https://haveibeenpwned.com/API/v3#PwnedPasswords). The service answers with
   a list of hash endings, and the comparison happens on your phone. Your password is never sent.
2. **Passkeys for apps.** When an app (not a browser) asks you to create or use a passkey,
   SecretDoor asks Google's
   [Digital Asset Links](https://developers.google.com/digital-asset-links) service whether the app
   belongs to the website. Only the website, the app's package name and its signing certificate
   fingerprint are sent. No vault data is included.

Website icons are never downloaded, because each icon request would tell a server which sites you
have logins for.

If you choose to save backups to a cloud folder (for example Google Drive), that cloud service
receives the encrypted backup file, as you would expect. The file is encrypted before it leaves the
app.

## Permissions

| Permission | Why |
| --- | --- |
| Internet | Only for the two cases above. |
| Network state | Used to avoid network requests when there is no connection. |
| Camera | Scan a QR code that contains an authenticator key. Used only while the scanner is open. |
| Biometrics | Unlock with fingerprint or face if you turn it on. |
| Local network | Declared by the upstream app for self-hosted servers. SecretDoor does not use it. |

SecretDoor can also act as an Android **autofill service**, an **accessibility service** (an
optional way to fill apps and browsers that do not support autofill) and a **credential provider**
(passkeys and passwords through Android Credential Manager). Each of these is switched on by you in
Android's settings, and you can switch it off there at any time.

## Your data and the law

SecretDoor does not collect personal data, so there is nothing to request, correct or delete on a
server. To remove your data, delete the vault in the app (Settings > Vault > Manage vaults) or
uninstall the app. Backup files you saved elsewhere stay where you saved them until you delete them.

## Contact

Questions about privacy can be sent to <contact@secretdoor.fyi> or raised as an issue in the
[project repository](https://github.com/secretdoorapp/secretdoor/issues).
