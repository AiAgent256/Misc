"""The commoners' buildings that grow up around a castle: cottages, a pasture, a mine, a mill, a well,
a chapel, a lumber yard, a quarry, a dock. Each is one small template placed at runtime on the real
terrain by holding/Fief.java, plus a JSON saying where the beds and workstations are.

Pieces are built with their ground floor at y = GL (standing level); everything below is foundation
the placer sinks into the ground. The "front" (door side) is +z; the placer rotates toward the castle.
"""
import json
import os

import numpy as np

from grid import Grid, Mix, gable_roof, disk, ring
from parts import (STONE, DARK, FOUND, PLANK, DARK_PLANK, ROOF_STAIR, STONE_STAIR, GLASS, building, lamp_post)
import export

LOOT = "stormhold:chests/"
TIMBER_WALL = Mix(("minecraft:spruce_planks", 60), ("minecraft:stripped_spruce_log[axis=y]", 15), ("minecraft:mud_bricks", 25))
WATTLE = Mix(("minecraft:mud_bricks", 55), ("minecraft:packed_mud", 30), ("minecraft:spruce_planks", 15))
THATCH = "minecraft:hay_block"
THATCH_STAIR = "minecraft:oak_stairs"


def _base(W, D, H=18, GL=4, seed=1):
    g = Grid(W, H, D, seed)
    g.fill(0, 0, 0, W - 1, GL - 2, D - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, D - 1, "minecraft:grass_block")
    return g


def _cottage(g, x0, z0, x1, z1, GL, h, door, beds, station, timber=True, thatch=False):
    """A small house with door on +z, beds and a workstation inside."""
    roof = THATCH_STAIR if thatch else ROOF_STAIR
    building(g, x0, z0, x1, z1, GL, h, "x", door_cells=(door,), wall_block=TIMBER_WALL if timber else WATTLE,
             chimney=(x0 + 1, z0 + 1), timber=False, roof=roof)
    if thatch:
        # swap the deepslate roof fill for straw
        for x in range(x0 - 1, x1 + 2):
            for z in range(z0 - 1, z1 + 2):
                for y in range(GL + h, GL + h + 8):
                    if g.get(x, y, z) in ("minecraft:deepslate_tiles",):
                        g.set(x, y, z, THATCH)
    g.fill(x0 + 1, GL - 1, z0 + 1, x1 - 1, GL - 1, z1 - 1, PLANK)
    for (bx, bz, f) in beds:
        g.bed(bx, GL, bz, f, "white")
        g.mark("bed", bx, GL, bz)
    if station:
        sx, sz, block = station
        g.set(sx, GL, sz, block)
        g.mark("station", sx, GL, sz)
    g.set(door[0], GL - 1, door[1] + 1, "minecraft:dirt_path")
    g.mark("door", door[0], GL, door[1] + 1)


def cottage_a():
    """Farmer's cottage: one room, two beds, a composter."""
    W, D, GL = 9, 9, 4
    g = _base(W, D, GL=GL, seed=31)
    _cottage(g, 1, 1, 7, 7, GL, 4, (4, 7), [(2, 2, "south"), (6, 2, "south")], (2, 6, "minecraft:composter[level=0]"), thatch=True)
    g.barrel(6, GL, 6, LOOT + "town_house", facing="west")
    g.set(5, GL, 6, "minecraft:crafting_table")
    g.set(4, GL, 2, "minecraft:flower_pot")
    return g, {"name": "cottage_a", "kind": "cottage", "profession": "farmer", "GL": GL, "size": [W, D]}


def cottage_b():
    """Shepherd's cottage with a loom, bigger, stone footing."""
    W, D, GL = 11, 9, 4
    g = _base(W, D, GL=GL, seed=32)
    _cottage(g, 1, 1, 9, 7, GL, 5, (5, 7), [(2, 2, "south"), (8, 2, "south"), (2, 5, "east")], (8, 6, "minecraft:loom[facing=north]"))
    g.fill(1, GL, 1, 9, GL, 1, DARK)
    g.barrel(7, GL, 6, LOOT + "town_house", facing="north")
    g.set(5, GL, 2, "minecraft:white_wool")
    g.set(6, GL, 2, "minecraft:white_wool")
    return g, {"name": "cottage_b", "kind": "cottage", "profession": "shepherd", "GL": GL, "size": [W, D]}


