#!/usr/bin/env python3
"""Tide 7 cenotaph generator: four ruin templates plus all their wiring.

The templates are authored as layered voxel maps and written as vanilla
structure NBT at DataVersion 3955 (1.21.1). That version is the floor of what
we support and DataFixerUpper only upgrades old NBT forward, so these exact
files ship on BOTH branches; never re-author them on a newer branch. The
worldgen/loot/advancement JSON is emitted per branch like gen_block_data.py.

Every variant holds the covenant's furniture: exactly one loot chest, exactly
one enderium block core, at least one Mourner's Relief, a burning lantern, and
mourning blooms rooted on end stone. A validation pass reloads each emitted
file and refuses to ship a template that breaks those rules.

Usage: python3 scripts/gen_cenotaph_templates.py <resources_dir> --branch 26x|1211
       (needs nbtlib; run with the scratchpad venv python)
"""
import json
import sys
from pathlib import Path

import nbtlib
from nbtlib import Compound, Int, String

RES = Path(sys.argv[1])
BRANCH = sys.argv[sys.argv.index('--branch') + 1]
NS = 'endrise'
DATA_VERSION = 3955  # 1.21.1: the authoring floor, DFU upgrades forward
LOOT_TABLE = f'{NS}:chests/cenotaph'
SALT = 20260728

# ---- shared block legend ---------------------------------------------------
# Only blocks that exist identically on both branches may appear here.
LEGEND = {
    'E': ('minecraft:end_stone', {}),
    'B': ('minecraft:end_stone_bricks', {}),
    'p': ('minecraft:purpur_block', {}),
    'I': ('minecraft:purpur_pillar', {'axis': 'y'}),
    'q': ('minecraft:purpur_slab', {'type': 'bottom', 'waterlogged': 'false'}),
    'P': (f'{NS}:polished_end_stone', {}),
    'T': (f'{NS}:end_stone_tiles', {}),
    'C': (f'{NS}:chiseled_end_stone_tiles', {}),
    'N': (f'{NS}:enderium_block', {}),
    'L': (f'{NS}:enderium_lantern', {'hanging': 'false', 'waterlogged': 'false'}),
    'l': (f'{NS}:enderium_lantern', {'hanging': 'true', 'waterlogged': 'false'}),
    'm': (f'{NS}:mourning_bloom', {}),
    's': (f'{NS}:end_stone_tile_slab', {'type': 'bottom', 'waterlogged': 'false'}),
    'S': (f'{NS}:polished_end_stone_slab', {'type': 'bottom', 'waterlogged': 'false'}),
    '1': (f'{NS}:polished_end_stone_stairs',
          {'facing': 'north', 'half': 'bottom', 'shape': 'straight', 'waterlogged': 'false'}),
    '2': (f'{NS}:polished_end_stone_stairs',
          {'facing': 'south', 'half': 'bottom', 'shape': 'straight', 'waterlogged': 'false'}),
    '7': (f'{NS}:polished_end_stone_stairs',
          {'facing': 'west', 'half': 'top', 'shape': 'straight', 'waterlogged': 'false'}),
    '_': ('minecraft:air', {}),
    # 'K' (chest) is resolved per variant for facing; '.' means keep terrain.
}

ALLOWED = {name for name, _ in LEGEND.values()} | {'minecraft:chest'}

# ---- the four cenotaphs ----------------------------------------------------
# Maps are y-layers bottom-up; each layer is rows z0(north)..zN, chars x0(west)..xN.
# y0 doubles as foundation and floor; it lands at the WORLD_SURFACE heightmap.

VIGIL = {  # 9x9: a walled grave plot, arch and lantern over the headstone
    'name': 'vigil', 'chest_facing': 'west',
    'layers': [
        ['.EEEEEEE.',
         'EPPPPPPPE',
         'EPTTNTEPE',
         'EPTTTTTPE',
         'EPTTTTTPE',
         'EPTTETTPE',
         'EPETTTTPE',
         'EPPPPPPPE',
         '.EEEEEEE.'],
        ['_________',
         '_PP____P_',
         '_P_ICIm__',
         '__2_s____',
         '____sK___',
         '____m_S__',
         '_Pm____P_',
         '_P____PP_',
         '_________'],
        ['_________',
         '_P_______',
         '___IlI___',
         '_________',
         '_________',
         '_________',
         '_________',
         '_______P_',
         '_________'],
        ['_________',
         '_________',
         '___ppp___',
         '_________',
         '_________',
         '_________',
         '_________',
         '_________',
         '_________'],
    ],
}

