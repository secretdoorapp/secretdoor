# SecretDoor for iPhone

A native SwiftUI version of SecretDoor, the offline password manager. The vault lives only on
the iPhone, encrypted with the master password, and is never sent anywhere.

It uses the same data formats as SecretDoor for Android: a `.secretdoor` backup made on one can
be restored on the other.

## Features

- Create a vault (Argon2id: 3 iterations, 64 MiB, 4 threads, the same as Android) or restore one
  from a `.secretdoor` backup made on iPhone or Android (format versions 1 and 2).
- Unlock with the master password or Face ID / Touch ID; auto-lock timeout; the screen is hidden
  in the app switcher.
- Logins (usernames, passwords, websites, authenticator keys with live TOTP codes), cards,
  identities, secure notes, SSH keys, bank accounts, driver's licenses and passports; custom
  fields, favorites, folders, trash, password history, master password re-prompt and encrypted
  attachments.
- Search, password and passphrase generator (EFF long wordlist).
- Copied values stay off other devices (no Universal Clipboard) and are cleared after a set time.
- Backups: save a backup file anywhere; automatic daily/weekly backups to a folder you pick (for
  example in iCloud Drive); import a backup into the current vault (merge) or as a separate vault.
- Several vaults, each with its own master password; change master password; delete vault.
- AutoFill in Safari and other apps (extension `SecretDoorAutoFill`): unlock with Face ID or the
  master password, logins matching the website first (Bitwarden URI match detection), search,
  master password re-prompt, optional copying of the verification code, and optional
  suggestions above the keyboard (iOS gets usernames and websites of the unlocked vault, never
  passwords). Turn it on in Settings > General > AutoFill & Passwords.

Not yet on iPhone: PIN unlock, passkeys (passkeys stored by the Android app are kept), import
from other password managers and export to CSV/JSON.

## Building

Requires Xcode 26 or later. Open `SecretDoor.xcodeproj`, choose an iPhone simulator and press
Run. To install on your own iPhone, select your team under *Signing & Capabilities* for both
targets, `SecretDoor` and `SecretDoorAutoFill`. Both use the App Group `group.app.secretdoor`,
a shared Keychain group and the AutoFill credential provider capability
(`Config/*.entitlements`); Xcode registers them for your team.

The Debug configuration is built with optimization (`-O`) because the Argon2id key derivation is
far too slow without it (several seconds instead of about half a second).

## Code layout

- `Shared/Core`: everything that does not need UIKit, tested on the Mac (also compiled into the
  AutoFill extension):
  - `Crypto`: AES, HMAC, HKDF, PBKDF2, BLAKE2b and Argon2id (Swift implementation, checked
    against the RFC 9106 test vector), Bitwarden encrypted strings (`2.iv|data|mac`), master key
    derivation and user key unwrapping.
  - `Storage`: JSON that keeps unknown fields (records from Android are stored and written back
    unchanged) and a small ZIP reader/writer.
  - `Backup`: the `.secretdoor` file format (header, AES-256-GCM chunks in the STREAM
    construction), the same as `VaultBackupManagerImpl.kt` and `BackupCrypto.kt`.
  - `Vault`: item models, item encryption/decryption, vault files on the device, restore,
    merge and website matching for AutoFill.
  - `Tools`: TOTP and the generator.
- `Shared/Platform`: Keychain (Face ID key, automatic backup key), the shared App Group
  container and settings, AutoFill suggestions.
- `SecretDoor/App`: app state and automatic backups.
- `SecretDoor/Views`: SwiftUI screens.
- `SecretDoorAutoFill`: the AutoFill extension.
- `Config`: Info.plist files and entitlements.
- `CoreTests`: a Swift package that compiles `Shared/Core` (through a symlink) and tests it:

  ```sh
  cd CoreTests && swift test
  ```

## Storage

Each vault is a folder in the App Group container (`Vaults/<vault id>/`; versions before
AutoFill used Application Support and are moved on first launch) holding the same encrypted
records as the Android app: `account.json`, `account_keys.json`, `folders.json`, `ciphers.json`
and `attachments/<item id>/<attachment id>`. Everything in them is encrypted with the vault's
user key. Only `meta.json` (vault name, hint, last backup) is plain.

The Keychain holds, per vault and only on this device: the user key for Face ID (invalidated when
Face ID enrollment changes) and the backup file key, so automatic backups work while the vault is
locked.

## License

GNU GPL v3, like the rest of SecretDoor. See `../LICENSE.txt` and `../NOTICE.md`.
