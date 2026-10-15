"""Stormhold castle designs."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, rect_ring, spiral_stair, gable_roof, cone_roof
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR,
                   DARK_STAIR, SLAB_CAP, RAIL, PLANK, DARK_PLANK, PORTCULLIS, GLASS,
                   curtain, posts_on_walk, round_tower, square_tower, straight_wall,
                   pointed_arch, building, lamp_post, tree_weirwood, side_grids)

LOOT = "stormhold:chests/"


def portcullis_plane(g, x0, x1, y0, height, z):
    g.__dict__.setdefault("gate_planes", []).append([x0, y0, z, x1, y0 + height - 1, z])
    for y in range(y0, y0 + height):
        for x in range(x0, x1 + 1):
            if g.get(x, y, z) == "minecraft:air":
                g.set(x, y, z, PORTCULLIS)


def d_tower(g, side, pos, a, b, walk, GL, r=6):
    """Half-round flanking tower projecting from the curtain's outer face."""
    if side == "north":
        cx, cz, door = pos, a + 1, (1, 0)
    elif side == "south":
        cx, cz, door = pos, b - 1, (1, 0)
    elif side == "west":
        cx, cz, door = a + 1, pos, (0, 1)
    else:
        cx, cz, door = b - 1, pos, (0, 1)
    top = walk + 6
    dx, dz = door
    round_tower(g, cx, cz, r, top, GL, floors=(GL + 8, walk - 1), spiral=False,
                doors=((dx, dz, walk), (-dx, -dz, walk), (dx, dz, GL + 9), (-dx, -dz, GL + 9)),
                banner_dirs=(), lantern_floors=True)
    # inner stair: ladder-free access is via the walk doors; mark posts
    for k in range(6):
        ang = k * np.pi / 3
        g.mark("archer", int(round(cx + 3 * np.cos(ang))), top, int(round(cz + 3 * np.sin(ang))))
    return cx, cz


def bartizan(g, cx, cz, y, r=2):
    """Small corbelled corner turret with a cone, sitting on a parapet corner."""
    g.fill_mask(disk(g, cx, cz, r), y - 1, y + 3, STONE)
    g.fill_mask(disk(g, cx, cz, r - 1), y, y + 2, "minecraft:air")
    g.fill_mask(disk(g, cx, cz, r), y - 2, y - 2, "minecraft:stone_brick_slab[type=top]")
    cone_roof(g, cx, cz, r, y + 4, ROOF, height_factor=2.0)


