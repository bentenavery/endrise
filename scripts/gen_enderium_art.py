#!/usr/bin/env python3
"""Generate the enderium gear art set from vanilla bases (indexed palette swap).

Vanilla precedent: netherite's tool/armor icons are pixel-identical silhouettes of
diamond's with a different ramp. Enderium follows suit: tools recolor from DIAMOND
bases (crystalline banding maps cleanly; a two-stop value skew darkens the mids so
they escape diamond at a glance), armor + ingot recolor from NETHERITE bases (the
forged-plate banding reads as dark End-metal), raw from raw_gold. Purple #8a5cf5
accents are the signature: teal body + purple core, the enderman colorway.

Usage: python3 scripts/gen_enderium_art.py <vanilla_client_jar> <resources_dir> [--armor-layout 26x|1211]
  26x  -> textures/entity/equipment/humanoid[_leggings]/enderium.png
  1211 -> textures/models/armor/enderium_layer_1/2.png
Every opaque source pixel must hit the color table or the script fails loudly
(guards against vanilla palette drift between MC versions).
"""
import sys
import zipfile
from io import BytesIO
from pathlib import Path

from PIL import Image

RAMP = ['#042c25', '#063d33', '#0b5f4e', '#0e6b5c', '#148f77', '#17a184', '#2fc39d', '#45d6b2', '#c9f7e8']
PURPLE = '#8a5cf5'

def hx(s):
    return tuple(int(s[i:i + 2], 16) for i in (1, 3, 5))

# Tools from diamond bases: two-stop value skew (naive rank map still reads diamond)
TOOL_MAP = {
    '#082520': '#042c25', '#0e3f36': '#063d33', '#156355': '#0b5f4e', '#1e8a77': '#0e6b5c',
    '#27b29a': '#0e6b5c', '#2bc7ac': '#148f77', '#33ebcb': '#2fc39d', '#a4fdf0': '#45d6b2',
    # handle browns pass through (sticks stay wood across every vanilla tier)
    '#281e0b': '#281e0b', '#493615': '#493615', '#684e1e': '#684e1e', '#896727': '#896727',
}
# Armor icons + ingot from netherite bases: rank-bucketed grays -> ramp
ARMOR_MAP = {
    '#171111': '#042c25', '#271c1d': '#063d33', '#241f20': '#063d33', '#31292a': '#063d33',
    '#312828': '#0b5f4e', '#352d2d': '#0b5f4e', '#3c3232': '#0b5f4e', '#3b393b': '#0b5f4e',
    '#373537': '#0e6b5c', '#3f303b': '#0e6b5c', '#4c4143': '#0e6b5c', '#484548': '#0e6b5c',
    '#473e3f': '#148f77', '#49393f': '#148f77', '#4d494d': '#148f77', '#51444e': '#17a184',
    '#5a575a': '#17a184', '#5d565d': '#17a184', '#111111': '#042c25',
    '#766a76': '#2fc39d', '#737173': '#2fc39d',
    '#0a0a0a': '#042c25', '#241919': '#063d33', '#322727': '#0b5f4e',
}
# Raw enderium from raw_gold: gold ramp rank-mapped, ordered dark->light
RAW_GOLD_ORDER_TARGETS = ['#063d33', '#0b5f4e', '#0e6b5c', '#148f77', '#17a184', '#2fc39d', '#45d6b2']

# accent placements (x, y, color) per finished sprite, from the art audit POCs
ACCENTS = {
    'enderium_sword': [(6, 9, PURPLE), (4, 10, '#c9f7e8')],          # guard gem + edge glint
    'enderium_helmet': [(7, 6, PURPLE), (8, 6, PURPLE)],             # brow gem
    'enderium_chestplate': [(7, 5, PURPLE)],                          # clasp
    'enderium_pickaxe': [(11, 4, '#c9f7e8')],                         # head glint only (audit: purple reads badly here)
    'enderium_axe': [(9, 3, '#c9f7e8')],
    'enderium_shovel': [(11, 4, '#c9f7e8')],
    'enderium_hoe': [(10, 3, '#c9f7e8')],
    'enderium_boots': [],
    'enderium_leggings': [],
    'enderium_ingot': [(6, 6, '#c9f7e8'), (7, 6, '#c9f7e8')],         # top-face streak
    'raw_enderium': [(7, 7, PURPLE), (5, 4, '#c9f7e8')],              # signature pair
}


