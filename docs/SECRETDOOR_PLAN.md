# SecretDoor: Offline Conversion Plan

SecretDoor is a fork of Bitwarden Android (GPLv3) that works without any Bitwarden server. The
vault lives only on the phone, and an encrypted backup file can be written to a location the user
picks (for example, Google Drive) on demand or on a schedule.

## Decisions

| Topic | Decision |
|-------|----------|
| Server features | Remove organizations/collections, Send, emergency access, premium/billing, push, SSO, login with device, two-step login, server environment selection, server feature flags, Firebase. |
| Breach check (HIBP) | Keep as an optional, user-initiated action, off by default. It contacts `api.pwnedpasswords.com` (k-anonymity, partial SHA-1 hash only). |
| Website icons | Remove downloads from `icons.bitwarden.net`. They reveal which sites the user has logins for. |
| Sync model | One-way backup and restore. The phone is the source of truth. |
| Backup format | SecretDoor's own file (see Phase 4): the vault exactly as stored on the device, and the whole file encrypted again with a key derived from the vault's user key. |
| Backup destination | Storage Access Framework folder chosen by the user (Google Drive, local storage, and others); a persisted URI permission is kept. Scheduled backups use WorkManager. |
| Vaults | One vault per device for now; a backup can be merged into it. Separate vaults (switching between several) are planned. |
| Pricing | Free: the whole password manager, plus manually exporting the encrypted backup file to any location (the user can upload it to the cloud themselves). Paid: SecretDoor uploading backups to the cloud itself (on demand or on a schedule). |
| Master password hint | Optional, stored only on the device (not encrypted), shown on the unlock screen via "Show master password hint". |
| App ID | `io.github.zcoklin.secretdoor` (done). Internal Kotlin packages stay `com.x8bit.bitwarden.*` to keep upstream security merges easy. |

## Key Insight

The encrypted vault is already stored locally in Room (`VaultDiskSource`, as
`SyncResponseJson.Cipher` / `Folder` records encrypted by the SDK). Today every write goes
SDK encrypt -> server -> save the server response to disk. SecretDoor drops the middle step:
SDK encrypt -> save to disk. Unlocking with the master password already works offline from locally
stored key material.

## Phases

### Phase 1: Local vault creation and unlock
- Replace the landing/login/registration flow with "Create vault" (master password + hint).
- Create keys locally with `AuthSdkSource.makeRegisterKeys` and local KDF defaults; persist a local
  `UserStateJson` account (generated user ID, no tokens) in `AuthDiskSource`.
- Unlock via existing `vaultunlock` (master password, PIN, biometrics).
- Remove screens: `landing`, `login`, `startregistration`, `checkemail`, `completeregistration`,
  `expiredregistrationlink`, `environment`, `enterprisesignon`, `loginwithdevice`,
  `twofactorlogin`, `trusteddevice`, `removepassword`, `setpassword`, `resetpassword`.
- Key files: `data/auth/repository/AuthRepositoryImpl.kt`, `ui/auth/feature/*`, root navigation.
- Progress:
  - [x] `AuthRepository.createLocalVault` creates keys locally (PBKDF2, salt =
    `<userId>@secretdoor.local`), stores the account, crypto state and a placeholder access token
    (`LOCAL_VAULT_ACCESS_TOKEN`, not a JWT), then unlocks the vault.
  - [x] Auth graph starts at the Complete Registration screen, repurposed as "Create vault"
    (no email, no hint field, breach check off by default); welcome carousel skipped.
  - [x] `VaultSyncManagerImpl.syncInternal` only re-emits local disk data (no server sync).
  - [x] Update unit tests for the changed classes.
  - [x] Delete the now-unreachable login/registration screens (landing, login, start
    registration, check email, expired link, environment, SSO, login with device, two-step login,
    trusted device, set/reset/remove password, welcome, emailed master password hint) and their
    root navigation states.
  - [x] Master password hint stored locally (`AuthDiskSource.storeMasterPasswordHint`), cleared
    on logout, shown on the vault unlock screen.

### Phase 2: Local item and folder storage
- `CipherManagerImpl`, `FolderManagerImpl`: replace `CiphersService` / `FolderService` calls with
  direct `VaultDiskSource` writes (generate IDs and revision dates locally using the injected `Clock`).
