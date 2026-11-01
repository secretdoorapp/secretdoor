# Authenticator codes (TOTP)

Many websites protect your account with a second step: a six-digit code that changes every 30
seconds. SecretDoor can generate these codes for you, so you do not need a separate authenticator
app. The codes are calculated on your phone from a secret called the **authenticator key**.

## Add an authenticator key to a login

1. Open (or create) the login and tap **Edit**.
2. In the **Authenticator key** field, either paste the key, or use the scan button to read the
   QR code that the website shows when you set up two-step login.
3. Save.

The code appears on the login and in the **Verification codes** list. Tap it to copy.

## Use the code

- Open the login and copy the current code.
- With autofill, SecretDoor can copy the code to the clipboard right after it fills the password.
  This is the **Copy TOTP automatically** option in Settings > Autofill.

## Supported keys

Standard `otpauth://` links and plain Base32 keys work. Steam keys (`steam://`) are supported too.
Optional parameters in the link, such as the number of digits, the period and the algorithm
(SHA-1, SHA-256 or SHA-512), are honored.

## Things to know

- Codes depend on your phone's clock. If a website rejects a correct-looking code, check that
  automatic date and time is turned on in Android's settings.
- The authenticator key is stored encrypted in your vault, like a password, and is included in
  [backups](backups.md).
- Keeping your password and your second step in the same vault is convenient but means that someone
  who gets into your vault gets both. For your most important accounts, consider keeping the
  second step somewhere else.
