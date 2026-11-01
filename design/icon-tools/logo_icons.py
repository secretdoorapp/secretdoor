"""Builds the launcher foreground PNGs and the splash icon PNGs from the round SecretDoor logo
(design/icon-source/secretdoor_logo_1024.jpg). The logo is cropped to a circle and centred on a
transparent canvas at 63% of its width, which keeps it inside the adaptive icon safe zone.
Usage: logo_icons.py [PREVIEW.png]   (run from anywhere; writes into app/ and ui/ res folders)
"""
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '../..'))
SRC = os.path.join(HERE, '../icon-source/secretdoor_logo_1024.jpg')
FRACTION = 0.63
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def round_logo(diameter):
    src = Image.open(SRC).convert('RGB')
    w, h = src.size
    side = min(w, h)
    src = src.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side,
                    (h - side) // 2 + side))
    big = Image.new('L', (side, side), 0)
    ImageDraw.Draw(big).ellipse([side * 0.018, side * 0.018, side * 0.982, side * 0.982], fill=255)
    rgba = src.convert('RGBA')
    rgba.putalpha(big)
    return rgba.resize((diameter, diameter), Image.LANCZOS)


def canvas(size):
    logo = round_logo(round(size * FRACTION))
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(logo, ((size - logo.width) // 2, (size - logo.height) // 2), logo)
    return out


def main():
    for name, k in DENSITIES.items():
        fg = os.path.join(REPO, f'app/src/main/res/drawable-{name}/ic_launcher_foreground.png')
        canvas(round(108 * k)).save(fg, optimize=True)
        splash = os.path.join(REPO, f'ui/src/main/res/drawable-{name}/logo_shield_icon.png')
        canvas(round(240 * k / 1)).save(splash, optimize=True)
    if len(sys.argv) > 1:
        bg = Image.new('RGBA', (432 * 3, 432), (11, 18, 22, 255))
        fg = canvas(432)
        for i, shape in enumerate(('round', 'squircle', 'square')):
            tile = Image.new('RGBA', (432, 432), (11, 18, 22, 255))
            tile.alpha_composite(fg)
            m = Image.new('L', (432, 432), 0)
            d = ImageDraw.Draw(m)
            if shape == 'round':
                d.ellipse([0, 0, 431, 431], fill=255)
            elif shape == 'squircle':
                d.rounded_rectangle([0, 0, 431, 431], radius=130, fill=255)
            else:
                d.rounded_rectangle([0, 0, 431, 431], radius=40, fill=255)
            bg.paste(tile, (i * 432, 0), m)
        bg.save(sys.argv[1])


if __name__ == '__main__':
    main()