WAYGATE = {  # 10x10: an arch over a tiled causeway, core as lantern pedestal
    'name': 'waygate', 'chest_facing': 'east',
    'layers': [
        ['.EEETTEEE.',
         'EEEETTEEEE',
         'EEEETTEEEE',
         'EEPPTTPEEE',
         'EEPPTTPPEE',
         'EEPPTTPPEE',
         'EEPPTTPPEE',
         'EPPPTTPPEE',
         'EPPPTTEEEE',
         '.EEETTEEE.'],
        ['__________',
         '__________',
         '__m_______',
         '_______m__',
         '___I__I___',
         '__1_______',
         '____ss__m_',
         '_K_____N__',
         '_PPP______',
         '__________'],
        ['__________',
         '__________',
         '__________',
         '__________',
         '___I__I___',
         '__________',
         '__________',
         '_______L__',
         '_PCP______',
         '__________'],
        ['__________',
         '__________',
         '__________',
         '__________',
         '___Il_I___',
         '__________',
         '__________',
         '__________',
         '_S_S______',
         '__________'],
        ['__________',
         '__________',
         '__________',
         '__________',
         '___pppp___',
         '__________',
         '__________',
         '__________',
         '__________',
         '__________'],
    ],
}

RING = {  # 11x11: broken pillar circle around an enderium-relief-lantern cairn
    'name': 'ring', 'chest_facing': 'south',
    'layers': [
        ['.EEEEEEEEE.',
         'EEEEEPEEEEE',
         'EEPEETEEPEE',
         'EEEEETEEEEE',
         'EEEEPPPEEPE',
         'EPTTPPPTTPE',
         'EEEEPPPEEEE',
         'EEEEETEEEEE',
         'EEPEETEEPEE',
         'EEEEEPEEEEE',
         '.EEEEEEEEE.'],
        ['___________',
         '_____I_____',
         '__I_____I__',
         '___m_s_m___',
         '_________K_',
         '_I_s_N_s_I_',
         '___________',
         '_____s_m___',
         '__I_____I__',
         '_____I_____',
         '___________'],
        ['___________',
         '_____I_____',
         '__I________',
         '___________',
         '___________',
         '_____C___I_',
         '___________',
         '___________',
         '__I_____I__',
         '_____I_____',
         '___________'],
        ['___________',
         '_____I_____',
         '___________',
         '___________',
         '___________',
         '_____L_____',
         '___________',
         '___________',
         '__I_____I__',
         '___________',
         '___________'],
        ['___________',
         '_____q_____',
         '___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '__q_____I__',
         '___________',
         '___________'],
        ['___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '___________',
         '________q__',
         '___________',
         '___________'],
    ],
}

FALLEN_HALL = {  # 12x12: two ruined walls meeting, grave inside, chest under rubble
    'name': 'fallen_hall', 'chest_facing': 'north',
    'layers': [
        ['.EEEEEEEEEE.',
         'EEEEEEEEEEEE',
         'EETTTTTTTTTE',
         'EETTTTETTTTE',
         'EETTTTTTTTTE',
         'EETTTETTTTTE',
         'EETTTTTTETTE',
         'EETETTTTT..E',
         'EETTT.TTE..E',
         'EETTT..TT.EE',
         'EEETTTTTEEEE',
         '.EEEEEEEEEE.'],
        ['____________',
         '_NBPBBPBP___',
         '_B__________',
         '_P__ssm_____',
         '____________',
         '____________',
         '_B__________',
         '_P_m___K7___',
         '_B__S___m___',
         '___B________',
         '____________',
         '____________'],
        ['____________',
         '_BPCBP______',
         '_P__________',
         '_B__________',
         '_l__________',
         '____________',
         '_P__________',
         '_______S____',
         '____________',
         '____________',
         '____________',
         '____________'],
        ['____________',
         '_PB_________',
         '_B__________',
         '_P__________',
         '_B__________',
         '_B__________',
         '_S__________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________'],
        ['____________',
         '_SS_________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________',
         '____________'],
    ],
}

VARIANTS = [VIGIL, WAYGATE, RING, FALLEN_HALL]
HEADROOM = 2  # extra clear-air layers above the tallest built layer


def int_list(values):
    return nbtlib.List[Int]([Int(v) for v in values])