- Attachments: store encrypted attachment files in app-private storage.
- `VaultSyncManagerImpl`: remove server sync; the vault flows come straight from disk.
- Import (`importCiphers`) writes to disk instead of the server.
- Progress:
  - [x] `FolderManagerImpl`: create/update/delete write to `VaultDiskSource` (IDs via `UUID`,
    revision dates from `Clock`).
  - [x] `CipherManagerImpl`: create/update/archive/unarchive/soft delete/restore/hard delete
    write to disk; organization share and collection updates return an unsupported error.
  - [x] Attachments: encrypted files stored at `files/attachments/<userId>/<cipherId>/<id>`,
    decrypted from a cache copy when opened, deleted with the attachment or cipher.
  - [x] Credential Exchange import saves ciphers to disk.
  - [ ] Delete attachment files when the vault is deleted (logout / account removal).
  - [x] Logout: "Log out" is removed everywhere (Settings, unlock screen, account switcher) and
    the "Log out" session timeout action is gone (stored values lock instead).
    `UserLogoutManagerImpl` refuses to erase anything unless the reason is
    `LogoutReason.AccountDelete`, so timeouts, too many unlock attempts, biometric changes and
    server signals can never wipe the vault. "Delete account" is now "Delete vault": it checks the
    master password locally and erases the vault.

### Phase 3: Remove server-only features and network code
- Delete organizations, collections, Send, emergency access, billing, push, events, config/feature
  flags, Fill Assist, Key Connector, devices, auth requests, icons.
- Remove Firebase (Crashlytics, FCM) and the `standard` flavor's Google Play-only services where
  they phone home.
- Shrink the `:network` module to only the HIBP client (or remove it).
- Remove the `:authenticator` and `:authenticatorbridge` modules from the build (separate app).
- Progress:
  - [x] Send: delete the Send tab, screens, `SendManager`, Send results/data models, Send search
    and listing types, the `ShareNewSend` special circumstance and the `ACTION_SEND` intent filter.
  - [ ] Send data layer: `VaultDiskSource` Send storage, `VaultSdkSource` Send encrypt/decrypt,
    `VaultSdkSendExtensions` (goes with the `:network` cleanup).
  - [x] Organizations/collections UI (not yet compiled or tested): delete Move to organization /
    Collections screen and menu options, Leave organization, Migrate to My Items (and
    `VaultMigrationManager`), the owner picker, owner bottom sheet and collection selector in
    add/edit.
  - [x] Organizations/collections data layer, part 1 (not yet compiled or tested): vault screen
    collections section, collection item listing (`VaultItemListingType.Collection`), collection
    search types, `VaultFilterType.OrganizationVault`, `OrganizationManager`, and the unused
    `AuthRepository.removePassword` (Key Connector). `OrganizationEventManagerImpl` is now a
    no-op, so organization events are never stored or uploaded.
  - [ ] Organizations/collections data layer, part 2: policies (`PolicyManager`, used in ~60
    files), `selectedCollectionId` / owner state in add/edit, collection permission helpers,
    org/collection SDK extensions. Do this with compiler feedback.
  - [ ] Room tables `CollectionEntity` and `OrganizationEventEntity`: kept (always empty). Dropping
    them needs a database migration and a regenerated schema, so do it with a build.
  - [x] Bug fixed: saving an item no longer requires an internet connection
    (`VaultAddEditViewModel` no longer depends on `NetworkConnectionManager`).
  - [x] Sync UI removed: "Sync" overflow items (vault, item listing, verification codes),
    Settings > Other "Sync now", last sync and "Allow sync on refresh" (pull-to-refresh stays off).
  - [x] Website icons are never downloaded (`isIconLoadingDisabled` is always true) and the
    "Show website icons" switch is gone.
  - [x] Firebase removed: Crashlytics, Firebase Cloud Messaging service, Google services plugin,
    Bitwarden's `google-services.json`. One `LogsManagerImpl` (no crash reporting) for all flavors.
  - [x] Network guard: `AllowedHostsInterceptor` (network module) refuses every request except to
    `api.pwnedpasswords.com` before any connection is made. This also stops leftover calls such as
    the server config fetch in `NetworkConfigManagerImpl`.
  - [x] `:authenticator` app module removed; the Authenticator bridge service is no longer exported
    and "Sync with Authenticator" is hidden.
  - [ ] Delete the unused Authenticator bridge code (`:authenticatorbridge`, bridge
    repository/processor/service, sync unlock key, key rotation).
  - [ ] Remaining server code (services in `:network`, push, auth requests, Key Connector,
    devices, Fill Assist) is unreachable behind the network guard; delete it over time.

