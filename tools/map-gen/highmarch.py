#!/usr/bin/env python3
"""Highmarch: original deterministic voxel architecture, ADR-0317. Python stdlib only."""
import argparse
import collections
import itertools
import json
import math
from pathlib import Path
import re
import time

ROOT = Path(__file__).resolve().parents[2]
SEED = 20261101
TEAMS = {'red': (0, -108, 0), 'blue': (94, 54, 1), 'yellow': (-94, 54, 3)}
OUTPOSTS = {'east': (85, -50), 'west': (-85, -50), 'north': (0, 112)}
PILLARS = [(0, 22), (-25, -4), (0, -27)]
DISTRICTS = {'plaza': (0, -2), 'market': (25, -2), 'forge': (-27, 24),
             'gate-court': PILLARS[0], 'inner-ward': PILLARS[1], 'keep': PILLARS[2],
             'house': (25, -27)}


def point(team, u, v):
    x, z, turn = TEAMS[team]
    for _ in range(turn):
        u, v = -v, u
    return x + u, z + v


def xyz(team, u, v, y=65):
    x, z = point(team, u, v)
    return [x, y, z]


class World:
    def __init__(self, blocks=None, turn=0, ox=0, oz=0, oy=0):
        self.blocks = {} if blocks is None else blocks
        self.turn, self.ox, self.oz, self.oy = turn, ox, oz, oy

    def frame(self, x, z, turn=0, dy=0):
        x, z = self.xz(x, z)
        return World(self.blocks, (self.turn + turn) % 4, x, z, self.oy + dy)

    def xz(self, x, z):
        for _ in range(self.turn):
            x, z = -z, x
        return self.ox + x, self.oz + z

    def set(self, x, y, z, state):
        x, z = self.xz(x, z)
        y += self.oy
        if state is None:
            self.blocks.pop((x, y, z), None)
            return
        # Rotate directional states together with the architecture, including fence/pane connections.
        directions = ['north', 'east', 'south', 'west']
        def rotate(match):
            return directions[(directions.index(match[0]) + self.turn) % 4]
        state = re.sub(r'\b(north|east|south|west)\b', rotate, state)
        if self.turn % 2:
            state = re.sub(r'axis=([xz])', lambda m: 'axis=' + ('z' if m[1] == 'x' else 'x'), state)
        if 'rotation=' in state:
            state = re.sub(r'rotation=(\d+)', lambda m: f'rotation={(int(m[1]) + self.turn * 4) % 16}', state)
        self.blocks[x, y, z] = 'minecraft:' + state

    def box(self, x0, x1, y0, y1, z0, z1, state):
        for x, y, z in itertools.product(range(x0, x1 + 1), range(y0, y1 + 1), range(z0, z1 + 1)):
            self.set(x, y, z, state)


def terrace(w, x, z, radius, top=64, town=False):
    """Layered rock with a tapered, faceted underside; no random single-block speckle."""
    for u in range(-radius, radius + 1):
        for v in range(-radius, radius + 1):
            r = max(abs(u), abs(v), (abs(u) + abs(v)) / 1.9) if town else math.hypot(u, v)
            edge = radius - r
            if edge < 0:
                continue
            w.box(x + u, x + u, top + 1, top + 3, z + v, z + v, None)
            bottom = top - 3 - int(min(34, edge * .95 + 2 * math.sin((u + v) / 11)))
            for y in range(bottom, top + 1):
                state = 'deepslate' if y < 39 else 'stone'
                if y in (43, 44, 56):
                    state = 'tuff'
                if y >= top - 2:
                    state = 'dirt'
                if y == top:
                    state = 'grass_block'
                w.set(x + u, y, z + v, state)


