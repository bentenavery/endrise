#!/usr/bin/env python3
"""Tide 5 block-family data generator: the 'datagen from shared source lists'.

One block list; every blockstate, model, item definition, loot table, recipe,
stonecutting path, recipe advancement, and tag entry is emitted by CRIBBING the
structural JSON from the target version's own vanilla client jar and rewriting
identifiers. Formats therefore cannot drift from what that version expects.

Usage: python3 scripts/gen_block_data.py <vanilla_client_jar> <resources_dir> --branch 26x|1211
"""
import json
import sys
import zipfile
from pathlib import Path

JAR, RES = sys.argv[1], Path(sys.argv[2])
BRANCH = sys.argv[sys.argv.index('--branch') + 1]
NS = 'endrise'

CUBES = ['polished_end_stone', 'end_stone_tiles', 'chiseled_end_stone_tiles',
         'enderium_block', 'raw_enderium_block']
STAIRS = {'polished_end_stone_stairs': 'polished_end_stone',
          'end_stone_tile_stairs': 'end_stone_tiles'}
SLABS = {'polished_end_stone_slab': 'polished_end_stone',
         'end_stone_tile_slab': 'end_stone_tiles'}

zf = zipfile.ZipFile(JAR)


def crib(path):
    return json.loads(zf.read(path))


def rewrite(obj, mapping):
    s = json.dumps(obj)
    for old, new in mapping.items():
        s = s.replace(old, new)
    return json.loads(s)


def emit(path, obj):
    f = RES / path
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(obj, indent=2) + '\n')


def item_def(name, model_ref):
    """Client item wiring differs per branch: items/ defs on 26x, model parent on 1211."""
    if BRANCH == '26x':
        emit(f'assets/{NS}/items/{name}.json',
             {'model': {'type': 'minecraft:model', 'model': model_ref}})
    else:
        emit(f'assets/{NS}/models/item/{name}.json', {'parent': model_ref})


counts = {'files': 0}
_orig_emit = emit
def emit(path, obj, _e=_orig_emit):  # noqa: E743 - count wrapper
    counts['files'] += 1
    _e(path, obj)