def cottage_c():
    """Butcher's house: smoker, hanging hooks, two beds."""
    W, D, GL = 9, 11, 4
    g = _base(W, D, GL=GL, seed=33)
    _cottage(g, 1, 1, 7, 8, GL, 4, (4, 8), [(2, 2, "south"), (6, 2, "south")], (2, 7, "minecraft:smoker[facing=east,lit=false]"), timber=False)
    g.set(6, GL, 7, "minecraft:water_cauldron[level=2]")
    g.set(6, GL + 2, 5, "minecraft:chain[axis=y]")
    g.set(6, GL + 1, 5, "minecraft:chain[axis=y]")
    g.barrel(2, GL, 5, LOOT + "castle_kitchen", facing="east")
    # a small pen behind the house
    for x in range(1, 8):
        g.set(x, GL, 9, "minecraft:oak_fence")
    g.fill(0, GL, 9, 0, GL, 10, "minecraft:oak_fence")
    g.fill(8, GL, 9, 8, GL, 10, "minecraft:oak_fence")
    g.set(4, GL, 9, "minecraft:oak_fence_gate[facing=south]")
    return g, {"name": "cottage_c", "kind": "cottage", "profession": "butcher", "GL": GL, "size": [W, D]}


def cottage_d():
    """Fletcher's cottage: fletching table, targets outside."""
    W, D, GL = 9, 9, 4
    g = _base(W, D, GL=GL, seed=34)
    _cottage(g, 1, 1, 7, 6, GL, 4, (4, 6), [(2, 2, "south"), (6, 2, "south")], (6, 5, "minecraft:fletching_table"), thatch=True)
    g.set(1, GL, 8, "minecraft:target")
    g.set(7, GL, 8, "minecraft:target")
    g.barrel(2, GL, 5, LOOT + "castle_store", facing="east")
    return g, {"name": "cottage_d", "kind": "cottage", "profession": "fletcher", "GL": GL, "size": [W, D]}


def pasture():
    """A fenced field with a byre, water troughs and hay. Animals are spawned by the placer."""
    W, D, GL = 17, 15, 4
    g = _base(W, D, GL=GL, seed=41)
    for x in range(0, W):
        g.set(x, GL, 0, "minecraft:oak_fence")
        g.set(x, GL, D - 1, "minecraft:oak_fence")
    for z in range(0, D):
        g.set(0, GL, z, "minecraft:oak_fence")
        g.set(W - 1, GL, z, "minecraft:oak_fence")
    g.set(8, GL, D - 1, "minecraft:oak_fence_gate[facing=south]")
    g.mark("door", 8, GL, D)
    # byre in the back corner
    building(g, 1, 1, 7, 5, GL, 3, "x", door_cells=((4, 5),), wall_block=WATTLE, roof=THATCH_STAIR)
    for x in range(0, 9):
        for z in range(0, 7):
            for y in range(GL + 3, GL + 9):
                if g.get(x, y, z) == "minecraft:deepslate_tiles":
                    g.set(x, y, z, THATCH)
    g.fill(2, GL, 2, 3, GL, 2, "minecraft:hay_block[axis=y]")
    g.set(6, GL, 2, "minecraft:water_cauldron[level=3]")
    g.set(12, GL, 3, "minecraft:water_cauldron[level=3]")
    g.set(13, GL, 3, "minecraft:water_cauldron[level=3]")
    g.fill(13, GL, 10, 14, GL + 1, 11, "minecraft:hay_block[axis=y]")
    g.set(3, GL, 11, "minecraft:composter[level=2]")
    # the grass inside gets muddy
    g.fill(1, GL - 1, 6, W - 2, GL - 1, D - 2, Mix(("minecraft:grass_block", 55), ("minecraft:coarse_dirt", 25), ("minecraft:mud", 10), ("minecraft:dirt_path", 10)))
    g.mark("station", 11, GL, 7, ) if False else None
    g.mark("animals", 8, GL, 8)
    return g, {"name": "pasture", "kind": "pasture", "profession": "leatherworker", "GL": GL, "size": [W, D],
               "animals": ["cow", "cow", "sheep", "sheep", "sheep", "pig", "pig", "chicken", "chicken"]}


