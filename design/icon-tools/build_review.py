"""Build design/icon-review/: old vs new icons + index.html for side-by-side review."""
import html
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import icons  # noqa: E402
import vd2svg  # noqa: E402

REPO = '/home/zarko/Downloads/android-main'
OUT = os.path.join(REPO, 'design/icon-review')
UI = os.path.join(REPO, 'ui/src/main/res')
APP = os.path.join(REPO, 'app/src')
OLD_BG = '#175DDC'

ILL_MAP = {  # Bitwarden illustration palette -> SecretDoor palette
    '1A41AC': '1B2C47',   # outlines: blue -> navy
    'AAC3EF': 'E3C9A0',   # mid fill: light blue -> warm tan
    'EEF6FF': 'FBF5EA',   # pale fill: pale blue -> warm off-white
    '2CDDE9': 'F2B544',   # accent: cyan -> gold
}

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
for d in ('old', 'new', 'new/android'):
    os.makedirs(os.path.join(OUT, d))


def w(rel, text):
    with open(os.path.join(OUT, rel), 'w') as f:
        f.write(text)


def old_svg(src, name):
    w(f'old/{name}.svg', vd2svg.convert(src))
    return f'old/{name}.svg'


# --- Launcher ---------------------------------------------------------------------------------
old_launchers = [
    ('Release (what users install)', old_svg(f'{APP}/release/res/drawable/ic_launcher_foreground.xml',
                                             'launcher_release_foreground'),
     old_svg(f'{APP}/release/res/drawable/ic_launcher_monochrome.xml', 'launcher_release_monochrome')),
    ('Beta', old_svg(f'{APP}/beta/res/drawable/ic_launcher_foreground.xml', 'launcher_beta_foreground'),
     old_svg(f'{APP}/beta/res/drawable/ic_launcher_monochrome.xml', 'launcher_beta_monochrome')),
    ('Debug / dev', old_svg(f'{APP}/main/res/drawable/ic_launcher_foreground.xml', 'launcher_dev_foreground'),
     old_svg(f'{APP}/main/res/drawable/ic_launcher_monochrome.xml', 'launcher_dev_monochrome')),
]
new_launchers = []
for key, (fg, mono, desc) in icons.OPTIONS.items():
    w(f'new/launcher_{key}_foreground.svg', icons.emit_svg(fg))
    w(f'new/launcher_{key}_monochrome.svg', icons.emit_svg(mono))
    w(f'new/android/option_{key}_ic_launcher_foreground.xml', icons.emit_vd(fg))
    w(f'new/android/option_{key}_ic_launcher_monochrome.xml', icons.emit_vd(mono))
    new_launchers.append((key, desc, f'new/launcher_{key}_foreground.svg',
                          f'new/launcher_{key}_monochrome.svg'))

# --- Other brand assets ------------------------------------------------------------------------
# Autofill/inline suggestion logo: full-color icon on its own rounded square (option A).
logo_shapes = [('M8,8 H100 V100 H8 Z', icons.NAVY, 1)] + icons.OPTION_A
w('new/logo_icon.svg', icons.emit_svg(logo_shapes))
w('new/android/logo_bitwarden_icon.xml', icons.emit_vd(logo_shapes))
w('new/android/logo_shield_icon.xml', icons.emit_vd(icons.OPTION_A))
w('new/splash_icon.svg', icons.emit_svg(icons.OPTION_A))
w('new/notification.svg', icons.emit_svg(icons.SMALL_A, vp=24))
w('new/android/ic_notification.xml', icons.emit_vd(icons.SMALL_A, vp=24))
black_glyph = [(s[0], '#000000', 1, *s[3:]) for s in icons.SMALL_A]
w('new/shield_glyph.svg', icons.emit_svg(black_glyph, vp=24))
w('new/android/ic_shield.xml', icons.emit_vd(black_glyph, vp=24))

