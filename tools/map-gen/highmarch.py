#!/usr/bin/env python3
"""Highmarch: original deterministic voxel architecture, ADR-0317. Python stdlib only."""
import argparse
import collections
import itertools
import json
import math
import random
from pathlib import Path
import re
import time

ROOT = Path(__file__).resolve().parents[2]
SEED = 20261101
TEAMS = {'red': (0, -108, 0), 'blue': (94, 54, 1), 'yellow': (-94, 54, 3)}
OUTPOSTS = {'east': (85, -50), 'west': (-85, -50), 'north': (0, 112)}
PILLARS = [(0, 9), (-25, -4), (0, -27)]
DISTRICTS = {'plaza': (23, 1), 'market': (25, -2), 'forge': (-27, 24),
             'gate-court': PILLARS[0], 'inner-ward': PILLARS[1], 'keep': PILLARS[2],
             'house': (25, -27)}

# u, v, half-width, half-depth, storeys, quarter-turns.
TOWN_HOUSES = [
    (-27, -27, 4, 5, 3, 0), (-29, -18, 5, 2, 1, 0),
    (-14, 11, 4, 4, 2, 1), (-14, 28, 4, 4, 1, 1),
    (14, 28, 4, 5, 2, 3), (28, 28, 5, 5, 1, 0),
    (14, 11, 3, 4, 3, 3), (29, 13, 4, 3, 1, 2)]


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
            state = re.sub(r'shape=(east_west|north_south)',
                           lambda m: 'shape=' + ('north_south' if m[1] == 'east_west' else 'east_west'), state)
            state = re.sub(r'axis=([xz])', lambda m: 'axis=' + ('z' if m[1] == 'x' else 'x'), state)
        if 'rotation=' in state:
            state = re.sub(r'rotation=(\d+)', lambda m: f'rotation={(int(m[1]) + self.turn * 4) % 16}', state)
        self.blocks[x, y, z] = 'minecraft:' + state

    def box(self, x0, x1, y0, y1, z0, z1, state):
        for x, y, z in itertools.product(range(x0, x1 + 1), range(y0, y1 + 1), range(z0, z1 + 1)):
            self.set(x, y, z, state)


# Authored covers that transmit light; all other covers in this map are solid.
TURF_COVERS = {'short_grass', 'fern', 'dandelion', 'azure_bluet', 'oxeye_daisy', 'poppy'}


def turf_open(above):
    if above is None:
        return True
    name = above.removeprefix('minecraft:').split('[')[0]
    return name in TURF_COVERS or name.endswith(('_leaves', '_fence', '_wall', '_wall_sign', '_trapdoor')) or name == 'iron_bars'


def settle_turf(w):
    # Run after all carving/building: roofs, trunks and foundations bury turf.
    # Exposed soil (including under open fences/walls) must never accept grass spread.
    for (x, y, z), state in w.blocks.items():
        if state not in ('minecraft:grass_block', 'minecraft:dirt'):
            continue
        exposed = turf_open(w.blocks.get((x, y + 1, z)))
        if state == 'minecraft:grass_block' and not exposed:
            w.blocks[x, y, z] = 'minecraft:dirt'
        elif state == 'minecraft:dirt' and exposed:
            w.blocks[x, y, z] = 'minecraft:coarse_dirt'


def terrace(w, x, z, radius, top=64, town=False):
    """Layered rock with a tapered, faceted underside; no random single-block speckle."""
    for u in range(-radius, radius + 1):
        for v in range(-radius, radius + 1):
            r = max(abs(u), abs(v), (abs(u) + abs(v)) / 1.9) if town else math.hypot(u, v)
            edge = radius - r
            if edge < 0 or (town and max(abs(u), abs(v)) > 39 and gorge_distance(*w.xz(x + u, z + v)) < 5):
                continue
            w.box(x + u, x + u, top + 1, top + 3, z + v, z + v, None)
            bottom = max(27, top - 18 - int(min(12, edge * .65) + 3 * math.sin(u / 7) * math.cos(v / 9)))
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


def gatehouse(w, team, main=False):
    # A bridgehead supports both tower doors above the gorge.
    w.box(-10, 10, 63, 64, -3, 3, 'stone_bricks')
    for x in (-7, 7):
        tower(w.frame(x, 0), team)
    # Curtain-walk stairs give each gate tower a direct roof approach.
    for side in (-1, 1) if main else ():
        for step in range(6):
            x, h = side * (12 - step), 71 + step
            w.set(x, h, -1, 'stone_bricks')
            w.box(x, x, h + 1, h + 3, -1, -1, None)
    w.box(-4, 4, 65, 73, -2, 2, 'stone_bricks')
    # Open arch is 7 wide at ground, narrows only well above the player's head.
    w.box(-3, 3, 65, 69, -3, 3, None)
    w.box(-2, 2, 70, 70, -3, 3, None)
    w.box(-1, 1, 71, 71, -3, 3, None)
    w.box(-4, 4, 74, 74, -2, 2, 'deepslate_bricks')
    for x, facing in ((-3, 'east'), (3, 'west')):
        w.box(x, x, 70, 70, -2, 2, f'stone_brick_stairs[facing={facing},half=top]')
    w.box(-2, 2, 73, 73, 3, 3, f'{team}_concrete')
    for x in (-4, 4):
        w.set(x, 69, 3, f'{team}_wall_banner[facing=south]')
        w.set(x, 68, 3, 'lantern[hanging=true]')


def monument(w, team, tier=1):
    # Low corner steps keep the chest-height objective approachable on every side.
    r = tier + 3
    w.box(-r, r, 64, 64, -r, r, 'cobbled_deepslate')
    w.box(-r + 1, r - 1, 64, 64, -r + 1, r - 1, 'polished_deepslate')
    # Recess the four-sided attack floor by one: the pinned objective sits at chest height.
    w.box(-2, 2, 64, 64, -2, 2, None)
    w.box(-2, 2, 63, 63, -2, 2, 'chiseled_stone_bricks')
    w.set(0, 64, 0, f'{team}_concrete')
    for x, z in itertools.product((-r, r), repeat=2):
        w.box(min(x, x - (1 if x > 0 else -1)), max(x, x - (1 if x > 0 else -1)),
              65, 65, min(z, z - (1 if z > 0 else -1)), max(z, z - (1 if z > 0 else -1)), 'stone_bricks')
    corner = 3 if tier == 1 else 4
    crown = 73 + tier * 2
    for x, z in itertools.product((-corner, corner), repeat=2):
        foot = 0 if tier == 1 else 1
        w.box(x - foot, x + foot, 65, 65, z - foot, z + foot, 'stone_bricks')
        w.box(x, x, 66, crown - 1, z, z, 'chiseled_stone_bricks')
        w.box(x - 1, x + 1, crown - 2, crown - 1, z - 1, z + 1, f'{team}_concrete')
    # Heavy vaulted canopy, open underneath; a lit clerestory is visible across the rim.
    w.box(-corner - 1, corner + 1, crown, crown, -corner - 1, corner + 1, f'{team}_concrete')
    w.box(-corner, corner, crown + 1, crown + 1, -corner, corner, f'{team}_wool')
    w.box(-2, 2, crown + 2, crown + 2, -2, 2, 'glowstone')
    w.box(-1, 1, crown + 3, crown + 3, -1, 1, f'{team}_concrete')
    w.set(0, crown + 4, 0, f'{team}_banner[rotation=0]')
    for x in (-corner, corner):
        w.set(x, crown - 1, 0, f'{team}_wall_banner[facing=south]')