def mine():
    """A mine head: timbered entrance shed, a shaft down with ore in the walls, rails and a cart."""
    W, D, GL = 13, 13, 4
    g = _base(W, D, H=24, GL=GL, seed=51)
    # headframe
    building(g, 3, 1, 9, 6, GL, 4, "z", door_cells=((6, 6),), wall_block=Mix(("minecraft:spruce_planks", 50), ("minecraft:cobblestone", 50)))
    g.set(4, GL, 2, "minecraft:blast_furnace[facing=east,lit=false]")
    g.set(8, GL, 2, "minecraft:smithing_table")
    g.mark("station", 8, GL, 2)
    g.chest(8, GL, 4, "west", LOOT + "castle_smithy")
    # the shaft: a stepped tunnel going down and back
    depth = GL - 2
    x = 6
    for i in range(8):
        y = depth - i
        z = 4 - i if i < 3 else 1
        g.fill(5, y, 2, 7, y + 2, 4, "minecraft:air") if i == 0 else None
    # simpler: vertical shaft with ladder, 10 deep, a gallery at the bottom
    for y in range(0, GL):
        g.fill(5, y, 3, 7, y, 4, "minecraft:air")
    g.fill(5, -0, 3, 7, GL - 1, 4, "minecraft:air")
    g.fill(6, 0, 3, 6, GL - 1, 3, "minecraft:ladder[facing=south]")
    g.set(6, GL, 3, "minecraft:ladder[facing=south]")
    g.fill(5, GL - 1, 3, 7, GL - 1, 4, "minecraft:air")
    g.fill(5, GL - 1, 5, 7, GL - 1, 5, "minecraft:spruce_trapdoor[facing=north,half=bottom,open=false]")
    # gallery at the bottom (y 0..2) running east with ore in the walls
    g.fill(2, 0, 3, 11, 2, 4, "minecraft:air")
    g.fill(2, 0, 2, 11, 0, 2, Mix(("minecraft:stone", 55), ("minecraft:coal_ore", 20), ("minecraft:iron_ore", 20), ("minecraft:copper_ore", 5)))
    g.fill(2, 0, 5, 11, 2, 5, Mix(("minecraft:stone", 55), ("minecraft:coal_ore", 20), ("minecraft:iron_ore", 20), ("minecraft:gold_ore", 5)))
    g.fill(2, 1, 2, 11, 2, 2, Mix(("minecraft:stone", 60), ("minecraft:coal_ore", 20), ("minecraft:iron_ore", 20)))
    for x in (3, 7, 10):
        g.fill(x, 0, 3, x, 2, 3, "minecraft:spruce_log[axis=y]") if x != 7 else None
        g.fill(x, 0, 4, x, 2, 4, "minecraft:spruce_log[axis=y]") if x != 7 else None
        g.fill(x, 2, 3, x, 2, 4, "minecraft:spruce_log[axis=z]") if x != 7 else None
    g.set(4, 2, 4, "minecraft:lantern[hanging=true]")
    g.set(9, 2, 4, "minecraft:lantern[hanging=true]")
    g.fill(2, -1, 3, 11, -1, 4, "minecraft:stone") if False else None
    g.set(10, 0, 3, "minecraft:rail[shape=east_west]")
    g.set(9, 0, 3, "minecraft:rail[shape=east_west]")
    g.chest(3, 0, 4, "east", LOOT + "castle_store")
    # spoil heaps and a cart outside
    g.fill(10, GL, 8, 11, GL, 9, "minecraft:cobblestone")
    g.set(10, GL + 1, 8, "minecraft:cobblestone")
    g.set(2, GL, 9, "minecraft:coal_block")
    g.set(1, GL, 9, "minecraft:raw_iron_block")
    g.fill(3, GL - 1, 7, 9, GL - 1, 11, "minecraft:dirt_path")
    g.mark("door", 6, GL, 7)
    lamp_post(g, 10, 11, GL)
    return g, {"name": "mine", "kind": "mine", "profession": "toolsmith", "GL": GL, "size": [W, D]}


