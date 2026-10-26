"""Legacy (1.8-1.12 numeric id:data) -> modern block-data strings. Pure data transform.

Targets the block registry shipped in tools/lobby-gen (validated by the caller). Block entities
(signs, banners, skulls, chests' contents, pots) are dropped or reduced to the plain block.
"""

COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
          'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']
WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak']
HORIZ = {2: 'north', 3: 'south', 4: 'west', 5: 'east'}  # chest/ladder/furnace/wall-sign
STAIR_FACING = {0: 'east', 1: 'west', 2: 'south', 3: 'north'}
STAIR_IDS = {53: 'oak', 67: 'cobblestone', 108: 'brick', 109: 'stone_brick', 114: 'nether_brick',
             128: 'sandstone', 134: 'spruce', 135: 'birch', 136: 'jungle', 156: 'quartz',
             163: 'acacia', 164: 'dark_oak'}
FENCE_IDS = {85: 'oak', 113: 'nether_brick', 188: 'spruce', 189: 'birch', 190: 'jungle', 191: 'dark_oak', 192: 'acacia'}
GATE_IDS = {107: 'oak', 183: 'spruce', 184: 'birch', 185: 'jungle', 186: 'dark_oak', 187: 'acacia'}
DOOR_IDS = {64: 'oak', 71: 'iron', 193: 'spruce', 194: 'birch', 195: 'jungle', 196: 'acacia', 197: 'dark_oak'}

SIMPLE = {
    2: 'grass_block', 4: 'cobblestone', 7: 'bedrock', 13: 'gravel', 20: 'glass', 30: 'cobweb', 32: 'dead_bush',
    37: 'dandelion', 41: 'gold_block', 42: 'iron_block', 45: 'bricks', 46: 'tnt', 47: 'bookshelf', 48: 'mossy_cobblestone',
    49: 'obsidian', 56: 'diamond_ore', 57: 'diamond_block', 58: 'crafting_table', 73: 'redstone_ore', 79: 'ice',
    80: 'snow_block', 82: 'clay', 84: 'jukebox', 87: 'netherrack', 88: 'soul_sand', 89: 'glowstone', 98: 'stone_bricks',
    101: 'iron_bars', 102: 'glass_pane', 111: 'lily_pad', 112: 'nether_bricks', 121: 'end_stone', 133: 'emerald_block',
    152: 'redstone_block', 169: 'sea_lantern', 172: 'terracotta', 173: 'coal_block', 174: 'packed_ice',
    16: 'coal_ore', 15: 'iron_ore', 14: 'gold_ore', 21: 'lapis_ore', 22: 'lapis_block', 24: 'sandstone',
    29: 'sticky_piston', 33: 'piston', 61: 'furnace', 62: 'furnace', 103: 'melon', 86: 'carved_pumpkin', 140: 'flower_pot',
    116: 'enchanting_table', 117: 'brewing_stand', 118: 'cauldron', 130: 'ender_chest', 145: 'anvil',
    121: 'end_stone', 155: 'quartz_block', 165: 'slime_block', 213: 'magma_block', 214: 'nether_wart_block',
    215: 'red_nether_bricks', 216: 'bone_block', 217: 'structure_void', 35: None,
}
# Blocks that never connect fences/panes/walls (not full cubes). Names are modern.
NONSOLID = ('air', 'water', 'lava', 'torch', 'wall_torch', 'redstone_torch', 'redstone_wall_torch', 'ladder', 'vine',
            'short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'dandelion', 'poppy', 'lily_pad',
            'wheat', 'rail', 'lever', 'cobweb', 'flower_pot', 'cake', 'redstone_wire', 'snow',
            'azure_bluet', 'allium', 'blue_orchid', 'oxeye_daisy', 'red_tulip', 'orange_tulip', 'white_tulip',
            'pink_tulip', 'sunflower', 'lilac', 'rose_bush', 'peony')


def _colour(i):
    return COLORS[i & 15]


