# Security policy

SecretDoor protects passwords, so security reports are taken seriously.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability.

Use GitHub's private reporting instead:
[Report a vulnerability](https://github.com/secretdoorapp/secretdoor/security/advisories/new).
Only the maintainers can see the report.

Please include what you found, the steps to reproduce it, the app version (Settings > About) and
how it could be abused. If you can, say what you think the fix could be.

## What to expect

- You will get an answer to your report as soon as the maintainers can; this is a small project, so
  please be patient.
- We will tell you when the problem is fixed and credit you in the release notes if you wish.
- Please give us a reasonable amount of time to fix the problem before you tell anyone else.

## Scope

In scope: the SecretDoor Android app in this repository: vault encryption and storage, unlocking,
backups, autofill, passkeys, and the permissions the app uses.

Out of scope:
- The Bitwarden SDK itself, `com.bitwarden:sdk-android`, is made by Bitwarden, Inc. and lives in
  [bitwarden/sdk-internal](https://github.com/bitwarden/sdk-internal). Report problems in it to
  Bitwarden through their program, and tell us too so that we can update.
- Attacks that need a rooted or already compromised phone, or physical access to an unlocked phone.
- Denial of service, spam, and social engineering.

## Supported versions

Only the latest release receives security fixes.
