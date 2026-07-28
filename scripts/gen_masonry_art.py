#!/usr/bin/env python3
"""Tide 5 masonry art. Three palette swaps from vanilla bases (enderium_block from
netherite_block, raw_enderium_block from raw_gold_block, enderium_lantern flame-swap
from lantern) and three new faces derived from the version's own end_stone palette
(polished, tiles, chiseled + Mourner's Relief), so the family tracks vanilla drift.

Usage: python3 scripts/gen_masonry_art.py <vanilla_client_jar> <resources_dir> [preview_png]
"""
import sys
import zipfile
from collections import Counter
from io import BytesIO
from pathlib import Path

from PIL import Image

RAMP = ['#042c25', '#063d33', '#0b5f4e', '#0e6b5c', '#148f77', '#17a184', '#2fc39d', '#45d6b2', '#c9f7e8']
FLAME_TARGETS = ['#0e6b5c', '#148f77', '#17a184', '#2fc39d', '#45d6b2', '#c9f7e8']
TEAL_INLAY = '#2fc39d'


def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))


def lum(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def load(jar, path):
    with zipfile.ZipFile(jar) as z:
        return Image.open(BytesIO(z.read(path))).convert('RGBA')


def rank_swap(img, targets, select=lambda c: True):
    """Map the selected colors, ordered dark->light, onto the target ramp."""
    img = img.convert('RGBA')
    colors = sorted({img.getpixel((x, y))[:3]
                     for y in range(img.height) for x in range(img.width)
                     if img.getpixel((x, y))[3] > 0 and select(img.getpixel((x, y))[:3])}, key=lum)
    if not colors:
        raise SystemExit('rank_swap: selector matched nothing')
    table = {}
    for i, c in enumerate(colors):
        table[c] = hx(targets[min(int(i * len(targets) / len(colors)), len(targets) - 1)])
    out = img.copy()
    px, po = img.load(), out.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0 and (r, g, b) in table:
                po[x, y] = (*table[(r, g, b)], a)
    return out


def end_stone_tones(end_stone):
    """The 4 dominant end stone tones, dark -> light, sampled live."""
    counts = Counter(end_stone.getpixel((x, y))[:3]
                     for y in range(16) for x in range(16))
    dominant = [c for c, _ in counts.most_common(6)]
    dominant.sort(key=lum)
    dark = dominant[0]
    mid_dark = dominant[1]
    mid = dominant[len(dominant) // 2]
    light = dominant[-1]
    return dark, mid_dark, mid, light


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def make_polished(end_stone):
    dark, mid_dark, mid, light = end_stone_tones(end_stone)
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    src = end_stone.load()
    for y in range(16):
        for x in range(16):
            c = src[x, y][:3]
            # flatten: crush the dark speck clusters into the two mid tones
            flat = mid if lum(c) < lum(mid) else light
            if y == 0 or x == 0:
                flat = shade(flat, 1.08)
            if y == 15 or x == 15:
                flat = shade(flat, 0.88)
            px[x, y] = (*flat, 255)
    return img


def make_tiles(polished):
    dark_grout = shade(end_stone_tones_cache[1], 0.72)
    img = polished.copy()
    px = img.load()
    for y in range(16):
        for x in range(16):
            if x % 8 == 0 or y % 8 == 0:
                px[x, y] = (*dark_grout, 255)
            elif y % 8 == 1 and x % 8 != 0:
                px[x, y] = (*shade(px[x, y][:3], 1.06), 255)
    return img


RELIEF = [
    "BBBBBBBBBBBBBBBB",
    "BLLLLLLLLLLLLLdB",
    "BLFFFFFFFFFFFFdB",
    "BLFFFFFFFFFFFFdB",
    "BLFFFDDFFFFFFFdB",
    "BLFFDDDFFFFFFFdB",
    "BLFFDDDDDFFFFFdB",
    "BLFFDDDDDDDtFFdB",
    "BLFFDDDDDDDDFFdB",
    "BLFFDDDDDDDDFFdB",
    "BLFFDDDDDDDDFFdB",
    "BLFDDDDDDDDDDFdB",
    "BLFddddddddddFdB",
    "BLFFFFFFFFFFFFdB",
    "BddddddddddddddB",
    "BBBBBBBBBBBBBBBB",
]


def make_chiseled(end_stone):
    dark, mid_dark, mid, light = end_stone_tones(end_stone)
    tones = {
        'B': mid,
        'L': shade(light, 1.05),
        'F': shade(mid, 0.94),
        'D': shade(mid_dark, 0.70),
        'd': shade(mid_dark, 0.82),
        't': hx(TEAL_INLAY),
    }
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y, row in enumerate(RELIEF):
        for x, ch in enumerate(row):
            px[x, y] = (*tones[ch], 255)
    return img


def is_flame(c):
    r, g, b = c
    return r > 105 and r >= g and g > b and r - b > 35


def main():
    jar, resources = sys.argv[1], Path(sys.argv[2])
    preview = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    blocks = resources / 'assets' / 'endrise' / 'textures' / 'block'
    blocks.mkdir(parents=True, exist_ok=True)

    end_stone = load(jar, 'assets/minecraft/textures/block/end_stone.png').crop((0, 0, 16, 16))
    global end_stone_tones_cache
    end_stone_tones_cache = end_stone_tones(end_stone)

    out = {}
    out['enderium_block'] = rank_swap(load(jar, 'assets/minecraft/textures/block/netherite_block.png'), RAMP[:5])
    out['raw_enderium_block'] = rank_swap(load(jar, 'assets/minecraft/textures/block/raw_gold_block.png'), RAMP[1:6])
    out['enderium_lantern'] = rank_swap(load(jar, 'assets/minecraft/textures/block/lantern.png'),
                                        FLAME_TARGETS, select=is_flame)
    out['polished_end_stone'] = make_polished(end_stone)
    out['end_stone_tiles'] = make_tiles(out['polished_end_stone'])
    out['chiseled_end_stone_tiles'] = make_chiseled(end_stone)

    for name, img in out.items():
        img.save(blocks / f'{name}.png')
    print('wrote', len(out), 'block textures to', blocks)

    if preview:
        names = ['polished_end_stone', 'end_stone_tiles', 'chiseled_end_stone_tiles',
                 'enderium_block', 'raw_enderium_block', 'enderium_lantern']
        strip = Image.new('RGBA', (len(names) * 18 + 2, 20), (24, 24, 32, 255))
        for i, n in enumerate(names):
            strip.alpha_composite(out[n].crop((0, 0, 16, 16)), (2 + i * 18, 2))
        strip.resize((strip.width * 8, strip.height * 8), Image.NEAREST).save(preview)
        print('preview at', preview)


if __name__ == '__main__':
    main()
