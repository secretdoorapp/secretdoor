"""Convert an Android VectorDrawable XML file to SVG text.

Usage: vd2svg.py IN.xml [OUT.svg]   (or import and call convert(path) -> str)
Supports vector/group/clip-path/path, hex colors, aapt:attr linear/radial gradients.
"""
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
AAPT = '{http://schemas.android.com/aapt}'
_gid = [0]


def color(c):
    """#RGB/#ARGB/#RRGGBB/#AARRGGBB -> (css color, opacity)."""
    if not c or not c.startswith('#'):
        return ('none', 1.0) if not c else ('#000000', 1.0)
    h = c[1:]
    if len(h) == 3:
        h = ''.join(x * 2 for x in h)
    if len(h) == 4:
        h = ''.join(x * 2 for x in h)
    if len(h) == 8:
        return '#' + h[2:], int(h[:2], 16) / 255
    return '#' + h, 1.0


def num(v, d=0.0):
    try:
        return float(v.rstrip('dp').rstrip('px'))
    except (AttributeError, ValueError):
        return d


def gradient(el, defs):
    g = el.find('gradient')
    if g is None:
        return None
    _gid[0] += 1
    gid = f'g{_gid[0]}'
    typ = g.get(A + 'type', 'linear')
    stops = []
    items = g.findall('item')
    if items:
        for it in items:
            c, o = color(it.get(A + 'color'))
            stops.append((num(it.get(A + 'offset')), c, o))
    else:
        for k, off in (('startColor', 0), ('centerColor', .5), ('endColor', 1)):
            if g.get(A + k):
                c, o = color(g.get(A + k))
                stops.append((off, c, o))
    st = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a:.3f}"/>' for o, c, a in stops)
    if typ == 'radial':
        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                    f'cx="{g.get(A + "centerX", 0)}" cy="{g.get(A + "centerY", 0)}" '
                    f'r="{g.get(A + "gradientRadius", 0)}">{st}</radialGradient>')
    else:
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                    f'x1="{g.get(A + "startX", 0)}" y1="{g.get(A + "startY", 0)}" '
                    f'x2="{g.get(A + "endX", 0)}" y2="{g.get(A + "endY", 0)}">{st}</linearGradient>')
    return f'url(#{gid})'


def path(el, defs):
    d = el.get(A + 'pathData', '')
    attrs = [f'd="{d}"']
    fill = None
    stroke = None
    for aa in el.findall(AAPT + 'attr'):
        name = aa.get('name')
        if name == 'android:fillColor':
            fill = gradient(aa, defs)
        elif name == 'android:strokeColor':
            stroke = gradient(aa, defs)
    if fill is None:
        fc = el.get(A + 'fillColor')
        if fc:
            c, o = color(fc)
            fill = c
            o *= num(el.get(A + 'fillAlpha'), 1.0)
            if o < 1:
                attrs.append(f'fill-opacity="{o:.3f}"')
        else:
            fill = 'none'
    attrs.append(f'fill="{fill}"')
    if el.get(A + 'fillType') == 'evenOdd':
        attrs.append('fill-rule="evenodd"')
    if stroke is None and el.get(A + 'strokeColor'):
        c, o = color(el.get(A + 'strokeColor'))
        stroke = c
        o *= num(el.get(A + 'strokeAlpha'), 1.0)
        if o < 1:
            attrs.append(f'stroke-opacity="{o:.3f}"')
    if stroke:
        attrs.append(f'stroke="{stroke}" stroke-width="{num(el.get(A + "strokeWidth"), 0)}"')
        cap = el.get(A + 'strokeLineCap')
        join = el.get(A + 'strokeLineJoin')
        if cap:
            attrs.append(f'stroke-linecap="{cap}"')
        if join:
            attrs.append(f'stroke-linejoin="{join}"')
    return '<path ' + ' '.join(attrs) + '/>'


def walk(el, defs):
    out = []
    clip = None
    for ch in el:
        tag = ch.tag
        if tag == 'clip-path':
            _gid[0] += 1
            clip = f'c{_gid[0]}'
            defs.append(f'<clipPath id="{clip}"><path d="{ch.get(A + "pathData", "")}"/></clipPath>')
        elif tag == 'path':
            out.append(path(ch, defs))
        elif tag == 'group':
            tx, ty = num(ch.get(A + 'translateX')), num(ch.get(A + 'translateY'))
            sx, sy = num(ch.get(A + 'scaleX'), 1), num(ch.get(A + 'scaleY'), 1)
            r = num(ch.get(A + 'rotation'))
            px, py = num(ch.get(A + 'pivotX')), num(ch.get(A + 'pivotY'))
            # Android order: translate, then rotate/scale around pivot.
            t = (f'translate({tx} {ty}) translate({px} {py}) rotate({r}) '
                 f'scale({sx} {sy}) translate({-px} {-py})')
            out.append(f'<g transform="{t}">' + walk(ch, defs) + '</g>')
    body = ''.join(out)
    return f'<g clip-path="url(#{clip})">{body}</g>' if clip else body


def convert(src):
    root = ET.parse(src).getroot()
    if root.tag != 'vector':
        raise ValueError(f'{src}: root is <{root.tag}>, not <vector>')
    vw = root.get(A + 'viewportWidth')
    vh = root.get(A + 'viewportHeight')
    w, h = num(root.get(A + 'width'), 24), num(root.get(A + 'height'), 24)
    defs = []
    body = walk(root, defs)
    alpha = num(root.get(A + 'alpha'), 1.0)
    if alpha < 1:
        body = f'<g opacity="{alpha}">{body}</g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {vw} {vh}"><defs>{"".join(defs)}</defs>{body}</svg>')


if __name__ == '__main__':
    svg = convert(sys.argv[1])
    if len(sys.argv) > 2:
        open(sys.argv[2], 'w').write(svg)
    else:
        print(svg)