brand = [
    ('Splash screen icon', 'logo_shield_icon', 'Shown on the launch screen over the icon '
     'background color.', old_svg(f'{UI}/drawable/logo_shield_icon.xml', 'logo_shield_icon'),
     'new/splash_icon.svg', 'navy'),
    ('Autofill / suggestion logo', 'logo_bitwarden_icon', 'Shown next to autofill suggestions '
     'and in the credential exchange (import/export) system sheet.',
     old_svg(f'{UI}/drawable/logo_bitwarden_icon.xml', 'logo_bitwarden_icon'), 'new/logo_icon.svg',
     'light'),
    ('Notification icon', 'ic_notification', 'Status bar icon; Android shows it as a white '
     'silhouette.', old_svg(f'{UI}/drawable/ic_notification.xml', 'ic_notification'),
     'new/notification.svg', 'dark'),
    ('Shield glyph', 'ic_shield', 'Used on the Import logins screen (tinted by the theme).',
     old_svg(f'{UI}/drawable/ic_shield.xml', 'ic_shield'), 'new/shield_glyph.svg', 'light'),
]

# --- Illustrations: palette swap ---------------------------------------------------------------
ills = []
for f in sorted(os.listdir(f'{UI}/drawable')):
    if not f.startswith('ill_'):
        continue
    n = f[:-4]
    src = f'{UI}/drawable/{f}'
    old = old_svg(src, n)
    xml = open(src).read()
    for a, b in ILL_MAP.items():
        xml = re.sub(rf'#(FF)?{a}\b', f'#\\g<1>{b}', xml, flags=re.I)
    w(f'new/android/{f}', xml)
    w(f'new/{n}.svg', vd2svg.convert(os.path.join(OUT, f'new/android/{f}')))
    ills.append((n, old, f'new/{n}.svg'))

# --- UI icons (unchanged, for flagging) --------------------------------------------------------
glyphs = []
for f in sorted(os.listdir(f'{UI}/drawable')):
    if f.startswith('ic_') and f.endswith('.xml') and f not in ('ic_shield.xml', 'ic_notification.xml'):
        glyphs.append((f[:-4], old_svg(f'{UI}/drawable/{f}', f[:-4])))
# Unused logos (not referenced anywhere in the app)
unused = [(n, old_svg(f'{UI}/drawable/{n}.xml', n)) for n in ('logo_bitwarden', 'logo_authenticator')]


# --- HTML --------------------------------------------------------------------------------------
def adaptive(fg, bg, shape, size):
    """Adaptive icon preview: 108dp layer, center 72dp visible, clipped to launcher shape."""
    inner = size * 108 / 72
    off = -(inner - size) / 2
    return (f'<div class="ai {shape}" style="width:{size}px;height:{size}px;background:{bg}">'
            f'<img src="{fg}" style="width:{inner}px;height:{inner}px;left:{off}px;top:{off}px" '
            f'alt=""></div>')


def mono(m, size):
    return adaptive(m, '#3C4043', 'circle', size).replace('<img ', '<img class="mono" ')


def fb(key, label):
    k = html.escape(key)
    return (f'<div class="fb" data-key="{k}"><label><input type="checkbox" data-role="flag"> '
            f'{html.escape(label)}</label><textarea data-role="note" rows="1" '
            f'placeholder="Notes (optional)"></textarea></div>')


