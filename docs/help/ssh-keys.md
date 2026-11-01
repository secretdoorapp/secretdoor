# SSH keys

SecretDoor can keep SSH keys in your vault, encrypted like every other item. Use them to store the
key pair you use to sign in to servers, Git hosts and other SSH services, so that it is backed up
and available on your phone.

SecretDoor on Android does **not** act as an SSH agent: it stores and shows the key. Copy the
public key to the server, or copy the private key into the app that needs it.

## Add an SSH key

1. On **My vault**, tap **+** and choose **SSH key**.
2. Give it a name.
3. Either **generate** a new key pair or **import** one you already have (see below).
4. Save.

The item then shows the **public key**, the **fingerprint** and the **private key**. The private key
is hidden until you tap the eye button.

## Generate a key

Choose **Generate key** and pick the type:

| Type | Notes |
| --- | --- |
| **Ed25519** (default) | Short, fast and secure. Supported by all modern servers. |
| **RSA 3072** / **RSA 4096** | Older servers sometimes only accept RSA. 4096 is slower to generate. |
| **ECDSA P-256 / P-384 / P-521** | Elliptic curve keys. |

The key is made on your phone and is not sent anywhere.

## Import a key

Choose **Import key** and paste the private key text, for example
`-----BEGIN OPENSSH PRIVATE KEY-----`. If the key is protected by a passphrase, enter it when
asked. SecretDoor reads the key, then stores the private key (without the passphrase), the
matching public key and the fingerprint. If the app cannot read the key it tells you so and nothing
is saved.

> The stored private key is **not** protected by its old passphrase any more; it is protected by
> your vault's encryption.

## Use the key

- Copy the **public key** and add it to the server or service (for example the "SSH keys" page of
  your Git host).
- Copy the **private key** only when you need to place it into another app. Treat it like a
  password, and consider turning on [master password re-prompt](master-password-reprompt.md) for
  the item.

## Related

- [Backups](backups.md): SSH keys are part of your backups.
- [Security](security.md)
