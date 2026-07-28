#!/usr/bin/env python3
"""Tide 8 pearl art: the Homeward Pearl is vanilla's ender pearl re-lit in the
covenant's colors, dark purple depths rising to the enderium teal highlight,
so it reads instantly as "ender pearl, but ours".

Usage: python3 scripts/gen_pearl_art.py <vanilla_client_jar> <resources_dir> [preview_png]
"""
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image

# dark purple depths -> enderium teal light: the reverse-portal gradient
TARGETS = ['#2c1d52', '#4b2f8a', '#6d46c4', '#0b5f4e', '#148f77',
           '#2fc39d', '#45d6b2', '#c9f7e8']


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def main():
    jar, resources = sys.argv[1], Path(sys.argv[2])
    preview = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    with zipfile.ZipFile(jar) as z:
        img = Image.open(BytesIO(z.read('assets/minecraft/textures/item/ender_pearl.png'))).convert('RGBA')

    colors = sorted({img.getpixel((x, y))[:3]
                     for y in range(img.height) for x in range(img.width)
                     if img.getpixel((x, y))[3] > 0}, key=lum)
    table = {c: hx(TARGETS[min(int(i * len(TARGETS) / len(colors)), len(TARGETS) - 1)])
             for i, c in enumerate(colors)}

    out = img.copy()
    px, po = img.load(), out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0:
                po[x, y] = (*table[(r, g, b)], a)

    dest = resources / 'assets' / 'endrise' / 'textures' / 'item'
    dest.mkdir(parents=True, exist_ok=True)
    out.save(dest / 'homeward_pearl.png')
    print('wrote', dest / 'homeward_pearl.png', f'({len(colors)} tones remapped)')

    if preview:
        strip = Image.new('RGBA', (38, 20), (24, 24, 32, 255))
        strip.alpha_composite(img.crop((0, 0, 16, 16)), (2, 2))
        strip.alpha_composite(out.crop((0, 0, 16, 16)), (20, 2))
        strip.resize((strip.width * 8, strip.height * 8), Image.NEAREST).save(preview)
        print('preview at', preview)


if __name__ == '__main__':
    main()