def ramp(w, x, z, length, bottom=64, rise=8, width=3, material='stone_bricks'):
    """Full-block stairs: the production bot and player both have one-block ascent and two-block headroom."""
    for step in range(length + 1):
        h = bottom + min(rise, step * rise // length)
        w.box(x - width // 2, x + width // 2, bottom, h, z + step, z + step, material)
        w.box(x - width // 2, x + width // 2, h + 1, h + 3, z + step, z + step, None)


def wall(w, length=78):
    for u in range(-length // 2, length // 2 + 1):
        w.box(u, u, 65, 70, -1, 1, 'stone_bricks')
        w.box(u, u, 71, 71, -2, 2, 'stone_bricks')
        for side in (-2, 2):
            w.set(u, 72, side, 'stone_brick_wall[north=none,south=none,east=low,west=low,up=false]')
            if u % 3 == 0:
                w.set(u, 73, side, 'stone_bricks')
        if u % 12 == 0:
            w.box(u, u, 65, 70, -2, -2, 'polished_deepslate')


def tower(w, team, y=64):
    w.box(-2, 2, y + 1, y + 12, -2, 2, None)
    for u in range(-3, 4):
        for v in range(-3, 4):
            if max(abs(u), abs(v)) == 3:
                w.box(u, u, y + 1, y + 12, v, v, 'stone_bricks')
    w.box(-3, 3, y + 12, y + 12, -3, 3, 'spruce_planks')
    w.box(-1, 1, y + 1, y + 3, 3, 3, None)
    # Wrapped stairs inside the tower; 12 rises on three sides, opening into the wall walk at y=72.
    route = [(x, 2) for x in range(-2, 3)] + [(2, z) for z in range(1, -3, -1)] + [(x, -2) for x in range(1, -3, -1)]
    for i, (u, v) in enumerate(route):
        w.set(u, y + min(i, 12), v, 'stone_bricks')
        w.box(u, u, y + min(i, 12) + 1, y + min(i, 12) + 3, v, v, None)
    w.box(-3, -3, y + 8, y + 10, -1, 1, None)
    for edge in (-3, 3):
        for u in range(-3, 4, 2):
            w.set(u, y + 13, edge, 'deepslate_brick_wall')
            w.set(edge, y + 13, u, 'deepslate_brick_wall')
    w.box(0, 0, y + 13, y + 16, 0, 0, 'spruce_fence')
    w.set(0, y + 17, 0, f'{team}_banner[rotation=0]')


def gatehouse(w, team):
    for x in (-7, 7):
        tower(w.frame(x, 0), team)
    w.box(-4, 4, 65, 73, -2, 2, 'stone_bricks')
    # Open arch is 7 wide at ground, narrows only well above the player's head.
    w.box(-3, 3, 65, 69, -3, 3, None)
    w.box(-2, 2, 70, 70, -3, 3, None)
    w.box(-1, 1, 71, 71, -3, 3, None)
    w.box(-4, 4, 74, 74, -2, 2, 'deepslate_bricks')
    for x in (-4, 4):
        w.set(x, 69, 3, f'{team}_wall_banner[facing=south]')
        w.set(x, 68, 3, 'lantern[hanging=true]')


def monument(w, team):
    w.box(-4, 4, 64, 64, -4, 4, 'polished_deepslate')
    w.set(0, 64, 0, f'{team}_concrete')
    for x, z in itertools.product((-3, 3), repeat=2):
        w.box(x, x, 65, 72, z, z, 'chiseled_stone_bricks')
        w.set(x, 73, z, 'lantern')
    for edge in (-3, 3):
        w.box(-3, 3, 74, 74, edge, edge, f'{team}_concrete')
        w.box(edge, edge, 74, 74, -3, 3, f'{team}_concrete')
    w.set(0, 75, -3, f'{team}_banner[rotation=0]')
    # The actual objective at (0,65,0) is left air for the match's single-block hitbox.


def house(w, team, forge_building=False):
    w.box(-5, 5, 64, 64, -5, 5, 'stone_bricks')
    for x in range(-5, 6):
        for z in range(-5, 6):
            if abs(x) == 5 or abs(z) == 5:
                w.box(x, x, 65, 69, z, z, 'spruce_planks')
    for x, z in itertools.product((-5, 5), repeat=2):
        w.box(x, x, 65, 70, z, z, 'stripped_spruce_log[axis=y]')
    for z in (-5, 5):
        for x in (-3, 3):
            w.box(x, x, 67, 68, z, z, 'glass_pane[east=true,west=true]')
            w.set(x + 1, 67, z, 'spruce_trapdoor[facing=south,half=bottom,open=true]')
    for z in (-2, 2):
        for x in (-5, 5):
            w.box(x, x, 67, 68, z, z, 'glass_pane[north=true,south=true]')
    w.box(-1, 1, 65, 67, 5, 5, None)
    w.set(-1, 65, 5, 'spruce_door[facing=south,half=lower,open=true,hinge=left]')
    w.set(-1, 66, 5, 'spruce_door[facing=south,half=upper,open=true,hinge=left]')
    for x in range(-6, 7):
        h = 70 + (6 - abs(x)) // 2
        facing = 'east' if x < 0 else 'west'
        w.box(x, x, h, h, -6, 6, f'deepslate_tile_stairs[facing={facing},half=bottom,shape=straight]')
        # Coloured gable ends read as heraldry rather than a solid coloured cube.
        if abs(x) <= 4:
            for z in (-5, 5):
                w.box(x, x, 70, h - 1, z, z, f'{team}_wool')
    w.set(-3, 65, -3, 'barrel[facing=up]')
    w.set(-3, 66, -3, 'lantern')
    w.set(3, 65, -3, 'crafting_table')
    if not forge_building:
        # External stepped roof access, also a cache approach (no ladder-only routes).
        ramp(w.frame(8, -7), 0, 0, 16, rise=8)
        w.box(5, 8, 72, 72, 6, 9, 'spruce_planks')


def market_stall(w, team):
    w.box(-3, 3, 64, 64, -2, 2, 'spruce_planks')
    for x in (-3, 3):
        for z in (-2, 2):
            w.box(x, x, 65, 68, z, z, 'spruce_fence')
    for x in range(-3, 4):
        w.box(x, x, 69, 69, -3, 3, f'{team}_wool' if x % 2 == 0 else 'white_wool')
    w.box(-2, 2, 65, 65, -1, -1, 'spruce_slab[type=top]')
    w.set(-2, 65, 1, 'barrel[facing=up]')
    w.set(3, 68, 0, 'lantern[hanging=true]')


def forge(w, team):
    house(w, team, forge_building=True)
    w.box(2, 4, 65, 77, -4, -2, 'deepslate_bricks')
    w.box(3, 3, 66, 77, -3, -3, None)
    w.set(3, 78, -3, 'campfire[lit=true]')
    w.set(3, 65, -1, 'blast_furnace[facing=south]')
    w.set(0, 65, 0, 'anvil[facing=east]')
    w.box(-5, 5, 65, 68, -9, -8, 'stone')
    w.box(-6, 6, 69, 69, -9, -8, 'andesite')
    w.set(-4, 65, 2, 'smithing_table')


def keep(w, team):
    for edge in (-9, 9):
        w.box(-9, 9, 65, 79, edge, edge, 'stone_bricks')
        w.box(edge, edge, 65, 79, -9, 9, 'stone_bricks')
    for y in (72, 80):
        w.box(-9, 9, y, y, -9, 9, 'spruce_planks' if y == 72 else 'deepslate_bricks')
        # Open central sanctum: monument visible through a large roof lantern.
        w.box(-4, 4, y, y, -4, 4, None)
    for edge in (-9, 9):
        w.box(-2, 2, 65, 69, edge, edge, None)
        w.box(edge, edge, 65, 69, -2, 2, None)
        for u in (-6, 6):
            w.box(u, u, 75, 77, edge, edge, 'glass_pane[east=true,west=true]')
    # Two flights at the west and north edges connect floor -> gallery -> roof.
    ramp(w.frame(-7, -7), 0, 0, 8, rise=8)
    w.box(-7, -5, 72, 72, 1, 7, 'spruce_planks')
    ramp(w.frame(-6, 7, 3), 0, 0, 8, bottom=72, rise=8)
    w.box(2, 7, 80, 80, 6, 8, 'deepslate_bricks')
    for edge in (-9, 9):
        for u in range(-9, 10, 2):
            w.set(u, 81, edge, 'deepslate_brick_wall')
            w.set(edge, 81, u, 'deepslate_brick_wall')
    for x in (-9, 9):
        for z in (-9, 9):
            w.box(x, x, 80, 84, z, z, f'{team}_concrete')
            w.set(x, 85, z, 'lantern')
    monument(w, team)


def bridge(w, a, b, width=7):
    ax, az = a; bx, bz = b
    distance = math.hypot(bx - ax, bz - az)
    nx, nz = -(bz - az) / distance, (bx - ax) / distance
    for i in range(math.ceil(distance) + 1):
        t = i / math.ceil(distance)
        x, z = ax + (bx - ax) * t, az + (bz - az) * t
        # Arch soffit rises at the centre; anchored abutments at the ends.
        depth = 2 + round(7 * (abs(2 * t - 1) ** 2))
        for side in range(-width // 2 + 1, width // 2 + 1):
            u, v = round(x + nx * side), round(z + nz * side)
            for y in range(64 - depth, 65):
                w.set(u, y, v, 'stone_bricks' if y >= 62 else 'andesite')
        for side in (-4, 4):
            u, v = round(x + nx * side), round(z + nz * side)
            w.set(u, 64, v, 'stone_bricks')
            w.set(u, 65, v, 'stone_brick_wall')
    # The diagonal raster has no holes: infill between adjacent deck samples.
    for x in range(min(ax, bx) - 4, max(ax, bx) + 5):
        for z in range(min(az, bz) - 4, max(az, bz) + 5):
            t = ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / distance ** 2
            if 0 <= t <= 1 and abs((x - ax) * nx + (z - az) * nz) <= 3:
                w.set(x, 64, z, 'stone_bricks')
                w.box(x, x, 65, 67, z, z, None)


def quarry_height(x, z):
    r = math.hypot(x, z)
    if r > 42:
        return None
    h = 50 if r <= 19 else 55 if r <= 28 else 60 if r <= 35 else 64
    # Three spoke ramps follow the approach bearings and never rise more than one block per step.
    for dx, dz in ((0, -1), (.86, .5), (-.86, .5)):
        along, across = x * dx + z * dz, abs(x * dz - z * dx)
        if along >= 19 and across <= 3:
            h = 50 + max(0, min(14, int((along - 19) * 14 / 23)))
    return h


def ruined_chapel(w):
    # Authored outcrop, reached by a broad ascending east stair.
    for x in range(-8, 9):
        for z in range(-6, 7):
            if math.hypot(x / 1.4, z) <= 6:
                w.box(x, x, 51, 67, z, z, 'stone')
    for edge in (-5, 5):
        for x in range(-6, 7):
            w.box(x, x, 68, 71 + (x + 6) // 3, edge, edge, 'mossy_stone_bricks')
    w.box(-6, -6, 68, 77, -5, 5, 'stone_bricks')
    w.box(-6, -6, 70, 74, -1, 1, None)
    w.box(-7, -5, 77, 78, -2, 2, 'chiseled_stone_bricks')
    w.box(-6, -6, 79, 81, 0, 0, 'stone_bricks')
    w.box(-8, -4, 79, 79, 0, 0, 'stone_bricks')
    w.box(-5, 6, 67, 67, -4, 4, 'cracked_stone_bricks')
    w.set(-3, 68, -2, 'chest[facing=east]')
    # East ramp begins on the upper quarry shelf, travels north to a landing at chapel height.
    ramp(w.frame(0, 18, 2), 0, 0, 12, bottom=55, rise=12, width=3)
    w.box(-1, 1, 68, 71, 5, 5, None)


def crane(w, bottom):
    for x in (-2, 2):
        for z in (-2, 2):
            w.box(x, x, bottom + 1, bottom + 10, z, z, 'stripped_spruce_log[axis=y]')
    w.box(-3, 9, bottom + 10, bottom + 10, -1, 1, 'spruce_planks')
    w.box(8, 8, bottom + 4, bottom + 9, 0, 0, 'iron_chain[axis=y]')
    w.set(8, bottom + 3, 0, 'barrel[facing=up]')
    ramp(w.frame(-6, -6), 0, 0, 10, bottom=bottom, rise=10, width=3, material='spruce_planks')
    w.box(-6, -2, bottom + 10, bottom + 10, 0, 5, 'spruce_planks')


def make_world():
    w = World()
    # Bridges first; terrain and buildings own their abutments and do not get overwritten by bridge rails.
    for team in TEAMS:
        bridge(w, point(team, 0, 39), (0, -40) if team == 'red' else ((35, 20) if team == 'blue' else (-35, 20)))
    for a, b in [(point('red', 39, 0), OUTPOSTS['east']), (OUTPOSTS['east'], point('blue', -39, 0)),
                 (point('red', -39, 0), OUTPOSTS['west']), (OUTPOSTS['west'], point('yellow', 39, 0)),
                 (point('blue', 39, 0), OUTPOSTS['north']), (OUTPOSTS['north'], point('yellow', -39, 0))]:
        bridge(w, a, b)
    terrace(w, 0, 0, 42, top=64)
    for x in range(-42, 43):
        for z in range(-42, 43):
            h = quarry_height(x, z)
            if h is not None:
                w.box(x, x, h + 1, 67, z, z, None)
                w.set(x, h, z, 'andesite' if h == 50 else 'stone')
    ruined_chapel(w.frame(-24, -15))
    crane(w.frame(25, -13), 60)
    crane(w.frame(-7, 29, 2), 60)
    for x, z in ((-30, 15), (14, -30), (22, 21)):
        w.box(x - 2, x + 2, 61, 63, z - 1, z + 1, 'tuff')
    for name, (x, z) in OUTPOSTS.items():
        terrace(w, x, z, 15)
        # Toll outposts: small open watch house, a camp and space for competing generators.
        house(w.frame(x - 5, z - 5), 'white')
        w.box(x + 3, x + 10, 64, 64, z + 3, z + 10, 'stone_bricks')
    for team, (x, z, turn) in TEAMS.items():
        t = w.frame(x, z, turn)
        terrace(t, 0, 0, 44, town=True)
        # Paved streets connect separated wards; grass courts remain deliberate empty space.
        t.box(-36, 36, 64, 64, -7, 4, 'gravel')
        t.box(-4, 4, 64, 64, -37, 39, 'gravel')
        t.box(-30, -21, 64, 64, -5, 29, 'gravel')
        t.box(21, 29, 64, 64, -32, 8, 'gravel')
        for px, pz, rotation in ((0, 39, 0), (0, -39, 2), (-39, 0, 1), (39, 0, 3)):
            edge = t.frame(px, pz, rotation)
            wall(edge)
            if pz != -39:
                gatehouse(edge, team)
            # Wall access is separate from the gate throat, so stair feet never obstruct the road.
            ramp(edge.frame(21, -13), 0, 0, 7, rise=7)
            edge.box(20, 22, 71, 71, -6, 0, 'stone_bricks')
            edge.box(20, 22, 72, 74, -2, -2, None)
        for u, v in ((-36, -36), (36, -36), (-36, 36), (36, 36)):
            # Corner watch turrets join curtain walkways.
            tower(t.frame(u, v, 2 if v > 0 else 0), team)
        t.box(-7, 13, 64, 64, -5, 1, 'smooth_stone')
        for u, v in PILLARS[:2]:
            monument(t.frame(u, v), team)
        # The keep stands on the town's highest ground, four blocks above the plaza.
        t.box(-12, 12, 65, 68, -39, -15, 'stone_bricks')
        for u, v, rotation in ((0, -10, 2), (-17, -27, 3), (17, -27, 1)):
            ramp(t.frame(u, v, rotation), 0, 0, 8, rise=4, width=5)
        keep(t.frame(0, -27, dy=4), team)
        t.set(6, 77, -21, 'barrel[facing=up]')
        forge(t.frame(-27, 24), team)
        house(t.frame(25, -27), team)
        t.set(32, 73, -18, 'barrel[facing=up]')
        for u, v in ((20, -11), (31, -11), (31, 7)):
            market_stall(t.frame(u, v), team)
        t.box(19, 29, 64, 64, -5, 3, 'smooth_stone')
    data = coordinates()
    # Forge pockets are cut into a continuous rock bank; all remaining nodes sit against terrace faces.
    for cells in data['ore'].values():
        for x, y, z in cells:
            w.set(x, y, z, None)
            w.set(x, y + 1, z, None)
    return w, data


def coordinates():
    data = {'seed': SEED, 'districts': {}, 'spawns': {}, 'pillars': {}, 'shops': {},
            'landmarks': [], 'stars': {}, 'ore': {}, 'climbs': {}, 'exploration_blocks': {}}
    for team in TEAMS:
        data['districts'][team] = {name: list(point(team, *pos)) for name, pos in DISTRICTS.items()}
        data['spawns'][team] = [xyz(team, u, -2) for u in (-4, 0, 4, 10)]
        data['pillars'][team] = [xyz(team, *p, y=69 if i == 2 else 65) for i, p in enumerate(PILLARS)]
        data['shops'][team] = xyz(team, 24, -2)
        for i, (u, v) in enumerate(((20, -2), (25, -2), (25, 3), (20, 3))):
            x, z = point(team, u, v)
            data['stars'][f'base-{team}-{i + 1}'] = [x, 65, z]
            data['landmarks'].append([x, z])
        for name, u, y, v in [('house-store', 23, 65, -30), ('keep-gallery', 5, 77, -21), ('house-roof', 33, 73, -18)]:
            x, z = point(team, u, v)
            data['stars'][f'cache-{team}-{name}'] = [x, y, z]
        for name, u, y, v in [('house-barrel', 22, 65, -30), ('gallery-barrel', 6, 77, -21), ('roof-barrel', 32, 73, -18)]:
            data['exploration_blocks'][f'exploration-block-{team}-{name}'] = xyz(team, u, v, y)
        x, z = point(team, -6, -20)
        data['climbs'][f'{team}-keep-roof'] = [x, 85, z]
        x, z = point(team, 21, 39)
        data['climbs'][f'{team}-wall-walk'] = [x, 72, z]
        tower_sites = [(u, v) for u in (-36, 36) for v in (-36, 36)]
        for u, v, rotation in ((0, 39, 0), (-39, 0, 1), (39, 0, 3)):
            for side in (-7, 7):
                du, dv = side, 0
                for _ in range(rotation):
                    du, dv = -dv, du
                tower_sites.append((u + du, v + dv))
        for i, (u, v) in enumerate(tower_sites):
            data['climbs'][f'{team}-tower-{i + 1}'] = xyz(team, u + 1, v + 1, 77)

    for name, (x, z) in OUTPOSTS.items():
        for i, (dx, dz) in enumerate(((5, 5), (10, 5), (5, 10))):
            data['stars'][f'flank-{name}-{i + 1}'] = [x + dx, 65, z + dz]
        data['landmarks'].append([x + 5, z + 5])
    data['exploration_blocks']['exploration-block-chapel-chest'] = [-27, 68, -17]
    data['stars']['center-wave'] = [0, 51, 0]
    data['center_wave_radius'] = 17
    data['climbs']['chapel'] = [-24, 68, -15]
    data['climbs']['east-crane'] = [25, 71, -13]
    data['climbs']['south-crane'] = [-7, 71, 29]
    # Enumerate ledge cells immediately beside higher rock. Exclude the chapel and ramps/scaffolds.
    used = set()
    def vein(h, count, angle):
        candidates = []
        for x in range(-36, 37):
            for z in range(-36, 37):
                if quarry_height(x, z) != h or (x, z) in used:
                    continue
                if (x + 24) ** 2 + (z + 15) ** 2 < 200 or (x > 15 and z < 0) or (abs(x) < 14 and z > 22):
                    continue
                if any(abs(x - ox) <= 4 and abs(z - oz) <= 3 for ox, oz in ((-30, 15), (14, -30), (22, 21))):
                    continue
                if any((quarry_height(x + dx, z + dz) or 0) > h + 2 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    diff = abs((math.atan2(z, x) - angle + math.pi) % (2 * math.pi) - math.pi)
                    candidates.append((diff, x, z))
        chosen = sorted(candidates)[:count]
        assert len(chosen) == count, (h, count)
        used.update((x, z) for _, x, z in chosen)
        return [[x, h + 1, z] for _, x, z in chosen]
    for name, h, count, angle in [('emerald', 50, 8, -1.1), ('diamond', 50, 10, 1.6),
                                  ('gold', 55, 6, .8), ('heartwood', 55, 2, 2.4), ('silt', 55, 6, -1.3),
                                  ('iron', 60, 11, 2.8), ('timber', 60, 4, -1.5), ('clay', 60, 6, .9)]:
        data['ore'][name] = vein(h, count, angle)
    for team in TEAMS:
        for name, us in [('iron', (-31, -29, -27)), ('timber', (-32, -26)), ('clay', (-30, -28))]:
            for u in us:
                x, z = point(team, u, 16)
                data['ore'][name].append([x, 65, z])
    return data


def stage_yaml(data):
    lines = ['# Highmarch: original floating highland (ADR-0317); generated by tools/map-gen/highmarch.py.',
             '# Town centres red (0,-108), blue (94,54), yellow (-94,54); each plateau radius ~44.',
             '# Local wards (u right, v inward): plaza (0,-2), market (25,-2), forge (-27,24),',
             '# gate court (0,22), inner ward (-25,-4), keep (0,-27), house (25,-27).',
             '# Ward centres are 20+ blocks apart, joined by broad streets; shops face the plaza edge.',
             '# Quarry r42: rim y64 / upper y60 / middle y55 / floor y50; three 7-wide ramps.',
             '# Spawn -> rim ~70-90 blocks; enemy gate court ~180-230 blocks along routes.',
             '# Outposts (85,-50), (-85,-50), (0,112); seven-wide stone bridges over void.',
             '# Keep floor y68 (tier 3 y69); other wards floor y64 (tiers 1/2 y65).',
             '# Four spawns/team, 3 exposed single-block pillars/team; monument crowns are visual only.',
             'id: annihilation-arena', 'display_name_key: astera.stage.annihilation-highmarch.name',
             'layout:', '  marker_version: 9', f'  seed: {SEED}', '  surface_y: 64', '  kill_y: 16',
             '  import: {map: annihilation-highmarch}', '  islands: []', '  bridges: []', '  structures: []', '  landmarks:']
    lines += [f'  - {{kind: generator, x: {x}, z: {z}}}' for x, z in data['landmarks']]
    lines += ['  theme: temperate', '  atmosphere: {time_of_day: 6000, weather: clear}',
              'regions:', '- name: full-arena', '  min: {x: -150.0, y: 16.0, z: -155.0}',
              '  max: {x: 150.0, y: 140.0, z: 150.0}', '- name: center',
              '  min: {x: -16.0, y: 50.0, z: -16.0}', '  max: {x: 16.0, y: 54.0, z: 16.0}']
    # These objective regions also let the existing generic preview resolve the actual enemy tier-1 pillar.
    for team, cells in data['pillars'].items():
        x, y, z = cells[0]
        lines += [f'- name: pillar-1-{team}', f'  min: {{x: {x - .5}, y: {y}.0, z: {z - .5}}}',
                  f'  max: {{x: {x + .5}, y: {y + 2}.0, z: {z + .5}}}']
    lines += ['pillars:']
    for team, cells in data['pillars'].items():
        for tier, (x, y, z) in enumerate(cells, 1):
            block, affinity = [('stripped_dark_oak_wood', 'axe'), ('coarse_dirt', 'shovel'), ('polished_deepslate', 'pickaxe')][tier - 1]
            lines += [f'- team: {team}', f'  tier: {tier}', f'  position: {{x: {x}.0, y: {y}.0, z: {z}.0}}',
                      f'  block: minecraft:{block}', f'  affinity: {affinity}']
    lines += ['spawn_points:']
    for team, cells in data['spawns'].items():
        yaw = {'red': 180.0, 'blue': 270.0, 'yellow': 90.0}[team]
        for x, y, z in cells:
            lines += [f'- team: {team}', f'  position: {{x: {x + .5}, y: {y}.0, z: {z + .5}}}', f'  yaw: {yaw}']
    lines += ['- role: lobby', '  position: {x: 0.5, y: 65.0, z: -43.5}',
              '- role: spectator', '  position: {x: 0.5, y: 110.0, z: 0.5}', '  pitch: 60.0',
              '# Shop NPCs stand on the market edge, within 15 blocks of the nearest plaza spawn.', 'shop_npcs:']
    for team, (x, y, z) in data['shops'].items():
        yaw = {'red': 90.0, 'blue': 180.0, 'yellow': 0.0}[team]
        lines += [f'- {{team: {team}, x: {x + .5}, y: {y}.0, z: {z + .5}, yaw: {yaw}}}']
    # warm_slots stays absent: retain the existing server-owned pool default.
    return '\n'.join(lines) + '\n'


def ore_yaml(data):
    path = ROOT / 'content/ore-nodes/annihilation-arena.yaml'
    original = path.read_text()
    node = None
    lines = []
    for line in original.splitlines(keepends=True):
        match = re.search(r'- block: astera:ore_(\w+)_node', line)
        if match:
            node = match[1]
        if line.lstrip().startswith('positions:'):
            cells = data['ore'][node]
            assert len(re.findall(r'\bx:', line)) == len(cells), f'{node}: node count changed'
            line = '    positions: [' + ', '.join(f'{{ x: {x}, y: {y}, z: {z} }}' for x, y, z in cells) + ']\n'
        lines.append(line)
    return ''.join(lines)


def arena_yaml(w):
    counts = collections.Counter(w.blocks.values())
    palette = sorted(counts, key=lambda s: (-counts[s], s))
    index = {s: i + 1 for i, s in enumerate(palette)}  # token zero is implicit air, never palette[0].
    bounds = [(min(p[i] for p in w.blocks), max(p[i] for p in w.blocks)) for i in range(3)]
    (x0, x1), (y0, y1), (z0, z1) = bounds
    lines = ['# Original work, Copyright (c) 2026 AstarWorks. MIT (see LICENSE).',
             '# Generated by tools/map-gen/highmarch.py; do not edit by hand.', 'id: annihilation-highmarch',
             'credits:', '  title: "Highmarch"', '  authors: ["AstarWorks"]',
             '  source: "tools/map-gen/highmarch.py (original work in this repository)"',
             '  license: "MIT; Copyright (c) 2026 AstarWorks; see LICENSE"',
             '  modified: "Original deterministic voxel world; no third-party map data"',
             f'origin: [{x0}, {y0}, {z0}]', f'size: [{x1 - x0 + 1}, {y1 - y0 + 1}, {z1 - z0 + 1}]', 'palette:']
    lines += ['  - ' + json.dumps(s) for s in palette]
    lines += ['layers:']
    for y in range(y0, y1 + 1):
        tokens = (index.get(w.blocks.get((x, y, z)), 0) for z in range(z0, z1 + 1) for x in range(x0, x1 + 1))
        runs = [f'{token}:{sum(1 for _ in group)}' for token, group in itertools.groupby(tokens)]
        lines.append('  - ' + json.dumps(' '.join(runs)))
    return '\n'.join(lines) + '\n'


def validate(w, data):
    registry = {}
    for line in (ROOT / 'kernel/runtime/src/main/resources/block-registry.txt').read_text().splitlines():
        name, _, raw = line.partition('\t')
        registry[name] = dict(p.split('=') for p in raw.split(',') if p)
    for state in set(w.blocks.values()):
        name, _, raw = state.partition('[')
        assert name in registry, name
        for prop in raw.rstrip(']').split(',') if raw else []:
            key, value = prop.split('=')
            assert value in registry[name].get(key, '').split('|'), state
    for team, spawns in data['spawns'].items():
        for x, y, z in spawns:
            assert (x, y - 1, z) in w.blocks and (x, y, z) not in w.blocks and (x, y + 1, z) not in w.blocks
        sx, _, sz = data['shops'][team]
        assert min(math.hypot(x - sx, z - sz) for x, _, z in spawns) < 15
    for x, z in data['landmarks']:
        assert (x, 64, z) in w.blocks and (x, 65, z) not in w.blocks, (x, z)
    cells = [tuple(cell) for group in data['ore'].values() for cell in group]
    assert len(cells) == len(set(cells)) == 74
    for x, y, z in cells:
        assert (x, y, z) not in w.blocks and (x, y - 1, z) in w.blocks, (x, y, z)
        assert any((x + dx, y, z + dz) in w.blocks for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))), (x, y, z, 'no rock face')
    for pillars in data['pillars'].values():
        for x, y, z in pillars:
            assert (x, y, z) not in w.blocks
            for dx, dz in ((0, 5), (0, -5), (5, 0), (-5, 0)):
                assert (x + dx, y - 1, z + dz) in w.blocks and (x + dx, y, z + dz) not in w.blocks, (x, z, dx, dz)
    assert min(y for _, y, _ in w.blocks) > 16
    assert all(abs(x) < 512 and abs(z) < 512 for x, _, z in w.blocks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify reproducibility without modifying repository files')
    parser.add_argument('--handoff', type=Path, default=Path('/tmp/claude-1000/anni-map/star-spots.txt'))
    args = parser.parse_args()
    start = time.monotonic()
    w, data = make_world()
    validate(w, data)
    outputs = {'content/arenas/annihilation-highmarch.yaml': arena_yaml(w),
               'content/stages/annihilation-arena.yaml': stage_yaml(data),
               'content/ore-nodes/annihilation-arena.yaml': ore_yaml(data)}
    for relative, text in outputs.items():
        path = ROOT / relative
        if args.check:
            assert path.read_text() == text, f'generated output drift: {path}'
        else:
            path.write_text(text)
    args.handoff.parent.mkdir(parents=True, exist_ok=True)
    lines = [f'{name}: {x} {y} {z}' for name, (x, y, z) in data['stars'].items()]
    lines += [f'{name}: {x} {y} {z}' for name, (x, y, z) in data['exploration_blocks'].items()]
    lines += [f'center-wave-max-scatter-radius: {data["center_wave_radius"]}',
              '# Exploration blocks: house barrel/crafting table; keep gallery barrel; roof barrel.',
              '# Suggested block materials: minecraft:barrel, minecraft:chest, minecraft:crafting_table.',
              '# Generator candidates stand on ground; subsequent candidates need landmarks if selected.',
              '# Flank legacy ids retained: east=(85,-50), west=(-85,-50), north=(0,112).']
    args.handoff.write_text('\n'.join(lines) + '\n')
    args.handoff.with_name('coordinates.json').write_text(json.dumps(data, indent=2) + '\n')
    print(json.dumps(data, indent=2))
    print(f'{len(w.blocks):,} blocks; arena {len(outputs["content/arenas/annihilation-highmarch.yaml"].encode()):,} bytes; {time.monotonic() - start:.2f} s')


if __name__ == '__main__':
    main()
