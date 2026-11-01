"""SecretDoor icon set, version 3: artwork cut from the generated icon sheet.

Usage: python3 -I sheet_icons.py [SHEET.jpeg] [--preview PREVIEW.png]

Reads design/icon-source/secretdoor_icon_sheet.jpeg (1408x768) by default and writes the
density-bucketed PNGs used by the app:

* launcher foreground (Access Control / Handle badge) for every build type, plus a matching
  vector monochrome layer for themed icons
* splash screen icon and autofill / credential-exchange logo (same badge)
* untinted artwork for the vault "Types" rows (Passwords, Credit Cards, Identities, Notes)
* the Master Key / Authentication badge shown above the unlock field
* the empty-vault illustration (the four type tiles in a 2x2 grid)

With --preview, also writes a contact sheet of the results on light and dark backgrounds.
"""
import os
import sys

from PIL import Image, ImageDraw

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
DEFAULT_SHEET = os.path.join(REPO, 'design/icon-source/secretdoor_icon_sheet.jpeg')

DENSITIES = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0, 'xxxhdpi': 4.0}
SUPERSAMPLE = 4

# Round badges on the sheet: (center x, center y, radius) in sheet pixels.
ACCESS_CONTROL = (741.0, 231.0, 108.5)
MASTER_KEY = (1254.0, 231.0, 108.5)

# Data-type tiles: outer border box (left, top, right, bottom) in sheet pixels.
TILE_TOP, TILE_BOTTOM = 439, 550
TILES = {
    'passwords': (784, 900),
    'cards': (939, 1054),
    'notes': (1093, 1208),
    'identities': (1247, 1363),
}
TILE_INSET = 3

APP_RES = os.path.join(REPO, 'app/src/main/res')
UI_RES = os.path.join(REPO, 'ui/src/main/res')


def circle_mask(size, radius_px, center):
    """Anti-aliased circular alpha mask."""
    big = Image.new('L', (size * SUPERSAMPLE, size * SUPERSAMPLE), 0)
    cx, cy = center[0] * SUPERSAMPLE, center[1] * SUPERSAMPLE
    r = radius_px * SUPERSAMPLE
    ImageDraw.Draw(big).ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def rounded_mask(size, radius_px):
    big = Image.new('L', (size * SUPERSAMPLE, size * SUPERSAMPLE), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, size * SUPERSAMPLE - 1, size * SUPERSAMPLE - 1),
        radius=radius_px * SUPERSAMPLE,
        fill=255,
    )
    return big.resize((size, size), Image.LANCZOS)


def badge(sheet, spec, out_px):
    """Cuts a round badge out of the sheet and returns it as an RGBA square of out_px."""
    cx, cy, r = spec
    box = (cx - r, cy - r, cx + r, cy + r)
    art = sheet.crop(tuple(round(v) for v in box)).resize((out_px, out_px), Image.LANCZOS)
    art.putalpha(circle_mask(out_px, out_px / 2 - 0.5, (out_px / 2, out_px / 2)))
    return art


def on_canvas(art, canvas_px):
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    off = (canvas_px - art.width) // 2
    canvas.alpha_composite(art, (off, off))
    return canvas