def recolor(img, table, label):
    img = img.convert('RGBA')
    out = img.copy()
    px, po = img.load(), out.load()
    missed = set()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            key = f'#{r:02x}{g:02x}{b:02x}'
            if key in table:
                po[x, y] = (*hx(table[key]), a)
            else:
                missed.add(key)
    if missed:
        raise SystemExit(f'{label}: unmapped source colors {sorted(missed)} — vanilla palette drifted, extend the table')
    return out


def rank_map(img, targets, label):
    """Map an image's opaque colors (ranked by luminance) onto target ramp buckets."""
    img = img.convert('RGBA')
    colors = sorted({img.getpixel((x, y))[:3] for y in range(img.height) for x in range(img.width)
                     if img.getpixel((x, y))[3] > 0}, key=lambda c: 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2])
    table = {}
    for i, c in enumerate(colors):
        t = targets[min(i * len(targets) // max(len(colors), 1), len(targets) - 1)]
        table[f'#{c[0]:02x}{c[1]:02x}{c[2]:02x}'] = t
    return recolor(img, table, label)


def apply_accents(img, name):
    px = img.load()
    for x, y, color in ACCENTS.get(name, []):
        if px[x, y][3] > 0:
            px[x, y] = (*hx(color), 255)
    return img


def main():
    jar_path, res_dir = Path(sys.argv[1]), Path(sys.argv[2])
    layout = sys.argv[4] if len(sys.argv) > 4 and sys.argv[3] == '--armor-layout' else (
        sys.argv[sys.argv.index('--armor-layout') + 1] if '--armor-layout' in sys.argv else '26x')
    jar = zipfile.ZipFile(jar_path)

    def load(entry):
        return Image.open(BytesIO(jar.read(entry))).convert('RGBA')

    def save(img, rel):
        p = res_dir / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        img.save(p)
        print('wrote', rel)

    item_tex = 'assets/minecraft/textures/item/'
    # tools from diamond
    for tool in ('sword', 'pickaxe', 'axe', 'shovel', 'hoe'):
        img = recolor(load(f'{item_tex}diamond_{tool}.png'), TOOL_MAP, f'diamond_{tool}')
        save(apply_accents(img, f'enderium_{tool}'), f'assets/endrise/textures/item/enderium_{tool}.png')
    # armor icons + ingot from netherite
    for piece in ('helmet', 'chestplate', 'leggings', 'boots'):
        img = recolor(load(f'{item_tex}netherite_{piece}.png'), ARMOR_MAP, f'netherite_{piece}')
        save(apply_accents(img, f'enderium_{piece}'), f'assets/endrise/textures/item/enderium_{piece}.png')
    ingot = recolor(load(f'{item_tex}netherite_ingot.png'), ARMOR_MAP, 'netherite_ingot')
    save(apply_accents(ingot, 'enderium_ingot'), 'assets/endrise/textures/item/enderium_ingot.png')
    raw = rank_map(load(f'{item_tex}raw_gold.png'), RAW_GOLD_ORDER_TARGETS, 'raw_gold')
    save(apply_accents(raw, 'raw_enderium'), 'assets/endrise/textures/item/raw_enderium.png')

    # worn armor layers, per-branch layout, rank-bucketed from that branch's own netherite
    worn_targets = RAMP[:-1]  # keep the near-white glint out of broad surfaces
    if layout == '26x':
        for kind in ('humanoid', 'humanoid_leggings'):
            img = rank_map(load(f'assets/minecraft/textures/entity/equipment/{kind}/netherite.png'),
                           worn_targets, f'worn {kind}')
            save(img, f'assets/endrise/textures/entity/equipment/{kind}/enderium.png')
    else:
        for n in ('1', '2'):
            img = rank_map(load(f'assets/minecraft/textures/models/armor/netherite_layer_{n}.png'),
                           worn_targets, f'worn layer_{n}')
            save(img, f'assets/endrise/textures/models/armor/enderium_layer_{n}.png')

    print('done')


if __name__ == '__main__':
    main()
