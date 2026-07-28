#!/usr/bin/env python3
"""Tide 6 art: Mourning Bloom (cross-plant, drooping head), Void Petal, Draught of
Return (honey-bottle silhouette, warm pixels swapped teal), and the Return effect
icon (18x18). Hand maps use the enderium ramp; the bottle swap cribs vanilla.

Usage: python3 scripts/gen_bloom_art.py <vanilla_client_jar> <resources_dir> [preview_png]
"""
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image

TONES = {
    'k': (4, 44, 37, 255),       # deepest teal
    'd': (11, 95, 78, 255),      # dark teal (stem shadow)
    's': (20, 143, 119, 255),    # stem
    'm': (23, 161, 132, 255),    # mid teal
    'l': (69, 214, 178, 255),    # light teal fringe
    'S': (201, 247, 232, 255),   # sparkle
    'p': (138, 92, 245, 255),    # ender purple
    'P': (74, 34, 122, 255),     # deep purple (head body)
    'B': (26, 16, 40, 255),      # near-black purple (head shadow)
    '.': None,
}

BLOOM = [
    "................",
    "......BBB.......",
    ".....BPPPB......",
    "....BPPpPPB.....",
    "....BPpSpPB.....",
    ".....BPPPB..l...",
    "......dBB..lm...",
    "......ds...ml...",
    ".......ds.ms....",
    "........dss.....",
    "....l...ds......",
    "...ml..ds.......",
    "....mssd........",
    "......ds........",
    "......ds........",
    ".....dss........",
]

PETAL = [
    "................",
    "................",
    "......ll........",
    ".....lmml.......",
    "....lmmmml......",
    "....mmpmml......",
    "....smmmms......",
    ".....smms.......",
    "......ss........",
    ".......s........",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]

ICON = [
    "..................",
    "......kkkkkk......",
    "....kkmmmmmmkk....",
    "...kmml....lmmk...",
    "..kml..'..'..lmk..",
    "..km..........mk..",
    ".kml....SS....lmk.",
    ".km....Spps....mk.",
    ".km....SppS....mk.",
    ".km.....pp.....mk.",
    ".km.....pp.....mk.",
    ".kml....pp....lmk.",
    "..km....pp.....mk.",
    "..kml..plllp.lmk..",
    "...kmml.ll.lmmk...",
    "....kkmmmmmmkk....",
    "......kkkkkk......",
    "..................",
]


def paint(rows, size):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            c = TONES.get(ch)
            if c:
                px[x, y] = c
    return img


def is_warm(c):
    r, g, b = c
    return r > 105 and r >= g and g >= b and r - b > 30


def rank_swap_warm(img):
    targets = ['#0b5f4e', '#148f77', '#17a184', '#2fc39d', '#45d6b2', '#c9f7e8']
    def hx(sv):
        return tuple(int(sv[i:i + 2], 16) for i in (1, 3, 5))
    def lum(c):
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    img = img.convert('RGBA')
    colors = sorted({img.getpixel((x, y))[:3]
                     for y in range(img.height) for x in range(img.width)
                     if img.getpixel((x, y))[3] > 0 and is_warm(img.getpixel((x, y))[:3])}, key=lum)
    table = {c: hx(targets[min(int(i * len(targets) / len(colors)), len(targets) - 1)])
             for i, c in enumerate(colors)}
    out = img.copy()
    px, po = img.load(), out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0 and (r, g, b) in table:
                po[x, y] = (*table[(r, g, b)], a)
    return out


def main():
    jar, resources = sys.argv[1], Path(sys.argv[2])
    preview = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    tex = resources / 'assets' / 'endrise' / 'textures'
    (tex / 'block').mkdir(parents=True, exist_ok=True)
    (tex / 'item').mkdir(parents=True, exist_ok=True)
    (tex / 'mob_effect').mkdir(parents=True, exist_ok=True)

    bloom = paint(BLOOM, 16)
    petal = paint(PETAL, 16)
    icon = paint(ICON, 18)
    with zipfile.ZipFile(jar) as z:
        honey = Image.open(BytesIO(z.read('assets/minecraft/textures/item/honey_bottle.png'))).convert('RGBA')
    draught = rank_swap_warm(honey)

    bloom.save(tex / 'block' / 'mourning_bloom.png')
    petal.save(tex / 'item' / 'void_petal.png')
    draught.save(tex / 'item' / 'draught_of_return.png')
    icon.save(tex / 'mob_effect' / 'return.png')
    print('wrote bloom, petal, draught, effect icon')

    if preview:
        strip = Image.new('RGBA', (4 * 20 + 2, 22), (24, 24, 32, 255))
        for i, im in enumerate([bloom, petal, draught, icon]):
            strip.alpha_composite(im, (2 + i * 20, 2))
        strip.resize((strip.width * 8, strip.height * 8), Image.NEAREST).save(preview)
        print('preview at', preview)


if __name__ == '__main__':
    main()