h = []
h.append('''<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>SecretDoor Icon Review</title>
<style>
:root{--bg:#F6F4EF;--card:#FFFFFF;--ink:#1B2C47;--muted:#5F6B7A;--line:#E2DDD2;--accent:#B7791F;
--light:#FFFFFF;--dark:#202124}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#11161F;--card:#1A2230;
--ink:#E8EDF5;--muted:#9AA6B6;--line:#2A3445;--accent:#F2B544}}
:root[data-theme="dark"]{--bg:#11161F;--card:#1A2230;--ink:#E8EDF5;--muted:#9AA6B6;--line:#2A3445;
--accent:#F2B544}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);
font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:1100px;margin:0 auto;padding:24px 16px 80px}
h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:36px 0 8px}
p.lead,p.note{color:var(--muted);margin:4px 0 12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin:12px 0}
.row{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start}
.col{display:flex;flex-direction:column;gap:8px;align-items:center}
.cap{font-size:12px;color:var(--muted);text-align:center}
.ai{position:relative;overflow:hidden;flex:none}.ai img{position:absolute}
.ai.circle{border-radius:50%}.ai.squircle{border-radius:22%}.ai.square{border-radius:6px}
img.mono{filter:brightness(0) invert(0.85)}
.pair{display:grid;grid-template-columns:1fr auto 1fr;gap:12px;align-items:center}
.arrow{color:var(--muted);font-size:20px}
.tile{display:flex;align-items:center;justify-content:center;border-radius:10px;
border:1px solid var(--line);width:120px;height:120px}
.tile.light{background:#FFFFFF}.tile.dark{background:#202124}.tile.navy{background:#14233B}
.tile img{max-width:88px;max-height:88px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px}
.ill{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:10px}
.ill .imgs{display:flex;gap:6px;justify-content:center}
.ill .imgs img{width:64px;height:64px;background:#fff;border-radius:8px;padding:4px}
.gl{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:8px}
.g{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px;
display:flex;flex-direction:column;align-items:center;gap:4px}
.g img{width:32px;height:32px;background:#fff;border-radius:6px;padding:4px}
.g .cap{word-break:break-all}
.fb{display:flex;flex-direction:column;gap:4px;width:100%;margin-top:6px;font-size:13px}
.fb textarea{width:100%;font:inherit;font-size:12px;border:1px solid var(--line);border-radius:6px;
background:var(--bg);color:var(--ink);padding:4px 6px;resize:vertical}
.choice{display:flex;gap:16px;flex-wrap:wrap;margin:8px 0}
.choice label{border:1px solid var(--line);border-radius:8px;padding:6px 10px;cursor:pointer}
.bar{position:sticky;bottom:0;background:var(--card);border-top:1px solid var(--line);
padding:10px 16px;display:flex;gap:12px;align-items:center;justify-content:center;flex-wrap:wrap}
button{font:inherit;background:var(--accent);color:#1B1B1B;border:0;border-radius:8px;
padding:8px 14px;cursor:pointer;font-weight:600}
#out{width:min(700px,100%);height:0;opacity:0}
@media (max-width:600px){.pair{grid-template-columns:1fr}.arrow{transform:rotate(90deg)}}
</style></head><body><main>
<h1>SecretDoor icon review</h1>
<p class="lead">Current Bitwarden icons on the left (or first), proposed SecretDoor icons on the
right. Nothing in the app has changed yet. Tick anything you want changed, add a note, then press
<b>Copy feedback</b> at the bottom and paste it to Claude.</p>
''')

h.append('<h2>1. App icon (launcher)</h2><p class="note">Android crops the icon to the shape your '
         'launcher uses (circle, rounded square). Themed icons use the monochrome version.</p>')
h.append('<div class="card"><b>Current</b><div class="row" style="margin-top:8px">')
for label, fg, mn in old_launchers:
    h.append(f'<div class="col">{adaptive(fg, OLD_BG, "squircle", 96)}'
             f'<div class="cap">{html.escape(label)}</div></div>')
h.append(f'<div class="col">{mono(old_launchers[0][2], 96)}<div class="cap">Themed '
         '(monochrome)</div></div></div></div>')
for key, desc, fg, mn in new_launchers:
    h.append(f'<div class="card"><b>Option {key}</b> — {html.escape(desc)}'
             '<div class="row" style="margin-top:8px">')
    for shape, size in (('squircle', 112), ('circle', 112), ('square', 112), ('circle', 48),
                        ('circle', 32)):
        h.append(f'<div class="col">{adaptive(fg, icons.NAVY, shape, size)}'
                 f'<div class="cap">{shape} {size}px</div></div>')
    h.append(f'<div class="col">{mono(mn, 112)}<div class="cap">Themed (monochrome)</div></div>')
    h.append('</div>' + fb(f'launcher-option-{key}', f'Change something in option {key}') + '</div>')
h.append('<div class="card"><b>Which option should SecretDoor use?</b><div class="choice">'
         + ''.join(f'<label><input type="radio" name="pick" value="{k}"> Option {k}</label>'
                   for k in icons.OPTIONS)
         + '<label><input type="radio" name="pick" value="none"> None of these</label></div>'
         '<p class="note">Beta and debug builds currently add a BETA / DEV badge. Proposal: same '
         'icon, different background color (beta purple, debug red) so you can tell them apart.</p>'
         + fb('launcher-variants', 'Change the beta/debug proposal') + '</div>')

h.append('<h2>2. Other Bitwarden-branded icons</h2><p class="note">These follow the chosen app icon; '
         'shown here with option A.</p>')
