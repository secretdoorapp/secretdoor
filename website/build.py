#!/usr/bin/env python3
"""Builds the secretdoor.fyi website into this folder.

The site is plain static HTML and CSS: the generated .html files are committed, so deploying needs
no build step. Run this script only after editing `docs/` or the pages below:

    python3 website/build.py

Manual topic pages (`/manual/<topic>/`) and the privacy page (`/privacy/`) are generated from the
Markdown files in `docs/`, so the repository and the website never drift apart.
"""
import html
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(ROOT)
DOCS = os.path.join(REPO, "docs")
SITE = "https://secretdoor.fyi"
GITHUB = "https://github.com/secretdoorapp/secretdoor"
CONTACT = "contact@secretdoor.fyi"

# --------------------------------------------------------------------------- Markdown

def slug(text):
    """GitHub-style heading anchor."""
    text = re.sub(r"<[^>]+>", "", text).lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.strip().replace(" ", "-")


def map_link(href, src):
    """Turns a link inside a docs/*.md file into a website (or GitHub) link."""
    if re.match(r"^(https?:|mailto:|#)", href):
        return href
    path, _, anchor = href.partition("#")
    target = os.path.normpath(os.path.join(os.path.dirname(src), path))
    rel = os.path.relpath(target, REPO).replace(os.sep, "/")
    suffix = "#" + anchor if anchor else ""
    if rel == "docs/README.md":
        return "/manual/" + suffix
    if rel == "docs/PRIVACY.md":
        return "/privacy/" + suffix
    m = re.match(r"^docs/help/([\w-]+)\.md$", rel)
    if m:
        return "/manual/%s/%s" % (m.group(1), suffix)
    return "%s/blob/main/%s%s" % (GITHUB, rel, suffix)


def inline(text, src):
    codes = []

    def stash(m):
        codes.append("<code>%s</code>" % html.escape(m.group(1)))
        return "\x00%d\x00" % (len(codes) - 1)

    text = re.sub(r"`([^`]+)`", stash, text)
    text = html.escape(text, quote=False)
    text = re.sub(r"&lt;(https?://[^&\s]+|[\w.+-]+@[\w.-]+)&gt;",
                  lambda m: '<a href="%s">%s</a>' % (
                      ("mailto:" if "@" in m.group(1) and "://" not in m.group(1) else "") + m.group(1),
                      m.group(1)), text)

    def link(m):
        href = map_link(html.unescape(m.group(2)), src)
        ext = ' rel="noopener"' if href.startswith("http") and "secretdoor.fyi" not in href else ""
        return '<a href="%s"%s>%s</a>' % (html.escape(href), ext, m.group(1))

    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)


def markdown(md, src):
    """A small Markdown converter: headings, paragraphs, lists, tables, code blocks."""
    lines = md.split("\n")
    out, i, title = [], 0, None
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        if line.startswith("```"):
            code = []
            i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                code.append(lines[i])
                i += 1
            i += 1
            out.append("<pre><code>%s</code></pre>" % html.escape("\n".join(code)))
            continue
        m = re.match(r"^(#{1,4})\s+(.*?)(?:\s+\{#([\w-]+)\})?\s*$", line)
        if m:
            level, text, ident = len(m.group(1)), m.group(2), m.group(3)
            if level == 1 and title is None:
                title = re.sub(r"[`*]", "", text)
                i += 1
                continue
            ident = ident or slug(text)
            out.append('<h%d id="%s">%s</h%d>' % (level, ident, inline(text, src), level))
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip().strip("|").split("|")])
                i += 1
            head, body = rows[0], rows[2:]
            t = ["<div class=\"table-wrap\"><table><thead><tr>"]
            t += ["<th>%s</th>" % inline(c, src) for c in head]
            t.append("</tr></thead><tbody>")
            for r in body:
                t.append("<tr>" + "".join("<td>%s</td>" % inline(c, src) for c in r) + "</tr>")
            t.append("</tbody></table></div>")
            out.append("".join(t))
            continue
        lm = re.match(r"^(\s*)([-*]|\d+\.)\s+(.*)$", line)
        if lm:
            ordered = lm.group(2)[0].isdigit()
            items = []
            while i < len(lines):
                lm = re.match(r"^([-*]|\d+\.)\s+(.*)$", lines[i])
                if not lm:
                    break
                buf = [lm.group(2)]
                i += 1
                while i < len(lines) and lines[i].startswith("  ") and lines[i].strip():
                    buf.append(lines[i].strip())
                    i += 1
                items.append(" ".join(buf))
                while i < len(lines) and not lines[i].strip() and i + 1 < len(lines) \
                        and re.match(r"^([-*]|\d+\.)\s+", lines[i + 1]):
                    i += 1
            tag = "ol" if ordered else "ul"
            out.append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % inline(x, src) for x in items), tag))
            continue
        para = []
        while i < len(lines) and lines[i].strip() and not re.match(r"^(#{1,4}\s|```|\||([-*]|\d+\.)\s)", lines[i]):
            para.append(lines[i].strip())
            i += 1
        out.append("<p>%s</p>" % inline(" ".join(para), src))
    return title or "SecretDoor", "\n".join(out)