def mill():
    """A windmill: stone tower, timber cap, four sails."""
    W, D, GL = 15, 15, 4
    g = _base(W, D, H=30, GL=GL, seed=61)
    cx, cz = 7, 6
    full = disk(g, cx, cz, 4)
    g.fill_mask(full, 0, GL + 9, STONE)
    g.fill_mask(disk(g, cx, cz, 2), GL, GL + 8, "minecraft:air")
    g.fill_mask(disk(g, cx, cz, 2), GL + 4, GL + 4, PLANK)
    g.fill_mask(disk(g, cx, cz, 2), GL + 4, GL + 4, PLANK)
    g.fill(cx, GL + 4, cz, cx, GL + 4, cz, "minecraft:air")
    g.fill(cx - 1, GL, cz - 1, cx - 1, GL + 7, cz - 1, "minecraft:ladder[facing=east]")
    g.fill_mask(disk(g, cx, cz, 4) & ~disk(g, cx, cz, 3), GL + 10, GL + 10, "minecraft:spruce_planks")
    g.fill_mask(disk(g, cx, cz, 3), GL + 10, GL + 12, "minecraft:spruce_planks")
    g.fill_mask(disk(g, cx, cz, 2), GL + 10, GL + 11, "minecraft:air")
    g.fill_mask(disk(g, cx, cz, 4), GL + 13, GL + 13, "minecraft:spruce_slab[type=bottom]")
    # door and windows
    g.fill(cx, GL, cz + 4, cx, GL + 1, cz + 4, "minecraft:air")
    g.fill(cx, GL, cz + 3, cx, GL + 1, cz + 3, "minecraft:air")
    g.set(cx + 4, GL + 2, cz, GLASS)
    g.set(cx - 4, GL + 2, cz, GLASS)
    g.set(cx, GL + 6, cz + 4, GLASS)
    # sails on the +z face, axle at GL+11
    ay = GL + 11
    g.set(cx, ay, cz + 4, "minecraft:spruce_log[axis=z]")
    g.set(cx, ay, cz + 5, "minecraft:spruce_log[axis=z]")
    g.set(cx, ay, cz + 6, "minecraft:spruce_fence")
    for i in range(1, 7):
        g.set(cx + i, ay, cz + 6, "minecraft:spruce_fence")
        g.set(cx - i, ay, cz + 6, "minecraft:spruce_fence")
        g.set(cx, ay + i, cz + 6, "minecraft:spruce_fence")
        g.set(cx, ay - i, cz + 6, "minecraft:spruce_fence")
        if i >= 2:
            g.set(cx + i, ay + 1, cz + 6, "minecraft:white_wool")
            g.set(cx - i, ay - 1, cz + 6, "minecraft:white_wool")
            g.set(cx - 1, ay + i, cz + 6, "minecraft:white_wool")
            g.set(cx + 1, ay - i, cz + 6, "minecraft:white_wool")
    # inside: millstones and sacks
    g.set(cx + 1, GL, cz - 1, "minecraft:grindstone[face=floor,facing=south]")
    g.set(cx - 1, GL, cz + 1, "minecraft:composter[level=5]")
    g.mark("station", cx - 1, GL, cz + 1)
    g.barrel(cx + 1, GL, cz + 1, LOOT + "castle_granary")
    g.set(cx + 1, GL + 5, cz + 1, "minecraft:hay_block[axis=y]")
    g.barrel(cx - 1, GL + 5, cz - 1, LOOT + "castle_granary")
    # hay bales and a cart outside
    g.fill(2, GL, 10, 3, GL, 11, "minecraft:hay_block[axis=y]")
    g.set(2, GL + 1, 10, "minecraft:hay_block[axis=x]")
    g.fill(cx - 1, GL - 1, cz + 5, cx + 1, GL - 1, D - 1, "minecraft:dirt_path")
    g.mark("door", cx, GL, cz + 5)
    g.bed(cx + 1, GL + 5, cz - 1, "south", "white")
    g.mark("bed", cx + 1, GL + 5, cz - 1)
    return g, {"name": "mill", "kind": "mill", "profession": "farmer", "GL": GL, "size": [W, D]}


