#!/usr/bin/env python3
"""Generate Sleep Timer launcher icons (legacy + adaptive) and notification glyph.

Renders the same moon + clock-ring + timer-hand design as the web prototype,
entirely with PIL so no external assets are needed.
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'res')

ACCENT = (0, 230, 118)
WHITE = (255, 255, 255)
MOON_LIGHT = (221, 227, 234)
MOON_DARK = (154, 163, 173)

# density name -> scale factor (relative to mdpi=1)
DENSITIES = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0, 'xxxhdpi': 4.0}
LEGACY_DP = 48      # full-bleed launcher icon size (dp)
FORE_DP = 108       # adaptive foreground canvas (dp)
NOTIF_DP = 24       # notification small icon (dp)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def new_canvas(size):
    return Image.new('RGBA', (size, size), (0, 0, 0, 0))


def rounded_mask(size, radius):
    m = Image.new('L', (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def draw_glyph(img, s, glow=False):
    """Draw ring + ticks + crescent moon + timer hand onto an RGBA image."""
    size = img.width
    d = ImageDraw.Draw(img)
    cx = cy = 256 * s

    if glow:
        layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        gd = ImageDraw.Draw(layer)
        for i in range(1, 41):
            r = (240 * s) * (i / 40.0)
            a = int(70 * (1 - i / 40.0))
            gd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(0, 230, 118, a))
        img = Image.alpha_composite(img, layer)
        d = ImageDraw.Draw(img)

    # clock ring
    ring_r = 152 * s
    ring_w = 15 * s
    d.ellipse([cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r],
              outline=WHITE + (255,), width=int(ring_w))

    # ticks at 12/3/6/9
    tick_w = int(4 * s)
    for deg in (0, 90, 180, 270):
        a = math.radians(deg)
        r1 = ring_r - ring_w / 2 - 7 * s
        r2 = ring_r - ring_w / 2 + 7 * s
        d.line([cx + math.cos(a) * r1, cy + math.sin(a) * r1,
                cx + math.cos(a) * r2, cy + math.sin(a) * r2],
               fill=WHITE + (115,), width=tick_w)

    # crescent moon (alpha-masked)
    moon_r = 82 * s
    mcx = cx - 6 * s
    mcy = cy + 12 * s
    cutx = mcx + 46 * s
    cuty = mcy - 26 * s
    mask = Image.new('L', (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([mcx - moon_r, mcy - moon_r, mcx + moon_r, mcy + moon_r], fill=255)
    md.ellipse([cutx - moon_r, cuty - moon_r, cutx + moon_r, cuty + moon_r], fill=0)
    fill = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fill)
    for y in range(size):
        t = y / (size - 1)
        fd.line([(0, y), (size, y)], fill=lerp(MOON_LIGHT, MOON_DARK, t) + (255,))
    moon_img = fill.copy()
    moon_img.putalpha(mask)
    img = Image.alpha_composite(img, moon_img)
    d = ImageDraw.Draw(img)

    # timer hand pointing at 12 o'clock
    d.line([cx, cy + 10 * s, cx, cy - (ring_r - ring_w - 6 * s)],
           fill=ACCENT + (255,), width=int(15 * s))
    d.ellipse([cx - 10 * s, cy - 10 * s, cx + 10 * s, cy + 10 * s], fill=ACCENT + (255,))
    return img


def compose_legacy(size):
    """Full launcher tile: gradient rounded square + glyph."""
    s = size / 512.0
    bg = new_canvas(size)
    bd = ImageDraw.Draw(bg)
    top, bot = (0x23, 0x23, 0x23), (0x0c, 0x0c, 0x0c)
    for y in range(size):
        t = y / (size - 1)
        bd.line([(0, y), (size, y)], fill=lerp(top, bot, t) + (255,))
    img = draw_glyph(bg, s, glow=True)
    img.putalpha(rounded_mask(size, int(118 * s)))
    return img


def compose_foreground(size):
    """Adaptive foreground: glyph only, transparent background."""
    s = size / 512.0
    return draw_glyph(new_canvas(size), s, glow=False)


def compose_notification(size):
    """Notification small icon: white ring + hand, transparent bg."""
    s = size / 512.0
    img = new_canvas(size)
    d = ImageDraw.Draw(img)
    cx = cy = 256 * s
    ring_r, ring_w = 152 * s, 15 * s
    d.ellipse([cx - ring_r, cy - ring_r, cx + ring_r, cy + ring_r],
              outline=(255, 255, 255, 255), width=int(ring_w))
    d.line([cx, cy + 10 * s, cx, cy - (ring_r - ring_w - 6 * s)],
           fill=(255, 255, 255, 255), width=int(15 * s))
    d.ellipse([cx - 10 * s, cy - 10 * s, cx + 10 * s, cy + 10 * s], fill=(255, 255, 255, 255))
    return img


def save_png(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, 'PNG')
    print('wrote', path)


def main():
    for name, scale in DENSITIES.items():
        px = int(LEGACY_DP * scale)
        save_png(compose_legacy(px), os.path.join(RES, 'mipmap-' + name, 'ic_launcher.png'))

        fpx = int(FORE_DP * scale)
        save_png(compose_foreground(fpx),
                 os.path.join(RES, 'mipmap-' + name, 'ic_launcher_foreground.png'))

        npx = int(NOTIF_DP * scale)
        save_png(compose_notification(npx),
                 os.path.join(RES, 'drawable-' + name, 'ic_stat_timer.png'))

    adaptive = os.path.join(RES, 'mipmap-anydpi-v26', 'ic_launcher.xml')
    os.makedirs(os.path.dirname(adaptive), exist_ok=True)
    with open(adaptive, 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    print('wrote', adaptive)


if __name__ == '__main__':
    main()