def great_castle_a(seed=7):
    W, H, GL = 128, 74, 6
    g = Grid(W, H, W, seed)
    a, b = 10, W - 11          # curtain outer faces (10 .. 117)
    thick = 6
    walk = GL + 24             # curtain walkway standing level
    xs, zs = g.xz()

    # ---------------- ground ----------------
    e = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(zs, W - 1 - zs))
    inside = (e >= 8)
    g.fill_mask(inside, 0, GL - 2, FOUND)
    g.fill_mask(inside, GL - 1, GL - 1, PAVE)
    g.fill_mask(inside, GL, GL + 12, "minecraft:air")
    # moat: e 0 bank, 1..7 water
    moat = (e >= 1) & (e <= 7)
    g.fill_mask(e == 0, 0, GL - 1, FOUND)
    g.fill_mask(e == 0, GL - 1, GL - 1, "minecraft:grass_block")
    g.fill_mask(moat, 0, GL - 6, DARK)
    g.fill_mask(moat, GL - 5, GL - 5, Mix(("minecraft:gravel", 60), ("minecraft:clay", 25), ("minecraft:mud", 15)))
    g.fill_mask(moat, GL - 4, GL - 2, "minecraft:water")
    g.fill_mask(moat | (e == 0), GL - 1, GL + 6, "minecraft:air")
    g.fill_mask(e == 0, GL - 1, GL - 1, "minecraft:grass_block")

    # ---------------- curtain ----------------
    d, along, out = curtain(g, a, b, thick, walk, GL)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=1)
    posts_on_walk(g, d, along, walk, "knight", dsel=3, every=17, offset=5)

    # ---------------- D-towers along the curtain ----------------
    for side in ("north", "south", "west", "east"):
        for pos in (36, 91):
            d_tower(g, side, pos, a, b, walk, GL)

    # ---------------- corner towers ----------------
    ttop = walk + 12
    tfloors = (GL + 8, GL + 16, walk - 1, walk + 6)
    for (cx, cz, bd) in ((a + 3, a + 3, ((-0.7, -0.7),)), (b - 3, a + 3, ((0.7, -0.7),)),
                         (a + 3, b - 3, ((-0.7, 0.7),)), (b - 3, b - 3, ((0.7, 0.7),))):
        sx = 1 if cx < W / 2 else -1
        sz = 1 if cz < W / 2 else -1
        round_tower(g, cx, cz, 9, ttop, GL, floors=tfloors,
                    doors=((sx, 0, walk), (0, sz, walk), (sx, 0, GL + 9), (0, sz, GL + 9), (sx * 0.7, sz * 0.7, GL)),
                    banner_dirs=bd)
        # stair turret rising above the roof platform on the outer side
        tx, tz = cx - 4 * sx, cz - 4 * sz
        g.fill_mask(disk(g, tx, tz, 3), ttop, ttop + 7, STONE)
        g.fill_mask(disk(g, tx, tz, 2), ttop, ttop + 6, "minecraft:air")
        g.fill(tx + sx * 2, ttop, tz, tx + sx * 3, ttop + 1, tz, "minecraft:air")
        g.fill(tx, ttop + 4, tz - 3, tx, ttop + 5, tz + 3, "minecraft:air")
        g.fill(tx - 3, ttop + 4, tz, tx + 3, ttop + 5, tz, "minecraft:air")
        cone_roof(g, tx, tz, 3, ttop + 8, ROOF)
        for k in range(8):
            ang = k * np.pi / 4
            px, pz = int(round(cx + 6 * np.cos(ang))), int(round(cz + 6 * np.sin(ang)))
            if (px - tx) ** 2 + (pz - tz) ** 2 > 16:
                g.mark("archer", px, ttop, pz)

    # ---------------- mid towers (W, E, N) ----------------
    mtop = walk + 8
    mf = (GL + 8, GL + 16, walk - 1)
    for (x0, z0, x1, z1, sp, bs) in ((a - 4, 57, a + 8, 69, (a + 3, 63), ("west",)),
                                     (b - 8, 57, b + 4, 69, (b - 3, 63), ("east",)),
                                     (57, a - 4, 69, a + 8, (63, a + 3), ("north",))):
        square_tower(g, x0, z0, x1, z1, mtop, GL, floors=mf, spiral_at=sp, banner_sides=bs)
        # doors onto the wall walk and gallery on both sides
        if x0 < W / 2 and z0 > 20:  # west
            for z in (z0, z1):
                for y in (walk, GL + 9):
                    g.fill(a + 1, y, z, a + 4, y + 1, z, "minecraft:air")
            g.fill(a + 6, GL, 62, a + 8, GL + 2, 64, "minecraft:air")
        elif x0 > W / 2:  # east
            for z in (z0, z1):
                for y in (walk, GL + 9):
                    g.fill(b - 4, y, z, b - 1, y + 1, z, "minecraft:air")
            g.fill(b - 8, GL, 62, b - 6, GL + 2, 64, "minecraft:air")
        else:  # north
            for x in (x0, x1):
                for y in (walk, GL + 9):
                    g.fill(x, y, a + 1, x, y + 1, a + 4, "minecraft:air")
        for (px, pz) in ((x0 + 1, z0 + 1), (x1 - 1, z1 - 1), (x0 + 1, z1 - 1), (x1 - 1, z0 + 1)):
            g.mark("crossbowman", px, mtop, pz)

    # ---------------- main gatehouse (south) ----------------
    gx0, gx1 = 61, 67
    gtop = walk + 6
    # central block
    g.fill(57, 0, b - 7, 71, gtop - 1, b + 7, STONE)
    g.fill(57, GL + 3, b + 7, 71, GL + 3, b + 7, DARK)
    for (tx, bd) in ((53, ((-1, 0), (0, 1))), (75, ((1, 0), (0, 1)))):
        round_tower(g, tx, b + 2, 6, gtop + 4, GL, floors=(GL + 8, GL + 16, walk - 1), spiral=False,
                    cone=True, doors=((0, -1, walk), (0, -1, GL + 9), (-1 if tx < 64 else 1, 0, gtop)), banner_dirs=bd)
    # passage
    pointed_arch(g, gx0, gx1, GL, 10, b - 7, b + 7)
    g.fill(gx0, GL - 1, b - 7, gx1, GL - 1, b + 7, ROAD)
    portcullis_plane(g, gx0, gx1, GL, 10, b + 6)
    portcullis_plane(g, gx0, gx1, GL, 10, b - 6)
    # gate hall above with murder holes
    g.fill(58, GL + 12, b - 6, 70, gtop - 2, b + 6, "minecraft:air")
    g.fill(58, GL + 11, b - 6, 70, GL + 11, b + 6, PLANK)
    for z in range(b - 4, b + 5, 2):
        g.fill(64, GL + 10, z, 64, GL + 11, z, "minecraft:air")
    g.lantern(61, gtop - 2, b); g.lantern(67, gtop - 2, b)
    spiral_stair(g, 60, b - 3, GL + 12, gtop - 1, STONE_STAIR, STONE)
    g.fill(57, gtop - 1, b - 7, 71, gtop - 1, b + 7, STONE)
    g.fill(58, gtop - 1, b - 5, 62, gtop - 1, b - 1, "minecraft:air")
    # gatehouse top parapet
    for x in range(56, 73):
        for z in (b - 8, b + 8):
            g.set(x, gtop - 1, z, STONE); g.set(x, gtop, z, STONE)
            if x % 4 < 2:
                g.set(x, gtop + 1, z, STONE); g.set(x, gtop + 2, z, SLAB_CAP)
    for z in range(b - 8, b + 9):
        for x in (56, 72):
            g.set(x, gtop - 1, z, STONE); g.set(x, gtop, z, STONE)
            if z % 4 < 2:
                g.set(x, gtop + 1, z, STONE); g.set(x, gtop + 2, z, SLAB_CAP)
    g.fill(57, gtop, b - 7, 71, gtop + 4, b + 7, "minecraft:air")
    for x in (59, 63, 65, 69):
        for z in (b - 5, b + 5):
            g.mark("crossbowman", x, gtop, z)
    for x in (55, 73):
        g.mark("crossbowman", x, gtop + 4, b + 2)
    g.wall_banner(60, GL + 14, b + 8, "south"); g.wall_banner(68, GL + 14, b + 8, "south")
    # wall gaps to gatehouse at walk level
    g.fill(56, walk, b - 5, 57, walk + 1, b - 2, "minecraft:air")
    g.fill(71, walk, b - 5, 72, walk + 1, b - 2, "minecraft:air")
    # drawbridge across the moat
    g.fill(gx0, GL - 1, b + 8, gx1, GL - 1, W - 1, "minecraft:spruce_planks")
    g.fill(gx0 - 1, GL - 1, b + 8, gx0 - 1, GL - 1, W - 1, "minecraft:dark_oak_log[axis=z]")
    g.fill(gx1 + 1, GL - 1, b + 8, gx1 + 1, GL - 1, W - 1, "minecraft:dark_oak_log[axis=z]")
    for x in (gx0 - 1, gx1 + 1):
        g.fill(x, GL, b + 8, x, GL, W - 1, "minecraft:dark_oak_fence")
        g.fill(x, GL + 1, b + 8, x, GL + 6, b + 8, "minecraft:chain[axis=y]")
    g.mark("gate", 64, GL, W - 2)
    for x in (gx0, gx1):
        for z in (b - 4, b - 1, b + 3):
            g.mark("knight", x, GL, z)

    # ---------------- inner ward ----------------
    iw_x0, iw_x1, iw_z1 = 30, 97, 64
    iwalk = GL + 15
    straight_wall(g, iw_x0, a + thick, iw_x0 + 2, iw_z1, iwalk, "west", GL)
    straight_wall(g, iw_x1 - 2, a + thick, iw_x1, iw_z1, iwalk, "east", GL)
    straight_wall(g, iw_x0, iw_z1 - 2, iw_x1, iw_z1, iwalk, "south", GL)
    for z in range(a + thick + 3, iw_z1 - 2, 6):
        g.mark("archer", iw_x0 + 1, iwalk, z); g.mark("archer", iw_x1 - 1, iwalk, z)
    for x in range(iw_x0 + 3, iw_x1 - 2, 6):
        g.mark("archer", x, iwalk, iw_z1 - 1)
    # corner towers of the inner ward
    for cx in (iw_x0 + 1, iw_x1 - 1):
        round_tower(g, cx, iw_z1 - 1, 5, iwalk + 6, GL, floors=(iwalk - 1,), spiral=False, cone=True,
                    doors=((1 if cx < 64 else -1, 0, iwalk), (0, -1, iwalk)))
    # inner gatehouse
    igx0, igx1 = 62, 66
    for (x0, x1) in ((53, 61), (67, 75)):
        square_tower(g, x0, iw_z1 - 6, x1, iw_z1 + 4, iwalk + 7, GL, floors=(iwalk - 1,),
                     spiral_at=((x0 + x1) // 2, iw_z1 - 1), banner_sides=("south",))
        g.mark("crossbowman", x0 + 1, iwalk + 7, iw_z1 + 3)
        g.mark("crossbowman", x1 - 1, iwalk + 7, iw_z1 + 3)
    g.fill(igx0 - 1, 0, iw_z1 - 4, igx1 + 1, iwalk + 2, iw_z1 + 2, STONE)
    pointed_arch(g, igx0, igx1, GL, 7, iw_z1 - 4, iw_z1 + 2)
    portcullis_plane(g, igx0, igx1, GL, 7, iw_z1 + 2)
    g.fill(igx0, GL - 1, iw_z1 - 4, igx1, GL - 1, iw_z1 + 2, ROAD)
    g.mark("captain", 64, GL, iw_z1 - 6)
    g.mark("rally", 64, GL, iw_z1 - 7)

    # inner ward ground
    iward = rect(g, iw_x0 + 3, a + thick, iw_x1 - 3, iw_z1 - 3)
    g.fill_mask(iward, GL - 1, GL - 1, Mix(("minecraft:stone_bricks", 40), ("minecraft:cobblestone", 25),
                                           ("minecraft:andesite", 20), ("minecraft:gravel", 15)))
    # godswood (north-west of inner ward)
    gw = rect(g, iw_x0 + 4, a + thick + 2, 44, 36)
    g.fill_mask(gw, GL - 1, GL - 1, Mix(("minecraft:grass_block", 80), ("minecraft:podzol", 20)))
    g.fill(36, GL - 1, 28, 39, GL - 1, 31, "minecraft:water")
    tree_weirwood(g, 40, 22, GL)
    for (x, z) in ((35, 21), (43, 33), (34, 33), (42, 26)):
        g.set(x, GL, z, "minecraft:fern")
    g.set(38, GL, 25, "minecraft:moss_carpet")

    # ---------------- keep ----------------
    kx0, kz0, kx1, kz1 = 46, 18, 81, 55
    kroof = GL + 30
    F2, F3 = GL + 11, GL + 20
    g.fill(kx0, 0, kz0, kx1, kroof - 1, kz1, STONE)
    g.fill(kx0 + 3, GL, kz0 + 3, kx1 - 3, kroof - 2, kz1 - 3, "minecraft:air")
    # plinth + bands
    for y, blk in ((GL + 3, DARK), (F2, DARK), (F3, DARK)):
        g.fill(kx0, y, kz0, kx1, y, kz0, blk); g.fill(kx0, y, kz1, kx1, y, kz1, blk)
        g.fill(kx0, y, kz0, kx0, y, kz1, blk); g.fill(kx1, y, kz0, kx1, y, kz1, blk)
    for x in range(kx0 - 1, kx1 + 2):
        for z in (kz0 - 1, kz1 + 1):
            g.fill(x, 0, z, x, GL + 1, z, DARK)
            g.stair(x, GL + 2, z, DARK_STAIR, "south" if z < kz0 else "north")
    for z in range(kz0, kz1 + 1):
        for x in (kx0 - 1, kx1 + 1):
            g.fill(x, 0, z, x, GL + 1, z, DARK)
            g.stair(x, GL + 2, z, DARK_STAIR, "east" if x < kx0 else "west")
    # buttresses
    for z in range(kz0 + 6, kz1 - 3, 7):
        for x, f in ((kx0 - 1, "east"), (kx1 + 1, "west")):
            g.fill(x, GL + 3, z, x, F3 - 2, z, STONE)
            g.stair(x, F3 - 1, z, STONE_STAIR, f)
    # floors
    g.fill(kx0 + 3, GL - 1, kz0 + 3, kx1 - 3, GL - 1, kz1 - 3, FLOOR_STONE)
    g.fill(kx0 + 3, F2, kz0 + 3, kx1 - 3, F2, kz1 - 3, DARK_PLANK)
    g.fill(kx0 + 3, F3, kz0 + 3, kx1 - 3, F3, kz1 - 3, PLANK)
    g.fill(kx0, kroof - 1, kz0, kx1, kroof - 1, kz1, STONE)
    # keep parapet with corbels
    for x in range(kx0 - 1, kx1 + 2):
        for z, f in ((kz0 - 1, "south"), (kz1 + 1, "north")):
            if x % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if x % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for z in range(kz0 - 1, kz1 + 2):
        for x, f in ((kx0 - 1, "east"), (kx1 + 1, "west")):
            if z % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if z % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for x in range(kx0 + 2, kx1 - 1, 5):
        g.mark("archer", x, kroof, kz1 - 1)
        g.mark("archer", x, kroof, kz0 + 1)
    # keep windows
    for z in range(kz0 + 22, kz1 - 2, 4):
        for x in (kx0, kx0 + 1, kx0 + 2, kx1, kx1 - 1, kx1 - 2):
            g.fill(x, GL + 3, z, x, GL + 8, z, "minecraft:air")
        for x in (kx0, kx1):
            g.fill(x, GL + 3, z, x, GL + 7, z, GLASS)
            g.set(x, GL + 8, z, "minecraft:air")
    for z in range(kz0 + 4, kz1 - 2, 5):
        for x in (kx0, kx1):
            for y in (F2 + 3, F3 + 3):
                g.fill(x, y, z, x, y + 1, z, GLASS)
    for x in range(kx0 + 4, kx1 - 2, 5):
        for z in (kz0, kz1):
            for y in (F2 + 3, F3 + 3):
                g.fill(x, y, z, x, y + 1, z, GLASS)
    for (x, z, f) in ((kx0 - 1, 40, "west"), (kx1 + 1, 40, "east"), (56, kz1 + 1, "south"), (71, kz1 + 1, "south")):
        g.wall_banner(x, F3 - 3, z, f)
    # entrance
    g.fill(62, GL, kz1 - 2, 65, GL + 5, kz1, "minecraft:air")
    g.fill(62, GL + 6, kz1 - 2, 65, GL + 6, kz1, "minecraft:air")
    g.set(62, GL + 6, kz1, STONE); g.set(65, GL + 6, kz1, STONE)
    for x in range(61, 67):
        g.stair(x, GL - 1, kz1 + 1, STONE_STAIR, "north")
    g.mark("captain", 63, GL, kz1 + 3)
    g.mark("knight", 60, GL, kz1 + 3); g.mark("knight", 67, GL, kz1 + 3)

    # great hall
    hz0 = 34
    for x in (55, 72):
        for z in range(hz0 + 3, kz1 - 3, 4):
            g.fill(x, GL, z, x, F2 - 1, z, "minecraft:stone_bricks")
            g.set(x, GL, z, "minecraft:chiseled_stone_bricks")
            g.set(x, F2 - 1, z, "minecraft:chiseled_stone_bricks")
    # checker floor + runner
    for x in range(kx0 + 3, kx1 - 2):
        for z in range(hz0, kz1 - 2):
            g.set(x, GL - 1, z, "minecraft:polished_deepslate" if (x + z) % 2 else "minecraft:polished_andesite")
    g.fill(61, GL, hz0 + 3, 66, GL, kz1 - 3, "minecraft:red_carpet")
    # dais and throne
    g.fill(57, GL, hz0, 70, GL, hz0 + 2, "minecraft:polished_deepslate")
    g.fill(57, GL + 1, hz0, 70, GL + 1, hz0 + 1, "minecraft:polished_deepslate")
    for x in range(57, 71):
        g.stair(x, GL, hz0 + 3, "minecraft:polished_deepslate_stairs", "north")
        g.stair(x, GL + 1, hz0 + 2, "minecraft:polished_deepslate_stairs", "north")
    g.stair(63, GL + 2, hz0, "minecraft:dark_oak_stairs", "north")
    g.stair(64, GL + 2, hz0, "minecraft:dark_oak_stairs", "north")
    g.set(62, GL + 2, hz0, "minecraft:dark_oak_fence"); g.set(65, GL + 2, hz0, "minecraft:dark_oak_fence")
    g.set(63, GL + 3, hz0 - 1, "minecraft:gold_block"); g.set(64, GL + 3, hz0 - 1, "minecraft:gold_block")
    for x in (58, 61, 66, 69):
        g.wall_banner(x, GL + 6, hz0 - 1 + 1, "south")
    g.mark("lord", 63, GL + 2, hz0 + 2)
    g.mark("war_table", 59, GL + 2, hz0 + 1)
    # long tables and benches
    for tx in (58, 69):
        for z in range(hz0 + 6, kz1 - 5):
            g.set(tx, GL, z, "minecraft:dark_oak_fence" if (z % 4 == 0) else "minecraft:air")
            g.set(tx, GL + 1 if False else GL, z, "minecraft:dark_oak_fence" if z % 4 == 0 else "minecraft:air")
        g.fill(tx, GL + 1, hz0 + 6, tx, GL + 1, kz1 - 6, "minecraft:dark_oak_slab[type=bottom]")
        g.fill(tx, GL, hz0 + 6, tx, GL, kz1 - 6, "minecraft:dark_oak_fence")
        for z in range(hz0 + 6, kz1 - 5):
            g.stair(tx - 1, GL, z, "minecraft:spruce_stairs", "west")
            g.stair(tx + 1, GL, z, "minecraft:spruce_stairs", "east")
        for z in range(hz0 + 8, kz1 - 6, 4):
            g.set(tx, GL + 2, z, "minecraft:candle[candles=3,lit=true]")
    # chandeliers
    for x in (60, 67):
        for z in (40, 47):
            g.fill(x, F2 - 3, z, x, F2 - 1, z, "minecraft:chain[axis=y]")
            g.lantern(x, F2 - 4, z)
    for (x, z) in ((kx0 + 4, hz0 + 1), (kx1 - 4, hz0 + 1), (kx0 + 4, kz1 - 4), (kx1 - 4, kz1 - 4)):
        g.set(x, GL, z, "minecraft:campfire[lit=true,signal_fire=false]")
    for x in range(51, 77, 4):
        for z in (40, 46, 50):
            if x not in (55, 72) and not (59 <= x <= 68):
                g.mark("knight", x, GL, z)
    for z in (40, 46):
        g.mark("captain", 63, GL, z)

    # west wing (kitchen) and east wing (armory) at ground level
    g.fill(kx0 + 3, GL, hz0 - 1, 55, F2 - 1, hz0 - 1, STONE)
    g.fill(72, GL, hz0 - 1, kx1 - 3, F2 - 1, hz0 - 1, STONE)
    g.fill(52, GL, hz0 - 1, 54, GL + 2, hz0 - 1, "minecraft:air")
    g.fill(73, GL, hz0 - 1, 75, GL + 2, hz0 - 1, "minecraft:air")
    g.set(50, GL, 30, "minecraft:smoker[facing=east,lit=false]")
    g.set(50, GL, 29, "minecraft:furnace[facing=east,lit=false]")
    g.chest(50, GL, 31, "east", LOOT + "castle_kitchen")
    g.set(50, GL, 28, "minecraft:water_cauldron[level=3]")
    for (x, z) in ((77, 23), (77, 25), (77, 27), (77, 29)):
        g.barrel(x, GL, z, LOOT + "castle_armory", facing="west")
    g.set(77, GL, 31, "minecraft:anvil[facing=north]")
    g.set(77, GL, 32, "minecraft:grindstone[face=floor,facing=north]")

    # keep spiral (west wing) to the roof
    spiral_stair(g, 52, 24, GL, kroof - 1, STONE_STAIR, STONE)
    for fy in (F2, F3):
        pass
    # second floor: treasury vault + solar
    vx0, vz0, vx1, vz1 = 58, 40, 69, 50
    g.fill(vx0, F2 + 1, vz0, vx1, F3 - 1, vz1, STONE)
    g.fill(vx0 + 1, F2 + 1, vz0 + 1, vx1 - 1, F3 - 2, vz1 - 1, "minecraft:air")
    g.fill(62, F2 + 1, vz0, 65, F2 + 3, vz0, "minecraft:iron_bars")
    g.fill(63, F2 + 1, vz0, 64, F2 + 2, vz0, "minecraft:air")
    g.fill(vx0 + 1, F2, vz0 + 1, vx1 - 1, F2, vz1 - 1, "minecraft:polished_blackstone_bricks")
    for z in range(vz0 + 2, vz1, 2):
        g.chest(vx0 + 1, F2 + 1, z, "east", LOOT + "castle_treasury")
        g.chest(vx1 - 1, F2 + 1, z, "west", LOOT + "castle_treasury")
    g.fill(62, F2 + 1, vz1 - 1, 65, F2 + 1, vz1 - 1, "minecraft:gold_block")
    g.fill(63, F2 + 2, vz1 - 1, 64, F2 + 2, vz1 - 1, "minecraft:gold_block")
    g.lantern(61, F3 - 2, 45); g.lantern(66, F3 - 2, 45)
    # solar (east)
    g.bed(76, F2 + 1, 46, "north", "red")
    g.bed(77, F2 + 1, 46, "north", "red")
    g.fill(78, F2 + 1, 38, 78, F2 + 3, 44, "minecraft:bookshelf")
    g.set(74, F2 + 1, 40, "minecraft:lectern[facing=west]")
    g.chest(78, F2 + 1, 47, "west", LOOT + "castle_solar")
    # chambers (west) and barracks floor (F3)
    for z in range(37, 52, 3):
        g.bed(49, F2 + 1, z, "west", "gray")
        g.bed(49, F3 + 1, z, "west", "blue")
        g.bed(78, F3 + 1, z, "east", "blue")
    for z in (38, 44, 50):
        g.barrel(70, F3 + 1, z, LOOT + "castle_armory")
    for x in range(52, 78, 6):
        for z in range(24, 52, 6):
            g.lantern(x, F2 - 1, z) if not (55 <= x <= 72 and z >= hz0) else None
            g.lantern(x, F3 - 1, z)
            g.lantern(x, kroof - 2, z)
    for x in range(52, 76, 6):
        for z in (37, 49):
            g.mark("knight", x, F2 + 1, z) if not (vx0 <= x <= vx1) else None
            g.mark("knight", x, F3 + 1, z)

    # keep corner turrets with conical roofs
    for (cx, cz) in ((kx0, kz0), (kx1, kz0), (kx0, kz1), (kx1, kz1)):
        round_tower(g, cx, cz, 4, kroof + 7, GL, floors=(kroof - 1,), spiral=False, cone=True,
                    doors=((1 if cx < 64 else -1, 0, kroof), (0, 1 if cz < 40 else -1, kroof)), t=2)

    # slate roof over the great hall block (wings stay as walkable terraces)
    gable_roof(g, kx0 + 3, 36, kx1 - 3, kz1 - 3, kroof, "x", ROOF_STAIR, STONE)
    for x in (kx0 + 8, kx1 - 8):
        g.fill(x, kroof, 44, x, kroof + 9, 45, "minecraft:bricks")
        g.set(x, kroof + 10, 44, "minecraft:campfire[lit=true,signal_fire=false]")
    # great tower (donjon) rising from the keep's north half
    tx0, tz0, tx1, tz1 = 56, 18, 71, 33
    gtop2 = GL + 54
    square_tower(g, tx0, tz0, tx1, tz1, gtop2, GL, floors=(F2, F3, kroof - 1, GL + 38, GL + 46),
                 spiral_at=(61, 23), banner_sides=("north", "east", "west", "south"))
    for (cx, cz) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx, cz, gtop2 + 1)
    # doors: hall -> tower, roof -> tower, F2/F3 -> tower
    g.fill(66, GL, tz1 - 2, 67, GL + 2, tz1, "minecraft:air")
    for y in (F2 + 1, F3 + 1, kroof):
        g.fill(tx1 - 2, y, 26, tx1, y + 1, 27, "minecraft:air")
        g.fill(tx0, y, 26, tx0 + 2, y + 1, 27, "minecraft:air")
    g.mark("banner_stand", 66, gtop2, 27)
    for (x, z) in ((65, 30), (68, 30), (68, 22), (65, 22)):
        g.mark("crossbowman", x, gtop2, z)
    for (x, z) in ((58, 36), (69, 36)):
        g.mark("captain", x, GL + 1, z)

    # ---------------- outer bailey ----------------
    road = rect(g, 61, iw_z1 + 3, 67, b - 7)
    g.fill_mask(road, GL - 1, GL - 1, ROAD)
    for z in range(iw_z1 + 6, b - 8, 10):
        lamp_post(g, 59, z, GL); lamp_post(g, 69, z, GL)
    for z in range(iw_z1 + 8, b - 8, 8):
        g.mark("knight", 62, GL, z); g.mark("knight", 66, GL, z)
    # barracks (west)
    building(g, 17, 70, 31, 106, GL, 10, "z", door_cells=((31, 80), (31, 96)), upper_floor=GL + 4,
             chimney=(24, 88), timber=True)
    for z in range(72, 105, 3):
        g.bed(18, GL, z, "west", "brown")
        g.bed(18, GL + 5, z, "west", "brown")
    for z in range(74, 104, 6):
        g.barrel(30, GL, z, LOOT + "castle_barracks", facing="west")
    g.spiral = None
    g.fill(27, GL, 102, 30, GL + 4, 105, "minecraft:air")
    for k in range(5):
        g.stair(30 - k if k < 4 else 26, GL + k, 104, "minecraft:spruce_stairs", "west")
    g.fill(26, GL + 4, 102, 30, GL + 4, 105, "minecraft:air")
    for z in range(76, 104, 8):
        g.mark("knight", 26, GL, z)
        g.mark("knight", 34, GL, z)
    # stables (east)
    building(g, 96, 70, 110, 90, GL, 6, "z", chimney=None)
    g.fill(96, GL, 72, 96, GL + 3, 88, "minecraft:air")
    for z in range(72, 89, 4):
        g.fill(97, GL, z, 108, GL, z, "minecraft:spruce_fence")
        g.set(109, GL, z + 2, "minecraft:hay_block[axis=y]")
        g.set(108, GL, z + 2, "minecraft:water_cauldron[level=3]")
    g.fill(97, GL, 72, 97, GL, 88, "minecraft:air")
    # smithy (east)
    building(g, 96, 93, 110, 106, GL, 7, "z", chimney=(108, 100))
    g.fill(96, GL, 95, 96, GL + 3, 104, "minecraft:air")
    g.set(100, GL, 97, "minecraft:anvil[facing=east]")
    g.set(100, GL, 100, "minecraft:smithing_table")
    g.set(108, GL, 97, "minecraft:blast_furnace[facing=west,lit=false]")
    g.set(108, GL, 98, "minecraft:furnace[facing=west,lit=false]")
    g.set(108, GL, 103, "minecraft:lava_cauldron")
    g.set(104, GL, 103, "minecraft:grindstone[face=floor,facing=west]")
    g.chest(109, GL, 101, "west", LOOT + "castle_smithy")
    g.mark("knight", 93, GL, 100); g.mark("knight", 93, GL, 80)
    # sept with seven-coloured windows
    building(g, 36, 72, 50, 86, GL, 9, "x", door_cells=((43, 86),), wall_block=STONE)
    colors = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple"]
    i = 0
    for x in range(38, 49, 2):
        for z in (72, 86):
            g.fill(x, GL + 2, z, x, GL + 6, z, f"minecraft:{colors[i % 7]}_stained_glass_pane")
            i += 1
    g.fill(40, GL, 74, 46, GL, 74, "minecraft:polished_andesite")
    for x in range(40, 47):
        g.set(x, GL + 1, 74, f"minecraft:candle[candles={1 + x % 4},lit=true]")
    for z in range(77, 84, 2):
        for x in (39, 40, 46, 47):
            g.stair(x, GL, z, "minecraft:dark_oak_stairs", "south")
    # kitchen / granary
    building(g, 36, 95, 50, 108, GL, 7, "x", door_cells=((43, 95),), chimney=(38, 105))
    g.fill(37, GL, 106, 41, GL + 1, 107, "minecraft:hay_block[axis=y]")
    g.set(45, GL, 107, "minecraft:smoker[facing=north,lit=false]")
    g.set(46, GL, 107, "minecraft:campfire[lit=true,signal_fire=false]")
    for x in (47, 48, 49):
        g.barrel(x, GL, 107, LOOT + "castle_kitchen")
    # well
    g.fill(74, GL - 3, 78, 78, GL - 1, 82, "minecraft:stone_bricks")
    g.fill(75, GL - 3, 79, 77, GL - 1, 81, "minecraft:water")
    for x in range(74, 79):
        for z in (78, 82):
            g.set(x, GL, z, RAIL)
    for z in range(78, 83):
        for x in (74, 78):
            g.set(x, GL, z, RAIL)
    for (x, z) in ((74, 78), (78, 78), (74, 82), (78, 82)):
        g.fill(x, GL + 1, z, x, GL + 2, z, "minecraft:dark_oak_fence")
    g.fill(74, GL + 3, 78, 78, GL + 3, 82, "minecraft:dark_oak_slab[type=bottom]")
    g.set(76, GL + 2, 80, "minecraft:chain[axis=y]")
    g.lantern(76, GL + 1, 80)
    # training yard
    for x in range(74, 91):
        for z in (92, 108):
            g.set(x, GL, z, "minecraft:spruce_fence")
    for z in range(92, 109):
        for x in (74, 90):
            if z not in (99, 100, 101):
                g.set(x, GL, z, "minecraft:spruce_fence")
    for (x, z) in ((78, 96), (82, 96), (86, 96), (78, 104), (82, 104)):
        g.set(x, GL, z, "minecraft:dark_oak_fence")
        g.set(x, GL + 1, z, "minecraft:hay_block[axis=y]")
        g.set(x, GL + 2, z, "minecraft:carved_pumpkin[facing=west]")
    for z in (95, 99, 103):
        g.set(89, GL, z, "minecraft:target")
        g.set(89, GL + 1, z, "minecraft:target")
    g.mark("knight", 80, GL, 100); g.mark("knight", 85, GL, 100)
    # market stalls
    wools = ["red", "yellow", "blue"]
    for i, z0 in enumerate((92, 98, 104)):
        for (x, z) in ((52, z0), (57, z0), (52, z0 + 4), (57, z0 + 4)):
            g.fill(x, GL, z, x, GL + 2, z, "minecraft:spruce_fence")
        g.fill(52, GL + 3, z0, 57, GL + 3, z0 + 4, f"minecraft:{wools[i]}_wool")
        g.fill(53, GL, z0 + 1, 56, GL, z0 + 1, "minecraft:barrel[facing=up]")
        g.set(54, GL + 1, z0 + 1, "minecraft:composter[level=0]")
    # bailey knights (patrols)
    for x in range(20, 110, 9):
        for z in (67, 110):
            g.mark("knight", x, GL, z)

    return g, {"name": "great_castle_a", "tier": 3, "GL": GL}


def holdfast_a(seed=11):
    """Tier 2: square curtain, four towers, gatehouse, tall keep."""
    W, H, GL = 64, 48, 5
    g = Grid(W, H, W, seed)
    a, b = 6, W - 7
    thick = 4
    walk = GL + 15
    xs, zs = g.xz()
    e = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(zs, W - 1 - zs))
    inside = e >= 4
    g.fill_mask(inside, 0, GL - 2, FOUND)
    g.fill_mask(inside, GL - 1, GL - 1, PAVE)
    g.fill_mask(inside, GL, GL + 10, "minecraft:air")
    ditch = e < 4
    g.fill_mask(ditch, 0, GL - 4, FOUND)
    g.fill_mask(ditch, GL - 3, GL - 3, Mix(("minecraft:coarse_dirt", 60), ("minecraft:gravel", 40)))
    g.fill_mask(ditch, GL - 2, GL + 6, "minecraft:air")
    g.fill_mask(e == 0, 0, GL - 1, FOUND)
    g.fill_mask(e == 0, GL - 1, GL - 1, "minecraft:grass_block")
    # sharpened stakes in the ditch
    for x in range(1, W - 1, 3):
        for z in (2, W - 3):
            g.set(x, GL - 2, z, "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]")
    d, along, out = curtain(g, a, b, thick, walk, GL, gallery=False, banners_every=12)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=5, offset=2)
    ttop = walk + 6
    for (cx, cz) in ((a + 2, a + 2), (b - 2, a + 2), (a + 2, b - 2), (b - 2, b - 2)):
        sx = 1 if cx < W / 2 else -1
        sz = 1 if cz < W / 2 else -1
        square_tower(g, cx - 4, cz - 4, cx + 4, cz + 4, ttop, GL, floors=(walk - 1,), spiral_at=None,
                     cone=(cz > W / 2))
        for (x0, z0, x1, z1) in ((cx + 4 * sx - (1 if sx > 0 else -1) * 0, cz - 1, cx + 4 * sx, cz + 1),
                                 (cx - 1, cz + 4 * sz, cx + 1, cz + 4 * sz)):
            g.fill(min(x0, x1), walk, min(z0, z1), max(x0, x1), walk + 1, max(z0, z1), "minecraft:air")
        g.fill(cx - 1, GL, cz + 4 * sz, cx + 1, GL + 2, cz + 4 * sz, "minecraft:air") if False else None
        g.mark("archer", cx, ttop, cz) if cz < W / 2 else None
        # ladder access from courtyard
        lx, lz = cx + 4 * sx, cz
        g.fill(cx + 3 * sx, GL, cz - 1, cx + 3 * sx, walk - 1, cz - 1, "minecraft:air")
    # gatehouse
    g.fill(25, 0, b - 4, 38, walk + 3, b + 4, STONE)
    pointed_arch(g, 29, 34, GL, 7, b - 4, b + 4)
    g.fill(29, GL - 1, b - 4, 34, GL - 1, W - 1, ROAD)
    portcullis_plane(g, 29, 34, GL, 7, b + 3)
    g.fill(29, GL - 1, b + 5, 34, GL - 1, W - 1, "minecraft:spruce_planks")
    for x in range(24, 40):
        for z in (b - 5, b + 5):
            g.set(x, walk + 3, z, STONE)
            if x % 4 < 2:
                g.set(x, walk + 4, z, STONE); g.set(x, walk + 5, z, SLAB_CAP)
    for z in range(b - 5, b + 6):
        for x in (24, 39):
            g.set(x, walk + 3, z, STONE)
            if z % 4 < 2:
                g.set(x, walk + 4, z, STONE); g.set(x, walk + 5, z, SLAB_CAP)
    g.fill(25, walk + 3, b - 4, 38, walk + 6, b + 4, "minecraft:air")
    g.fill(25, walk + 2, b - 4, 38, walk + 2, b + 4, STONE)
    for x in (27, 31, 35):
        g.mark("crossbowman", x, walk + 3, b + 2)
    g.wall_banner(27, GL + 10, b + 5, "south"); g.wall_banner(36, GL + 10, b + 5, "south")
    g.mark("gate", 31, GL, W - 2)
    g.mark("knight", 29, GL, b - 6); g.mark("knight", 34, GL, b - 6)
    # stairs up to the wall walk inside the gatehouse block
    for k in range(walk - GL + 1):
        if 25 + k <= 28:
            pass
    g.fill(25, walk, b - 4, 28, walk + 1, b - 4, "minecraft:air")
    # keep
    kx0, kz0, kx1, kz1 = 21, 10, 42, 31
    kroof = GL + 26
    F2 = GL + 10
    g.fill(kx0, 0, kz0, kx1, kroof - 1, kz1, STONE)
    g.fill(kx0 + 2, GL, kz0 + 2, kx1 - 2, kroof - 2, kz1 - 2, "minecraft:air")
    g.fill(kx0 + 2, GL - 1, kz0 + 2, kx1 - 2, GL - 1, kz1 - 2, FLOOR_STONE)
    g.fill(kx0 + 2, F2, kz0 + 2, kx1 - 2, F2, kz1 - 2, DARK_PLANK)
    g.fill(kx0, kroof - 1, kz0, kx1, kroof - 1, kz1, STONE)
    for y in (GL + 3, F2):
        g.fill(kx0, y, kz0, kx1, y, kz0, DARK); g.fill(kx0, y, kz1, kx1, y, kz1, DARK)
        g.fill(kx0, y, kz0, kx0, y, kz1, DARK); g.fill(kx1, y, kz0, kx1, y, kz1, DARK)
    for x in range(kx0 - 1, kx1 + 2):
        for z, f in ((kz0 - 1, "south"), (kz1 + 1, "north")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if x % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if x % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for z in range(kz0 - 1, kz1 + 2):
        for x, f in ((kx0 - 1, "east"), (kx1 + 1, "west")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if z % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if z % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for (cx, cz) in ((kx0, kz0), (kx1, kz0)):
        round_tower(g, cx, cz, 3, kroof + 6, GL, floors=(), spiral=False, cone=True, t=1)
    # windows
    for z in range(kz0 + 4, kz1 - 2, 4):
        for x in (kx0, kx1):
            g.fill(x, GL + 3, z, x, GL + 6, z, GLASS)
            g.fill(x, F2 + 4, z, x, F2 + 5, z, GLASS)
    g.fill(30, GL, kz1, 33, GL + 4, kz1, "minecraft:air")
    g.fill(30, GL, kz1 - 1, 33, GL + 4, kz1 - 1, "minecraft:air")
    spiral_stair(g, kx0 + 5, kz0 + 5, GL, kroof - 1, STONE_STAIR, STONE)
    # hall
    g.fill(29, GL, kz0 + 3, 34, GL, kz0 + 4, "minecraft:polished_deepslate")
    g.stair(31, GL + 1, kz0 + 3, "minecraft:dark_oak_stairs", "south")
    g.stair(32, GL + 1, kz0 + 3, "minecraft:dark_oak_stairs", "south")
    g.wall_banner(31, GL + 5, kz0 + 2, "south"); g.wall_banner(32, GL + 5, kz0 + 2, "south")
    g.fill(30, GL, kz0 + 6, 33, GL, kz1 - 2, "minecraft:red_carpet")
    g.mark("lord", 31, GL, kz0 + 5)
    g.mark("war_table", 28, GL, kz0 + 4)
    for (x, z) in ((25, 18), (38, 18), (25, 25), (38, 25)):
        g.mark("knight", x, GL, z)
        g.lantern(x, F2 - 1, z)
        g.lantern(x, kroof - 2, z)
    g.mark("captain", 31, GL, kz1 + 3)
    # upper floor: treasury
    for z in range(kz0 + 4, kz1 - 2, 2):
        g.chest(kx1 - 3, F2 + 1, z, "west", LOOT + "holdfast_treasury")
    for z in range(kz0 + 4, kz1 - 2, 3):
        g.bed(kx0 + 3, F2 + 1, z, "west", "gray")
    g.mark("banner_stand", 37, kroof, 27)
    for (x, z) in ((24, 13), (39, 13), (24, 28), (39, 28)):
        g.mark("archer", x, kroof, z)
    g.mark("rally", 31, GL, kz1 + 4)
    g.wall_banner(kx0 - 1, F2 + 3, 20, "west"); g.wall_banner(kx1 + 1, F2 + 3, 20, "east")
    # small barracks and stable
    building(g, 10, 36, 19, 52, GL, 6, "z", door_cells=((19, 44),), chimney=(14, 40))
    for z in range(38, 51, 3):
        g.bed(11, GL, z, "west", "brown")
    g.barrel(18, GL, 50, LOOT + "castle_barracks", facing="west")
    building(g, 45, 38, 53, 50, GL, 5, "z")
    g.fill(45, GL, 40, 45, GL + 2, 48, "minecraft:air")
    for z in (41, 45):
        g.set(52, GL, z, "minecraft:hay_block[axis=y]")
    for (x, z) in ((25, 40), (38, 40), (31, 46), (22, 50), (41, 50)):
        g.mark("knight", x, GL, z)
    for z in range(36, 52, 7):
        lamp_post(g, 27, z, GL); lamp_post(g, 36, z, GL)
    return g, {"name": "holdfast_a", "tier": 2, "GL": GL}


def watchtower_a(seed=5):
    """Tier 1: tall round tower with a timber hoarding inside a low stone enclosure."""
    W, H, GL = 36, 46, 4
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    c = W // 2
    yard = disk(g, c, c, 16)
    g.fill_mask(yard, 0, GL - 2, FOUND)
    g.fill_mask(yard, GL - 1, GL - 1, PAVE)
    g.fill_mask(yard, GL, GL + 8, "minecraft:air")
    wall = ring(g, c, c, 16, 14)
    g.fill_mask(wall, 0, GL + 3, STONE)
    from grid import angle_index
    ai = angle_index(g, c, c, 16)
    g.fill_mask(ring(g, c, c, 16, 15) & (np.mod(ai, 3) == 0), GL + 4, GL + 4, STONE)
    g.fill_mask(ring(g, c, c, 15, 14), GL + 4, GL + 4, "minecraft:air")
    # gate gap (south)
    g.fill(c - 2, GL, c + 13, c + 2, GL + 5, c + 17, "minecraft:air")
    g.fill(c - 2, GL - 1, c + 13, c + 2, GL - 1, W - 1, ROAD)
    for x in (c - 3, c + 3):
        g.fill(x, GL, c + 14, x, GL + 6, c + 15, STONE)
    # lintel and a portcullis between the pillars
    g.fill(c - 3, GL + 6, c + 14, c + 3, GL + 6, c + 15, STONE)
    g.fill(c - 3, GL + 7, c + 14, c + 3, GL + 7, c + 15, SLAB_CAP)
    portcullis_plane(g, c - 2, c + 2, GL, 6, c + 15)
    g.mark("gate", c, GL, W - 1)
    top = GL + 30
    # a proper tower: 19 wide, so the floors are rooms around the stair and not a ledge beside it
    round_tower(g, c, c - 2, 9, top, GL, floors=(GL + 8, GL + 16, GL + 23), spiral=True,
                doors=((0, 1, GL),), banner_dirs=((0, 1), (1, 0), (-1, 0)))
    for fy in (GL + 8, GL + 16, GL + 23):
        for ang in (0.0, np.pi):
            g.fill(int(round(c + 8 * np.cos(ang))), fy + 2, int(round(c - 2 + 8 * np.sin(ang))),
                   int(round(c + 8 * np.cos(ang))), fy + 3, int(round(c - 2 + 8 * np.sin(ang))), GLASS)
    # timber hoarding around the top
    hz = ring(g, c, c - 2, 12, 10)
    g.fill_mask(hz, top - 2, top - 2, "minecraft:spruce_planks")
    g.fill_mask(ring(g, c, c - 2, 12, 11), top - 1, top + 1, "minecraft:spruce_fence")
    g.fill_mask(hz, top + 2, top + 2, "minecraft:dark_oak_slab[type=bottom]")
    for k in range(8):
        ang = k * np.pi / 4
        x = int(round(c + 11 * np.cos(ang))); z = int(round(c - 2 + 11 * np.sin(ang)))
        g.fill(x, top - 5, z, x, top - 3, z, "minecraft:dark_oak_log[axis=y]")
        g.mark("archer", int(round(c + 7 * np.cos(ang))), top, int(round(c - 2 + 7 * np.sin(ang))))
    g.mark("banner_stand", c + 5, top, c - 2)
    # the castellan's floor: his chair, the war table and the strongbox, all clear of the stair core
    g.mark("lord", c + 4, GL + 17, c - 2)
    g.stair(c + 5, GL + 17, c - 2, "minecraft:dark_oak_stairs", "west")
    g.mark("war_table", c - 4, GL + 17, c - 2)
    g.chest(c + 5, GL + 17, c - 5, "west", LOOT + "watchtower_treasury")
    g.chest(c + 5, GL + 17, c + 1, "west", LOOT + "watchtower_treasury")
    g.bed(c - 4, GL + 24, c - 5, "east", "red")
    g.mark("captain", c, GL, c + 10)
    g.lantern(c + 4, GL + 15, c + 2); g.lantern(c - 4, GL + 15, c - 6); g.lantern(c + 4, GL + 22, c + 2)
    # a hut in the yard
    building(g, c - 13, c + 4, c - 6, c + 11, GL, 5, "x", door_cells=((c - 6, c + 7),), chimney=(c - 11, c + 6))
    g.bed(c - 12, GL, c + 5, "north", "brown"); g.bed(c - 12, GL, c + 9, "south", "brown")
    g.barrel(c - 8, GL, c + 10, LOOT + "castle_barracks")
    for (x, z) in ((c + 8, c + 6), (c - 4, c + 10), (c + 10, c - 6), (c - 10, c - 4), (c + 2, c + 9)):
        g.mark("knight", x, GL, z)
    g.mark("captain", c, GL, c + 8)
    g.mark("rally", c, GL, c + 10)
    lamp_post(g, c - 3, c + 11, GL); lamp_post(g, c + 3, c + 11, GL)
    return g, {"name": "watchtower_a", "tier": 1, "GL": GL}