# --------------------------------------------------------------------------- Layout

NAV = [("/", "Home"), ("/manual/", "Manual"), ("/privacy/", "Privacy"), ("/support/", "Support")]

PAGES = []  # (url path, lastmod-less) for the sitemap


def page(path, title, body, description, section=None, wide=False):
    """Writes one page. `path` is the URL path, e.g. "/manual/backups/"."""
    PAGES.append(path)
    nav = []
    for href, label in NAV:
        current = ' aria-current="page"' if section == href else ""
        nav.append('<a href="%s"%s>%s</a>' % (href, current, label))
    nav.append('<a class="ext" href="%s" rel="noopener">Source code</a>' % GITHUB)
    full_title = "SecretDoor" if path == "/" else "%s · SecretDoor" % title
    doc = TEMPLATE.format(
        title=html.escape(full_title),
        description=html.escape(description, quote=True),
        canonical=SITE + path,
        nav="\n        ".join(nav),
        body=body,
        contact=CONTACT,
        github=GITHUB,
        mainclass="wide" if wide else "",
    )
    target = os.path.join(ROOT, path.strip("/"), "index.html") if path != "/404" else \
        os.path.join(ROOT, "404.html")
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        f.write(doc)


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{title}</title>
  <meta name="description" content="{description}">
  <link rel="canonical" href="{canonical}">
  <meta property="og:title" content="{title}">
  <meta property="og:description" content="{description}">
  <meta property="og:type" content="website">
  <meta property="og:url" content="{canonical}">
  <meta property="og:image" content="https://secretdoor.fyi/assets/img/og.png">
  <meta name="theme-color" content="#06080c">
  <link rel="icon" type="image/png" href="/assets/img/favicon.png">
  <link rel="apple-touch-icon" href="/assets/img/icon-180.png">
  <link rel="stylesheet" href="/assets/style.css">
</head>
<body>
  <a class="skip" href="#main">Skip to content</a>
  <header class="site-header">
    <div class="bar">
      <a class="brand" href="/"><img src="/assets/img/favicon.png" alt="" width="32" height="32"><span>SecretDoor</span></a>
      <nav aria-label="Main">
        {nav}
      </nav>
    </div>
  </header>
  <main id="main" class="{mainclass}">
{body}
  </main>
  <footer class="site-footer">
    <div class="bar">
      <p><strong>SecretDoor</strong> is free software under the
        <a href="https://www.gnu.org/licenses/gpl-3.0.html" rel="noopener">GNU GPL v3</a>.
        <a href="{github}" rel="noopener">Source code on GitHub</a>.</p>
      <p>It is based on the open source Bitwarden apps and is not affiliated with or endorsed by
        Bitwarden Inc. SecretDoor is provided &ldquo;as is&rdquo;, without warranty. The authors are
        not liable for any loss, exposure or unauthorized access of your personal data that results
        from using the app. Keep your own backups.</p>
      <p><a href="/manual/">Manual</a> · <a href="/privacy/">Privacy</a> ·
        <a href="/support/">Support</a> · <a href="mailto:{contact}">{contact}</a></p>
    </div>
  </footer>