def well():
    W, D, GL = 7, 7, 4
    g = _base(W, D, H=12, GL=GL, seed=71)
    g.fill(2, GL - 4, 2, 4, GL - 1, 4, "minecraft:cobblestone")
    g.fill(3, GL - 3, 3, 3, GL - 1, 3, "minecraft:water")
    g.fill(2, GL, 2, 4, GL, 4, "minecraft:cobblestone_wall")
    g.set(3, GL, 3, "minecraft:air")
    for (x, z) in ((2, 2), (4, 2), (2, 4), (4, 4)):
        g.fill(x, GL + 1, z, x, GL + 2, z, "minecraft:oak_fence")
    g.fill(2, GL + 3, 2, 4, GL + 3, 4, "minecraft:oak_slab[type=bottom]")
    g.set(3, GL + 2, 3, "minecraft:chain[axis=y]")
    g.set(3, GL + 1, 3, "minecraft:lantern[hanging=true]")
    g.fill(1, GL - 1, 1, 5, GL - 1, 5, Mix(("minecraft:cobblestone", 50), ("minecraft:gravel", 30), ("minecraft:dirt_path", 20)))
    g.mark("door", 3, GL, 6)
    return g, {"name": "well", "kind": "well", "profession": None, "GL": GL, "size": [W, D]}


def chapel():
    """A small stone chapel with a bell."""
    W, D, GL = 11, 15, 4
    g = _base(W, D, H=22, GL=GL, seed=81)
    building(g, 1, 1, 9, 12, GL, 6, "z", door_cells=((5, 12),), wall_block=STONE)
    cols = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple"]
    for i, z in enumerate(range(3, 11, 2)):
        g.fill(1, GL + 2, z, 1, GL + 4, z, f"minecraft:{cols[i % 7]}_stained_glass_pane")
        g.fill(9, GL + 2, z, 9, GL + 4, z, f"minecraft:{cols[(i + 3) % 7]}_stained_glass_pane")
    g.fill(3, GL, 2, 7, GL, 2, "minecraft:polished_andesite")
    for x in range(3, 8):
        g.set(x, GL + 1, 2, f"minecraft:candle[candles={1 + x % 3},lit=true]")
    g.set(5, GL, 3, "minecraft:lectern[facing=south]")
    g.mark("station", 5, GL, 3)
    for z in range(5, 11, 2):
        for x in (2, 3, 7, 8):
            g.stair(x, GL, z, "minecraft:dark_oak_stairs", "north")
    # bell cote
    g.fill(4, GL + 6, 1, 6, GL + 9, 1, STONE)
    g.set(5, GL + 7, 1, "minecraft:air")
    g.set(5, GL + 8, 1, "minecraft:air")
    g.set(5, GL + 8, 1, "minecraft:bell[attachment=ceiling,facing=north]")
    g.fill(5, GL - 1, 13, 5, GL - 1, 14, "minecraft:dirt_path")
    g.mark("door", 5, GL, 13)
    g.bed(2, GL, 11, "east", "white")
    g.mark("bed", 2, GL, 11)
    return g, {"name": "chapel", "kind": "chapel", "profession": "cleric", "GL": GL, "size": [W, D]}


