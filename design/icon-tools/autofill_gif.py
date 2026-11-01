"""Draws the "Set up autofill" animation (gif_setup_autofill.gif, light and night) with SecretDoor
branding (the round SecretDoor logo, secretdoor_logo.jpg): a login form, the keyboard suggestion chip "SecretDoor  My vault", unlock, filled fields
and a tap on "Log in". Usage: autofill_gif.py RES_DIR (e.g. ui/src/main/res).
"""
import io
import os
import sys

import cairo
import gi
from PIL import Image, ImageDraw, ImageFont

gi.require_version('Rsvg', '2.0')
from gi.repository import Rsvg  # noqa: E402

sys.path.insert(0, os.path.dirname(__file__))
from icons import SMALL_A, emit_svg  # noqa: E402

W, H = 595, 720
S = 2  # supersampling
FPS_MS = 40
DURATION = 7.2
FONT_DIR = os.path.join(os.path.dirname(__file__), '../../ui/src/main/res/font')

THEMES = {
    'drawable': dict(
        bg=(243, 246, 249), phone=(255, 255, 255), camera=(237, 240, 245),
        border=(205, 212, 224), focus=(20, 35, 59), fill=(20, 35, 59),
        btn_off=(214, 220, 231), btn_off_text=(164, 176, 196),
        btn_on=(20, 35, 59), btn_on_text=(255, 255, 255),
        kb=(236, 236, 240), key=(214, 216, 222), chip=(222, 223, 228),
        chip_text=(27, 27, 31), chip_sub=(110, 112, 120), logo_bg=(20, 35, 59),
        glyph=(20, 35, 59), ring=(20, 35, 59), tap=(242, 181, 68),
    ),
    'drawable-night': dict(
        bg=(16, 26, 40), phone=(33, 38, 48), camera=(20, 27, 38),
        border=(85, 92, 105), focus=(242, 181, 68), fill=(108, 160, 240),
        btn_off=(52, 58, 70), btn_off_text=(110, 118, 132),
        btn_on=(108, 160, 240), btn_on_text=(16, 20, 30),
        kb=(30, 30, 34), key=(55, 55, 62), chip=(52, 52, 58),
        chip_text=(255, 255, 255), chip_sub=(150, 150, 158), logo_bg=(20, 35, 59),
        glyph=(255, 255, 255), ring=(108, 160, 240), tap=(242, 181, 68),
    ),
}


def door(size, color):
    """Renders the 24dp SecretDoor glyph as an RGBA image."""
    hexc = '#%02X%02X%02X' % color
    shapes = [(s[0], hexc) + tuple(s[2:]) for s in SMALL_A]
    handle = Rsvg.Handle.new_from_data(emit_svg(shapes, vp=24).encode())
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surf)
    vp = Rsvg.Rectangle()
    vp.x, vp.y, vp.width, vp.height = 0, 0, size, size
    handle.render_document(ctx, vp)
    buf = io.BytesIO()
    surf.write_to_png(buf)
    return Image.open(io.BytesIO(buf.getvalue())).convert('RGBA')


def logo(size, _cache={}):
    """The round SecretDoor logo (secretdoor_logo.jpg) cropped to a circle, as an RGBA image."""
    src = _cache.get('src')
    if src is None:
        src = _cache['src'] = Image.open(os.path.join(os.path.dirname(__file__),
                                                      'secretdoor_logo.jpg')).convert('RGB')
    w, h = src.size
    side = min(w, h)
    img = src.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side,
                    (h - side) // 2 + side)).resize((size, size), Image.LANCZOS).convert('RGBA')
    mask = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse([size * 4 * 0.02, size * 4 * 0.02, size * 4 * 0.98,
                                  size * 4 * 0.98], fill=255)
    img.putalpha(mask.resize((size, size), Image.LANCZOS))
    return img


