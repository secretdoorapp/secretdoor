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
| Backup format | Bitwarden password-protected encrypted JSON (`ExportFormat.EncryptedJson`), already produced by the SDK. |
| Backup destination | Storage Access Framework folder chosen by the user (Google Drive, local storage, and others); a persisted URI permission is kept. Scheduled backups use WorkManager. |
| Vaults | One vault per device. |
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
  - [ ] Logout currently deletes the local vault (there is no server copy); decide whether to
    hide or rename "Log out" before release.

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
  - [ ] Sync UI (vault and item listing pull-to-refresh, "Sync" menu, Settings > Other "Sync
    now") still shows "Internet connection required" when offline. Remove the sync UI.

### Phase 4: Backup and restore
- Free: "Export encrypted backup" saves the password-protected file wherever the user picks
  (`ACTION_CREATE_DOCUMENT`); the user can upload it anywhere themselves.
- Paid: Settings > Backup: choose a cloud folder (`ACTION_OPEN_DOCUMENT_TREE`, persisted
  permission), set a backup password, "Back up now", and a schedule (off/daily/weekly) run by
  WorkManager. Gate this behind a paid entitlement (billing approach TBD; Google Play Billing for
  the `standard` flavor, F-Droid needs a separate model).
- Restore: pick a backup file (`ACTION_OPEN_DOCUMENT`), enter its password, import into the vault.
- Backup files are named with a timestamp; keep the last N.

### Phase 5: Branding cleanup
- Replace remaining user-visible "Bitwarden" strings, icons, About screen, links, and README.

## Build Prerequisites
- `user.properties` with `gitHubToken=<classic PAT with read:packages>` to download
  `com.bitwarden:sdk-android` from GitHub Packages.
- Prefer the `fdroid` flavor (no Firebase) for development: `./gradlew app:assembleFdroidDebug`.