def lumber():
    """A lumber yard: log stacks, a saw bench, a lean-to."""
    W, D, GL = 15, 13, 4
    g = _base(W, D, H=14, GL=GL, seed=91)
    # lean-to
    g.fill(1, GL, 1, 1, GL + 3, 5, "minecraft:spruce_log[axis=y]")
    g.fill(7, GL, 1, 7, GL + 3, 5, "minecraft:spruce_log[axis=y]")
    for (x, z) in ((1, 1), (7, 1), (1, 5), (7, 5)):
        g.fill(x, GL, z, x, GL + 3, z, "minecraft:spruce_log[axis=y]")
    g.fill(1, GL, 2, 1, GL + 2, 4, "minecraft:air"); g.fill(7, GL, 2, 7, GL + 2, 4, "minecraft:air")
    g.fill(1, GL + 1, 1, 7, GL + 3, 1, WATTLE)
    g.fill(1, GL, 1, 7, GL + 3, 1, WATTLE)
    g.fill(0, GL + 4, 0, 8, GL + 4, 6, "minecraft:spruce_slab[type=bottom]")
    g.fill(0, GL + 4, 0, 8, GL + 4, 2, "minecraft:spruce_planks")
    g.set(4, GL, 3, "minecraft:stonecutter[facing=south]") if False else None
    g.set(3, GL, 2, "minecraft:crafting_table")
    g.set(5, GL, 2, "minecraft:smithing_table") if False else g.set(5, GL, 2, "minecraft:barrel[facing=up]")
    g.mark("station", 3, GL, 2)
    g.bed(2, GL, 4, "east", "white")
    g.mark("bed", 2, GL, 4)
    # log stacks
    g.fill(10, GL, 1, 13, GL + 1, 4, "minecraft:spruce_log[axis=x]")
    g.fill(10, GL + 2, 2, 13, GL + 2, 3, "minecraft:spruce_log[axis=x]")
    g.fill(9, GL, 7, 13, GL, 8, "minecraft:oak_log[axis=z]")
    g.fill(10, GL + 1, 7, 12, GL + 1, 8, "minecraft:oak_log[axis=z]")
    g.fill(2, GL, 8, 5, GL, 9, "minecraft:stripped_spruce_log[axis=x]")
    g.set(7, GL, 10, "minecraft:oak_log[axis=y]")
    g.set(7, GL + 1, 10, "minecraft:stone_brick_slab[type=bottom]") if False else None
    g.fill(6, GL - 1, 6, 8, GL - 1, D - 1, "minecraft:dirt_path")
    g.mark("door", 7, GL, 11)
    return g, {"name": "lumber", "kind": "lumber", "profession": "mason", "GL": GL, "size": [W, D]}


