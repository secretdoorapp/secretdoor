"""SecretDoor icon, version 2: a closed round steel vault door with a glowing digital padlock.

Usage: vault_icon.py PREVIEW.png [--apply]
Writes a preview sheet (launcher sizes, round and squircle masks, themed icon, small glyph) and,
with --apply, the VectorDrawables used by the app (launcher foreground/monochrome for all build
types, splash icon, autofill logo, notification and shield glyphs).
"""
import math
import os
import sys

import cairo
import gi

gi.require_version('Rsvg', '2.0')
from gi.repository import Rsvg  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from icons import emit_svg, emit_vd, keyhole  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
NAVY = '#14233B'
PANEL = '#0A1424'
CYAN = '#3CCBFF'
CYAN_LIGHT = '#9BEBFF'
BOLT = '#3A4554'

CX, CY = 55.0, 54.0


def circle(cx, cy, r):
    return (f'M{cx - r:.2f},{cy:.2f} A{r},{r} 0 1,0 {cx + r:.2f},{cy:.2f} '
            f'A{r},{r} 0 1,0 {cx - r:.2f},{cy:.2f} Z')


def point(cx, cy, r, degrees):
    a = math.radians(degrees)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def ring_segment(cx, cy, r_in, r_out, a0, a1):
    x0, y0 = point(cx, cy, r_out, a0)
    x1, y1 = point(cx, cy, r_out, a1)
    x2, y2 = point(cx, cy, r_in, a1)
    x3, y3 = point(cx, cy, r_in, a0)
    return (f'M{x0:.2f},{y0:.2f} A{r_out},{r_out} 0 0,1 {x1:.2f},{y1:.2f} '
            f'L{x2:.2f},{y2:.2f} A{r_in},{r_in} 0 0,0 {x3:.2f},{y3:.2f} Z')


def rounded_rect(x0, y0, x1, y1, r):
    return (f'M{x0 + r},{y0} H{x1 - r} A{r},{r} 0 0,1 {x1},{y0 + r} V{y1 - r} '
            f'A{r},{r} 0 0,1 {x1 - r},{y1} H{x0 + r} A{r},{r} 0 0,1 {x0},{y1 - r} '
            f'V{y0 + r} A{r},{r} 0 0,1 {x0 + r},{y0} Z')


def steel(x1, y1, x2, y2, light='#E3E9EF', dark='#5E6A79'):
    return ('lin', x1, y1, x2, y2, [(0, light), (1, dark)])


def padlock(cx, top, scale=1.0):
    """Shackle and body of a padlock centred on [cx]; returns (shackle, body) path data."""
    s = scale
    shackle_y = top + 4.5 * s
    body_top = top + 8.5 * s
    shackle = (f'M{cx - 5 * s:.2f},{body_top:.2f} V{shackle_y:.2f} '
               f'A{5 * s:.2f},{5 * s:.2f} 0 0,1 {cx + 5 * s:.2f},{shackle_y:.2f} '
               f'V{body_top:.2f} H{cx + 2.5 * s:.2f} V{shackle_y:.2f} '
               f'A{2.5 * s:.2f},{2.5 * s:.2f} 0 0,0 {cx - 2.5 * s:.2f},{shackle_y:.2f} '
               f'V{body_top:.2f} Z')
    body = rounded_rect(cx - 7.5 * s, body_top, cx + 7.5 * s, body_top + 11.5 * s, 2 * s)
    hole = keyhole(cx, body_top + 4.6 * s, 1.8 * s, 0.75 * s, 1.35 * s, body_top + 9 * s)
    return shackle, body, hole


SHACKLE, BODY, HOLE = padlock(CX, 44.0)

# Hinges on the left edge of the door, inside the adaptive icon safe zone.
HINGES = [rounded_rect(23, 42, 29, 49, 1.2), rounded_rect(23, 59, 29, 66, 1.2)]
BOLTS = [circle(*point(CX, CY, 26.2, 22.5 + 45 * i), 1.25) for i in range(8)]
DIGITAL_RING = [ring_segment(CX, CY, 15.0, 16.6, 30 * i + 5, 30 * i + 25) for i in range(12)]

FOREGROUND = (
    [(h, steel(23, 42, 29, 66, '#C9D2DC', '#4E5968'), 1) for h in HINGES]
    + [
        # Rim, then the door face with the light coming from the other side (bevel).
        (circle(CX, CY, 28.5), steel(30, 28, 80, 82), 1),
        (circle(CX, CY, 24.5), steel(30, 28, 80, 82, '#7D8998', '#D3DBE3'), 1),
        (circle(CX, CY, 23.2), steel(30, 28, 80, 82, '#C5CED8', '#8692A1'), 1),
    ]
    + [(b, BOLT, 1) for b in BOLTS]
    + [
        # Digital panel with a soft glow around it.
        (circle(CX, CY, 19.2), CYAN, 0.25),
        (circle(CX, CY, 18.2), PANEL, 1),
    ]
    + [(seg, CYAN, 0.95) for seg in DIGITAL_RING]
    + [
        (SHACKLE, ('lin', CX, 44, CX, 53, [(0, CYAN_LIGHT), (1, CYAN)]), 1),
        (BODY + ' ' + HOLE, ('lin', CX, 52, CX, 64, [(0, CYAN_LIGHT), (1, CYAN)]), 1,
         'evenOdd'),
    ]
)

