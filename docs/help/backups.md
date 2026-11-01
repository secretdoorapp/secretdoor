# Backups

Your vault exists only on your phone. If you lose the phone, reset it or uninstall SecretDoor
without a backup, **the vault is gone**, and SecretDoor cannot recover it for you because it has no
copy. Make a backup, and keep it somewhere other than the phone.

## What a backup is

A backup is one file with the extension `.secretdoor`. It contains your whole vault (items,
folders and attachments) and is encrypted as a whole: without your master password it is just
random-looking data. The master password is **not** stored in the file.

You need the master password of the vault to restore a backup. If you later change the master
password, older backups still need the old one.

## Save a backup by hand

**Settings > Vault > Backup > Save backup file.** Pick where to save it, for example a folder in
Google Drive, a USB drive or another phone. The file name is the vault name, the date and the time.

## Automatic backups

**Settings > Vault > Backup > Automatic backup.** Pick a file once and a frequency (off, daily or
weekly). SecretDoor then rewrites that file on schedule, even when the vault is locked. A cloud
folder that keeps earlier versions (Google Drive does) gives you several restore points.

Automatic backups use the vault's own key, so they need no password at backup time.

## Restore

- **New phone or fresh install**: on the Create vault screen choose **Restore from backup**.
- **Another vault on the same phone**: **Import backup**, then add it to the current vault or as a
  separate vault. See [Importing data](importing-data.md) and [Managing vaults](managing-vaults.md).

## Good habits

- Keep at least one backup that is not on the phone.
- Test a restore after you set things up.
- Remember that anyone with the file can try to guess the master password. Use a long master
  password. See [Security](security.md).