### Phase 4: Backup and restore
- Format (version 2, `.secretdoor`): `SECRETDOOR`, a version byte, the header length and a JSON
  header holding only what turns the master password into the user key (KDF settings, salt,
  master-key-wrapped user key, wrapped private key) plus the payload salt and nonce prefix. The
  payload is a ZIP with `manifest.json`, `account.json`, `account_keys.json`, `folders.json`,
  `ciphers.json` and `attachments/<cipherId>/<attachmentId>` (items stay encrypted exactly as on
  the device), encrypted with AES-256-GCM in 1 MiB chunks (STREAM construction, header as
  associated data). The payload key is HKDF-SHA256 of the user key with a per-vault salt. It is
  kept Keystore-encrypted while the vault is unlocked, so automatic backups work while it is
  locked. Without the master password the file shows nothing, not even the number of items. The
  master password hint is no longer included. Version 1 files (plain ZIP) can still be read.
- New vaults use Argon2id (3 iterations, 64 MiB, 4 threads) instead of PBKDF2.
  (The SDK cannot import Bitwarden's encrypted JSON export, so the backup is SecretDoor's own
  format. "Export vault" stays for moving to other password managers.)
- Progress:
  - [x] `VaultBackupManager`: write, read (rejects non-backups, newer versions and unsafe entry
    names) and restore (only when no vault exists; a wrong password rolls everything back).
  - [x] Settings > Vault > Backup: "Save backup file" (`ACTION_CREATE_DOCUMENT`, free).
  - [x] Automatic backup: pick a file once (`ACTION_CREATE_DOCUMENT`, persisted permission; Google
    Drive does not support folder picking), Off/Daily/Weekly via WorkManager
    (`AutoBackupWorker`), "Back up now", last backup status. Each run overwrites the file; Google
    Drive keeps earlier versions.
  - [x] Restore: "Restore from backup" on the Create vault screen (pick file, enter master
    password).
  - [ ] Paid gate: `AutoBackupManager.isAutoBackupAvailable` is `true` until billing exists
    (Google Play Billing for `standard`; F-Droid needs a separate model).
  - [x] Import a backup into the current vault (Backup screen or ☰ "Import backup" on My vault):
    the backup's user key is unlocked in a temporary SDK client, every encrypted string is
    re-encrypted for this vault (only the item key for items that have one) and checked by
    decrypting it before anything is saved. Folders merge by name; an item with the same ID
    keeps the newer copy; items with the same content are skipped; attachments are copied.
  - [ ] Import a backup as a separate vault (multiple vaults with a vault switcher).
  - [ ] Move existing PBKDF2 vaults to Argon2id.

### Phase 5: Branding cleanup
- Replace remaining user-visible "Bitwarden" strings, icons, About screen, links, and README.
- Progress:
  - [x] Icons (option A, "door ajar with light and keyhole"): launcher foreground and
    monochrome (main/beta/release), navy launcher/splash background (beta purple, debug red),
    splash icon, autofill/suggestion logo, notification icon, Import logins shield glyph.
    Illustrations recolored to navy/tan/gold. Unused `logo_bitwarden` deleted.
    Review page and generators: `design/icon-review/`, `design/icon-tools/`.
  - [x] "Bitwarden" replaced by "SecretDoor" in the text of every string resource (all
    languages); resource names unchanged.
  - [x] About: help center and privacy links point to the GitHub README; Web vault, Learn about
    organizations and Submit crash logs rows removed; copyright line credits Bitwarden (GPLv3).
  - [x] README rewritten for SecretDoor (features, privacy, backups, building, license).

## Build Prerequisites
- `user.properties` with `gitHubToken=<classic PAT with read:packages>` to download
  `com.bitwarden:sdk-android` from GitHub Packages.
- Prefer the `fdroid` flavor (no Firebase) for development: `./gradlew app:assembleFdroidDebug`.