def build_template(variant):
    layers = variant['layers']
    depth = len(layers[0])
    width = len(layers[0][0])
    legend = dict(LEGEND)
    legend['K'] = ('minecraft:chest',
                   {'facing': variant['chest_facing'], 'type': 'single', 'waterlogged': 'false'})

    for y, layer in enumerate(layers):
        assert len(layer) == depth, f"{variant['name']} y{y}: {len(layer)} rows, want {depth}"
        for z, row in enumerate(layer):
            assert len(row) == width, f"{variant['name']} y{y} z{z}: {len(row)} cols, want {width}"

    full_air = ['_' * width] * depth
    layers = layers + [full_air] * HEADROOM

    palette, palette_index, blocks = [], {}, []
    for y, layer in enumerate(layers):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                if ch == '.':
                    continue
                if ch not in legend:
                    raise SystemExit(f"{variant['name']}: unknown map char {ch!r}")
                name, props = legend[ch]
                key = (name, tuple(sorted(props.items())))
                if key not in palette_index:
                    state = Compound({'Name': String(name)})
                    if props:
                        state['Properties'] = Compound({k: String(v) for k, v in props.items()})
                    palette_index[key] = len(palette)
                    palette.append(state)
                block = Compound({'pos': int_list([x, y, z]), 'state': Int(palette_index[key])})
                if ch == 'K':
                    block['nbt'] = Compound({'id': String('minecraft:chest'),
                                             'LootTable': String(LOOT_TABLE)})
                blocks.append(block)

    return nbtlib.File({
        'size': int_list([width, len(layers), depth]),
        'entities': nbtlib.List([]),
        'blocks': nbtlib.List[Compound](blocks),
        'palette': nbtlib.List[Compound](palette),
        'DataVersion': Int(DATA_VERSION),
    })


def validate(path, variant):
    """Reload the emitted file and enforce the covenant's furniture rules."""
    root = nbtlib.load(path)
    palette = [str(p['Name']) for p in root['palette']]
    unknown = [n for n in palette if n not in ALLOWED]
    assert not unknown, f'{path.name}: blocks outside the both-branch whitelist: {unknown}'

    by_pos, counts = {}, {}
    for b in root['blocks']:
        name = palette[int(b['state'])]
        pos = tuple(int(v) for v in b['pos'])
        by_pos[pos] = name
        counts[name] = counts.get(name, 0) + 1
        if name == 'minecraft:chest':
            assert str(b['nbt']['LootTable']) == LOOT_TABLE, f'{path.name}: chest loot table wrong'

    def below(pos):
        return by_pos.get((pos[0], pos[1] - 1, pos[2]), 'void')

    def above(pos):
        return by_pos.get((pos[0], pos[1] + 1, pos[2]), 'void')

    assert counts.get('minecraft:chest') == 1, f'{path.name}: want exactly 1 chest'
    assert counts.get(f'{NS}:enderium_block') == 1, f'{path.name}: want exactly 1 enderium core'
    assert counts.get(f'{NS}:chiseled_end_stone_tiles', 0) >= 1, f'{path.name}: relief missing'
    assert counts.get(f'{NS}:enderium_lantern', 0) >= 1, f'{path.name}: lantern missing'
    blooms = [p for p, n in by_pos.items() if n == f'{NS}:mourning_bloom']
    assert len(blooms) >= 2, f'{path.name}: want >=2 blooms, got {len(blooms)}'
    for p in blooms:
        assert below(p) == 'minecraft:end_stone', f'{path.name}: bloom at {p} not on end stone'
    for b in root['blocks']:
        if palette[int(b['state'])] != f'{NS}:enderium_lantern':
            continue
        pos = tuple(int(v) for v in b['pos'])
        props = root['palette'][int(b['state'])].get('Properties', {})
        hanging = str(props.get('hanging', 'false')) == 'true'
        neighbor = above(pos) if hanging else below(pos)
        assert neighbor not in ('void', 'minecraft:air'), \
            f'{path.name}: lantern at {pos} has no {"ceiling" if hanging else "floor"}'
    size = [int(v) for v in root['size']]
    assert size[0] <= 12 and size[2] <= 12, f'{path.name}: footprint {size} over 12x12'
    return counts


# ---- lost-traveler gear: 12 names, distinct pairings, one made it far ------
GEAR = [
    ("Marren's Pick", 'minecraft:iron_pickaxe'),
    ("What Isla Carried", 'minecraft:iron_sword'),
    ("Tovan's Last Light", 'minecraft:iron_helmet'),
    ("The Cartographer's Boots", 'minecraft:iron_boots'),
    ("Sable's Second Spade", 'minecraft:iron_shovel'),
    ("Ninth Expedition, Standard Issue", 'minecraft:iron_axe'),
    ("Beruna's Edge", 'minecraft:diamond_sword'),
    ("Old Wren's Mattock", 'minecraft:diamond_pickaxe'),
    ("Halvar's Burden", 'minecraft:diamond_chestplate'),
    ("The Long Walk", 'minecraft:diamond_boots'),
    ("Corvo's Patience", 'minecraft:diamond_hoe'),
    ("Ferren's Folly", f'{NS}:enderium_pickaxe'),  # the 8%: someone made it that far
]


def emit(path, obj):
    f = RES / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(obj, indent=2) + '\n')