def base(i, d):
    """Return a modern state string or None (drop) for a legacy block. Context-dependent blocks return a tuple tag."""
    if i == 0: return None
    if i == 1: return 'minecraft:' + ['stone', 'granite', 'polished_granite', 'diorite', 'polished_diorite', 'andesite', 'polished_andesite'][d if d < 7 else 0]
    if i == 3: return 'minecraft:' + ['dirt', 'coarse_dirt', 'podzol'][d if d < 3 else 0]
    if i == 5: return 'minecraft:%s_planks' % WOODS[d % 6]
    if i in (8, 9): return 'minecraft:water[level=%d]' % d
    if i in (10, 11): return 'minecraft:lava[level=%d]' % d
    if i == 12: return 'minecraft:' + ('red_sand' if d == 1 else 'sand')
    if i in (17, 162):
        t = WOODS[(d & 3) + (4 if i == 162 else 0)]
        ax = ['y', 'x', 'z', 'bark'][(d >> 2) & 3]
        if ax == 'bark': return 'minecraft:%s_wood' % t
        return 'minecraft:%s_log[axis=%s]' % (t, ax)
    if i in (18, 161):
        t = WOODS[(d & 3) + (4 if i == 161 else 0)]
        return 'minecraft:%s_leaves[persistent=true]' % t
    if i == 24: return 'minecraft:' + ['sandstone', 'chiseled_sandstone', 'cut_sandstone'][d if d < 3 else 0]
    if i == 31: return 'minecraft:' + ['dead_bush', 'short_grass', 'fern'][d if d < 3 else 1]
    if i == 35: return 'minecraft:%s_wool' % _colour(d)
    if i == 38: return 'minecraft:' + ['poppy', 'blue_orchid', 'allium', 'azure_bluet', 'red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip', 'oxeye_daisy'][d if d < 9 else 0]
    if i == 43: return 'minecraft:' + ['smooth_stone', 'sandstone', 'oak_planks', 'cobblestone', 'bricks', 'stone_bricks', 'nether_bricks', 'quartz_block'][(d & 7)]
    if i == 44:
        t = ['smooth_stone', 'sandstone', 'oak', 'cobblestone', 'brick', 'stone_brick', 'nether_brick', 'quartz'][d & 7]
        return 'minecraft:%s_slab[type=%s]' % (t, 'top' if d & 8 else 'bottom')
    if i == 125: return 'minecraft:%s_planks' % WOODS[d % 6]
    if i == 126: return 'minecraft:%s_slab[type=%s]' % (WOODS[d & 7 if (d & 7) < 6 else 0], 'top' if d & 8 else 'bottom')
    if i == 182: return 'minecraft:red_sandstone_slab[type=%s]' % ('top' if d & 8 else 'bottom')
    if i == 50:
        if d == 5 or d == 0: return 'minecraft:torch'
        return 'minecraft:wall_torch[facing=%s]' % {1: 'east', 2: 'west', 3: 'south', 4: 'north'}.get(d, 'north')
    if i == 75: return 'minecraft:redstone_wall_torch[lit=false,facing=%s]' % {1: 'east', 2: 'west', 3: 'south', 4: 'north'}.get(d, 'north') if d != 5 else 'minecraft:redstone_torch[lit=false]'
    if i == 76: return 'minecraft:redstone_wall_torch[lit=true,facing=%s]' % {1: 'east', 2: 'west', 3: 'south', 4: 'north'}.get(d, 'north') if d != 5 else 'minecraft:redstone_torch[lit=true]'
    if i in (54, 146): return 'minecraft:%s[facing=%s]' % ('chest' if i == 54 else 'trapped_chest', HORIZ.get(d, 'north'))
    if i == 55: return 'minecraft:redstone_wire[power=%d]' % d
    if i == 59: return 'minecraft:wheat[age=%d]' % (d & 7)
    if i == 60: return 'minecraft:farmland[moisture=%d]' % (d & 7)
    if i == 61: return 'minecraft:furnace[facing=%s]' % HORIZ.get(d, 'north')
    if i == 65: return 'minecraft:ladder[facing=%s]' % HORIZ.get(d, 'north')
    if i == 66:
        shape = ['north_south', 'east_west', 'ascending_east', 'ascending_west', 'ascending_north', 'ascending_south',
                 'south_east', 'south_west', 'north_west', 'north_east'][d % 10]
        return 'minecraft:rail[shape=%s]' % shape
    if i in STAIR_IDS:
        return ('stairs', STAIR_IDS[i], STAIR_FACING[d & 3], 'top' if d & 4 else 'bottom')
    if i in (77, 143):
        name = 'stone_button' if i == 77 else 'oak_button'
        b = d & 7
        if b == 0: return 'minecraft:%s[face=ceiling,facing=north,powered=%s]' % (name, str(bool(d & 8)).lower())
        if b == 5: return 'minecraft:%s[face=floor,facing=north,powered=%s]' % (name, str(bool(d & 8)).lower())
        return 'minecraft:%s[face=wall,facing=%s,powered=%s]' % (name, {1: 'east', 2: 'west', 3: 'south', 4: 'north'}.get(b, 'north'), str(bool(d & 8)).lower())
    if i == 69:
        b = d & 7
        face = 'ceiling' if b in (0, 7) else ('floor' if b in (5, 6) else 'wall')
        facing = {1: 'east', 2: 'west', 3: 'south', 4: 'north'}.get(b, 'north')
        return 'minecraft:lever[face=%s,facing=%s,powered=%s]' % (face, facing, str(bool(d & 8)).lower())
    if i in FENCE_IDS: return ('fence', FENCE_IDS[i] + ('_fence' if i != 113 else '_fence'))
    if i == 139: return ('wall', 'mossy_cobblestone_wall' if d == 1 else 'cobblestone_wall')
    if i in GATE_IDS:
        return 'minecraft:%s_fence_gate[facing=%s,open=%s]' % (GATE_IDS[i], ['south', 'west', 'north', 'east'][d & 3], str(bool(d & 4)).lower())
    if i in DOOR_IDS: return ('door', DOOR_IDS[i], d)
    if i == 96 or i == 167:
        name = 'oak_trapdoor' if i == 96 else 'iron_trapdoor'
        return 'minecraft:%s[facing=%s,half=%s,open=%s]' % (name, ['north', 'south', 'west', 'east'][d & 3], 'top' if d & 8 else 'bottom', str(bool(d & 4)).lower())
    if i == 91: return 'minecraft:jack_o_lantern[facing=%s]' % ['south', 'west', 'north', 'east'][d & 3]
    if i == 92: return 'minecraft:cake[bites=%d]' % min(d, 6)
    if i == 95: return 'minecraft:%s_stained_glass' % _colour(d)
    if i == 160: return ('pane', '%s_stained_glass_pane' % _colour(d))
    if i == 102: return ('pane', 'glass_pane')
    if i == 101: return ('pane', 'iron_bars')
    if i == 97: return 'minecraft:' + ['stone', 'cobblestone', 'stone_bricks', 'mossy_stone_bricks', 'cracked_stone_bricks', 'chiseled_stone_bricks'][d if d < 6 else 0]
    if i == 98: return 'minecraft:' + ['stone_bricks', 'mossy_stone_bricks', 'cracked_stone_bricks', 'chiseled_stone_bricks'][d & 3]
    if i == 106: return ('vine', d)
    if i == 124: return 'minecraft:redstone_lamp[lit=true]'
    if i == 123: return 'minecraft:redstone_lamp'
    if i == 159: return 'minecraft:%s_terracotta' % _colour(d)
    if i == 171: return 'minecraft:%s_carpet' % _colour(d)
    if i == 168: return 'minecraft:' + ['prismarine', 'prismarine_bricks', 'dark_prismarine'][d if d < 3 else 0]
    if i == 170: return 'minecraft:hay_block[axis=%s]' % ['y', 'x', 'z', 'y'][(d >> 2) & 3]
    if i == 175: return ('plant2', d)
    if i == 179: return 'minecraft:' + ['red_sandstone', 'chiseled_red_sandstone', 'cut_red_sandstone'][d if d < 3 else 0]
    if i == 251: return 'minecraft:%s_concrete' % _colour(d)
    if i == 252: return 'minecraft:%s_concrete_powder' % _colour(d)
    if i == 3: return 'minecraft:dirt'
    if i in SIMPLE and SIMPLE[i]: return 'minecraft:' + SIMPLE[i]
    # signs, banners, skulls, beds, flower pots with contents, pistons' heads etc.: dropped
    if i in (26, 36, 63, 68, 144, 176, 177, 34, 119, 120, 52, 90, 95 - 1000):
        return None
    raise KeyError('unmapped legacy block %d:%d' % (i, d))


