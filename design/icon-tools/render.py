"""Render SVG(s) to PNG with librsvg. Usage: render.py OUT.png SIZE BG SVG [SVG...]
Renders each SVG into a SIZExSIZE tile on background BG (hex or 'none'), tiles side by side.
"""
import sys
import gi
gi.require_version('Rsvg', '2.0')
from gi.repository import Rsvg
import cairo

out, size, bg = sys.argv[1], int(sys.argv[2]), sys.argv[3]
files = sys.argv[4:]
gap = 8
surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, len(files) * (size + gap) + gap, size + 2 * gap)
ctx = cairo.Context(surf)
ctx.set_source_rgb(0.85, 0.85, 0.85)
ctx.paint()
for i, f in enumerate(files):
    x = gap + i * (size + gap)
    ctx.save()
    ctx.translate(x, gap)
    if bg != 'none':
        h = bg.lstrip('#')
        ctx.set_source_rgb(*(int(h[j:j + 2], 16) / 255 for j in (0, 2, 4)))
        ctx.rectangle(0, 0, size, size)
        ctx.fill()
    handle = Rsvg.Handle.new_from_file(f)
    vp = Rsvg.Rectangle()
    vp.x, vp.y, vp.width, vp.height = 0, 0, size, size
    handle.render_document(ctx, vp)
    ctx.restore()
surf.write_to_png(out)
