# Autofill

Autofill lets SecretDoor fill your logins and cards into other apps and websites. You set it up
once in Android's settings. The vault must be unlocked to fill.

## Set up autofill

1. Open **Settings > Autofill** and turn on **Autofill services**.
2. In Android's dialog, choose **SecretDoor** as the autofill service.
3. Optionally use the inline suggestions option on the same screen to see suggestions in the
   keyboard.

SecretDoor can also be set up as a **credential provider** (passkeys and passwords in Android
Credential Manager) from the same screen.

## Browsers {#browsers}

Most browsers fill through Android's autofill service once it is set up. Some browsers need an
extra setting before they allow third-party autofill:

1. Open **Settings > Autofill** and use **Turn on browser autofill integration** (shown when a
   supported browser is installed).
2. Follow the prompt to open the browser's settings and enable "Autofill services" or "Third-party
   autofill".

If your browser is not supported, SecretDoor cannot fill it. Copy the username and password from
the item instead. SecretDoor does not use an accessibility service and never reads what is on your
screen.

## Troubleshooting {#troubleshooting}

- **No suggestions appear.** Check that SecretDoor is the selected autofill service in Android's
  settings and that the vault is unlocked. Check the login's website matches ([URI match
  detection](uri-match-detection.md)).
- **It works in some apps but not others.** Some apps block autofill on purpose, mostly banking
  apps. Copy and paste from the item instead.
- **The browser ignores autofill.** Enable third-party autofill in the browser's settings (see
  above), and restart the browser.
- **Suggestions show for the wrong site.** Tighten the login's [match detection](uri-match-detection.md)
  or add the correct website.
- **Autofill stops after a while.** Some phones stop background services to save battery. Exclude
  SecretDoor from battery optimization in Android's settings.
- **Never fill on a site.** Add it under **Settings > Autofill > Block autofill**.

## Related

- [Master password re-prompt](master-password-reprompt.md)
- [Authenticator codes](authenticator-codes.md)