def spawn_guardhall(w, team):
    # A roofed garrison protects respawns from the keep/T1/side-gate crossfire.
    # Its broad east arcade opens towards the market; no screen divides the star plaza.
    w.box(-6, 7, 64, 70, -9, 2, 'stone_bricks')
    w.box(-5, 6, 65, 69, -8, 1, None)
    w.box(7, 7, 65, 68, -3, -1, None)
    for x, v in itertools.product((-6, 7), (-9, 2)):
        w.box(x, x, 65, 70, v, v, 'stripped_spruce_log[axis=y]')
    for x in (-3, 3):
        for v in (-9, 2):
            w.box(x - 1, x + 1, 67, 68, v, v, 'glass_pane[east=true,west=true]')
    for v in (-6, -2):
        w.box(-6, -6, 67, 68, v, v, 'glass_pane[north=true,south=true]')
    for x in range(-7, 9):
        h = 71 + min(x + 7, 8 - x) // 2
        w.box(x, x, h, h, -10, 3, 'deepslate_tiles')
        for v in (-9, 2):
            w.box(x, x, 71, h - 1, v, v, f'{team}_wool')
    w.set(7, 69, -2, f'{team}_wall_banner[facing=east]')
    w.set(4, 69, 0, 'lantern[hanging=true]')
    w.set(-5, 65, 0, 'barrel[facing=up]')


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
        w.box(x, x, 69, 69, -3, 3, f'{team}_wool' if x % 2 == 0 else (f'{team}_concrete' if team == 'yellow' else 'white_wool'))
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
        w.box(-5, 5, y, y, -5, 5, None)
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
    ramp(w.frame(7, -7), 0, 0, 8, rise=8)
    w.box(6, 8, 72, 72, 1, 7, 'spruce_planks')
    for edge in (-9, 9):
        for u in range(-9, 10, 2):
            w.set(u, 81, edge, 'deepslate_brick_wall')
            w.set(edge, 81, u, 'deepslate_brick_wall')
    for x in (-9, 9):
        for z in (-9, 9):
            w.box(x, x, 80, 84, z, z, f'{team}_concrete')
            w.set(x, 85, z, 'lantern')
    monument(w, team, tier=3)
    for x in (-6, 6):
        w.set(x, 78, 10, f'{team}_wall_banner[facing=south]')


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
            if math.hypot(u, v) <= 42:
                continue
            for y in range(64 - depth, 65):
                material = 'stone_bricks' if y >= 62 else 'andesite'
                if gorge_distance(u, v) >= 6:
                    material = 'coarse_dirt' if y == 64 else 'stone'
                w.set(u, y, v, material)
        for side in (-4, 4):
            u, v = round(x + nx * side), round(z + nz * side)
            if math.hypot(u, v) <= 42:
                continue
            w.set(u, 64, v, 'stone_bricks' if gorge_distance(u, v) < 6 else 'coarse_dirt')
            if gorge_distance(u, v) < 6 and i % 9 not in (4, 5):
                w.set(u, 65, v, 'stone_brick_wall')
    # The diagonal raster has no holes: infill between adjacent deck samples.
    for x in range(min(ax, bx) - 4, max(ax, bx) + 5):
        for z in range(min(az, bz) - 4, max(az, bz) + 5):
            t = ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / distance ** 2
            if 0 <= t <= 1 and math.hypot(x, z) > 42 and abs((x - ax) * nx + (z - az) * nz) <= 3:
                w.set(x, 64, z, 'stone_bricks' if gorge_distance(x, z) < 6 else 'coarse_dirt')
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
    # Extended roofless nave: asymmetric ruined walls and broken ribs, not a solid box.
    w.box(-8, 7, 67, 67, -8, 7, 'cracked_stone_bricks')
    for edge in (-8, 7):
        for x in range(-8, 8):
            h = 70 + (x * 7 + edge) % 5
            w.box(x, x, 68, h, edge, edge, 'mossy_stone_bricks')
            if x % 4 == 0:
                w.box(x, x, 69, 71, edge, edge, None)
    for x in (-5, 1, 6):
        for z in (-7, 6):
            w.box(x, x, 68, 75, z, z, 'stone_bricks')
        for z, h in ((-6, 76), (-5, 77), (5, 76)):
            w.set(x, h, z, 'stone_brick_stairs[facing=south,half=top]')
    # Bell-tower stump: climbable exterior stair reaches a high lookout through the ruin.
    for edge in (-8, -3):
        w.box(edge, edge, 68, 81, -6, -1, 'cracked_stone_bricks')
    w.box(-8, -3, 78, 78, -6, -1, 'spruce_planks')
    w.box(-7, -4, 79, 83, -5, -2, None)
    w.set(-6, 81, -1, 'bell[attachment=ceiling,facing=south]')
    # Cache niche in the tower's east wall: chest on the nave floor, open air above for the star spot.
    w.box(-3, -3, 69, 70, -2, -2, None)
    w.set(-3, 68, -2, 'chest[facing=east]')
    ramp(w.frame(6, -5, 2), 0, 0, 11, bottom=67, rise=11, width=3)
    w.box(-5, 6, 78, 78, -18, -16, 'spruce_planks')
    w.box(-6, -4, 78, 78, -16, -4, 'spruce_planks')
    w.box(-6, -4, 79, 81, -6, -4, None)
    # Original approach and exploration chest coordinates stay fixed.
    ramp(w.frame(0, 18, 2), 0, 0, 12, bottom=55, rise=12, width=3)
    w.box(-1, 1, 68, 71, 5, 7, None)


def crane(w, bottom):
    for x in (-2, 2):
        for z in (-2, 2):
            w.box(x, x, bottom + 1, bottom + 10, z, z, 'stripped_spruce_log[axis=y]')
    w.box(-3, 9, bottom + 10, bottom + 10, -1, 1, 'spruce_planks')
    w.box(8, 8, bottom + 4, bottom + 9, 0, 0, 'iron_chain[axis=y]')
    w.set(8, bottom + 3, 0, 'barrel[facing=up]')
    ramp(w.frame(-6, -6), 0, 0, 10, bottom=bottom, rise=10, width=3, material='spruce_planks')
    w.box(-6, -2, bottom + 10, bottom + 10, 0, 5, 'spruce_planks')


def townhouse(w, team, width=4, depth=4, stories=1):
    top = 65 + stories * 4
    w.box(-width, width, 64, 64, -depth, depth, 'stone_bricks')
    for y in range(65, top):
        material = 'stripped_spruce_log[axis=y]' if (y - 65) % 4 == 0 else 'spruce_planks'
        for edge in (-depth, depth):
            w.box(-width, width, y, y, edge, edge, material)
        for edge in (-width, width):
            w.box(edge, edge, y, y, -depth, depth, material)
    for y in range(67, top, 4):
        for x in (-width + 1, width - 1):
            for z in (-depth, depth):
                w.set(x, y, z, 'glass_pane[east=true,west=true]')
                w.set(x, y - 1, z, f'{team}_wool')
    w.box(-1, 1, 65, 67, depth, depth, None)
    w.set(2, 67, depth + 1, 'lantern[hanging=true]')
    for x in range(-width - 1, width + 2):
        h = top + width - abs(x)
        for z in (-depth, depth):
            w.box(x, x, top, h - 1, z, z, f'{team}_wool')
        w.box(x, x, h, h, -depth - 1, depth + 1,
              f'deepslate_tile_stairs[facing={"east" if x < 0 else "west"}]')
    for level in range(1, stories):
        y = 64 + level * 4
        w.box(-width + 1, width - 1, y, y, -depth + 1, depth - 1, 'spruce_planks')
    # Interior stair spine reaches each upper storey with three blocks of headroom.
    if stories > 1:
        route = [(x, -depth + 1) for x in range(-width + 1, width)]
        route += [(width - 1, z) for z in range(-depth + 2, depth)]
        for i, (x, z) in enumerate(route[:(stories - 1) * 4 + 1]):
            w.set(x, 64 + i, z, 'spruce_planks')
            w.box(x, x, 65 + i, 67 + i, z, z, None)
    w.set(-width + 1, 65, depth - 1, 'barrel[facing=up]')


def town_fabric(w, team):
    # Streets and lanes replace the lawn; a five-wide street separates the southern blocks.
    for x0, x1, z0, z1 in [(-35, -9, -36, -16), (9, 35, 10, 35),
                            (-20, -9, 7, 35), (-35, -22, 7, 12)]:
        w.box(x0, x1, 64, 64, z0, z1, 'coarse_dirt')
    for z in (-17, 20, 35):
        w.box(-34, 34, 64, 64, z - 2, z + 2, 'gravel')
    for u, v, width, depth, stories, rotation in TOWN_HOUSES:
        townhouse(w.frame(u, v, rotation), team, width, depth, stories)
    # Barracks insignia beside the main gate and a storehouse with loading crates.
    w.set(20, 71, 28, f'{team}_wall_banner[facing=east]')
    for x in (24, 27, 30):
        w.set(x, 65, 35, 'barrel[facing=up]')
        w.set(x, 66, 35, 'hay_block')
    # Well square northwest of spawn leaves the broad east-west street unobstructed.
    well = w.frame(0, -8)
    well.box(-16, -9, 64, 64, -6, 3, 'stone_bricks')
    well.box(-14, -11, 65, 65, -3, 0, 'stone_bricks')
    well.box(-13, -12, 65, 65, -2, -1, 'water')
    for x in (-14, -11):
        well.box(x, x, 66, 68, -2, -2, 'spruce_fence')
    well.box(-15, -10, 69, 69, -3, 0, 'spruce_slab[type=bottom]')
    # Small kitchen gardens, bounded by paths rather than the old open lawn.
    for x0, z0 in ((9, -34), (-18, 35), (24, 36)):
        for x in range(x0, x0 + 6):
            for z in range(z0, z0 + 2):
                w.set(x, 64, z, 'farmland[moisture=0]')
                w.set(x, 65, z, 'wheat[age=7]')
    # Inner ward terrace wraps a recessed, eight-block-clear objective court.
    w.box(-35, -15, 65, 66, -14, 6, 'stone_bricks')
    w.box(-33, -17, 65, 69, -12, 4, None)
    w.box(-33, -17, 64, 64, -12, 4, 'polished_andesite')
    for u, v, rotation in ((-25, 9, 2), (-12, -4, 1), (-25, -17, 0)):
        ramp(w.frame(u, v, rotation), 0, 0, 3, rise=2, width=5)
    monument(w.frame(-25, -4), team, tier=2)