# Themed (one-color) icon: rim ring, hinges and padlock.
MONOCHROME = (
    [(h, '#FFFFFF', 1) for h in HINGES]
    + [
        (circle(CX, CY, 28.5) + ' ' + circle(CX, CY, 23.5), '#FFFFFF', 1, 'evenOdd'),
        (SHACKLE, '#FFFFFF', 1),
        (BODY + ' ' + HOLE, '#FFFFFF', 1, 'evenOdd'),
    ]
)

# 24dp glyph (notification, small logo): door ring with the padlock.
_S_SHACKLE, _S_BODY, _S_HOLE = padlock(12.0, 5.2, scale=0.62)
SMALL = [
    (circle(12, 12, 10.5) + ' ' + circle(12, 12, 8.6), '#FFFFFF', 1, 'evenOdd'),
    (_S_SHACKLE, '#FFFFFF', 1),
    (_S_BODY + ' ' + _S_HOLE, '#FFFFFF', 1, 'evenOdd'),
]


def render(svg_text, size, bg=None, mask=None):
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    if mask == 'circle':
        ctx.arc(size / 2, size / 2, size / 2, 0, 2 * math.pi)
        ctx.clip()
    elif mask == 'squircle':
        r = size * 0.28
        ctx.new_sub_path()
        for x, y, a in ((size - r, r, -90), (size - r, size - r, 0), (r, size - r, 90),
                        (r, r, 180)):
            ctx.arc(x, y, r, math.radians(a), math.radians(a + 90))
        ctx.close_path()
        ctx.clip()
    if bg:
        ctx.set_source_rgb(*(int(bg[i:i + 2], 16) / 255 for i in (1, 3, 5)))
        ctx.paint()
    handle = Rsvg.Handle.new_from_data(svg_text.encode())
    viewport = Rsvg.Rectangle()
    # Adaptive icons show the middle 72 of the 108 units; scale so that part fills the tile.
    zoom = 108 / 72 if mask else 1
    viewport.x = viewport.y = -(zoom - 1) * size / 2
    viewport.width = viewport.height = size * zoom
    handle.render_document(ctx, viewport)
    return surface


def preview(path):
    fg = emit_svg(FOREGROUND)
    mono = emit_svg([(s[0], '#D8E6FF') + tuple(s[2:]) for s in MONOCHROME])
    small = emit_svg(SMALL, vp=24)
    tiles = [(fg, 192, NAVY, 'squircle'), (fg, 192, NAVY, 'circle'), (fg, 132, NAVY, 'circle'),
             (fg, 96, NAVY, 'circle'), (fg, 48, NAVY, 'circle'), (fg, 132, '#6B2330', 'circle'),
             (mono, 132, '#2B3A55', 'circle'), (small, 48, '#202124', None),
             (small, 24, '#202124', None)]
    width = sum(t[1] for t in tiles) + 16 * (len(tiles) + 1)
    sheet = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, 232)
    ctx = cairo.Context(sheet)
    ctx.set_source_rgb(0.93, 0.93, 0.93)
    ctx.paint()
    x = 16
    for svg, size, bg, mask in tiles:
        ctx.set_source_surface(render(svg, size, bg, mask), x, 20)
        ctx.paint()
        x += size + 16
    sheet.write_to_png(path)


def apply():
    def write(rel, text):
        with open(os.path.join(REPO, rel), 'w') as f:
            f.write(text)

    for build_type in ('main', 'beta', 'release'):
        write(f'app/src/{build_type}/res/drawable/ic_launcher_foreground.xml',
              emit_vd(FOREGROUND))
        write(f'app/src/{build_type}/res/drawable/ic_launcher_monochrome.xml',
              emit_vd(MONOCHROME))
    ui = 'ui/src/main/res/drawable'
    write(f'{ui}/logo_shield_icon.xml', emit_vd(FOREGROUND))
    write(f'{ui}/logo_bitwarden_icon.xml',
          emit_vd([(rounded_rect(8, 8, 100, 100, 22), NAVY, 1)] + FOREGROUND))
    write(f'{ui}/ic_notification.xml', emit_vd(SMALL, vp=24))
    write(f'{ui}/ic_shield.xml',
          emit_vd([(s[0], '#000000') + tuple(s[2:]) for s in SMALL], vp=24))


if __name__ == '__main__':
    preview(sys.argv[1])
    if '--apply' in sys.argv:
        apply()