def ease(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


def ramp(t, a, b):
    return ease((t - a) / (b - a))


def mix(c1, c2, k):
    return tuple(round(a + (b - a) * k) for a, b in zip(c1, c2))


def font(name, px):
    return ImageFont.truetype(os.path.join(FONT_DIR, f'dm_sans_{name}.ttf'), px * S)


def frame(t, th, glyphs):
    img = Image.new('RGB', (W * S, H * S), th['bg'])
    d = ImageDraw.Draw(img, 'RGBA')

    def r(x0, y0, x1, y1, rad, fill=None, outline=None, width=0):
        d.rounded_rectangle([x0 * S, y0 * S, x1 * S, y1 * S], radius=rad * S, fill=fill,
                            outline=outline, width=width * S)

    def circle(cx, cy, rad, fill=None, outline=None, width=0):
        d.ellipse([(cx - rad) * S, (cy - rad) * S, (cx + rad) * S, (cy + rad) * S], fill=fill,
                  outline=outline, width=round(width * S))

    def paste(glyph, cx, cy, alpha=1.0):
        g = glyph
        if alpha < 1:
            g = glyph.copy()
            g.putalpha(g.getchannel('A').point(lambda v: round(v * alpha)))
        img.paste(g, (round(cx * S - g.width / 2), round(cy * S - g.height / 2)), g)

    # Timeline (seconds).
    appear = ramp(t, 0.2, 0.6) * (1 - ramp(t, 6.5, 7.0))
    focus = ramp(t, 0.7, 0.9) * (1 - ramp(t, 2.0, 2.2))
    kb_up = ramp(t, 0.9, 1.3) * (1 - ramp(t, 2.0, 2.4))
    unlock = ramp(t, 2.3, 2.6) * (1 - ramp(t, 3.4, 3.7))
    filled = ramp(t, 3.5, 3.8) * (1 - ramp(t, 6.5, 7.0))
    dim = 1 - 0.7 * unlock

    # Phone.
    r(12, 30, W - 12, H + 60, 50, fill=th['phone'])
    circle(W / 2, 93, 14, fill=th['camera'])

    def fade(c, k):
        return mix(th['phone'], c, k * dim)

    # Fields.
    for i, (y0, y1) in enumerate(((150, 240), (262, 350))):
        border = mix(th['border'], th['focus'], focus) if i == 0 else th['border']
        r(42, y0, W - 42, y1, 8, outline=fade(border, appear), width=4)
        if filled > 0:
            cy = (y0 + y1) / 2
            col = fade(th['fill'], filled)
            if i == 0:
                r(74, cy - 8, 74 + 368 * filled, cy + 8, 8, fill=col)
            else:
                for n in range(round(12 * filled)):
                    circle(81 + n * 25.5, cy, 8, fill=col)
            paste(glyphs['field'], W - 74, cy, filled * dim)

    # Log in button.
    btn = mix(th['btn_off'], th['btn_on'], filled)
    txt = mix(th['btn_off_text'], th['btn_on_text'], filled)
    r(40, 405, W - 40, 475, 35, fill=fade(btn, appear))
    label_font = font('semi_bold', 26)
    tw = d.textlength('Log in', font=label_font) / S
    tx = W / 2 - (tw + 54) / 2
    d.text((tx * S, 440 * S), 'Log in', font=label_font, fill=fade(txt, appear), anchor='lm')
    ax = tx + tw + 14
    d.line([(ax * S, 440 * S), ((ax + 36) * S, 440 * S)], fill=fade(txt, appear), width=4 * S)
    d.line([((ax + 24) * S, 428 * S), ((ax + 37) * S, 440 * S), ((ax + 24) * S, 452 * S)],
           fill=fade(txt, appear), width=4 * S, joint='curve')

    # Keyboard with the autofill suggestion chip.
    if kb_up > 0:
        top = H - 205 * kb_up
        d.rectangle([12 * S, top * S, (W - 12) * S, H * S], fill=th['kb'])
        circle(60, top + 45, 20, fill=th['key'])
        circle(W - 60, top + 45, 20, fill=th['key'])
        name_font, sub_font = font('medium', 24), font('regular', 24)
        nw = d.textlength('SecretDoor', font=name_font) / S
        sw = d.textlength('My vault', font=sub_font) / S
        cw = 14 + 42 + 14 + nw + 18 + sw + 18
        cx0 = W / 2 - cw / 2
        r(cx0, top + 18, cx0 + cw, top + 78, 8, fill=th['chip'])
        paste(glyphs['chip'], cx0 + 31, top + 48)
        d.text(((cx0 + 66) * S, (top + 48) * S), 'SecretDoor', font=name_font,
               fill=th['chip_text'], anchor='lm')
        d.text(((cx0 + 66 + nw + 18) * S, (top + 48) * S), 'My vault', font=sub_font,
               fill=th['chip_sub'], anchor='lm')
        for row, (n, x0) in enumerate(((10, 31), (9, 58))):
            y = top + 96 + row * 76
            for k in range(n):
                r(x0 + k * 54.2, y, x0 + k * 54.2 + 44, y + 60, 6, fill=th['key'])
        # Tap on the chip.
        tap = ramp(t, 1.5, 1.7) * (1 - ramp(t, 1.9, 2.1))
        if tap > 0:
            circle(cx0 + 66 + nw * 0.75, top + 48, 18 + 6 * tap, fill=th['tap'] + (round(210 * tap),))

    # Unlock: the door in a ring, with a pulse.
    if unlock > 0:
        pulse = ramp(t, 2.6, 3.2)
        scale = 0.7 + 0.3 * unlock
        rad = 84 * scale
        a = round(255 * unlock)
        circle(W / 2, 395, rad + 30 * pulse, outline=th['ring'] + (round(120 * (1 - pulse) * unlock),),
               width=4)
        circle(W / 2, 395, rad, fill=th['phone'] + (a,), outline=th['ring'] + (a,), width=7)
        paste(glyphs['big'], W / 2, 395, unlock)

    # Tap on "Log in".
    tap = ramp(t, 4.4, 4.6) * (1 - ramp(t, 4.9, 5.1))
    if tap > 0:
        circle(tx + tw * 0.75, 440, 24 + 4 * tap, fill=th['tap'] + (round(210 * tap),))

    return img.resize((W, H), Image.LANCZOS)


def main(res_dir):
    for folder, th in THEMES.items():
        glyphs = {'field': logo(38 * S), 'chip': logo(42 * S), 'big': logo(150 * S)}
        frames = [frame(i * FPS_MS / 1000, th, glyphs)
                  for i in range(round(DURATION * 1000 / FPS_MS))]
        keys = [frames[round(sec * 1000 / FPS_MS)] for sec in (1.6, 2.5, 2.9, 4.5)]
        sheet = Image.new('RGB', (W, H * len(keys)))
        for k, f in enumerate(keys):
            sheet.paste(f, (0, k * H))
        palette = sheet.quantize(colors=255, method=Image.MEDIANCUT)
        out = [f.quantize(palette=palette, dither=Image.NONE) for f in frames]
        path = os.path.join(res_dir, folder, 'gif_setup_autofill.gif')
        out[0].save(path, save_all=True, append_images=out[1:], duration=FPS_MS, loop=0,
                    optimize=True, disposal=1)
        print(path, os.path.getsize(path))


if __name__ == '__main__':
    main(sys.argv[1])
