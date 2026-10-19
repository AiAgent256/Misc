"""Market towns: the settlements between the castles. Built from the city's town pieces: party-wall
terraces along a street or two, a church with a spire, a market with stalls and a market hall where
the reeve sits, an inn, a smithy, a tithe barn, a well. Three plans: a walled hill town with a gate,
a crossroads town, and a village round a green. Tier 1: a town watch of a dozen men, a reeve, no lord.
The fields outside are painted in play (see Roads.paintFields)."""
import numpy as np
from grid import Grid, Mix, disk, ring, rect, rect_ring, gable_roof, cone_roof
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR, DARK_STAIR, SLAB_CAP,
                   RAIL, PLANK, PORTCULLIS, GLASS, building, lamp_post, pointed_arch)
from castle import portcullis_plane
from castle2 import parapet_rect
from city import inn, SQUARE, ALLEY, PLASTER
from town import terrace, lane, stall_cluster, well as town_well, townhouse

LOOT = "stormhold:chests/"
AIR = "minecraft:air"
GREEN = Mix(("minecraft:grass_block", 70), ("minecraft:coarse_dirt", 10), ("minecraft:dirt_path", 10), ("minecraft:podzol", 10))
WHITE = Mix(("minecraft:stone_bricks", 40), ("minecraft:polished_andesite", 25), ("minecraft:andesite", 20), ("minecraft:cobblestone", 15))
TRADES_A = ["armoury", "general", "cloth", "none", "chandler", "none"]
TRADES_B = ["weapons", "none", "potter", "general", "none", "butcher"]
TRADES_C = ["none", "none", "apothecary", "none", "fish", "none"]


# ---------------------------------------------------------------- pieces


def ground(g, GL, X, Z):
    g.fill(0, 0, 0, X - 1, GL - 2, Z - 1, FOUND)
    g.fill(0, GL - 1, 0, X - 1, GL - 1, Z - 1, GREEN)
    g.fill(0, GL, 0, X - 1, GL + 14, Z - 1, AIR)


def road(g, x0, z0, x1, z1, GL):
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, ROAD)
    g.fill(x0, GL, z0, x1, GL + 6, z1, AIR)


