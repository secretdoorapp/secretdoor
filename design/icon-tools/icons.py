"""SecretDoor icon designs. Each design is a list of shapes in a 108x108 (adaptive icon) or
24x24 viewport. Shapes: (pathData, fill, opacity) or (pathData, fill, opacity, 'evenOdd').
fill may be a hex color or ('lin', x1, y1, x2, y2, [(offset, hex), ...]).
emit_svg / emit_vd turn the same shapes into SVG (preview) and VectorDrawable XML (app).
"""

NAVY = '#14233B'
NAVY_LIGHT = '#1F3556'
WOOD = '#7A4A2E'
WOOD_DARK = '#5B3520'
WOOD_EDGE = '#94603D'
LIGHT_HI = '#FFE29A'
LIGHT_LO = '#FFB443'
GOLD = '#F2B544'
INK = '#14233B'



def keyhole(cx, cy, r, top_hw, bot_hw, bottom):
    """Keyhole as one closed outline (round head + tapered stem), safe for evenOdd cut-outs."""
    yj = cy + (r * r - top_hw * top_hw) ** 0.5
    return (f'M{cx - top_hw:.2f},{yj:.2f} L{cx - bot_hw:.2f},{bottom:.2f} H{cx + bot_hw:.2f} '
            f'L{cx + top_hw:.2f},{yj:.2f} A{r},{r} 0 1,0 {cx - top_hw:.2f},{yj:.2f} Z')


def light(x1, y1, x2, y2):
    return ('lin', x1, y1, x2, y2, [(0, LIGHT_HI), (1, LIGHT_LO)])


# Shared door geometry (108 viewport), centred on x=54. Doorway opening and a door panel
# swung toward the viewer (hinged on the doorway's left edge).
DOORWAY = 'M46,26 H76 V82 H46 Z'
DOOR_PANEL = 'M46,26 L32,20 L32,88 L46,82 Z'
KEYHOLE = keyhole(61, 46, 5, 2, 4, 62)
SPILL = ('lin', 61, 82, 66, 98, [(0, LIGHT_HI, 0.55), (1, LIGHT_HI, 0)])
FLOOR_SPILL = 'M46,82 H76 L88,98 H54 Z'

# --- Option A: door ajar, light pouring out, keyhole in the light ---------------------------
OPTION_A = [
    (FLOOR_SPILL, SPILL, 1),
    (DOORWAY, light(61, 26, 61, 82), 1),
    (KEYHOLE, INK, 0.85),
    ('M46,26 H76 V82 H46 Z M49,29 V79 H73 V29 Z', NAVY_LIGHT, 1, 'evenOdd'),     # frame
    (DOOR_PANEL, WOOD, 1),
    ('M46,26 L32,20 L32,24 L46,29.5 Z', WOOD_EDGE, 1),                         # top bevel
    ('M35,30 L43,32.5 V50 L35,48.5 Z M35,56 L43,57 V76 L35,79 Z', WOOD_DARK, 1),  # panels
    ('M37.5,55 m-1.6,0 a1.6,1.6 0 1,0 3.2,0 a1.6,1.6 0 1,0 -3.2,0', GOLD, 1),   # knob
]

# --- Option B: bookcase that swings open (classic secret passage) ---------------------------
_BOOKS = []
_cols = ['#C8553D', '#F2B544', '#3E7CB1', '#81A684', '#E6E1C5', '#9B5DE5']
for row, y in enumerate((30, 46, 62)):
    x = 32.0
    for i, w in enumerate((3.2, 2.4, 3.6, 2.8, 3.0)):
        h = 12 - (i + row) % 3
        _BOOKS.append((f'M{x:.1f},{y + 13 - h} h{w} v{h} h-{w} Z', _cols[(i + row * 2) % 6], 1))
        x += w + 0.6
OPTION_B = [
    ('M53,82 H78 L90,98 H62 Z', SPILL, 1),
    ('M53,26 H78 V82 H53 Z', light(65, 26, 65, 82), 1),             # passage behind the shelf
    ('M30,26 H53 V82 H30 Z', WOOD, 1),                               # fixed left half
    ('M32,28 H51 V43 H32 Z M32,44 H51 V59 H32 Z M32,60 H51 V75 H32 Z', WOOD_DARK, 1),
    *_BOOKS,
    ('M53,26 L66,21 V87 L53,82 Z', WOOD, 1),                         # swung right half, edge-on
    ('M53,26 L66,21 V24 L53,28.5 Z', WOOD_EDGE, 1),
    ('M55,31 L64,28 V41 L55,43 Z M55,47 L64,45.5 V58 L55,59 Z M55,63 L64,62.5 V76 L55,76.5 Z',
     WOOD_DARK, 1),
]

# --- Option C: minimal — a closed door, a glowing seam and a gold keyhole -------------------
OPTION_C = [
    ('M37,22 H71 V86 H37 Z', '#2E5288', 1),                          # door slab
    ('M41,27 H67 V51 H41 Z M41,56 H67 V81 H41 Z', '#244371', 1),    # recessed panels
    ('M71,22 L75,23.5 V84.5 L71,86 Z', light(73, 22, 73, 86), 1),   # light leaking at the seam
    (keyhole(54, 58, 5.5, 2.4, 4.5, 74), GOLD, 1),
]

