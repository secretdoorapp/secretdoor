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


def side(shots, text, duo=False):
    """A screenshot (or two) beside explanatory text."""
    if isinstance(shots, str):
        shots = [shots]
    inner = "".join(shots)
    if len(shots) > 1:
        inner = '<div class="phones">%s</div>' % inner
    return '<div class="side%s">%s<div>%s</div></div>' % (" duo" if len(shots) > 1 else "", inner, text)


def manual_index():
    sections = []
    for heading, topics in MANUAL_TOPICS:
        items = "".join('<li><a href="/manual/%s/">%s<span>%s</span></a></li>' % (s, t, d)
                        for s, t, d in topics)
        sections.append("<h3>%s</h3><ul class=\"topics\">%s</ul>" % (heading, items))
    topics_html = "\n".join(sections)

    S = lambda n, alt, cap=None: shot(n + ".webp", alt, cap)
    parts = []
    add = parts.append

    add("""
    <article class="doc">
      <h1>SecretDoor manual</h1>
      <p class="lead">Everything you need to know to use SecretDoor, from creating your vault to
        backing it up. The screenshots are from the Android app, which is the version available now.
        An iPhone version may be coming soon.</p>

      <nav class="toc" aria-label="On this page">
        <strong>On this page</strong>
        <ol>
          <li><a href="#how-it-works">How it works</a></li>
          <li><a href="#create-vault">Create your vault</a></li>
          <li><a href="#first-setup">First-time setup</a></li>
          <li><a href="#your-vault">Your vault</a></li>
          <li><a href="#item-types">Item types</a></li>
          <li><a href="#logins">Add and edit a login</a></li>
          <li><a href="#cards-identities">Cards, identities and notes</a></li>
          <li><a href="#folders">Folders</a></li>
          <li><a href="#generator">The password generator</a></li>
          <li><a href="#autofill">Autofill</a></li>
          <li><a href="#locking">Locking and unlocking</a></li>
          <li><a href="#backup">Backups</a></li>
          <li><a href="#export">Export and import</a></li>
          <li><a href="#vaults">Several vaults</a></li>
          <li><a href="#settings">Settings</a></li>
          <li><a href="#topics">More topics</a></li>
        </ol>
      </nav>

      <h2 id="how-it-works">How it works</h2>
      <p>SecretDoor is a password manager with <strong>no server</strong>. Your vault is a set of
        encrypted records stored in the app on your phone. You unlock it with a master password that
        only you know; the password is never stored and never sent anywhere.</p>
      <ul>
        <li>There is <strong>no account</strong> and no sign-in. Install the app, create a vault and
          start saving.</li>
        <li>Items are encrypted before they are saved. Names, usernames, notes and everything else
          are unreadable without your master password.</li>
        <li>Because nothing is stored online, <strong>a lost phone means a lost vault</strong>
          unless you made a backup. SecretDoor cannot recover it for you.</li>
      </ul>
      <p>That is also why SecretDoor is private: there is no company holding a copy of your data. The
        trade-off is that you are responsible for your master password and your backups.</p>
""")

    add("""
      <h2 id="create-vault">Create your vault</h2>""")
    add(side(S("create-vault", "The Create vault screen with a strong master password entered"), """
        <p>The first time you open the app you create a vault by choosing a
          <strong>master password</strong>. It is the only thing protecting your data, so make it
          long. A few random words work better than a short, complicated password.</p>
        <ul>
          <li>The meter under the field tells you how strong the password is. Aim for
            <strong>Strong</strong>.</li>
          <li>Type it twice to avoid typos. Tap the eye icon to check what you typed.</li>
          <li>The <strong>hint</strong> is optional. It is stored only on your phone and shown on the
            unlock screen when you tap &ldquo;Show master password hint&rdquo;. Never put the password
            itself in it.</li>
          <li><strong>Check known data breaches</strong> is off by default. If you turn it on, the
            app sends the first five characters of a hash of your password to a breach database,
            never the password itself. See <a href="/privacy/">Privacy</a>.</li>
        </ul>
        <p>Tap <strong>Create vault</strong>. If you already have a SecretDoor backup, choose
          <strong>Restore from backup</strong> instead and enter that vault&rsquo;s master
          password.</p>"""))
    add("""
      <div class="note warn"><strong>Don&rsquo;t forget it.</strong> There is no &ldquo;forgot
        password&rdquo; button. Nobody, including the developers, can reset or recover a master
        password. Write it down and keep it somewhere safe.</div>
""")

    add("""
      <h2 id="first-setup">First-time setup</h2>
      <p>After creating the vault, a short setup offers three optional steps. You can skip any of
        them and come back later in Settings.</p>""")
    add(side([S("setup-unlock", "Set up unlock screen", "Quick unlock"),
              S("setup-autofill", "Turn on autofill screen", "Autofill"),
              S("setup-done", "You're all set screen", "Done")], """
        <ul>
          <li><strong>Set up unlock.</strong> Choose <strong>biometrics</strong> (fingerprint or face,
            when your phone supports it) or a <strong>PIN code</strong> so you don&rsquo;t have to
            type the long master password every time. These are convenient but weaker than the master
            password: someone who knows your PIN and has your phone could unlock the vault.</li>
          <li><strong>Turn on autofill.</strong> Lets SecretDoor fill in logins in other apps and in
            your browser. See <a href="#autofill">Autofill</a>.</li>
          <li><strong>You&rsquo;re all set.</strong> Tap Continue and your empty vault opens.</li>
        </ul>"""))

    add("""
      <h2 id="your-vault">Your vault</h2>""")
    add(side([S("vault-empty", "The empty My vault screen", "A new vault"),
              S("vault-top", "My vault with item types and counts", "With items")], """
        <p>The <strong>My vault</strong> tab is home. At the top the <strong>Types</strong> list shows
          each kind of item with a count, and tapping a type lists just those items. Below are your
          <strong>folders</strong>, the items that are not in a folder, and two special places:
          <strong>Archive</strong> and <strong>Trash</strong>.</p>
        <ul>
          <li>Tap the <strong>+</strong> button to add an item.</li>
          <li>Tap an item to open it. The <strong>&hellip;</strong> button beside it offers quick
            actions such as copying the username or password.</li>
          <li>Deleted items go to the <strong>Trash</strong> first, so you can bring them back.
            <strong>Archive</strong> keeps items you don&rsquo;t need right now out of the way.</li>
        </ul>"""))
    add(side([S("vault-folders", "My vault showing folders, items without a folder and hidden items"),
              S("search", "Searching the vault for \"net\"")], """
        <p>Use the <strong>search</strong> icon at the top to find items by name, username or website
          as you type. Everything is searched on your phone; nothing is sent anywhere.</p>
        <p>Folders (here Finance, Personal and Work) group related items, and the number beside each
          one tells you how many it holds.</p>"""))

    add("""
      <h2 id="item-types">Item types</h2>""")
    add(side(S("add-menu", "The Type menu listing the item types"), """
        <p>Tap <strong>+</strong> and choose what you want to save:</p>
        <ul>
          <li><strong>Login</strong>: a username and password for a website or app, with optional
            authenticator codes and website addresses.</li>
          <li><strong>Card</strong>: credit or debit card details.</li>
          <li><strong>Bank account</strong>: account, routing, IBAN and similar numbers.</li>
          <li><strong>Identity</strong>: your name, address, contact details and Social Security
            number.</li>
          <li><strong>Driver&rsquo;s license</strong> and <strong>Passport</strong>: their own item
            types, with fields for numbers, issuing country and dates.</li>
          <li><strong>Secure note</strong>: any private text, such as a Wi-Fi password or a safe
            combination.</li>
          <li><strong>SSH key</strong>: a private and public key pair.
            <a href="/manual/ssh-keys/">Learn more</a>.</li>
          <li><strong>Folder</strong>: to organize your items.</li>
        </ul>
        <p>Every item can also hold custom fields, file attachments and a note, and can be marked as a
          favorite with the star.</p>"""))

    add("""
      <h2 id="logins">Add and edit a login</h2>""")
    add(side([S("add-login", "The Add login screen", "Add login"),
              S("view-login", "A saved login in view mode", "View login")], """
        <p>A login has a name, an optional folder, a <strong>username</strong>, a
          <strong>password</strong> and one or more <strong>websites</strong>. Next to the username
          and password are buttons to <strong>generate</strong> a new value and to
          <strong>show or hide</strong> it. When you are happy, tap <strong>Save</strong>.</p>
        <ul>
          <li><strong>Authenticator key</strong>: paste or scan the key from a website&rsquo;s
            two-step setup and SecretDoor shows the live six-digit codes.
            <a href="/manual/authenticator-codes/">Learn more</a>.</li>
          <li><strong>Website (URI)</strong>: tells autofill when to offer this login. The gear icon
            sets how strictly addresses must match.
            <a href="/manual/uri-match-detection/">Learn more</a>.</li>
          <li><strong>Check password for data breaches</strong>: an optional lookup that sends only a
            short partial hash, never the password.</li>
        </ul>
        <p>Open a saved login to see it. Tap a copy icon to copy the username or password, the eye to
          reveal the password, the arrow beside the website to open it, and the pencil to edit. Copied
          values are cleared from the clipboard after the time you choose in <em>Settings &gt;
          Other</em>.</p>"""))
    add("""
      <p>To protect a sensitive item further, turn on <strong>master password re-prompt</strong> in its
        additional options: the app then asks for your master password again before showing or copying
        it. <a href="/manual/master-password-reprompt/">Learn more</a>.</p>
""")

    add("""
      <h2 id="cards-identities">Cards, identities and notes</h2>""")
    add(side([S("add-card", "The Add card screen", "Add card"),
              S("view-card", "A saved card in view mode", "View card"),
              S("add-identity", "The Add identity screen", "Add identity")], """
        <p>These work the same way as logins: fill in the fields you need, then save. The number and
          security code of a card are hidden until you tap the eye.</p>
        <p>An <strong>identity</strong> holds personal details. Its <strong>Identification</strong>
          section has the Social Security number. Passport and driver&rsquo;s license numbers live in
          their own item types, so they only appear on an identity that already contained them.</p>
        <p>A <strong>secure note</strong> is a title and free text. Use it for anything you want
          encrypted that doesn&rsquo;t fit another type.</p>"""))

    add("""
      <h2 id="folders">Folders</h2>""")
    add(side(S("folder-picker", "Choosing a folder for an item"), """
        <p>Create folders from <em>Settings &gt; Vault &gt; Folders</em> or with <strong>+ &gt;
          Folder</strong>. To put an item in a folder, edit it, tap the <strong>Folder</strong> box,
          choose the folder and tap <strong>Save</strong> in the sheet, then <strong>Save</strong>
          again on the item. You can also create a new folder from that sheet with <strong>Add
          folder</strong>.</p>
        <p>Deleting a folder does not delete the items in it; they move back to &ldquo;No
          folder&rdquo;.</p>"""))

    add("""
      <h2 id="generator">The password generator</h2>""")
    add(side([S("generator", "The password generator", "Password"),
              S("generator-passphrase", "The passphrase generator", "Passphrase"),
              S("generator-username", "The username generator", "Username")], """
        <p>The <strong>Generator</strong> tab creates strong random values. Pick a type with the tabs
          at the top:</p>
        <ul>
          <li><strong>Password</strong>: set the length and which character types to include.</li>
          <li><strong>Passphrase</strong>: random words, easier to type and remember. Choose the number
            of words, a separator and whether to capitalize or add a number.</li>
          <li><strong>Username</strong>: a random username or a plus-addressed email.</li>
        </ul>
        <p>Tap the circular arrows for a new value and the copy icon to copy it. <strong>Create
          login</strong> saves the generated value into a new login, and the menu at the top keeps a
          history of recent values. <a href="/manual/generator/">More about the generator</a>.</p>"""))

    add("""
      <h2 id="autofill">Autofill</h2>""")
    add(side(S("settings-autofill", "The Autofill settings screen"), """
        <p>With autofill on, SecretDoor offers your saved login when you tap a username or password
          field in an app or a browser, so you don&rsquo;t have to switch apps and copy and paste.</p>
        <ul>
          <li><strong>Autofill services</strong>: turn it on, and Android asks you to choose SecretDoor
            as your autofill service.</li>
          <li><strong>Copy TOTP automatically</strong>: when a login has an authenticator key, copy
            its current code to the clipboard after autofilling.</li>
          <li><strong>Passkey management</strong> and <strong>Privileged apps</strong>: for passkeys
            and trusted browsers.</li>
        </ul>
        <p>Browsers need an extra step. See <a href="/manual/autofill/">Autofill</a> for setup and
          troubleshooting.</p>"""))

    add("""
      <h2 id="locking">Locking and unlocking</h2>""")
    add(side([S("account-security", "The Account security settings", "Account security"),
              S("unlock-hint", "The unlock screen showing the master password hint", "Unlock")], """
        <p>Open <em>Settings &gt; Account security</em> to choose how the vault locks and unlocks.</p>
        <ul>
          <li><strong>Unlock with PIN code</strong> (and biometrics, if your phone supports them).</li>
          <li><strong>Session timeout</strong>: how long the app waits before locking. New vaults
            start with <strong>10 minutes</strong>. Pick a shorter time for more safety, or
            <strong>Never</strong> to keep the vault unlocked until you lock it.
            <strong>Session timeout action</strong> is what happens when it expires: Lock.</li>
          <li><strong>Lock now</strong> locks the vault immediately.</li>
          <li><strong>Change master password</strong> and <strong>Delete vault</strong>.</li>
        </ul>
        <p>On the unlock screen, type your master password, or tap <strong>Show master password
          hint</strong> if you need a reminder. More in <a href="/manual/security/">Security</a>.</p>"""))
    add("""
      <div class="note"><strong>Be careful with Delete vault.</strong> It erases the vault from the
        phone for good, after you confirm with the master password. Backups you made earlier are
        not affected.</div>
""")

    add("""
      <h2 id="backup">Backups</h2>""")
    add(side(S("backup", "The Backup screen"), """
        <p>Open <em>Settings &gt; Vault &gt; Backup</em>. A backup is one <code>.secretdoor</code> file
          that holds your whole vault, encrypted as a whole. You need your master password to restore
          it.</p>
        <ul>
          <li><strong>Save backup file</strong> writes a backup wherever you choose: a Google Drive
            folder, a USB drive, another phone.</li>
          <li><strong>Automatic backup</strong>: pick a file once under <em>Backup file</em> and a
            <em>Frequency</em> (off, daily or weekly). SecretDoor then rewrites that file on
            schedule, even while the vault is locked. <strong>Back up now</strong> runs it
            immediately. A cloud folder that keeps old versions (Google Drive does) gives you several
            restore points.</li>
          <li><strong>Import backup</strong> adds the items from a backup file to this vault. On a new
            phone, use <strong>Restore from backup</strong> when you create your vault.</li>
        </ul>
        <p>Keep at least one backup that is not on the phone, and try a restore once to make sure it
          works. Details in <a href="/manual/backups/">Backups</a>.</p>"""))

    add("""
      <h2 id="export">Export and import</h2>""")
    add(side(S("export", "The Export vault screen"), """
        <p><strong>Export vault</strong> (<em>Settings &gt; Vault</em>) writes your items to a file
          that other password managers can read. You choose the file format and confirm with your
          master password.</p>
        <div class="note warn"><strong>Exports are different from backups.</strong> A plain export is
          <em>not</em> encrypted: anyone who gets the file can read all your passwords. Delete it when
          you are done, and never send it over email or chat.</div>
        <p><strong>Import items</strong> restores a SecretDoor backup, or receives items from another
          password manager on the same phone through Android&rsquo;s Credential Exchange. Importing CSV
          or JSON files from other managers is not supported yet.
          <a href="/manual/importing-data/">Learn more</a>.</p>"""))

    add("""
      <h2 id="vaults">Several vaults</h2>""")
    add(side(S("manage-vaults", "The Manage vaults screen"), """
        <p>One phone can hold several vaults, for example a personal one and a work one. Each has its
          own master password and is encrypted separately. Open <em>Settings &gt; Vault &gt; Manage
          vaults</em> to rename or delete a vault, create a new one, or import a backup as a separate
          vault. When you have more than one, a vault picker appears on the My vault and unlock
          screens. <a href="/manual/managing-vaults/">Learn more</a>.</p>"""))

    add("""
      <h2 id="settings">Settings</h2>""")
    add(side([S("settings", "The Settings screen", "Settings"),
              S("settings-vault", "The Vault settings", "Vault")], """
        <ul>
          <li><strong>Account security</strong>: unlocking, timeout, master password.</li>
          <li><strong>Autofill</strong>: autofill and passkeys.</li>
          <li><strong>Vault</strong>: manage vaults, folders, backup, export and import.</li>
          <li><strong>Appearance</strong>: language, theme and dynamic colors.</li>
          <li><strong>Other</strong>: clear the clipboard after a delay, and allow or block screen
            capture.</li>
          <li><strong>About</strong>: the version, this manual, the privacy policy, the source code, the
            license and open source notices, and the flight recorder for bug reports
            (<a href="/manual/flight-recorder/">learn more</a>).</li>
        </ul>"""))
    add(side([S("appearance-dark", "Appearance settings with the dark theme", "Theme"),
              S("vault-dark", "My vault in the dark theme", "Dark theme")], """
        <p>Under <strong>Appearance</strong> you can pick the <strong>Dark</strong> or
          <strong>Light</strong> theme, or follow your phone&rsquo;s setting (the default). Here is
          the vault in the dark theme.</p>"""))

    add("""
      <h2 id="topics">More topics</h2>
      %s
      <p class="back">Questions? Visit <a href="/support/">Support</a>.</p>
    </article>
    """ % topics_html)

    page("/manual/", "Manual", "".join(parts),
         "How SecretDoor works: creating a vault, adding items, folders, the generator, autofill, "
         "locking, backups and settings, with screenshots.", section="/manual/")


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
