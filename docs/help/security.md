# Security

This page explains how SecretDoor protects your vault and where the limits are.

## Encryption

SecretDoor uses the same encryption library as Bitwarden (the Bitwarden SDK), which has been
through third-party security audits for Bitwarden. SecretDoor itself, and the changes made to
turn it into an offline app, have not been independently audited.

1. You choose a **master password** when you create a vault.
2. A **master key** is derived from the password with **Argon2id** (3 iterations, 64 MiB of memory,
   4 lanes). This is deliberately slow and memory-hungry to make guessing expensive.
3. The master key unlocks a random **vault key** (an AES-256 encryption key plus an HMAC
   authentication key), which encrypts every item. The vault key is stored on the phone only in
   encrypted form.
4. Items are stored in the phone's database as encrypted text. Item names, notes, usernames and
   everything else are encrypted; the app keeps only a few non-secret values in the clear (such as
   which folder an item is in).

Your master password is never stored. If you forget it and have no unlock method turned on, the
vault cannot be opened, and nobody, including the developers, can recover it.

## Unlocking

- **Master password**: always works.
- **PIN** and **biometrics** (optional): the app keeps a copy of the vault key encrypted with a key
  held in the Android hardware keystore. These are convenient but weaker than the master password;
  for example, someone who knows your PIN and has your phone can unlock. SecretDoor locks the vault
  after the timeout you pick in **Settings > Account security**.

## Backups

Backup files are encrypted as a whole with AES-256-GCM, using a key derived from your vault key and a
per-vault random salt. The format is described in the source code
(`data/vault/manager/VaultBackupManagerImpl.kt` and `BackupCrypto.kt`). Because someone with a
backup file can try guessing offline, **a long master password matters**.

## What SecretDoor does not protect against

- A phone that is already compromised (malware that can read the screen or the clipboard, or an
  accessibility service you did not intend to enable).
- Someone looking over your shoulder, or a weak master password.
- Items you copy to the clipboard: other apps may be able to read the clipboard on some Android
  versions, so paste sensitive values promptly.
- Backups you leave unprotected next to the phone.

## Reporting a vulnerability

Please do not post vulnerabilities in a public issue. Use the repository's private
[security advisory](https://github.com/secretdoorapp/secretdoor/security/advisories/new) form.
