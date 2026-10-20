"""A fortified manor: a lord's house that can hold its own.

A stone base of two terraces: a barmkin wall six thick round the court, its walk four wide behind a
crenellated parapet, round towers at three corners and a tall square tower at the fourth with its roof
standing open on timber posts over the platform; and in the court the manor itself, a stone ground storey
whose roof is a second crenellated terrace, and on it two timber-framed storeys in yellow plaster under a
steep slate roof with dormers, a balcony and a brick chimney. A perron climbs from the court to the house
terrace; a pergola, planters and beds of flowers fill the corner of the court by the gate.
"""
import numpy as np

from grid import Grid, Mix, disk, rect, rect_ring, FACING_VEC, OPPOSITE
from parts import (SILL, STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR, DARK_STAIR,
                   SLAB_CAP, RAIL, PLANK, DARK_PLANK, GLASS, round_tower, square_tower, pointed_arch, building,
                   lamp_post, wall_stair)
from castle import portcullis_plane
from castle2 import parapet_rect

LOOT = "stormhold:chests/"
AIR = "minecraft:air"
PLASTER = Mix(("minecraft:yellow_terracotta", 55), ("minecraft:white_terracotta", 30), ("minecraft:smooth_sandstone", 15))
POST = "minecraft:stripped_dark_oak_log[axis=y]"
BEAM_X = "minecraft:dark_oak_log[axis=x]"
BEAM_Z = "minecraft:dark_oak_log[axis=z]"
GRASS = Mix(("minecraft:grass_block", 70), ("minecraft:coarse_dirt", 15), ("minecraft:dirt_path", 10), ("minecraft:gravel", 5))
FLOWERS = ("minecraft:poppy", "minecraft:cornflower", "minecraft:oxeye_daisy", "minecraft:allium", "minecraft:azure_bluet",
           "minecraft:lily_of_the_valley", "minecraft:red_tulip", "minecraft:white_tulip")


def steep_gable(g, x0, z0, x1, z1, y, wall, overhang=1):
    """A steep gable roof over x0..x1 / z0..z1, ridge along x, rising two for every one in: a full tile under a
    stair at each step, the gable ends filled in plaster and timber. Returns the ridge's top."""
    a0, a1 = z0 - overhang, z1 + overhang
    k = 0
    yy = y
    while a0 + k < a1 - k:
        yy = y + 2 * k
        for row, face in ((a0 + k, "south"), (a1 - k, "north")):
            g.fill(x0 - overhang, yy, row, x1 + overhang, yy, row, ROOF)
            g.fill(x0 - overhang, yy + 1, row, x1 + overhang, yy + 1, row, f"{ROOF_STAIR}[facing={face},half=bottom]")
        if a0 + k + 1 < a1 - k:
            lo, hi = max(a0 + k + 1, z0), min(a1 - k - 1, z1)
            for x in (x0, x1):
                g.fill(x, yy, lo, x, yy + 1, hi, wall)
            g.fill(x0 + 1, yy, a0 + k + 1, x1 - 1, yy + 1, a1 - k - 1, AIR)
        k += 1
    ridge = y + 2 * k
    if a0 + k == a1 - k:
        g.fill(x0 - overhang, ridge, a0 + k, x1 + overhang, ridge, a0 + k, ROOF)
        g.fill(x0 - overhang, ridge + 1, a0 + k, x1 + overhang, ridge + 1, a0 + k, "minecraft:deepslate_tile_slab[type=bottom]")
        for x in (x0, x1):
            g.set(x, ridge, a0 + k, BEAM_X)
        return ridge + 1
    g.fill(x0 - overhang, ridge, a0 + k - 1, x1 + overhang, ridge, a1 - k + 1, "minecraft:deepslate_tile_slab[type=bottom]")
    return ridge


def frame_wall(g, xa, za, xb, zb, y0, y1, every=3):
    """A timber-framed wall from (xa, za) to (xb, zb) (one of them the same), y0..y1: plaster infill, a post at
    each end and every `every`, a beam along the top and the sill, a brace in every other bay."""
    horiz = za == zb
    n = (xb - xa) if horiz else (zb - za)
    for i in range(n + 1):
        x, z = (xa + i, za) if horiz else (xa, za + i)
        post = i == 0 or i == n or i % every == 0
        g.fill(x, y0, z, x, y1, z, POST if post else PLASTER)
    beam = BEAM_X if horiz else BEAM_Z
    if horiz:
        g.fill(xa, y0, za, xb, y0, za, beam); g.fill(xa, y1, za, xb, y1, za, beam)
    else:
        g.fill(xa, y0, za, xa, y0, zb, beam); g.fill(xa, y1, za, xa, y1, zb, beam)


