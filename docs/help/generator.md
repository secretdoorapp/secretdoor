# Generator

The generator creates strong random passwords, passphrases and usernames. Open it from the
**Generator** tab, or tap the generate button while editing a login.

## Password

Random characters. You can choose the length and which kinds of characters to include (upper case,
lower case, numbers, special characters), a minimum number of numbers and special characters, and
whether to avoid look-alike characters such as `l` and `1`.

Longer is stronger. 16 or more characters is a good default for accounts you protect with a
password manager.

## Passphrase

Several random words joined by a separator, for example `canal-ruby-opens-4-lumber`. You can choose
the number of words, the separator, capitalization, and whether to add a number. Passphrases are
easier to type and remember than random characters, so they suit your master password or a device
you have to type into.

## Username types {#username-types}

The username generator can create:

- **Plus addressed email**: `you+random@example.com`. Mail still reaches `you@example.com`, but
  each site gets its own address so you can tell where spam comes from.
- **Catch-all email**: `random@yourdomain.com`, for domains where you receive all mail.
- **Random word**: a random word, for sites that want a username rather than an email.
- **Forwarded email alias**: asks an outside email-forwarding service (for example SimpleLogin or
  Firefox Relay) to create an alias. This needs an internet connection and an account with that
  service, and SecretDoor does not connect to such services, so this type does not work in the app.

## History

Passwords you generate are kept in **Password history** (in the generator menu) so that you can
recover one you did not save. The history is encrypted in your vault and is deleted with it.

## Related

- [Security](security.md)