def tile(sheet, name, out_px):
    left, right = TILES[name]
    box = (left + TILE_INSET, TILE_TOP + TILE_INSET, right - TILE_INSET, TILE_BOTTOM - TILE_INSET)
    side = min(box[2] - box[0], box[3] - box[1])
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    square = (cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    art = sheet.crop(tuple(round(v) for v in square)).resize((out_px, out_px), Image.LANCZOS)
    art = art.convert('RGBA')
    art.putalpha(rounded_mask(out_px, round(out_px * 0.2)))
    return art


GRID_ORDER = [['passwords', 'cards'], ['identities', 'notes']]


def type_grid(sheet, scale, size_dp=128, gap_dp=8):
    size = round(size_dp * scale)
    gap = round(gap_dp * scale)
    cell = (size - gap) // 2
    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    for r, row in enumerate(GRID_ORDER):
        for c, name in enumerate(row):
            canvas.alpha_composite(tile(sheet, name, cell), (c * (cell + gap), r * (cell + gap)))
    return canvas


def write_density_set(res_dir, name, make):
    """make(scale) -> RGBA image; written to drawable-<density>/<name>.png."""
    written = []
    for bucket, scale in DENSITIES.items():
        folder = os.path.join(res_dir, f'drawable-{bucket}')
        os.makedirs(folder, exist_ok=True)
        path = os.path.join(folder, f'{name}.png')
        make(scale).save(path, optimize=True)
        written.append(path)
    return written


def monochrome_vector():
    """Themed-icon layer: hexagon ring + padlock with a keyhole, inside the 66dp safe zone."""
    import math

    def hexagon(r):
        pts = [(54 + r * math.cos(math.radians(a)), 54 + r * math.sin(math.radians(a)))
               for a in range(-90, 270, 60)]
        return 'M' + ' L'.join(f'{x:.2f},{y:.2f}' for x, y in pts) + ' Z'

    ring = hexagon(29) + ' ' + hexagon(25.5)
    # Shackle: an arch of outer radius 8.5 and inner radius 5 above the body.
    shackle = ('M45.5,52 L45.5,45 A8.5,8.5 0 0,1 62.5,45 L62.5,52 L59,52 L59,45 '
               'A5,5 0 0,0 49,45 L49,52 Z')
    body = ('M43.5,50 L64.5,50 A2.5,2.5 0 0,1 67,52.5 L67,68.5 A2.5,2.5 0 0,1 64.5,71 '
            'L43.5,71 A2.5,2.5 0 0,1 41,68.5 L41,52.5 A2.5,2.5 0 0,1 43.5,50 Z')
    keyhole = ('M54,55 A3,3 0 0,1 55.6,60.5 L56.6,66 L51.4,66 L52.4,60.5 A3,3 0 0,1 54,55 Z')
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by design/icon-tools/sheet_icons.py: Access Control / Handle glyph. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{ring}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{shackle}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{body} {keyhole}" />
</vector>
'''


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    preview = argv[argv.index('--preview') + 1] if '--preview' in argv else None
    if preview in args:
        args.remove(preview)
    sheet = Image.open(args[0] if args else DEFAULT_SHEET).convert('RGB')
    written = []

    # Adaptive launcher foreground: 108dp canvas, badge 68dp so the whole ring stays inside
    # the 72dp visible area under any launcher mask.
    written += write_density_set(
        APP_RES, 'ic_launcher_foreground',
        lambda s: on_canvas(badge(sheet, ACCESS_CONTROL, round(68 * s)), round(108 * s)),
    )
    mono = os.path.join(APP_RES, 'drawable/ic_launcher_monochrome.xml')
    with open(mono, 'w') as f:
        f.write(monochrome_vector())
    written.append(mono)

    # Splash icon: 240dp canvas, masked to a 160dp circle by the system; badge 152dp.
    written += write_density_set(
        UI_RES, 'logo_shield_icon',
        lambda s: on_canvas(badge(sheet, ACCESS_CONTROL, round(152 * s)), round(240 * s)),
    )
    # Autofill / credential-exchange logo: tight badge, 48dp.
    written += write_density_set(
        UI_RES, 'logo_bitwarden_icon', lambda s: badge(sheet, ACCESS_CONTROL, round(48 * s)),
    )
    # Unlock header: Master Key badge, 88dp.
    written += write_density_set(
        UI_RES, 'art_master_key', lambda s: badge(sheet, MASTER_KEY, round(88 * s)),
    )
    # Vault "Types" rows: 36dp rounded tiles.
    for name in TILES:
        written += write_density_set(
            UI_RES, f'art_type_{name}', lambda s, n=name: tile(sheet, n, round(36 * s)),
        )

    # Empty-vault illustration: the four type tiles in a 2x2 grid, 128dp square.
    written += write_density_set(UI_RES, 'art_vault_items', lambda s: type_grid(sheet, s))

    for path in written:
        print(os.path.relpath(path, REPO))

    if preview:
        write_preview(sheet, preview)


def write_preview(sheet, path):
    backgrounds = [('#14233B', 'release'), ('#4B2A6B', 'beta'), ('#6B2330', 'debug')]
    cell = 216
    img = Image.new('RGBA', (cell * 7, cell * 2), (255, 255, 255, 255))
    fg = on_canvas(badge(sheet, ACCESS_CONTROL, 136), cell)
    for i, (color, _) in enumerate(backgrounds):
        bg = Image.new('RGBA', (cell, cell), color)
        bg.alpha_composite(fg)
        masked = Image.new('RGBA', (cell, cell), (0, 0, 0, 0))
        masked.paste(bg, (0, 0), circle_mask(cell, 72, (cell / 2, cell / 2)))
        img.alpha_composite(masked, (i * cell, 0))
        squircle = Image.new('RGBA', (cell, cell), (0, 0, 0, 0))
        squircle.paste(bg, (0, 0), on_canvas_mask(cell))
        img.alpha_composite(squircle, (i * cell, cell))
    img.alpha_composite(on_canvas(badge(sheet, MASTER_KEY, 180), cell), (3 * cell, 0))
    for half, color in enumerate(['#FFFFFF', '#1B1B1F']):
        panel = Image.new('RGBA', (cell * 3, cell), color)
        for j, name in enumerate(TILES):
            panel.alpha_composite(tile(sheet, name, 108), (20 + j * 160, 54))
        img.alpha_composite(panel, (4 * cell, half * cell))
    img.save(path)


def on_canvas_mask(size):
    m = Image.new('L', (size, size), 0)
    inset = (size - 144) // 2
    ImageDraw.Draw(m).rounded_rectangle((inset, inset, size - inset, size - inset), 44, fill=255)
    return m


if __name__ == '__main__':
    main(sys.argv[1:])