for title, res, note, old, new, bg in brand:
    h.append(f'<div class="card"><b>{html.escape(title)}</b> <span class="cap">({res})</span>'
             f'<p class="note">{html.escape(note)}</p><div class="pair">'
             f'<div class="col"><div class="tile {bg}"><img src="{old}" alt="old {res}"></div>'
             '<div class="cap">Current</div></div><div class="arrow">→</div>'
             f'<div class="col"><div class="tile {bg}"><img src="{new}" alt="new {res}"></div>'
             '<div class="cap">Proposed</div></div></div>'
             + fb(f'brand-{res}', 'Needs changes') + '</div>')
h.append('<div class="card"><b>Unused Bitwarden logos</b><p class="note">Not referenced anywhere in '
         'the app. Proposal: delete them.</p><div class="row">'
         + ''.join(f'<div class="col"><div class="tile light" style="width:220px"><img src="{p}" '
                   f'alt="{n}"></div><div class="cap">{n}</div></div>' for n, p in unused)
         + '</div>' + fb('brand-unused-logos', 'Keep them instead') + '</div>')

h.append('<h2>3. Illustrations: color refresh</h2><p class="note">Same drawings, recolored from '
         'Bitwarden blue/cyan to SecretDoor navy, tan and gold. Shown on onboarding, empty states '
         'and settings screens.</p><div class="grid">')
for n, old, new in ills:
    h.append(f'<div class="ill"><div class="imgs"><img src="{old}" alt="old {n}">'
             f'<img src="{new}" alt="new {n}"></div><div class="cap">{n}</div>'
             + fb(f'ill-{n}', 'Needs changes') + '</div>')
h.append('</div>')

h.append(f'<h2>4. Small UI icons ({len(glyphs)}, unchanged)</h2><p class="note">Generic glyphs '
         '(arrows, copy, trash…), tinted by the app theme at runtime, so they already match any '
         'color scheme. Tick any you want redrawn.</p><div class="gl">')
for n, p in glyphs:
    h.append(f'<div class="g"><img src="{p}" alt="{n}"><div class="cap">{n}</div>'
             f'<div class="fb" data-key="glyph-{n}"><label><input type="checkbox" data-role="flag">'
             ' redraw</label></div></div>')
h.append('</div>')

h.append('''</main><div class="bar"><button id="copy">Copy feedback</button>
<span class="cap" id="status">Your ticks and notes are kept in this browser.</span>
<textarea id="out" aria-hidden="true"></textarea></div>
<script>
const KEY='secretdoor-icon-review';
let saved={};try{saved=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
function save(){try{localStorage.setItem(KEY,JSON.stringify(saved))}catch(e){}}
document.querySelectorAll('.fb').forEach(el=>{const k=el.dataset.key,s=saved[k]||{};
 const c=el.querySelector('[data-role=flag]'),t=el.querySelector('[data-role=note]');
 if(c){c.checked=!!s.flag;c.onchange=()=>{(saved[k]=saved[k]||{}).flag=c.checked;save()}}
 if(t){t.value=s.note||'';t.oninput=()=>{(saved[k]=saved[k]||{}).note=t.value;save()}}});
document.querySelectorAll('input[name=pick]').forEach(r=>{if(saved.pick===r.value)r.checked=true;
 r.onchange=()=>{saved.pick=r.value;save()}});
document.getElementById('copy').onclick=async()=>{
 const lines=['SecretDoor icon review feedback','App icon choice: '+(saved.pick||'(not chosen)')];
 Object.keys(saved).filter(k=>k!=='pick').sort().forEach(k=>{const s=saved[k];
  if(s.flag||(s.note&&s.note.trim()))lines.push('- '+k+(s.flag?' [flagged]':'')+(s.note?': '+s.note.trim():''))});
 const txt=lines.join('\\n'),o=document.getElementById('out');o.value=txt;
 try{await navigator.clipboard.writeText(txt)}catch(e){o.select();document.execCommand('copy')}
 document.getElementById('status').textContent='Copied '+(lines.length-2)+' item(s). Paste it to Claude.';};
</script></body></html>''')
w('index.html', '\n'.join(h))
print('built', OUT, len(ills), 'illustrations', len(glyphs), 'glyphs')
