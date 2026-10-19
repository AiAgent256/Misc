"""Additional Stormhold layouts: ring fortress, castle-town, tower house, peel tower."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, rect_ring, spiral_stair, dogleg_stair, gable_roof, cone_roof, angle_index
from parts import (SILL, STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR,
                   DARK_STAIR, SLAB_CAP, RAIL, PLANK, DARK_PLANK, PORTCULLIS, GLASS,
                   curtain, ring_grids, posts_on_walk, round_tower, square_tower, straight_wall,
                   pointed_arch, building, lamp_post, tree_weirwood, roof_ladder, tower_room, wall_stair)
from castle import portcullis_plane, bartizan

LOOT = "stormhold:chests/"
AIR = "minecraft:air"


def portcullis_plane_x(g, z0, z1, y0, height, x):
    g.__dict__.setdefault("gate_planes", []).append([x, y0, z0, x, y0 + height - 1, z1])
    for y in range(y0, y0 + height):
        for z in range(z0, z1 + 1):
            if g.get(x, y, z) == AIR:
                g.set(x, y, z, PORTCULLIS)


def parapet_rect(g, x0, z0, x1, z1, y):
    """Crenellated parapet ring standing on level y-1 (blocks at y..y+2). Leaves stair shafts alone."""
    p = getattr(g, "protected_xz", set())
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            if (x, z) in p:
                continue
            g.set(x, y, z, STONE if x % 4 < 2 else SILL)
            if x % 4 < 2:
                g.set(x, y + 1, z, STONE); g.set(x, y + 2, z, SLAB_CAP)
    for z in range(z0, z1 + 1):
        for x in (x0, x1):
            if (x, z) in p:
                continue
            g.set(x, y, z, STONE if z % 4 < 2 else SILL)
            if z % 4 < 2:
                g.set(x, y + 1, z, STONE); g.set(x, y + 2, z, SLAB_CAP)


def corbel_parapet_rect(g, x0, z0, x1, z1, y):
    """parapet_rect's parapet carried a block out from the wall's face on corbels, so the ledge can take
    machicolations: the wall's top course becomes walk, the ledge stands on a corbel at every other block, and the
    merlons and sills go on the ledge. Only where the outside is open air from two below the walk up; elsewhere
    (a tower, a building against the wall) the flush parapet stands as parapet_rect builds it."""
    from parts import OPPOSITE
    p = getattr(g, "protected_xz", set())
    # the flush parapet drew two random stones per merlon cell from the grid's stream: draw them still, and build
    # the ledge from a stream of its own, so the rest of the plan comes out as it did
    burn = sum(2 for x in range(x0, x1 + 1) for z in (z0, z1) if (x, z) not in p and x % 4 < 2) + \
        sum(2 for z in range(z0, z1 + 1) for x in (x0, x1) if (x, z) not in p and z % 4 < 2)
    for _ in range(burn):
        g._ids_for(STONE, 1)
    with g.private_rng(x0 * 7919 + z0 * 104729 + y):
        _corbel_parapet(g, x0, z0, x1, z1, y, p, OPPOSITE)


def _corbel_parapet(g, x0, z0, x1, z1, y, p, OPPOSITE):
    def open_air(x, z):
        return g.inb(x, y, z) and all(g.get(x, yy, z) in ("minecraft:air", "<void>") for yy in range(y - 2, y + 3))

    def crenel(x, z, a):
        g.set(x, y, z, STONE if a % 4 < 2 else SILL)
        if a % 4 < 2:
            g.set(x, y + 1, z, STONE); g.set(x, y + 2, z, SLAB_CAP)

    cells = []
    for x in range(x0, x1 + 1):
        cells += [(x, z0, x, z0 - 1, "north", x), (x, z1, x, z1 + 1, "south", x)]
    for z in range(z0 + 1, z1):
        cells += [(x0, z, x0 - 1, z, "west", z), (x1, z, x1 + 1, z, "east", z)]
    for (ix, iz, ox, oz, out, a) in cells:
        if (ix, iz) in p:
            continue
        if not open_air(ox, oz):
            crenel(ix, iz, a)                       # flush here
            continue
        if a % 2 == 0:
            g.stair(ox, y - 2, oz, STONE_STAIR, OPPOSITE[out], half="top")
        g.set(ox, y - 1, oz, STONE)
        crenel(ox, oz, a)
    # the ledge's corners: a solid merlon each, so the ledge doesn't stop short with a gap at the angle
    for (cx, cz) in ((x0 - 1, z0 - 1), (x1 + 1, z0 - 1), (x0 - 1, z1 + 1), (x1 + 1, z1 + 1)):
        if open_air(cx, cz) and sum(g.is_solid(cx + dx, y - 1, cz + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))) >= 2:
            g.set(cx, y - 1, cz, STONE); g.set(cx, y, cz, STONE); g.set(cx, y + 1, cz, STONE); g.set(cx, y + 2, cz, SLAB_CAP)


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
        # a newel stair from the ground floor to the shell walk and the roof (it was a twenty-two rung ladder)
        round_tower(g, tx, 85, 5, swalk + 6, GL, floors=(swalk - 1,), spiral=True, cone=True, t=2,
                    doors=((1, 0, swalk), (-1, 0, swalk), (0, -1, GL)))
        # a landing joining the tower's outer door to the ring walk, which curves away from it here
        lx0, lx1 = (tx - 8, tx - 4) if tx < 64 else (tx + 4, tx + 8)
        g.fill(lx0, swalk - 1, 81, lx1, swalk - 1, 86, STONE)
        g.fill(lx0, swalk, 81, lx1, swalk + 2, 86, AIR)
        g.fill(lx0, swalk, 87, lx1, swalk, 87, RAIL)
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
    dx, dz, dr = 64, 56, 12
    dtop = GL + 52
    F2, F3 = GL + 11, GL + 21
    round_tower(g, dx, dz, dr, dtop, GL, floors=(F2, F3, GL + 31, GL + 41), spiral=False,
                doors=((0, 1, GL),), banner_dirs=((0, 1), (1, 0), (-1, 0), (0, -1)))
    spiral_stair(g, 59, 56, GL, dtop - 1, STONE_STAIR, STONE)
    for (x, z) in ((57, 54), (57, 58), (61, 54)):
        g.set(x, dtop, z, RAIL)
    for k in range(4):
        ang = np.pi / 4 + k * np.pi / 2
        bartizan(g, int(round(dx + 10.5 * np.cos(ang))), int(round(dz + 10.5 * np.sin(ang))), dtop + 1)
    g.fill(dx - 8, GL - 1, dz - 8, dx + 8, GL - 1, dz + 8, FLOOR_STONE)
    for x in range(dx - 9, dx + 10):
        for z in range(dz - 9, dz + 10):
            if (x - dx) ** 2 + (z - dz) ** 2 <= 81 and g.get(x, GL - 1, z) != AIR:
                g.set(x, GL - 1, z, "minecraft:polished_deepslate" if (x + z) % 2 else "minecraft:polished_andesite")
    hall_furnish(g, 62, 70, 48, 64, GL)
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
        round_tower(g, cx, cz, 9, ttop, GL, floors=(GL + 8, GL + 16, walk - 1), spiral=True,
                    cone=(k % 2 == 1), doors=((tx, tz, walk), (-tx, -tz, walk), (tx, tz, GL + 9), (-tx, -tz, GL + 9),
                                             (-np.cos(ang), -np.sin(ang), GL)),
                    banner_dirs=((round(np.cos(ang), 2), round(np.sin(ang), 2)),))
        if k % 2 == 0:
            for j in range(6):
                a2 = j * np.pi / 3
                g.mark("archer", int(round(cx + 6.5 * np.cos(a2))), ttop, int(round(cz + 6.5 * np.sin(a2))))

    # ---- main gatehouse (south) ----
    gtop = walk + 6
    g.fill(58, 0, 106, 70, gtop - 1, 122, STONE)
    for tx in (55, 73):
        round_tower(g, tx, 115, 7, gtop + 4, GL, floors=(GL + 8, walk - 1), spiral=False, cone=True,
                    doors=((0, -1, walk), (1 if tx < 64 else -1, 0, gtop), (1, 0, walk), (-1, 0, walk)))
    pointed_arch(g, 61, 67, GL, 10, 104, 122)
    g.fill(61, GL - 1, 88, 67, GL - 1, 122, ROAD)
    portcullis_plane(g, 61, 67, GL, 10, 121)
    portcullis_plane(g, 61, 67, GL, 10, 107)
    g.fill(59, GL + 12, 108, 69, gtop - 2, 120, AIR)
    g.fill(59, GL + 11, 108, 69, GL + 11, 120, PLANK)
    # murder holes right through the vault, a crossbowman at the lip of each (see great_castle_a)
    g.lips = getattr(g, "lips", {})
    for i, z in enumerate(range(110, 120, 2)):
        g.fill(64, GL + 9, z, 64, GL + 11, z, AIR)
        side = -1 if i % 2 == 0 else 1
        g.mark("crossbowman", 64 + side, GL + 12, z)
        g.lips[(64 + side, GL + 12, z)] = (-side, 0)
    g.fill(59, walk - 1, 108, 69, walk - 1, 120, PLANK)            # gallery at wall-walk level
    spiral_stair(g, 61, 111, GL + 12, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
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
    for (x0, x1) in ((68, 76), (83, 91)):
        square_tower(g, x0, 41, x1, 48, kw + 7, GL, floors=(kw - 1,), banner_sides=("south",))
        g.mark("crossbowman", x0 + 2, kw + 7, 46); g.mark("crossbowman", x1 - 2, kw + 7, 46)
        # door from the yard, a ladder up the inside to the walk floor and on to the roof, doors onto the walk
        mx = (x0 + x1) // 2
        if x0 < 80:
            g.fill(mx + 1, GL, 44, x1 + 1, GL + 1, 45, AIR)       # from the gate passage
        else:
            g.fill(x0 - 1, GL, 44, mx - 1, GL + 1, 45, AIR)
        # from the walk room to the roof in two short ladders (the old one ran eighteen up from the ground)
        roof_ladder(g, rect(g, x0 + 3, 44, x1 - 3, 45), kw, kw + 7, (mx, 45, "north"), (mx, 44, "south"))
        g.fill(x0, kw, 45, x0 + 2, kw + 1, 45, AIR)
        g.fill(x1 - 2, kw, 45, x1, kw + 1, 45, AIR)
    # the keep's walls are climbed by two three-wide flights from its yard, against the west and east walls
    wall_stair(g, 59, 22, GL, kw, "z", "west")
    wall_stair(g, 100, 22, GL, kw, "z", "east")
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
    # posterns from the hall out to the strips of yard either side of the keep
    g.fill(kx0 - 1, GL, 26, kx0 + 3, GL + 1, 27, AIR)
    g.fill(kx1 - 3, GL, 26, kx1 + 1, GL + 1, 27, AIR)
    gable_roof(g, kx0 + 3, 28, kx1 - 3, kz1 - 3, kroof, "x", ROOF_STAIR, STONE)
    for (cx, cz) in ((kx0, kz0), (kx1, kz0), (kx0, kz1), (kx1, kz1)):
        round_tower(g, cx, cz, 4, kroof + 8, GL, floors=(kroof - 1,), spiral=False, cone=True, t=2,
                    doors=((1 if cx < 80 else -1, 0, kroof),))
    # donjon
    tx0, tz0, tx1, tz1 = 72, 15, 87, 26
    dtop = GL + 48
    square_tower(g, tx0, tz0, tx1, tz1, dtop, GL, floors=(F2, F3, kroof - 1, GL + 38), spiral_at=(79, 20),
                 banner_sides=("north", "south", "east", "west"))
    for (cx, cz) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx, cz, dtop + 1)
    g.fill(84, GL, 24, 87, GL + 2, 26, AIR)
    for y in (F2 + 1, F3 + 1, kroof):
        g.fill(84, y, 24, 87, y + 1, 26, AIR)
    g.mark("banner_stand", 82, dtop, 23)
    for (x, z) in ((83, 18), (77, 23)):
        g.mark("crossbowman", x, dtop, z)
    hall_furnish(g, 77, 89, 28, 37, GL)
    # the stair hall in the hall's south-west corner, up to the roof
    dogleg_stair(g, 69, 31, GL, kroof - 1, STONE_STAIR, STONE, DARK_PLANK, levels=(F2 + 1, F3 + 1, kroof), back="south")
    for z in range(19, 26, 3):
        g.chest(kx1 - 4, F2 + 1, z, "west", LOOT + "castle_treasury")
        g.chest(kx0 + 4, F2 + 1, z, "east", LOOT + "castle_treasury")
    for z in range(30, 37, 3):
        g.bed(kx1 - 5, F3 + 1, z, "east", "red")
        g.bed(kx1 - 5, F2 + 1, z, "east", "gray")
    for x in range(70, 90, 6):
        for z in (22, 33):
            if z == 33 and x < 77:
                continue                       # the stair hall
            g.lantern(x, F2 - 1, z); g.lantern(x, F3 - 1, z); g.lantern(x, kroof - 2, z)
    for (x, z) in ((78, 31), (89, 31), (78, 36), (89, 36)):
        g.mark("knight", x, GL, z)
    g.mark("captain", 76, GL, 34); g.mark("captain", 84, GL, 34)
    for (x, z) in ((62, 20), (62, 34), (97, 20), (97, 34), (66, 43), (93, 43)):
        g.mark("knight", x, GL, z)
    tree_weirwood(g, 61, 16, GL)

    # ---- curtain, towers, gates ----
    d, along, out = curtain(g, ax, bx, thick, walk, GL, az=az, bz=bz, banners_every=16)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=1)
    ctop = walk + 9
    for (x0, z0, sx, sz) in ((ax - 3, az - 3, 1, 1), (bx - 13, az - 3, -1, 1), (ax - 3, bz - 13, 1, -1), (bx - 13, bz - 13, -1, -1)):
        x1, z1 = x0 + 16, z0 + 16
        square_tower(g, x0, z0, x1, z1, ctop, GL, floors=(GL + 8, walk - 1), spiral_at=(x0 + 8, z0 + 8))
        for (cx, cz) in ((x0 - 1, z0 - 1), (x1 + 1, z0 - 1), (x0 - 1, z1 + 1), (x1 + 1, z1 + 1)):
            bartizan(g, cx, cz, ctop + 1)
        for y in (walk, GL + 9):
            g.fill(x0 + 5, y, z1 if sz > 0 else z0, x0 + 7, y + 1, z1 if sz > 0 else z0, AIR) if False else None
        # doors toward both wall walks
        wxa, wxb = (x1 - 2, x1) if sx > 0 else (x0, x0 + 2)
        wza, wzb = (z1 - 2, z1) if sz > 0 else (z0, z0 + 2)
        dza, dzb = (z0 + 5, z0 + 9) if sz > 0 else (z1 - 9, z1 - 5)      # where the curtain meets the tower
        dxa, dxb = (x0 + 5, x0 + 9) if sx > 0 else (x1 - 9, x1 - 5)
        for y in (walk, GL + 9):
            g.fill(wxa, y, dza, wxb, y + 1, dzb, AIR)
            g.fill(dxa, y, wza, dxb, y + 1, wzb, AIR)
        # the ground door is a notch cut from the tower's inner corner, the only face on the yard
        nx0, nx1 = (x1 - 3, x1) if sx > 0 else (x0, x0 + 3)
        nz0, nz1 = (z1 - 3, z1) if sz > 0 else (z0, z0 + 3)
        ex0, ex1 = (nx0, nx1 + 1) if sx > 0 else (nx0 - 1, nx1)
        ez0, ez1 = (nz0, nz1 + 1) if sz > 0 else (nz0 - 1, nz1)
        g.fill(ex0, GL, ez0, ex1, GL + 2, ez1, AIR)            # through the plinth too: an open porch, not a closet
        g.fill(ex0, GL - 1, ez0, ex1, GL - 1, ez1, FLOOR_STONE)
        g.mark("archer", x0 + 3, ctop, z0 + 3); g.mark("archer", x1 - 3, ctop, z1 - 3)
    for xpos in (44, 116):
        for (cz, sgn) in ((az + 1, 1), (bz - 1, -1)):
            round_tower(g, xpos, cz, 7, walk + 7, GL, floors=(GL + 8, walk - 1), spiral=False,
                        doors=((1, 0, walk), (-1, 0, walk), (1, 0, GL + 9), (-1, 0, GL + 9)))
            roof_ladder(g, tower_room(g, xpos, cz, 7), walk, walk + 7, (xpos + 2, cz, "west"), (xpos - 2, cz, "east"))
            for k in range(6):
                a2 = k * np.pi / 3
                g.mark("archer", int(round(xpos + 3 * np.cos(a2))), walk + 7, int(round(cz + 3 * np.sin(a2))))
    # gatehouses west and east
    gtop = walk + 5
    for (gx, sgn, label) in ((ax, -1, "gate"), (bx, 1, "gate2")):
        bx0, bx1 = (gx - 6, gx + 8) if sgn < 0 else (gx - 8, gx + 6)
        g.fill(bx0, 0, cz_mid - 7, bx1, gtop - 1, cz_mid + 7, STONE)
        for tz in (cz_mid - 9, cz_mid + 9):
            round_tower(g, gx + sgn * 1, tz, 7, gtop + 4, GL, floors=(GL + 8, walk - 1), spiral=False, cone=True,
                        doors=((-sgn, 0, walk), (0, -1 if tz > cz_mid else 1, gtop)))
            # the wall-walk runs straight through the tower
            px0, px1 = (gx + 2, gx + 3) if sgn < 0 else (gx - 3, gx - 2)
            g.fill(px0, walk - 1, tz - 7, px1, walk - 1, tz + 7, STONE)
            g.fill(px0, walk, tz - 7, px1, walk + 1, tz + 7, AIR)
        pointed_arch(g, cz_mid - 3, cz_mid + 3, GL, 9, bx0, bx1, axis="x")
        g.fill(bx0, GL - 1, cz_mid - 3, bx1, GL - 1, cz_mid + 3, ROAD)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 9, bx0 + 1 if sgn < 0 else bx1 - 1)
        portcullis_plane_x(g, cz_mid - 3, cz_mid + 3, GL, 9, bx1 - 1 if sgn < 0 else bx0 + 1)
        g.fill(bx0 + 1, GL + 11, cz_mid - 6, bx1 - 1, gtop - 2, cz_mid + 6, AIR)
        g.fill(bx0 + 1, GL + 10, cz_mid - 6, bx1 - 1, GL + 10, cz_mid + 6, PLANK)
        sp_x = (bx0 + 3) if sgn < 0 else (bx1 - 3)
        g.fill(bx0 + 1, walk - 1, cz_mid - 6, bx1 - 1, walk - 1, cz_mid + 6, PLANK)    # gallery at wall-walk level
        spiral_stair(g, sp_x, cz_mid + 3, GL + 11, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
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
    """Tier 2 tower house inside a barmkin wall, with a yard a garrison can live in."""
    W, H, GL = 72, 56, 5
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    c = W // 2
    e = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(zs, W - 1 - zs))
    g.fill_mask(e >= 2, 0, GL - 2, FOUND)
    g.fill_mask(e >= 2, GL - 1, GL - 1, PAVE)
    g.fill_mask(e >= 2, GL, GL + 10, AIR)
    g.fill_mask(e < 2, 0, GL - 2, FOUND)
    g.fill_mask(e < 2, GL - 1, GL - 1, "minecraft:grass_block")
    g.fill_mask(e < 2, GL, GL + 6, AIR)
    # barmkin wall
    wtop = GL + 8
    o = W - 5          # the wall's outer line on the far side
    # four thick, so the walk behind the parapet is three: a man can pass another with his bow drawn
    body = rect_ring(g, 4, 4, o, o, 4)
    g.fill_mask(body, 0, wtop - 1, STONE)
    g.fill_mask(rect_ring(g, 3, 3, o + 1, o + 1, 1), 0, GL + 1, DARK)
    g.fill_mask(rect_ring(g, 4, 4, o, o, 1), GL + 3, GL + 3, DARK)
    corbel_parapet_rect(g, 4, 4, o, o, wtop)
    g.fill_mask(rect(g, 5, 5, o - 1, o - 1) & ~rect(g, 8, 8, o - 4, o - 4), wtop, wtop + 3, AIR)
    for x in range(8, o - 2, 5):
        g.mark("archer", x, wtop, 5); g.mark("archer", x, wtop, o - 1)
    for z in range(8, o - 2, 5):
        g.mark("archer", 5, wtop, z); g.mark("archer", o - 1, wtop, z)
    for (cx, cz) in ((5, 5), (o - 1, 5), (5, o - 1), (o - 1, o - 1)):
        round_tower(g, cx, cz, 3, wtop + 4, GL, floors=(wtop - 1,), spiral=False, cone=True, t=1,
                    doors=((1 if cx < c else -1, 0, wtop), (0, 1 if cz < c else -1, wtop)))
    # stairs up to the wall walk, along the inner face of the south wall, landing onto the walk
    sz = o - 4
    for zz in (sz, sz - 1, sz - 2):
        for k in range(wtop - GL):
            g.stair(10 + k, GL + k, zz, STONE_STAIR, "east")
            g.fill(10 + k, GL, zz, 10 + k, GL + k - 1, zz, STONE)
            g.fill(10 + k, GL + k + 1, zz, 10 + k, GL + k + 3, zz, AIR)
        g.fill(10 + wtop - GL, GL, zz, 11 + wtop - GL, wtop - 1, zz, STONE)
    g.fill(10 + wtop - GL, wtop, sz - 2, 11 + wtop - GL, wtop + 1, sz + 1, AIR)
    # gatehouse
    gx0, gx1 = c - 5, c + 4
    g.fill(gx0, 0, o - 5, gx1, wtop + 5, o + 2, STONE)
    pointed_arch(g, c - 2, c + 1, GL, 6, o - 5, o + 2)
    portcullis_plane(g, c - 2, c + 1, GL, 6, o + 1)
    g.fill(c - 2, GL - 1, o - 5, c + 1, GL - 1, W - 1, ROAD)
    parapet_rect(g, gx0 - 1, o - 6, gx1 + 1, o + 3, wtop + 6)
    g.fill(gx0, wtop + 6, o - 5, gx1, wtop + 9, o + 2, AIR)
    for x in (gx0 + 1, gx1 - 1):
        g.mark("crossbowman", x, wtop + 6, o + 1)
    # a stair cut up through the gatehouse from the west wall-walk to its roof
    for zz in (o - 1, o - 2, o - 3):
        for k in range(6):
            g.stair(gx0 + k, wtop + k, zz, STONE_STAIR, "east")
            g.fill(gx0 + k, wtop + k + 1, zz, gx0 + k, wtop + k + 3, zz, AIR)
        g.fill(gx0 + 6, wtop + 6, zz, gx0 + 6, wtop + 8, zz, AIR)
    g.mark("gate", c - 1, GL, W - 1)
    # tower house: twenty-eight by twenty-two, five floors
    hx0, hz0, hx1, hz1 = c - 18, 10, c + 9, 31
    top = GL + 36
    floors = [GL + 8, GL + 15, GL + 22, GL + 29]
    g.fill(hx0, 0, hz0, hx1, top - 1, hz1, STONE)
    g.fill(hx0 + 3, GL, hz0 + 3, hx1 - 3, top - 2, hz1 - 3, AIR)
    g.fill(hx0 + 3, GL - 1, hz0 + 3, hx1 - 3, GL - 1, hz1 - 3, FLOOR_STONE)
    for fy in floors:
        g.fill(hx0 + 3, fy, hz0 + 3, hx1 - 3, fy, hz1 - 3, PLANK)
        g.lantern(c - 4, fy - 1, 18); g.lantern(c + 4, fy - 1, 24)
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
    g.fill(c - 2, GL, hz1 - 2, c + 1, GL + 3, hz1, AIR)
    # the stair hall in the north-west corner: two flights and a landing per storey, up to the roof
    dogleg_stair(g, hx0 + 3, hz0 + 3, GL, top - 1, STONE_STAIR, STONE, PLANK, levels=tuple(f + 1 for f in floors), back="north")
    g.wall_banner(hx0 - 2, top - 6, 20, "west"); g.wall_banner(hx1 + 2, top - 6, 20, "east")
    g.wall_banner(c - 1, top - 6, hz1 + 2, "south")
    hall_furnish(g, hx0 + 11, hx1 - 5, hz0 + 3, hz1 - 3, GL)
    for z in range(hz0 + 3, hz1 - 3, 3):
        g.chest(hx1 - 3, floors[0] + 1, z, "west", LOOT + "holdfast_treasury")
    for z in range(hz0 + 3, hz1 - 3, 3):
        g.bed(hx1 - 5, floors[1] + 1, z, "east", "gray"); g.bed(hx1 - 5, floors[2] + 1, z, "east", "red")
    g.mark("banner_stand", hx1 - 4, top, hz1 - 4)
    for (x, z) in ((c - 2, hz0 + 2), (hx1 - 4, hz1 - 2), (hx0 + 12, hz1 - 2)):
        g.mark("archer", x, top, z)
    for (x, z) in ((c, hz1 - 7), (c - 10, hz1 - 7)):
        g.mark("knight", x, GL, z)
    g.mark("captain", c - 1, GL, hz1 + 3); g.mark("rally", c - 1, GL, hz1 + 17)
    # outbuildings: barracks and a lodging on the west, stable and smithy on the east, a well on the way to the gate
    building(g, 9, 40, 18, 56, GL, 6, "z", door_cells=((18, 48),), chimney=(11, 42))
    for z in range(42, 56, 3):
        g.bed(10, GL, z, "west", "brown")
    g.barrel(17, GL, 55, LOOT + "castle_barracks", facing="west")
    building(g, 9, 10, 16, 22, GL, 5, "z", door_cells=((16, 16),), chimney=(11, 12))
    for z in range(12, 21, 3):
        g.bed(10, GL, z, "west", "brown")
    building(g, o - 14, 42, o - 5, 54, GL, 5, "z")
    g.fill(o - 14, GL, 44, o - 14, GL + 2, 52, AIR)
    g.set(o - 6, GL, 46, "minecraft:hay_block[axis=y]"); g.set(o - 6, GL, 51, "minecraft:hay_block[axis=y]")
    building(g, o - 16, 10, o - 7, 20, GL, 5, "x", door_cells=((o - 16, 15),), chimney=(o - 9, 12))
    g.set(o - 12, GL, 12, "minecraft:anvil[facing=north]"); g.set(o - 12, GL, 18, "minecraft:blast_furnace[facing=north]")
    g.set(o - 9, GL, 12, "minecraft:grindstone[face=floor,facing=north]")
    g.fill(c + 2, GL - 3, 44, c + 4, GL - 1, 46, "minecraft:stone_bricks")
    g.set(c + 3, GL - 1, 45, "minecraft:water"); g.set(c + 3, GL - 2, 45, "minecraft:water")
    g.set(c + 3, GL, 45, "minecraft:oak_fence"); g.set(c + 3, GL + 1, 45, "minecraft:oak_fence")
    g.set(c + 3, GL + 2, 45, "minecraft:dark_oak_slab[type=bottom]")
    for (x, z) in ((c - 12, 44), (c + 9, 44), (c - 6, 52), (c + 8, 56), (12, 34), (o - 11, 34), (12, 26), (o - 11, 26), (c - 1, 58),
                   (c - 14, 60), (c + 12, 60)):
        g.mark("knight", x, GL, z)
    lamp_post(g, c - 8, 50, GL); lamp_post(g, c + 7, 50, GL)
    lamp_post(g, c - 8, 36, GL); lamp_post(g, c + 7, 36, GL)
    return g, {"name": "holdfast_b", "tier": 2, "GL": GL}


def palisade(g, W, GL, gate=None):
    """A ring of stakes two in from the plan's edge with a fighting platform three planks wide behind it. Every
    other pair of stakes is cut down to a half-height sill, so a man on the platform can step up into the gap,
    lean out and see the ditch at its foot; the stakes between stand two higher, pointed with fence."""
    xs, zs = g.xz()
    log = "minecraft:spruce_log[axis=y]"
    pal = rect_ring(g, 2, 2, W - 3, W - 3, 1)
    merlon = pal & (np.mod(xs + zs, 4) < 2)
    g.fill_mask(pal, GL - 1, GL + 2, log)
    g.fill_mask(merlon, GL + 3, GL + 4, log)
    g.fill_mask(merlon & (np.mod(xs + zs, 2) == 0), GL + 5, GL + 5, "minecraft:spruce_fence")
    g.fill_mask(pal & ~merlon, GL + 3, GL + 3, "minecraft:spruce_slab[type=bottom]")
    plat = rect_ring(g, 3, 3, W - 4, W - 4, 3)
    g.fill_mask(plat, GL + 2, GL + 2, "minecraft:spruce_planks")
    g.fill_mask(plat & (np.mod(xs, 4) == 0) & (np.mod(zs, 4) == 0), GL, GL + 1, "minecraft:spruce_fence")
    g.fill_mask(rect_ring(g, 6, 6, W - 7, W - 7, 1), GL + 3, GL + 3, "minecraft:spruce_fence")
    # a bowman's place at every gap: one man to a gap, on its sill
    taken = set()
    for x, z in zip(*np.nonzero(pal & ~merlon)):
        x, z = int(x), int(z)
        if any((x + dx, z + dz) in taken for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
            continue
        if gate is not None and z >= W - 4 and gate[0] - 3 <= x <= gate[1] + 3:
            continue                     # the gate's own posts, either side of the passage, are cut away
        taken.add((x, z))
        g.mark("archer", x, GL + 3, z)


def watchtower_b(seed=9):
    """Tier 1 square peel tower inside a timber palisade, with a fighting platform behind the stakes."""
    W, H, GL = 48, 46, 4
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    c = W // 2
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, W - 1, Mix(("minecraft:grass_block", 50), ("minecraft:coarse_dirt", 25),
                                                     ("minecraft:dirt_path", 15), ("minecraft:gravel", 10)))
    g.fill(0, GL, 0, W - 1, GL + 8, W - 1, AIR)
    palisade(g, W, GL, gate=(W // 2 - 2, W // 2 + 1))
    # gate in the south stakes
    gx0, gx1 = c - 2, c + 1
    g.fill(gx0, GL - 1, W - 3, gx1, GL + 5, W - 3, AIR)
    g.fill(gx0, GL, W - 6, gx1, GL + 4, W - 4, AIR)             # the platform breaks for the gate passage
    g.fill(gx0 - 1, GL, W - 6, gx1 + 1, GL + 3, W - 4, AIR)
    g.fill(gx0, GL - 1, W - 8, gx1, GL - 1, W - 1, "minecraft:dirt_path")
    g.fill(gx0, GL - 1, W - 3, gx1, GL - 1, W - 3, "minecraft:dirt_path")
    for x in (gx0 - 1, gx1 + 1):
        g.fill(x, GL - 1, W - 4, x, GL + 6, W - 3, "minecraft:spruce_log[axis=y]")
    g.fill(gx0 - 1, GL + 7, W - 4, gx1 + 1, GL + 7, W - 3, "minecraft:spruce_log[axis=x]")
    g.fill(gx0, GL + 5, W - 4, gx1, GL + 6, W - 4, "minecraft:spruce_planks")
    portcullis_plane(g, gx0, gx1, GL, 5, W - 3)
    g.mark("gate", c, GL, W - 1)
    # steps up to the platform either side of the gate passage, straight flights out of the yard
    for sx in (gx0 - 6, gx0 - 5, gx0 - 4, gx1 + 4, gx1 + 5, gx1 + 6):
        for k in range(3):
            z = W - 9 + k
            g.stair(sx, GL + k, z, "minecraft:spruce_stairs", "south")
            g.fill(sx, GL - 1, z, sx, GL + k - 1, z, "minecraft:spruce_log[axis=y]") if k > 0 else None
            g.fill(sx, GL + k + 1, z, sx, GL + 5, z, AIR)
        g.set(sx, GL + 3, W - 7, AIR)
    # the peel tower: eighteen across, four floors, a stair two men wide
    tx0, tz0, tx1, tz1 = c - 10, 8, c + 7, 25
    top = GL + 28
    square_tower(g, tx0, tz0, tx1, tz1, top, GL, floors=(GL + 7, GL + 14, GL + 21), stair="north",
                 banner_sides=("south", "east", "west"))
    for (cx, cz) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx, cz, top + 1)
    g.fill(tx1 - 6, GL, tz1 - 3, tx1 - 5, GL + 2, tz1 + 1, AIR)        # the door, right through the wall and the plinth
    g.mark("banner_stand", tx0 + 5, top, tz1 - 5)
    g.mark("lord", tx1 - 5, GL + 15, tz0 + 11)
    g.stair(tx1 - 4, GL + 15, tz0 + 11, "minecraft:dark_oak_stairs", "west")
    g.mark("war_table", tx0 + 5, GL + 15, tz0 + 11)
    g.chest(tx0 + 3, GL + 15, tz0 + 5, "east", LOOT + "watchtower_treasury")
    g.chest(tx0 + 3, GL + 15, tz0 + 8, "east", LOOT + "watchtower_treasury")
    for (x, z) in ((tx0 + 4, tz0 + 4), (tx1 - 4, tz1 - 4), (tx0 + 4, tz1 - 4), (tx1 - 8, tz1 - 4)):
        g.mark("archer", x, top, z)
    # the yard: a barracks, a stable, a well, a smithy's lean-to, a campfire
    building(g, 7, 29, 16, 40, GL, 5, "x", door_cells=((16, 34),), chimney=(8, 30))
    for z in range(31, 40, 3):
        g.bed(8, GL, z, "west", "brown")
    g.barrel(15, GL, 39, LOOT + "castle_barracks", facing="west")
    building(g, W - 16, 29, W - 7, 38, GL, 5, "z", door_cells=((W - 16, 33),))
    g.fill(W - 16, GL, 31, W - 16, GL + 2, 35, AIR)
    for z in (31, 35):
        g.set(W - 8, GL, z, "minecraft:hay_block[axis=y]")
    g.fill(c + 11, GL - 3, 12, c + 13, GL - 1, 14, "minecraft:stone_bricks")
    g.set(c + 12, GL - 1, 13, "minecraft:water"); g.set(c + 12, GL - 2, 13, "minecraft:water")
    g.set(c + 12, GL, 13, "minecraft:oak_fence"); g.set(c + 12, GL + 1, 13, "minecraft:oak_fence")
    g.set(c + 12, GL + 2, 13, "minecraft:dark_oak_slab[type=bottom]")
    g.set(c - 14, GL, 15, "minecraft:campfire[lit=true,signal_fire=false]")
    for (x, z) in ((c - 15, 15), (c - 13, 15), (c - 14, 16)):
        g.stair(x, GL, z, "minecraft:spruce_stairs", "north")
    g.fill(c + 10, GL, 18, c + 12, GL + 1, 20, "minecraft:hay_block[axis=y]")
    g.set(c + 14, GL, 20, "minecraft:anvil[facing=north]"); g.set(c + 14, GL, 22, "minecraft:blast_furnace[facing=west]")
    for (x, z) in ((9, 12), (c + 15, 9), (c - 5, 30), (c + 6, 30), (8, 24), (W - 9, 26), (c, 36), (c - 8, 40), (c + 8, 40)):
        g.mark("knight", x, GL, z)
    g.mark("captain", c, GL, 31); g.mark("rally", c, GL, 34)
    lamp_post(g, c - 4, W - 8, GL); lamp_post(g, c + 3, W - 8, GL)
    lamp_post(g, c - 12, 26, GL); lamp_post(g, c + 10, 26, GL)
    return g, {"name": "watchtower_b", "tier": 1, "GL": GL}


def holdfast_c(seed=17):
    """Tier 2 ringwork: a round curtain with four towers, a round keep in the middle, hall and lodgings round the yard."""
    W, H, GL = 72, 56, 5
    g = Grid(W, H, W, seed)
    c = W // 2
    xs, zs = g.xz()
    rr = np.sqrt((xs - c) ** 2 + (zs - c) ** 2)
    Ro, thick, walk = 33, 4, GL + 9
    inside = rr <= Ro + 1.5
    g.fill_mask(inside, 0, GL - 2, FOUND)
    g.fill_mask(inside, GL - 1, GL - 1, PAVE)
    g.fill_mask(inside, GL, GL + 10, AIR)
    g.fill_mask(~inside, 0, GL - 2, FOUND)
    g.fill_mask(~inside, GL - 1, GL - 1, "minecraft:grass_block")
    g.fill_mask(~inside, GL, GL + 6, AIR)
    # the ring wall and its walk
    d, along, out = ring_grids(g, c, c, Ro)
    curtain(g, None, None, thick, walk, GL, gallery=False, grids=(d, along, out), banners_every=12)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=2)
    ttop = walk + 7
    for k in range(4):
        ang = np.radians(45 + 90 * k)
        cx, cz = int(round(c + (Ro - 1) * np.cos(ang))), int(round(c + (Ro - 1) * np.sin(ang)))
        tx, tz = -np.sin(ang), np.cos(ang)
        round_tower(g, cx, cz, 6, ttop, GL, floors=(walk - 1,), spiral=False, cone=(k % 2 == 0),
                    doors=((tx, tz, walk), (-tx, -tz, walk)), banner_dirs=((round(np.cos(ang), 2), round(np.sin(ang), 2)),))
        roof_ladder(g, tower_room(g, cx, cz, 6), walk, ttop, (cx + 2, cz, "west"), (cx - 2, cz, "east"))
        for j in range(4):
            a2 = j * np.pi / 2
            g.mark("archer", int(round(cx + 3 * np.cos(a2))), ttop, int(round(cz + 3 * np.sin(a2))))
    # two radial stairs, east and west, from the yard up onto the walk
    for sgn in (1, -1):
        for k in range(walk - GL):
            x = c + sgn * (18 + k)
            g.stair(x, GL + k, c, STONE_STAIR, "east" if sgn > 0 else "west")
            g.stair(x, GL + k, c + 1, STONE_STAIR, "east" if sgn > 0 else "west")
            for zz in (c, c + 1):
                g.fill(x, GL, zz, x, GL + k - 1, zz, "minecraft:stone_brick_slab[type=double]") if k > 0 else None
                g.fill(x, GL + k + 1, zz, x, GL + k + 3, zz, AIR)
        x = c + sgn * (18 + walk - GL)
        g.fill(x, GL, c, x + sgn * 2, walk - 1, c + 1, STONE)
        g.fill(x, walk, c, x + sgn * 2, walk + 2, c + 1, AIR)
    # gate: a gap in the ring on the south between two drum towers, a portcullis across it
    for tx in (c - 7, c + 6):
        round_tower(g, tx, c + Ro - 2, 4, walk + 5, GL, floors=(walk - 1,), spiral=False, cone=True, t=2,
                    doors=((1, 0, walk), (-1, 0, walk)))
    g.fill(c - 2, GL, c + Ro - thick - 1, c + 1, GL + 6, c + Ro + 2, AIR)
    g.fill(c - 2, GL - 1, c + Ro - thick - 1, c + 1, GL - 1, W - 1, ROAD)
    g.fill(c - 2, GL + 7, c + Ro - thick, c + 1, walk - 1, c + Ro, STONE)        # the gate's lintel and the walk over it
    g.fill(c - 2, walk, c + Ro - thick, c + 1, walk + 2, c + Ro, AIR)
    portcullis_plane(g, c - 2, c + 1, GL, 6, c + Ro - 1)
    g.mark("gate", c - 1, GL, W - 1)
    g.mark("knight", c - 4, GL, c + Ro - 7); g.mark("knight", c + 3, GL, c + Ro - 7)
    g.mark("captain", c - 1, GL, c + Ro - 9); g.mark("rally", c - 1, GL, c + Ro - 11)
    # the keep: a round tower twenty-three across, hall below, lord's floor above, treasury under the roof
    kx, kz, kr = c, c - 6, 11
    ktop = GL + 30
    kfloors = (GL + 8, GL + 16, GL + 23)
    round_tower(g, kx, kz, kr, ktop, GL, floors=kfloors, spiral=True, doors=((0, 1, GL),),
                banner_dirs=((0, 1), (1, 0), (-1, 0)))
    for fy in kfloors:
        for ang in (0.0, np.pi, np.pi / 2):
            g.fill(int(round(kx + (kr - 1) * np.cos(ang))), fy + 2, int(round(kz + (kr - 1) * np.sin(ang))),
                   int(round(kx + (kr - 1) * np.cos(ang))), fy + 3, int(round(kz + (kr - 1) * np.sin(ang))), GLASS)
    g.fill(kx - 4, GL, kz - 7, kx + 4, GL, kz - 6, "minecraft:polished_deepslate")
    g.stair(kx - 1, GL + 1, kz - 7, "minecraft:dark_oak_stairs", "south"); g.stair(kx, GL + 1, kz - 7, "minecraft:dark_oak_stairs", "south")
    g.wall_banner(kx - 2, GL + 5, kz - 8, "south"); g.wall_banner(kx + 2, GL + 5, kz - 8, "south")
    g.fill(kx - 1, GL, kz - 4, kx + 1, GL, kz + 6, "minecraft:red_carpet")
    g.mark("lord", kx, GL, kz - 5)
    g.mark("war_table", kx - 5, GL, kz - 5)
    for (x, z) in ((kx - 6, kz + 2), (kx + 6, kz + 2)):
        g.mark("knight", x, GL, z)
    for ang in (np.pi / 4, 3 * np.pi / 4):
        g.chest(int(round(kx + 6 * np.cos(ang))), kfloors[1] + 1, int(round(kz + 6 * np.sin(ang))), "south", LOOT + "holdfast_treasury")
    for ang in (5 * np.pi / 4, 7 * np.pi / 4):
        g.bed(int(round(kx + 6 * np.cos(ang))), kfloors[0] + 1, int(round(kz + 6 * np.sin(ang))), "north", "gray")
    g.bed(kx + 5, kfloors[2] + 1, kz, "east", "red")
    g.mark("banner_stand", kx + 6, ktop, kz)
    for ang in (0.0, np.pi / 2, np.pi, 3 * np.pi / 2):
        g.mark("archer", int(round(kx + 8 * np.cos(ang))), ktop, int(round(kz + 8 * np.sin(ang))))
    # round the yard: barracks and a lodging west, stable and smithy east, a well on the way to the gate
    building(g, c - 27, c + 4, c - 17, c + 18, GL, 6, "z", door_cells=((c - 17, c + 11),), chimney=(c - 25, c + 6))
    for z in range(c + 6, c + 17, 3):
        g.bed(c - 26, GL, z, "west", "brown")
    g.barrel(c - 18, GL, c + 17, LOOT + "castle_barracks", facing="west")
    building(g, c - 24, c - 18, c - 14, c - 8, GL, 5, "z", door_cells=((c - 14, c - 13),), chimney=(c - 22, c - 16))
    for z in range(c - 16, c - 9, 3):
        g.bed(c - 23, GL, z, "west", "brown")
    building(g, c + 17, c + 4, c + 27, c + 16, GL, 5, "z")
    g.fill(c + 17, GL, c + 6, c + 17, GL + 2, c + 14, AIR)
    g.set(c + 26, GL, c + 8, "minecraft:hay_block[axis=y]"); g.set(c + 26, GL, c + 13, "minecraft:hay_block[axis=y]")
    building(g, c + 14, c - 18, c + 24, c - 8, GL, 5, "z", door_cells=((c + 14, c - 13),), chimney=(c + 22, c - 16))
    g.set(c + 16, GL, c - 16, "minecraft:anvil[facing=north]"); g.set(c + 16, GL, c - 10, "minecraft:blast_furnace[facing=north]")
    g.set(c + 20, GL, c - 16, "minecraft:grindstone[face=floor,facing=north]")
    g.fill(c - 6, GL - 3, c + 16, c - 4, GL - 1, c + 18, "minecraft:stone_bricks")
    g.set(c - 5, GL - 1, c + 17, "minecraft:water"); g.set(c - 5, GL - 2, c + 17, "minecraft:water")
    g.set(c - 5, GL, c + 17, "minecraft:oak_fence"); g.set(c - 5, GL + 1, c + 17, "minecraft:oak_fence")
    g.set(c - 5, GL + 2, c + 17, "minecraft:dark_oak_slab[type=bottom]")
    for (x, z) in ((c - 12, c + 10), (c + 11, c + 10), (c - 8, c + 20), (c + 7, c + 20), (c - 14, c - 4), (c + 13, c - 4),
                   (c - 1, c + 14), (c - 20, c + 2), (c + 19, c + 2)):
        g.mark("knight", x, GL, z)
    lamp_post(g, c - 4, c + 22, GL); lamp_post(g, c + 3, c + 22, GL)
    lamp_post(g, c - 10, c + 6, GL); lamp_post(g, c + 9, c + 6, GL)
    return g, {"name": "holdfast_c", "tier": 2, "GL": GL}


def watchtower_c(seed=19):
    """Tier 1 motte and bailey: a square tower on an earth mound, a stair cut up its face, a palisaded bailey below."""
    W, H, GL = 48, 46, 4
    g = Grid(W, H, W, seed)
    xs, zs = g.xz()
    c = W // 2
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, W - 1, Mix(("minecraft:grass_block", 50), ("minecraft:coarse_dirt", 25),
                                                     ("minecraft:dirt_path", 15), ("minecraft:gravel", 10)))
    g.fill(0, GL, 0, W - 1, GL + 8, W - 1, AIR)
    # the palisade and its platform, as at the timber tower
    palisade(g, W, GL, gate=(W // 2 - 2, W // 2 + 1))
    gx0, gx1 = c - 2, c + 1
    g.fill(gx0, GL - 1, W - 3, gx1, GL + 5, W - 3, AIR)
    g.fill(gx0 - 1, GL, W - 6, gx1 + 1, GL + 4, W - 4, AIR)
    g.fill(gx0, GL - 1, W - 8, gx1, GL - 1, W - 1, "minecraft:dirt_path")
    for x in (gx0 - 1, gx1 + 1):
        g.fill(x, GL - 1, W - 4, x, GL + 6, W - 3, "minecraft:spruce_log[axis=y]")
    g.fill(gx0 - 1, GL + 7, W - 4, gx1 + 1, GL + 7, W - 3, "minecraft:spruce_log[axis=x]")
    g.fill(gx0, GL + 5, W - 4, gx1, GL + 6, W - 4, "minecraft:spruce_planks")
    portcullis_plane(g, gx0, gx1, GL, 5, W - 3)
    g.mark("gate", c, GL, W - 1)
    for sx in (gx0 - 6, gx0 - 5, gx0 - 4, gx1 + 4, gx1 + 5, gx1 + 6):
        for k in range(3):
            z = W - 9 + k
            g.stair(sx, GL + k, z, "minecraft:spruce_stairs", "south")
            g.fill(sx, GL - 1, z, sx, GL + k - 1, z, "minecraft:spruce_log[axis=y]") if k > 0 else None
            g.fill(sx, GL + k + 1, z, sx, GL + 5, z, AIR)
        g.set(sx, GL + 3, W - 7, AIR)
    # the motte: an earth mound, radius twenty at the foot and twelve at the top, six high, turfed
    mx, mz = c, c - 8
    MT = GL + 6
    def mr(k):
        return 20 - (k * 8) // 6
    for k in range(7):
        g.fill_mask(disk(g, mx, mz, mr(k)), GL - 1 + k, GL - 1 + k, "minecraft:dirt")
    for k in range(7):
        r2 = mr(k + 1) if k < 6 else 0
        g.fill_mask(disk(g, mx, mz, mr(k)) & ~disk(g, mx, mz, r2), GL - 1 + k, GL - 1 + k,
                    Mix(("minecraft:grass_block", 75), ("minecraft:moss_block", 15), ("minecraft:coarse_dirt", 10)))
    g.fill_mask(disk(g, mx, mz, 12), MT - 1, MT - 1, Mix(("minecraft:grass_block", 60), ("minecraft:dirt_path", 40)))
    # a stair cut into the south face of the mound, two wide, from the bailey to the top
    for k in range(6):
        z = mz + 20 - k
        for x in (mx - 1, mx):
            g.stair(x, GL + k, z, "minecraft:spruce_stairs", "north")
            g.fill(x, GL - 1, z, x, GL + k - 1, z, "minecraft:dirt") if k > 0 else None
            g.fill(x, GL + k + 1, z, x, GL + k + 3, z, AIR)
    g.fill(mx - 1, MT - 1, mz + 9, mx, MT - 1, mz + 14, "minecraft:dirt_path")
    g.fill(mx - 1, MT, mz + 9, mx, MT + 2, mz + 14, AIR)
    # the tower on top: sixteen square, a stair hall inside and the rooms in front of it, four floors
    tx0, tz0, tx1, tz1 = mx - 8, mz - 8, mx + 7, mz + 7
    top = MT + 24
    square_tower(g, tx0, tz0, tx1, tz1, top, MT, floors=(MT + 7, MT + 14), stair="north",
                 banner_sides=("south", "east", "west"))
    g.fill(tx0 - 1, 0, tz0 - 1, tx1 + 1, MT - 2, tz1 + 1, STONE)       # footing down into the mound
    for (cx, cz) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx, cz, top + 1)
    g.fill(mx - 1, MT, tz1 - 3, mx, MT + 2, tz1 + 1, AIR)                  # the door, through the wall and the plinth
    g.fill(mx - 1, MT - 1, tz1 - 3, mx, MT - 1, tz1 + 1, FLOOR_STONE)
    # the rooms are the two rows in front of the stair hall
    g.mark("banner_stand", tx0 + 4, top, tz1 - 4)
    g.mark("lord", tx1 - 5, MT + 15, tz1 - 4)
    g.stair(tx1 - 5, MT + 15, tz1 - 3, "minecraft:dark_oak_stairs", "north")
    g.mark("war_table", tx0 + 4, MT + 15, tz1 - 4)
    g.chest(tx0 + 3, MT + 15, tz1 - 3, "east", LOOT + "watchtower_treasury")
    g.chest(tx1 - 3, MT + 15, tz1 - 3, "west", LOOT + "watchtower_treasury")
    g.bed(tx0 + 3, MT + 8, tz1 - 4, "east", "red")
    for (x, z) in ((tx0 + 3, tz1 - 3), (tx1 - 3, tz1 - 3), (tx0 + 3, tz0 + 3)):
        g.mark("archer", x, top, z)
    g.mark("captain", mx, MT, mz + 10)
    # the bailey, round the foot of the mound: barracks, stable, well, smithy, a campfire
    building(g, 6, 36, 16, 41, GL, 5, "x", door_cells=((16, 39),), chimney=(7, 37))
    for x in range(8, 15, 3):
        g.bed(x, GL, 37, "north", "brown")
    g.barrel(15, GL, 40, LOOT + "castle_barracks", facing="west")
    building(g, W - 16, 36, W - 7, 41, GL, 5, "x", door_cells=((W - 16, 39),))
    g.fill(W - 16, GL, 38, W - 16, GL + 2, 40, AIR)
    for x in (W - 14, W - 9):
        g.set(x, GL, 40, "minecraft:hay_block[axis=y]")
    g.fill(41, GL - 3, 33, 43, GL - 1, 35, "minecraft:stone_bricks")
    g.set(42, GL - 1, 34, "minecraft:water"); g.set(42, GL - 2, 34, "minecraft:water")
    g.set(42, GL, 34, "minecraft:oak_fence"); g.set(42, GL + 1, 34, "minecraft:oak_fence")
    g.set(42, GL + 2, 34, "minecraft:dark_oak_slab[type=bottom]")
    g.set(6, GL, 31, "minecraft:campfire[lit=true,signal_fire=false]")
    for (x, z) in ((5, 31), (7, 31), (6, 32)):
        g.stair(x, GL, z, "minecraft:spruce_stairs", "north")
    g.set(43, GL, 27, "minecraft:anvil[facing=north]"); g.set(43, GL, 29, "minecraft:blast_furnace[facing=west]")
    for (x, z) in ((5, 7), (43, 6), (19, 40), (30, 40), (6, 26), (42, 31), (24, 38), (20, 41), (28, 41)):
        g.mark("knight", x, GL, z)
    g.mark("rally", c, GL, 39)
    lamp_post(g, c - 4, W - 8, GL); lamp_post(g, c + 3, W - 8, GL)
    lamp_post(g, 10, 30, GL); lamp_post(g, 36, 32, GL)
    return g, {"name": "watchtower_c", "tier": 1, "GL": GL}