def ruined_watchtower(w):
    # Yard wall, broad opposite gaps for both bridge routes, and an open star court.
    for edge in (-12, 12):
        w.box(-12, 12, 65, 67, edge, edge, 'mossy_stone_bricks')
        w.box(edge, edge, 65, 67, -12, 12, 'cracked_stone_bricks')
        w.box(-3, 3, 65, 68, edge, edge, None)
        w.box(edge, edge, 65, 68, -3, 3, None)
    w.box(-10, 11, 64, 64, -10, 11, 'coarse_dirt')
    for edge in (-9, -1):
        w.box(-9, -1, 65, 75, edge, edge, 'mossy_stone_bricks')
        w.box(edge, edge, 65, 75, -9, -1, 'stone_bricks')
    w.box(-6, -4, 65, 68, -1, -1, None)
    w.box(-9, -9, 70, 75, -7, -4, None)
    w.box(-8, -5, 73, 75, -9, -9, None)
    w.box(-1, -1, 70, 72, -6, -5, None)
    w.box(-8, -2, 75, 75, -8, -2, 'spruce_planks')
    for x, z, h in ((-9, -9, 79), (-1, -9, 77), (-9, -1, 76)):
        w.box(x, x, 76, h, z, z, 'cracked_stone_bricks')
    # Outside dogleg steps, ending at an open gap in the broken parapet.
    ramp(w.frame(-11, 2, 3), 0, 0, 11, rise=11, width=3)
    w.box(-1, 1, 75, 75, -3, 2, 'spruce_planks')
    w.box(-1, -1, 76, 78, -3, -1, None)
    for x, z in ((-5, 7), (6, -6), (-8, 3)):
        w.box(x - 1, x + 1, 65, 66, z - 1, z + 1, 'cobbled_deepslate')


def quarry_detail(w, data):
    # Lateral bays/spurs alter the silhouettes of terrace edges, preserving the pinned
    # outer rim, central scatter disk, spoke ramps and ore faces.
    protected = {(x, z) for group in data['ore'].values() for x, _, z in group}
    for x in range(-41, 42):
        for z in range(-41, 42):
            h = quarry_height(x, z)
            if h is None:
                continue
            r = math.hypot(x, z)
            angle = math.atan2(z, x)
            near_ore = any(abs(x - ox) <= 2 and abs(z - oz) <= 2 for ox, oz in protected)
            on_spoke = any(x * dx + z * dz >= 18 and abs(x * dz - z * dx) <= 5
                           for dx, dz in ((0, -1), (.86, .5), (-.86, .5)))
            if not near_ore and not on_spoke and 21 < r < 38:
                warped = r + 1.8 * math.sin(5 * angle) + 1.1 * math.sin(9 * angle + .6)
                h = 55 if warped <= 28 else 60 if warped <= 35 else 64
                w.box(x, x, h + 1, 67, z, z, None)
                w.box(x, x, 49, h, z, z, 'stone')
            # Irregular deposits continue down exposed terrace faces. Height and
            # collision stay fixed; no radial material bands or isolated speckles.
            for y in range(h - 4, h + 1):
                patch = math.sin(x / 6 + .7 * math.sin(z / 8)) + math.cos(z / 5 - x / 11 + (y - h) / 5)
                material = ('coarse_dirt' if patch > 1.05 else 'gravel' if patch > .45
                            else 'stone' if patch > -.25 else 'andesite' if patch > -.8
                            else 'tuff' if patch > -1.4 else 'cobbled_deepslate')
                w.set(x, y, z, material)
            if near_ore:
                for y in range(h - 2, h):
                    w.set(x, y, z, 'cobbled_deepslate')
    # Floor cover stays beyond the full radius-17 scatter disk; every shelf has cover.
    for x, z in ((-16, -9), (18, 0), (-14, 12), (5, -18),
                 (-33, 8), (29, 7), (9, 30), (-16, -31), (34, -19), (-33, -21)):
        h = quarry_height(x, z)
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if math.hypot(max(0, abs(x + dx) - .5), max(0, abs(z + dz) - .5)) <= 17 or any(abs(x + dx - ox) <= 3 and abs(z + dz - oz) <= 3 for ox, oz in protected):
                    continue
                w.box(x + dx, x + dx, h + 1, h + 2 + (abs(dx) + abs(dz) < 2), z + dz, z + dz, 'tuff')
    # Abandoned haul tracks and voxel minecart tubs (the import stores blocks, not entities).
    for x, z, rotation, h in ((-18, 22, 1, 55), (18, -29, 1, 60), (33, 3, 0, 60)):
        rail = w.frame(x, z, rotation)
        for u in range(-4, 5):
            rail.box(u, u, 50, h, 0, 0, 'stone')
            rail.set(u, h, 0, 'spruce_planks')
            rail.set(u, h + 1, 0, 'rail[shape=east_west]')
        rail.box(-1, 1, h + 2, h + 2, -1, 1, 'iron_trapdoor[half=bottom]')
        for v, facing in ((-1, 'north'), (1, 'south')):
            rail.box(-1, 1, h + 3, h + 3, v, v, f'iron_trapdoor[facing={facing},open=true]')
        rail.set(0, h + 3, 0, 'cobbled_deepslate')


def lane_distance(x, z, a, b):
    ax, az = a; bx, bz = b
    t = max(0, min(1, ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / ((bx - ax) ** 2 + (bz - az) ** 2)))
    return math.hypot(x - ax - t * (bx - ax), z - az - t * (bz - az))


def ridge_routes():
    return [(point('red', 39, 0), OUTPOSTS['east']), (OUTPOSTS['east'], point('blue', -39, 0)),
            (point('red', -39, 0), OUTPOSTS['west']), (OUTPOSTS['west'], point('yellow', 39, 0)),
            (point('blue', 39, 0), OUTPOSTS['north']), (OUTPOSTS['north'], point('yellow', -39, 0))]


def gorge_centre(angle):
    # r63..67: even the inner bank leaves >=16 blocks beyond the r42 rim.
    return 65 + 2 * math.cos(3 * angle)


def gorge_distance(x, z):
    radius = math.hypot(x, z)
    return min(abs(radius - gorge_centre(math.atan2(z, x))),
               lane_distance(x, z, (-61, -25), (-144, -25)),
               lane_distance(x, z, (61, -25), (144, -25)))


def gorge_exits(w):
    # Bank ladders at <=25-block intervals, on BOTH banks. No extra bridge across the river.
    for degrees in range(0, 360, 20):
        angle = math.radians(degrees)
        dx, dz = math.cos(angle), math.sin(angle)
        centre = gorge_centre(angle)
        for radius, outward in ((centre - 3, -1), (centre + 3, 1)):
            x, z = round(dx * radius), round(dz * radius)
            sx, sz = (1 if dx > 0 else -1, 0) if abs(dx) > abs(dz) else (0, 1 if dz > 0 else -1)
            sx *= outward; sz *= outward
            facing = {(1, 0): 'west', (-1, 0): 'east', (0, 1): 'north', (0, -1): 'south'}[sx, sz]
            w.box(x + sx, x + sx, 56, 64, z + sz, z + sz, 'stone')
            w.box(x, x, 57, 64, z, z, f'ladder[facing={facing}]')
            w.box(x, x, 65, 66, z, z, None)
            w.box(x + sx, x + sx, 65, 66, z + sz, z + sz, None)
    for side in (-1, 1):
        for x in range(75, 140, 24):
            for z, facing in ((-22, 'south'), (-28, 'north')):
                w.box(side * x, side * x, 56, 64, z + (1 if z == -22 else -1), z + (1 if z == -22 else -1), 'stone')
                w.box(side * x, side * x, 57, 64, z, z, f'ladder[facing={facing}]')