def windows(g, xa, za, xb, zb, y, f, every=3, h=2):
    """Glazed windows in the middle of each bay of a framed wall, with a sill box of flowers under some."""
    horiz = za == zb
    n = (xb - xa) if horiz else (zb - za)
    for i in range(1, n):
        if i % every != every // 2 + 1 and every > 2:
            continue
        x, z = (xa + i, za) if horiz else (xa, za + i)
        g.fill(x, y, z, x, y + h - 1, z, GLASS)
        ox, oz = x + FACING_VEC[f][0], z + FACING_VEC[f][1]
        if g.get(ox, y - 1, oz) == AIR and (x + z) % 2 == 0:
            g.set(ox, y - 1, oz, "minecraft:spruce_trapdoor[facing=" + f + ",half=top,open=false]")


def wall_gable(g, xc, zface, ye, f="south", half=2):
    """A cross gable on the roof's south slope: the wall below carried up into a gable of its own over a window,
    five wide, a steep little roof running back into the main one."""
    dz = FACING_VEC[f][1]
    # the gable wall, framed, with a pair of windows
    for d in range(-half, half + 1):
        top = ye + 2 * (half - abs(d))
        g.fill(xc + d, ye, zface, xc + d, top - 1, zface, POST if abs(d) == half or d == 0 else PLASTER)
    g.fill(xc - half, ye, zface, xc + half, ye, zface, BEAM_X)
    for d in (-1, 1):
        g.fill(xc + d, ye + 1, zface, xc + d, ye + half, zface, GLASS)
    # its roof: from a block out over the wall back into the main roof
    for k in range(-1, half + 2):
        z = zface - dz * k
        for d in range(-half - 1, half + 2):
            h = ye + 2 * (half + 1 - abs(d)) - 2
            if abs(d) == half + 1:
                h = ye - 1
            face = "east" if d < 0 else "west"
            x = xc + d
            if d == 0:
                g.set(x, h, z, ROOF)
                g.set(x, h + 1, z, "minecraft:deepslate_tile_slab[type=bottom]")
            else:
                g.set(x, h, z, ROOF)
                g.set(x, h + 1, z, f"{ROOF_STAIR}[facing={face},half=bottom]")
            if k >= 0:
                g.fill(x, ye, z - dz, x, h - 1, z - dz, AIR) if abs(d) < half and k < half + 1 else None


