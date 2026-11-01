# URI match detection

A login can have one or more **websites or apps** (URIs) saved with it. When you open a website or
app and use autofill, SecretDoor suggests the logins whose saved URI **matches** the one you are
on. *Match detection* is the rule that decides what "matches" means. You can set a default for all
logins and change it for a single URI.

## The options

| Option | A saved URI matches when... |
| --- | --- |
| **Base domain** (default) | the top-level and second-level domain are the same. `https://login.example.com` matches `https://www.example.com`. |
| **Host** | the host name (and port, if given) is the same. `https://login.example.com` does not match `https://www.example.com`. |
| **Starts with** | the address you are on begins with the saved text. |
| **Exact** | the address is identical. |
| **Regular expression** | the address matches the pattern you saved. Advanced; a wrong pattern can match too much. |
| **Never** | never. The URI is kept for reference only. |

## Choosing a setting

- **Base domain** is convenient but also the broadest. A login saved for `example.com` is offered
  on every subdomain of it.
- Use **Host** or **Exact** for sites where different subdomains are different services, or when
  you want fewer, safer suggestions.
- Use **Never** to keep a URI on a login without ever offering it for autofill.

## Where to set it

- Default for all logins: **Settings > Autofill > Default URI match detection**.
- For one URI: edit the login, tap the gear next to the website, and pick a match detection.

## Related

- [Autofill](autofill.md)