def quarry():
    """A stone quarry: a stepped pit with a stonecutter at the bottom and dressed blocks stacked above."""
    W, D, GL = 17, 15, 4
    g = _base(W, D, H=14, GL=GL, seed=101)
    g.fill(0, 0, 0, W - 1, GL - 2, D - 1, Mix(("minecraft:stone", 70), ("minecraft:andesite", 20), ("minecraft:cobblestone", 10)))
    # the pit
    g.fill(3, GL - 4, 2, 13, GL - 1, 10, "minecraft:air")
    g.fill(3, GL - 4, 2, 13, GL - 4, 10, Mix(("minecraft:stone", 60), ("minecraft:gravel", 20), ("minecraft:andesite", 20)))
    # steps down the +z side
    for i in range(3):
        g.fill(3 + i, GL - 1 - i, 2 + i, 13 - i, GL - 1 - i, 2 + i, "minecraft:stone_brick_stairs[facing=south,half=bottom]")
        g.fill(3, GL - 1 - i, 2 + i, 3, GL - 1 - i, 10 - i, "minecraft:stone")
        g.fill(13, GL - 1 - i, 2 + i, 13, GL - 1 - i, 10 - i, "minecraft:stone")
    g.set(8, GL - 4, 6, "minecraft:stonecutter[facing=south]")
    g.mark("station", 8, GL - 4, 6)
    g.set(5, GL - 4, 8, "minecraft:lantern[hanging=false]")
    g.set(11, GL - 4, 8, "minecraft:lantern[hanging=false]")
    g.set(10, GL - 4, 4, "minecraft:cobblestone")
    g.set(11, GL - 4, 4, "minecraft:cobblestone")
    g.set(6, GL - 4, 4, "minecraft:cobblestone")
    # ramp out on +z
    g.fill(7, GL - 3, 10, 9, GL - 1, 12, "minecraft:air")
    for i, y in enumerate(range(GL - 4, GL)):
        g.fill(7, y, 10 + i // 2, 9, y, 10 + i // 2, "minecraft:stone_brick_stairs[facing=south,half=bottom]")
    g.fill(7, GL - 1, 12, 9, GL - 1, 13, "minecraft:dirt_path")
    # stacks of dressed stone and a crane post
    g.fill(1, GL, 11, 2, GL + 1, 13, "minecraft:stone_bricks")
    g.fill(14, GL, 11, 15, GL + 1, 13, "minecraft:cobblestone")
    g.fill(15, GL, 1, 15, GL + 5, 1, "minecraft:spruce_log[axis=y]")
    g.fill(11, GL + 5, 1, 15, GL + 5, 1, "minecraft:spruce_log[axis=x]")
    g.set(11, GL + 4, 1, "minecraft:chain[axis=y]")
    g.set(11, GL + 3, 1, "minecraft:chain[axis=y]")
    g.bed(1, GL, 1, "east", "white")
    g.mark("bed", 1, GL, 1)
    g.mark("door", 8, GL, 13)
    return g, {"name": "quarry", "kind": "quarry", "profession": "mason", "GL": GL, "size": [W, D]}


def dock():
    """A fishing jetty: the placer puts the far end over water (+z)."""
    W, D, GL = 7, 17, 4
    g = _base(W, D, H=12, GL=GL, seed=111)
    g.fill(0, 0, 6, W - 1, GL - 1, D - 1, "<void>")
    g.fill(2, GL - 1, 0, 4, GL - 1, 5, "minecraft:spruce_planks")
    g.fill(2, GL - 1, 6, 4, GL - 1, D - 1, "minecraft:spruce_planks")
    for z in range(6, D, 3):
        for x in (2, 4):
            g.fill(x, GL - 5, z, x, GL - 2, z, "minecraft:spruce_log[axis=y]")
            g.set(x, GL, z, "minecraft:spruce_fence")
    g.barrel(3, GL, 1, LOOT + "castle_cellar")
    g.set(2, GL, 2, "minecraft:barrel[facing=up]")
    g.mark("station", 2, GL, 2)
    g.set(4, GL, 3, "minecraft:campfire[lit=true,signal_fire=false]")
    g.set(3, GL, D - 2, "minecraft:lantern[hanging=false]")
    g.set(2, GL, D - 1, "minecraft:oak_fence"); g.set(4, GL, D - 1, "minecraft:oak_fence")
    g.mark("door", 3, GL, -1)
    return g, {"name": "dock", "kind": "dock", "profession": "fisherman", "GL": GL, "size": [W, D], "water": True}


PIECES = (cottage_a, cottage_b, cottage_c, cottage_d, pasture, mine, mill, well, chapel, lumber, quarry, dock)


def write(root):
    sdir = os.path.join(root, "src/main/resources/data/stormhold/structures/fief")
    jdir = os.path.join(root, "src/main/resources/data/stormhold/fief")
    os.makedirs(sdir, exist_ok=True)
    os.makedirs(jdir, exist_ok=True)
    index = []
    for fn in PIECES:
        g, meta = fn()
        tiles = export.write_tiles(g, os.path.join(sdir, meta["name"]), "fief/" + meta["name"])
        if len(tiles) != 1:
            raise SystemExit(meta["name"] + " spans tiles")
        X, Z, Y = g.a.shape
        beds = [list(p) for p in g.markers.get("bed", [])]
        info = {"name": meta["name"], "kind": meta["kind"], "profession": meta.get("profession"),
                "size": [X, Y, Z], "ground": meta["GL"], "template": "fief/" + meta["name"] + "/tile_0_0",
                "beds": beds, "stations": [list(p) for p in g.markers.get("station", [])],
                "door": list(g.markers["door"][0]) if "door" in g.markers else [X // 2, meta["GL"], Z - 1],
                "animals": meta.get("animals", []), "water": bool(meta.get("water", False)),
                "animal_spot": list(g.markers["animals"][0]) if "animals" in g.markers else None}
        with open(os.path.join(jdir, meta["name"] + ".json"), "w") as f:
            json.dump(info, f, separators=(",", ":"))
        index.append(info)
        print("fief piece", meta["name"], X, "x", Z, "beds", len(beds))
    return index



def siege_tower():
    """A timber siege tower on wheels: ladders up the inside, a drawbridge at wall height, a War Table on
    the ground floor. +z is the front (toward the wall)."""
    W, D, GL = 7, 9, 2
    g = Grid(W, 24, D, seed=121)
    g.fill(0, 0, 0, W - 1, GL - 1, D - 1, "<void>")
    LOG = "minecraft:dark_oak_log[axis=y]"
    AX = "minecraft:dark_oak_log[axis=x]"
    # wheels: two axles across the width, front and back
    for z in (1, 7):
        for x in range(0, W):
            g.set(x, GL - 1, z, AX)
        for x in (0, 6):
            g.set(x, GL - 2, z, "minecraft:dark_oak_fence")
            g.set(x, GL, z, "minecraft:dark_oak_fence")
    # chassis
    g.fill(1, GL, 1, 5, GL, 7, "minecraft:spruce_planks")
    # body: 5 wide, 5 deep (z 2..6), 14 tall, corner posts
    top = GL + 15
    for (x, z) in ((1, 2), (5, 2), (1, 6), (5, 6)):
        g.fill(x, GL + 1, z, x, top, z, LOG)
    g.fill(1, GL + 1, 2, 5, top - 1, 2, "minecraft:spruce_planks")      # back wall
    g.fill(1, GL + 1, 6, 5, top - 1, 6, "minecraft:spruce_planks")      # front wall (hides)
    g.fill(1, GL + 1, 3, 1, top - 1, 5, "minecraft:spruce_planks")      # sides
    g.fill(5, GL + 1, 3, 5, top - 1, 5, "minecraft:spruce_planks")
    g.fill(2, GL + 1, 3, 4, top - 1, 5, "minecraft:air")
    # wet hides on the front against fire
    for y in range(GL + 2, top - 1, 2):
        g.fill(1, y, 6, 5, y, 6, "minecraft:brown_wool")
    # floors every 5 with a hatch over the ladder
    g.fill(2, GL + 1, 3, 2, top - 1, 3, "minecraft:ladder[facing=east]")
    for fy in (GL + 6, GL + 11):
        g.fill(3, fy, 3, 4, fy, 5, "minecraft:spruce_planks")
        g.set(2, fy, 4, "minecraft:spruce_planks")
        g.set(2, fy, 5, "minecraft:spruce_planks")
        g.lantern(4, fy - 1, 4)
    # arrow slits on the sides
    for y in (GL + 4, GL + 9, GL + 13):
        g.set(1, y, 4, "minecraft:air")
        g.set(5, y, 4, "minecraft:air")
    # top platform with rails and the drawbridge forward
    g.fill(1, top, 2, 5, top, 6, "minecraft:spruce_planks")
    g.set(2, top, 3, "minecraft:air")
    g.fill(1, top + 1, 2, 5, top + 1, 2, "minecraft:spruce_fence")
    g.fill(1, top + 1, 3, 1, top + 1, 5, "minecraft:spruce_fence")
    g.fill(5, top + 1, 3, 5, top + 1, 5, "minecraft:spruce_fence")
    for z in (7, 8):
        g.fill(2, top, z, 4, top, z, "minecraft:oak_trapdoor[facing=north,half=top,open=false]")
    g.set(3, top + 1, 2, "minecraft:white_wall_banner[facing=south]")
    g.banners.append((3, top + 1, 2))
    # the back door and the war table on the ground floor
    g.fill(3, GL + 1, 2, 3, GL + 2, 2, "minecraft:air")
    g.set(4, GL + 1, 5, "stormhold:war_table")
    g.mark("war_table", 4, GL + 1, 5)
    g.chest(2, GL + 1, 5, "east", LOOT + "castle_armory")
    g.lantern(3, GL + 5, 4)
    g.mark("door", 3, GL + 1, 1)
    return g, {"name": "siege_tower", "kind": "siege", "profession": None, "GL": GL + 1, "size": [W, D]}


PIECES = PIECES + (siege_tower,)


if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(__file__))
    write(os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
