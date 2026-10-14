"""Additional Stormhold layouts: ring fortress, castle-town, tower house, peel tower."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, rect_ring, spiral_stair, gable_roof, cone_roof, angle_index
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR,
                   DARK_STAIR, SLAB_CAP, RAIL, PLANK, DARK_PLANK, PORTCULLIS, GLASS,
                   curtain, ring_grids, posts_on_walk, round_tower, square_tower, straight_wall,
                   pointed_arch, building, lamp_post, tree_weirwood)
from castle import portcullis_plane, bartizan

LOOT = "stormhold:chests/"
AIR = "minecraft:air"


def portcullis_plane_x(g, z0, z1, y0, height, x):
    for y in range(y0, y0 + height):
        for z in range(z0, z1 + 1):
            if g.get(x, y, z) == AIR:
                g.set(x, y, z, PORTCULLIS)


def parapet_rect(g, x0, z0, x1, z1, y):
    """Crenellated parapet ring standing on level y-1 (blocks at y..y+2)."""
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            g.set(x, y, z, STONE)
            if x % 4 < 2:
                g.set(x, y + 1, z, STONE); g.set(x, y + 2, z, SLAB_CAP)
    for z in range(z0, z1 + 1):
        for x in (x0, x1):
            g.set(x, y, z, STONE)
            if z % 4 < 2:
                g.set(x, y + 1, z, STONE); g.set(x, y + 2, z, SLAB_CAP)


def hall_furnish(g, x0, x1, z_throne, z_end, GL, throne_facing="south"):
    """Throne dais at z_throne, carpet toward z_end, tables either side."""
    cx = (x0 + x1) // 2
    step = 1 if z_end > z_throne else -1
    g.fill(cx - 4, GL, z_throne, cx + 4, GL, z_throne + step, "minecraft:polished_deepslate")
    for x in range(cx - 4, cx + 5):
        g.stair(x, GL, z_throne + 2 * step, "minecraft:polished_deepslate_stairs",
                "north" if step > 0 else "south")
    g.stair(cx, GL + 1, z_throne, "minecraft:dark_oak_stairs", throne_facing)
    g.set(cx - 1, GL + 1, z_throne, "minecraft:dark_oak_fence"); g.set(cx + 1, GL + 1, z_throne, "minecraft:dark_oak_fence")
    g.wall_banner(cx - 2, GL + 5, z_throne, throne_facing); g.wall_banner(cx + 2, GL + 5, z_throne, throne_facing)
    lo, hi = sorted((z_throne + 3 * step, z_end))
    g.fill(cx - 1, GL, lo, cx + 1, GL, hi, "minecraft:red_carpet")
    for tx in (x0 + 2, x1 - 2):
        if tx < cx - 2 or tx > cx + 2:
            g.fill(tx, GL, lo + 1, tx, GL, hi - 1, "minecraft:dark_oak_fence")
            g.fill(tx, GL + 1, lo + 1, tx, GL + 1, hi - 1, "minecraft:dark_oak_slab[type=bottom]")
    g.mark("lord", cx, GL + 1, z_throne + 2 * step)
    g.mark("war_table", cx - 3, GL + 1, z_throne + step)


def great_castle_b(seed=21):
    """Ring fortress: round curtain with eight towers, shell keep, round donjon."""
    W, H, GL = 128, 74, 6
    g = Grid(W, H, W, seed)
    c = 64
    xs, zs = g.xz()
    rr = np.sqrt((xs - c) ** 2 + (zs - c) ** 2)
    Ro, thick, walk = 54, 6, GL + 22
    inside = rr <= Ro + 2.5
    g.fill_mask(inside, 0, GL - 2, FOUND)
    g.fill_mask(inside, GL - 1, GL - 1, PAVE)
    g.fill_mask(inside, GL, GL + 12, AIR)
    moat = (rr > Ro + 2.5) & (rr <= 61.5)
    bank = (rr > 61.5) & (rr <= 64)
    g.fill_mask(moat, 0, GL - 6, DARK)
    g.fill_mask(moat, GL - 5, GL - 5, Mix(("minecraft:gravel", 60), ("minecraft:clay", 25), ("minecraft:mud", 15)))
    g.fill_mask(moat, GL - 4, GL - 2, "minecraft:water")
    g.fill_mask(moat | bank, GL - 1, GL + 6, AIR)
    g.fill_mask(bank, 0, GL - 2, FOUND)
    g.fill_mask(bank, GL - 1, GL - 1, "minecraft:grass_block")

    # ---- outer bailey buildings ----
    building(g, 18, 52, 28, 76, GL, 9, "z", door_cells=((28, 60), (28, 70)), upper_floor=GL + 4,
             chimney=(22, 64), timber=True)
    for z in range(54, 75, 3):
        g.bed(19, GL, z, "west", "brown"); g.bed(19, GL + 5, z, "west", "brown")
    g.barrel(27, GL, 55, LOOT + "castle_barracks", facing="west")
    g.barrel(27, GL, 73, LOOT + "castle_barracks", facing="west")
    building(g, 99, 54, 108, 74, GL, 6, "z")
    g.fill(99, GL, 56, 99, GL + 3, 72, AIR)
    for z in range(56, 73, 4):
        g.fill(100, GL, z, 106, GL, z, "minecraft:spruce_fence")
        g.set(107, GL, z + 2, "minecraft:hay_block[axis=y]")
    building(g, 82, 28, 92, 37, GL, 7, "x", door_cells=((86, 37),), chimney=(90, 30))
    g.set(84, GL, 30, "minecraft:anvil[facing=east]"); g.set(85, GL, 33, "minecraft:smithing_table")
    g.set(90, GL, 34, "minecraft:blast_furnace[facing=north,lit=false]")
    g.chest(83, GL, 35, "east", LOOT + "castle_smithy")
    building(g, 36, 28, 46, 38, GL, 9, "x", door_cells=((41, 38),))
    cols = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple"]
    for i, x in enumerate(range(38, 45)):
        g.fill(x, GL + 2, 28, x, GL + 6, 28, f"minecraft:{cols[i % 7]}_stained_glass_pane")
    for x in range(38, 45):
        g.set(x, GL + 1, 30, f"minecraft:candle[candles={1 + x % 4},lit=true]")
    g.fill(38, GL, 30, 44, GL, 30, "minecraft:polished_andesite")
    building(g, 30, 84, 42, 94, GL, 7, "x", door_cells=((36, 84),), chimney=(32, 92))
    g.fill(31, GL, 92, 34, GL + 1, 93, "minecraft:hay_block[axis=y]")
    for x in (38, 39, 40):
        g.barrel(x, GL, 93, LOOT + "castle_kitchen")
    for x in range(80, 95):
        for z in (86, 98):
            g.set(x, GL, z, "minecraft:spruce_fence")
    for z in range(86, 99):
        for x in (80, 94):
            if z not in (91, 92, 93):
                g.set(x, GL, z, "minecraft:spruce_fence")
    for (x, z) in ((84, 90), (88, 90), (84, 95), (88, 95)):
        g.set(x, GL, z, "minecraft:dark_oak_fence"); g.set(x, GL + 1, z, "minecraft:hay_block[axis=y]")
        g.set(x, GL + 2, z, "minecraft:carved_pumpkin[facing=west]")
    for i, z0 in enumerate((96, 101)):
        for (x, z) in ((50, z0), (56, z0), (50, z0 + 3), (56, z0 + 3)):
            g.fill(x, GL, z, x, GL + 2, z, "minecraft:spruce_fence")
        g.fill(50, GL + 3, z0, 56, GL + 3, z0 + 3, f"minecraft:{['red', 'yellow'][i]}_wool")
        g.fill(51, GL, z0 + 1, 55, GL, z0 + 1, "minecraft:barrel[facing=up]")
    # well
    g.fill(74, GL - 3, 102, 78, GL - 1, 106, "minecraft:stone_bricks")
    g.fill(75, GL - 3, 103, 77, GL - 1, 105, "minecraft:water")
    for (x, z) in ((74, 102), (78, 102), (74, 106), (78, 106)):
        g.fill(x, GL, z, x, GL + 2, z, "minecraft:dark_oak_fence")
    g.fill(74, GL + 3, 102, 78, GL + 3, 106, "minecraft:dark_oak_slab[type=bottom]")

    # ---- shell keep ----
    kc_x, kc_z, kR = 64, 60, 26
    sd, salong, sout = ring_grids(g, kc_x, kc_z, kR)
    swalk = GL + 16
    g.fill_mask((sd > 3), GL - 1, GL - 1, Mix(("minecraft:stone_bricks", 45), ("minecraft:cobblestone", 30),
                                             ("minecraft:andesite", 25)))
    curtain(g, None, None, 4, swalk, GL, gallery=False, banners_every=12, grids=(sd, salong, sout))
    posts_on_walk(g, sd, salong, swalk, "archer", dsel=1, every=7, offset=3)
    pointed_arch(g, 62, 66, GL, 7, 80, 88)
    g.fill(62, GL - 1, 80, 66, GL - 1, 88, ROAD)
    portcullis_plane(g, 62, 66, GL, 7, 86)
    for tx in (57, 71):
        round_tower(g, tx, 85, 4, swalk + 6, GL, floors=(swalk - 1,), spiral=False, cone=True, t=2,
                    doors=((1 if tx < 64 else -1, 0, swalk),))
        g.mark("crossbowman", tx, swalk + 6, 85) if False else None
    g.mark("captain", 64, GL, 78); g.mark("rally", 64, GL, 77)
    # buildings inside the shell
    building(g, 44, 52, 50, 68, GL, 7, "z", door_cells=((50, 60),), chimney=(46, 56))
    for z in range(54, 67, 3):
        g.bed(45, GL, z, "west", "gray")
    building(g, 78, 52, 84, 68, GL, 7, "z", door_cells=((78, 60),))
    for z in range(54, 67, 3):
        g.barrel(83, GL, z, LOOT + "castle_armory", facing="west")
    tree_weirwood(g, 73, 72, GL)

    # ---- donjon (round) ----
    dx, dz, dr = 64, 56, 11
    dtop = GL + 52
    F2, F3 = GL + 11, GL + 21
    round_tower(g, dx, dz, dr, dtop, GL, floors=(F2, F3, GL + 31, GL + 41), spiral=False,
                doors=((0, 1, GL),), banner_dirs=((0, 1), (1, 0), (-1, 0), (0, -1)))
    spiral_stair(g, 59, 56, GL, dtop - 1, STONE_STAIR, STONE)
    for (x, z) in ((57, 54), (57, 58), (61, 54)):
        g.set(x, dtop, z, RAIL)
    for k in range(4):
        ang = np.pi / 4 + k * np.pi / 2
        bartizan(g, int(round(dx + 9.5 * np.cos(ang))), int(round(dz + 9.5 * np.sin(ang))), dtop + 1)
    g.fill(dx - 7, GL - 1, dz - 7, dx + 7, GL - 1, dz + 7, FLOOR_STONE)
    for x in range(dx - 8, dx + 9):
        for z in range(dz - 8, dz + 9):
            if (x - dx) ** 2 + (z - dz) ** 2 <= 64 and g.get(x, GL - 1, z) != AIR:
                g.set(x, GL - 1, z, "minecraft:polished_deepslate" if (x + z) % 2 else "minecraft:polished_andesite")
    hall_furnish(g, 60, 68, 48, 64, GL)
    g.lantern(dx + 3, F2 - 1, dz); g.lantern(dx - 1, F2 - 1, dz + 5)
    for (x, z) in ((dx + 6, dz - 3), (dx + 6, dz + 3), (dx + 4, dz + 6), (dx - 2, dz + 7)):
        g.chest(x, F2 + 1, z, "west", LOOT + "castle_treasury")
    g.fill(dx + 3, F2 + 1, dz - 1, dx + 4, F2 + 1, dz + 1, "minecraft:gold_block")
    for z in (dz - 3, dz + 3):
        g.bed(dx + 5, F3 + 1, z, "east", "red")
    g.mark("banner_stand", 68, dtop, 59)
    for (x, z) in ((70, 52), (70, 60), (66, 63)):
        g.mark("crossbowman", x, dtop, z)
    g.mark("captain", 64, GL, 69)
    for (x, z) in ((61, 60), (67, 60), (64, 58)):
        g.mark("knight", x, GL, z)

    # ---- outer curtain + towers ----
    d, along, out = ring_grids(g, c, c, Ro)
    curtain(g, None, None, thick, walk, GL, grids=(d, along, out), banners_every=14)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=1)
    ttop = walk + 10
    for k in range(8):
        deg = k * 45
        if deg == 90:
            continue
        ang = np.radians(deg)
        cx, cz = int(round(c + 52 * np.cos(ang))), int(round(c + 52 * np.sin(ang)))
        tx, tz = -np.sin(ang), np.cos(ang)
        round_tower(g, cx, cz, 7, ttop, GL, floors=(GL + 8, GL + 16, walk - 1), spiral=True,
                    cone=(k % 2 == 1), doors=((tx, tz, walk), (-tx, -tz, walk), (tx, tz, GL + 9), (-tx, -tz, GL + 9),
                                             (-np.cos(ang), -np.sin(ang), GL)),
                    banner_dirs=((round(np.cos(ang), 2), round(np.sin(ang), 2)),))
        if k % 2 == 0:
            for j in range(6):
                a2 = j * np.pi / 3
                g.mark("archer", int(round(cx + 4.5 * np.cos(a2))), ttop, int(round(cz + 4.5 * np.sin(a2))))

    # ---- main gatehouse (south) ----
    gtop = walk + 6
    g.fill(58, 0, 106, 70, gtop - 1, 122, STONE)
    for tx in (55, 73):
        round_tower(g, tx, 115, 6, gtop + 4, GL, floors=(GL + 8, walk - 1), spiral=False, cone=True,
                    doors=((0, -1, walk), (1 if tx < 64 else -1, 0, gtop)))
    pointed_arch(g, 61, 67, GL, 10, 104, 122)
    g.fill(61, GL - 1, 88, 67, GL - 1, 122, ROAD)
    portcullis_plane(g, 61, 67, GL, 10, 121)
    portcullis_plane(g, 61, 67, GL, 10, 107)
    g.fill(59, GL + 12, 108, 69, gtop - 2, 120, AIR)
    g.fill(59, GL + 11, 108, 69, GL + 11, 120, PLANK)
    for z in range(110, 120, 2):
        g.fill(64, GL + 10, z, 64, GL + 11, z, AIR)
    spiral_stair(g, 61, 111, GL + 12, gtop - 1, STONE_STAIR, STONE)
    g.fill(58, gtop - 1, 106, 70, gtop - 1, 122, STONE)
    g.fill(59, gtop - 1, 109, 63, gtop - 1, 113, AIR)
    parapet_rect(g, 57, 105, 71, 123, gtop)
    g.fill(58, gtop, 106, 70, gtop + 4, 122, AIR)
    g.fill(58, gtop - 1, 106, 70, gtop - 1, 122, STONE)
    g.fill(59, gtop - 1, 109, 63, gtop - 1, 113, AIR)
    for (x, z) in ((60, 107), (68, 107), (60, 120), (68, 120), (64, 118)):
        g.mark("crossbowman", x, gtop, z)
    g.wall_banner(60, GL + 14, 123, "south"); g.wall_banner(68, GL + 14, 123, "south")
    g.fill(61, GL - 1, 123, 67, GL - 1, W - 1, "minecraft:spruce_planks")
    for x in (60, 68):
        g.fill(x, GL - 1, 123, x, GL - 1, W - 1, "minecraft:dark_oak_log[axis=z]")
        g.fill(x, GL, 123, x, GL, W - 1, "minecraft:dark_oak_fence")
    g.mark("gate", 64, GL, W - 1)
    for z in range(90, 104, 4):
        g.mark("knight", 61, GL, z); g.mark("knight", 67, GL, z)
        lamp_post(g, 59, z, GL); lamp_post(g, 69, z, GL)
    # bailey patrol points
    for k in range(24):
        ang = k * 2 * np.pi / 24
        for rad in (33, 43):
            x, z = int(round(c + rad * np.cos(ang))), int(round(c + rad * np.sin(ang)))
            g.mark("knight", x, GL, z)
    return g, {"name": "great_castle_b", "tier": 3, "GL": GL}


def town_house(g, x0, z0, x1, z1, GL, rng):
    wall_h = int(rng.integers(6, 9))
    axis = "x" if (x1 - x0) > (z1 - z0) else "z"
    timber = bool(rng.random() < 0.7)
    door = ((x0 + x1) // 2, z1) if rng.random() < 0.5 else ((x0 + x1) // 2, z0)
    chim = (x0 + 2, z0 + 2) if rng.random() < 0.6 else None
    building(g, x0, z0, x1, z1, GL, wall_h, axis, door_cells=(door,), upper_floor=GL + 4 if wall_h >= 7 else None,
             chimney=chim, timber=timber)
    g.bed(x0 + 1, GL, z0 + 1 + (1 if door[1] == z0 else 0), "east", rng.choice(["red", "brown", "green", "blue"]))
    g.barrel(x1 - 1, GL, z1 - 1 if door[1] == z0 else z0 + 1, LOOT + "town_house")


def great_castle_c(seed=33):
    """Castle-town: long walled town with gates at both ends, keep enclosure on the north side."""
    X, Z, H, GL = 160, 112, 72, 6
    g = Grid(X, H, Z, seed)
    xs, zs = g.xz()
    e = np.minimum(np.minimum(xs, X - 1 - xs), np.minimum(zs, Z - 1 - zs))
    inside = e >= 6
    g.fill_mask(inside, 0, GL - 2, FOUND)
    g.fill_mask(inside, GL - 1, GL - 1, PAVE)
    g.fill_mask(inside, GL, GL + 12, AIR)
    ditch = (e >= 1) & (e <= 5)
    g.fill_mask(ditch, 0, GL - 5, FOUND)
    g.fill_mask(ditch, GL - 4, GL - 4, Mix(("minecraft:coarse_dirt", 50), ("minecraft:gravel", 30), ("minecraft:mud", 20)))
    g.fill_mask(ditch, GL - 3, GL + 6, AIR)
    g.fill_mask(e == 0, 0, GL - 2, FOUND)
    g.fill_mask(e == 0, GL - 1, GL - 1, "minecraft:grass_block")
    g.fill_mask(e == 0, GL, GL + 6, AIR)
    for x in range(2, X - 2, 3):
        for z in (3, Z - 4):
            g.set(x, GL - 3, z, "minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]")
    rng = g.rng
    ax, bx, az, bz = 8, X - 9, 8, Z - 9
    thick, walk = 5, GL + 20
    cz_mid = 56
    # ---- main street + market ----
    g.fill(ax, GL - 1, cz_mid - 4, bx, GL - 1, cz_mid + 4, ROAD)
    g.fill(66, GL - 1, 62, 94, GL - 1, 90, Mix(("minecraft:stone_bricks", 50), ("minecraft:polished_andesite", 30),
                                                ("minecraft:cobblestone", 20)))
    g.fill(76, GL - 1, 72, 84, GL - 1, 80, "minecraft:stone_bricks")
    g.fill(77, GL, 73, 83, GL, 79, "minecraft:stone_brick_wall")
    g.fill(78, GL - 1, 74, 82, GL - 1, 78, "minecraft:water")
    g.fill(78, GL, 74, 82, GL, 78, AIR)
    g.fill(80, GL - 1, 76, 80, GL + 4, 76, "minecraft:chiseled_stone_bricks")
    g.lantern(80, GL + 5, 76, hanging=False)
    for (x0, z0, col) in ((68, 64, "red"), (68, 84, "yellow"), (88, 64, "blue"), (88, 84, "green")):
        for (x, z) in ((x0, z0), (x0 + 4, z0), (x0, z0 + 3), (x0 + 4, z0 + 3)):
            g.fill(x, GL, z, x, GL + 2, z, "minecraft:spruce_fence")
        g.fill(x0, GL + 3, z0, x0 + 4, GL + 3, z0 + 3, f"minecraft:{col}_wool")
        g.fill(x0 + 1, GL, z0 + 1, x0 + 3, GL, z0 + 1, "minecraft:barrel[facing=up]")
    for x in range(ax + 8, bx - 6, 10):
        lamp_post(g, x, cz_mid - 5, GL); lamp_post(g, x + 5, cz_mid + 5, GL)
        g.mark("knight", x + 2, GL, cz_mid)
    for (x, z) in ((72, 70), (88, 70), (72, 82), (88, 82)):
        g.mark("knight", x, GL, z)
    # ---- town houses ----
    def row(xa, xb, za, zb):
        x = xa
        while x + 7 <= xb:
            w = int(rng.integers(8, 12))
            x1 = min(x + w - 1, xb)
            if x1 - x >= 6:
                town_house(g, x, za, x1, zb, GL, rng)
            x = x1 + 3
    row(15, 54, 40, 49)
    row(106, 146, 40, 49)
    row(15, 62, 63, 73)
    row(98, 146, 63, 73)
    # ---- service buildings (south) ----
    building(g, 14, 80, 36, 97, GL, 10, "x", door_cells=((25, 80),), upper_floor=GL + 4, chimney=(16, 95), timber=True)
    for x in range(15, 36, 3):
        g.bed(x, GL, 96, "south", "brown"); g.bed(x, GL + 5, 96, "south", "brown")
    g.barrel(35, GL, 82, LOOT + "castle_barracks", facing="west")
    building(g, 40, 80, 58, 97, GL, 9, "x", door_cells=((49, 80),))
    cols = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple"]
    for i, x in enumerate(range(42, 57, 2)):
        g.fill(x, GL + 2, 97, x, GL + 6, 97, f"minecraft:{cols[i % 7]}_stained_glass_pane")
    for x in range(44, 55):
        g.set(x, GL + 1, 95, f"minecraft:candle[candles={1 + x % 4},lit=true]")
    building(g, 100, 80, 118, 97, GL, 7, "x", door_cells=((109, 80),), chimney=(116, 95))
    g.set(104, GL, 84, "minecraft:anvil[facing=east]"); g.set(108, GL, 84, "minecraft:smithing_table")
    g.set(116, GL, 86, "minecraft:blast_furnace[facing=west,lit=false]")
    g.set(114, GL, 95, "minecraft:lava_cauldron")
    g.chest(117, GL, 90, "west", LOOT + "castle_smithy")
    building(g, 124, 80, 146, 97, GL, 6, "x")
    g.fill(126, GL, 80, 144, GL + 3, 80, AIR)
    for x in range(126, 145, 4):
        g.fill(x, GL, 81, x, GL, 95, "minecraft:spruce_fence")
        g.set(x + 2, GL, 96, "minecraft:hay_block[axis=y]")
    # ---- keep enclosure (north centre) ----
    kw = GL + 12
    straight_wall(g, 56, az + thick, 58, 46, kw, "west", GL)
    straight_wall(g, 101, az + thick, 103, 46, kw, "east", GL)
    straight_wall(g, 56, 44, 103, 46, kw, "south", GL)
    for (x0, x1) in ((70, 76), (83, 89)):
        square_tower(g, x0, 41, x1, 48, kw + 6, GL, floors=(kw - 1,), banner_sides=("south",))
        g.mark("crossbowman", x0 + 2, kw + 6, 46); g.mark("crossbowman", x1 - 2, kw + 6, 46)
    g.fill(77, 0, 43, 82, kw + 2, 47, STONE)
    pointed_arch(g, 77, 82, GL, 7, 43, 47)
    g.fill(77, GL - 1, 43, 82, GL - 1, 51, ROAD)
    portcullis_plane(g, 77, 82, GL, 7, 47)
    g.mark("captain", 79, GL, 50); g.mark("rally", 80, GL, 50)
    for x in range(60, 100, 6):
        g.mark("archer", x, kw, 45)
    for z in range(16, 44, 6):
        g.mark("archer", 57, kw, z); g.mark("archer", 102, kw, z)
    g.fill(59, GL - 1, 13, 100, GL - 1, 43, Mix(("minecraft:stone_bricks", 50), ("minecraft:cobblestone", 30),
                                                 ("minecraft:gravel", 20)))
    # keep
    kx0, kz0, kx1, kz1 = 66, 15, 93, 40
    kroof = GL + 28
    F2, F3 = GL + 10, GL + 19
    g.fill(kx0, 0, kz0, kx1, kroof - 1, kz1, STONE)
    g.fill(kx0 + 3, GL, kz0 + 3, kx1 - 3, kroof - 2, kz1 - 3, AIR)
    g.fill(kx0 + 3, GL - 1, kz0 + 3, kx1 - 3, GL - 1, kz1 - 3, FLOOR_STONE)
    g.fill(kx0 + 3, F2, kz0 + 3, kx1 - 3, F2, kz1 - 3, DARK_PLANK)
    g.fill(kx0 + 3, F3, kz0 + 3, kx1 - 3, F3, kz1 - 3, PLANK)
    g.fill(kx0, kroof - 1, kz0, kx1, kroof - 1, kz1, STONE)
    for y in (GL + 3, F2, F3):
        for x in range(kx0, kx1 + 1):
            g.set(x, y, kz0, DARK); g.set(x, y, kz1, DARK)
        for z in range(kz0, kz1 + 1):
            g.set(kx0, y, z, DARK); g.set(kx1, y, z, DARK)
    for x in range(kx0 - 1, kx1 + 2):
        for z, f in ((kz0 - 1, "south"), (kz1 + 1, "north")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if x % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
    for z in range(kz0 - 1, kz1 + 2):
        for x, f in ((kx0 - 1, "east"), (kx1 + 1, "west")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if z % 2 == 0:
                g.stair(x, kroof - 2, z, STONE_STAIR, f, half="top")
    for x in range(kx0 - 1, kx1 + 2):
        for z in (kz0 - 1, kz1 + 1):
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if x % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for z in range(kz0 - 1, kz1 + 2):
        for x in (kx0 - 1, kx1 + 1):
            g.fill(x, kroof - 1, z, x, kroof, z, STONE)
            if z % 4 < 2:
                g.set(x, kroof + 1, z, STONE); g.set(x, kroof + 2, z, SLAB_CAP)
    for z in range(kz0 + 4, kz1 - 2, 4):
        for x in (kx0, kx1):
            g.fill(x, GL + 3, z, x, GL + 7, z, GLASS)
            g.fill(x, F2 + 3, z, x, F2 + 4, z, GLASS); g.fill(x, F3 + 3, z, x, F3 + 4, z, GLASS)
    g.fill(78, GL, kz1 - 2, 81, GL + 5, kz1, AIR)
    gable_roof(g, kx0 + 3, 28, kx1 - 3, kz1 - 3, kroof, "x", ROOF_STAIR, STONE)
    for (cx, cz) in ((kx0, kz0), (kx1, kz0), (kx0, kz1), (kx1, kz1)):
        round_tower(g, cx, cz, 4, kroof + 8, GL, floors=(kroof - 1,), spiral=False, cone=True, t=2,
                    doors=((1 if cx < 80 else -1, 0, kroof),))
    # donjon
    tx0, tz0, tx1, tz1 = 74, 15, 85, 26
    dtop = GL + 48
    square_tower(g, tx0, tz0, tx1, tz1, dtop, GL, floors=(F2, F3, kroof - 1, GL + 38), spiral_at=(79, 20),
                 banner_sides=("north", "south", "east", "west"))
    for (cx, cz) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx, cz, dtop + 1)
    g.fill(82, GL, 24, 82, GL + 2, 26, AIR)
    for y in (F2 + 1, F3 + 1, kroof):
        g.fill(82, y, 24, 82, y + 1, 26, AIR)
    g.mark("banner_stand", 82, dtop, 23)
    for (x, z) in ((83, 18), (77, 23)):
        g.mark("crossbowman", x, dtop, z)
    hall_furnish(g, 70, 89, 28, 37, GL)
    spiral_stair(g, 71, 34, GL, kroof - 1, STONE_STAIR, STONE)
    for z in range(19, 26, 3):
        g.chest(kx1 - 4, F2 + 1, z, "west", LOOT + "castle_treasury")
        g.chest(kx0 + 4, F2 + 1, z, "east", LOOT + "castle_treasury")
    for z in range(30, 37, 3):
        g.bed(kx1 - 5, F3 + 1, z, "east", "red")
        g.bed(kx0 + 6, F2 + 1, z, "west", "gray")
    for x in range(70, 90, 6):
        for z in (22, 33):
            g.lantern(x, F2 - 1, z); g.lantern(x, F3 - 1, z); g.lantern(x, kroof - 2, z)
    for (x, z) in ((70, 31), (89, 31), (70, 36), (89, 36)):
        g.mark("knight", x, GL, z)
    g.mark("captain", 76, GL, 34); g.mark("captain", 84, GL, 34)
    for (x, z) in ((62, 20), (62, 34), (97, 20), (97, 34), (66, 43), (93, 43)):
        g.mark("knight", x, GL, z)
    tree_weirwood(g, 61, 16, GL)

    # ---- curtain, towers, gates ----
    d, along, out = curtain(g, ax, bx, thick, walk, GL, az=az, bz=bz, banners_every=16)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=1)
    ctop = walk + 9
    for (x0, z0, sx, sz) in ((ax - 3, az - 3, 1, 1), (bx - 9, az - 3, -1, 1), (ax - 3, bz - 9, 1, -1), (bx - 9, bz - 9, -1, -1)):
        x1, z1 = x0 + 12, z0 + 12
        square_tower(g, x0, z0, x1, z1, ctop, GL, floors=(GL + 8, walk - 1), spiral_at=(x0 + 6, z0 + 6))
        for (cx, cz) in ((x0 - 1, z0 - 1), (x1 + 1, z0 - 1), (x0 - 1, z1 + 1), (x1 + 1, z1 + 1)):
            bartizan(g, cx, cz, ctop + 1)
        for y in (walk, GL + 9):
            g.fill(x0 + 5, y, z1 if sz > 0 else z0, x0 + 7, y + 1, z1 if sz > 0 else z0, AIR) if False else None
        # doors toward both wall walks
        wx = x1 if sx > 0 else x0
        wz = z1 if sz > 0 else z0
        for y in (walk, GL + 9):
            g.fill(wx, y, z0 + 5, wx, y + 1, z0 + 7, AIR)
            g.fill(x0 + 5, y, wz, x0 + 7, y + 1, wz, AIR)
        g.fill(x0 + 5, GL, wz, x0 + 7, GL + 2, wz, AIR)
        g.mark("archer", x0 + 3, ctop, z0 + 3); g.mark("archer", x1 - 3, ctop, z1 - 3)
    for xpos in (44, 116):
        for (cz, sgn) in ((az + 1, 1), (bz - 1, -1)):
            round_tower(g, xpos, cz, 6, walk + 6, GL, floors=(GL + 8, walk - 1), spiral=False,
                        doors=((1, 0, walk), (-1, 0, walk), (1, 0, GL + 9), (-1, 0, GL + 9)))
            for k in range(6):
                a2 = k * np.pi / 3
                g.mark("archer", int(round(xpos + 3 * np.cos(a2))), walk + 6, int(round(cz + 3 * np.sin(a2))))
    # gatehouses west and east
    gtop = walk + 5
    for (gx, sgn, label) in ((ax, -1, "gate"), (bx, 1, "gate2")):
        bx0, bx1 = (gx - 6, gx + 8) if sgn < 0 else (gx - 8, gx + 6)
        g.fill(bx0, 0, cz_mid - 7, bx1, gtop - 1, cz_mid + 7, STONE)
        for tz in (cz_mid - 9, cz_mid + 9):
            round_tower(g, gx + sgn * 1, tz, 6, gtop + 4, GL, floors=(GL + 8, walk - 1), spiral=False, cone=True,
                        doors=((-sgn, 0, walk), (0, -1 if tz > cz_mid else 1, gtop)))
        pointed_arch(g, cz_mid - 3, cz_mid + 3, GL, 9, bx0, bx1, axis="x")
        g.fill(bx0, GL - 1, cz_mid - 3, bx1, GL - 1, cz_mid + 3, ROAD)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 9, bx0 + 1 if sgn < 0 else bx1 - 1)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 9, bx1 - 1 if sgn < 0 else bx0 + 1)
        g.fill(bx0 + 1, GL + 11, cz_mid - 6, bx1 - 1, gtop - 2, cz_mid + 6, AIR)
        g.fill(bx0 + 1, GL + 10, cz_mid - 6, bx1 - 1, GL + 10, cz_mid + 6, PLANK)
        sp_x = (bx0 + 3) if sgn < 0 else (bx1 - 3)
        spiral_stair(g, sp_x, cz_mid + 3, GL + 11, gtop - 1, STONE_STAIR, STONE)
        parapet_rect(g, bx0 - 1, cz_mid - 8, bx1 + 1, cz_mid + 8, gtop)
        g.fill(bx0, gtop, cz_mid - 7, bx1, gtop + 4, cz_mid + 7, AIR)
        g.fill(bx0, gtop - 1, cz_mid - 7, bx1, gtop - 1, cz_mid + 7, STONE)
        g.fill(sp_x - 2, gtop - 1, cz_mid + 1, sp_x + 2, gtop - 1, cz_mid + 5, AIR)
        for z in (cz_mid - 5, cz_mid, cz_mid + 5):
            g.mark("crossbowman", (bx0 + bx1) // 2, gtop, z) if z != cz_mid + 5 else None
        outside = 0 if sgn < 0 else X - 1
        g.fill(min(outside, bx0), GL - 1, cz_mid - 3, max(outside, bx1), GL - 1, cz_mid + 3, ROAD)
        g.fill(min(outside, bx0), GL - 1, cz_mid - 3, min(outside, bx0) + 5 if sgn < 0 else max(outside, bx1),
               GL - 1, cz_mid + 3, "minecraft:spruce_planks") if False else None
        g.mark(label, outside, GL, cz_mid)
        g.wall_banner(gx + sgn * 7, GL + 13, cz_mid - 5, "west" if sgn < 0 else "east")
        g.wall_banner(gx + sgn * 7, GL + 13, cz_mid + 5, "west" if sgn < 0 else "east")
    # bridges over the ditch at both gates
    for xa, xb in ((0, 6), (X - 7, X - 1)):
        g.fill(xa, GL - 1, cz_mid - 3, xb, GL - 1, cz_mid + 3, "minecraft:spruce_planks")
        g.fill(xa, GL, cz_mid - 4, xb, GL, cz_mid - 4, "minecraft:spruce_fence")
        g.fill(xa, GL, cz_mid + 4, xb, GL, cz_mid + 4, "minecraft:spruce_fence")
    return g, {"name": "great_castle_c", "tier": 3, "GL": GL}


def holdfast_b(seed=13):
    """Tier 2 tower house inside a barmkin wall."""
    W, H, GL = 56, 56, 5
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    e = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(zs, W - 1 - zs))
    g.fill_mask(e >= 2, 0, GL - 2, FOUND)
    g.fill_mask(e >= 2, GL - 1, GL - 1, PAVE)
    g.fill_mask(e >= 2, GL, GL + 10, AIR)
    g.fill_mask(e < 2, 0, GL - 2, FOUND)
    g.fill_mask(e < 2, GL - 1, GL - 1, "minecraft:grass_block")
    g.fill_mask(e < 2, GL, GL + 6, AIR)
    # barmkin wall
    wtop = GL + 8
    body = rect_ring(g, 4, 4, 51, 51, 2)
    g.fill_mask(body, 0, wtop - 1, STONE)
    g.fill_mask(rect_ring(g, 3, 3, 52, 52, 1), 0, GL + 1, DARK)
    g.fill_mask(rect_ring(g, 4, 4, 51, 51, 1), GL + 3, GL + 3, DARK)
    parapet_rect(g, 4, 4, 51, 51, wtop)
    g.fill_mask(rect(g, 5, 5, 50, 50) & ~rect(g, 6, 6, 49, 49), wtop, wtop + 3, AIR)
    for x in range(8, 49, 5):
        g.mark("archer", x, wtop, 5); g.mark("archer", x, wtop, 50)
    for z in range(8, 49, 5):
        g.mark("archer", 5, wtop, z); g.mark("archer", 50, wtop, z)
    for (cx, cz) in ((5, 5), (50, 5), (5, 50), (50, 50)):
        round_tower(g, cx, cz, 3, wtop + 4, GL, floors=(), spiral=False, cone=True, t=1,
                    doors=((1 if cx < 28 else -1, 0, wtop), (0, 1 if cz < 28 else -1, wtop)))
    # stairs up to the wall walk
    for k in range(wtop - GL):
        g.stair(10 + k, GL + k, 48, STONE_STAIR, "east")
        g.fill(10 + k, GL, 48, 10 + k, GL + k - 1, 48, STONE)
    # gatehouse
    g.fill(23, 0, 46, 32, wtop + 5, 53, STONE)
    pointed_arch(g, 26, 29, GL, 6, 46, 53)
    portcullis_plane(g, 26, 29, GL, 6, 52)
    g.fill(26, GL - 1, 46, 29, GL - 1, W - 1, ROAD)
    parapet_rect(g, 22, 45, 33, 54, wtop + 6)
    g.fill(23, wtop + 6, 46, 32, wtop + 9, 53, AIR)
    for x in (24, 31):
        g.mark("crossbowman", x, wtop + 6, 52)
    g.mark("gate", 27, GL, W - 1)
    # tower house
    hx0, hz0, hx1, hz1 = 16, 10, 37, 27
    top = GL + 36
    floors = [GL + 8, GL + 15, GL + 22, GL + 29]
    g.fill(hx0, 0, hz0, hx1, top - 1, hz1, STONE)
    g.fill(hx0 + 3, GL, hz0 + 3, hx1 - 3, top - 2, hz1 - 3, AIR)
    g.fill(hx0 + 3, GL - 1, hz0 + 3, hx1 - 3, GL - 1, hz1 - 3, FLOOR_STONE)
    for fy in floors:
        g.fill(hx0 + 3, fy, hz0 + 3, hx1 - 3, fy, hz1 - 3, PLANK)
        g.lantern(26, fy - 1, 18); g.lantern(32, fy - 1, 22)
    g.fill(hx0, top - 1, hz0, hx1, top - 1, hz1, STONE)
    for x in range(hx0 - 1, hx1 + 2):
        for z, f in ((hz0 - 1, "south"), (hz1 + 1, "north")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if x % 2 == 0:
                g.stair(x, top - 2, z, STONE_STAIR, f, half="top")
    for z in range(hz0 - 1, hz1 + 2):
        for x, f in ((hx0 - 1, "east"), (hx1 + 1, "west")):
            g.fill(x, 0, z, x, GL + 1, z, DARK); g.stair(x, GL + 2, z, DARK_STAIR, f)
            if z % 2 == 0:
                g.stair(x, top - 2, z, STONE_STAIR, f, half="top")
    g.fill(hx0 - 1, top - 1, hz0 - 1, hx1 + 1, top - 1, hz1 + 1, STONE)
    parapet_rect(g, hx0 - 1, hz0 - 1, hx1 + 1, hz1 + 1, top)
    for (cx, cz) in ((hx0 - 1, hz0 - 1), (hx1 + 1, hz0 - 1), (hx0 - 1, hz1 + 1), (hx1 + 1, hz1 + 1)):
        bartizan(g, cx, cz, top + 1)
    for z in range(hz0 + 4, hz1 - 2, 4):
        for x in (hx0, hx1):
            for fy in [GL] + floors:
                g.fill(x, fy + 2, z, x, fy + 3, z, GLASS)
    for x in range(hx0 + 4, hx1 - 2, 5):
        for z in (hz0, hz1):
            for fy in floors:
                g.fill(x, fy + 2, z, x, fy + 3, z, GLASS)
    g.fill(25, GL, hz1 - 2, 27, GL + 3, hz1, AIR)
    spiral_stair(g, 21, 15, GL, top - 1, STONE_STAIR, STONE)
    # caphouse over the stair head
    g.fill(18, top, 12, 24, top + 4, 18, STONE)
    g.fill(19, top, 13, 23, top + 3, 17, AIR)
    g.fill(24, top, 14, 24, top + 2, 16, AIR)
    gable_roof(g, 18, 12, 24, 18, top + 5, "z", ROOF_STAIR, STONE)
    spiral_stair(g, 21, 15, GL, top - 1, STONE_STAIR, STONE, clear=False)
    g.wall_banner(hx0 - 2, top - 6, 18, "west"); g.wall_banner(hx1 + 2, top - 6, 18, "east")
    g.wall_banner(26, top - 6, hz1 + 2, "south")
    hall_furnish(g, 21, 34, 13, 24, GL)
    for z in range(13, 24, 3):
        g.chest(34, floors[0] + 1, z, "west", LOOT + "holdfast_treasury")
    for z in range(13, 24, 3):
        g.bed(32, floors[1] + 1, z, "east", "gray"); g.bed(32, floors[2] + 1, z, "east", "red")
    g.mark("banner_stand", 33, top, 23)
    for (x, z) in ((30, 12), (35, 25), (28, 25)):
        g.mark("archer", x, top, z)
    for (x, z) in ((30, 22), (24, 22)):
        g.mark("knight", x, GL, z)
    g.mark("captain", 26, GL, hz1 + 3); g.mark("rally", 27, GL, 40)
    # outbuildings
    building(g, 7, 31, 15, 45, GL, 6, "z", door_cells=((15, 38),), chimney=(9, 33))
    for z in range(32, 45, 3):
        g.bed(8, GL, z, "west", "brown")
    g.barrel(14, GL, 44, LOOT + "castle_barracks", facing="west")
    building(g, 41, 33, 48, 44, GL, 5, "z")
    g.fill(41, GL, 35, 41, GL + 2, 42, AIR)
    g.set(47, GL, 36, "minecraft:hay_block[axis=y]"); g.set(47, GL, 41, "minecraft:hay_block[axis=y]")
    g.fill(30, GL - 3, 36, 32, GL - 1, 38, "minecraft:stone_bricks")
    g.set(31, GL - 1, 37, "minecraft:water"); g.set(31, GL - 2, 37, "minecraft:water")
    for (x, z) in ((20, 36), (35, 36), (24, 42), (36, 44), (12, 28), (44, 28)):
        g.mark("knight", x, GL, z)
    lamp_post(g, 22, 40, GL); lamp_post(g, 33, 40, GL)
    return g, {"name": "holdfast_b", "tier": 2, "GL": GL}


def watchtower_b(seed=9):
    """Tier 1 square peel tower inside a timber palisade."""
    W, H, GL = 34, 46, 4
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, W - 1, Mix(("minecraft:grass_block", 50), ("minecraft:coarse_dirt", 25),
                                                     ("minecraft:dirt_path", 15), ("minecraft:gravel", 10)))
    g.fill(0, GL, 0, W - 1, GL + 8, W - 1, AIR)
    pal = rect_ring(g, 2, 2, 31, 31, 1)
    g.fill_mask(pal, GL, GL + 2, "minecraft:spruce_log[axis=y]")
    xs2, zs2 = g.xz()
    g.fill_mask(pal & (np.mod(xs2 + zs2, 2) == 0), GL + 3, GL + 3, "minecraft:spruce_fence")
    g.fill(15, GL, 31, 18, GL + 3, 31, AIR)
    g.fill(15, GL - 1, 28, 18, GL - 1, W - 1, "minecraft:dirt_path")
    for x in (14, 19):
        g.fill(x, GL, 31, x, GL + 5, 31, "minecraft:spruce_log[axis=y]")
    g.fill(14, GL + 6, 31, 19, GL + 6, 31, "minecraft:spruce_log[axis=x]")
    g.mark("gate", 16, GL, W - 1)
    top = GL + 28
    square_tower(g, 10, 7, 23, 20, top, GL, floors=(GL + 7, GL + 14, GL + 21), spiral_at=(15, 12),
                 banner_sides=("south", "east", "west"))
    for (cx, cz) in ((9, 6), (24, 6), (9, 21), (24, 21)):
        bartizan(g, cx, cz, top + 1)
    g.fill(18, GL, 20, 19, GL + 2, 20, AIR)
    g.mark("banner_stand", 19, top, 15)
    g.mark("lord", 18, GL + 7 + 1, 16) if False else None
    g.mark("lord", 18, GL + 15, 16)
    g.mark("war_table", 19, GL + 15, 12)
    g.chest(20, GL + 15, 17, "west", LOOT + "watchtower_treasury")
    g.chest(20, GL + 15, 11, "west", LOOT + "watchtower_treasury")
    for (x, z) in ((12, 9), (21, 18), (21, 9), (12, 18)):
        g.mark("archer", x, top, z)
    building(g, 4, 23, 11, 29, GL, 5, "x", door_cells=((11, 26),), chimney=(5, 24))
    g.bed(5, GL, 27, "north", "brown")
    g.barrel(10, GL, 24, LOOT + "castle_barracks")
    g.set(26, GL, 26, "minecraft:campfire[lit=true,signal_fire=false]")
    for (x, z) in ((27, 26), (25, 26), (26, 27)):
        g.stair(x, GL, z, "minecraft:spruce_stairs", "north")
    g.fill(26, GL, 5, 28, GL + 1, 7, "minecraft:hay_block[axis=y]")
    for (x, z) in ((6, 10), (27, 12), (16, 25), (24, 28), (6, 18)):
        g.mark("knight", x, GL, z)
    g.mark("captain", 16, GL, 23); g.mark("rally", 16, GL, 25)
    lamp_post(g, 13, 29, GL); lamp_post(g, 20, 29, GL)
    return g, {"name": "watchtower_b", "tier": 1, "GL": GL}