# ---- cubes -----------------------------------------------------------------
for name in CUBES:
    emit(f'assets/{NS}/blockstates/{name}.json',
         {'variants': {'': {'model': f'{NS}:block/{name}'}}})
    emit(f'assets/{NS}/models/block/{name}.json',
         {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/{name}'}})
    item_def(name, f'{NS}:block/{name}')
    emit(f'data/{NS}/loot_table/blocks/{name}.json',
         rewrite(crib('data/minecraft/loot_table/blocks/iron_block.json'),
                 {'minecraft:blocks/iron_block': f'{NS}:blocks/{name}',
                  'minecraft:iron_block': f'{NS}:{name}'}))

# ---- stairs ----------------------------------------------------------------
V_STAIR = 'polished_andesite_stairs'
for name, base in STAIRS.items():
    m = {f'minecraft:blocks/{V_STAIR}': f'{NS}:blocks/{name}',
         f'minecraft:block/{V_STAIR}': f'{NS}:block/{name}',
         f'minecraft:{V_STAIR}': f'{NS}:{name}',
         'minecraft:block/polished_andesite': f'{NS}:block/{base}'}
    emit(f'assets/{NS}/blockstates/{name}.json',
         rewrite(crib(f'assets/minecraft/blockstates/{V_STAIR}.json'), m))
    for suffix in ('', '_inner', '_outer'):
        emit(f'assets/{NS}/models/block/{name}{suffix}.json',
             rewrite(crib(f'assets/minecraft/models/block/{V_STAIR}{suffix}.json'), m))
    item_def(name, f'{NS}:block/{name}')
    emit(f'data/{NS}/loot_table/blocks/{name}.json',
         rewrite(crib(f'data/minecraft/loot_table/blocks/{V_STAIR}.json'), m))

# ---- slabs -----------------------------------------------------------------
V_SLAB = 'polished_andesite_slab'
for name, base in SLABS.items():
    m = {f'minecraft:blocks/{V_SLAB}': f'{NS}:blocks/{name}',
         f'minecraft:block/{V_SLAB}': f'{NS}:block/{name}',
         f'minecraft:{V_SLAB}': f'{NS}:{name}',
         'minecraft:block/polished_andesite': f'{NS}:block/{base}'}
    emit(f'assets/{NS}/blockstates/{name}.json',
         rewrite(crib(f'assets/minecraft/blockstates/{V_SLAB}.json'), m))
    for suffix in ('', '_top'):
        emit(f'assets/{NS}/models/block/{name}{suffix}.json',
             rewrite(crib(f'assets/minecraft/models/block/{V_SLAB}{suffix}.json'), m))
    item_def(name, f'{NS}:block/{name}')
    emit(f'data/{NS}/loot_table/blocks/{name}.json',
         rewrite(crib(f'data/minecraft/loot_table/blocks/{V_SLAB}.json'), m))

# ---- lantern ---------------------------------------------------------------
m = {'minecraft:blocks/lantern': f'{NS}:blocks/enderium_lantern',
     'minecraft:block/lantern': f'{NS}:block/enderium_lantern',
     'minecraft:lantern': f'{NS}:enderium_lantern',
     'minecraft:item/lantern': f'{NS}:item/enderium_lantern'}
emit(f'assets/{NS}/blockstates/enderium_lantern.json',
     rewrite(crib('assets/minecraft/blockstates/lantern.json'), m))
emit(f'assets/{NS}/models/block/enderium_lantern.json',
     rewrite(crib('assets/minecraft/models/block/lantern.json'), m))
emit(f'assets/{NS}/models/block/enderium_lantern_hanging.json',
     rewrite(crib('assets/minecraft/models/block/lantern_hanging.json'), m))
emit(f'assets/{NS}/models/item/enderium_lantern.json',
     rewrite(crib('assets/minecraft/models/item/lantern.json'), m))
if BRANCH == '26x':
    emit(f'assets/{NS}/items/enderium_lantern.json',
         {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/enderium_lantern'}})
emit(f'data/{NS}/loot_table/blocks/enderium_lantern.json',
     rewrite(crib('data/minecraft/loot_table/blocks/lantern.json'), m))

# ---- crafting recipes ------------------------------------------------------
ING = (lambda x: x) if BRANCH == '26x' else (lambda x: {'item': x})

V_ADV = crib('data/minecraft/advancement/recipes/building_blocks/polished_andesite.json')

def recipe_advancement(recipe, unlock_item):
    emit(f'data/{NS}/advancement/recipes/{recipe}.json',
         rewrite(V_ADV, {'minecraft:polished_andesite': f'{NS}:{recipe}',
                         'minecraft:andesite': unlock_item}))

def shaped(name, pattern, key, result, count, group=None):
    r = {'type': 'minecraft:crafting_shaped', 'category': 'building',
         'key': {k: ING(v) for k, v in key.items()}, 'pattern': pattern,
         'result': {'count': count, 'id': result}}
    if group:
        r['group'] = group
    emit(f'data/{NS}/recipe/{name}.json', r)
    recipe_advancement(name, next(iter(key.values())) if isinstance(next(iter(key.values())), str) else list(key.values())[0])

def shapeless(name, ingredients, result, count):
    emit(f'data/{NS}/recipe/{name}.json',
         {'type': 'minecraft:crafting_shapeless', 'category': 'building',
          'ingredients': [ING(i) for i in ingredients], 'result': {'count': count, 'id': result}})
    recipe_advancement(name, ingredients[0])

# polished is stonecutter-only: the 2x2 end stone grid belongs to vanilla end_stone_bricks
shaped('end_stone_tiles', ['SS', 'SS'], {'S': f'{NS}:polished_end_stone'}, f'{NS}:end_stone_tiles', 4)
shaped('chiseled_end_stone_tiles', ['S', 'S'], {'S': f'{NS}:end_stone_tile_slab'}, f'{NS}:chiseled_end_stone_tiles', 1)
shaped('polished_end_stone_stairs', ['S  ', 'SS ', 'SSS'], {'S': f'{NS}:polished_end_stone'}, f'{NS}:polished_end_stone_stairs', 4)
shaped('end_stone_tile_stairs', ['S  ', 'SS ', 'SSS'], {'S': f'{NS}:end_stone_tiles'}, f'{NS}:end_stone_tile_stairs', 4)
shaped('polished_end_stone_slab', ['SSS'], {'S': f'{NS}:polished_end_stone'}, f'{NS}:polished_end_stone_slab', 6)
shaped('end_stone_tile_slab', ['SSS'], {'S': f'{NS}:end_stone_tiles'}, f'{NS}:end_stone_tile_slab', 6)
shaped('enderium_block', ['III', 'III', 'III'], {'I': f'{NS}:enderium_ingot'}, f'{NS}:enderium_block', 1)
shaped('raw_enderium_block', ['III', 'III', 'III'], {'I': f'{NS}:raw_enderium'}, f'{NS}:raw_enderium_block', 1)
shaped('enderium_lantern', [' S ', 'SRS', ' S '],
       {'S': 'minecraft:end_stone_bricks', 'R': f'{NS}:raw_enderium'}, f'{NS}:enderium_lantern', 2)
shapeless('enderium_ingot_from_enderium_block', [f'{NS}:enderium_block'], f'{NS}:enderium_ingot', 9)
shapeless('raw_enderium_from_raw_enderium_block', [f'{NS}:raw_enderium_block'], f'{NS}:raw_enderium', 9)

# ---- stonecutting ----------------------------------------------------------
def stonecut(name, src, result, count=1):
    emit(f'data/{NS}/recipe/{name}.json',
         {'type': 'minecraft:stonecutting', 'ingredient': ING(src),
          'result': {'count': count, 'id': result}})
    recipe_advancement(name, src)

CUT = {
    'minecraft:end_stone': ['polished_end_stone', 'end_stone_tiles', 'chiseled_end_stone_tiles',
                            'polished_end_stone_stairs', 'end_stone_tile_stairs',
                            ('polished_end_stone_slab', 2), ('end_stone_tile_slab', 2)],
    f'{NS}:polished_end_stone': ['end_stone_tiles', 'chiseled_end_stone_tiles',
                                 'polished_end_stone_stairs', ('polished_end_stone_slab', 2),
                                 'end_stone_tile_stairs', ('end_stone_tile_slab', 2)],
    f'{NS}:end_stone_tiles': ['chiseled_end_stone_tiles', 'end_stone_tile_stairs',
                              ('end_stone_tile_slab', 2)],
}
for src, outs in CUT.items():
    src_short = src.split(':')[1]
    for out in outs:
        out_name, count = out if isinstance(out, tuple) else (out, 1)
        stonecut(f'{out_name}_from_{src_short}_stonecutting', src, f'{NS}:{out_name}', count)

# ---- recipe-book advancements ---------------------------------------------

# ---- tags ------------------------------------------------------------------
ALL_BLOCKS = CUBES + list(STAIRS) + list(SLABS) + ['enderium_lantern']
emit('data/minecraft/tags/block/mineable/pickaxe.json',
     {'values': [f'{NS}:enderium_ore'] + [f'{NS}:{b}' for b in ALL_BLOCKS]})
emit('data/minecraft/tags/block/needs_diamond_tool.json',
     {'values': [f'{NS}:enderium_ore', f'{NS}:enderium_block', f'{NS}:raw_enderium_block']})
emit('data/minecraft/tags/block/beacon_base_blocks.json',
     {'values': [f'{NS}:enderium_block']})
for kind in ('block', 'item'):
    emit(f'data/minecraft/tags/{kind}/stairs.json',
         {'values': [f'{NS}:{s}' for s in STAIRS]})
    emit(f'data/minecraft/tags/{kind}/slabs.json',
         {'values': [f'{NS}:{s}' for s in SLABS]})


# ---- Tide 6: Mourning Bloom + petal economy --------------------------------
BLOOM = 'mourning_bloom'
mb = {'minecraft:block/poppy': f'{NS}:block/{BLOOM}',
      'minecraft:poppy': f'{NS}:{BLOOM}',
      'minecraft:blocks/poppy': f'{NS}:blocks/{BLOOM}',
      'minecraft:item/poppy': f'{NS}:item/{BLOOM}',
      'minecraft:block/potted_poppy': f'{NS}:block/potted_{BLOOM}',
      'minecraft:potted_poppy': f'{NS}:potted_{BLOOM}',
      'minecraft:blocks/potted_poppy': f'{NS}:blocks/potted_{BLOOM}'}
emit(f'assets/{NS}/blockstates/{BLOOM}.json',
     rewrite(crib('assets/minecraft/blockstates/poppy.json'), mb))
emit(f'assets/{NS}/models/block/{BLOOM}.json',
     rewrite(crib('assets/minecraft/models/block/poppy.json'), mb))
emit(f'assets/{NS}/blockstates/potted_{BLOOM}.json',
     rewrite(crib('assets/minecraft/blockstates/potted_poppy.json'), mb))
emit(f'assets/{NS}/models/block/potted_{BLOOM}.json',
     rewrite(crib('assets/minecraft/models/block/potted_poppy.json'), mb))
emit(f'data/{NS}/loot_table/blocks/{BLOOM}.json',
     rewrite(crib('data/minecraft/loot_table/blocks/poppy.json'), mb))
emit(f'data/{NS}/loot_table/blocks/potted_{BLOOM}.json',
     rewrite(crib('data/minecraft/loot_table/blocks/potted_poppy.json'), mb))

# flower item: flat sprite of the block texture
emit(f'assets/{NS}/models/item/{BLOOM}.json',
     {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:block/{BLOOM}'}})
if BRANCH == '26x':
    emit(f'assets/{NS}/items/{BLOOM}.json',
         {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{BLOOM}'}})
for it in ('void_petal', 'draught_of_return'):
    emit(f'assets/{NS}/models/item/{it}.json',
         {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{it}'}})
    if BRANCH == '26x':
        emit(f'assets/{NS}/items/{it}.json',
             {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{it}'}})

# worldgen: rare constellations, never the dragon island
# 26.x dropped the random_patch wrapper: patch shape lives in the PLACED chain
# (count + random_offset), and simple_block self-validates the ground.
cf = crib('data/minecraft/worldgen/configured_feature/flower_default.json')
cf = rewrite(cf, {'minecraft:dandelion': f'{NS}:{BLOOM}', 'minecraft:poppy': f'{NS}:{BLOOM}'})
emit(f'data/{NS}/worldgen/configured_feature/mourning_blooms.json', cf)
emit(f'data/{NS}/worldgen/placed_feature/mourning_blooms.json', {
    'feature': f'{NS}:mourning_blooms',
    'placement': [
        {'type': 'minecraft:rarity_filter', 'chance': 6},
        {'type': 'minecraft:in_square'},
        {'type': 'minecraft:heightmap', 'heightmap': 'MOTION_BLOCKING'},
        {'type': 'minecraft:count', 'count': 5},
        {'type': 'minecraft:random_offset',
         'xz_spread': {'type': 'minecraft:trapezoid', 'min': -4, 'max': 4, 'plateau': 0},
         'y_spread': {'type': 'minecraft:trapezoid', 'min': -2, 'max': 2, 'plateau': 0}},
        {'type': 'minecraft:block_predicate_filter',
         'predicate': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'}},
        {'type': 'minecraft:biome'},
    ],
})
emit(f'data/{NS}/neoforge/biome_modifier/add_mourning_blooms.json', {
    'type': 'neoforge:add_features',
    'biomes': ['minecraft:end_highlands', 'minecraft:end_midlands',
               'minecraft:small_end_islands', 'minecraft:end_barrens'],
    'features': f'{NS}:mourning_blooms',
    'step': 'vegetal_decoration',
})

shapeless('void_petals_from_bloom', [f'{NS}:{BLOOM}'], f'{NS}:void_petal', 2)
shapeless('draught_of_return', [f'{NS}:void_petal', 'minecraft:glass_bottle'], f'{NS}:draught_of_return', 1)

print(f'{BRANCH}: emitted {counts["files"]} files into {RES}')