def church(g, x0, z0, GL, rng, along="z"):
    """A stone church: a nave with a steep slate roof, lancet windows, pews and an altar, and a west
    tower with a bell and a spire. Archers on the tower top, up a ladder. Returns the tower top."""
    if along == "z":
        nx0, nz0, nx1, nz1 = x0 + 7, z0, x0 + 17, z0 + 22
        tx0, tz0, tx1, tz1 = x0, z0 + 7, x0 + 6, z0 + 13
    else:
        nx0, nz0, nx1, nz1 = x0, z0 + 7, x0 + 22, z0 + 17
        tx0, tz0, tx1, tz1 = x0 + 7, z0, x0 + 13, z0 + 6
    wall_h = 8
    building(g, nx0, nz0, nx1, nz1, GL, wall_h, along, door_cells=(), wall_block=WHITE)
    # the roof building() put on is a gable; raise it steeper in slate
    gable_roof(g, nx0, nz0, nx1, nz1, GL + wall_h, along, ROOF_STAIR, ROOF, overhang=1, ridge_slab=SLAB_CAP)
    # lancet windows down both long walls, buttresses between
    if along == "z":
        for z in range(nz0 + 3, nz1 - 2, 4):
            for x in (nx0, nx1):
                g.fill(x, GL + 2, z, x, GL + 5, z, "minecraft:light_blue_stained_glass_pane")
                g.set(x, GL + 6, z, "minecraft:white_stained_glass_pane")
            for x, dx in ((nx0, -1), (nx1, 1)):
                g.fill(x + dx, GL, z + 2, x + dx, GL + 4, z + 2, STONE)
                g.set(x + dx, GL + 5, z + 2, f"minecraft:stone_brick_stairs[facing={'west' if dx < 0 else 'east'},half=bottom]")
        door = (nx0 + 5, nz1)
        g.fill(nx0 + 4, GL, nz1, nx0 + 6, GL + 3, nz1, AIR)
        g.set(nx0 + 5, GL + 4, nz1, "minecraft:stone_brick_stairs[facing=north,half=top]")
        altar = (nx0 + 5, nz0 + 3)
        aisle = [(nx0 + 5, z) for z in range(nz0 + 5, nz1)]
        pews = [(x, z) for z in range(nz0 + 6, nz1 - 2, 2) for x in (nx0 + 2, nx0 + 3, nx0 + 7, nx0 + 8)]
        pew_face = "north"
        rose = (nx0 + 5, nz1)
    else:
        for x in range(nx0 + 3, nx1 - 2, 4):
            for z in (nz0, nz1):
                g.fill(x, GL + 2, z, x, GL + 5, z, "minecraft:light_blue_stained_glass_pane")
                g.set(x, GL + 6, z, "minecraft:white_stained_glass_pane")
            for z, dz in ((nz0, -1), (nz1, 1)):
                g.fill(x + 2, GL, z + dz, x + 2, GL + 4, z + dz, STONE)
                g.set(x + 2, GL + 5, z + dz, f"minecraft:stone_brick_stairs[facing={'north' if dz < 0 else 'south'},half=bottom]")
        door = (nx1, nz0 + 5)
        g.fill(nx1, GL, nz0 + 4, nx1, GL + 3, nz0 + 6, AIR)
        g.set(nx1, GL + 4, nz0 + 5, "minecraft:stone_brick_stairs[facing=west,half=top]")
        altar = (nx0 + 3, nz0 + 5)
        aisle = [(x, nz0 + 5) for x in range(nx0 + 5, nx1)]
        pews = [(x, z) for x in range(nx0 + 6, nx1 - 2, 2) for z in (nz0 + 2, nz0 + 3, nz0 + 7, nz0 + 8)]
        pew_face = "west"
        rose = (nx1, nz0 + 5)
    # inside: a stone floor, a carpet down the aisle, pews, the altar on a step, candles
    g.fill(nx0 + 1, GL - 1, nz0 + 1, nx1 - 1, GL - 1, nz1 - 1, Mix(("minecraft:polished_andesite", 60), ("minecraft:stone_bricks", 40)))
    for (x, z) in aisle:
        g.set(x, GL, z, "minecraft:red_carpet")
    for (x, z) in pews:
        g.set(x, GL, z, f"minecraft:dark_oak_stairs[facing={pew_face},half=bottom]")
    ax, az = altar
    g.fill(ax - 1, GL, az - 1, ax + 1, GL, az + 1, "minecraft:polished_andesite_slab[type=bottom]")
    g.set(ax, GL, az, "minecraft:quartz_block"); g.set(ax, GL + 1, az, "minecraft:candle[candles=4,lit=true]")
    g.set(ax - 1, GL + 1, az, "minecraft:candle[candles=2,lit=true]"); g.set(ax + 1, GL + 1, az, "minecraft:candle[candles=2,lit=true]")
    for k in range(3):
        g.lantern(aisle[len(aisle) * (k + 1) // 4][0], GL + wall_h - 2, aisle[len(aisle) * (k + 1) // 4][1])
    # rose window over the door
    g.set(rose[0], GL + 5, rose[1], "minecraft:purple_stained_glass_pane")
    g.set(rose[0], GL + 6, rose[1], "minecraft:magenta_stained_glass_pane")
    # the tower: square, stone, a bell chamber with open arches, a slate spire, a ladder up the inside
    top = GL + 18
    g.fill(tx0, 0, tz0, tx1, top - 1, tz1, WHITE)
    g.fill(tx0 + 1, GL, tz0 + 1, tx1 - 1, top - 2, tz1 - 1, AIR)
    g.fill(tx0 + 1, GL - 1, tz0 + 1, tx1 - 1, GL - 1, tz1 - 1, FLOOR_STONE)
    g.fill(tx0 + 1, top - 1, tz0 + 1, tx1 - 1, top - 1, tz1 - 1, STONE)
    for (x, z) in ((tx0, tz0), (tx1, tz0), (tx0, tz1), (tx1, tz1)):
        for y in range(GL, top - 1, 2):
            g.set(x, y, z, "minecraft:polished_andesite")
    cx, cz = (tx0 + tx1) // 2, (tz0 + tz1) // 2
    for y in (GL + 5, GL + 10):
        for (x, z) in ((tx0, cz), (tx1, cz), (cx, tz0), (cx, tz1)):
            g.fill(x, y, z, x, y + 1, z, "minecraft:light_blue_stained_glass_pane")
    # bell chamber: open arches, the bell
    for (x, z) in ((tx0, cz), (tx1, cz), (cx, tz0), (cx, tz1)):
        g.fill(x, top - 5, z, x, top - 3, z, AIR)
    g.set(cx, top - 3, cz, "minecraft:bell[attachment=ceiling,facing=north]")
    g.set(cx, top - 2, cz, "minecraft:chain[axis=y]")
    # a door from the nave, a ladder up, a hatch onto the roof
    if along == "z":
        g.fill(tx1, GL, cz, tx1, GL + 2, cz, AIR); g.fill(tx1 + 1, GL, cz - 1, tx1 + 1, GL + 2, cz + 1, AIR)
    else:
        g.fill(cx, GL, tz1, cx, GL + 2, tz1, AIR); g.fill(cx - 1, GL, tz1 + 1, cx + 1, GL + 2, tz1 + 1, AIR)
    lx, lz = tx0 + 1, tz0 + 1
    for y in range(GL, top):
        g.set(lx, y, lz, "minecraft:ladder[facing=east]")
    g.fill(tx0 + 1, top - 4, tz0 + 1, tx1 - 1, top - 4, tz1 - 1, PLANK)          # bell-ringers' floor
    g.set(lx, top - 4, lz, "minecraft:ladder[facing=east]")
    g.set(lx, top - 1, lz, "minecraft:ladder[facing=east]")
    g.set(lx, top, lz, AIR)
    g.__dict__.setdefault("protected_xz", set()).add((lx, lz))
    # roof platform with a parapet, then the spire set back on it
    g.fill(tx0, top, tz0, tx1, top + 1, tz1, AIR)
    parapet_rect(g, tx0, tz0, tx1, tz1, top)
    g.fill(tx0, top - 1, tz0, tx1, top - 1, tz1, STONE)
    g.set(lx, top - 1, lz, AIR)
    g.set(lx, top - 1, lz, "minecraft:ladder[facing=east]")
    g.set(lx, top - 2, lz, "minecraft:ladder[facing=east]")
    g.fill(cx - 1, top, cz - 1, cx + 1, top + 2, cz + 1, WHITE)               # the spire's foot; a ring to stand on round it
    spire = cone_roof(g, cx, cz, 1, top + 3, ROOF, height_factor=4.0)
    g.set(cx, spire, cz, "minecraft:gold_block"); g.set(cx, spire + 1, cz, "minecraft:lightning_rod")
    g.mark("archer", tx0 + 1, top, tz1 - 1); g.mark("archer", tx1 - 1, top, tz0 + 1)
    g.mark("banner_stand", tx1 - 1, top, tz1 - 1)
    g.mark("knight", door[0] + (0 if along == "z" else 2), GL, door[1] + (2 if along == "z" else 0))
    g.wall_banner(door[0] - 2 if along == "z" else door[0], GL + 3, door[1] if along == "z" else door[1] - 2, "south" if along == "z" else "east")
    return (cx, top, cz)


def market_hall(g, x0, z0, x1, z1, GL, rng, door="south"):
    """The market hall: an open arcade on timber posts below, the reeve's hall above with the War Table,
    the town's strongbox and the reeve's bed. An outside stair."""
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    F2 = GL + 5
    g.fill(x0, 0, z0, x1, GL - 2, z1, FOUND)
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, FLOOR_STONE)
    g.fill(x0, GL, z0, x1, GL + 12, z1, AIR)
    # posts and the floor they hold
    for x in range(x0, x1 + 1, 3):
        for z in (z0, z1):
            g.fill(x, GL, z, x, F2 - 2, z, "minecraft:dark_oak_log[axis=y]")
    for z in range(z0, z1 + 1, 3):
        for x in (x0, x1):
            g.fill(x, GL, z, x, F2 - 2, z, "minecraft:dark_oak_log[axis=y]")
    g.fill(x0, F2 - 1, z0, x1, F2 - 1, z1, "minecraft:dark_oak_planks")
    g.fill(x0, F2 - 2, z0, x1, F2 - 2, z0, "minecraft:dark_oak_log[axis=x]"); g.fill(x0, F2 - 2, z1, x1, F2 - 2, z1, "minecraft:dark_oak_log[axis=x]")
    # stalls under the arcade
    for x in range(x0 + 2, x1 - 2, 4):
        g.set(x, GL, z0 + 1, "minecraft:barrel[facing=up]"); g.set(x + 1, GL, z0 + 1, "minecraft:hay_block[axis=y]")
        g.set(x, GL, z1 - 1, "minecraft:barrel[facing=up]"); g.set(x + 1, GL, z1 - 1, "minecraft:decorated_pot")
    g.mark("captain", cx, GL, cz)
    # the hall above: plaster walls, timber frame, a hipped slate roof
    h = 5
    g.fill(x0, F2, z0, x1, F2 + h - 1, z1, PLASTER)
    g.fill(x0 + 1, F2, z0 + 1, x1 - 1, F2 + h - 1, z1 - 1, AIR)
    for x in range(x0, x1 + 1, 3):
        g.fill(x, F2, z0, x, F2 + h - 1, z0, "minecraft:dark_oak_log[axis=y]"); g.fill(x, F2, z1, x, F2 + h - 1, z1, "minecraft:dark_oak_log[axis=y]")
    for z in range(z0, z1 + 1, 3):
        g.fill(x0, F2, z, x0, F2 + h - 1, z, "minecraft:dark_oak_log[axis=y]"); g.fill(x1, F2, z, x1, F2 + h - 1, z, "minecraft:dark_oak_log[axis=y]")
    for x in range(x0 + 2, x1 - 1, 3):
        g.fill(x, F2 + 1, z0, x, F2 + 2, z0, GLASS); g.fill(x, F2 + 1, z1, x, F2 + 2, z1, GLASS)
    gable_roof(g, x0, z0, x1, z1, F2 + h, "x" if x1 - x0 >= z1 - z0 else "z", ROOF_STAIR, ROOF, overhang=1, ridge_slab=SLAB_CAP)
    # inside: the reeve's table, the strongbox, a bed in the corner, the house banner
    g.fill(cx - 2, F2, cz, cx + 2, F2, cz, "minecraft:dark_oak_fence")
    g.fill(cx - 2, F2 + 1, cz, cx + 2, F2 + 1, cz, "minecraft:spruce_slab[type=top]")
    g.set(cx, F2 + 2, cz, "minecraft:candle[candles=3,lit=true]")
    g.chest(x0 + 1, F2, z0 + 1, "south", LOOT + "watchtower_treasury"); g.chest(x0 + 2, F2, z0 + 1, "south", LOOT + "watchtower_treasury")
    g.bed(x1 - 1, F2, z1 - 2, "west", "red")
    g.wall_banner(cx, F2 + 3, z0 + 1, "south"); g.wall_banner(cx, F2 + 3, z1 - 1, "north")
    g.lantern(cx - 3, F2 + 3, cz); g.lantern(cx + 3, F2 + 3, cz)
    g.mark("lord", cx, F2, cz - 2)
    g.mark("war_table", cx + 1, F2, cz + 2)
    # the stair: outside the door side, up to a landing and a door in the hall wall
    if door == "south":
        sx = x1 + 1
        for k in range(F2 - GL):
            g.fill(sx, GL + k, z1 - k, sx, GL + k, z1 - k, "minecraft:stone_brick_stairs[facing=north,half=bottom]")
            g.fill(sx, 0, z1 - k, sx, GL + k - 1, z1 - k, STONE)
            g.set(sx + 1, GL + k, z1 - k, "minecraft:stone_brick_wall")
        g.fill(sx, F2 - 1, z1 - (F2 - GL), sx, F2 - 1, z0, STONE)
        g.fill(sx, 0, z1 - (F2 - GL) - 1, sx, F2 - 2, z0, STONE)
        g.fill(sx, F2, z0, sx, F2 + 2, z1 - (F2 - GL), AIR)
        g.fill(sx + 1, F2, z0, sx + 1, F2, z1 - (F2 - GL), "minecraft:stone_brick_wall")
        g.fill(x1, F2, cz, x1, F2 + 2, cz, AIR)
        g.set(x1, F2, cz, "minecraft:spruce_door[facing=west,half=lower]"); g.set(x1, F2 + 1, cz, "minecraft:spruce_door[facing=west,half=upper]")
    g.mark("rally", cx, GL, z1 + 4)
    g.mark("knight", x0 - 2, GL, cz); g.mark("knight", cx, GL, z1 + 2)


def smithy(g, x0, z0, x1, z1, GL, rng, door="south"):
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    dcell = {"north": (cx, z0), "south": (cx, z1), "west": (x0, cz), "east": (x1, cz)}[door]
    building(g, x0, z0, x1, z1, GL, 5, "x", door_cells=(dcell,), wall_block=STONE, chimney=(x0 + 1, z0 + 1))
    g.set(x0 + 1, GL, z0 + 2, "minecraft:blast_furnace[facing=east,lit=true]")
    g.set(x0 + 2, GL, z0 + 1, "minecraft:campfire[lit=true,signal_fire=false]")
    g.set(x0 + 3, GL, z0 + 2, "minecraft:anvil[facing=north]")
    g.set(x1 - 1, GL, z0 + 1, "minecraft:water_cauldron[level=3]")
    g.set(x1 - 1, GL, z0 + 2, "minecraft:grindstone[face=floor,facing=west]")
    g.chest(x1 - 1, GL, z1 - 1, "west", LOOT + "castle_smithy")
    g.set(x0 + 1, GL, z1 - 1, "minecraft:smithing_table")
    g.mark("smithy", dcell[0], GL, dcell[1])
    g.mark("shop_smith", cx, GL, cz)                 # the smith at his anvil


def tithe_barn(g, x0, z0, x1, z1, GL, rng, door="south"):
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    dcell = {"north": (cx, z0), "south": (cx, z1), "west": (x0, cz), "east": (x1, cz)}[door]
    building(g, x0, z0, x1, z1, GL, 7, "x" if x1 - x0 >= z1 - z0 else "z", door_cells=(dcell,), timber=True)
    for x in range(x0 + 1, x1, 2):
        for z in (z0 + 1, z1 - 1):
            if (x, z) != dcell:
                g.set(x, GL, z, "minecraft:hay_block[axis=y]")
                if (x + z) % 3 == 0:
                    g.set(x, GL + 1, z, "minecraft:hay_block[axis=y]")
    g.barrel(x0 + 1, GL, cz, LOOT + "castle_granary"); g.barrel(x1 - 1, GL, cz, LOOT + "castle_granary")
    g.barrel(x0 + 1, GL + 1, cz, LOOT + "castle_granary")
    g.chest(x1 - 1, GL, cz + 1, "west", LOOT + "castle_store")


BOARD_SIGNS = (("Shops",), ("Houses", "for sale"), ("NOTICE", "BOARD", "", "Right-click"), ("Work", "for hire"), ("Ask the", "reeve"))


def notice_board(g, x, z, GL, facing="south"):
    from town import sign_nbt
    g.fill(x - 2, GL, z, x + 2, GL + 2, z, "minecraft:dark_oak_planks")
    for i, xx in enumerate(range(x - 2, x + 3)):
        g.set(xx, GL + 3, z, "minecraft:dark_oak_slab[type=bottom]")
        g.set(xx, GL + 1, z + (1 if facing == "south" else -1), f"minecraft:oak_wall_sign[facing={facing}]",
              nbt=sign_nbt(BOARD_SIGNS[i], "minecraft:sign"))
    g.mark("notice_board", x, GL, z + (2 if facing == "south" else -2))


def toll_gate(g, x0, x1, z, GL):
    """A timber gate across the road: two posts, a beam, a portcullis between them."""
    for x in (x0 - 1, x1 + 1):
        g.fill(x, GL, z, x, GL + 4, z, "minecraft:dark_oak_log[axis=y]")
        g.fill(x, GL, z - 1, x, GL + 1, z - 1, "minecraft:dark_oak_fence")
        g.fill(x, GL, z + 1, x, GL + 1, z + 1, "minecraft:dark_oak_fence")
    g.fill(x0 - 1, GL + 5, z, x1 + 1, GL + 5, z, "minecraft:dark_oak_log[axis=x]")
    g.fill(x0, GL + 4, z, x1, GL + 4, z, "minecraft:dark_oak_planks")
    g.set((x0 + x1) // 2, GL + 3, z + 1, "minecraft:lantern[hanging=true]")
    portcullis_plane(g, x0, x1, GL, 4, z)


def town_wall(g, x0, z0, x1, z1, GL, gate_x0, gate_x1):
    """A low town wall two thick with a walk, a crenellated parapet, a gate arch on the south side with
    a portcullis, and a stair up to the walk inside the gate. Archers on the walk."""
    walk = GL + 5
    outer = rect_ring(g, x0, z0, x1, z1, 2)
    g.fill_mask(outer, 0, walk - 2, STONE)
    g.fill_mask(outer, walk - 1, walk - 1, PAVE)
    g.fill_mask(outer, walk, walk + 6, AIR)
    parapet_rect(g, x0, z0, x1, z1, walk)
    # plinth
    for x in (x0 - 1, x1 + 1):
        g.fill(x, 0, z0 - 1, x, GL, z1 + 1, DARK)
    g.fill(x0 - 1, 0, z0 - 1, x1 + 1, GL, z0 - 1, DARK); g.fill(x0 - 1, 0, z1 + 1, x1 + 1, GL, z1 + 1, DARK)
    # the gate: a thicker block, an arch, the portcullis
    gx0, gx1 = gate_x0 - 4, gate_x1 + 4
    g.fill(gx0, 0, z1 - 4, gx1, walk + 3, z1 + 1, STONE)
    g.fill(gx0, walk + 4, z1 - 4, gx1, walk + 8, z1 + 1, AIR)
    parapet_rect(g, gx0, z1 - 4, gx1, z1 + 1, walk + 4)
    g.fill(gx0 + 1, walk + 3, z1 - 3, gx1 - 1, walk + 3, z1, PAVE)
    g.fill(gx0 + 1, walk + 4, z1 - 3, gx1 - 1, walk + 7, z1, AIR)
    pointed_arch(g, gate_x0, gate_x1, GL, 6, z1 - 4, z1 + 1)
    g.fill(gate_x0, GL - 1, z1 - 4, gate_x1, GL - 1, z1 + 1, ROAD)
    portcullis_plane(g, gate_x0, gate_x1, GL, 6, z1)
    # the walk runs through the gate block; a ladder from it up onto the gate roof
    g.fill(gx0, walk, z1 - 1, gx1, walk + 2, z1 - 1, AIR)
    for y in range(walk, walk + 4):
        g.set(gx0 + 1, y, z1 - 1, "minecraft:ladder[facing=north]")
    g.set(gx0 + 1, walk + 3, z1 - 1, "minecraft:ladder[facing=north]")
    g.__dict__.setdefault("protected_xz", set()).add((gx0 + 1, z1 - 1))
    # a stair from the street up to the walk, against the inside of the wall east of the gate
    sx = gate_x1 + 6
    for k in range(walk - GL):
        g.set(sx + k, GL + k, z1 - 2, "minecraft:stone_brick_stairs[facing=west,half=bottom]")
        g.fill(sx + k, 0, z1 - 2, sx + k, GL + k - 1, z1 - 2, STONE)
        g.set(sx + k, GL + k, z1 - 3, "minecraft:stone_brick_wall")
    g.set(sx + (walk - GL), walk - 1, z1 - 2, PAVE)
    g.fill(sx + (walk - GL), 0, z1 - 2, sx + (walk - GL), walk - 2, z1 - 2, STONE)
    g.fill(sx + (walk - GL), walk, z1 - 2, sx + (walk - GL), walk + 2, z1 - 1, AIR)
    # the walk's posts
    for x in range(x0 + 8, x1 - 6, 14):
        g.mark("archer", x, walk, z0 + 1); g.mark("archer", x, walk, z1 - 1)
    for z in range(z0 + 10, z1 - 8, 16):
        g.mark("archer", x0 + 1, walk, z); g.mark("archer", x1 - 1, walk, z)
    g.mark("crossbowman", gate_x0 - 1, walk + 4, z1 - 1); g.mark("crossbowman", gate_x1 + 1, walk + 4, z1 - 1)
    g.wall_banner(gate_x0 - 2, GL + 4, z1 + 2, "south"); g.wall_banner(gate_x1 + 2, GL + 4, z1 + 2, "south")
    return walk


def sell_homes(g, rng, n=10):
    """Up to `n` of the town's homes are for sale: their doors become 'house' plots the game sells."""
    homes = list(g.markers.pop("dwelling", []))
    rng.shuffle(homes)
    for (x, y, z) in homes[:n]:
        g.mark("house", x, y, z)


# ---------------------------------------------------------------- the three towns


def _framed(fn):
    """Build a town with the timber-framed ground storeys on (its third revision), and turn them off after."""
    import functools
    import town as _town

    @functools.wraps(fn)
    def build(seed=fn.__defaults__[0]):
        _town.FACADES = True
        try:
            return fn(seed)
        finally:
            _town.FACADES = False
    build.__defaults__ = fn.__defaults__
    return build


@_framed
def town_walled(seed=301):
    """A walled hill town: one gate, a main street up to the market under the church."""
    X, Z, H = 100, 100, 48
    GL = 5
    g = Grid(X, H, Z, seed)
    rng = g.rng
    ground(g, GL, X, Z)
    x0, z0, x1, z1 = 8, 8, 91, 91
    gx0, gx1 = 46, 51
    town_wall(g, x0, z0, x1, z1, GL, gx0, gx1)
    # streets: the main street north from the gate, a cross street, the square under the church
    road(g, gx0, 56, gx1, 97, GL)
    road(g, 12, 56, 87, 60, GL)
    g.fill(30, GL - 1, 26, 70, GL - 1, 55, SQUARE)
    g.fill(30, GL, 26, 70, GL + 10, 55, AIR)
    church(g, 12, 12, GL, rng, along="z")
    market_hall(g, 56, 30, 70, 40, GL, rng, door="south")
    inn(g, 72, 44, 86, 54, GL, rng, "south")
    smithy(g, 76, 80, 84, 86, GL, rng, door="west")
    tithe_barn(g, 74, 12, 86, 24, GL, rng, door="south")
    town_well(g, 40, 44, GL)
    stall_cluster(g, 32, 28, 52, 42, GL, rng, trades=["baker", "fletcher", "general", "farmer", "butcher"])
    notice_board(g, 50, 48, GL, facing="south")
    # terraces: north of the cross street (west of the square), south of it, and down the main street
    terrace(g, 12, 28, 55, 8, GL, rng, "south", trades=TRADES_A)
    terrace(g, 12, 34, 61, 8, GL, rng, "north", trades=TRADES_B)
    terrace(g, 64, 86, 61, 8, GL, rng, "north", trades=TRADES_C)
    terrace(g, 72, 86, 53, 8, GL, rng, "west", trades=["none", "farmer", "none"])
    terrace(g, 72, 86, 44, 8, GL, rng, "east", trades=["none", "none", "apothecary"])
    for z in range(66, 86, 10):
        lamp_post(g, gx0 - 1, z, GL); lamp_post(g, gx1 + 1, z, GL)
    for x in range(16, 86, 14):
        lamp_post(g, x, 61, GL)
    for (x, z) in ((48, 70), (48, 84), (20, 58), (80, 58), (48, 50), (64, 22)):
        g.mark("knight", x, GL, z)
    g.mark("gate", 48, GL, 96)
    sell_homes(g, rng)
    return g, {"name": "town_walled", "tier": 1, "GL": GL, "kind": "town", "character": "rustic"}


@_framed
def town_cross(seed=302):
    """A crossroads town: two roads meet at a market; the church and the hall face the crossing."""
    X, Z, H = 104, 88, 48
    GL = 5
    g = Grid(X, H, Z, seed)
    rng = g.rng
    ground(g, GL, X, Z)
    road(g, 48, 0, 55, Z - 1, GL)
    road(g, 0, 40, X - 1, 47, GL)
    g.fill(34, GL - 1, 28, 69, GL - 1, 59, SQUARE)
    g.fill(34, GL, 28, 69, GL + 10, 59, AIR)
    road(g, 48, 28, 55, 59, GL); road(g, 34, 40, 69, 47, GL)
    church(g, 72, 8, GL, rng, along="x")
    market_hall(g, 36, 12, 50, 22, GL, rng, door="south")
    inn(g, 74, 50, 90, 60, GL, rng, "north")
    smithy(g, 72, 64, 80, 70, GL, rng, door="west")
    tithe_barn(g, 84, 64, 98, 74, GL, rng, door="west")
    town_well(g, 42, 52, GL)
    stall_cluster(g, 56, 30, 68, 39, GL, rng, trades=["fletcher", "general", "baker"])
    stall_cluster(g, 56, 49, 68, 58, GL, rng, trades=["farmer", "butcher", "general"])
    notice_board(g, 42, 30, GL, facing="south")
    terrace(g, 4, 30, 39, 8, GL, rng, "south", trades=TRADES_A)
    terrace(g, 74, 100, 39, 8, GL, rng, "south", trades=TRADES_B)
    terrace(g, 4, 30, 48, 8, GL, rng, "north", trades=["none", "chandler", "none"])
    terrace(g, 62, 84, 57, 8, GL, rng, "west", trades=["none", "none", "apothecary"])
    terrace(g, 62, 84, 46, 8, GL, rng, "east", trades=["none", "general", "none"])
    for z in range(6, 84, 12):
        lamp_post(g, 47, z, GL); lamp_post(g, 56, z, GL)
    for x in range(6, 100, 14):
        lamp_post(g, x, 39, GL)
    toll_gate(g, 48, 55, 2, GL)
    for (x, z) in ((51, 10), (51, 76), (12, 43), (90, 43), (40, 44), (62, 44)):
        g.mark("knight", x, GL, z)
    g.mark("gate", 51, GL, 0)
    sell_homes(g, rng)
    return g, {"name": "town_cross", "tier": 1, "GL": GL, "kind": "town", "character": "martial"}


@_framed
def town_green(seed=303):
    """A village round a green: a ring road, a pond, the church at the top, houses facing in."""
    X, Z, H = 96, 96, 48
    GL = 5
    g = Grid(X, H, Z, seed)
    rng = g.rng
    ground(g, GL, X, Z)
    for (a, b, c, d) in ((20, 20, 75, 24), (20, 71, 75, 75), (20, 20, 24, 75), (71, 20, 75, 75)):
        road(g, a, b, c, d, GL)
    road(g, 46, 75, 51, 95, GL)
    # the pond
    g.fill(35, GL - 4, 39, 51, GL - 4, 53, "minecraft:clay")
    g.fill(36, GL - 3, 40, 50, GL - 1, 52, "minecraft:water")
    for (x, z) in ((35, 39), (51, 39), (35, 53), (51, 53), (43, 38), (43, 54)):
        g.set(x, GL - 1, z, "minecraft:sand")
    church(g, 30, 2, GL, rng, along="x")
    market_hall(g, 54, 30, 66, 40, GL, rng, door="south")
    inn(g, 26, 78, 40, 88, GL, rng, "north")
    smithy(g, 78, 30, 86, 36, GL, rng, door="west")
    tithe_barn(g, 4, 78, 18, 90, GL, rng, door="north")
    town_well(g, 60, 56, GL)
    stall_cluster(g, 28, 58, 44, 68, GL, rng, trades=["baker", "general", "fletcher"])
    notice_board(g, 48, 30, GL, facing="south")
    terrace(g, 56, 70, 19, 8, GL, rng, "south", trades=["butcher", "none"])
    terrace(g, 26, 70, 19, 8, GL, rng, "east", trades=TRADES_A)
    terrace(g, 54, 70, 76, 8, GL, rng, "north", trades=["none", "farmer"])
    terrace(g, 42, 68, 76, 8, GL, rng, "west", trades=["none", "weapons", "none"])
    for (x, z) in ((22, 30), (22, 60), (73, 30), (73, 60), (48, 22), (48, 73)):
        lamp_post(g, x, z, GL)
    toll_gate(g, 46, 51, 93, GL)
    for (x, z) in ((48, 80), (48, 60), (30, 22), (66, 22), (22, 46), (73, 46)):
        g.mark("knight", x, GL, z)
    g.mark("gate", 48, GL, 95)
    sell_homes(g, rng)
    return g, {"name": "town_green", "tier": 1, "GL": GL, "kind": "town", "character": "pious"}