def fortified_manor(seed=41):
    W, H, GL = 60, 60, 4
    g = Grid(W, H, W, seed)
    rng = g.rng
    xs, zs = g.xz()
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, W - 1, GRASS)
    g.fill(0, GL, 0, W - 1, GL + 12, W - 1, AIR)

    # ---- the barmkin: six thick, the walk on top four wide behind the parapet, a rail on the court side ----
    x0, z0, x1, z1 = 6, 6, 53, 53
    WT = GL + 6                                              # standing level on the walk
    body = rect_ring(g, x0, z0, x1, z1, 6)
    g.fill_mask(body, 0, WT - 1, STONE)
    g.fill_mask(rect_ring(g, x0 - 1, z0 - 1, x1 + 1, z1 + 1, 1), 0, GL + 1, DARK)
    g.fill_mask(rect_ring(g, x0, z0, x1, z1, 1), GL + 3, GL + 3, DARK)
    g.fill_mask(rect(g, x0 + 1, z0 + 1, x1 - 1, z1 - 1) & ~rect(g, x0 + 5, z0 + 5, x1 - 5, z1 - 5), WT, WT + 4, AIR)
    parapet_rect(g, x0, z0, x1, z1, WT)     # flush: six high, a ledge would push the dead ground out (see machicolate)
    g.fill_mask(rect_ring(g, x0 + 5, z0 + 5, x1 - 5, z1 - 5, 1), WT, WT, RAIL)
    # flower boxes on the rail every few blocks, as on the house's own terrace
    rail = rect_ring(g, x0 + 5, z0 + 5, x1 - 5, z1 - 5, 1)
    g.fill_mask(rail & (np.mod(xs + zs, 6) == 0), WT + 1, WT + 1, "minecraft:potted_azalea_bush")

    # ---- the gate in the south wall: an arched passage, a portcullis, two half-round towers either side ----
    gx0, gx1 = 28, 31
    g.fill(gx0, GL - 1, z1 - 5, gx1, GL - 1, W - 1, ROAD)
    pointed_arch(g, gx0, gx1, GL, 5, z1 - 5, z1)
    portcullis_plane(g, gx0, gx1, GL, 5, z1)
    portcullis_plane(g, gx0, gx1, GL, 5, z1 - 4)  # a second at the inner end: break the first and you're in a box under the walls
    g.mark("gate", (gx0 + gx1) // 2, GL, W - 1)

    # ---- towers: round ones at three corners and either side of the gate, the great tower at the north-east ----
    top_r = WT + 7
    import parts
    plan_cap, parts.CAP_STYLE = parts.CAP_STYLE, "flat"     # the little towers stand open and crenellated
    for (cx, cz) in ((x0, z0), (x0, z1), (x1, z1)):
        horiz = 1 if cx == x0 else -1
        vert = 1 if cz == z0 else -1
        round_tower(g, cx, cz, 5, top_r, GL, floors=(GL + 4, WT - 1), spiral=True, cone=True, t=2,
                    doors=((horiz, 0, WT), (0, vert, WT)))
        g.mark("archer", cx + horiz, top_r, cz + vert)
        g.mark("archer", cx - horiz, top_r, cz - vert)
    for cx in (gx0 - 6, gx1 + 6):
        round_tower(g, cx, z1 - 1, 5, top_r, GL, floors=(GL + 4, WT - 1), spiral=True, cone=True, t=2,
                    doors=((1, 0, WT), (-1, 0, WT)))
        g.mark("crossbowman", cx, top_r, z1 + 1)
    parts.CAP_STYLE = plan_cap
    # the gate towers' plinths ran into the passage, leaving it two wide: clear it to the arch's four, either side
    # of the portcullis
    g.fill(gx0, GL, z1 - 5, gx1, GL + 1, z1 - 1, AIR)
    g.fill(gx0, GL, z1 + 1, gx1, GL + 1, W - 1, AIR)
    # the great tower, seventeen up from the walk, its roof standing on posts over an open platform
    tx0, tz0, tx1, tz1 = 47, 0, 59, 12
    ttop = WT + 18
    square_tower(g, tx0, tz0, tx1, tz1, ttop, GL, floors=(GL + 5, WT - 1, WT + 6, WT + 12),
                 spiral_at=(53, 6), wide=None, cone=True, banner_sides=("south", "west"))
    g.fill(tx0, WT, 7, tx0 + 2, WT + 2, 9, AIR)              # through the west wall onto the north walk
    g.fill(49, WT, tz1 - 2, 51, WT + 2, tz1, AIR)            # and the south wall onto the east walk
    g.mark("banner_stand", tx0 + 4, ttop, tz0 + 4)
    for (x, z) in ((tx0 + 3, tz1 - 3), (tx1 - 3, tz1 - 3), (tx1 - 3, tz0 + 3)):
        g.mark("archer", x, ttop, z)
    g.mark("crossbowman", tx0 + 3, ttop, tz0 + 3)
    # the barmkin walk's posts: a man at every few crenels; the embrasure pass puts each up on his sill
    for x in range(x0 + 8, x1 - 6, 4):
        g.mark("archer", x, WT, z0 + 1)
        if not gx0 - 11 <= x <= gx1 + 11:
            g.mark("archer", x, WT, z1 - 1)
    for z in range(z0 + 8, z1 - 6, 4):
        g.mark("archer", x0 + 1, WT, z); g.mark("archer", x1 - 1, WT, z)
    for x in (gx0 - 2, gx1 + 2):
        g.mark("archer", x, WT, z1 - 1)
    # three-wide flights from the court up to the walk on the west and east walls, south of the house
    wall_stair(g, x0 + 6, 44, GL, WT, "z", "west")
    wall_stair(g, x1 - 6, 44, GL, WT, "z", "east")
    for rx in (x0 + 5, x1 - 5):
        g.fill(rx, WT, 37, rx, WT + 2, 39, AIR)             # no rail where the flights land

    # ---- the manor: a stone storey, its roof a crenellated terrace, two timber storeys on that ----
    bx0, bz0, bx1, bz1 = 14, 14, 45, 34
    TT = GL + 6                                              # the terrace's standing level
    g.fill(bx0, 0, bz0, bx1, TT - 1, bz1, STONE)
    g.fill(bx0 + 2, GL, bz0 + 2, bx1 - 2, TT - 2, bz1 - 2, AIR)
    g.fill(bx0 + 2, GL - 1, bz0 + 2, bx1 - 2, GL - 1, bz1 - 2, FLOOR_STONE)
    g.fill_mask(rect_ring(g, bx0, bz0, bx1, bz1, 1), GL, GL + 1, DARK)            # a dark footing course
    g.fill_mask(rect_ring(g, bx0, bz0, bx1, bz1, 1), TT - 3, TT - 3, DARK)        # and a string under the terrace
    # the hall's door, its windows, a cross wall making a kitchen at the west end
    g.fill(29, GL, bz1 - 1, 30, GL + 2, bz1, AIR)
    g.stair(29, GL + 3, bz1 + 1, DARK_STAIR, "north", half="top"); g.stair(30, GL + 3, bz1 + 1, DARK_STAIR, "north", half="top")
    for x in range(bx0 + 4, bx1 - 2, 4):
        for z in (bz0, bz1):
            if x not in (28, 32):
                g.fill(x, GL + 2, z, x, GL + 3, z, AIR)
                g.fill(x, GL + 2, z + (1 if z == bz0 else -1), x, GL + 3, z + (1 if z == bz0 else -1), GLASS)
    g.fill(25, GL, bz0 + 2, 25, TT - 2, bz1 - 2, STONE)
    g.fill(25, GL, 24, 25, GL + 2, 25, AIR)
    for (lx, lz) in ((20, 20), (20, 29), (31, 19), (40, 19), (34, 29), (40, 29)):
        g.lantern(lx, TT - 2, lz)
    # the terrace parapet, with a man at every few crenels
    parapet_rect(g, bx0, bz0, bx1, bz1, TT)
    for x in range(bx0 + 3, bx1 - 1, 4):
        g.mark("archer", x, TT, bz0 + 1); g.mark("archer", x, TT, bz1 - 1)
    for z in range(bz0 + 4, bz1 - 1, 4):
        g.mark("archer", bx0 + 1, TT, z); g.mark("archer", bx1 - 1, TT, z)
    # the perron: three wide, from the court straight up to the terrace, low walls either side
    px0, px1 = 35, 37
    for k in range(TT - GL):
        z = bz1 + 6 - k
        for x in range(px0, px1 + 1):
            g.stair(x, GL + k, z, STONE_STAIR, "north")
            g.fill(x, GL, z, x, GL + k - 1, z, STONE)
            g.fill(x, GL + k + 1, z, x, GL + k + 4, z, AIR)
    for x in (px0 - 1, px1 + 1):
        for k in range(TT - GL):
            g.fill(x, GL, bz1 + 6 - k, x, GL + k, bz1 + 6 - k, STONE)
            g.set(x, GL + k + 1, bz1 + 6 - k, RAIL)
    g.fill(px0, TT, bz1, px1, TT + 3, bz1, AIR)             # through the parapet onto the terrace
    g.fill(px0, TT - 1, bz1, px1, TT - 1, bz1, STONE)
    for x in (px0 - 1, px1 + 1):
        g.set(x, TT, bz1, STONE); g.set(x, TT + 1, bz1, "minecraft:lantern[hanging=false]")

    # the timber storeys: four in from the terrace's edge, the upper one jettied out a block to the south
    hx0, hz0, hx1, hz1 = 18, 18, 41, 30
    S1, S2 = TT, TT + 6                                       # standing levels of the two storeys
    for (xa, za, xb, zb) in ((hx0, hz0, hx1, hz0), (hx0, hz1, hx1, hz1), (hx0, hz0, hx0, hz1), (hx1, hz0, hx1, hz1)):
        frame_wall(g, xa, za, xb, zb, S1, S2 - 2)
    g.fill(hx0 + 1, S1, hz0 + 1, hx1 - 1, S2 + 5, hz1 - 1, AIR)
    g.fill(hx0, S2 - 1, hz0, hx1, S2 - 1, hz1 + 1, DARK_PLANK)             # the upper floor, out over the jetty
    for x in range(hx0, hx1 + 1):
        g.stair(x, S2 - 2, hz1 + 1, "minecraft:dark_oak_stairs", "north", half="top")   # jetty brackets
    for (xa, za, xb, zb) in ((hx0, hz0, hx1, hz0), (hx0, hz1 + 1, hx1, hz1 + 1), (hx0, hz0, hx0, hz1 + 1), (hx1, hz0, hx1, hz1 + 1)):
        frame_wall(g, xa, za, xb, zb, S2, S2 + 4)
    g.fill(hx0 + 1, S2, hz0 + 1, hx1 - 1, S2 + 4, hz1, AIR)
    windows(g, hx0, hz0, hx1, hz0, S1 + 1, "north"); windows(g, hx0, hz1, hx1, hz1, S1 + 1, "south")
    windows(g, hx0, hz0, hx0, hz1, S1 + 1, "west"); windows(g, hx1, hz0, hx1, hz1, S1 + 1, "east")
    windows(g, hx0, hz0, hx1, hz0, S2 + 1, "north"); windows(g, hx0, hz1 + 1, hx1, hz1 + 1, S2 + 1, "south")
    windows(g, hx0, hz0, hx0, hz1 + 1, S2 + 1, "west"); windows(g, hx1, hz0, hx1, hz1 + 1, S2 + 1, "east")
    # the door from the terrace, under a hood
    g.fill(29, S1, hz1, 30, S1 + 2, hz1, AIR)
    # a balcony off the upper storey over the door, on brackets, with a rail and flowers
    g.fill(27, S2 - 1, hz1 + 2, 32, S2 - 1, hz1 + 2, "minecraft:dark_oak_slab[type=top]")
    for x in range(27, 33):
        g.stair(x, S2 - 2, hz1 + 2, "minecraft:dark_oak_stairs", "north", half="top")
        g.set(x, S2, hz1 + 2, "minecraft:dark_oak_fence")
    g.fill(29, S2, hz1 + 1, 30, S2 + 2, hz1 + 1, AIR)
    g.set(27, S2 + 1, hz1 + 2, "minecraft:potted_red_tulip"); g.set(32, S2 + 1, hz1 + 2, "minecraft:potted_cornflower")
    # the roof, steep, over both storeys, two cross gables on the south slope
    ridge = steep_gable(g, hx0, hz0, hx1, hz1 + 1, S2 + 5, PLASTER)
    for xc in (23, 36):
        wall_gable(g, xc, hz1 + 1, S2 + 5, half=3)
    # the chimney stack, brick, up from the kitchen hearth through the roof at the west end
    cx, cz = hx0 + 2, hz0 + 2
    g.fill(cx, GL, cz, cx + 1, ridge + 2, cz + 1, "minecraft:bricks")
    g.fill(cx, ridge + 3, cz, cx + 1, ridge + 3, cz + 1, "minecraft:brick_slab[type=bottom]")
    g.set(cx, ridge + 3, cz, "minecraft:campfire[lit=true,signal_fire=false]")
    # stairs inside, three wide: the hall up to the first timber storey, and on up to the second
    for k in range(S1 - GL):
        x = 26 + k
        for z in (21, 22, 23):
            g.stair(x, GL + k, z, STONE_STAIR, "east")
            g.fill(x, GL, z, x, GL + k - 1, z, STONE)
            g.fill(x, GL + k + 1, z, x, GL + k + 4, z, AIR)
    for k in range(S2 - S1):
        x = 33 + k
        for z in (25, 26, 27):
            g.stair(x, S1 + k, z, "minecraft:spruce_stairs", "east")
            g.fill(x, S1, z, x, S1 + k - 1, z, PLANK)
            g.fill(x, S1 + k + 1, z, x, S1 + k + 4, z, AIR)
    # rails round the stairwells
    for x in range(28, 32):
        for z in (20, 24):
            if g.get(x, S1, z) == AIR:
                g.set(x, S1, z, "minecraft:spruce_fence")
    for z in range(21, 24):
        if g.get(28, S1, z) == AIR:
            g.set(28, S1, z, "minecraft:spruce_fence")
    for x in range(33, 39):
        for z in (24, 28):
            if g.get(x, S2, z) == AIR and g.get(x, S2 - 1, z) != AIR:
                g.set(x, S2, z, "minecraft:spruce_fence")
    for z in range(25, 28):
        if g.get(33, S2, z) == AIR and g.get(33, S2 - 1, z) == AIR:
            g.set(32, S2, z, "minecraft:spruce_fence")
    for (lx, lz) in ((23, 21), (36, 21), (24, 28)):
        g.lantern(lx, S2 - 2, lz)
    for (lx, lz) in ((24, 21), (35, 21), (28, 27)):
        g.lantern(lx, S2 + 4, lz)
    # the lord's floor: his chair and the war table in the first storey, the strongboxes upstairs
    g.mark("lord", 22, S1, 21)
    g.stair(23, S1, 21, "minecraft:dark_oak_stairs", "west")
    g.mark("war_table", 37, S1, 21)
    g.chest(hx0 + 1, S2, hz0 + 3, "east", LOOT + "watchtower_treasury")
    g.chest(hx0 + 1, S2, hz0 + 5, "east", LOOT + "watchtower_treasury")

    # ---- the court: a garden and the well to the west of the road, a pergola to the east ----
    for x in range(16, 26):
        for z in range(39, 47):
            if (x + z) % 3 != 0 and 17 <= x <= 24 and 40 <= z <= 45:
                g.set(x, GL - 1, z, "minecraft:grass_block")
                if (x * 7 + z) % 4 == 0:
                    g.set(x, GL, z, FLOWERS[int(rng.integers(len(FLOWERS)))])
    g.fill(19, GL - 3, 42, 21, GL - 1, 44, "minecraft:stone_bricks")
    g.set(20, GL - 1, 43, "minecraft:water"); g.set(20, GL - 2, 43, "minecraft:water")
    for (x, z) in ((19, 42), (21, 44)):
        g.fill(x, GL, z, x, GL + 1, z, "minecraft:dark_oak_fence")
    g.fill(19, GL + 2, 42, 21, GL + 2, 44, "minecraft:dark_oak_slab[type=bottom]")
    # the pergola: posts and a slatted roof grown over with azalea, benches under it
    for x in range(39, 46, 3):
        for z in (41, 45):
            g.fill(x, GL, z, x, GL + 3, z, "minecraft:dark_oak_fence")
    g.fill(39, GL + 4, 41, 45, GL + 4, 45, "minecraft:dark_oak_slab[type=bottom]")
    g.fill_mask(rect(g, 39, 41, 45, 45) & (np.mod(xs + 2 * zs, 3) == 0), GL + 4, GL + 4,
                "minecraft:flowering_azalea_leaves[persistent=true]")
    for x in (40, 44):
        g.stair(x, GL, 43, "minecraft:spruce_stairs", "west")
    # flower beds under the house's south windows, planters at the corners of the court
    for x in list(range(bx0 + 1, 27)) + list(range(39, bx1)):
        if bx1 - 1 >= x:
            g.set(x, GL - 1, bz1 + 1, "minecraft:grass_block")
            g.set(x, GL, bz1 + 1, FLOWERS[int(rng.integers(len(FLOWERS)))])
    for (x, z) in ((12, 12), (47, 12), (12, 47), (47, 47)):
        g.set(x, GL, z, "minecraft:potted_azalea_bush")
    # the road from the gate to the house door
    g.fill(gx0, GL - 1, bz1 + 1, gx1, GL - 1, z1 - 5, ROAD)
    lamp_post(g, gx0 - 1, 44, GL); lamp_post(g, gx1 + 1, 44, GL)
    lamp_post(g, 13, 36, GL); lamp_post(g, 46, 36, GL)
    # the garrison's places in the court
    for (x, z) in ((gx0 - 2, 42), (gx1 + 2, 42), (12, 24), (47, 24), (26, 37), (40, 12), (20, 12), (33, 38)):
        g.mark("knight", x, GL, z)
    g.mark("captain", (gx0 + gx1) // 2, GL, 46)
    g.mark("rally", (gx0 + gx1) // 2, GL, 40)
    return g, {"name": "fortified_manor", "tier": 1, "GL": GL, "character": "rich"}


fortified_manor.cap_style = "pyramid"        # the great tower's roof: a slate pyramid standing on its posts
