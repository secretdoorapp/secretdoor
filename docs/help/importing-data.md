# Importing data

There are three ways to bring items into SecretDoor.

## 1. Restore or import a SecretDoor backup

Backups made by SecretDoor (`.secretdoor` files, see [Backups](backups.md)) can be brought back in
three ways:

- **On a fresh install**, choose **Restore from backup** on the Create vault screen. Pick the file
  and enter the vault's master password. The vault is restored as it was.
- **Into your current vault**: in Settings > Vault > Backup (or the menu on the My vault screen)
  choose **Import backup**, then **Add to this vault**. The items of the backup are added to
  the vault.
- **As another vault**: choose **Import backup**, then **Add as a separate vault**, and give it a
  name. The backup keeps its own master password. See [Managing vaults](managing-vaults.md).

You always need the master password of the vault that created the backup.

## 2. Credential Exchange (from another password manager on the same phone)

On phones that support it (Android 14 or newer with a compatible app), another password manager can
send its items to SecretDoor directly through Android's Credential Exchange. Start the transfer in
the other app and choose SecretDoor, or open **Settings > Vault > Import items** in SecretDoor.
Nothing is sent over the internet; the transfer happens on the device.

## 3. Add items yourself

Tap the **+** button on My vault and choose the type of item. Passwords can be generated with the
[generator](generator.md); [SSH keys](ssh-keys.md) can be generated or imported.

## What is not supported yet

- Importing CSV or JSON files exported from other password managers.
- Importing from a Bitwarden account. SecretDoor has no connection to Bitwarden servers. Export from
  Bitwarden to a file, then use a Credential Exchange capable app, or add items by hand.

If you need file import, please say so in an
[issue](https://github.com/secretdoorapp/secretdoor/issues).

## Exporting

**Settings > Vault > Export vault** writes your items to a file (CSV, JSON, or a
password-protected JSON) that other password managers can read. The file is **not** protected like
a backup unless you pick the password-protected format; delete it when you are done.