</body>
</html>
"""

# --------------------------------------------------------------------------- Pages

def shot(name, alt, caption=None):
    cap = "<figcaption>%s</figcaption>" % caption if caption else ""
    return ('<figure class="shot"><div class="phone"><img src="/assets/img/shots/%s" alt="%s" '
            'width="540" height="1080" loading="lazy"></div>%s</figure>'
            % (name, html.escape(alt, quote=True), cap))


ICONS = {
    "lock": '<path d="M5 11h14v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1z"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    "shield": '<path d="M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6z"/><path d="M9 12l2 2 4-4"/>',
    "backup": '<path d="M12 16V4"/><path d="M7 9l5-5 5 5"/><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/>',
    "layers": '<path d="M12 3l9 5-9 5-9-5z"/><path d="M3 13l9 5 9-5"/>',
    "key": '<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M16 7l3 3M14 9l2 2"/>',
    "code": '<path d="M8 8l-5 4 5 4M16 8l5 4-5 4M14 5l-4 14"/>',
}


def icon(name):
    return '<span class="icon"><svg viewBox="0 0 24 24" aria-hidden="true">%s</svg></span>' % ICONS[name]


def card(ic, title, text, cyan=False):
    return '<div class="card%s">%s<h3>%s</h3><p>%s</p></div>' % (" cyan" if cyan else "", icon(ic), title, text)


def home():
    body = """
    <section class="hero">
      <div class="hero-text">
        <p class="eyebrow">Offline password manager</p>
        <h1>Your passwords <span class="glow-text">never leave</span> your phone.</h1>
        <p class="lead">SecretDoor keeps your logins, cards, notes and keys in a vault that is
          encrypted on your device. No account. No server. No sync service. No tracking.</p>
        <p class="actions">
          <a class="btn primary" href="#download">Get SecretDoor</a>
          <a class="btn" href="/manual/">Read the manual</a>
          <a class="btn ghost" href="%(github)s" rel="noopener">Source code</a>
        </p>
      </div>
      <div class="orb">
        <img src="/assets/img/icon-512.png" alt="SecretDoor icon: a steel vault door with a padlock" width="380" height="380">
        <span class="chip c1"><b>&#9679;</b>AES-256</span>
        <span class="chip c2"><b>&#9679;</b>ARGON2ID</span>
        <span class="chip c3"><b>&#9679;</b>OFFLINE</span>
        <span class="chip c4"><b>&#9679;</b>OPEN SOURCE</span>
      </div>
    </section>

    <div class="stats" role="list">
      <div class="stat" role="listitem"><strong>0</strong><span>accounts to create</span></div>
      <div class="stat" role="listitem"><strong>0</strong><span>servers holding your data</span></div>
      <div class="stat" role="listitem"><strong>0</strong><span>trackers or analytics</span></div>
      <div class="stat" role="listitem"><strong>256</strong><span>bit AES encryption</span></div>
    </div>

    <section>
      <div class="section-head">
        <p class="kicker">Built different</p>
        <h2>A vault, not a service.</h2>
        <p>Most password managers keep a copy of your vault on their servers. SecretDoor has no
          servers, so there is nothing to breach, sell or switch off.</p>
      </div>
      <div class="cards">
        %(c1)s %(c2)s %(c3)s %(c4)s %(c5)s %(c6)s
      </div>
    </section>

    <section>
      <div class="section-head">
        <p class="kicker">How your data is protected</p>
        <h2>From master password to encrypted vault.</h2>
        <p>Everything happens on your phone. Your master password is never stored and never sent
          anywhere.</p>
      </div>
      <div class="flow">
        <div class="step"><strong>Master password</strong><span>Only you know it</span></div>
        <div class="step"><strong>Argon2id</strong><span>Slow and memory-hungry on purpose, to make guessing costly</span></div>
        <div class="step"><strong>Vault key</strong><span>A random key that only the master key can unlock</span></div>
        <div class="step"><strong>AES-256</strong><span>Every item is encrypted with it</span></div>
        <div class="step end"><strong>Your phone</strong><span>The encrypted vault stays here</span></div>
      </div>
      <p class="flow-note"><strong>Zero knowledge, literally.</strong> We can&rsquo;t read your vault, reset your
        password or recover it, because we never have any of it. See
        <a href="/manual/security/">Security</a> for the details.</p>
    </section>

    <section>
      <div class="section-head">
        <p class="kicker">Inside the app</p>
        <h2>Simple on the surface.</h2>
      </div>
      <div class="shots">
        %(s1)s
        %(s2)s
        %(s3)s
        %(s4)s
      </div>
      <p class="center" style="margin-top:44px"><a href="/manual/">See how it works in the manual &rarr;</a></p>
    </section>

    <section class="split">
      <div class="panel">
        <h2>What leaves your phone?</h2>
        <p>Almost nothing. SecretDoor has no analytics and no crash reporting, and it never
          downloads website icons, because that would tell a server which sites you use. The only
          optional network use is a breach check for a new master password. It is off by default and
          sends just the first five characters of a hash, never the password.</p>
        <p><a href="/privacy/">Read the full privacy policy &rarr;</a></p>
      </div>
      <div class="panel hot">
        <h2>One thing to know.</h2>
        <p>Because there is no server, <strong>nobody can recover your vault or reset your master
          password</strong>, not even us. Make a backup and keep it somewhere other than your phone.</p>
        <p><a href="/manual/backups/">How backups work &rarr;</a></p>
      </div>
    </section>

    <section id="download">
      <div class="cta-band">
        <p class="kicker">Get SecretDoor</p>
        <h2>Take your passwords back.</h2>
        <p class="lead" style="margin:0 auto">Free and open source. No account needed.</p>
        <div class="badges">
          <div class="badge"><small>Android</small>Coming soon to Google Play</div>
          <div class="badge"><small>iPhone</small>May follow</div>
        </div>
        <p class="actions" style="justify-content:center">
          <a class="btn" href="%(github)s" rel="noopener">Read the source code</a>
          <a class="btn ghost" href="/manual/">Read the manual</a>
        </p>
      </div>
    </section>
    """ % {
        "github": GITHUB,
        "c1": card("lock", "Local only", "Your vault lives on your phone and nowhere else. There is nothing to sign in to and no company that holds a copy of your data."),
        "c2": card("shield", "Strongly encrypted", "A key only your master password can unlock, using Argon2id and AES-256 through the audited Bitwarden SDK.", True),
        "c3": card("backup", "Backups you control", "Save an encrypted backup file anywhere, or schedule daily or weekly backups to a file or cloud folder you choose, such as Google Drive."),
        "c4": card("layers", "More than passwords", "Logins, cards, identities, secure notes, SSH keys, bank accounts, driver&rsquo;s licenses, passports and passkeys, with built-in authenticator codes.", True),
        "c5": card("key", "Autofill and biometrics", "Fill logins in apps and browsers, and unlock with your fingerprint or face."),
        "c6": card("code", "Free and open source", "Every feature is free. The code is public under the GNU GPL v3, so anyone can check what the app does.", True),
        "s1": shot("vault.webp", "The My vault screen listing item types and saved items", "Your vault"),
        "s2": shot("generator.webp", "The password generator", "Password generator"),
        "s3": shot("add-login.webp", "The Add login screen", "Add a login"),
        "s4": shot("backup.webp", "The Backup screen", "Backups you control"),
    }
    page("/", "SecretDoor", body,
         "SecretDoor is an offline password manager for Android, with an iPhone version that may be "
         "coming soon. Your vault is encrypted on your device, with no account, no server and no "
         "tracking.", wide=True)


MANUAL_TOPICS = [
    ("Basics", [
        ("backups", "Backups", "Protect yourself against losing your phone."),
        ("managing-vaults", "Managing vaults", "Rename, delete and add vaults."),
        ("security", "Security", "How the vault is encrypted and what it cannot protect you from."),
    ]),
    ("Using SecretDoor", [
        ("autofill", "Autofill", "Fill logins and cards in apps and browsers."),
        ("generator", "Generator", "Passwords, passphrases and usernames."),
        ("authenticator-codes", "Authenticator codes", "Two-step verification codes."),
        ("ssh-keys", "SSH keys", "Generate or import SSH keys."),
        ("uri-match-detection", "URI match detection", "Which logins show up for a website or app."),
        ("master-password-reprompt", "Master password re-prompt", "Ask again before sensitive items are used."),
        ("importing-data", "Importing data", "Bring in items from a backup or another app."),
        ("flight-recorder", "Flight recorder", "Diagnostic logs for bug reports."),
        ("premium", "Premium", "What “Premium” means in SecretDoor."),
    ]),
]


def manual_index():
    sections = []
    for heading, topics in MANUAL_TOPICS:
        items = "".join('<li><a href="/manual/%s/">%s<span>%s</span></a></li>' % (s, t, d)
                        for s, t, d in topics)
        sections.append("<h3>%s</h3><ul class=\"topics\">%s</ul>" % (heading, items))
    topics_html = "\n".join(sections)

    body = """
    <article class="doc">
      <h1>SecretDoor manual</h1>
      <p class="lead">How SecretDoor works, from creating your vault to backing it up. The screens
        below are from the Android app. An iPhone version may be coming soon.</p>

      <nav class="toc" aria-label="On this page">
        <strong>On this page</strong>
        <ol>
          <li><a href="#how-it-works">How it works</a></li>
          <li><a href="#create-vault">Create your vault</a></li>
          <li><a href="#unlock">Unlock with a PIN or biometrics</a></li>
          <li><a href="#your-vault">Your vault</a></li>
          <li><a href="#add-items">Add items</a></li>
          <li><a href="#generator">Generate passwords</a></li>
          <li><a href="#autofill">Turn on autofill</a></li>
          <li><a href="#backup">Back up your vault</a></li>
          <li><a href="#settings">Settings</a></li>
          <li><a href="#topics">More topics</a></li>
        </ol>
      </nav>

      <h2 id="how-it-works">How it works</h2>
      <p>SecretDoor is a password manager with <strong>no server</strong>. Your vault is a set of
        encrypted records stored in the app on your phone. You unlock it with a master password
        that only you know; the password is never stored and never sent anywhere.</p>
      <ul>
        <li>There is <strong>no account</strong> and no sign-in. Installing the app and creating a
          vault is all it takes.</li>
        <li>Items are encrypted before they are saved. Names, usernames, notes and everything else
          are unreadable without your master password.</li>
        <li>Because nothing is stored online, <strong>a lost phone means a lost vault</strong>
          unless you made a backup. SecretDoor cannot recover it for you.</li>
      </ul>

      <h2 id="create-vault">Create your vault</h2>
      <p>On first launch you choose a <strong>master password</strong> for a new vault. Pick a long
        one, preferably a few random words: it is the only thing protecting your data, and anyone
        who gets a copy of your backup can try to guess it. You can add an optional hint, which is
        stored only on your phone and shown on the unlock screen if you tap
        &ldquo;Show master password hint&rdquo;.</p>
      <p>If you already have a SecretDoor backup (from an old phone, or from the other platform),
        choose <strong>Restore from backup</strong> instead and enter that vault&rsquo;s master
        password.</p>
      <div class="note"><strong>Don&rsquo;t forget it.</strong> There is no &ldquo;forgot password&rdquo;
        link. Nobody, including the developers, can reset or recover a master password.</div>

      <h2 id="unlock">Unlock with a PIN or biometrics</h2>
      <div class="side">
        %(unlock)s
        <div>
          <p>Typing a long master password every time is tiring, so SecretDoor offers two
            shortcuts: <strong>biometrics</strong> (fingerprint or face) and a <strong>PIN
            code</strong>. Both are optional and you can turn them on during setup or later in
            <em>Settings &gt; Account security</em>.</p>
          <p>They are convenient but a little weaker than the master password: someone who knows
            your PIN and has your phone could unlock the vault. The master password always works,
            and the vault locks itself after the timeout you choose. After a restart the master
            password is required.</p>
          <p>More in <a href="/manual/security/">Security</a>.</p>
        </div>
      </div>

      <h2 id="your-vault">Your vault</h2>
      <div class="side">
        %(vault)s
        <div>
          <p>The <strong>My vault</strong> tab lists everything you have saved. At the top the
            <strong>Types</strong> section counts items by kind: logins, cards, identities, secure
            notes and SSH keys. Below that are your folders and the items that are not in a folder.</p>
          <ul>
            <li>Tap an item to see its details, copy a value, or open its website.</li>
            <li>Use the <strong>search</strong> icon to find items by name, username or website.</li>
            <li>The <strong>+</strong> button adds a new item.</li>
            <li>Mark items as <strong>favorites</strong> and sort them into <strong>folders</strong>
              to keep things tidy. Deleted items go to the <strong>trash</strong> first.</li>
            <li>The vault picker next to the title appears when you have more than one vault. See
              <a href="/manual/managing-vaults/">Managing vaults</a>.</li>
          </ul>
        </div>
      </div>

      <h2 id="add-items">Add items</h2>
      <div class="side">
        %(addlogin)s
        <div>
          <p>Tap <strong>+</strong> and choose the kind of item. A <strong>login</strong> has a name,
            an optional folder, a username, a password and one or more websites. Next to the
            username and password fields you will find buttons to <strong>generate</strong> a new
            value and to <strong>show or hide</strong> it.</p>
          <ul>
            <li><strong>Authenticator key</strong>: paste or scan the key from a website&rsquo;s
              two-step setup and SecretDoor shows the live six-digit codes.
              <a href="/manual/authenticator-codes/">Learn more</a>.</li>
            <li><strong>Website (URI)</strong>: tells autofill when to offer this login. The gear
              icon sets how strictly addresses must match.
              <a href="/manual/uri-match-detection/">Learn more</a>.</li>
            <li><strong>Check password for data breaches</strong>: an optional lookup that sends only
              a short partial hash, never the password.</li>
          </ul>
          <p>Other item types are cards, identities, secure notes, SSH keys, bank accounts,
            driver&rsquo;s licenses and passports. You can also add custom fields, attachments and
            ask for the master password again before a sensitive item is shown
            (<a href="/manual/master-password-reprompt/">re-prompt</a>).</p>
        </div>
      </div>

      <h2 id="generator">Generate passwords</h2>
      <div class="side">
        %(generator)s
        <div>
          <p>The <strong>Generator</strong> tab creates strong random values. Choose
            <strong>Password</strong>, <strong>Passphrase</strong> (random words, easier to type) or
            <strong>Username</strong>. Set the length and which character types to include, tap the
            circular arrows for a new one and the copy icon to copy it.</p>
          <p><strong>Create login</strong> saves the generated value straight into a new login.
            Earlier values are kept in the generator history from the menu.
            <a href="/manual/generator/">More about the generator</a>.</p>
        </div>
      </div>

      <h2 id="autofill">Turn on autofill</h2>
      <div class="side">
        %(autofill)s
        <div>
          <p>With autofill on, SecretDoor offers your saved login when you tap a username or
            password field in an app or browser, so you don&rsquo;t have to switch apps and copy.
            Android asks you to choose SecretDoor as the autofill service. You can do this during
            setup, or later in <em>Settings &gt; Autofill</em>.</p>
          <p>Browsers need an extra step, and a few apps don&rsquo;t support autofill and need the
            optional accessibility method. Both are covered in
            <a href="/manual/autofill/">Autofill</a>.</p>
        </div>
      </div>

      <h2 id="backup">Back up your vault</h2>
      <div class="side">
        %(backup)s
        <div>
          <p>Open <em>Settings &gt; Vault &gt; Backup</em>. A backup is one <code>.secretdoor</code>
            file holding your whole vault, encrypted as a whole.</p>
          <ul>
            <li><strong>Save backup file</strong> writes a backup wherever you choose: a Google Drive
              folder, a USB drive, another phone.</li>
            <li><strong>Automatic backup</strong>: pick a file once and a frequency (daily or
              weekly). SecretDoor then rewrites it on schedule, even while the vault is locked. A
              cloud folder that keeps old versions gives you several restore points.</li>
            <li><strong>Import backup</strong> adds the items from a backup to this vault or as a
              separate vault. On a new phone, use <em>Restore from backup</em> when you create your
              vault.</li>
          </ul>
          <p>Keep at least one backup that is not on the phone, and try a restore once to make sure
            it works. Details in <a href="/manual/backups/">Backups</a>.</p>
        </div>
      </div>
      <div class="note warn"><strong>Exports are different.</strong> If you export your vault as a
        plain file (for example to move to another app), that file is <em>not</em> encrypted: anyone
        who gets it can read all of your passwords. Delete it when you are done.</div>

      <h2 id="settings">Settings</h2>
      <div class="side">
        %(settings)s
        <div>
          <p>The <strong>Settings</strong> tab has these sections:</p>
          <ul>
            <li><strong>Account security</strong>: unlock with biometrics or a PIN, the vault timeout
              and what happens when it expires, and changing your master password.</li>
            <li><strong>Autofill</strong>: autofill service, browser and accessibility options.</li>
            <li><strong>Vault</strong>: backups, import, export, folders and managing vaults.</li>
            <li><strong>Appearance</strong>: language, theme (light, dark or system) and more.</li>
            <li><strong>Other</strong>: clipboard clearing and similar options.</li>
            <li><strong>About</strong>: version, this manual, the privacy policy, the source code
              and the license.</li>
          </ul>
        </div>
      </div>

      <h2 id="topics">More topics</h2>
      %(topics)s
    </article>
    """ % {
        "unlock": shot("unlock.webp", "The Set up unlock screen with switches for biometrics and PIN"),
        "vault": shot("vault.webp", "The My vault screen"),
        "addlogin": shot("add-login.webp", "The Add login screen"),
        "generator": shot("generator.webp", "The Generator tab"),
        "autofill": shot("autofill.webp", "The Turn on autofill screen"),
        "backup": shot("backup.webp", "The Backup screen"),
        "settings": shot("settings.webp", "The Settings tab in the dark theme"),
        "topics": topics_html,
    }
    page("/manual/", "Manual", body,
         "How SecretDoor works: creating a vault, adding items, the generator, autofill, backups "
         "and settings, with screenshots.", section="/manual/")


def topic_pages():
    for _, topics in MANUAL_TOPICS:
        for slug_, title, desc in topics:
            src = os.path.join(DOCS, "help", slug_ + ".md")
            with open(src, encoding="utf-8") as f:
                t, content = markdown(f.read(), src)
            body = """
    <article class="doc">
      <p class="crumbs"><a href="/manual/">Manual</a> &rsaquo; %s</p>
      <h1>%s</h1>
      %s
      <p class="back"><a href="/manual/">&larr; Back to the manual</a></p>
    </article>
    """ % (html.escape(title), html.escape(t), content)
            page("/manual/%s/" % slug_, title, body, desc, section="/manual/")


def privacy():
    src = os.path.join(DOCS, "PRIVACY.md")
    with open(src, encoding="utf-8") as f:
        t, content = markdown(f.read(), src)
    body = """
    <article class="doc">
      <h1>Privacy policy</h1>
      %s
    </article>
    """ % content
    page("/privacy/", "Privacy policy", body,
         "What SecretDoor stores on your phone, what can leave it (almost nothing) and which "
         "permissions it asks for.", section="/privacy/")


def support():
    body = """
    <article class="doc">
      <h1>Support</h1>
      <p class="lead">Stuck, found a bug, or have a question? Start here.</p>

      <h2>Look it up</h2>
      <ul>
        <li><a href="/manual/">The manual</a> explains every part of the app.</li>
        <li><a href="/manual/autofill/#troubleshooting">Autofill troubleshooting</a>, the most common
          question.</li>
        <li><a href="/manual/backups/">Backups and restoring</a> if you changed phones.</li>
      </ul>

      <h2>Frequently asked questions</h2>
      <h3>I forgot my master password.</h3>
      <p>Unfortunately it cannot be recovered or reset. SecretDoor has no server and keeps no copy of
        your password, which is exactly what keeps your vault private. If you set a hint, tap
        &ldquo;Show master password hint&rdquo; on the unlock screen. If you have a backup, it needs
        the master password that was current when the backup was made.</p>
      <h3>I got a new phone. How do I move my vault?</h3>
      <p>Save a backup file on the old phone (<em>Settings &gt; Vault &gt; Backup</em>), copy it to the
        new phone, install SecretDoor and choose <strong>Restore from backup</strong> when creating the
        vault.</p>
      <h3>Does SecretDoor sync between my devices?</h3>
      <p>Not at the moment. Each vault lives on one device, and a backup file is how you move or copy
        it.</p>
      <h3>Is it really free?</h3>
      <p>Yes. Every feature works without paying, and the code is open source. See
        <a href="/manual/premium/">Premium</a> for what that word means here.</p>
      <h3>Is SecretDoor made by Bitwarden?</h3>
      <p>No. SecretDoor is an independent project based on the open source Bitwarden apps, and is not
        affiliated with or endorsed by Bitwarden Inc.</p>

      <h2>Contact us</h2>
      <ul>
        <li><strong>Questions and feedback:</strong> <a href="mailto:%(c)s">%(c)s</a></li>
        <li><strong>Bugs and feature requests:</strong>
          <a href="%(g)s/issues" rel="noopener">open an issue on GitHub</a>. Please describe what you
          did and what happened, and never include your master password or vault contents.</li>
        <li><strong>Security problems:</strong> please do not post them publicly. Use the repository&rsquo;s
          private <a href="%(g)s/security/advisories/new" rel="noopener">security advisory form</a>.</li>
      </ul>
    </article>
    """ % {"c": CONTACT, "g": GITHUB}
    page("/support/", "Support", body,
         "Help with SecretDoor: frequently asked questions and how to contact us.",
         section="/support/")


def not_found():
    body = """
    <article class="doc center">
      <h1>Page not found</h1>
      <p class="lead">That page doesn&rsquo;t exist, or it has moved.</p>
      <p><a class="btn primary" href="/">Go to the home page</a>
         <a class="btn" href="/manual/">Open the manual</a></p>
    </article>
    """
    page("/404", "Page not found", body, "Page not found")
    PAGES.remove("/404")


def extras():
    urls = "".join("  <url><loc>%s%s</loc></url>\n" % (SITE, p) for p in sorted(PAGES))
    with open(os.path.join(ROOT, "sitemap.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n%s</urlset>\n' % urls)
    with open(os.path.join(ROOT, "robots.txt"), "w") as f:
        f.write("User-agent: *\nAllow: /\n\nSitemap: %s/sitemap.xml\n" % SITE)


def main():
    home()
    manual_index()
    topic_pages()
    privacy()
    support()
    not_found()
    extras()
    print("Built %d pages" % len(PAGES))


if __name__ == "__main__":
    sys.exit(main())