# Monochrome (themed icon) versions: single color, launcher tints them.
MONO_A = [(DOOR_PANEL, '#FFFFFF', 1),
          ('M46,26 H76 V82 H46 Z ' + KEYHOLE, '#FFFFFF', 1, 'evenOdd')]
MONO_B = [('M30,26 H53 V82 H30 Z M32,43 H51 V44 H32 Z M32,59 H51 V60 H32 Z', '#FFFFFF', 1,
           'evenOdd'),
          ('M55,26 L66,21 V87 L55,82 Z', '#FFFFFF', 1),
          ('M68,26 H78 V82 H68 Z', '#FFFFFF', 0.5)]
MONO_C = [('M37,22 H71 V86 H37 Z ' + keyhole(54, 58, 5.5, 2.4, 4.5, 74), '#FFFFFF', 1, 'evenOdd'),
          ('M72.5,23 L75,24 V84 L72.5,85 Z', '#FFFFFF', 1)]

OPTIONS = {'A': (OPTION_A, MONO_A, 'Door ajar with light and keyhole'),
           'B': (OPTION_B, MONO_B, 'Bookcase swinging open'),
           'C': (OPTION_C, MONO_C, 'Closed door, glowing seam, gold keyhole')}

# 24dp glyph versions (notification / small logo) derived from option A, scaled 108 -> 24.
SMALL_A = [('M10.2,3 H20.5 V21 H10.2 Z ' + keyhole(15.35, 9.6, 2.3, 1.0, 1.95, 16.6),
            '#FFFFFF', 1, 'evenOdd'),
           ('M9.2,3 L3.5,1.4 V22.6 L9.2,21 Z', '#FFFFFF', 1)]


def _fill_svg(fill, defs, n):
    if isinstance(fill, tuple):
        _, x1, y1, x2, y2, stops = fill
        gid = f'lg{n}'
        st = ''.join(f'<stop offset="{t[0]}" stop-color="{t[1]}" stop-opacity="{t[2] if len(t) > 2 else 1}"/>'
                     for t in stops)
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" '
                    f'y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>')
        return f'url(#{gid})'
    return fill


def emit_svg(shapes, vp=108, bg=None, mask=None):
    defs, body = [], []
    for n, s in enumerate(shapes):
        d, fill, op = s[:3]
        rule = ' fill-rule="evenodd"' if len(s) > 3 else ''
        body.append(f'<path d="{d}" fill="{_fill_svg(fill, defs, n)}" fill-opacity="{op}"{rule}/>')
    bgs = f'<rect width="{vp}" height="{vp}" fill="{bg}"/>' if bg else ''
    inner = bgs + ''.join(body)
    if mask == 'circle':
        defs.append(f'<clipPath id="m"><circle cx="{vp/2}" cy="{vp/2}" r="{vp*0.5*66/72}"/></clipPath>')
        inner = f'<g clip-path="url(#m)">{inner}</g>'
    elif mask == 'squircle':
        r = vp * 0.22
        defs.append(f'<clipPath id="m"><rect x="{vp*0.0833}" y="{vp*0.0833}" '
                    f'width="{vp*0.8333}" height="{vp*0.8333}" rx="{r}"/></clipPath>')
        inner = f'<g clip-path="url(#m)">{inner}</g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vp} {vp}" width="{vp}" '
            f'height="{vp}"><defs>{"".join(defs)}</defs>{inner}</svg>')


def _hex8(c, op):
    a = round(op * 255)
    return f'#{a:02X}{c.lstrip("#").upper()}' if op < 1 else c.upper()


def emit_vd(shapes, vp=108, size=None, tint=None):
    size = size or vp
    need_aapt = any(isinstance(s[1], tuple) for s in shapes)
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if need_aapt else ''
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}',
           f'    android:width="{size}dp"', f'    android:height="{size}dp"',
           f'    android:viewportWidth="{vp}"', f'    android:viewportHeight="{vp}"'
           + (f'\n    android:tint="{tint}">' if tint else '>')]
    for s in shapes:
        d, fill, op = s[:3]
        if op == 0:
            continue
        attrs = [f'android:pathData="{d}"']
        if len(s) > 3:
            attrs.append('android:fillType="evenOdd"')
        if isinstance(fill, tuple):
            _, x1, y1, x2, y2, stops = fill
            if op < 1:
                attrs.append(f'android:fillAlpha="{op}"')
            items = '\n'.join(
                f'                    <item android:offset="{t[0]}" '
                f'android:color="{_hex8(t[1], t[2] if len(t) > 2 else 1)}" />'
                for t in stops)
            out.append('    <path\n        ' + '\n        '.join(attrs) + '>\n'
                       '        <aapt:attr name="android:fillColor">\n'
                       f'            <gradient\n                android:type="linear"\n'
                       f'                android:startX="{x1}"\n                android:startY="{y1}"\n'
                       f'                android:endX="{x2}"\n                android:endY="{y2}">\n'
                       f'{items}\n            </gradient>\n        </aapt:attr>\n    </path>')
        else:
            attrs.append(f'android:fillColor="{_hex8(fill, op)}"')
            out.append('    <path\n        ' + '\n        '.join(attrs) + ' />')
    out.append('</vector>')
    return '\n'.join(out) + '\n'