def tree(w, x, h, z, species, size):
    """Vanilla-style layered crowns, clipped corners and branch-supported overhangs."""
    rng = random.Random(SEED + x * 73856093 + z * 19349663 + size)
    wide = size == 10 and species in ('dark_oak', 'spruce', 'oak')
    width = 2 if wide else 1
    log = f'{species}_log[axis=y]'
    for dx, dz in itertools.product(range(width), repeat=2):
        w.box(x + dx, x + dx, h + 1, h + size, z + dz, z + dz, log)

    def leaves(cx, cy, cz, radius):
        for dx, dz in itertools.product(range(-radius, radius + 1), repeat=2):
            # Circular cross-sections with seeded corner/edge omissions, never square slabs.
            if dx * dx + dz * dz > (radius + .25) ** 2:
                continue
            if abs(dx) + abs(dz) >= radius * 2 - 1 and rng.random() < .35:
                continue
            p = x + cx + dx, h + cy, z + cz + dz
            if p not in w.blocks:
                w.set(*p, f'{species}_leaves[persistent=true]')

    if species == 'spruce':
        for dy in range(3, size + 2):
            radius = min(4 if wide else 3, 1 + (size - dy + 1) // 2)
            if dy % 2 == 0 and dy < size - 1:
                radius = max(1, radius - 1)
            leaves(0, dy, 0, max(0, radius))
        if wide:
            for dx, dz in ((-2, 0), (3, 1), (0, -2), (1, 3)):
                w.set(x + dx // 2, h + 5, z + dz // 2, log)
                leaves(dx, 5, dz, 2)
    else:
        radius = 3 if size >= 7 else 2
        for dy, r in ((size - 3, radius - 1), (size - 2, radius),
                      (size - 1, radius), (size, radius - 1), (size + 1, 1)):
            leaves(0, dy, 0, r)
            if wide:
                leaves(1, dy, 1, r)
        if size >= 7:
            # Ascending limbs carry separate lobes beyond the trunk's crown.
            for dx, dz in ((-3, 1), (2, -3), (3, 2)):
                dy = size - rng.randint(2, 3)
                for i in range(1, 4):
                    w.set(x + round(dx * i / 3), h + dy - 2 + i // 2,
                          z + round(dz * i / 3), log)
                for layer, r in ((-1, 1), (0, 2), (1, 2), (2, 1)):
                    leaves(dx, dy + layer, dz, r)


def underside(w):
    """Reshape the completed footprint, including town plinths, below playable ground."""
    tops = {}
    original_bottoms = {}
    for x, y, z in w.blocks:
        if y <= 64:
            tops[x, z] = max(tops.get((x, z), y), y)
            original_bottoms[x, z] = min(original_bottoms.get((x, z), y), y)
    # Distance from the actual silhouette includes the squared town corners.
    distance = {}
    queue = collections.deque()
    neighbours = ((1, 0), (-1, 0), (0, 1), (0, -1))
    for x, z in tops:
        if any((x + dx, z + dz) not in tops for dx, dz in neighbours):
            distance[x, z] = 0
            queue.append((x, z))
    while queue:
        x, z = queue.popleft()
        for dx, dz in neighbours:
            p = x + dx, z + dz
            if p in tops and p not in distance:
                distance[p] = distance[x, z] + 1
                queue.append(p)
    bottoms = {}
    for (x, z), surface in tops.items():
        if w.blocks.get((x, 56, z)) == 'minecraft:water':
            surface = min(surface, 56)
        d = distance[x, z]
        noise = 1.8 * math.sin(x / 8 + math.sin(z / 12)) + 1.4 * math.cos(z / 7 - x / 15)
        bottom = max(20, min(surface - 3, round(61 - .29 * d + noise)))
        bottoms[x, z] = bottom
        cap = min(60, surface - 3)
        for y in range(17, cap + 1):
            if y < bottom:
                w.set(x, y, z, None)
                continue
            existing = w.blocks.get((x, y, z))
            if y >= original_bottoms[x, z] and (existing is None or existing.split('[')[0].removeprefix('minecraft:') not in
                    ('stone', 'andesite', 'tuff', 'deepslate', 'dirt', 'gravel')):
                continue
            band = y + round(2 * math.sin(x / 13) + 2 * math.cos(z / 17))
            material = ('deepslate' if band < 33 else 'tuff' if band % 13 < 3
                        else 'andesite' if band % 13 < 6 else 'stone')
            if y >= surface - 5:
                material = 'dirt'
            if y == bottom and (x * 37 + z * 61) % 263 == 0:
                material = 'coal_ore' if y > 40 else 'deepslate_iron_ore'
            w.set(x, y, z, material)
    rng = random.Random(SEED + 42)
    # Block-built hanging spires taper on every layer instead of extruding columns.
    for (x, z), bottom in bottoms.items():
        if not 8 < distance[x, z] < 65 or rng.random() > .004:
            continue
        length = rng.randint(5, 10)
        for drop in range(length):
            radius = 3 * (1 - drop / length)
            for dx in range(-3, 4):
                for dz in range(-3, 4):
                    y = bottom - drop
                    if y > 18 and dx * dx + dz * dz <= radius * radius:
                        w.set(x + dx, y, z + dz, 'tuff' if drop < 3 else 'deepslate')
    for (x, z), surface in tops.items():
        if distance[x, z] != 0:
            continue
        if w.blocks.get((x, surface, z)) == 'minecraft:grass_block' and rng.random() < .2:
            # Roots cling to the overhang and bend sideways as they descend.
            length = rng.randint(3, 7)
            for drop in range(length):
                dx = min(drop, length - 2) // 3 * (1 if x > 0 else -1)
                w.set(x + dx, surface - 3 - drop, z, 'hanging_roots' if drop == length - 1 else 'oak_log[axis=y]')
        # The two river outlets spill beyond the rim; no new water across routes.
        if w.blocks.get((x, 56, z)) == 'minecraft:water' and abs(z + 25) <= 2 and abs(x) > 135:
            for y in range(20, 56):
                w.set(x, y, z, 'water[level=8]')


def continent(w):
    lanes = [(point(t, 0, 39), end) for t, end in
             [('red', (0, -40)), ('blue', (35, 20)), ('yellow', (-35, 20))]]
    ridges = ridge_routes()
    heights = {}
    for x in range(-149, 150):
        for z in range(-158, 140):
            # Add coherent rocky headlands without removing any of the existing land.
            edge = ((1 - math.hypot(x / 146, (z + 9) / 149)) * 146
                    + max(0, 2 * math.sin(x / 8) + math.cos(z / 11)))
            if edge < 0:
                continue
            road = min(lane_distance(x, z, a, b) for a, b in lanes)
            ridge = min(lane_distance(x, z, a, b) for a, b in ridges)
            # Broad cone-shaped hills, each rise capped to one block per horizontal cell.
            hill = max(0, *(6 - max(abs(x - hx), abs(z - hz)) // 2
                            for hx, hz in ((-49, -89), (49, -89), (-79, 0), (79, 0), (-39, 88), (39, 88))))
            hill = max(hill, max(0, 3 - int(abs(math.hypot(x, z) - 51) / 2)))
            # Continuous low-frequency relief, with a one-block-per-cell envelope
            # around protected streets and outpost courts. No coordinate moves.
            relief = int(4 + 2.4 * math.sin(x / 18 + math.sin(z / 31)) + 2 * math.cos(z / 21 - x / 39))
            town_clearance = min(max(abs(x - tx), abs(z - tz)) - 45 for tx, tz, _ in TEAMS.values())
            outpost_clearance = min(math.hypot(x - ox, z - oz) - 18 for ox, oz in OUTPOSTS.values())
            h = 64 + max(0, min(max(hill, relief), int(road - 7), int(ridge - 5),
                                int(town_clearance), int(outpost_clearance)))
            # Quarry carving owns y<=67: preserve its original rim relief, so
            # the outer-hill pass cannot leave floating turf above the excavated pit.
            if math.hypot(x, z) <= 55:
                h = 64 + min(hill, max(0, int(road - 11)), max(0, int(ridge - 6)))
            d = gorge_distance(x, z)
            if d < 5:
                h = 55 if d < 3 else 56 + int((d - 3) * 4)
            if 3 <= d < 11 and math.hypot(x, z) > 54:
                h = 56 + int(d - 3)
            heights[x, z] = h
            depth = 20 + int(3 * math.sin(x / 7 + math.sin(z / 13)) + 2 * math.cos(z / 9))
            bottom = max(27, h - depth - int(min(12, edge * .65)))
            for y in range(bottom, h + 1):
                material = 'deepslate' if y < 40 else 'tuff' if y in (43, 44, 56) else 'stone'
                if edge < 6 and h - 13 < y < h - 2 and math.sin(x / 5 + y / 7) + math.cos(z / 6) > 1:
                    material = 'gravel'
                if y >= h - 2:
                    material = 'dirt'
                if y == h:
                    material = 'gravel' if d < 5 else 'grass_block'
                    if edge < 4:
                        patch = math.sin(x / 4) + math.cos(z / 5)
                        material = 'stone' if patch > 1 else 'coarse_dirt' if patch > -.4 else 'grass_block'
                    if road < 5 or ridge < 5:
                        material = 'coarse_dirt' if road < 3 or ridge < 3 else 'gravel'
                w.set(x, y, z, material)
            if d < 3:
                w.set(x, 56, z, 'water')
    # Rejection sampling against a smooth grove field avoids rows and preserves clearings.
    rng = random.Random(SEED)
    planted = []
    for _ in range(2700):
        x, z = rng.randint(-144, 144), rng.randint(-155, 136)
        h = heights.get((x, z))
        if h is None or math.hypot(x, z) < 55 or gorge_distance(x, z) < 12:
            continue
        if any(max(abs(x - tx), abs(z - tz)) < 49 for tx, tz, _ in TEAMS.values()):
            continue
        if any(math.hypot(x - ox, z - oz) < 22 for ox, oz in OUTPOSTS.values()):
            continue
        if min(lane_distance(x, z, a, b) for a, b in lanes) < 12 or min(lane_distance(x, z, a, b) for a, b in ridges) < 10:
            continue
        grove = math.sin(x / 13 + math.sin(z / 23)) + math.cos(z / 17 - x / 29)
        if grove < -.35 or any(math.hypot(x - px, z - pz) < 7 for px, pz in planted):
            continue
        size = rng.choice((5, 7, 10))
        species = rng.choice(('oak', 'birch', 'spruce', 'dark_oak'))
        tree(w, x, h, z, species, size)
        planted.append((x, z))
    # Groundcover follows the same grove field, leaving broad battle clearings.
    for (x, z), h in heights.items():
        if h < 64 or math.hypot(x, z) < 55 or w.blocks.get((x, h, z)) != 'minecraft:grass_block':
            continue
        if any(max(abs(x - tx), abs(z - tz)) < 46 for tx, tz, _ in TEAMS.values()):
            continue
        if any(math.hypot(x - ox, z - oz) < 20 for ox, oz in OUTPOSTS.values()):
            continue
        if min(lane_distance(x, z, a, b) for a, b in lanes + ridges) < 6 or (x, h + 1, z) in w.blocks:
            continue
        chance = rng.random()
        grove = math.sin(x / 13 + math.sin(z / 23)) + math.cos(z / 17 - x / 29)
        if chance < .15:
            w.set(x, h + 1, z, 'fern' if grove > 0 else 'short_grass')
        elif chance < .17:
            w.set(x, h + 1, z, rng.choice(('dandelion', 'poppy', 'azure_bluet', 'oxeye_daisy')))
        elif chance < .173 and grove > 0:
            for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                ground = heights.get((x + dx, z + dz), h)
                if (x + dx, ground + 1, z + dz) not in w.blocks:
                    w.set(x + dx, ground + 1, z + dz, 'oak_leaves[persistent=true]')
        elif chance < .174 and grove > 0:
            for dx in range(-2, 3):
                ground = heights.get((x + dx, z), h)
                w.set(x + dx, ground + 1, z, 'oak_log[axis=x]')
        elif chance < .175:
            for dx, dz in ((0, 0), (1, 0), (0, 1), (-1, 0)):
                ground = heights.get((x + dx, z + dz), h)
                w.set(x + dx, ground + 1, z + dz, 'mossy_cobblestone')
            w.set(x, h + 2, z, 'andesite')
    # A recessed stair in the west outer bank gives a dry, bot-walkable
    # escape from the channel as well as the regular ladders. It crosses no water.
    angle = math.radians(180)
    dx, dz = math.cos(angle), math.sin(angle)
    centre = gorge_centre(angle)
    for step in range(18):
        radius = centre + 1 + step
        h = 56 + min(8, step // 2)
        for side in range(-1, 2):
            x, z = round(dx * radius - dz * side), round(dz * radius + dx * side)
            w.box(x, x, 55, h, z, z, 'andesite')
            w.box(x, x, h + 1, 67, z, z, None)
    # Ridge roads cross the outlets on solid natural rock arches; they pass through the outposts.
    for a, b in ridges:
        for x in range(min(a[0], b[0]) - 4, max(a[0], b[0]) + 5):
            for z in range(min(a[1], b[1]) - 4, max(a[1], b[1]) + 5):
                if lane_distance(x, z, a, b) <= 3:
                    w.box(x, x, 61, 63, z, z, 'stone')
                    w.set(x, 64, z, 'coarse_dirt')
                    w.box(x, x, 65, 67, z, z, None)
    # Worn winding tracks inside the broad walkable corridors. Their edges blend
    # into gravel and turf; the unobstructed corridor keeps the original route budget.
    for a, b in lanes + ridges:
        length = math.dist(a, b)
        nx, nz = -(b[1] - a[1]) / length, (b[0] - a[0]) / length
        for x in range(min(a[0], b[0]) - 7, max(a[0], b[0]) + 8):
            for z in range(min(a[1], b[1]) - 7, max(a[1], b[1]) + 8):
                t = ((x - a[0]) * (b[0] - a[0]) + (z - a[1]) * (b[1] - a[1])) / length ** 2
                if not 0 <= t <= 1 or math.hypot(x, z) < 55:
                    continue
                across = (x - a[0]) * nx + (z - a[1]) * nz
                d = abs(across - 2.2 * math.sin(t * math.pi * 2) * math.sin(t * math.pi))
                h = heights.get((x, z))
                if h is None or h != 64 or gorge_distance(x, z) < 6:
                    continue
                if (x, z) in ((-88, -25), (88, -25)):
                    continue
                if d < 2.3:
                    w.set(x, h, z, 'dirt_path' if math.sin(x / 3) + math.cos(z / 4) > -.5 else 'coarse_dirt')
                elif d < 4.5:
                    patch = math.sin(x / 2 + z / 3)
                    w.set(x, h, z, 'gravel' if patch > .3 else 'coarse_dirt' if patch > -.4 else 'grass_block')
        for t, side in ((.28, -7), (.62, 7), (.83, -7)):
            x, z = (round(a[i] + (b[i] - a[i]) * t + n * side) for i, n in enumerate((nx, nz)))
            h = heights.get((x, z))
            if h is None or h < 64 or gorge_distance(x, z) < 12:
                continue
            if any(max(abs(x - tx), abs(z - tz)) < 48 for tx, tz, _ in TEAMS.values()):
                continue
            if any(math.hypot(x - ox, z - oz) < 20 for ox, oz in OUTPOSTS.values()):
                continue
            w.box(x, x, h + 1, h + 3, z, z, 'spruce_fence')
            w.set(x, h + 4, z, 'lantern')
            w.set(x + 1, h + 2, z, 'spruce_wall_sign[facing=east]')
            w.set(x - 2, h + 1, z, 'barrel[facing=up]')
            for dx in range(-3, 1):
                w.set(x + dx, h + 1, z + 2, 'mossy_cobblestone_wall')
    # Low walls, boulders and abandoned wagons on the wide approach shoulders.
    for a, b in lanes:
        distance = math.dist(a, b)
        nx, nz = -(b[1] - a[1]) / distance, (b[0] - a[0]) / distance
        for t, side in ((.12, -8), (.26, 8), (.86, -9)):
            x, z = (round(a[i] + (b[i] - a[i]) * t + n * side) for i, n in enumerate((nx, nz)))
            if any(gorge_distance(x + dx, z + dz) < 6 for dx in range(-2, 3) for dz in range(-2, 3)):
                continue
            w.box(x - 2, x + 2, 65, 66, z, z + 1, 'cobblestone')
        x, z = (round(a[i] + (b[i] - a[i]) * .18 + n * 9) for i, n in enumerate((nx, nz)))
        w.box(x - 1, x + 1, 65, 65, z - 2, z + 2, 'spruce_planks')
        for u in (x - 2, x + 2):
            w.box(u, u, 65, 66, z - 1, z + 1, 'dark_oak_log[axis=z]')


def valley_cover(w):
    underside(w)
    # Ruined wayside shrines occupy the broad shoulder before each crossing.
    # All are off the seven-wide through-route and outside the fixed town wards.
    for x, z in ((-14, -62), (41, 53), (-41, 53)):
        t = w.frame(x, z)
        t.box(-2, 2, 64, 64, -2, 2, 'mossy_stone_bricks')
        t.box(-2, -2, 65, 67, -2, 1, 'mossy_cobblestone')
        t.box(-2, 1, 65, 66, -2, -2, 'stone_bricks')
        t.box(1, 1, 65, 68, -2, -2, 'chiseled_stone_bricks')
        t.set(0, 65, 0, 'chiseled_stone_bricks')
        t.set(0, 66, 0, 'lantern')
        # Boulder and a loaded wagon form separate pieces of low cover.
        t.box(5, 7, 65, 66, 0, 2, 'andesite')
        t.set(6, 67, 1, 'tuff')
        t.box(-1, 1, 65, 65, 5, 8, 'spruce_planks')
        for u in (-2, 2):
            t.box(u, u, 65, 66, 6, 7, 'dark_oak_log[axis=z]')
        t.set(0, 66, 6, 'barrel[facing=up]')
        # Small mixed groves bring forest cover up to the valley shoulder.
        for u, v, species in ((-5, 0, 'oak'), (-5, 7, 'spruce'), (4, 8, 'birch')):
            px, pz = x + u, z + v
            if gorge_distance(px, pz) < 8 or (px, 65, pz) in w.blocks:
                continue
            tree(w, px, 64, pz, species, 5)

    # Inner-bank groves and low rock cover make the rim-to-river land band
    # readable at preview scale while keeping all three seven-wide lanes open.
    for degrees in range(0, 360, 30):
        angle = math.radians(degrees)
        x, z = round(51 * math.cos(angle)), round(51 * math.sin(angle))
        if any(lane_distance(x, z, point(team, 0, 39), end) < 7
               for team, end in [('red', (0, -40)), ('blue', (35, 20)), ('yellow', (-35, 20))]):
            continue
        h = max(y for y in range(64, 68) if (x, y, z) in w.blocks)
        species = ('oak', 'birch', 'spruce')[degrees // 30 % 3]
        tree(w, x, h, z, species, 5)
        w.box(x + 3, x + 4, 65, 66, z, z + 1, 'tuff')


def town_character(w, team):
    # Palette swaps affect this town's existing fabric, preserving all interiors and access stairs.
    replacements = {
        'red': {'stone_bricks': 'deepslate_bricks', 'spruce_planks': 'bricks', 'coarse_dirt': 'blackstone'},
        'blue': {'stone_bricks': 'calcite', 'spruce_planks': 'smooth_quartz', 'deepslate_bricks': 'prismarine',
                 'deepslate_tile_stairs': 'prismarine_brick_stairs', 'coarse_dirt': 'smooth_stone'},
        'yellow': {'stone_bricks': 'oak_planks', 'spruce_planks': 'oak_planks',
                   'deepslate_tile_stairs': 'oak_stairs', 'deepslate_bricks': 'stripped_oak_log'}}[team]
    cx, cz, turn = TEAMS[team]
    for (x, y, z), state in list(w.blocks.items()):
        if max(abs(x - cx), abs(z - cz)) > 43 or y < 64:
            continue
        name, bracket, props = state.removeprefix('minecraft:').partition('[')
        if name in replacements:
            w.blocks[x, y, z] = 'minecraft:' + replacements[name] + (bracket + props if bracket else '')
    t = w.frame(cx, cz, turn)
    # Dogleg barbicans bend the flank routes through cover, preserving the travel-time budget.
    for u, v, rotation in ((-39, 0, 1), (39, 0, 3), (0, 39, 0)):
        gate = t.frame(u, v, rotation)
        if (u, v) == (0, 39):
            gate = gate.frame(0, -14)
        width = 11 if (u, v) == (0, 39) else 8
        gate.box(-width, width, 61, 63, 3, 11, 'stone')
        gate.box(-width, width, 64, 64, 3, 11, 'coarse_dirt')
        gate.box(-width, width, 65, 68, 3, 11, None)
        for side in (-width, width):
            gate.box(side, side, 65, 67, 3, 11, 'stone_bricks')
        overlap = 5 if (u, v) == (0, 39) else 2
        gate.box(-width, overlap, 65, 66, 5, 5, 'stone_bricks')
        gate.box(-overlap, width, 65, 66, 8, 8, 'stone_bricks')
    # Every ore-quarter gate has the harbour's service opening: equal flank access.
    t.box(-36, -36, 65, 67, 6, 8, None)
    if team == 'red':
        for u, v in ((-27, -27), (-14, 28), (28, 28)):
            t.box(u - 4, u - 2, 65, 85, v - 1, v + 1, 'deepslate_bricks')
            t.set(u - 3, 86, v, 'campfire[lit=true,signal_fire=true]')
            t.set(u, 65, v + 2, 'blast_furnace[facing=south,lit=true]')
        for u in range(-34, -21):
            t.set(u, 64, 33, 'lava')
            t.box(u, u, 65, 66, 32, 32, 'iron_bars[east=true,west=true]')
            t.box(u, u, 65, 66, 34, 34, 'iron_bars[east=true,west=true]')
        for u in (-31, -27, -23):
            t.set(u, 65, 29, 'anvil[facing=east]')
        t.box(-35, -35, 65, 66, 32, 34, 'blackstone')
        t.box(-21, -21, 65, 66, 32, 34, 'blackstone')
        ramp(t, -35, 21, 12, rise=12, width=1, material='deepslate_bricks')
    elif team == 'blue':
        # Harbour basin and quay on the outer edge, away from fixed cache/keep stairs.
        t.box(-32, -15, 62, 63, -43, -35, 'water')
        t.box(-32, -15, 64, 67, -43, -35, None)
        t.box(-33, -14, 61, 61, -44, -34, 'prismarine')
        for u in (-33, -14):
            t.box(u, u, 62, 64, -44, -34, 'calcite')
        t.box(-33, -14, 64, 64, -34, -34, 'smooth_stone')
        for u in (-28, -20):
            boat = t.frame(u, -39)
            boat.box(-2, 2, 64, 64, -1, 1, 'spruce_planks')
            for side in (-2, 2):
                boat.set(side, 65, 0, 'spruce_trapdoor[open=true]')
            boat.box(0, 0, 65, 69, 0, 0, 'oak_fence')
            boat.box(1, 2, 67, 69, 0, 0, 'white_wool')
        # Lantern cupola on the east corner of the keep; the pinned roof route remains open.
        t.box(6, 8, 85, 91, -35, -33, 'calcite')
        t.box(6, 8, 92, 94, -35, -33, 'glass')
        t.set(7, 93, -34, 'sea_lantern')
        t.box(5, 9, 95, 95, -36, -32, 'prismarine')
        t.set(7, 96, -34, 'blue_banner[rotation=0]')
    else:
        # Timber-frame barn and a full field beside the western farm ward.
        for u in range(-34, -20):
            for v in range(31, 38):
                t.set(u, 64, v, 'farmland[moisture=0]')
                t.set(u, 65, v, 'wheat[age=7]')
        for u, v in ((-32, 28), (-22, 28), (31, 35)):
            t.box(u, u + 2, 65, 66, v, v + 1, 'hay_block')
        for u, v, width, depth, _, rotation in TOWN_HOUSES:
            barn = t.frame(u, v, rotation)
            for a in (-width, width):
                for b in (-depth, depth):
                    barn.box(a, a, 65, 69, b, b, 'stripped_oak_log[axis=y]')
        # Four cloth sails on a working-height windmill, above the rear farm house.
        t.box(-29, -25, 77, 86, -29, -25, 'oak_planks')
        t.box(-30, -24, 87, 87, -30, -24, 'yellow_wool')
        for d in range(-7, 8):
            t.set(-27 + d, 83, -21, 'stripped_oak_log[axis=x]')
            t.set(-27, 83 + d, -21, 'stripped_oak_log[axis=y]')
            if abs(d) > 2:
                t.set(-27 + d, 84, -21, 'white_wool')
                t.set(-26, 83 + d, -21, 'white_wool')
        t.box(-27, -27, 83, 83, -25, -21, 'stripped_oak_log[axis=z]')


def quarry_finish(w, data):
    # Replace rock, never add collision: 64 ore/raw blocks per pocket, plus a
    # broad coloured mineral cap on the lip. Overlapping pockets merge as veins.
    colours = {'iron': ('iron_ore', 'raw_iron_block', 'calcite'),
               'gold': ('gold_ore', 'raw_gold_block', 'yellow_concrete'),
               'diamond': ('deepslate_diamond_ore', 'diamond_block', 'light_blue_concrete'),
               'emerald': ('emerald_ore', 'emerald_block', 'lime_concrete')}
    rock = {'minecraft:' + name for name in ('stone', 'andesite', 'tuff', 'gravel',
            'dirt', 'coarse_dirt', 'cobbled_deepslate')}
    original = dict(w.blocks)
    for name, materials in colours.items():
        for x, y, z in data['ore'][name]:
            if math.hypot(x, z) > 43:
                continue
            candidates = []
            for dx, dy, dz in itertools.product(range(-5, 6), range(-2, 5), range(-5, 6)):
                pos = x + dx, y + dy, z + dz
                if original.get(pos) not in rock:
                    continue
                # Only visible faces and lips; ignore buried concentric strata.
                if not any((pos[0] + a, pos[1] + b, pos[2] + c) not in original
                           for a, b, c in ((0, 1, 0), (1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1))):
                    continue
                candidates.append((dx * dx + dy * dy + dz * dz, pos))
            candidates.sort()
            for i, (_, pos) in enumerate(candidates[:64]):
                w.set(*pos, materials[0] if i % 5 == 0 else materials[1])
    # Caps are applied after all face seams, so neighbouring pockets cannot
    # overwrite their shared colour. Use the upper lip rather than buried rock.
    for name, materials in colours.items():
        for x, y, z in data['ore'][name]:
            if math.hypot(x, z) > 43:
                continue
            lips = []
            for dx, dz in itertools.product(range(-6, 7), repeat=2):
                for h in range(y - 1, y + 5):
                    pos = x + dx, h, z + dz
                    if original.get(pos) in rock and (pos[0], h + 1, pos[2]) not in original:
                        lips.append((dx * dx + dz * dz, pos))
            for _, pos in sorted(lips)[:12]:
                w.set(*pos, materials[2])
    # Exposed ends of the same seams extend onto unobscured lips/floor. These
    # broad patches stay legible in a whole-quarry iso even behind crane roofs.
    for name, x, z in (('lapis', -9, -16),):
        materials = colours.get(name, ('lapis_ore', 'lapis_block', 'blue_concrete'))
        for dx, dz in itertools.product(range(-3, 4), range(-2, 3)):
            if dx * dx / 10 + dz * dz / 5 > 1 or (dx, dz) == (2, -1):
                continue
            for h in range(64, 49, -1):
                pos = x + dx, h, z + dz
                if original.get(pos) in rock and (pos[0], h + 1, pos[2]) not in original:
                    w.set(*pos, materials[2])
                    if original.get((pos[0], h - 1, pos[2])) in rock:
                        w.set(pos[0], h - 1, pos[2], materials[1])
                    break
    # Lapis seam at the deep south face, beside the diamond working.
    for x in range(-7, 1):
        for z in range(20, 22):
            for y in range(51, 55):
                pos = x, y, z
                if original.get(pos) in rock:
                    w.set(*pos, 'lapis_block' if y < 53 else 'blue_concrete')
    # Shallow pool beyond the scatter disk. One-block submerged steps on every side.
    for x in range(18, 25):
        for z in range(-5, 5):
            if ((x - 21) / 4) ** 2 + (z / 5) ** 2 <= 1:
                w.set(x, 49, z, 'gravel')
                w.set(x, 50, z, 'water')
                w.box(x, x, 51, 59, z, z, None)
    for x in range(24, 30):
        h = 50 + x - 24
        w.box(x, x, 49, h, -4, 4, 'gravel')
        w.box(x, x, h + 1, 59, -4, 4, None)
    # Tower spire visible above y64 banks from all three gates; existing lookout stays at y79.
    for y in range(82, 99):
        r = 2 if y < 91 else 1 if y < 96 else 0
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if max(abs(dx), abs(dz)) == r:
                    w.set(-30 + dx, y, -19 + dz, 'mossy_stone_bricks' if y < 91 else 'deepslate_bricks')
    w.set(-30, 99, -19, 'glowstone')


def make_world():
    w = World()
    continent(w)
    # Bridges first; terrain and buildings own their abutments and do not get overwritten by bridge rails.
    for team in TEAMS:
        bridge(w, point(team, 0, 39), (0, -40) if team == 'red' else ((35, 20) if team == 'blue' else (-35, 20)))
    terrace(w, 0, 0, 42, top=64)
    for x in range(-42, 43):
        for z in range(-42, 43):
            h = quarry_height(x, z)
            if h is not None:
                w.box(x, x, h + 1, 67, z, z, None)
                w.set(x, h, z, 'andesite' if h == 50 else 'stone')
    quarry_detail(w, coordinates())
    ruined_chapel(w.frame(-24, -15))
    crane(w.frame(25, -13), 60)
    crane(w.frame(-7, 29, 2), 60)
    crane(w.frame(20, 20, 1), 55)
    # Smaller timber scaffold on the west shelf, with a stair and loading deck.
    scaffold = w.frame(-34, 3)
    for u in (-2, 2):
        for v in (-2, 2):
            scaffold.box(u, u, 61, 68, v, v, 'spruce_fence')
    scaffold.box(-3, 3, 68, 68, -3, 3, 'spruce_planks')
    ramp(scaffold.frame(5, -8), 0, 0, 8, bottom=60, rise=8, material='spruce_planks')
    scaffold.box(2, 5, 68, 68, -1, 1, 'spruce_planks')
    for x, z in ((-30, 15), (14, -30), (22, 21)):
        w.box(x - 2, x + 2, 61, 63, z - 1, z + 1, 'tuff')
    for name, (x, z) in OUTPOSTS.items():
        terrace(w, x, z, 15)
        ruined_watchtower(w.frame(x, z))
    for team, (x, z, turn) in TEAMS.items():
        t = w.frame(x, z, turn)
        terrace(t, 0, 0, 44, town=True)
        # Main streets stay broad; the later housing pass fills blocks between them.
        t.box(-36, 36, 64, 64, -7, 4, 'gravel')
        t.box(-4, 4, 64, 64, -37, 39, 'gravel')
        t.box(-30, -21, 64, 64, -5, 29, 'gravel')
        t.box(21, 29, 64, 64, -32, 8, 'gravel')
        for px, pz, rotation in ((0, 39, 0), (0, -39, 2), (-39, 0, 1), (39, 0, 3)):
            edge = t.frame(px, pz, rotation)
            wall(edge)
            if pz != -39:
                gatehouse(edge, team, main=pz == 39)
            # Wall access is separate from the gate throat, so stair feet never obstruct the road.
            ramp(edge.frame(21, -13), 0, 0, 7, rise=7)
            edge.box(20, 22, 71, 71, -6, 0, 'stone_bricks')
            edge.box(20, 22, 72, 74, -2, -2, None)
        for u, v in ((-36, -36), (36, -36), (-36, 36), (36, 36)):
            # Corner watch turrets join curtain walkways.
            tower(t.frame(u, v, 2 if v > 0 else 0), team)
        t.box(-7, 13, 64, 64, -5, 1, 'smooth_stone')
        for tier, (u, v) in enumerate(PILLARS[:2], 1):
            monument(t.frame(u, v), team, tier)
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
        town_fabric(t, team)
        monument(t.frame(*PILLARS[0]), team, tier=1)
        # Front court: T1 is 29 blocks behind the first gate entry, with a shorter
        # defender approach from the roofed guardhall. Low cover leaves both lanes open.
        t.box(-7, 7, 64, 64, 16, 26, 'gravel')
        for u in (-7, 7):
            t.box(u, u, 65, 66, 19, 22, 'stone_bricks')
        town_character(w, team)
        spawn_guardhall(t, team)
        # The wider gate court meets the barracks' original interior stair.
        for step in range(5):
            t.set(10, 64 + step, 31 - step, 'spruce_planks')
            t.box(10, 10, 65 + step, 67 + step, 31 - step, 31 - step, None)
    # Recovery flights join the existing north bridge; they do not span a
    # second crossing. Bots can leave the channel without retracing its ring.
    for side in (-1, 1):
        for step in range(9):
            x, h = side * (13 - step), 56 + step
            w.box(x, x, 55, h, -65, -63, 'andesite')
            w.box(x, x, h + 1, 67, -65, -63, None)
    # A fallen rock breaks the otherwise straight swimming line in the south.
    w.box(-2, 2, 55, 59, 60, 63, 'andesite')
    valley_cover(w)
    # Banks and bridge soffits must not overwrite the recovery shafts.
    gorge_exits(w)
    data = coordinates()
    # Rasterised copies can land one cell inside the terrace face. Extend that
    # face inward instead of moving the ore away from its rotational anchor.
    pockets = {tuple(p) for cells in data['ore'].values() for p in cells}
    for x, y, z in sorted(pockets):
        if math.hypot(x, z) > 43:
            continue
        sides = [(x + dx, y, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        if not any(p in w.blocks and p not in pockets for p in sides):
            bx, _, bz = max((p for p in sides if p not in pockets), key=lambda p: math.hypot(p[0], p[2]))
            w.box(bx, bx, y - 1, y + 1, bz, bz, 'cobbled_deepslate')
    quarry_finish(w, data)
    # Forge pockets are cut into a continuous rock bank; all remaining nodes sit against terrace faces.
    for cells in data['ore'].values():
        for x, y, z in cells:
            w.set(x, y, z, None)
            w.set(x, y + 1, z, None)
    # Adjacent veins must leave a real cardinal mining stance after runtime ore
    # placement. Cut only a blocked shoulder, never another node or a full lane.
    for x, y, z in sorted(pockets):
        sides = [(x + dx, y, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        def stance(p):
            u, h, v = p
            return p not in pockets and (u, h - 1, v) in w.blocks and p not in w.blocks and (u, h + 1, v) not in w.blocks
        if not any(stance(p) for p in sides):
            bx, _, bz = min((p for p in sides if p not in pockets), key=lambda p: math.hypot(p[0], p[2]))
            w.set(bx, y - 1, bz, 'cobbled_deepslate')
            w.box(bx, bx, y, y + 2, bz, bz, None)
    settle_turf(w)
    return w, data


def coordinates():
    data = {'seed': SEED, 'districts': {}, 'spawns': {}, 'pillars': {}, 'shops': {},
            'landmarks': [], 'stars': {}, 'ore': {}, 'climbs': {}, 'exploration_blocks': {}}
    for team in TEAMS:
        data['districts'][team] = {name: list(point(team, *pos)) for name, pos in DISTRICTS.items()}
        data['spawns'][team] = [xyz(team, u, v) for u, v in ((0, -8), (-2, -8), (-2, -6), (-4, -6))]
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
        for i, (u, v, width, depth, stories, rotation) in enumerate(TOWN_HOUSES):
            if stories == 1:
                continue
            route = [(a, -depth + 1) for a in range(-width + 1, width)]
            route += [(width - 1, b) for b in range(-depth + 2, depth)]
            du, dv = route[(stories - 1) * 4]
            for _ in range(rotation):
                du, dv = -dv, du
            data['climbs'][f'{team}-townhouse-{i + 1}'] = xyz(team, u + du, v + dv, 65 + (stories - 1) * 4)
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
    data['climbs']['chapel-bell-lookout'] = [-29, 79, -20]
    for name, (x, z) in OUTPOSTS.items():
        data['climbs'][f'outpost-{name}-lookout'] = [x - 5, 76, z - 5]
    data['climbs']['east-crane'] = [25, 71, -13]
    data['climbs']['south-crane'] = [-7, 71, 29]
    # Author one irregular vein per type, rotate its cells about Mid by 120°.
    # The square voxel lattice requires nearest-cell rounding (<= .71 block).
    # Select the whole orbit together: every copy must occupy the same ledge
    # and avoid the chapel, cranes, pool and ramp footprints.
    used = set()
    def orbit(x, z):
        return [(round(x * math.cos(a) - z * math.sin(a)),
                 round(x * math.sin(a) + z * math.cos(a)))
                for a in (0, 2 * math.pi / 3, 4 * math.pi / 3)]
    def vein(h, count, angle):
        candidates = []
        for x in range(-36, 37):
            for z in range(-36, 0):
                if not -math.pi * 5 / 6 <= math.atan2(z, x) < -math.pi / 6:
                    continue
                copies = orbit(x, z)
                if any(quarry_height(u, v) != h or (u, v) in used for u, v in copies):
                    continue
                if h == 50 and any(any(abs(u * dz - v * dx) <= 5 and u * dx + v * dz >= 17
                               for dx, dz in ((0, -1), (.86, .5), (-.86, .5))) for u, v in copies):
                    continue
                if any((u + 24) ** 2 + (v + 15) ** 2 < 200 or
                       (u > 15 and -22 < v < 10) or (abs(u) < 14 and v > 22) or
                       any(abs(u - ox) <= 4 and abs(v - oz) <= 3
                           for ox, oz in ((-30, 15), (14, -30), (22, 21))) for u, v in copies):
                    continue
                if all(any((quarry_height(u + dx, v + dz) or 0) > h + 2
                           for dx, dz in ((2, 0), (-2, 0), (0, 2), (0, -2))) for u, v in copies):
                    candidates.append((abs(math.atan2(z, x) - angle), x, z))
        cells = []
        for _, x, z in sorted(candidates):
            copies = orbit(x, z)
            if any(p in used for p in copies):
                continue
            used.update(copies)
            cells.extend([[u, h + 1, v] for u, v in copies])
            if len(cells) == count * 3:
                break
        assert len(cells) == count * 3, (h, count, cells)
        return cells
    for name, h, count, angle in [('emerald', 50, 3, -1.25), ('diamond', 50, 3, -1.9),
                                  ('gold', 55, 2, -1.25), ('heartwood', 55, 1, -1.7), ('silt', 55, 2, -1.9),
                                  ('iron', 60, 4, -1.25), ('timber', 60, 1, -1.7), ('clay', 60, 2, -1.9)]:
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
             '# Local wards (u right, v inward): plaza (23,1), market (25,-2), forge (-27,24),',
             '# gate court (0,9), inner ward (-25,-4), keep (0,-27), house (25,-27).',
             '# Objective wards have broad streets; four roofed guardhall spawns face the market through an east arcade.',
             '# Quarry r42: rim y64 / upper y60 / middle y55 / floor y50; three 7-wide ramps.',
             '# Spawn -> rim ~70-90 blocks; enemy gate court ~165-230 blocks along routes.',
             '# One continent: wooded valleys to Mid; seven-wide stone bridges cross the river gorge.',
             '# Outposts (85,-50), (-85,-50), (0,112) lie on covered flank ridge paths.',
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
        yaw = {'red': 270.0, 'blue': 0.0, 'yellow': 180.0}[team]
        for x, y, z in cells:
            lines += [f'- team: {team}', f'  position: {{x: {x + .5}, y: {y}.0, z: {z + .5}}}', f'  yaw: {yaw}']
    lines += ['- role: lobby', '  position: {x: 0.5, y: 65.0, z: -43.5}',
              '- role: spectator', '  position: {x: 0.5, y: 110.0, z: 0.5}', '  pitch: 60.0',
              '# Shop NPCs remain at the market edge, east of the spawn guardhall.', 'shop_npcs:']
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
    for x, z in data['landmarks']:
        assert (x, 64, z) in w.blocks and (x, 65, z) not in w.blocks, (x, z)
    cells = [tuple(cell) for group in data['ore'].values() for cell in group]
    assert len(cells) == len(set(cells)) == 75
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
    # Validate the actual authored world, including regenerated terrain and all fixed supply anchors.
    for name, (x, y, z) in data['stars'].items():
        assert (x, y - 1, z) in w.blocks and (x, y, z) not in w.blocks and (x, y + 1, z) not in w.blocks, name
    for x in range(-146, 147):
        for z in range(-158, 140):
            if math.hypot(x / 146, (z + 9) / 149) <= 1:
                assert any((x, y, z) in w.blocks for y in range(27, 65)), (x, z, 'continent hole')
    assert all(any(s.startswith(f'minecraft:{species}_leaves') for s in w.blocks.values()) for species in ('oak', 'spruce', 'birch'))
    assert sum(s.startswith('minecraft:ladder') for s in w.blocks.values()) >= 300
    for x, z in ((21, 0), (0, -65), (0, 65), (-120, -25), (120, -25)):
        y = 50 if (x, z) == (21, 0) else 56
        assert w.blocks[x, y, z] == 'minecraft:water', (x, y, z)
        assert (x, y - 1, z) in w.blocks
    for degrees in range(0, 360, 20):
        angle = math.radians(degrees)
        dx, dz = math.cos(angle), math.sin(angle)
        for radius in (gorge_centre(angle) - 3, gorge_centre(angle) + 3):
            x, z = round(dx * radius), round(dz * radius)
            assert all(w.blocks.get((x, y, z), '').startswith('minecraft:ladder') for y in range(57, 65)), (x, z, 'broken climb-out')
            assert (x, 65, z) not in w.blocks and (x, 66, z) not in w.blocks
    gaps = []
    for bank in (-3, 3):
        points = [(math.cos(math.radians(d)) * (gorge_centre(math.radians(d)) + bank),
                   math.sin(math.radians(d)) * (gorge_centre(math.radians(d)) + bank)) for d in range(361)]
        gaps.extend(sum(math.dist(points[d], points[d + 1]) for d in range(start, start + 20))
                    for start in range(0, 360, 20))
    assert max(gaps) <= 30
    for side in (-1, 1):
        for x in range(75, 140, 24):
            for z in (-22, -28):
                assert all(w.blocks.get((side * x, y, z), '').startswith('minecraft:ladder') for y in range(57, 65))
                assert (side * x, 65, z) not in w.blocks and (side * x, 66, z) not in w.blocks
        assert w.blocks.get((side * 88, 56, -25)) == 'minecraft:water'
        assert w.blocks.get((side * 88, 64, -25)) == 'minecraft:coarse_dirt'
    print(f'Climb-out spacing: ring maximum {max(gaps):.2f}, outlets maximum 24 blocks; both ridge crossings intact')
    # Integration geometry assertions inspect the fully assembled world, so later
    # town/bridge passes cannot silently restore the old moat or bury a crossing.
    for x in range(-54, 55):
        for z in range(-54, 55):
            if 42 < math.hypot(x, z) <= 54:
                assert (x, 64, z) in w.blocks and w.blocks[x, 64, z] != 'minecraft:water', (x, z, 'broken 12-wide land band')
    for team, end in [('red', (0, -40)), ('blue', (35, 20)), ('yellow', (-35, 20))]:
        start = point(team, 0, 39)
        distance = math.dist(start, end)
        samples = [(end[0] + (start[0] - end[0]) * i / 1000,
                    end[1] + (start[1] - end[1]) * i / 1000) for i in range(1001)]
        centre = min(samples, key=lambda p: abs(math.hypot(*p) - gorge_centre(math.atan2(p[1], p[0]))))
        along = sum(centre[i] * (start[i] - end[i]) / distance for i in range(2))
        inner = next(p for p in samples if gorge_distance(*p) < 5)
        rim = next(p for p in samples if math.hypot(*p) >= 42)
        band = math.dist(inner, rim)
        assert 58 <= along <= 72 and band >= 12, (team, along, band)
        x, z = map(round, centre)
        assert w.blocks.get((x, 64, z)) in {'minecraft:stone_bricks', 'minecraft:deepslate_bricks', 'minecraft:calcite', 'minecraft:oak_planks'}, (team, 'missing bridge deck')
        assert w.blocks.get((x, 56, z)) == 'minecraft:water', (team, 'buried channel')
        print(f'{team} bridge centre x={centre[0]:.2f} z={centre[1]:.2f}: lane={along:.2f}, radius={math.hypot(*centre):.2f}, dry band={band:.2f}')
    tops = collections.Counter()
    for (x, y, z), state in w.blocks.items():
        if math.hypot(x, z) <= 42 and 50 <= y <= 64 and (x, y + 1, z) not in w.blocks:
            tops[state] += 1
    for name in ('coarse_dirt', 'gravel', 'tuff', 'andesite', 'cobbled_deepslate'):
        assert tops['minecraft:' + name] >= 30, (name, 'quarry material lost')
    print('Exposed quarry geology:', {name.removeprefix('minecraft:'): count for name, count in tops.items()})
    # A ray from the outside of each gate to the spire must hit only the spire itself.
    for team in TEAMS:
        x, z = point(team, 0, 43)
        start, end = (x, 67, z), (-30, 99, -19)
        for i in range(1, 200):
            p = tuple(round(a + (b - a) * i / 200) for a, b in zip(start, end))
            assert p not in w.blocks or math.dist(p, end) < 3, (team, p, 'spire hidden from gate')


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
