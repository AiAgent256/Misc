"""The Great City: a 256x256 walled capital. Harbour and river gate on the south, lower city of
streets and districts, an upper city of noble houses behind a second wall, and the Red Keep on
its own terrace at the north. One per region; the seat of the house that rules it."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, rect_ring, spiral_stair, gable_roof, cone_roof, angle_index
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR,
                   DARK_STAIR, SLAB_CAP, RAIL, PLANK, DARK_PLANK, PORTCULLIS, GLASS,
                   curtain, side_grids, posts_on_walk, round_tower, square_tower, straight_wall,
                   pointed_arch, building, lamp_post, tree_weirwood, stairs_by_facing)
from castle import portcullis_plane, bartizan
from castle2 import portcullis_plane_x, parapet_rect, hall_furnish
from town import dense_block, terrace, lane, arcade, stall_cluster, townhouse, well as town_well

LOOT = "stormhold:chests/"
AIR = "minecraft:air"
SQUARE = Mix(("minecraft:stone_bricks", 45), ("minecraft:polished_andesite", 30), ("minecraft:cobblestone", 15),
             ("minecraft:cracked_stone_bricks", 10))
ALLEY = Mix(("minecraft:cobblestone", 45), ("minecraft:gravel", 20), ("minecraft:andesite", 20),
            ("minecraft:mossy_cobblestone", 10), ("minecraft:dirt_path", 5))
NOBLE = Mix(("minecraft:stone_bricks", 55), ("minecraft:polished_andesite", 25), ("minecraft:andesite", 20))
PLASTER = Mix(("minecraft:white_terracotta", 40), ("minecraft:light_gray_terracotta", 20),
              ("minecraft:white_concrete_powder", 15), ("minecraft:mud_bricks", 15), ("minecraft:stone_bricks", 10))
ROOFS = [None, None, "minecraft:spruce_stairs", "minecraft:dark_oak_stairs", "minecraft:brick_stairs",
         "minecraft:mud_brick_stairs", "minecraft:deepslate_brick_stairs"]
BED_COLORS = ["red", "brown", "green", "blue", "gray", "white", "purple", "light_gray"]


# ---------------------------------------------------------------- pieces

def carve(g, x0, y0, z0, x1, y1, z1, block=AIR, floor=None):
    """Fill a box, leaving stair shafts (g.protected_xz) alone so a corridor never cuts a spiral."""
    p = getattr(g, "protected_xz", set())
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            if (x, z) in p:
                continue
            g.fill(x, y0, z, x, y1, z, block)
            if floor is not None:
                g.set(x, y0 - 1, z, floor)


def house(g, x0, z0, x1, z1, GL, rng, door, wall=None, h=None, stalls=False, sign=None):
    """A town house whose door is on the street side: door in ('north','south','east','west')."""
    wall_h = h or int(rng.integers(6, 10))
    axis = "x" if (x1 - x0) >= (z1 - z0) else "z"
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    dcell = {"north": (cx, z0), "south": (cx, z1), "west": (x0, cz), "east": (x1, cz)}[door]
    timber = wall is None and rng.random() < 0.65
    wb = wall if wall is not None else (PLASTER if rng.random() < 0.5 else STONE)
    chim = (x0 + 1, z0 + 1) if rng.random() < 0.6 else None
    roof = rng.choice(ROOFS) if wall is None else None
    building(g, x0, z0, x1, z1, GL, wall_h, axis, door_cells=(dcell,), upper_floor=GL + 4 if wall_h >= 7 else None,
             chimney=chim, timber=timber, wall_block=wb, roof=roof)
    # a bed, a barrel, a table
    bx, bz = (x1 - 2, z1 - 2) if door in ("north", "west") else (x0 + 1, z0 + 1)
    g.bed(bx, GL, bz, "east" if bx == x0 + 1 else "west", rng.choice(BED_COLORS))
    g.barrel(x0 + 1 if door != "west" else x1 - 1, GL, z1 - 1 if door != "south" else z0 + 1, LOOT + "town_house")
    if wall_h >= 7:
        g.bed(bx, GL + 5, bz, "east" if bx == x0 + 1 else "west", rng.choice(BED_COLORS))
    if stalls:
        dx, dz = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}[door]
        ax_, az_ = dcell[0] + dx * 2, dcell[1] + dz * 2
        g.set(ax_ - (1 if dz else 0), GL, az_ - (1 if dx else 0), "minecraft:barrel[facing=up]")
        g.set(ax_ + (1 if dz else 0), GL, az_ + (1 if dx else 0), "minecraft:barrel[facing=up]")
    if sign:
        dx, dz = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}[door]
        g.set(dcell[0] + dx, GL + 3, dcell[1] + dz, f"minecraft:lantern[hanging=true]")
    return dcell


def row_x(g, xa, xb, z0, depth, GL, rng, door, lot=(8, 12), gap=2, **kw):
    """Houses side by side along x between xa..xb, each `depth` deep from z0 (z0..z0+depth-1)."""
    x = xa
    doors = []
    while x + lot[0] <= xb + 1:
        w = int(rng.integers(lot[0], lot[1] + 1))
        x1 = min(x + w - 1, xb)
        if x1 - x >= lot[0] - 2:
            doors.append(house(g, x, z0, x1, z0 + depth - 1, GL, rng, door, **kw))
        x = x1 + 1 + gap
    return doors


def row_z(g, za, zb, x0, depth, GL, rng, door, lot=(8, 12), gap=2, **kw):
    z = za
    doors = []
    while z + lot[0] <= zb + 1:
        w = int(rng.integers(lot[0], lot[1] + 1))
        z1 = min(z + w - 1, zb)
        if z1 - z >= lot[0] - 2:
            doors.append(house(g, x0, z, x0 + depth - 1, z1, GL, rng, door, **kw))
        z = z1 + 1 + gap
    return doors


def block_houses(g, x0, z0, x1, z1, GL, rng, depth=9, sides=("north", "south", "west", "east"), **kw):
    """Fill a city block: a row of houses on each requested side facing out, a yard in the middle."""
    doors = []
    inner = (x0 + depth + 2, z0 + depth + 2, x1 - depth - 2, z1 - depth - 2)
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, ALLEY)
    if "north" in sides:
        doors += row_x(g, x0, x1, z0, depth, GL, rng, "north", **kw)
    if "south" in sides:
        doors += row_x(g, x0, x1, z1 - depth + 1, depth, GL, rng, "south", **kw)
    zc0 = z0 + (depth + 2 if "north" in sides else 0)
    zc1 = z1 - (depth + 2 if "south" in sides else 0)
    if "west" in sides and zc1 - zc0 >= 8:
        doors += row_z(g, zc0, zc1, x0, depth, GL, rng, "west", **kw)
    if "east" in sides and zc1 - zc0 >= 8:
        doors += row_z(g, zc0, zc1, x1 - depth + 1, depth, GL, rng, "east", **kw)
    # yard: a tree or a well
    yx, yz = (x0 + x1) // 2, (z0 + z1) // 2
    if inner[2] - inner[0] > 6 and inner[3] - inner[1] > 6:
        if rng.random() < 0.5:
            g.fill(yx, GL, yz, yx, GL + 3, yz, "minecraft:oak_log[axis=y]")
            g.fill_mask(disk(g, yx + 0.5, yz + 0.5, 2.6), GL + 3, GL + 4, "minecraft:oak_leaves[persistent=true]")
            g.fill_mask(disk(g, yx + 0.5, yz + 0.5, 1.6), GL + 5, GL + 5, "minecraft:oak_leaves[persistent=true]")
        else:
            g.fill(yx - 1, GL - 3, yz - 1, yx + 1, GL - 1, yz + 1, "minecraft:stone_bricks")
            g.set(yx, GL - 1, yz, "minecraft:water"); g.set(yx, GL - 2, yz, "minecraft:water")
            g.set(yx, GL, yz, AIR)
            for (x, z) in ((yx - 1, yz - 1), (yx + 1, yz - 1), (yx - 1, yz + 1), (yx + 1, yz + 1)):
                g.fill(x, GL, z, x, GL + 2, z, "minecraft:dark_oak_fence")
            g.fill(yx - 1, GL + 3, yz - 1, yx + 1, GL + 3, yz + 1, "minecraft:dark_oak_slab[type=bottom]")
    return doors


def tree_oak(g, x, z, GL, rng):
    h = int(rng.integers(4, 6))
    g.fill(x, GL, z, x, GL + h - 1, z, "minecraft:oak_log[axis=y]")
    g.fill_mask(disk(g, x + 0.5, z + 0.5, 2.6), GL + h - 2, GL + h - 1, "minecraft:oak_leaves[persistent=true]")
    g.fill_mask(disk(g, x + 0.5, z + 0.5, 1.6), GL + h, GL + h, "minecraft:oak_leaves[persistent=true]")
    g.set(x, GL + h - 1, z, "minecraft:oak_log[axis=y]")


def retaining_wall(g, x0, z0, x1, z1, y_low, y_high, face):
    """Terrace edge: solid face between two ground levels along one side of a rect."""
    g.fill(x0, y_low - 1, z0, x1, y_high - 1, z1, STONE)
    if face in ("north", "south"):
        z = z0 if face == "north" else z1
        for x in range(x0, x1 + 1):
            g.set(x, y_high, z, "minecraft:stone_brick_wall")
            if x % 3 == 0:
                g.set(x, y_low + 2, z, DARK)
    else:
        x = x0 if face == "west" else x1
        for z in range(z0, z1 + 1):
            g.set(x, y_high, z, "minecraft:stone_brick_wall")
            if z % 3 == 0:
                g.set(x, y_low + 2, z, DARK)


def ramp_z(g, x0, x1, z_from, y_from, y_to, block=STONE_STAIR, fill=STONE, direction=-1):
    """Stairs climbing from (z_from, y_from) toward -z (direction -1) or +z, one step per block."""
    rise = y_to - y_from
    facing = "north" if direction < 0 else "south"
    for k in range(rise):
        z = z_from + direction * k
        g.fill(x0, 0, z, x1, y_from + k - 1, z, fill)
        for x in range(x0, x1 + 1):
            g.stair(x, y_from + k, z, block, facing)
        g.fill(x0, y_from + k + 1, z, x1, y_from + k + 6, z, AIR)
    z = z_from + direction * rise
    g.fill(x0, 0, z, x1, y_to - 1, z, fill)
    g.fill(x0, y_to - 1, z, x1, y_to - 1, z, ROAD)


def great_sept(g, cx, cz, r, GL):
    """The Great Sept: a seven-sided hall under a tall dome, seven slender towers around it, a stair to the south."""
    top = GL + 22
    full = disk(g, cx, cz, r)
    shell = ring(g, cx, cz, r, r - 2)
    WHITE = Mix(("minecraft:polished_diorite", 45), ("minecraft:stone_bricks", 25), ("minecraft:diorite", 15),
                ("minecraft:white_concrete", 15))
    g.fill_mask(disk(g, cx, cz, r + 6), 0, GL - 2, FOUND)
    g.fill_mask(disk(g, cx, cz, r + 6), GL - 1, GL - 1, Mix(("minecraft:polished_andesite", 50), ("minecraft:stone_bricks", 30),
                                                             ("minecraft:polished_diorite", 20)))
    g.fill_mask(disk(g, cx, cz, r + 6), GL, GL + 40, AIR)
    g.fill_mask(shell, GL, top - 1, WHITE)
    g.fill_mask(ring(g, cx, cz, r, r - 1), GL + 3, GL + 3, "minecraft:quartz_block")
    g.fill_mask(ring(g, cx, cz, r, r - 1), top - 3, top - 3, "minecraft:quartz_block")
    g.fill_mask(full & ~shell, GL - 1, GL - 1, Mix(("minecraft:polished_diorite", 50), ("minecraft:white_concrete", 30),
                                                    ("minecraft:quartz_block", 20)))
    ai = angle_index(g, cx, cz, r)
    cols = ["red", "orange", "yellow", "lime", "light_blue", "blue", "purple"]
    for k in range(7):
        a2 = k * 2 * np.pi / 7 + np.pi / 7
        for dy in range(4, top - 6):
            for off in (-0.6, 0, 0.6):
                x, z = int(round(cx + (r - 1) * np.cos(a2 + off * 0.06))), int(round(cz + (r - 1) * np.sin(a2 + off * 0.06)))
                g.set(x, GL + dy, z, f"minecraft:{cols[k]}_stained_glass_pane")
    # pilasters between the windows
    g.fill_mask(ring(g, cx, cz, r + 1, r) & (np.mod(ai, 9) == 0), GL, top - 2, "minecraft:quartz_pillar[axis=y]")
    # dome: a hemisphere of white stone with a lantern on top
    R = r - 2
    for k in range(R + 1):
        rr = np.sqrt(max(0.0, R * R - k * k))
        band = disk(g, cx, cz, rr + 0.8) & ~disk(g, cx, cz, max(0.3, rr - 1.4))
        g.fill_mask(band, top + k, top + k, Mix(("minecraft:polished_diorite", 45), ("minecraft:white_concrete", 30),
                                                 ("minecraft:quartz_block", 25)))
    g.fill_mask(disk(g, cx, cz, 2.5), top + R, top + R + 3, "minecraft:quartz_pillar[axis=y]")
    g.fill_mask(disk(g, cx, cz, 2.5) & ~disk(g, cx, cz, 1.5), top + R + 1, top + R + 2, "minecraft:white_stained_glass_pane")
    g.set(cx, top + R + 1, cz, "minecraft:sea_lantern")
    g.set(cx, top + R + 4, cz, "minecraft:gold_block"); g.set(cx, top + R + 5, cz, "minecraft:lightning_rod")
    # seven towers
    for k in range(7):
        a2 = k * 2 * np.pi / 7
        tx, tz = int(round(cx + (r + 3) * np.cos(a2))), int(round(cz + (r + 3) * np.sin(a2)))
        round_tower(g, tx, tz, 3, top + 8, GL, floors=(), spiral=False, cone=True, t=1)
        g.fill_mask(disk(g, tx, tz, 3), GL + 3, GL + 3, "minecraft:quartz_block")
    # interior: seven altars, candles, a crystal of the Seven
    for k in range(7):
        a2 = k * 2 * np.pi / 7 + np.pi / 7
        x, z = int(round(cx + (r - 6) * np.cos(a2))), int(round(cz + (r - 6) * np.sin(a2)))
        g.set(x, GL, z, "minecraft:quartz_pillar[axis=y]"); g.set(x, GL + 1, z, "minecraft:quartz_slab[type=bottom]")
        g.set(x, GL + 2, z, f"minecraft:candle[candles=4,lit=true]")
        g.fill(x - 1, GL, z - 1, x + 1, GL, z + 1, "minecraft:white_carpet") if False else None
    g.fill(cx - 1, GL, cz - 1, cx + 1, GL + 1, cz + 1, "minecraft:quartz_block")
    g.fill(cx - 1, GL + 2, cz - 1, cx + 1, GL + 3, cz + 1, "minecraft:white_stained_glass")
    g.set(cx, GL + 4, cz, "minecraft:sea_lantern")
    g.fill_mask(disk(g, cx, cz, 2.5) & ~disk(g, cx, cz, 1.8), GL, GL, "minecraft:quartz_block")
    for a2 in np.linspace(0.2, 2 * np.pi, 8, endpoint=False):
        g.lantern(int(round(cx + (r - 4) * np.cos(a2))), top - 4, int(round(cz + (r - 4) * np.sin(a2))))
    # south door and stair
    g.fill(cx - 2, GL, cz + r - 2, cx + 2, GL + 6, cz + r + 1, AIR)
    for k in range(3):
        g.fill(cx - 6 + k, GL - 1 - k, cz + r + 2 + k, cx + 6 - k, GL - 1 - k, cz + r + 2 + k, "minecraft:polished_andesite") if False else None
    g.fill(cx - 3, GL - 1, cz + r + 1, cx + 3, GL - 1, cz + r + 8, Mix(("minecraft:polished_diorite", 60), ("minecraft:polished_andesite", 40)))
    statue_plinth(g, cx - 6, cz + r + 4, GL); statue_plinth(g, cx + 6, cz + r + 4, GL)
    g.mark("knight", cx, GL, cz + r + 6); g.mark("knight", cx - 8, GL, cz); g.mark("knight", cx + 8, GL, cz)


def barracks(g, x0, z0, x1, z1, GL, rng):
    """Barracks hall with a training yard. The hiring post is the marker 'barracks'."""
    building(g, x0, z0, x0 + 14, z1, GL, 9, "z", door_cells=((x0 + 14, (z0 + z1) // 2),), upper_floor=GL + 4,
             chimney=(x0 + 2, z0 + 2))
    for z in range(z0 + 2, z1 - 1, 3):
        g.bed(x0 + 1, GL, z, "east", "gray"); g.bed(x0 + 1, GL + 5, z, "east", "gray")
        g.bed(x0 + 11, GL, z, "west", "gray"); g.bed(x0 + 11, GL + 5, z, "west", "gray")
    for z in range(z0 + 2, z1 - 1, 6):
        g.barrel(x0 + 13, GL, z, LOOT + "castle_barracks", facing="west")
    # yard with fence, training dummies, armour stands as a rack
    g.fill(x0 + 16, GL - 1, z0, x1, GL - 1, z1, Mix(("minecraft:coarse_dirt", 50), ("minecraft:dirt_path", 30), ("minecraft:gravel", 20)))
    for x in range(x0 + 16, x1 + 1):
        for z in (z0, z1):
            g.set(x, GL, z, "minecraft:spruce_fence")
    for z in range(z0, z1 + 1):
        g.set(x1, GL, z, "minecraft:spruce_fence")
    g.fill(x1, GL, (z0 + z1) // 2 - 1, x1, GL, (z0 + z1) // 2 + 1, AIR)
    for (x, z) in ((x0 + 20, z0 + 4), (x0 + 26, z0 + 4), (x0 + 32, z0 + 4)):
        g.fill(x, GL, z, x, GL + 1, z, "minecraft:dark_oak_fence")
        g.set(x, GL + 2, z, "minecraft:hay_block[axis=y]")
        g.set(x, GL + 3, z, "minecraft:carved_pumpkin[facing=south]")
    for x in range(x0 + 18, x1 - 2, 4):
        g.set(x, GL, z1 - 2, "minecraft:target")
    g.mark("barracks", x0 + 16, GL, (z0 + z1) // 2)
    g.mark("captain", x0 + 24, GL, (z0 + z1) // 2)
    for (x, z) in ((x0 + 20, z0 + 8), (x0 + 28, z0 + 8), (x0 + 24, z1 - 6)):
        g.mark("knight", x, GL, z)
    g.wall_banner(x0 + 14, GL + 5, z0 + 3, "east"); g.wall_banner(x0 + 14, GL + 5, z1 - 3, "east")


def inn(g, x0, z0, x1, z1, GL, rng, door):
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    dcell = {"north": (cx, z0), "south": (cx, z1), "west": (x0, cz), "east": (x1, cz)}[door]
    building(g, x0, z0, x1, z1, GL, 10, "x" if x1 - x0 >= z1 - z0 else "z", door_cells=(dcell,), upper_floor=GL + 5,
             chimney=(x0 + 2, z0 + 2), timber=True)
    # common room: tables, bar, kitchen
    for x in range(x0 + 3, x1 - 2, 4):
        g.set(x, GL, z0 + 3, "minecraft:dark_oak_fence"); g.set(x, GL + 1, z0 + 3, "minecraft:dark_oak_pressure_plate")
        g.set(x, GL, z1 - 3, "minecraft:dark_oak_fence"); g.set(x, GL + 1, z1 - 3, "minecraft:dark_oak_pressure_plate")
    g.fill(x1 - 2, GL, z0 + 2, x1 - 2, GL, z1 - 2, "minecraft:dark_oak_slab[type=top]")
    g.barrel(x1 - 1, GL, z0 + 2, LOOT + "castle_kitchen"); g.barrel(x1 - 1, GL, z1 - 2, LOOT + "castle_kitchen")
    g.set(x0 + 2, GL, z1 - 2, "minecraft:smoker[facing=east,lit=true]")
    g.set(x0 + 2, GL, z0 + 2, "minecraft:campfire[lit=true,signal_fire=false]")
    for z in range(z0 + 2, z1 - 1, 3):
        g.bed(x0 + 1, GL + 6, z, "east", rng.choice(BED_COLORS))
        g.bed(x1 - 2, GL + 6, z, "west", rng.choice(BED_COLORS))
    g.mark("inn", dcell[0], GL, dcell[1])
    g.mark("knight", dcell[0], GL, dcell[1] + (3 if door == "south" else -3))
    return dcell


def market_square(g, x0, z0, x1, z1, GL, rng):
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, SQUARE)
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    # fountain
    g.fill(cx - 5, GL - 1, cz - 5, cx + 5, GL - 1, cz + 5, "minecraft:stone_bricks")
    g.fill_mask(disk(g, cx, cz, 5.2) & ~disk(g, cx, cz, 4.2), GL, GL, "minecraft:stone_brick_wall")
    g.fill_mask(disk(g, cx, cz, 4.2), GL - 1, GL - 1, "minecraft:water")
    g.fill(cx, GL - 1, cz, cx, GL + 3, cz, "minecraft:chiseled_stone_bricks")
    g.fill_mask(disk(g, cx, cz, 1.5), GL + 4, GL + 4, "minecraft:stone_brick_slab[type=bottom]")
    g.set(cx, GL + 5, cz, "minecraft:water")
    # stalls in rows
    cols = ["red", "yellow", "blue", "green", "white", "orange", "purple", "lime"]
    i = 0
    for sx in range(x0 + 4, x1 - 8, 12):
        for sz in (z0 + 4, z1 - 8):
            if abs(sx - cx) < 10:
                continue
            col = cols[i % len(cols)]
            i += 1
            for (x, z) in ((sx, sz), (sx + 5, sz), (sx, sz + 4), (sx + 5, sz + 4)):
                g.fill(x, GL, z, x, GL + 2, z, "minecraft:spruce_fence")
            g.fill(sx, GL + 3, sz, sx + 5, GL + 3, sz + 4, f"minecraft:{col}_wool")
            g.fill(sx + 1, GL, sz + 1, sx + 4, GL, sz + 1, "minecraft:barrel[facing=up]")
            g.set(sx + 2, GL, sz + 3, "minecraft:hay_block[axis=y]")
    for x in range(x0 + 6, x1 - 4, 14):
        lamp_post(g, x, z0 + 1, GL); lamp_post(g, x, z1 - 1, GL)
    # notice board on the north side of the fountain
    g.fill(cx - 2, GL, cz - 9, cx + 2, GL + 2, cz - 9, "minecraft:dark_oak_planks")
    for x in range(cx - 2, cx + 3):
        g.set(x, GL + 3, cz - 9, "minecraft:dark_oak_slab[type=bottom]")
        g.set(x, GL + 1, cz - 8, "minecraft:oak_wall_sign[facing=south]")
    g.mark("notice_board", cx, GL, cz - 7)
    # gallows and pillory, the cheerful part of town
    g.fill(x1 - 12, GL, z1 - 6, x1 - 12, GL + 4, z1 - 6, "minecraft:dark_oak_log[axis=y]")
    g.fill(x1 - 12, GL + 4, z1 - 6, x1 - 8, GL + 4, z1 - 6, "minecraft:dark_oak_log[axis=x]")
    g.set(x1 - 9, GL + 3, z1 - 6, "minecraft:chain[axis=y]")
    g.fill(x1 - 12, GL - 1, z1 - 7, x1 - 7, GL - 1, z1 - 5, "minecraft:dark_oak_planks")
    for (x, z) in ((cx - 14, cz), (cx + 14, cz), (cx, cz + 14), (cx - 10, cz - 12), (cx + 10, cz - 12)):
        g.mark("knight", x, GL, z)


def keep(g, GL3):
    """The Red Keep on the high terrace: a buttressed great hall with a rose window, a 70-block donjon that
    steps into a round lantern stage, four drum towers, the Tower of the Hand, the godswood."""
    kx0, kz0, kx1, kz1 = 88, 18, 168, 76
    kw = GL3 + 18
    straight_wall(g, kx0, kz0, kx0 + 4, kz1, kw, "west", GL3)
    straight_wall(g, kx1 - 4, kz0, kx1, kz1, kw, "east", GL3)
    straight_wall(g, kx0, kz0, kx1, kz0 + 4, kw, "north", GL3)
    straight_wall(g, kx0, kz1 - 4, kx1, kz1, kw, "south", GL3)
    for (cx, cz) in ((kx0 + 2, kz0 + 2), (kx1 - 2, kz0 + 2), (kx0 + 2, kz1 - 2), (kx1 - 2, kz1 - 2)):
        sx, sz = (1 if cx < 128 else -1), (1 if cz < 48 else -1)
        ground = (0, sz, GL3) if cx > 128 and cz < 48 else (sx * 0.7, sz * 0.7, GL3)
        ring_tower(g, cx, cz, 8, kw + 10, GL3, stages=1, cone=True, posts="archer",
                   doors=((sx, 0, kw), (0, sz, kw), ground))
    for x in range(kx0 + 12, kx1 - 8, 8):
        g.mark("archer", x, kw, kz0 + 2); g.mark("archer", x, kw, kz1 - 2)
    for z in range(kz0 + 12, kz1 - 8, 8):
        g.mark("archer", kx0 + 2, kw, z); g.mark("archer", kx1 - 2, kw, z)
    # gatehouse (south), twin turrets
    g.fill(119, 0, kz1 - 7, 136, kw + 5, kz1 + 1, STONE)
    pointed_arch(g, 124, 131, GL3, 9, kz1 - 7, kz1 + 1)
    portcullis_plane(g, 124, 131, GL3, 9, kz1)
    for tx in (117, 138):
        round_tower(g, tx, kz1 - 3, 5, kw + 12, GL3, floors=(GL3 + 8, kw - 1, kw + 4), spiral=False, cone=True, t=2,
                    doors=((0, -1, kw), (1 if tx < 128 else -1, 0, kw + 5), (1, 0, kw), (-1, 0, kw)))
        for y in range(kw, kw + 5):
            g.set(tx, y, kz1 - 5, "minecraft:ladder[facing=south]")
    parapet_rect(g, 119, kz1 - 7, 136, kz1 + 1, kw + 6)
    g.fill(120, kw + 6, kz1 - 6, 135, kw + 10, kz1, AIR)
    g.fill(119, kw + 5, kz1 - 7, 136, kw + 5, kz1 + 1, STONE)
    for x in (119, 136):
        g.fill(x, kw + 6, kz1 - 3, x, kw + 8, kz1 - 2, AIR)             # the towers' doors onto the roof
    for x in (121, 127, 134):
        g.mark("crossbowman", x, kw + 6, kz1 - 1)
    g.wall_banner(122, GL3 + 13, kz1 + 2, "south"); g.wall_banner(133, GL3 + 13, kz1 + 2, "south")
    g.mark("captain", 127, GL3, kz1 - 10); g.mark("rally", 128, GL3, kz1 - 11)
    # courtyard
    g.fill(kx0 + 5, GL3 - 1, kz0 + 5, kx1 - 5, GL3 - 1, kz1 - 5, Mix(("minecraft:stone_bricks", 50), ("minecraft:polished_andesite", 30),
                                                                     ("minecraft:cobblestone", 20)))
    g.fill(125, GL3 - 1, kz0 + 5, 130, GL3 - 1, kz1 - 5, "minecraft:polished_andesite")
    # ---- great hall ----
    hx0, hz0, hx1, hz1 = 96, 26, 142, 58
    hroof = GL3 + 22
    g.fill(hx0, 0, hz0, hx1, hroof - 1, hz1, STONE)
    g.fill(hx0 + 2, GL3, hz0 + 2, hx1 - 2, hroof - 2, hz1 - 2, AIR)
    g.fill(hx0 + 2, GL3 - 1, hz0 + 2, hx1 - 2, GL3 - 1, hz1 - 2, Mix(("minecraft:polished_deepslate", 50), ("minecraft:polished_andesite", 50)))
    # buttresses and tall windows along both long sides
    for x in range(hx0 + 2, hx1 - 1, 6):
        for z, f in ((hz0 - 2, "north"), (hz1 + 2, "south")):
            zz = z if f == "north" else z
            g.fill(x, GL3, zz, x + 1, GL3 + 12, zz, STONE)
            g.fill(x, GL3, zz + (1 if f == "north" else -1), x + 1, hroof - 4, zz + (1 if f == "north" else -1), STONE)
            g.stair(x, GL3 + 13, zz, STONE_STAIR, "south" if f == "north" else "north", half="bottom")
            g.stair(x + 1, GL3 + 13, zz, STONE_STAIR, "south" if f == "north" else "north", half="bottom")
        for z in (hz0, hz1):
            g.fill(x + 3, GL3 + 2, z, x + 4, GL3 + 11, z, "minecraft:red_stained_glass_pane" if (x // 6) % 2 else "minecraft:yellow_stained_glass_pane")
            g.fill(x + 3, GL3 + 14, z, x + 4, GL3 + 17, z, GLASS)
    # rose window on the south gable, door below it
    g.fill(116, GL3, hz1 - 2, 122, GL3 + 6, hz1, AIR)
    pointed_arch(g, 116, 122, GL3, 7, hz1 - 2, hz1 + 1)
    cx, wy = 119, GL3 + 14
    for (dx, dy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2), (1, 1), (-1, 1), (1, -1), (-1, -1),
                     (3, 0), (-3, 0), (0, 3), (0, -3), (2, 1), (2, -1), (-2, 1), (-2, -1), (1, 2), (-1, 2), (1, -2), (-1, -2)):
        col = "red" if (dx, dy) == (0, 0) else ("yellow" if abs(dx) + abs(dy) == 1 else ("blue" if abs(dx) + abs(dy) == 2 else "purple"))
        g.set(cx + dx, wy + dy, hz1, f"minecraft:{col}_stained_glass_pane")
    # pitched roof with ridge finials, and a clerestory walk
    gable_roof(g, hx0 + 2, hz0 + 2, hx1 - 2, hz1 - 2, hroof, "x", ROOF_STAIR, STONE)
    for x in range(hx0 + 1, hx1, 2):
        for z, f in ((hz0 - 1, "south"), (hz1 + 1, "north")):
            g.stair(x, hroof - 2, z, STONE_STAIR, f, half="top")
            g.fill(x, hroof - 1, z, x, hroof, z, STONE)
    for x in range(hx0 + 6, hx1 - 4, 12):
        g.set(x, hroof + ((hz1 - hz0) // 2) + 1, (hz0 + hz1) // 2, "minecraft:gold_block") if False else None
    hall_furnish(g, hx0 + 2, hx1 - 2, hz0 + 5, hz1 - 5, GL3, throne_facing="south")
    # the Iron Throne: a jagged seat of dark iron and swords (anvils, chains, iron bars)
    tx, tz = 119, hz0 + 5
    g.fill(tx - 3, GL3, tz - 2, tx + 3, GL3 + 1, tz, "minecraft:polished_deepslate")
    g.fill(tx - 2, GL3 + 2, tz - 2, tx + 2, GL3 + 2, tz - 1, "minecraft:iron_block")
    for (dx, dy, dz) in ((-2, 3, -2), (2, 3, -2), (-1, 3, -2), (1, 3, -2), (0, 3, -2), (0, 4, -2), (-2, 4, -2), (2, 4, -2),
                         (-1, 5, -2), (1, 5, -2), (0, 6, -2), (-3, 3, -1), (3, 3, -1)):
        g.set(tx + dx, GL3 + dy, tz + dz, "minecraft:iron_bars" if dy > 4 or abs(dx) == 3 else "minecraft:anvil[facing=south]")
    g.set(tx, GL3 + 2, tz - 1, "minecraft:polished_blackstone_stairs[facing=south,half=bottom]") if False else None
    for x in range(hx0 + 8, hx1 - 6, 10):
        for z in (hz0 + 9, hz1 - 9):
            g.set(x, hroof - 3, z, "minecraft:chain[axis=y]"); g.set(x, hroof - 4, z, "minecraft:chain[axis=y]")
            g.lantern(x, hroof - 5, z)
    for (x, z) in ((hx0 + 4, hz0 + 7), (hx1 - 4, hz0 + 7), (hx0 + 4, hz1 - 7), (hx1 - 4, hz1 - 7), (108, 42), (130, 42)):
        g.mark("knight", x, GL3, z)
    # ---- the donjon: square base, round lantern stage, four bartizans, royal apartments ----
    tx0, tz0, tx1, tz1 = 146, 28, 164, 46
    dtop = GL3 + 56
    floors = tuple(range(GL3 + 10, dtop - 6, 10))
    square_tower(g, tx0, tz0, tx1, tz1, dtop, GL3, floors=floors, stair="north", t=4,
                 banner_sides=("north", "south", "east", "west"))
    for (cx2, cz2) in ((tx0 - 1, tz0 - 1), (tx1 + 1, tz0 - 1), (tx0 - 1, tz1 + 1), (tx1 + 1, tz1 + 1)):
        bartizan(g, cx2, cz2, dtop + 1, r=3)
    carve(g, 147, dtop, 29, 163, dtop, 45, STONE)                      # stage plinth, one step up, clear of the stair hall
    stepped_top(g, 155, 37, 9, dtop + 1, stages=2, cone=True, posts="crossbowman")
    g.fill(tx0 - 1, GL3, 38, tx0 + 3, GL3 + 2, 40, AIR)                 # the door, through the whole wall
    for fy in floors:
        g.fill(tx0, fy + 1, 30, tx0, fy + 2, 34, AIR) if False else None
    # tall windows on the donjon faces
    for fy in floors:
        for z in (tz0, tz1):
            for x in (tx0 + 6, tx0 + 10, tx0 + 14):
                g.fill(x, fy + 2, z, x, fy + 5, z, GLASS)
        for x in (tx0, tx1):
            for z in (tz0 + 6, tz0 + 10, tz0 + 14):
                g.fill(x, fy + 2, z, x, fy + 5, z, GLASS)
    g.mark("banner_stand", 162, dtop + 1, 44)                              # on the stage plinth
    for (x, z) in ((149, 31), (161, 31), (149, 44)):
        g.mark("crossbowman", x, dtop + 1, z)
    # the treasury keeps clear of the stair hall in the north-east corner
    for z in range(33, 44, 3):
        g.chest(tx0 + 5, floors[0] + 1, z, "east", LOOT + "castle_treasury")
    for x in range(tx0 + 5, tx0 + 9):
        g.chest(x, floors[0] + 1, tz1 - 4, "north", LOOT + "castle_treasury")
    g.fill(150, floors[0] + 1, 40, 153, floors[0] + 1, 43, "minecraft:gold_block")
    g.fill(151, floors[0] + 2, 41, 152, floors[0] + 2, 42, "minecraft:gold_block")
    for z in range(33, 44, 4):
        g.bed(tx0 + 6, floors[1] + 1, z, "west", "red"); g.bed(tx0 + 7, floors[2] + 1, z, "west", "purple")
    # the Tower of the Hand: slender, taller than the walls
    ring_tower(g, 154, 55, 6, GL3 + 40, GL3, stages=1, cone=True, posts="archer", doors=((0, 1, GL3),))
    # godswood, kitchens, stables, a well
    g.fill(146, GL3 - 1, 46, 166, GL3 - 1, 70, Mix(("minecraft:grass_block", 65), ("minecraft:moss_block", 20), ("minecraft:podzol", 15)))
    tree_weirwood(g, 162, 58, GL3)
    for (x, z) in ((163, 66), (164, 48)):
        tree_oak(g, x, z, GL3, g.rng)
    building(g, 96, 62, 116, 70, GL3, 8, "x", door_cells=((106, 62),), chimney=(98, 68))
    for x in (98, 101, 104, 107):
        g.barrel(x, GL3, 69, LOOT + "castle_kitchen")
    g.set(113, GL3, 69, "minecraft:smoker[facing=north,lit=true]"); g.set(110, GL3, 69, "minecraft:smoker[facing=north,lit=true]")
    building(g, 136, 62, 158, 70, GL3, 7, "x")                      # stables, clear of the gate passage
    g.fill(138, GL3, 62, 156, GL3 + 3, 62, AIR)
    for x in range(138, 157, 4):
        g.fill(x, GL3, 63, x, GL3, 69, "minecraft:spruce_fence"); g.set(x + 2, GL3, 69, "minecraft:hay_block[axis=y]")
    for (x, z) in ((108, 60), (130, 60), (140, 50), (100, 50), (120, 60), (140, 56)):
        g.mark("knight", x, GL3, z)
    g.mark("captain", 128, GL3, 64)
    statue_plinth(g, 122, 48, GL3, h=5); statue_plinth(g, 133, 48, GL3, h=5)


# ---------------------------------------------------------------- the city


def stepped_top(g, cx, cz, r, y, stages=2, cone=True, posts=None):
    """Stages that step inward from radius r at level y: a lantern gallery each, a cone on the last."""
    rr = r
    for k in range(stages):
        rr = rr - 2
        if rr < 3:
            break
        y2 = y + 10
        g.fill_mask(disk(g, cx, cz, rr), y, y2 - 1, STONE)
        g.fill_mask(disk(g, cx, cz, rr - 1), y, y2 - 2, AIR)
        g.fill_mask(disk(g, cx, cz, rr - 1), y2 - 1, y2 - 1, STONE)
        ai = angle_index(g, cx, cz, rr)
        g.fill_mask(ring(g, cx, cz, rr, rr - 1) & (np.mod(ai, 4) == 1), y + 3, y + 5, AIR)
        g.fill_mask(ring(g, cx, cz, rr + 1, rr) & (np.mod(ai, 2) == 0), y, y, SLAB_CAP)
        g.fill_mask(ring(g, cx, cz, rr + 1, rr) & (np.mod(ai, 4) < 2), y + 1, y + 1, "minecraft:stone_brick_wall")
        for a2 in np.linspace(0, 2 * np.pi, 6, endpoint=False):
            g.lantern(int(round(cx + (rr - 1) * np.cos(a2))), y + 6, int(round(cz + (rr - 1) * np.sin(a2))))
        # a door from the stage out onto the walkway round it
        g.fill(int(cx) + rr - 2, y, int(cz), int(cx) + rr + 1, y + 2, int(cz) + 1, AIR)
        if posts and k == 0:
            # on the walkway between the parapet and the first stage
            for a2 in np.linspace(0.2, 2 * np.pi, 4, endpoint=False):
                g.mark(posts, int(round(cx + (r - 0.6) * np.cos(a2))), y, int(round(cz + (r - 0.6) * np.sin(a2))))
        y = y2
    if cone:
        cone_roof(g, cx, cz, rr + 1, y, ROOF, height_factor=2.2)
        g.set(cx, y + int((rr + 1) * 2.2) + 1, cz, "minecraft:lightning_rod")
    else:
        g.fill_mask(disk(g, cx, cz, rr), y, y, STONE)


def ring_tower(g, cx, cz, r, top, GL, stages=2, cone=True, posts=None, doors=(), wide=None):
    """A tall round tower that steps in once or twice near the top, with a lantern gallery."""
    floors = sorted(set(range(GL + 9, top - 8, 9)) | {y - 1 for (_, _, y) in doors if y > GL + 2})
    sp = round_tower(g, cx, cz, r, top, GL, floors=tuple(floors), spiral=True, cone=False, doors=doors, wide=wide)
    stepped_top(g, cx, cz, r, top, stages=stages, cone=cone, posts=posts)
    return sp


def grand_stair(g, x0, x1, z_from, y_from, y_to, direction=-1):
    """A wide ceremonial stair with a landing every 6 steps and balustrades."""
    rise = y_to - y_from
    facing = "north" if direction < 0 else "south"
    z = z_from
    y = y_from
    k = 0
    while k < rise:
        g.fill(x0, 0, z, x1, y - 1, z, STONE)
        for x in range(x0, x1 + 1):
            g.stair(x, y, z, "minecraft:polished_andesite_stairs", facing)
        g.fill(x0, y + 1, z, x1, y + 8, z, AIR)
        g.fill(x0 - 1, 0, z, x0 - 1, y, z, STONE); g.fill(x1 + 1, 0, z, x1 + 1, y, z, STONE)
        g.set(x0 - 1, y + 1, z, "minecraft:stone_brick_wall"); g.set(x1 + 1, y + 1, z, "minecraft:stone_brick_wall")
        y += 1
        k += 1
        z += direction
        if k % 6 == 0 and k < rise:
            for _ in range(3):
                g.fill(x0, 0, z, x1, y - 2, z, STONE)
                g.fill(x0, y - 1, z, x1, y - 1, z, "minecraft:polished_andesite")
                g.fill(x0, y, z, x1, y + 8, z, AIR)
                g.fill(x0 - 1, 0, z, x0 - 1, y - 1, z, STONE); g.fill(x1 + 1, 0, z, x1 + 1, y - 1, z, STONE)
                g.set(x0 - 1, y, z, "minecraft:stone_brick_wall"); g.set(x1 + 1, y, z, "minecraft:stone_brick_wall")
                z += direction
            g.set(x0 - 1, y + 1, z - direction * 2, "minecraft:lantern[hanging=false]")
            g.set(x1 + 1, y + 1, z - direction * 2, "minecraft:lantern[hanging=false]")
    g.fill(x0, 0, z, x1, y_to - 2, z, STONE)
    g.fill(x0, y_to - 1, z, x1, y_to - 1, z, "minecraft:polished_andesite")
    return z


def statue_plinth(g, x, z, GL, h=4):
    """A pedestal with a stone figure: a column with an iron helm, flanked by low walls."""
    g.fill(x - 1, GL, z - 1, x + 1, GL, z + 1, "minecraft:polished_andesite")
    g.fill(x, GL + 1, z, x, GL + h - 1, z, "minecraft:chiseled_stone_bricks")
    g.set(x, GL + h, z, "minecraft:polished_andesite")
    g.set(x, GL + h + 1, z, "minecraft:stone_brick_wall")
    g.set(x, GL + h + 2, z, "minecraft:anvil[facing=north]")
    for (dx, dz) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        g.set(x + dx, GL + 1, z + dz, "minecraft:stone_brick_wall")


def triumphal_arch(g, cx, z, GL):
    """An arch over the avenue: two piers, a vault, trophies on top."""
    for px in (cx - 7, cx + 4):
        g.fill(px, GL, z - 2, px + 3, GL + 13, z + 2, STONE)
        g.fill(px, GL + 3, z - 2, px + 3, GL + 3, z + 2, DARK)
        g.fill(px, GL + 10, z - 2, px + 3, GL + 10, z + 2, DARK)
        for zz in (z - 2, z + 2):
            g.fill(px + 1, GL + 5, zz, px + 2, GL + 8, zz, "minecraft:polished_andesite")
    g.fill(cx - 7, GL + 12, z - 2, cx + 7, GL + 15, z + 2, STONE)
    g.fill(cx - 3, GL + 12, z - 1, cx + 3, GL + 13, z + 1, AIR) if False else None
    for x in range(cx - 3, cx + 4):
        g.stair(x, GL + 11, z - 3, STONE_STAIR, "south", half="top"); g.stair(x, GL + 11, z + 3, STONE_STAIR, "north", half="top")
    g.fill(cx - 8, GL + 16, z - 3, cx + 8, GL + 16, z + 3, SLAB_CAP)
    for x in (cx - 6, cx, cx + 6):
        g.set(x, GL + 17, z, "minecraft:chiseled_stone_bricks"); g.set(x, GL + 18, z, "minecraft:stone_brick_wall")
        g.set(x, GL + 19, z, "minecraft:lantern[hanging=false]")
    g.wall_banner(cx - 8, GL + 8, z, "west"); g.wall_banner(cx + 8, GL + 8, z, "east")
    g.lantern(cx - 4, GL + 11, z); g.lantern(cx + 4, GL + 11, z)


def lighthouse(g, cx, cz, GL):
    top = GL + 30
    round_tower(g, cx, cz, 4, top, GL, floors=(GL + 8, GL + 16, GL + 24), spiral=True, cone=False, t=2,
                doors=((0, -1, GL),))
    g.fill_mask(disk(g, cx, cz, 3), top, top + 3, AIR)
    g.fill_mask(ring(g, cx, cz, 3, 2), top, top + 2, "minecraft:gray_stained_glass_pane")
    g.set(cx, top, cz, "minecraft:sea_lantern"); g.set(cx, top + 1, cz, "minecraft:sea_lantern")
    g.fill_mask(disk(g, cx, cz, 3), top + 3, top + 3, SLAB_CAP)
    cone_roof(g, cx, cz, 3, top + 4, ROOF, height_factor=1.5)
    for y in range(GL + 4, top - 2, 6):
        g.fill_mask(ring(g, cx, cz, 4, 3) & (angle_index(g, cx, cz, 4) == 0), y, y, "minecraft:white_concrete")


def bell_tower(g, x0, z0, GL):
    x1, z1 = x0 + 6, z0 + 6
    top = GL + 30
    square_tower(g, x0, z0, x1, z1, top, GL, floors=(GL + 8, GL + 16, GL + 24), spiral_at=(x0 + 3, z0 + 3), t=1)
    g.fill(x0 + 1, top - 6, z0 + 1, x1 - 1, top - 2, z1 - 1, AIR)
    for x in range(x0 + 1, x1):
        for z in (z0, z1):
            g.fill(x, top - 6, z, x, top - 3, z, AIR if (x - x0) % 2 == 1 else STONE)
    for z in range(z0 + 1, z1):
        for x in (x0, x1):
            g.fill(x, top - 6, z, x, top - 3, z, AIR if (z - z0) % 2 == 1 else STONE)
    g.set(x0 + 3, top - 3, z0 + 3, "minecraft:bell[attachment=ceiling,facing=north]")
    g.fill(x0 - 1, top, z0 - 1, x1 + 1, top, z1 + 1, SLAB_CAP)
    cone_roof(g, x0 + 3, z0 + 3, 4, top + 1, ROOF, height_factor=2.4)
    g.fill(x0 + 3, GL, z1, x0 + 3, GL + 2, z1, AIR)


def dragonpit(g, cx, cz, r, GL):
    """A great domed arena, half ruined: ribs of a fallen dome, the pit open to the sky."""
    full = disk(g, cx, cz, r)
    shell = ring(g, cx, cz, r, r - 3)
    g.fill_mask(full, 0, GL - 2, FOUND)
    g.fill_mask(disk(g, cx, cz, r - 3), GL - 1, GL - 1, Mix(("minecraft:sand", 40), ("minecraft:coarse_dirt", 30),
                                                             ("minecraft:gravel", 20), ("minecraft:bone_block", 10)))
    g.fill_mask(full, GL, GL + 40, AIR)
    h = GL + 16
    RUIN = Mix(("minecraft:stone_bricks", 35), ("minecraft:cracked_stone_bricks", 25), ("minecraft:mossy_stone_bricks", 20),
               ("minecraft:cobblestone", 20))
    g.fill_mask(shell, GL, h - 1, RUIN)
    ai = angle_index(g, cx, cz, r)
    for y in (GL + 4, GL + 10):
        g.fill_mask(ring(g, cx, cz, r, r - 1) & (np.mod(ai, 6) < 2), y, y + 3, AIR)
    for k in range(4):
        rr = r - 3 - k * 2
        g.fill_mask(ring(g, cx, cz, rr, rr - 2), GL, GL + k, Mix(("minecraft:stone_bricks", 60), ("minecraft:cobblestone", 40)))
    xs2, zs2 = g.xz()
    ang = np.arctan2(zs2 - cz, xs2 - cx) + 0 * xs2 + 0 * zs2
    for k in range(12):
        rr = r - 1 - k * (r - 8) / 12.0
        band = disk(g, cx, cz, rr + 1.2) & ~disk(g, cx, cz, max(0.3, rr - 1.4))
        keep = band & ((np.mod((ang + np.pi) * 8 / np.pi, 2.0) < 0.8) | (k < 3))
        g.fill_mask(keep, h + k * 2, h + k * 2 + 1, RUIN)
    g.fill(cx - 2, GL, cz + r - 4, cx + 2, GL + 6, cz + r + 1, AIR)
    g.fill(cx - 3, GL - 1, cz + r - 4, cx + 3, GL - 1, cz + r + 4, ROAD)
    g.fill_mask(disk(g, cx, cz, 5), GL - 4, GL - 1, AIR)
    g.fill_mask(disk(g, cx, cz, 5), GL - 5, GL - 5, "minecraft:magma_block")
    g.fill_mask(ring(g, cx, cz, 6, 5), GL - 1, GL - 1, "minecraft:polished_blackstone")
    for a2 in np.linspace(0, 2 * np.pi, 5, endpoint=False):
        x, z = int(round(cx + 9 * np.cos(a2))), int(round(cz + 9 * np.sin(a2)))
        g.fill(x, GL, z, x, GL + 2, z, "minecraft:bone_block[axis=y]")
        g.set(x, GL + 3, z, "minecraft:skeleton_skull[rotation=8]")
    for a2 in np.linspace(0.4, 2 * np.pi, 4, endpoint=False):
        g.mark("knight", int(round(cx + (r + 3) * np.cos(a2))), GL, int(round(cz + (r + 3) * np.sin(a2))))
    g.mark("crossbowman", cx, h, cz + r - 2)


def great_city(seed=77):
    X, Z, H = 256, 256, 128
    GL = 8            # lower city ground (standing level)
    GL2 = GL + 8      # upper city
    GL3 = GL + 22     # keep terrace
    g = Grid(X, H, Z, seed)
    rng = g.rng
    xs, zs = g.xz()

    # ---- ground, harbour ----
    g.fill(0, 0, 0, X - 1, GL - 2, Z - 1, FOUND)
    g.fill(0, GL - 1, 0, X - 1, GL - 1, Z - 1, Mix(("minecraft:grass_block", 60), ("minecraft:coarse_dirt", 20),
                                                     ("minecraft:dirt_path", 10), ("minecraft:gravel", 10)))
    g.fill(0, GL, 0, X - 1, GL + 10, Z - 1, AIR)
    water = (zs >= 238) & (xs >= 0)
    g.fill_mask(water, 0, GL - 7, DARK)
    g.fill_mask(water, GL - 6, GL - 6, Mix(("minecraft:sand", 50), ("minecraft:gravel", 30), ("minecraft:clay", 20)))
    g.fill_mask(water, GL - 5, GL - 2, "minecraft:water")
    g.fill_mask(water, GL - 1, GL + 10, AIR)
    quay = (zs >= 228) & (zs < 238) & (xs >= 0)
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
        lamp_post(g, x, 237, GL)
    for x in (20, 68, 180, 228):
        # against the foot of the wall, doors on the quay: no slot behind them and a street three wide in front
        building(g, x, 228, x + 16, 234, GL, 6, "x", door_cells=((x + 8, 234),), wall_block=STONE)
        g.barrel(x + 2, GL, 232, LOOT + "town_house"); g.barrel(x + 14, GL, 232, LOOT + "town_house")
    lighthouse(g, 8, 232, GL)
    lighthouse(g, 247, 232, GL)
    g.mark("knight", 128, GL, 236); g.mark("knight", 60, GL, 236); g.mark("knight", 196, GL, 236)

    # ---- terraces ----
    upper = rect(g, 64, 14, 191, 113)
    g.fill_mask(upper, GL - 1, GL2 - 2, FOUND)
    g.fill_mask(upper, GL2 - 1, GL2 - 1, PAVE)
    g.fill_mask(upper, GL2, GL2 + 12, AIR)
    keep_t = rect(g, 88, 18, 168, 76)
    g.fill_mask(keep_t, GL2 - 1, GL3 - 2, FOUND)
    g.fill_mask(keep_t, GL3 - 1, GL3 - 1, PAVE)
    g.fill_mask(keep_t, GL3, GL3 + 12, AIR)

    # ---- streets (lower city) ----
    g.fill(124, GL - 1, 114, 131, GL - 1, 227, ROAD)                 # the avenue
    g.fill(14, GL - 1, 160, 241, GL - 1, 165, ROAD)                  # the cross street
    for x0 in (60, 192):
        g.fill(x0, GL - 1, 14, x0 + 3, GL - 1, 221, ROAD)
    for z0 in (128, 196):
        g.fill(14, GL - 1, z0, 241, GL - 1, z0 + 3, ROAD)
    g.fill(14, GL - 1, 114, 241, GL - 1, 117, ROAD)
    g.fill(14, GL - 1, 218, 241, GL - 1, 221, ROAD)
    g.fill(14, GL - 1, 62, 59, GL - 1, 65, ROAD); g.fill(196, GL - 1, 62, 241, GL - 1, 65, ROAD)
    for x in range(20, 240, 16):
        g.mark("knight", x, GL, 162)
    for z in range(120, 220, 16):
        g.mark("knight", 127, GL, z); g.mark("knight", 62, GL, z); g.mark("knight", 194, GL, z)
    for z in range(24, 110, 16):
        g.mark("knight", 62, GL, z); g.mark("knight", 194, GL, z)
    for x in range(30, 230, 24):
        lamp_post(g, x, 159, GL); lamp_post(g, x + 12, 166, GL)
    for z in range(120, 216, 20):
        lamp_post(g, 123, z, GL); lamp_post(g, 132, z + 10, GL)
    for z in range(30, 110, 24):
        lamp_post(g, 59, z, GL); lamp_post(g, 196, z, GL)
    # the avenue: statues every 20 blocks and an arch at the market
    for z in range(136, 216, 20):
        statue_plinth(g, 121, z, GL); statue_plinth(g, 134, z, GL)
    triumphal_arch(g, 127, 150, GL)

    # ---- districts: packed terraces, alleys a cart wide, trades by street ----
    doors = []
    # north of the cross street, west of the avenue: the market street along the terrace foot
    doors.append(inn(g, 18, 118, 42, 127, GL, rng, "south"))
    doors += terrace(g, 45, 58, 118, 9, GL, rng, "south", trades=["general", "none"])
    doors += terrace(g, 65, 122, 118, 9, GL, rng, "south", trades=["baker", "general", "chandler", "none", "potter", "general"])
    doors += terrace(g, 133, 190, 118, 9, GL, rng, "south", trades=["cloth", "general", "apothecary", "none", "cloth", "general"])
    # Smiths' Row: the smithy and its neighbours, chimneys smoking
    building(g, 200, 118, 216, 126, GL, 7, "x", door_cells=((208, 126),), chimney=(202, 120))
    g.set(203, GL, 121, "minecraft:anvil[facing=east]"); g.set(206, GL, 121, "minecraft:smithing_table")
    g.set(213, GL, 120, "minecraft:blast_furnace[facing=south,lit=false]"); g.set(210, GL, 120, "minecraft:lava_cauldron")
    g.chest(214, GL, 124, "west", LOOT + "castle_smithy")
    g.mark("smithy", 208, GL, 128)
    doors += terrace(g, 219, 237, 118, 9, GL, rng, "south", trades=["armoury", "weapons", "tanner"])
    # the four blocks between the cross street and the market street, each split by a back lane
    doors += dense_block(g, 18, 133, 58, 158, GL, rng, trades=["general", "none", "none", "baker", "none"], alley=("z", 36))
    doors += dense_block(g, 65, 133, 122, 158, GL, rng, trades=["none", "cloth", "none", "none", "chandler"], alley=("z", 93))
    doors += dense_block(g, 133, 133, 190, 158, GL, rng, trades=["none", "none", "potter", "none", "general"], alley=("z", 161))
    doors += dense_block(g, 197, 133, 237, 158, GL, rng, trades=["smith", "none", "tanner", "none", "none"], alley=("z", 220))
    # flanks: the north-west quarter is a warren of lanes, the north-east holds the Dragonpit
    doors += dense_block(g, 18, 18, 58, 60, GL, rng, sides=("south", "west", "east"), trades=["none", "none", "apothecary", "none"], alley=("x", 39))
    doors += dense_block(g, 18, 67, 58, 112, GL, rng, trades=["none", "fletcher", "none", "none"], alley=("x", 90))
    dragonpit(g, 218, 42, 19, GL)
    doors += dense_block(g, 197, 67, 237, 112, GL, rng, trades=["none", "none", "butcher", "none"], alley=("x", 90))
    # south of the cross street: the sept in its plaza, the market, the barracks
    great_sept(g, 36, 188, 17, GL)
    g.fill(34, GL - 1, 166, 38, GL - 1, 168, ROAD)
    market_square(g, 68, 168, 186, 194, GL, rng)
    arcade(g, 68, 168, 186, 170, GL, rng, "south", trades=["cloth", "general", "potter", "chandler"])
    arcade(g, 68, 192, 186, 194, GL, rng, "north", trades=["baker", "butcher", "general", "fish"])
    stall_cluster(g, 72, 173, 110, 190, GL, rng, trades=["general", "fletcher", "farmer", "baker", "armoury"])
    stall_cluster(g, 146, 173, 182, 190, GL, rng, trades=["fish", "weapons", "butcher", "apothecary", "general"])
    bell_tower(g, 70, 170, GL)
    barracks(g, 200, 170, 240, 192, GL, rng)
    # the harbour quarter: fishmongers and taverns stacked over the quay street
    doors.append(inn(g, 65, 204, 92, 216, GL, rng, "south"))
    doors += dense_block(g, 95, 201, 122, 216, GL, rng, sides=("south", "north"), depth=7, trades=["fish", "none", "general"], court=False)
    doors += dense_block(g, 133, 201, 190, 216, GL, rng, sides=("south", "north"), depth=7, trades=["fish", "none", "tanner", "none"], court=False)
    doors += dense_block(g, 197, 201, 237, 216, GL, rng, sides=("south", "north"), depth=7, trades=["none", "fish", "none"], court=False)

    # ---- inner wall and upper city ----
    iw = GL2 + 18
    d2, along2, out2 = side_grids(g, 64, 191, 14, 113)
    curtain(g, 64, 191, 5, iw, GL2, az=14, bz=113, banners_every=18, grids=(d2, along2, out2))
    posts_on_walk(g, d2, along2, iw, "archer", dsel=1, every=7, offset=2)
    for (cx, cz) in ((66, 16), (189, 16), (66, 111), (189, 111)):
        ring_tower(g, cx, cz, 7, iw + 8, GL2, stages=1, cone=True, posts="archer",
                   doors=((1 if cx < 128 else -1, 0, iw), (0, 1 if cz < 64 else -1, iw)))
    # the side streets pass under the south-east and south-west towers through vaulted ways
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
        g.fill(x + 4, GL2, 105, x + 5, GL2 + 2, 109, AIR)                 # door from the upper city
        g.fill(x + 4, GL2 - 1, 105, x + 5, GL2 - 1, 109, FLOOR_STONE)
        g.mark("crossbowman", x + 4, iw + 6, 112)
    # inner gate on the avenue: a barbican with the grand stair climbing to it
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
    # the grand stair to the keep: 14 blocks of rise in a wide sweep, flanked by statues
    z_end = grand_stair(g, 122, 133, 96, GL2, GL3, direction=-1)
    g.fill(120, GL2 - 1, z_end, 121, GL3 - 1, 96, STONE); g.fill(134, GL2 - 1, z_end, 135, GL3 - 1, 96, STONE)
    for z in (94, 86):
        statue_plinth(g, 118, z, GL2, h=5); statue_plinth(g, 137, z, GL2, h=5)
    # terrace faces, arcaded where they face the streets
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
                    continue                       # the keep's corner towers stand right behind the north towers
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
    # land gates west and east: barbicans with twin drum towers
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
        g.fill(bx0 + 1, walk - 1, cz_mid - 8, bx1 - 1, walk - 1, cz_mid + 8, PLANK)     # gallery at wall-walk level
        spiral_stair(g, sp_x, cz_mid + 5, GL + 13, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
        # the wall-walk runs straight through both drum towers and across the gallery
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
    # the river gate
    g.fill(117, 0, bz - 8, 138, gtop - 1, bz + 1, STONE)
    for tx in (114, 141):
        ring_tower(g, tx, bz - 3, 8, gtop + 6, GL, stages=1, cone=True, posts="crossbowman",
                   doors=((0, -1, walk), (1 if tx < 128 else -1, 0, gtop)), wide=False)
    pointed_arch(g, 124, 131, GL, 11, bz - 8, bz + 1)
    g.fill(124, GL - 1, bz - 8, 131, GL - 1, 237, ROAD)
    portcullis_plane(g, 124, 131, GL, 11, bz)
    portcullis_plane(g, 124, 131, GL, 11, bz - 7)
    g.fill(118, GL + 13, bz - 7, 137, gtop - 2, bz, AIR)
    g.fill(118, GL + 12, bz - 7, 137, GL + 12, bz, PLANK)
    g.fill(118, walk - 1, bz - 7, 137, walk - 1, bz, PLANK)                            # gallery at wall-walk level
    spiral_stair(g, 120, bz - 4, GL + 13, gtop - 1, STONE_STAIR, STONE, landings=(walk,))
    carve(g, 104, walk, bz - 5, 151, walk + 1, bz - 2, AIR, floor=STONE)               # walk through towers and gallery
    parapet_rect(g, 116, bz - 9, 139, bz + 2, gtop)
    g.fill(117, gtop, bz - 8, 138, gtop + 4, bz + 1, AIR)
    g.fill(117, gtop - 1, bz - 8, 138, gtop - 1, bz + 1, STONE)
    g.fill(118, gtop - 1, bz - 6, 122, gtop - 1, bz - 2, AIR)
    for x in (121, 127, 134):
        g.mark("crossbowman", x, gtop, bz)
    from parts import gate_face_posts, murder_holes
    gate_face_posts(g, "z", (bz + 1, bz - 8), 127, gtop)
    murder_holes(g, "z", bz - 5, bz - 2, 127, GL + 11, GL + 12, side=1)
    g.wall_banner(121, GL + 16, bz + 2, "south"); g.wall_banner(134, GL + 16, bz + 2, "south")
    g.mark("captain", 127, GL, bz - 14)
    # the quay road runs along the foot of the wall and through vaulted ways under the river-gate towers
    g.fill(14, GL - 1, 217, 241, GL - 1, 218, ROAD)
    for (x0, x1) in ((104, 123), (132, 151), (47, 62), (191, 206)):
        g.fill(x0, GL, 217, x1, GL + 3, 220 if x0 > 100 and x0 < 160 else 218, AIR)
        g.fill(x0, GL - 1, 217, x1, GL - 1, 218, ROAD)
        g.fill(x0, GL + 4, 217, x1, GL + 4, 218, STONE)
        for x in range(x0 + 2, x1, 5):
            g.set(x, GL + 3, 216, "minecraft:lantern[hanging=true]") if g.get(x, GL + 4, 216) != AIR else None
    g.fill(104, GL, 217, 123, GL + 2, 218, AIR); g.fill(132, GL, 217, 151, GL + 2, 218, AIR)

    rng.shuffle(doors)
    for d in doors[:12]:
        x, z = d[0], d[-1]
        g.mark("house", x, GL if z > 114 or x < 64 or x > 191 else GL2, z)
    return g, {"name": "great_city", "tier": 4, "GL": GL, "levels": (GL2, GL3)}
