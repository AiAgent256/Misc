"""The Canal City: the second capital plan. The same 256x256 walled footprint as the Great City, but the
harbour comes in through the water gate and runs as a canal twenty-four blocks wide up the middle of the
lower city to a dock basin under the inner wall, with quays, warehouses, jetties and three stone bridges.
The upper city and the keep on its terrace are the same bones in a different town."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, spiral_stair, cone_roof, angle_index
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, STONE_STAIR, SLAB_CAP, PLANK,
                   curtain, side_grids, posts_on_walk, round_tower, square_tower, pointed_arch, building, lamp_post)
from castle import portcullis_plane, bartizan
from castle2 import portcullis_plane_x, parapet_rect
from town import dense_block, terrace, arcade, stall_cluster, stall_row
from city import (LOOT, AIR, SQUARE, NOBLE, carve, row_x, row_z, tree_oak, great_sept, barracks, inn, market_square,
                  keep, ring_tower, grand_stair, statue_plinth, lighthouse, bell_tower, dragonpit)

CANAL_X0, CANAL_X1 = 116, 139        # the water
QUAY_W0, QUAY_W1 = 110, 115          # the west quay street
QUAY_E0, QUAY_E1 = 140, 145          # the east quay street
BASIN_Z = 128                        # the head of the canal


def quay_wall(g, x, z0, z1, GL, face):
    """The stone face of a quay from the bed to the street, with mooring rings and a ladder here and there."""
    g.fill(x, GL - 7, z0, x, GL - 2, z1, Mix(("minecraft:stone_bricks", 60), ("minecraft:mossy_stone_bricks", 25),
                                            ("minecraft:cracked_stone_bricks", 15)))
    wx = x + (1 if face == "east" else -1)        # the water column against the face
    for z in range(z0 + 6, z1 - 3, 24):
        for y in range(GL - 4, GL):
            g.set(wx, y, z, f"minecraft:ladder[facing={face}]")


def jetty(g, x0, x1, z, GL, rng):
    """A plank pier out from the quay over the water, on log piles, with a lantern at the end."""
    g.fill(x0, GL - 1, z, x1, GL - 1, z + 2, "minecraft:spruce_planks")
    lo, hi = sorted((x0, x1))
    for x in range(lo, hi + 1, 2):
        g.fill(x, GL - 6, z, x, GL - 2, z, "minecraft:spruce_log[axis=y]")
        g.fill(x, GL - 6, z + 2, x, GL - 2, z + 2, "minecraft:spruce_log[axis=y]")
    g.set(x1, GL, z + 1, "minecraft:spruce_fence")
    g.set(x1, GL + 1, z + 1, "minecraft:lantern[hanging=false]")
    g.set(x0 + (1 if x1 > x0 else -1), GL, z, "minecraft:barrel[facing=up]")


def canal_bridge(g, x0, x1, z0, z1, GL, lamps=True):
    """A stone bridge over the canal, its deck three steps above the quays so a boat passes under it."""
    deck = GL + 2
    # piers in the water, the span, parapets
    for x in (x0 + 5, (x0 + x1) // 2, x1 - 5):
        g.fill(x, GL - 7, z0, x, deck - 2, z1, STONE)
    g.fill(x0, deck - 1, z0, x1, deck - 1, z1, STONE)
    g.fill(x0, deck, z0, x1, deck + 2, z1, AIR)
    for x in range(x0 + 1, x1):
        g.stair(x, deck - 2, z0, STONE_STAIR, "south", half="top")
        g.stair(x, deck - 2, z1, STONE_STAIR, "north", half="top")
    g.fill(x0, deck, z0 - 1, x1, deck, z0 - 1, "minecraft:stone_brick_wall")
    g.fill(x0, deck, z1 + 1, x1, deck, z1 + 1, "minecraft:stone_brick_wall")
    g.fill(x0, deck - 1, z0 - 1, x1, deck - 1, z1 + 1, STONE)
    # ramps of three steps at either end, down to the quay streets
    for (sx, direction) in ((x0 - 1, -1), (x1 + 1, 1)):
        for i in range(2):
            x = sx + direction * i
            y = deck - 1 - i
            for z in range(z0, z1 + 1):
                g.stair(x, y, z, STONE_STAIR, "east" if direction < 0 else "west")
            for z in (z0 - 1, z1 + 1):
                g.set(x, y, z, "minecraft:stone_brick_wall")
                g.fill(x, GL - 1, z, x, y - 1, z, STONE)
            g.fill(x, GL - 1, z0, x, y - 1, z1, STONE)
            g.fill(x, y + 1, z0 - 1, x, deck + 2, z1 + 1, AIR)
    if lamps:
        for x in (x0 + 1, x1 - 1):
            g.set(x, deck + 1, z0 - 1, "minecraft:lantern[hanging=false]")
            g.set(x, deck + 1, z1 + 1, "minecraft:lantern[hanging=false]")
    g.mark("knight", (x0 + x1) // 2, deck, (z0 + z1) // 2)


def warehouse(g, x0, z0, x1, z1, GL, rng, door):
    """A tall stone warehouse on the quay: a hoist beam over the door, crates and casks inside."""
    building(g, x0, z0, x1, z1, GL, 9, "x" if x1 - x0 >= z1 - z0 else "z", door_cells=(door,), upper_floor=GL + 5,
             wall_block=STONE)
    dx, dz = door
    # the hoist: a beam out over the door with a chain
    if dz == z0 or dz == z1:
        out = -1 if dz == z0 else 1
        g.fill(dx, GL + 7, dz, dx, GL + 7, dz + 2 * out, "minecraft:spruce_log[axis=z]")
        g.set(dx, GL + 6, dz + 2 * out, "minecraft:chain[axis=y]")
        g.set(dx, GL + 5, dz + 2 * out, "minecraft:chain[axis=y]")
    for i in range(4):
        x = x0 + 2 + int(rng.integers(0, max(1, x1 - x0 - 3)))
        z = z0 + 2 + int(rng.integers(0, max(1, z1 - z0 - 3)))
        g.barrel(x, GL, z, LOOT + "castle_store")
        if rng.integers(0, 2) == 0:
            g.set(x, GL + 1, z, "minecraft:barrel[facing=up]")
    g.chest(x0 + 1, GL + 5, z0 + 1, "south", LOOT + "castle_store")


def canal_city(seed=91):
    X, Z, H = 256, 256, 128
    GL = 8
    GL2 = GL + 8
    GL3 = GL + 22
    g = Grid(X, H, Z, seed)
    rng = g.rng
    xs, zs = g.xz()

    # ---- ground, harbour, the canal ----
    g.fill(0, 0, 0, X - 1, GL - 2, Z - 1, FOUND)
    g.fill(0, GL - 1, 0, X - 1, GL - 1, Z - 1, Mix(("minecraft:grass_block", 60), ("minecraft:coarse_dirt", 20),
                                                     ("minecraft:dirt_path", 10), ("minecraft:gravel", 10)))
    g.fill(0, GL, 0, X - 1, GL + 10, Z - 1, AIR)
    water = (zs >= 238) | ((xs >= CANAL_X0) & (xs <= CANAL_X1) & (zs >= BASIN_Z))
    g.fill_mask(water, 0, GL - 8, DARK)
    g.fill_mask(water, GL - 7, GL - 7, Mix(("minecraft:sand", 50), ("minecraft:gravel", 30), ("minecraft:clay", 20)))
    g.fill_mask(water, GL - 6, GL - 2, "minecraft:water")
    g.fill_mask(water, GL - 1, GL + 10, AIR)
    quay = (zs >= 228) & (zs < 238) & ~water
    g.fill_mask(quay, 0, GL - 2, DARK)
    g.fill_mask(quay, GL - 1, GL - 1, Mix(("minecraft:stone_bricks", 50), ("minecraft:cobblestone", 30), ("minecraft:andesite", 20)))
    for px in (40, 96, 160, 216):
        g.fill(px, GL - 1, 238, px + 4, GL - 1, 252, "minecraft:spruce_planks")
        for z in range(239, 253, 4):
            g.fill(px, GL - 6, z, px, GL - 2, z, "minecraft:spruce_log[axis=y]")
            g.fill(px + 4, GL - 6, z, px + 4, GL - 2, z, "minecraft:spruce_log[axis=y]")
            g.set(px, GL, z, "minecraft:spruce_fence"); g.set(px + 4, GL, z, "minecraft:spruce_fence")
        g.set(px + 2, GL, 251, "minecraft:lantern[hanging=false]")
        g.set(px + 1, GL, 240, "minecraft:barrel[facing=up]"); g.set(px + 3, GL, 244, "minecraft:barrel[facing=up]")
    for x in range(14, 242, 24):
        if not (CANAL_X0 - 2 <= x <= CANAL_X1 + 2):
            lamp_post(g, x, 237, GL)
    for x in (20, 68, 180, 228):
        # against the foot of the wall, doors on the quay: no slot behind them and a street three wide in front
        building(g, x, 228, x + 16, 234, GL, 6, "x", door_cells=((x + 8, 234),), wall_block=STONE)
        g.barrel(x + 2, GL, 232, LOOT + "town_house"); g.barrel(x + 14, GL, 232, LOOT + "town_house")
    lighthouse(g, 8, 232, GL)
    lighthouse(g, 247, 232, GL)
    g.mark("knight", 104, GL, 236); g.mark("knight", 60, GL, 236); g.mark("knight", 196, GL, 236)
    # the quays along the canal and the basin head
    g.fill(QUAY_W0, GL - 1, BASIN_Z - 4, QUAY_W1, GL - 1, 237, ROAD)
    g.fill(QUAY_E0, GL - 1, BASIN_Z - 4, QUAY_E1, GL - 1, 237, ROAD)
    g.fill(QUAY_W0, GL - 1, BASIN_Z - 4, QUAY_E1, GL - 1, BASIN_Z - 1, ROAD)
    quay_wall(g, CANAL_X0 - 1, BASIN_Z, 237, GL, "east")
    quay_wall(g, CANAL_X1 + 1, BASIN_Z, 237, GL, "west")
    g.fill(CANAL_X0 - 1, GL - 7, BASIN_Z - 1, CANAL_X1 + 1, GL - 2, BASIN_Z - 1, "minecraft:stone_bricks")
    for z in range(BASIN_Z + 2, 236, 4):
        for x in (CANAL_X0 - 1, CANAL_X1 + 1):
            g.set(x, GL, z, "minecraft:stone_brick_wall")
            if (z - BASIN_Z) % 16 == 2:
                g.set(x, GL + 1, z, "minecraft:lantern[hanging=false]")
    for z in (140, 176, 206):
        jetty(g, CANAL_X0, CANAL_X0 + 4, z, GL, rng)
    for z in (150, 186, 212):
        jetty(g, CANAL_X1, CANAL_X1 - 4, z, GL, rng)
    # the basin head: a crane, a weighing beam, casks waiting on the quay
    g.fill(126, GL, BASIN_Z - 2, 126, GL + 6, BASIN_Z - 2, "minecraft:spruce_log[axis=y]")
    g.fill(126, GL + 6, BASIN_Z - 2, 126, GL + 6, BASIN_Z + 2, "minecraft:spruce_log[axis=z]")
    g.set(126, GL + 5, BASIN_Z + 2, "minecraft:chain[axis=y]"); g.set(126, GL + 4, BASIN_Z + 2, "minecraft:chain[axis=y]")
    for (x, z) in ((118, BASIN_Z - 3), (120, BASIN_Z - 2), (134, BASIN_Z - 3), (136, BASIN_Z - 2), (113, 150), (143, 170)):
        g.set(x, GL, z, "minecraft:barrel[facing=up]")
    g.mark("knight", 112, GL, BASIN_Z - 2); g.mark("knight", 143, GL, BASIN_Z - 2)

    # ---- terraces ----
    upper = rect(g, 64, 14, 191, 113)
    g.fill_mask(upper, GL - 1, GL2 - 2, FOUND)
    g.fill_mask(upper, GL2 - 1, GL2 - 1, PAVE)
    g.fill_mask(upper, GL2, GL2 + 12, AIR)
    keep_t = rect(g, 88, 18, 168, 76)
    g.fill_mask(keep_t, GL2 - 1, GL3 - 2, FOUND)
    g.fill_mask(keep_t, GL3 - 1, GL3 - 1, PAVE)
    g.fill_mask(keep_t, GL3, GL3 + 12, AIR)

    # ---- streets (lower city): the canal takes the avenue's place ----
    g.fill(14, GL - 1, 160, QUAY_W1, GL - 1, 165, ROAD); g.fill(QUAY_E0, GL - 1, 160, 241, GL - 1, 165, ROAD)   # the cross street
    for x0 in (60, 192):
        g.fill(x0, GL - 1, 14, x0 + 3, GL - 1, 221, ROAD)
    for z0 in (128, 196):
        g.fill(14, GL - 1, z0, QUAY_W1, GL - 1, z0 + 3, ROAD); g.fill(QUAY_E0, GL - 1, z0, 241, GL - 1, z0 + 3, ROAD)
    g.fill(14, GL - 1, 114, 241, GL - 1, 117, ROAD)
    g.fill(14, GL - 1, 218, QUAY_W1, GL - 1, 221, ROAD); g.fill(QUAY_E0, GL - 1, 218, 241, GL - 1, 221, ROAD)
    g.fill(14, GL - 1, 62, 59, GL - 1, 65, ROAD); g.fill(196, GL - 1, 62, 241, GL - 1, 65, ROAD)
    for x in range(20, 240, 16):
        if not (QUAY_W0 - 2 <= x <= QUAY_E1 + 2):
            g.mark("knight", x, GL, 162)
    for z in range(136, 220, 16):
        g.mark("knight", 112, GL, z); g.mark("knight", 143, GL, z); g.mark("knight", 62, GL, z); g.mark("knight", 194, GL, z)
    for z in range(24, 110, 16):
        g.mark("knight", 62, GL, z); g.mark("knight", 194, GL, z)
    for x in range(30, 230, 24):
        if not (QUAY_W0 - 2 <= x <= QUAY_E1 + 2):
            lamp_post(g, x, 159, GL); lamp_post(g, x + 12, 166, GL)
    for z in range(136, 216, 20):
        lamp_post(g, 111, z, GL); lamp_post(g, 144, z + 10, GL)
    for z in range(30, 110, 24):
        lamp_post(g, 59, z, GL); lamp_post(g, 196, z, GL)
    # the quays: statues of admirals every twenty blocks
    for z in range(146, 216, 20):
        statue_plinth(g, 111, z + 4, GL); statue_plinth(g, 144, z - 4, GL)
    # three bridges: the Cross Bridge, the Fish Bridge, and the Gate Bridge by the water gate
    canal_bridge(g, CANAL_X0, CANAL_X1, 160, 165, GL)
    canal_bridge(g, CANAL_X0, CANAL_X1, 196, 199, GL)
    canal_bridge(g, CANAL_X0, CANAL_X1, 218, 221, GL, lamps=False)

    # ---- districts ----
    doors = []
    # north of the cross street: the market street along the terrace foot, broken by the basin
    doors.append(inn(g, 18, 118, 42, 127, GL, rng, "south"))
    doors += terrace(g, 45, 58, 118, 9, GL, rng, "south", trades=["general", "none"])
    doors += terrace(g, 65, 108, 118, 9, GL, rng, "south", trades=["baker", "general", "chandler", "none", "potter"])
    doors += terrace(g, 147, 190, 118, 9, GL, rng, "south", trades=["cloth", "general", "apothecary", "none", "cloth"])
    # Smiths' Row
    building(g, 200, 118, 216, 126, GL, 7, "x", door_cells=((208, 126),), chimney=(202, 120))
    g.set(203, GL, 121, "minecraft:anvil[facing=east]"); g.set(206, GL, 121, "minecraft:smithing_table")
    g.set(213, GL, 120, "minecraft:blast_furnace[facing=south,lit=false]"); g.set(210, GL, 120, "minecraft:lava_cauldron")
    g.chest(214, GL, 124, "west", LOOT + "castle_smithy")
    g.mark("smithy", 208, GL, 128)
    doors += terrace(g, 219, 237, 118, 9, GL, rng, "south", trades=["armoury", "weapons", "tanner"])
    # the blocks between the cross street and the market street; those on the canal are warehouses
    doors += dense_block(g, 18, 133, 58, 158, GL, rng, trades=["general", "none", "none", "baker", "none"], alley=("z", 36))
    doors += dense_block(g, 65, 133, 100, 158, GL, rng, trades=["none", "cloth", "none", "chandler"], alley=("z", 82))
    warehouse(g, 102, 133, 108, 146, GL, rng, (108, 139))
    warehouse(g, 102, 148, 108, 158, GL, rng, (108, 153))
    warehouse(g, 147, 133, 153, 146, GL, rng, (147, 139))
    warehouse(g, 147, 148, 153, 158, GL, rng, (147, 153))
    doors += dense_block(g, 155, 133, 190, 158, GL, rng, trades=["none", "none", "potter", "general"], alley=("z", 172))
    doors += dense_block(g, 197, 133, 237, 158, GL, rng, trades=["smith", "none", "tanner", "none", "none"], alley=("z", 220))
    # flanks
    doors += dense_block(g, 18, 18, 58, 60, GL, rng, sides=("south", "west", "east"), trades=["none", "none", "apothecary", "none"], alley=("x", 39))
    doors += dense_block(g, 18, 67, 58, 112, GL, rng, trades=["none", "fletcher", "none", "none"], alley=("x", 90))
    dragonpit(g, 218, 42, 19, GL)
    doors += dense_block(g, 197, 67, 237, 112, GL, rng, trades=["none", "none", "butcher", "none"], alley=("x", 90))
    # south of the cross street: the sept, the two halves of the market either side of the water, the barracks
    great_sept(g, 36, 188, 17, GL)
    g.fill(34, GL - 1, 166, 38, GL - 1, 168, ROAD)
    g.fill(68, GL - 1, 168, 108, GL - 1, 194, SQUARE)
    market_square(g, 147, 168, 186, 194, GL, rng)
    arcade(g, 68, 168, 108, 170, GL, rng, "south", trades=["cloth", "general", "potter"])
    arcade(g, 68, 192, 108, 194, GL, rng, "north", trades=["baker", "butcher", "general"])
    arcade(g, 147, 168, 186, 170, GL, rng, "south", trades=["chandler", "cloth", "general"])
    arcade(g, 147, 192, 186, 194, GL, rng, "north", trades=["fish", "general", "fish"])
    stall_cluster(g, 80, 173, 106, 190, GL, rng, trades=["general", "fletcher", "farmer", "baker", "armoury"])   # clear of the bell tower
    # and a row each side of the fountain in the east square, under the arcade
    stall_row(g, 150, 158, 172, GL, rng, "south", trades=["fish", "general"])
    stall_row(g, 174, 182, 172, GL, rng, "south", trades=["potter", "farmer"])
    bell_tower(g, 70, 170, GL)
    barracks(g, 200, 170, 240, 192, GL, rng)
    # the harbour quarter: taverns and fishmongers stacked over the quay street, warehouses on the canal
    doors.append(inn(g, 65, 204, 92, 216, GL, rng, "south"))
    doors += dense_block(g, 95, 201, 108, 216, GL, rng, sides=("south", "north"), depth=6, trades=["fish", "none"], court=False)
    doors += dense_block(g, 147, 201, 190, 216, GL, rng, sides=("south", "north"), depth=7, trades=["fish", "none", "tanner", "none"], court=False)
    doors += dense_block(g, 197, 201, 237, 216, GL, rng, sides=("south", "north"), depth=7, trades=["none", "fish", "none"], court=False)
    warehouse(g, 100, 170, 108, 178, GL, rng, (108, 174))

    # ---- inner wall and upper city ----
    iw = GL2 + 18
    d2, along2, out2 = side_grids(g, 64, 191, 14, 113)
    curtain(g, 64, 191, 5, iw, GL2, az=14, bz=113, banners_every=18, grids=(d2, along2, out2))
    posts_on_walk(g, d2, along2, iw, "archer", dsel=1, every=7, offset=2)
    for (cx, cz) in ((66, 16), (189, 16), (66, 111), (189, 111)):
        ring_tower(g, cx, cz, 7, iw + 8, GL2, stages=1, cone=True, posts="archer",
                   doors=((1 if cx < 128 else -1, 0, iw), (0, 1 if cz < 64 else -1, iw)))
    for (x0, x1) in ((60, 63), (193, 196)):
        g.fill(x0, GL, 102, x1, GL + 3, 120, AIR)
        g.fill(x0, GL - 1, 102, x1, GL - 1, 120, ROAD)
        g.fill(x0, GL + 4, 102, x1, GL + 4, 120, STONE)
        for z in range(104, 119, 4):
            g.set(x0 - 1, GL + 3, z, "minecraft:lantern[hanging=true]") if g.get(x0 - 1, GL + 4, z) != AIR else None
    for x in (110, 146):
        # fourteen square, so the stair inside runs two wide
        square_tower(g, x - 2, 106, x + 11, 119, iw + 6, GL2, floors=(GL2 + 8, iw - 1), spiral_at=(x + 5, 113))
        g.fill(x - 2, iw, 111, x + 1, iw + 1, 113, AIR); g.fill(x + 8, iw, 111, x + 11, iw + 1, 113, AIR)
        g.fill(x - 2, iw - 1, 111, x + 1, iw - 1, 113, STONE); g.fill(x + 8, iw - 1, 111, x + 11, iw - 1, 113, STONE)
        g.fill(x + 4, GL2, 105, x + 5, GL2 + 2, 109, AIR)
        g.fill(x + 4, GL2 - 1, 105, x + 5, GL2 - 1, 109, FLOOR_STONE)
        g.mark("crossbowman", x + 4, iw + 6, 112)
    # inner gate: the barbican with the grand stair climbing from the basin head
    g.fill(119, 0, 104, 136, iw + 4, 114, STONE)
    pointed_arch(g, 124, 131, GL2, 10, 102, 114)
    portcullis_plane(g, 124, 131, GL2, 10, 113)
    parapet_rect(g, 118, 103, 137, 115, iw + 5)
    g.fill(119, iw + 5, 104, 136, iw + 9, 114, AIR)
    g.fill(119, iw + 4, 104, 136, iw + 4, 114, STONE)
    for x in (121, 127, 134):
        g.mark("crossbowman", x, iw + 5, 112)
    g.wall_banner(120, GL2 + 13, 115, "south"); g.wall_banner(135, GL2 + 13, 115, "south")
    grand_stair(g, 124, 131, 124, GL, GL2, direction=-1)
    g.fill(122, GL - 1, 114, 123, GL2 - 1, 124, STONE); g.fill(132, GL - 1, 114, 133, GL2 - 1, 124, STONE)
    statue_plinth(g, 120, 126, GL, h=5); statue_plinth(g, 135, 126, GL, h=5)
    g.mark("captain", 127, GL2, 98)
    # upper streets
    g.fill(70, GL2 - 1, 98, 185, GL2 - 1, 103, ROAD)
    g.fill(124, GL2 - 1, 78, 131, GL2 - 1, 104, ROAD)
    for x0 in (80, 172):
        g.fill(x0, GL2 - 1, 20, x0 + 3, GL2 - 1, 103, ROAD)
    g.fill(70, GL2 - 1, 18, 185, GL2 - 1, 21, ROAD)
    for z in range(30, 100, 14):
        g.mark("knight", 82, GL2, z); g.mark("knight", 174, GL2, z)
    for x in range(76, 180, 18):
        g.mark("knight", x, GL2, 100)
        lamp_post(g, x, 97, GL2)
    doors += row_z(g, 24, 96, 70, 9, GL2, rng, "east", lot=(12, 16), gap=3, wall=NOBLE, h=10)
    doors += row_z(g, 24, 96, 177, 9, GL2, rng, "west", lot=(12, 16), gap=3, wall=NOBLE, h=10)
    doors += row_x(g, 86, 118, 84, 12, GL2, rng, "south", lot=(14, 18), gap=3, wall=NOBLE, h=11)
    doors += row_x(g, 138, 170, 84, 12, GL2, rng, "south", lot=(14, 18), gap=3, wall=NOBLE, h=11)
    for z in range(26, 76, 10):
        for x in (84, 172):
            rng.integers(0, 2)          # the old jitter, drawn so the rest of the city comes out the same
            # three clear of the terrace wall, so the walk between them is two wide
            tree_oak(g, x, z + int(rng.integers(0, 4)), GL2, rng)
    for (x, z) in ((86, 40), (170, 40), (86, 60), (170, 60)):
        g.set(x, GL2, z, "minecraft:stone_brick_wall"); g.set(x, GL2 + 1, z, "minecraft:lantern[hanging=false]")
    z_end = grand_stair(g, 122, 133, 96, GL2, GL3, direction=-1)
    g.fill(120, GL2 - 1, z_end, 121, GL3 - 1, 96, STONE); g.fill(134, GL2 - 1, z_end, 135, GL3 - 1, 96, STONE)
    for z in (94, 86):
        statue_plinth(g, 118, z, GL2, h=5); statue_plinth(g, 137, z, GL2, h=5)
    xs2, zs2 = g.xz()
    low_face = (((xs2 == 63) | (xs2 == 192)) & (zs2 >= 13) & (zs2 <= 114)) | ((zs2 == 114) & (xs2 >= 63) & (xs2 <= 192))
    g.fill_mask(low_face & ~rect(g, 122, 114, 133, 114), GL - 1, GL2 - 2, STONE)
    g.fill_mask(low_face & ~rect(g, 122, 114, 133, 114) & (np.mod(xs2 + zs2, 6) < 3), GL + 1, GL + 4, DARK)
    keep_face = (((xs2 == 87) | (xs2 == 169)) & (zs2 >= 17) & (zs2 <= 77)) | ((zs2 == 77) & (xs2 >= 87) & (xs2 <= 169)) | ((zs2 == 17) & (xs2 >= 87) & (xs2 <= 169))
    g.fill_mask(keep_face & ~rect(g, 120, 77, 135, 77), GL2 - 1, GL3 - 2, STONE)
    g.fill_mask(keep_face & ~rect(g, 120, 77, 135, 77) & (np.mod(xs2 + zs2, 6) < 3), GL2 + 1, GL2 + 6, DARK)
    keep(g, GL3)

    # ---- outer curtain, towers, gates ----
    ax, bx, az, bz = 8, 247, 8, 227
    thick, walk = 7, GL + 28
    d, along, out = curtain(g, ax, bx, thick, walk, GL, az=az, bz=bz, banners_every=20)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=7, offset=3)
    ttop = walk + 12
    for x in range(ax + 40, bx - 20, 48):
        for (z0, sgn) in ((az - 3, 1), (bz - 10, -1)):
            if abs(x - 127) < 24 and sgn < 0:
                continue
            square_tower(g, x, z0, x + 13, z0 + 13, ttop, GL, floors=(GL + 9, GL + 18, walk - 1), spiral_at=(x + 6, z0 + 6))
            for (cx2, cz2) in ((x - 1, z0 - 1), (x + 14, z0 - 1), (x - 1, z0 + 14), (x + 14, z0 + 14)):
                if sgn > 0 and cz2 == z0 + 14:
                    continue
                bartizan(g, cx2, cz2, ttop + 1)
            wz0, wz1 = (z0 + 11, z0 + 14) if sgn > 0 else (z0 - 1, z0 + 2)
            for y in (walk, GL + 10):
                g.fill(x, y, z0 + 5, x + 2, y + 1, z0 + 7, AIR); g.fill(x + 11, y, z0 + 5, x + 13, y + 1, z0 + 7, AIR)
                g.fill(x, y - 1, z0 + 5, x + 2, y - 1, z0 + 7, STONE); g.fill(x + 11, y - 1, z0 + 5, x + 13, y - 1, z0 + 7, STONE)
            g.fill(x + 5, GL, wz0, x + 7, GL + 2, wz1, AIR)
            g.mark("archer", x + 3, ttop, z0 + 3); g.mark("archer", x + 10, ttop, z0 + 10); g.mark("crossbowman", x + 6, ttop, z0 + 6)
    for z in range(az + 40, bz - 20, 48):
        for (x0, sgn) in ((ax - 3, 1), (bx - 10, -1)):
            if abs(z - 162) < 28:
                continue
            square_tower(g, x0, z, x0 + 13, z + 13, ttop, GL, floors=(GL + 9, GL + 18, walk - 1), spiral_at=(x0 + 6, z + 6))
            for (cx2, cz2) in ((x0 - 1, z - 1), (x0 + 14, z - 1), (x0 - 1, z + 14), (x0 + 14, z + 14)):
                bartizan(g, cx2, cz2, ttop + 1)
            wx0, wx1 = (x0 + 11, x0 + 14) if sgn > 0 else (x0 - 1, x0 + 2)
            for y in (walk, GL + 10):
                g.fill(x0 + 5, y, z, x0 + 7, y + 1, z + 2, AIR); g.fill(x0 + 5, y, z + 11, x0 + 7, y + 1, z + 13, AIR)
                g.fill(x0 + 5, y - 1, z, x0 + 7, y - 1, z + 2, STONE); g.fill(x0 + 5, y - 1, z + 11, x0 + 7, y - 1, z + 13, STONE)
            g.fill(wx0, GL, z + 5, wx1, GL + 2, z + 7, AIR)
            g.mark("archer", x0 + 3, ttop, z + 3); g.mark("archer", x0 + 10, ttop, z + 10); g.mark("crossbowman", x0 + 6, ttop, z + 6)
    for (cx, cz) in ((ax + 2, az + 2), (bx - 2, az + 2), (ax + 2, bz - 2), (bx - 2, bz - 2)):
        ring_tower(g, cx, cz, 10, ttop + 4, GL, stages=2, cone=True, posts="archer",
                   doors=((1 if cx < 128 else -1, 0, walk), (0, 1 if cz < 128 else -1, walk)))
    # land gates west and east
    gtop = walk + 8
    for (gx, sgn, label) in ((ax, -1, "gate"), (bx, 1, "gate2")):
        cz_mid = 162
        bx0, bx1 = (gx - 7, gx + 10) if sgn < 0 else (gx - 10, gx + 7)
        g.fill(bx0, 0, cz_mid - 9, bx1, gtop - 1, cz_mid + 9, STONE)
        for tz in (cz_mid - 12, cz_mid + 12):
            ring_tower(g, gx + sgn, tz, 8, gtop + 6, GL, stages=1, cone=True, posts="crossbowman",
                       doors=((-sgn, 0, walk), (0, -1 if tz > cz_mid else 1, gtop)), wide=False)
        pointed_arch(g, cz_mid - 3, cz_mid + 3, GL, 11, bx0, bx1, axis="x")
        g.fill(bx0, GL - 1, cz_mid - 3, bx1, GL - 1, cz_mid + 3, ROAD)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 11, bx0 + 1 if sgn < 0 else bx1 - 1)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 11, bx1 - 1 if sgn < 0 else bx0 + 1)
        g.fill(bx0 + 1, GL + 13, cz_mid - 8, bx1 - 1, gtop - 2, cz_mid + 8, AIR)
        g.fill(bx0 + 1, GL + 12, cz_mid - 8, bx1 - 1, GL + 12, cz_mid + 8, PLANK)
        sp_x = (bx0 + 3) if sgn < 0 else (bx1 - 3)
        g.fill(bx0 + 1, walk - 1, cz_mid - 8, bx1 - 1, walk - 1, cz_mid + 8, PLANK)
        spiral_stair(g, sp_x, cz_mid + 5, GL + 13, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
        wx0, wx1 = (ax + 1, ax + 4) if sgn < 0 else (bx - 4, bx - 1)
        carve(g, wx0, walk, cz_mid - 22, wx1, walk + 1, cz_mid + 22, AIR, floor=STONE)
        parapet_rect(g, bx0 - 1, cz_mid - 10, bx1 + 1, cz_mid + 10, gtop)
        g.fill(bx0, gtop, cz_mid - 9, bx1, gtop + 4, cz_mid + 9, AIR)
        g.fill(bx0, gtop - 1, cz_mid - 9, bx1, gtop - 1, cz_mid + 9, STONE)
        g.fill(sp_x - 2, gtop - 1, cz_mid + 3, sp_x + 2, gtop - 1, cz_mid + 7, AIR)
        for z in (cz_mid - 6, cz_mid, cz_mid + 6):
            g.mark("crossbowman", (bx0 + bx1) // 2, gtop, z)
        from parts import gate_face_posts, murder_holes
        gate_face_posts(g, "x", (bx0, bx1), cz_mid, gtop)          # over the gate's mouth, front and back
        murder_holes(g, "x", bx0 + 3, bx1 - 3, cz_mid, GL + 11, GL + 12)  # the arch is open to GL + 10
        # a land gate stands on the plan's edge, where the machicolation pass cuts no holes (the walk behind a
        # crenel there runs off the grid): two men in the crenels over the gate lean out of them to see its mouth
        ring = bx0 - 1 if sgn < 0 else bx1 + 1
        if ring in (0, X - 1):
            g.lips = getattr(g, "lips", {})
            for z in (cz_mid - 2, cz_mid + 2):
                zc = z if z % 4 >= 2 else z + 2
                g.mark("crossbowman", ring, gtop, zc)
                g.lips[(ring, gtop, zc)] = (sgn, 0)
        outside = 0 if sgn < 0 else X - 1
        g.fill(min(outside, bx0), GL - 1, cz_mid - 3, max(outside, bx1), GL - 1, cz_mid + 3, ROAD)
        g.mark(label, outside, GL, cz_mid)
        g.mark("captain", gx - sgn * 14, GL, cz_mid)
        g.wall_banner(gx + sgn * 8, GL + 16, cz_mid - 7, "west" if sgn < 0 else "east")
        g.wall_banner(gx + sgn * 8, GL + 16, cz_mid + 7, "west" if sgn < 0 else "east")
    # the water gate: the canal passes under the south wall through a twenty-block arch between two drum towers
    wg0, wg1 = CANAL_X0 - 4, CANAL_X1 + 4
    g.fill(wg0, 0, bz - 8, wg1, gtop - 1, bz + 1, STONE)
    for tx in (wg0 - 5, wg1 + 5):
        ring_tower(g, tx, bz - 3, 8, gtop + 6, GL, stages=1, cone=True, posts="crossbowman",
                   doors=((0, -1, walk), (1 if tx < 128 else -1, 0, gtop)), wide=False)
    pointed_arch(g, CANAL_X0 + 2, CANAL_X1 - 2, GL - 1, 13, bz - 8, bz + 1)
    g.fill(CANAL_X0 + 2, GL - 7, bz - 8, CANAL_X1 - 2, GL - 7, bz + 1, Mix(("minecraft:sand", 50), ("minecraft:gravel", 50)))
    g.fill(CANAL_X0 + 2, GL - 6, bz - 8, CANAL_X1 - 2, GL - 2, bz + 1, "minecraft:water")
    g.fill(CANAL_X0 + 2, GL - 1, bz - 8, CANAL_X1 - 2, GL - 1, bz + 1, AIR)
    portcullis_plane(g, CANAL_X0 + 2, CANAL_X1 - 2, GL - 1, 13, bz)
    portcullis_plane(g, CANAL_X0 + 2, CANAL_X1 - 2, GL - 1, 13, bz - 7)
    # towpaths through the gate either side of the water, for the men and the carts
    for (x0, x1) in ((CANAL_X0 - 3, CANAL_X0 + 1), (CANAL_X1 - 1, CANAL_X1 + 3)):
        g.fill(x0, GL, bz - 8, x1, GL + 3, bz + 1, AIR)
        g.fill(x0, GL - 1, bz - 8, x1, GL - 1, bz + 1, ROAD)
    g.fill(wg0 + 1, GL + 14, bz - 7, wg1 - 1, gtop - 2, bz, AIR)
    g.fill(wg0 + 1, GL + 13, bz - 7, wg1 - 1, GL + 13, bz, PLANK)
    g.fill(wg0 + 1, walk - 1, bz - 7, wg1 - 1, walk - 1, bz, PLANK)
    spiral_stair(g, wg0 + 3, bz - 4, GL + 14, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
    carve(g, wg0 - 13, walk, bz - 5, wg1 + 13, walk + 1, bz - 2, AIR, floor=STONE)
    parapet_rect(g, wg0 - 1, bz - 9, wg1 + 1, bz + 2, gtop)
    g.fill(wg0, gtop, bz - 8, wg1, gtop + 4, bz + 1, AIR)
    g.fill(wg0, gtop - 1, bz - 8, wg1, gtop - 1, bz + 1, STONE)
    g.fill(wg0 + 1, gtop - 1, bz - 6, wg0 + 5, gtop - 1, bz - 2, AIR)
    for x in (wg0 + 4, 127, wg1 - 4):
        g.mark("crossbowman", x, gtop, bz)
    from parts import gate_face_posts
    gate_face_posts(g, "z", (bz + 1, bz - 8), (wg0 + wg1) // 2, gtop)
    g.wall_banner(wg0 + 2, GL + 16, bz + 2, "south"); g.wall_banner(wg1 - 2, GL + 16, bz + 2, "south")
    g.mark("captain", 112, GL, bz - 14)
    # the quay road along the foot of the wall, through vaulted ways under the water-gate towers
    g.fill(14, GL - 1, 217, QUAY_W1, GL - 1, 218, ROAD); g.fill(QUAY_E0, GL - 1, 217, 241, GL - 1, 218, ROAD)
    for (x0, x1) in ((wg0 - 14, wg0 - 1), (wg1 + 1, wg1 + 14), (47, 62), (191, 206)):
        g.fill(x0, GL, 217, x1, GL + 3, 218, AIR)
        g.fill(x0, GL - 1, 217, x1, GL - 1, 218, ROAD)
        g.fill(x0, GL + 4, 217, x1, GL + 4, 218, STONE)
        for x in range(x0 + 2, x1, 5):
            g.set(x, GL + 3, 216, "minecraft:lantern[hanging=true]") if g.get(x, GL + 4, 216) != AIR else None

    rng.shuffle(doors)
    for d in doors[:12]:
        x, z = d[0], d[-1]
        g.mark("house", x, GL if z > 114 or x < 64 or x > 191 else GL2, z)
    return g, {"name": "canal_city", "tier": 4, "GL": GL, "levels": (GL2, GL3)}