def gear_entry(display_name, item):
    return {
        'type': 'minecraft:item',
        'name': item,
        'weight': 1,
        'functions': [
            {'function': 'minecraft:set_damage', 'add': False,
             'damage': {'type': 'minecraft:uniform', 'min': 0.3, 'max': 0.7}},
            {'function': 'minecraft:set_name', 'target': 'custom_name',
             'name': {'text': display_name}},
            {'function': 'minecraft:set_enchantments', 'add': False,
             'enchantments': {f'{NS}:soulbound': 1},
             'conditions': [{'condition': 'minecraft:random_chance', 'chance': 0.4}]},
        ],
    }


def counted(item, lo, hi):
    return {'type': 'minecraft:item', 'name': item, 'functions': [
        {'function': 'minecraft:set_count', 'add': False,
         'count': {'type': 'minecraft:uniform', 'min': float(lo), 'max': float(hi)}}]}


def emit_json():
    emit(f'data/{NS}/worldgen/structure/cenotaph.json', {
        'type': 'minecraft:jigsaw',
        'biomes': f'#{NS}:has_structure/cenotaph',
        'max_distance_from_center': 80,
        'project_start_to_heightmap': 'WORLD_SURFACE_WG',
        'size': 1,
        'spawn_overrides': {},
        'start_height': {'absolute': 0},
        'start_pool': f'{NS}:cenotaph/start',
        'step': 'surface_structures',
        'terrain_adaptation': 'beard_thin',
        'use_expansion_hack': False,
    })

    emit(f'data/{NS}/worldgen/structure_set/cenotaphs.json', {
        'placement': {'type': 'minecraft:random_spread', 'salt': SALT,
                      'separation': 12, 'spacing': 34},
        'structures': [{'structure': f'{NS}:cenotaph', 'weight': 1}],
    })

    emit(f'data/{NS}/worldgen/template_pool/cenotaph/start.json', {
        'elements': [
            {'weight': 1, 'element': {
                'element_type': 'minecraft:single_pool_element',
                'location': f'{NS}:cenotaph/{v["name"]}',
                'processors': 'minecraft:empty',
                'projection': 'rigid'}}
            for v in VARIANTS
        ],
        'fallback': 'minecraft:empty',
    })

    emit(f'data/{NS}/tags/worldgen/biome/has_structure/cenotaph.json', {
        'values': ['minecraft:end_midlands', 'minecraft:end_highlands',
                   'minecraft:small_end_islands'],
    })

    emit(f'data/{NS}/loot_table/chests/cenotaph.json', {
        'type': 'minecraft:chest',
        'random_sequence': f'{NS}:chests/cenotaph',
        'pools': [
            {'rolls': 1.0, 'entries': [gear_entry(n, i) for n, i in GEAR]},
            {'rolls': 1.0, 'entries': [counted(f'{NS}:void_petal', 2, 4)]},
            {'rolls': 1.0, 'entries': [counted(f'{NS}:raw_enderium', 2, 5)]},
            {'rolls': 1.0, 'entries': [
                {'type': 'minecraft:item', 'weight': 15,
                 'name': f'{NS}:enderium_upgrade_smithing_template'},
                {'type': 'minecraft:empty', 'weight': 85},
            ]},
        ],
    })

    emit(f'data/{NS}/advancement/someone_was_here.json', {
        'parent': f'{NS}:there_and_back',
        'display': {
            'title': {'translate': f'advancements.{NS}.someone_was_here.title'},
            'description': {'translate': f'advancements.{NS}.someone_was_here.description'},
            'icon': {'id': f'{NS}:chiseled_end_stone_tiles'},
            'frame': 'task',
            'show_toast': True,
            'announce_to_chat': True,
        },
        'criteria': {'entered': {
            'trigger': 'minecraft:location',
            'conditions': {'player': [{
                'condition': 'minecraft:entity_properties',
                'entity': 'this',
                'predicate': {'location': {'structures': f'{NS}:cenotaph'}},
            }]},
        }},
        'requirements': [['entered']],
    })


def main():
    out_dir = RES / 'data' / NS / 'structure' / 'cenotaph'
    out_dir.mkdir(parents=True, exist_ok=True)
    for variant in VARIANTS:
        f = build_template(variant)
        path = out_dir / f"{variant['name']}.nbt"
        f.save(str(path), gzipped=True)
        counts = validate(path, variant)
        interesting = {k.split(':')[1]: v for k, v in sorted(counts.items())
                       if k != 'minecraft:air'}
        print(f"{path.name}: OK  {interesting}")
    emit_json()
    print(f'wrote 4 templates + worldgen/loot/advancement JSON for branch {BRANCH}')


if __name__ == '__main__':
    main()