def convert(blocks):
    """blocks: dict (x,y,z)->(id,data). Returns dict (x,y,z)->modern state string."""
    mid = {}
    for pos, (i, d) in blocks.items():
        b = base(i, d)
        if b is not None: mid[pos] = b
    name_at = {}
    for pos, b in mid.items():
        name_at[pos] = b if isinstance(b, str) else None
    out = {}

    def nm(pos):
        b = mid.get(pos)
        if b is None: return 'air'
        if isinstance(b, str): return b.split('[')[0].split(':')[1]
        k = b[0]
        if k == 'stairs': return b[1] + '_stairs'
        if k == 'door': return b[1] + '_door'
        if k in ('fence', 'wall', 'pane'): return b[1]
        return k

    def solid(pos):
        n = nm(pos)
        if n in NONSOLID or n.endswith('_stairs') or n.endswith('_slab') or n.endswith('_trapdoor') or n.endswith('_carpet') \
                or n.endswith('_button') or n.endswith('_door') or n == 'plant2' or n.endswith('_leaves') and False:
            return False
        if n.endswith('_fence') or n.endswith('_wall') or n.endswith('_pane') or n == 'iron_bars' or n.endswith('_fence_gate'):
            return False
        return True

    DIRS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}
    CCW = {'north': 'west', 'west': 'south', 'south': 'east', 'east': 'north'}
    OPP = {'north': 'south', 'south': 'north', 'west': 'east', 'east': 'west'}

    def add(pos, d): return (pos[0] + d[0], pos[1] + d[1], pos[2] + d[2])

    def stair_info(pos):
        b = mid.get(pos)
        if isinstance(b, tuple) and b[0] == 'stairs': return b
        return None

    def stair_shape(pos, b):
        _, fam, facing, half = b
        f = stair_info(add(pos, DIRS[facing]))
        if f and f[3] == half and f[2] not in (facing, OPP[facing]):
            d1 = f[2]
            n = stair_info(add(pos, DIRS[OPP[d1]]))
            if not (n and n[2] == facing and n[3] == half):
                return 'outer_left' if d1 == CCW[facing] else 'outer_right'
        g = stair_info(add(pos, DIRS[OPP[facing]]))
        if g and g[3] == half and g[2] not in (facing, OPP[facing]):
            d2 = g[2]
            n = stair_info(add(pos, DIRS[d2]))
            if not (n and n[2] == facing and n[3] == half):
                return 'inner_left' if d2 == CCW[facing] else 'inner_right'
        return 'straight'

    for pos, b in mid.items():
        if isinstance(b, str):
            out[pos] = b
            continue
        kind = b[0]
        if kind == 'stairs':
            out[pos] = 'minecraft:%s_stairs[facing=%s,half=%s,shape=%s]' % (b[1], b[2], b[3], stair_shape(pos, b))
        elif kind in ('fence', 'pane', 'wall'):
            fam = b[1]
            conn = {}
            for dn, dv in DIRS.items():
                n = add(pos, dv); nn = nm(n)
                ok = solid(n)
                if kind == 'fence':
                    if fam == 'nether_brick_fence': ok = ok or nn == 'nether_brick_fence'
                    else: ok = ok or (nn.endswith('_fence') and nn != 'nether_brick_fence')
                    ok = ok or nn.endswith('_fence_gate')
                else:
                    ok = ok or nn.endswith('_pane') or nn == 'iron_bars' or nn.endswith('_wall')
                conn[dn] = ok
            if kind == 'wall':
                sides = ','.join('%s=%s' % (k, 'low' if v else 'none') for k, v in conn.items())
                straight = (conn['north'] and conn['south'] and not conn['east'] and not conn['west']) or \
                           (conn['east'] and conn['west'] and not conn['north'] and not conn['south'])
                up = 'false' if straight and nm(add(pos, (0, 1, 0))) in ('air',) else 'true'
                out[pos] = 'minecraft:%s[%s,up=%s]' % (fam, sides, up)
            else:
                out[pos] = 'minecraft:%s[%s]' % (fam, ','.join('%s=%s' % (k, str(v).lower()) for k, v in conn.items()))
        elif kind == 'door':
            fam, d = b[1], b[2]
            if d & 8:
                below = mid.get(add(pos, (0, -1, 0)))
                # lower half carries facing/open; upper carries hinge/powered
                ld = below[2] if isinstance(below, tuple) and below[0] == 'door' else 0
                out[pos] = 'minecraft:%s_door[half=upper,facing=%s,open=%s,hinge=%s,powered=%s]' % (
                    fam, ['east', 'south', 'west', 'north'][ld & 3], str(bool(ld & 4)).lower(),
                    'right' if d & 1 else 'left', str(bool(d & 2)).lower())
            else:
                above = mid.get(add(pos, (0, 1, 0)))
                ud = above[2] if isinstance(above, tuple) and above[0] == 'door' else 8
                out[pos] = 'minecraft:%s_door[half=lower,facing=%s,open=%s,hinge=%s,powered=%s]' % (
                    fam, ['east', 'south', 'west', 'north'][d & 3], str(bool(d & 4)).lower(),
                    'right' if ud & 1 else 'left', str(bool(ud & 2)).lower())
        elif kind == 'vine':
            d = b[1]
            sides = {'south': d & 1, 'west': d & 2, 'north': d & 4, 'east': d & 8}
            up = 'true' if d == 0 else 'false'
            out[pos] = 'minecraft:vine[%s,up=%s]' % (','.join('%s=%s' % (k, str(bool(v)).lower()) for k, v in sides.items()), up)
        elif kind == 'plant2':
            d = b[1]
            if d & 8:
                below = mid.get(add(pos, (0, -1, 0)))
                lo = below[1] if isinstance(below, tuple) and below[0] == 'plant2' else 2
                name = ['sunflower', 'lilac', 'tall_grass', 'large_fern', 'rose_bush', 'peony'][lo & 7 if (lo & 7) < 6 else 2]
                out[pos] = 'minecraft:%s[half=upper]' % name
            else:
                name = ['sunflower', 'lilac', 'tall_grass', 'large_fern', 'rose_bush', 'peony'][d & 7 if (d & 7) < 6 else 2]
                out[pos] = 'minecraft:%s[half=lower]' % name
    return out
