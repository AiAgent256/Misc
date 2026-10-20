"""More kinds of place: an overgrown temple of the old world, moated castles, a chateau with its gardens."""
import numpy as np

from grid import Grid, Mix, disk, ring, rect, rect_ring, FACING_VEC, OPPOSITE
from parts import STONE, FOUND, PAVE, ROAD, STONE_STAIR, SLAB_CAP

LOOT = "stormhold:chests/"

# the temple's stone: dressed, worn, and the moss getting into it. Every block here has a theme mapping, so
# the temple comes out sandstone in Dorne and white in the Reach like the castles round it.
TEMPLE = Mix(("minecraft:stone_bricks", 45), ("minecraft:cracked_stone_bricks", 20), ("minecraft:mossy_stone_bricks", 20),
             ("minecraft:polished_andesite", 15))
STEP = Mix(("minecraft:stone_bricks", 40), ("minecraft:mossy_stone_bricks", 25), ("minecraft:cracked_stone_bricks", 20),
           ("minecraft:mossy_cobblestone", 15))
SHAFT = Mix(("minecraft:polished_andesite", 55), ("minecraft:stone_bricks", 25), ("minecraft:mossy_stone_bricks", 12),
            ("minecraft:cracked_stone_bricks", 8))
GROUND = Mix(("minecraft:grass_block", 55), ("minecraft:moss_block", 15), ("minecraft:coarse_dirt", 15),
             ("minecraft:rooted_dirt", 5), ("minecraft:mossy_cobblestone", 10))
LEAVES = Mix(("minecraft:oak_leaves[persistent=true]", 50), ("minecraft:azalea_leaves[persistent=true]", 30),
             ("minecraft:flowering_azalea_leaves[persistent=true]", 20))
SIDES = ("north", "south", "east", "west")


def vine(g, x, y, z, toward):
    """A vine on the face of the block {toward} of (x, y, z)."""
    props = {f: "false" for f in ("east", "north", "south", "up", "west")}
    props[toward] = "true"
    g.set(x, y, z, "minecraft:vine[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]")


def creep(g, x0, z0, x1, z1, y0, y1, rng, chance=0.25, length=(2, 5)):
    """Ivy down the open faces of everything solid in a box: on air cells beside stone, hanging from the top."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y1, y0 - 1, -1):
                if g.get(x, y, z) != "minecraft:air":
                    continue
                for f in SIDES:
                    dx, dz = FACING_VEC[f]
                    if not g.is_solid(x + dx, y, z + dz) or "stairs" in g.get(x + dx, y, z + dz) or "slab" in g.get(x + dx, y, z + dz):
                        continue
                    if g.is_solid(x + dx, y + 1, z + dz) and not g.is_solid(x, y + 1, z) and rng.random() < chance:
                        n = int(rng.integers(length[0], length[1] + 1))
                        for k in range(n):
                            if g.get(x, y - k, z) != "minecraft:air" or not g.is_solid(x + dx, y - k, z + dz):
                                break
                            if g.is_solid(x, y - k - 1, z) and k == 0:
                                break
                            vine(g, x, y - k, z, f)
                        break


def small_tree(g, x, z, y, rng, h=None):
    """An oak or birch come up through the stones."""
    h = h or int(rng.integers(4, 7))
    log = "minecraft:oak_log[axis=y]" if rng.random() < 0.6 else "minecraft:birch_log[axis=y]"
    g.fill(x, y, z, x, y + h - 1, z, log)
    g.fill_mask(disk(g, x + 0.5, z + 0.5, 2.6) & (g.a[:, :, y + h - 2] == 1), y + h - 2, y + h - 2, LEAVES)
    g.fill_mask(disk(g, x + 0.5, z + 0.5, 2.2) & (g.a[:, :, y + h - 1] == 1), y + h - 1, y + h - 1, LEAVES)
    g.fill_mask(disk(g, x + 0.5, z + 0.5, 1.4) & (g.a[:, :, y + h] == 1), y + h, y + h, LEAVES)
    g.set(x, y + h - 1, z, log)


def column(g, cx, cz, y, height, state, rng):
    """A column on a three-square base: a plus-shaped shaft, a capital on the ones still whole.
    state: 'whole' (to full height with a capital), 'snapped' (broken off part way), 'stump' (base and a drum)."""
    g.fill(cx - 1, y, cz - 1, cx + 1, y, cz + 1, "minecraft:polished_andesite")
    top = {"whole": height, "snapped": int(rng.integers(2, max(3, height - 2))), "stump": 1}[state]
    for k in range(1, top + 1):
        yy = y + k
        g.set(cx, yy, cz, "minecraft:stone_bricks")
        for f in SIDES:
            dx, dz = FACING_VEC[f]
            g.set(cx + dx, yy, cz + dz, SHAFT)
    if state == "whole":
        cy = y + height + 1
        g.fill(cx - 1, cy, cz - 1, cx + 1, cy, cz + 1, "minecraft:chiseled_stone_bricks")
        for f in SIDES:
            dx, dz = FACING_VEC[f]
            g.stair(cx + dx * 2, cy, cz + dz * 2, STONE_STAIR, OPPOSITE[f], half="top")
        return cy
    # the break: a jagged top, one arm a block lower, moss on what's left
    f = SIDES[int(rng.integers(0, 4))]
    dx, dz = FACING_VEC[f]
    g.set(cx + dx, y + top, cz + dz, "minecraft:air")
    g.set(cx, y + top + 1, cz, "minecraft:moss_carpet") if top > 1 else None
    return None


def fallen_drums(g, x, z, y, axis, n, rng):
    """A column come down: its drums lying in a line, one rolled aside."""
    for k in range(n):
        xx, zz = (x + k, z) if axis == "x" else (x, z + k)
        if g.get(xx, y, zz) != "minecraft:air" or not g.is_solid(xx, y - 1, zz):
            break
        g.set(xx, y, zz, Mix(("minecraft:polished_andesite", 50), ("minecraft:stone_bricks", 30), ("minecraft:mossy_stone_bricks", 20)))
        if rng.random() < 0.3 and g.get(xx, y + 1, zz) == "minecraft:air":
            g.set(xx, y + 1, zz, "minecraft:moss_carpet")


def temple_ruin(seed=61):
    """An overgrown temple of the old world: a stepped platform, a colonnade half fallen, the cella open to
    the sky with its altar, and a pool before the steps with stones to cross it. No one holds it; there may
    be something under the altar."""
    W, D, H, GL = 52, 52, 30, 4
    g = Grid(W, H, D, seed)
    rng = g.rng
    xs, zs = g.xz()
    # the ground: earth and moss, a little rough
    g.fill(0, 0, 0, W - 1, GL - 2, D - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, D - 1, GROUND)
    g.fill(0, GL, 0, W - 1, H - 1, D - 1, "minecraft:air")
    # the pool before the temple, south of it, an irregular basin two deep with a stony rim
    pool = (((xs - 25.5) / 17.0) ** 2 + ((zs - 42.0) / 6.5) ** 2) <= 1.0
    rim = (((xs - 25.5) / 18.5) ** 2 + ((zs - 42.0) / 8.0) ** 2) <= 1.0
    g.fill_mask(rim & ~pool, GL - 1, GL - 1, Mix(("minecraft:mossy_cobblestone", 35), ("minecraft:stone", 25),
                                                  ("minecraft:gravel", 20), ("minecraft:moss_block", 20)))
    g.fill_mask(pool, GL - 4, GL - 4, Mix(("minecraft:gravel", 40), ("minecraft:sand", 30), ("minecraft:clay", 30)))
    g.fill_mask(pool, GL - 3, GL - 1, "minecraft:water")
    # stepping stones straight across from the temple's stair to the far bank, two wide
    for z in range(35, 50, 2):
        for x in (24, 25):
            if g.get(x, GL - 1, z) == "minecraft:water":
                g.set(x, GL - 1, z, Mix(("minecraft:mossy_stone_bricks", 50), ("minecraft:stone_bricks", 30), ("minecraft:mossy_cobblestone", 20)))
                g.set(x, GL - 2, z, "minecraft:stone")
                g.set(x, GL - 3, z, "minecraft:stone")
            z1 = z + 1
            if g.get(x, GL - 1, z1) == "minecraft:water":
                g.set(x, GL - 1, z1, "minecraft:stone_brick_slab[type=top,waterlogged=true]")
                g.set(x, GL - 2, z1, "minecraft:stone"); g.set(x, GL - 3, z1, "minecraft:stone")
    for (x, z) in ((14, 40), (18, 46), (33, 39), (37, 44), (30, 47), (12, 43)):
        if g.get(x, GL - 1, z) == "minecraft:water":
            g.set(x, GL, z, "minecraft:lily_pad")
    # the platform: three steps up all round, a broad stair on the south front
    px0, pz0, px1, pz1 = 8, 4, 43, 30
    for k in range(3):
        g.fill(px0 + k, 0, pz0 + k, px1 - k, GL + k, pz1 - k, STEP)
    FL = GL + 3                                            # the temple floor's standing level
    g.fill(px0 + 3, FL - 1, pz0 + 3, px1 - 3, FL - 1, pz1 - 3, Mix(("minecraft:stone_bricks", 45), ("minecraft:polished_andesite", 20),
                                                                     ("minecraft:mossy_stone_bricks", 20), ("minecraft:cracked_stone_bricks", 15)))
    for x in range(17, 35):
        for k in range(3):
            g.stair(x, GL + k, pz1 - k, STONE_STAIR, "north")
    # the colonnade: columns every five round the top of the platform
    cy = FL - 1
    cols = []
    for x in range(13, 40, 5):
        cols.append((x, pz0 + 5)); cols.append((x, pz1 - 5))
    for z in range(pz0 + 10, pz1 - 5, 5):
        cols.append((13, z)); cols.append((38, z))
    HT = 9
    whole = []
    states = {}
    for (x, z) in cols:
        r = rng.random()
        st = "whole" if r < 0.5 else "snapped" if r < 0.8 else "stump"
        states[(x, z)] = st
        if column(g, x, z, cy, HT, st, rng) is not None:
            whole.append((x, z))
    # the entablature, still lying on runs of whole columns side by side along the front and back
    top = cy + HT + 2
    for row in (pz0 + 5, pz1 - 5):
        xs_row = sorted(x for (x, z) in whole if z == row)
        for a, b in zip(xs_row, xs_row[1:]):
            if b - a == 5 and rng.random() < 0.75:
                g.fill(a - 1, top, row - 1, b + 1, top, row + 1, TEMPLE)
                g.fill(a - 1, top + 1, row - 1, b + 1, top + 1, row + 1, "minecraft:stone_brick_slab[type=bottom]")
                if rng.random() < 0.5:
                    g.set(a + 2, top + 1, row, "minecraft:moss_carpet")
    for col in (13, 38):
        zs_col = sorted(z for (x, z) in whole if x == col)
        for a, b in zip(zs_col, zs_col[1:]):
            if b - a == 5 and rng.random() < 0.6:
                g.fill(col - 1, top, a - 1, col + 1, top, b + 1, TEMPLE)
    # fallen columns: their drums down on the floor and the steps
    for (x, z), st in states.items():
        if st == "stump" and rng.random() < 0.8:
            axis = "x" if z in (pz0 + 5, pz1 - 5) else "z"
            if axis == "x":
                fallen_drums(g, x + 2, z + (2 if z > 15 else -2) * 0, FL, "x", int(rng.integers(3, 6)), rng)
            else:
                fallen_drums(g, x + (2 if x < 25 else -2), z + 2, FL, "z", int(rng.integers(3, 6)), rng)
    # the cella: its walls standing to different heights, open to the sky, the altar at the back
    cx0, cz0, cx1, cz1 = 19, 13, 32, 22
    for x in range(cx0, cx1 + 1):
        for z in range(cz0, cz1 + 1):
            edge = x in (cx0, cx1) or z in (cz0, cz1)
            if not edge:
                continue
            if z == cz1 and 24 <= x <= 27:
                continue                                   # the doorway, four wide
            hgt = int(max(1, 6 - abs(np.sin(x * 0.9 + z * 0.4)) * 5 - (2 if rng.random() < 0.25 else 0)))
            g.fill(x, FL, z, x, FL + hgt - 1, z, TEMPLE)
    g.fill(cx0 + 1, FL - 1, cz0 + 1, cx1 - 1, FL - 1, cz1 - 1, Mix(("minecraft:polished_andesite", 40), ("minecraft:mossy_stone_bricks", 30),
                                                                     ("minecraft:cracked_stone_bricks", 30)))
    # the altar, and what the old priests hid under it
    g.fill(24, FL, cz0 + 2, 27, FL, cz0 + 3, "minecraft:chiseled_stone_bricks")
    g.fill(24, FL + 1, cz0 + 2, 27, FL + 1, cz0 + 3, "minecraft:stone_brick_slab[type=bottom]")
    g.set(25, FL + 2, cz0 + 2, "minecraft:candle[candles=3,lit=false]")
    g.chest(25, FL - 2, cz0 + 2, "south", LOOT + "castle_treasury")
    g.chest(26, FL - 2, cz0 + 2, "south", LOOT + "holdfast_treasury")
    # a broken corner of the cella with a cracked urn and a chest the robbers missed
    g.chest(cx1 - 1, FL, cz0 + 1, "west", LOOT + "castle_store")
    g.set(cx0 + 1, FL, cz1 - 1, "minecraft:decorated_pot[facing=north]")
    # the old world's growth: trees through the floor, moss and ferns, ivy on what stands
    for (x, z) in ((16, 9), (35, 25), (30, 10), (21, 26)):
        if g.get(x, FL, z) == "minecraft:air":
            g.set(x, FL - 1, z, "minecraft:rooted_dirt")
            small_tree(g, x, z, FL, rng)
    for (x, z) in ((5, 8), (46, 12), (4, 30), (47, 33), (44, 48), (6, 48)):
        g.set(x, GL - 1, z, "minecraft:grass_block")
        small_tree(g, x, z, GL, rng, h=int(rng.integers(5, 8)))
    for x in range(1, W - 1):
        for z in range(1, D - 1):
            for y in (FL, GL):
                if g.get(x, y, z) != "minecraft:air" or not g.is_solid(x, y - 1, z):
                    continue
                below = g.get(x, y - 1, z)
                if "stairs" in below or "slab" in below or "water" in below:
                    continue
                r = rng.random()
                if y == GL and "grass" in below:
                    if r < 0.22:
                        g.set(x, y, z, "minecraft:grass")
                    elif r < 0.30:
                        g.set(x, y, z, "minecraft:fern")
                    elif r < 0.33:
                        g.set(x, y, z, Mix(("minecraft:poppy", 40), ("minecraft:oxeye_daisy", 30), ("minecraft:azure_bluet", 30)))
                elif y == FL and r < 0.12:
                    g.set(x, y, z, "minecraft:moss_carpet")
                elif y == FL and r < 0.16 and "moss" in below:
                    g.set(x, y, z, "minecraft:fern")
    creep(g, px0 - 1, pz0 - 1, px1 + 1, pz1 + 1, GL, top + 1, rng, chance=0.22)
    # where the game's people stand when they come: the steps' foot, the altar, the middle of the floor
    g.mark("gate", 25, GL, pz1 + 2)
    g.mark("rally", 25, GL, pz1 + 3)
    g.mark("lord", 25, FL, cz0 + 5)
    g.mark("war_table", 22, FL, cz0 + 5)
    g.mark("banner_stand", 29, FL, cz0 + 5)
    for (x, y, z) in list(g.markers["gate"]) + list(g.markers["rally"]) + list(g.markers["lord"]) + \
            list(g.markers["war_table"]) + list(g.markers["banner_stand"]):
        g.fill(x, y, z, x, y + 1, z, "minecraft:air")
    return g, {"name": "temple_ruin", "tier": 0, "GL": GL, "kind": "landmark"}


# ---------------------------------------------------------------- moated castles

MOAT_LINING = Mix(("minecraft:stone_bricks", 50), ("minecraft:mossy_stone_bricks", 25), ("minecraft:cobblestone", 25))


def _grow(g, M):
    """The same castle on a grid M wider every side: blocks, markers, banners, gate planes and the generator's notes."""
    g2 = Grid(g.X + 2 * M, g.Y, g.Z + 2 * M, 1)
    g2.palette = list(g.palette)
    g2.index = dict(g.index)
    g2.a[M:M + g.X, M:M + g.Z, :] = g.a
    g2.rng = g.rng
    g2.block_nbt = {(x + M, y, z + M): v for (x, y, z), v in g.block_nbt.items()}
    for role, pts in g.markers.items():
        g2.markers[role] = [(x + M, y, z + M) for (x, y, z) in pts]
    g2.banners = [(x + M, y, z + M) for (x, y, z) in g.banners]
    if hasattr(g, "gate_planes"):
        g2.gate_planes = [[p[0] + M, p[1], p[2] + M, p[3] + M, p[4], p[5] + M] for p in g.gate_planes]
    for attr in ("protected_xz", "spiral_cells"):
        if hasattr(g, attr):
            setattr(g2, attr, {(x + M, z + M) for (x, z) in getattr(g, attr)})
    for attr in ("narrow_spirals", "last_cap"):
        if hasattr(g, attr):
            setattr(g2, attr, getattr(g, attr))
    return g2


def moated(fn, style="cone", seed_shift=5, margin=10):
    """The castle {fn} with a water moat round it: a bank, a stone-lined moat four wide, an outer bank, and a
    causeway with low walls over it from the gate. The castle inside is the same plan, built from another seed
    so its yard and rooms are dressed differently."""
    def build(seed=None):
        g0, meta = fn(seed=(fn.__defaults__[0] + seed_shift) if seed is None else seed)
        M = margin
        GL = meta["GL"]
        g = _grow(g0, M)
        X, Z = g.X, g.Z
        xs, zs = g.xz()
        d = np.maximum(np.maximum(M - xs, xs - (M + g0.X - 1)), np.maximum(M - zs, zs - (M + g0.Z - 1)))
        margin_m = d >= 1
        # the land round it
        g.fill_mask(margin_m, 0, GL - 2, FOUND)
        g.fill_mask(margin_m, GL - 1, GL - 1, Mix(("minecraft:grass_block", 80), ("minecraft:coarse_dirt", 10), ("minecraft:moss_block", 10)))
        g.fill_mask(margin_m, GL, GL + 6, "minecraft:air")
        # the moat: lined each side, three deep, its water a block under the banks
        lining = margin_m & ((d == 2) | (d == 7))
        water = margin_m & (d >= 3) & (d <= 6)
        g.fill_mask(lining, GL - 6, GL - 1, MOAT_LINING)
        g.fill_mask(water, GL - 6, GL - 6, Mix(("minecraft:gravel", 40), ("minecraft:clay", 35), ("minecraft:sand", 25)))
        g.fill_mask(water, GL - 5, GL - 2, "minecraft:water")
        g.fill_mask(water, GL - 1, GL - 1, "minecraft:air")
        rng = g.rng
        for _ in range(30):
            x, z = int(rng.integers(0, X)), int(rng.integers(0, Z))
            if g.get(x, GL - 2, z) == "minecraft:water" and g.get(x, GL - 1, z) == "minecraft:air":
                g.set(x, GL - 1, z, "minecraft:lily_pad")
        # causeways: one from the gate, as wide as the road through it, and one from any other way out to the edge
        gx, gy, gz = g.markers["gate"][0]
        box = (M, M + g0.X - 1, M, M + g0.Z - 1)
        side = min((("west", gx - box[0]), ("east", box[1] - gx), ("north", gz - box[2]), ("south", box[3] - gz)), key=lambda t: t[1])[0]
        along_gate = gx if side in ("north", "south") else gz
        done = set()
        for sd in ("north", "south", "west", "east"):
            for (ra, rb) in _openings(g, sd, box, gy):
                if sd == side and ra - 1 <= along_gate <= rb + 1:
                    continue
                if 2 <= rb - ra + 1 <= 10:
                    _causeway(g, sd, ra, rb, gy, box)
                    done.add(sd)
        ra, rb = along_gate, along_gate
        runs = [r for r in _openings(g, side, box, gy) if r[0] - 1 <= along_gate <= r[1] + 1]
        if runs:
            ra, rb = runs[0]
        # as wide as the way through the gate, but no wider than a road: the edge may be open ground all along
        ra, rb = max(min(ra, along_gate - 1), along_gate - 3), min(max(rb, along_gate + 2), along_gate + 4)
        _causeway(g, side, ra, rb, gy, box)
        meta = dict(meta)
        meta["name"] = fn.__name__ + "_moat"
        return g, meta
    build.__name__ = fn.__name__ + "_moat"
    build.__defaults__ = (fn.__defaults__[0] + seed_shift,)
    build.cap_style = style
    return build


def _openings(g, side, box, gy):
    """Runs of open floor along the castle's outer edge on one side: (first, last) along the edge."""
    x0, x1, z0, z1 = box
    if side in ("north", "south"):
        ez = z0 if side == "north" else z1
        cells = [(x, ez) for x in range(x0, x1 + 1)]
        key = lambda c: c[0]
    else:
        ex = x0 if side == "west" else x1
        cells = [(ex, z) for z in range(z0, z1 + 1)]
        key = lambda c: c[1]
    runs, cur = [], None
    for (x, z) in cells:
        open_ = g.get(x, gy, z) == "minecraft:air" and g.get(x, gy + 1, z) == "minecraft:air" and g.is_solid(x, gy - 1, z)
        if open_:
            cur = [key((x, z)), key((x, z))] if cur is None else [cur[0], key((x, z))]
        elif cur is not None:
            runs.append(tuple(cur))
            cur = None
    if cur is not None:
        runs.append(tuple(cur))
    return runs


def _causeway(g, side, ra, rb, gy, box):
    """A stone causeway with low walls from the castle's edge over the moat to the land, lanterns at the ends."""
    x0, x1, z0, z1 = box
    if side in ("north", "south"):
        span = range(0, z0) if side == "north" else range(z1 + 1, g.Z)
        for z in span:
            g.fill(ra - 1, 0, z, rb + 1, gy - 2, z, FOUND)
            g.fill(ra, gy - 1, z, rb, gy - 1, z, ROAD)
            g.fill(ra, gy, z, rb, gy + 3, z, "minecraft:air")
            for x in (ra - 1, rb + 1):
                g.set(x, gy - 1, z, MOAT_LINING)
                g.set(x, gy, z, "minecraft:stone_brick_wall")
        for z in ((z0 - 1, 0) if side == "north" else (z1 + 1, g.Z - 1)):
            for x in (ra - 1, rb + 1):
                g.set(x, gy + 1, z, "minecraft:lantern[hanging=false]")
    else:
        span = range(0, x0) if side == "west" else range(x1 + 1, g.X)
        for x in span:
            g.fill(x, 0, ra - 1, x, gy - 2, rb + 1, FOUND)
            g.fill(x, gy - 1, ra, x, gy - 1, rb, ROAD)
            g.fill(x, gy, ra, x, gy + 3, rb, "minecraft:air")
            for z in (ra - 1, rb + 1):
                g.set(x, gy - 1, z, MOAT_LINING)
                g.set(x, gy, z, "minecraft:stone_brick_wall")
        for x in ((x0 - 1, 0) if side == "west" else (x1 + 1, g.X - 1)):
            for z in (ra - 1, rb + 1):
                g.set(x, gy + 1, z, "minecraft:lantern[hanging=false]")


# ---------------------------------------------------------------- the chateau

from parts import (DARK, ROOF, ROOF_STAIR, DARK_STAIR, RAIL, PLANK, DARK_PLANK, GLASS, FLOOR_STONE,
                   curtain, posts_on_walk, round_tower, square_tower, pointed_arch, building, lamp_post, wall_stair,
                   cap_roof)
from grid import gable_roof, dogleg_stair, cone_roof

HEDGE = "minecraft:oak_leaves[persistent=true]"
TOPIARY = Mix(("minecraft:oak_leaves[persistent=true]", 60), ("minecraft:azalea_leaves[persistent=true]", 40))
GRAVEL_PATH = Mix(("minecraft:gravel", 55), ("minecraft:dirt_path", 25), ("minecraft:coarse_dirt", 20))
BLOOMS = (("minecraft:red_tulip", "minecraft:white_tulip"), ("minecraft:allium", "minecraft:oxeye_daisy"),
          ("minecraft:poppy", "minecraft:cornflower"), ("minecraft:pink_tulip", "minecraft:azure_bluet"))


def _portcullis(g, x0, x1, y0, height, z):
    g.__dict__.setdefault("gate_planes", []).append([x0, y0, z, x1, y0 + height - 1, z])
    for y in range(y0, y0 + height):
        for x in range(x0, x1 + 1):
            if g.get(x, y, z) == "minecraft:air":
                g.set(x, y, z, "stormhold:portcullis")


def fountain(g, cx, cz, y, r=5):
    """A round basin with a pedestal and a bowl on top, water standing in both."""
    basin = disk(g, cx + 0.5, cz + 0.5, r)
    rim = disk(g, cx + 0.5, cz + 0.5, r + 1) & ~basin
    g.fill_mask(basin | rim, y - 3, y - 2, "minecraft:stone_bricks")
    g.fill_mask(basin, y - 1, y - 1, "minecraft:water")
    g.fill_mask(rim, y - 1, y - 1, "minecraft:polished_andesite")
    g.fill_mask(rim, y, y, "minecraft:stone_brick_slab[type=bottom]")
    g.fill(cx, y - 1, cz, cx + 1, y + 2, cz + 1, "minecraft:stone_bricks")
    g.fill(cx - 1, y + 3, cz - 1, cx + 2, y + 3, cz + 2, "minecraft:polished_andesite")
    for x in range(cx - 1, cx + 3):
        for z in range(cz - 1, cz + 3):
            if x in (cx - 1, cx + 2) or z in (cz - 1, cz + 2):
                g.set(x, y + 4, z, "minecraft:stone_brick_wall")
            else:
                g.set(x, y + 4, z, "minecraft:water")
    g.set(cx, y + 5, cz, "minecraft:lantern[hanging=false]") if False else None


def parterre(g, x0, z0, x1, z1, y, rng):
    """A formal bed: a clipped hedge round it with a gap two wide in the middle of each side, rows of flowers,
    a topiary cone in the middle. y is the standing level (the bed's soil is y-1)."""
    g.fill(x0, y - 1, z0, x1, y - 1, z1, "minecraft:grass_block")
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            gap = (x in (cx, cx + 1) and z in (z0, z1)) or (z in (cz, cz + 1) and x in (x0, x1))
            if edge and not gap:
                g.set(x, y, z, HEDGE)
    a, b = BLOOMS[int(rng.integers(0, len(BLOOMS)))]
    for x in range(x0 + 2, x1 - 1):
        for z in range(z0 + 2, z1 - 1):
            if abs(x - cx - 0.5) <= 1.5 and abs(z - cz - 0.5) <= 1.5:
                continue
            if (x + z) % 3 == 0:
                continue                                   # room to walk the rows
            g.set(x, y, z, a if (x // 2 + z) % 2 else b)
    for k in range(3):
        g.fill_mask(disk(g, cx + 1, cz + 1, 1.6 - k * 0.5), y + k, y + k, TOPIARY)
    g.set(cx, y + 3, cz, TOPIARY)


def flower_boxes(g, x0, z0, x1, z1, y0, y1):
    """A pot of flowers on every window sill of a building's walls (the sill stair under each pane)."""
    pots = ("minecraft:potted_red_tulip", "minecraft:potted_poppy", "minecraft:potted_allium", "minecraft:potted_cornflower")
    k = 0
    for y in range(y0, y1 + 1):
        for x in range(x0 - 1, x1 + 2):
            for z in range(z0 - 1, z1 + 2):
                s = g.get(x, y, z)
                if "stairs" in s and "half=top" in s and g.get(x, y + 1, z) == "minecraft:air":
                    inside = x0 <= x <= x1 and z0 <= z <= z1
                    if inside:
                        continue
                    g.set(x, y + 1, z, pots[k % len(pots)])
                    k += 1


def great_castle_d(seed=41):
    """Tier 3: a chateau. A light curtain with spired corner towers and a gatehouse; inside, a forecourt with a
    fountain between two formal gardens of hedged beds, timber-framed barracks and stables along the walls, and a
    grand stair up to a terrace where the house itself stands: three storeys under a steep roof with dormers,
    a spired tower at each corner and a tall one over the door."""
    W, H, GL = 128, 84, 6
    TL = GL + 6                     # the terrace's standing level
    g = Grid(W, H, W, seed)
    rng = g.rng
    xs, zs = g.xz()
    a, b = 6, W - 7
    thick, walk = 4, GL + 12
    c = W // 2
    inside = (xs > a) & (xs < b) & (zs > a) & (zs < b)
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill(0, GL - 1, 0, W - 1, GL - 1, W - 1, Mix(("minecraft:grass_block", 85), ("minecraft:coarse_dirt", 15)))
    # air to clear the ground the house stands in; higher up the game clears the land itself (the footprint)
    g.fill(0, GL, 0, W - 1, GL + 14, W - 1, "minecraft:air")
    # ---- the terrace the house stands on, a balustrade round its edge ----
    tx0, tz0, tx1, tz1 = 20, 10, 107, 63
    g.fill(tx0, 0, tz0, tx1, TL - 2, tz1, FOUND)
    g.fill(tx0, TL - 1, tz0, tx1, TL - 1, tz1, Mix(("minecraft:stone_bricks", 40), ("minecraft:polished_andesite", 35),
                                                   ("minecraft:andesite", 25)))
    g.fill(tx0, GL, tz1, tx1, TL - 1, tz1, "minecraft:stone_bricks")
    for x in range(tx0, tx1 + 1):
        if not (52 <= x <= 75):
            g.set(x, TL, tz1, RAIL)
    for z in range(tz0, tz1 + 1):
        g.set(tx0, TL, z, RAIL); g.set(tx1, TL, z, RAIL)
    for x in range(tx0, tx1 + 1):
        g.set(x, TL, tz0, RAIL)
    # the grand stair: twenty wide, from the forecourt up to the terrace, urns at its foot
    for k in range(TL - GL):
        z = tz1 + (TL - GL) - k
        g.fill(54, GL, z, 73, GL + k - 1, z, "minecraft:stone_bricks") if k > 0 else None
        for x in range(54, 74):
            g.stair(x, GL + k, z, STONE_STAIR, "north")
    for x in (53, 74):
        for k in range(TL - GL):
            z = tz1 + (TL - GL) - k
            g.fill(x, GL, z, x, GL + k, z, "minecraft:stone_bricks")
            g.set(x, GL + k + 1, z, RAIL)
    for x in (52, 75):
        g.fill(x, GL, tz1 + 7, x, GL + 1, tz1 + 7, "minecraft:polished_andesite")
        g.set(x, GL + 2, tz1 + 7, "minecraft:potted_azalea_bush")
    # ---- the curtain, its spired corner towers and the gatehouse ----
    d, along, out = curtain(g, a, b, thick, walk, GL, gallery=False, banners_every=16)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=6, offset=2)
    ttop = walk + 8
    for (cx, cz) in ((a + 3, a + 3), (b - 3, a + 3), (a + 3, b - 3), (b - 3, b - 3)):
        sx = 1 if cx < c else -1
        sz = 1 if cz < c else -1
        round_tower(g, cx, cz, 7, ttop, GL, floors=(GL + 7, walk - 1), spiral=True, cone=True,
                    doors=((sx, 0, walk), (0, sz, walk), (sx, sz, GL)), wide=True)
    wall_stair(g, a + thick, 40, GL, walk, "z", "west")
    wall_stair(g, b - thick, 40, GL, walk, "z", "east")
    gx0, gx1 = c - 7, c + 6
    g.fill(gx0, 0, b - 4, gx1, walk + 3, b + 4, STONE)
    pointed_arch(g, c - 3, c + 2, GL, 8, b - 4, b + 4)
    g.fill(c - 3, GL - 1, b - 6, c + 2, GL - 1, W - 1, ROAD)
    _portcullis(g, c - 3, c + 2, GL, 8, b + 3)
    _portcullis(g, c - 3, c + 2, GL, 8, b - 3)  # a second at the inner end: break the first and you're in a box under the walls
    g.fill(c - 3, GL - 1, b + 5, c + 2, GL - 1, W - 1, "minecraft:spruce_planks")
    for x in (c - 4, c + 3):
        g.fill(x, GL - 1, b + 5, x, GL - 1, W - 1, "minecraft:dark_oak_log[axis=z]")
        g.fill(x, GL, b + 5, x, GL, W - 1, "minecraft:dark_oak_fence")
    for x in range(gx0 - 1, gx1 + 2):
        for z in (b - 5, b + 5):
            g.set(x, walk + 3, z, STONE)
            if x % 4 < 2:
                g.set(x, walk + 4, z, STONE); g.set(x, walk + 5, z, SLAB_CAP)
    for z in range(b - 5, b + 6):
        for x in (gx0 - 1, gx1 + 1):
            g.set(x, walk + 3, z, STONE)
            if z % 4 < 2:
                g.set(x, walk + 4, z, STONE); g.set(x, walk + 5, z, SLAB_CAP)
    g.fill(gx0, walk + 3, b - 4, gx1, walk + 6, b + 4, "minecraft:air")
    g.fill(gx0, walk + 2, b - 4, gx1, walk + 2, b + 4, STONE)
    for k in range(3):
        for (x, f) in ((gx0 + k, "east"), (gx1 - k, "west")):
            for zz in (b - 1, b - 2):
                g.stair(x, walk + k, zz, STONE_STAIR, f)
                g.fill(x, walk + k + 1, zz, x, walk + k + 3, zz, "minecraft:air")
    g.fill(gx0 + 3, walk + 3, b - 2, gx1 - 3, walk + 5, b - 1, "minecraft:air")
    g.fill(gx0, walk, b - 4, gx0 + 3, walk + 1, b - 4, "minecraft:air")
    g.fill(gx1 - 3, walk, b - 4, gx1, walk + 1, b - 4, "minecraft:air")
    for x in (gx0 + 2, gx0 + 6, gx0 + 10):
        g.mark("crossbowman", x, walk + 3, b + 2)
    # two slim spired turrets on the gatehouse's outer corners
    for tx in (gx0, gx1):
        g.fill_mask(disk(g, tx + 0.5, b + 4.5, 2.2), walk + 3, walk + 7, STONE)
        cone_roof(g, tx, b + 4, 2, walk + 8, ROOF, height_factor=3.0)
    g.wall_banner(c - 5, GL + 10, b + 5, "south"); g.wall_banner(c + 4, GL + 10, b + 5, "south")
    g.mark("gate", c - 1, GL, W - 2)
    g.mark("captain", c - 1, GL, b - 8)
    # ---- the forecourt: an avenue from the gate to the stair, the fountain in a round plaza ----
    g.fill(c - 6, GL - 1, tz1 + 7, c + 5, GL - 1, b - 5, ROAD)
    plaza = disk(g, c - 0.5, 92.5, 10)
    g.fill_mask(plaza, GL - 1, GL - 1, Mix(("minecraft:polished_andesite", 40), ("minecraft:stone_bricks", 40), ("minecraft:andesite", 20)))
    fountain(g, c - 1, 92, GL, r=5)
    for (x, z) in ((c - 11, 84), (c + 10, 84), (c - 11, 101), (c + 10, 101)):
        lamp_post(g, x, z, GL)
    for z in range(tz1 + 9, b - 6, 6):
        if not (80 <= z <= 104):
            g.mark("knight", c - 5, GL, z); g.mark("knight", c + 4, GL, z)
    g.mark("rally", c - 1, GL, 106)
    # ---- the gardens either side: four hedged beds each, gravel walks three wide between ----
    for (gx_0, gx_1) in ((23, 51), (76, 104)):
        g.fill(gx_0, GL - 1, tz1 + 4, gx_1, GL - 1, b - 8, GRAVEL_PATH)
        mx = (gx_0 + gx_1) // 2
        for (bx0, bx1) in ((gx_0 + 2, mx - 2), (mx + 2, gx_1 - 2)):
            for (bz0, bz1) in ((tz1 + 6, 87), (93, b - 10)):
                parterre(g, bx0, bz0, bx1, bz1, GL, rng)
        for (x, z) in ((mx, 90), (mx - 1, 90)):
            pass
        g.fill(mx - 1, GL, 89, mx, GL, 90, "minecraft:polished_andesite")
        g.set(mx - 1, GL + 1, 89, "minecraft:potted_flowering_azalea_bush")
        for z in (tz1 + 5, 90, b - 9):
            g.mark("knight", gx_0 + 1, GL, z); g.mark("knight", gx_1 - 1, GL, z)
        for z in range(tz1 + 8, b - 9, 9):
            g.stair(mx + 2, GL, z, "minecraft:spruce_stairs", "east") if g.get(mx + 2, GL, z) == "minecraft:air" else None
    # ---- timber-framed barracks and stables along the side walls ----
    building(g, 10, 66, 19, 112, GL, 9, "z", door_cells=((19, 76), (19, 100)), upper_floor=GL + 4, chimney=(13, 88), timber=True)
    for z in range(68, 111, 3):
        if z in (75, 76, 77, 99, 100, 101):
            continue
        g.bed(11, GL, z, "west", "brown"); g.bed(11, GL + 5, z, "west", "brown")
    g.barrel(18, GL, 68, LOOT + "castle_barracks", facing="west"); g.barrel(18, GL, 110, LOOT + "castle_barracks", facing="west")
    flower_boxes(g, 10, 66, 19, 112, GL, GL + 8)
    building(g, 108, 66, 117, 112, GL, 7, "z", door_cells=((108, 80), (108, 98)), chimney=(114, 88), timber=True)
    for z in range(70, 110, 5):
        g.fill(110, GL, z, 116, GL, z, "minecraft:spruce_fence")
        g.set(116, GL, z + 2, "minecraft:hay_block[axis=y]")
    g.fill(109, GL, 79, 116, GL, 81, "minecraft:air"); g.fill(109, GL, 97, 116, GL, 99, "minecraft:air")
    flower_boxes(g, 108, 66, 117, 112, GL, GL + 6)
    # ---- the house: three storeys, a spired tower at each corner, a tall one over the door ----
    hx0, hz0, hx1, hz1 = 30, 16, 97, 44
    F2, F3 = TL + 6, TL + 12           # floor blocks of the upper storeys
    eave = TL + 18
    g.fill(hx0, 0, hz0, hx1, eave - 1, hz1, STONE)
    g.fill(hx0 + 2, TL, hz0 + 2, hx1 - 2, eave - 2, hz1 - 2, "minecraft:air")
    g.fill(hx0 + 2, TL - 1, hz0 + 2, hx1 - 2, TL - 1, hz1 - 2, FLOOR_STONE)
    g.fill(hx0 + 2, F2, hz0 + 2, hx1 - 2, F2, hz1 - 2, DARK_PLANK)
    g.fill(hx0 + 2, F3, hz0 + 2, hx1 - 2, F3, hz1 - 2, PLANK)
    g.fill(hx0 + 2, eave - 1, hz0 + 2, hx1 - 2, eave - 1, hz1 - 2, PLANK)
    # string courses and quoins in the darker stone
    for y in (TL + 5, F3 - 1, eave - 1):
        for x in range(hx0, hx1 + 1):
            g.set(x, y, hz0, "minecraft:polished_andesite"); g.set(x, y, hz1, "minecraft:polished_andesite")
        for z in range(hz0, hz1 + 1):
            g.set(hx0, y, z, "minecraft:polished_andesite"); g.set(hx1, y, z, "minecraft:polished_andesite")
    # cross walls: the hall in the middle of the ground floor, rooms either side and above
    for x in (46, 81):
        g.fill(x, TL, hz0 + 2, x, eave - 2, hz1 - 2, STONE)
        for (y0, y1) in ((TL, TL + 2), (F2 + 1, F2 + 3), (F3 + 1, F3 + 3)):
            g.fill(x, y0, 29, x, y1, 31, "minecraft:air")
    for x in (63, 64):
        pass
    for (y0, fl) in ((F2 + 1, F2), (F3 + 1, F3)):
        g.fill(63, y0, hz0 + 2, 64, y0 + 4, hz1 - 2, STONE)          # the upper floors split down the middle
        g.fill(63, y0, 29, 64, y0 + 2, 31, "minecraft:air")
        for z in (22, 37):
            g.fill(47, y0, z, 62, y0 + 4, z, STONE); g.fill(65, y0, z, 80, y0 + 4, z, STONE)
            g.fill(54, y0, z, 55, y0 + 2, z, "minecraft:air"); g.fill(72, y0, z, 73, y0 + 2, z, "minecraft:air")
    # tall windows
    for x in range(hx0 + 4, hx1 - 2, 5):
        for z in (hz0, hz1):
            for (y0, y1) in ((TL + 1, TL + 3), (F2 + 2, F2 + 4), (F3 + 2, F3 + 4)):
                g.fill(x, y0, z, x + 1, y1, z, GLASS)
    for z in range(hz0 + 4, hz1 - 2, 5):
        for x in (hx0, hx1):
            for (y0, y1) in ((TL + 1, TL + 3), (F2 + 2, F2 + 4), (F3 + 2, F3 + 4)):
                g.fill(x, y0, z, x, y1, z + 1, GLASS)
    # the stairs: a stair hall in each wing, ground to the top floor
    dogleg_stair(g, 33, hz0 + 3, TL, F3, STONE_STAIR, STONE, PLANK, levels=(F2 + 1, F3 + 1), back="north", rail=RAIL)
    dogleg_stair(g, 88, hz0 + 3, TL, F3, STONE_STAIR, STONE, PLANK, levels=(F2 + 1, F3 + 1), back="north", rail=RAIL)
    # the roof: steep, slate, dormers along the front and back
    top = gable_roof(g, hx0, hz0, hx1, hz1, eave, "x", ROOF_STAIR, STONE)
    for x in range(hx0 + 6, hx1 - 4, 8):
        for (zf, zb, f) in ((hz1 - 1, hz1 - 5, "south"), (hz0 + 1, hz0 + 5, "north")):
            lo, hi = sorted((zf, zb))
            g.fill(x - 1, eave, lo, x + 1, eave + 3, hi, STONE)
            g.fill(x, eave + 1, zf, x, eave + 2, zf, GLASS)
            g.stair(x - 1, eave + 4, zf, ROOF_STAIR, "east"); g.stair(x + 1, eave + 4, zf, ROOF_STAIR, "west")
            g.set(x, eave + 4, zf, "minecraft:deepslate_tile_slab[type=bottom]")
            g.fill(x - 1, eave + 4, lo, x + 1, eave + 4, hi, ROOF) if False else None
    # chimneys
    for x in (40, 87):
        g.fill(x, eave, 29, x + 1, top + 2, 30, "minecraft:bricks")
        g.set(x, top + 3, 29, "minecraft:campfire[lit=true,signal_fire=false]")
    # the corner towers, spired, a door into each from the house
    for (cx, cz) in ((hx0, hz0), (hx1, hz0), (hx0, hz1), (hx1, hz1)):
        sx = 1 if cx < c else -1
        sz = 1 if cz < 30 else -1
        round_tower(g, cx, cz, 6, eave + 6, TL, floors=(F2, F3), spiral=False, cone=True, t=2,
                    doors=((sx, sz, TL), (sx, sz, F2 + 1), (sx, sz, F3 + 1)))
    # the tall tower over the door, out in front of the house: a porch below, the chambers above reached from
    # the house's own floors
    vx0, vz0, vx1, vz1 = 57, 38, 70, 51
    square_tower(g, vx0, vz0, vx1, vz1, eave + 12, TL, floors=(F2, F3, eave - 1, eave + 5), t=2, cone=True)
    for fl in (F2, F3):
        g.fill(61, fl + 1, vz0 - 2, 66, fl + 3, vz0 + 2, "minecraft:air")
    g.fill(61, TL, vz1 - 2, 66, TL + 5, vz1, "minecraft:air")                 # the great door
    g.fill(61, TL - 1, vz1 - 2, 66, TL - 1, vz1 + 1, FLOOR_STONE)
    g.fill(61, TL, vz0 - 2, 66, TL + 4, vz1 - 2, "minecraft:air")            # through the tower's back wall into the hall
    g.fill(61, TL - 1, vz0 - 2, 66, TL - 1, vz1 - 2, FLOOR_STONE)
    g.wall_banner(60, TL + 7, vz1 + 1, "south"); g.wall_banner(67, TL + 7, vz1 + 1, "south")
    # the hall: the lord's dais at the north end
    from castle2 import hall_furnish
    hall_furnish(g, 50, 77, hz0 + 3, hz1 - 3, TL, "south")
    for (x, z) in ((50, 26), (77, 26), (50, 36), (77, 36)):
        g.mark("knight", x, TL, z)
    for (x, z) in ((56, 28), (71, 28), (56, 34), (71, 34)):
        g.lantern(x, F2 - 1, z)
    g.mark("captain", c - 1, TL, 41)
    # treasure on the lord's floor
    for z in range(hz0 + 4, hz0 + 10, 2):
        g.chest(hx1 - 4, F3 + 1, z, "west", LOOT + "castle_treasury")
    # the terrace court before the house: the banner stand, topiary, lamps
    g.mark("banner_stand", 45, TL, 56)
    for (x, z) in ((28, 50), (99, 50), (28, 58), (99, 58)):
        for k in range(3):
            g.fill_mask(disk(g, x + 0.5, z + 0.5, 1.6 - k * 0.5), TL + k, TL + k, TOPIARY)
    for x in range(34, 96, 10):
        lamp_post(g, x, 60, TL)
    for (x, z) in ((40, 54), (88, 54), (52, 58), (76, 58), (64, 56), (30, 55), (98, 55)):
        g.mark("knight", x, TL, z)
    return g, {"name": "great_castle_d", "tier": 3, "GL": GL, "levels": (TL,)}


great_castle_d.cap_style = "spire"


# ---------------------------------------------------------------- the lake castle

# White stone for the lake castle: the northern palette's greys swapped for calcite, quartz and diorite, the
# dark trim and the slate spires left as they are. The theme processor in the game only swaps the northern
# greys, so the lake castle stays white in every region.
WHITEN = {
    "minecraft:stone_bricks": "minecraft:quartz_bricks",
    "minecraft:cracked_stone_bricks": "minecraft:calcite",
    "minecraft:mossy_stone_bricks": "minecraft:polished_diorite",
    "minecraft:chiseled_stone_bricks": "minecraft:chiseled_quartz_block",
    "minecraft:cobblestone": "minecraft:calcite",
    "minecraft:mossy_cobblestone": "minecraft:diorite",
    "minecraft:stone": "minecraft:calcite",
    "minecraft:smooth_stone": "minecraft:smooth_quartz",
    "minecraft:andesite": "minecraft:diorite",
    "minecraft:polished_andesite": "minecraft:polished_diorite",
    "minecraft:tuff": "minecraft:calcite",
    "minecraft:stone_brick_stairs": "minecraft:quartz_stairs",
    "minecraft:mossy_stone_brick_stairs": "minecraft:polished_diorite_stairs",
    "minecraft:cobblestone_stairs": "minecraft:diorite_stairs",
    "minecraft:polished_andesite_stairs": "minecraft:polished_diorite_stairs",
    "minecraft:andesite_stairs": "minecraft:diorite_stairs",
    "minecraft:stone_stairs": "minecraft:smooth_quartz_stairs",
    "minecraft:stone_brick_slab": "minecraft:quartz_slab",
    "minecraft:mossy_stone_brick_slab": "minecraft:polished_diorite_slab",
    "minecraft:cobblestone_slab": "minecraft:diorite_slab",
    "minecraft:polished_andesite_slab": "minecraft:polished_diorite_slab",
    "minecraft:andesite_slab": "minecraft:diorite_slab",
    "minecraft:stone_slab": "minecraft:smooth_quartz_slab",
    "minecraft:smooth_stone_slab": "minecraft:smooth_quartz_slab",
    "minecraft:stone_brick_wall": "minecraft:diorite_wall",
    "minecraft:mossy_stone_brick_wall": "minecraft:diorite_wall",
    "minecraft:cobblestone_wall": "minecraft:diorite_wall",
    "minecraft:andesite_wall": "minecraft:diorite_wall",
}


def whiten(g):
    """Swap the greys for white stone across the whole grid, keeping each block's properties."""
    for i, s in enumerate(g.palette):
        name = s.split("[")[0]
        to = WHITEN.get(name)
        if to is None:
            continue
        g.palette[i] = to + s[len(name):]
    g.index = {}
    for i, s in enumerate(g.palette):
        g.index.setdefault(s, i)


def _raise(g, K):
    """The same castle K blocks higher in a taller grid, the new bottom layers copied from the old bottom."""
    g2 = Grid(g.X, g.Y + K, g.Z, 1)
    g2.palette = list(g.palette)
    g2.index = dict(g.index)
    g2.a[:, :, K:] = g.a
    for k in range(K):
        g2.a[:, :, k] = g.a[:, :, 0]
    g2.rng = g.rng
    g2.block_nbt = {(x, y + K, z): v for (x, y, z), v in g.block_nbt.items()}
    for role, pts in g.markers.items():
        g2.markers[role] = [(x, y + K, z) for (x, y, z) in pts]
    g2.banners = [(x, y + K, z) for (x, y, z) in g.banners]
    if hasattr(g, "gate_planes"):
        g2.gate_planes = [[p[0], p[1] + K, p[2], p[3], p[4] + K, p[5]] for p in g.gate_planes]
    for attr in ("protected_xz", "spiral_cells", "narrow_spirals", "last_cap"):
        if hasattr(g, attr):
            setattr(g2, attr, getattr(g, attr))
    return g2


OPEN_GROUND = ("minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:dirt", "minecraft:moss_block", "minecraft:gravel",
               "minecraft:mud", "minecraft:clay", "minecraft:sand", "minecraft:dirt_path", "minecraft:podzol")


def lake(fn, style="spire", seed_shift=5, margin=20, raise_by=4, name="lake_castle"):
    """The castle {fn} standing on an island in its own lake: everything outside its walls drowned, the island's
    edge faced in stone from the water up, a long bridge on arches from the gate to the shore, a sandy shore with
    reeds, a jetty for the boats, and the whole castle in white stone under its dark spires."""
    def build(seed=None):
        g0, meta = fn(seed=(fn.__defaults__[0] + seed_shift) if seed is None else seed)
        K, M = raise_by, margin
        GL = meta["GL"] + K
        g = _grow(_raise(g0, K), M)
        X, Z = g.X, g.Z
        rng = g.rng
        top, bed = GL - 5, GL - 9                 # the water's top block and the lake bed
        # the new margin: ground at the old ground level, so the flood below can walk it
        xs, zs = g.xz()
        border = np.minimum(np.minimum(xs, X - 1 - xs), np.minimum(zs, Z - 1 - zs))
        margin_m = (xs < M) | (xs >= M + g0.X) | (zs < M) | (zs >= M + g0.Z)
        g.fill_mask(margin_m, 0, GL - 2, FOUND)
        g.fill_mask(margin_m, GL - 1, GL - 1, "minecraft:grass_block")
        g.fill_mask(margin_m, GL, GL + 8, "minecraft:air")
        # everything outside the walls: open ground or water reachable from the edge of the grid
        open_ = np.zeros((X, Z), dtype=bool)
        A = g.a
        # a column the plan leaves empty (structure void) is nothing built: the lake takes it too
        ground_ids = {g.index[s] for s in g.palette if s.split("[")[0] in OPEN_GROUND or "water" in s or s in ("minecraft:air", "<void>")}
        clear_ids = {g.index[s] for s in g.palette if s in ("minecraft:air", "<void>") or "lily_pad" in s or "grass" == s.split(":")[-1]
                     or s.endswith(":fern") or "tall_grass" in s}
        floor_ok = np.isin(A[:, :, GL - 1], list(ground_ids))
        clear = np.ones((X, Z), dtype=bool)
        for y in range(GL, GL + 4):
            clear &= np.isin(A[:, :, y], list(clear_ids))
        cand = floor_ok & clear
        from collections import deque
        q = deque()
        for x in range(X):
            for z in (0, Z - 1):
                if cand[x, z] and not open_[x, z]:
                    open_[x, z] = True; q.append((x, z))
        for z in range(Z):
            for x in (0, X - 1):
                if cand[x, z] and not open_[x, z]:
                    open_[x, z] = True; q.append((x, z))
        while q:
            x, z = q.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if 0 <= nx < X and 0 <= nz < Z and cand[nx, nz] and not open_[nx, nz]:
                    open_[nx, nz] = True; q.append((nx, nz))
        lake_m = open_ & (border >= 5)
        shore = open_ & (border < 5)
        # the island's edge: the castle's own cells next to the water, faced in stone from the bed to its floor
        island = ~open_
        rim = np.zeros((X, Z), dtype=bool)
        rim[1:, :] |= island[1:, :] & lake_m[:-1, :]
        rim[:-1, :] |= island[:-1, :] & lake_m[1:, :]
        rim[:, 1:] |= island[:, 1:] & lake_m[:, :-1]
        rim[:, :-1] |= island[:, :-1] & lake_m[:, 1:]
        # the lake: a bed of sand, gravel and clay, five of water, open air to well over the walls' feet
        g.fill_mask(lake_m, 0, bed - 1, FOUND)
        g.fill_mask(lake_m, bed, bed, Mix(("minecraft:sand", 40), ("minecraft:gravel", 35), ("minecraft:clay", 25)))
        g.fill_mask(lake_m, bed + 1, top, "minecraft:water")
        g.fill_mask(lake_m, top + 1, GL + 8, "minecraft:air")
        g.fill_mask(rim, bed, GL - 2, STONE)
        # the shore: grass stepping down a block a ring to a sandy beach at the water's edge
        for d, y, block in ((0, GL - 1, "minecraft:grass_block"), (1, GL - 2, "minecraft:grass_block"),
                            (2, GL - 3, Mix(("minecraft:grass_block", 50), ("minecraft:sand", 50))),
                            (3, GL - 4, "minecraft:sand"), (4, top, "minecraft:sand")):
            ring_m = shore & (border == d)
            g.fill_mask(ring_m, 0, y - 1, FOUND)
            g.fill_mask(ring_m, y, y, block)
            g.fill_mask(ring_m, y + 1, GL + 8, "minecraft:air")
        for _ in range(int(X * Z / 160)):
            x, z = int(rng.integers(0, X)), int(rng.integers(0, Z))
            if not shore[x, z]:
                continue
            d = int(border[x, z])
            y = (GL - 1, GL - 2, GL - 3, GL - 4, top)[d]
            if d == 4:
                # reeds on the beach where the water laps it
                if any(0 <= x + dx < X and 0 <= z + dz < Z and lake_m[x + dx, z + dz] for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    for k in range(1, 2 + int(rng.integers(0, 3))):
                        g.set(x, y + k, z, "minecraft:sugar_cane")
            elif d <= 2:
                g.set(x, y + 1, z, "minecraft:grass" if rng.random() < 0.8 else "minecraft:oxeye_daisy")
        for _ in range(int(X * Z / 300)):
            x, z = int(rng.integers(0, X)), int(rng.integers(0, Z))
            if lake_m[x, z] and g.get(x, top, z) == "minecraft:water" and g.get(x, top + 1, z) == "minecraft:air":
                g.set(x, top + 1, z, "minecraft:lily_pad")
        # the bridge: from the gate's outer end straight out to the shore, on piers, arched between them
        gx, gy, gz = g.markers["gate"][0]
        box = (M, M + g0.X - 1, M, M + g0.Z - 1)
        side = min((("west", gx - box[0]), ("east", box[1] - gx), ("north", gz - box[2]), ("south", box[3] - gz)), key=lambda t: t[1])[0]
        along = gx if side in ("north", "south") else gz
        runs = [r for r in _openings(g, side, box, GL) if r[0] - 1 <= along <= r[1] + 1]
        ra, rb = runs[0] if runs else (along - 3, along + 3)
        ra, rb = max(ra, along - 3), min(rb, along + 3)
        # the bridge starts where the castle's own causeway or drawbridge ends: walk out from the gate
        def cell(u, w):           # u along the bridge's width, w outward from the castle
            if side == "south":
                return u, w
            if side == "north":
                return u, Z - 1 - w
            if side == "east":
                return w, u
            return X - 1 - w, u
        span_end = (Z if side in ("north", "south") else X) - 1
        # the castle's own approach (a drawbridge, a causeway) is rebuilt as the bridge's first span: walk in from
        # the gate marker while the way is open floor between rails or fences, and stop at the gatehouse's walls
        w_gate = gz if side == "south" else Z - 1 - gz if side == "north" else gx if side == "east" else X - 1 - gx
        def approach(w):
            x, z = cell(along, w)
            if not (g.is_solid(x, GL - 1, z) and not g.is_solid(x, GL, z)):
                return False
            for u in (ra - 1, rb + 1):
                sx, sz = cell(u, w)
                st = g.get(sx, GL, sz)
                if g.is_solid(sx, GL, sz) and "fence" not in st and "wall" not in st:
                    return False
            return True
        w = w_gate
        while w - 1 > 0 and approach(w - 1):
            w -= 1
        k = 0
        while w <= span_end:
            for u in range(ra - 1, rb + 2):
                x, z = cell(u, w)
                pier = k % 7 in (0, 1) and border[x, z] >= 5
                if pier:
                    g.fill(x, bed, z, x, GL - 2, z, STONE)
                else:
                    if border[x, z] >= 5:
                        g.fill(x, 0, z, x, bed - 1, z, FOUND)
                        g.set(x, bed, z, "minecraft:gravel")
                        g.fill(x, bed + 1, z, x, top, z, "minecraft:water")
                        g.fill(x, top + 1, z, x, GL - 3, z, "minecraft:air")
                    g.set(x, GL - 2, z, STONE)
                g.set(x, GL - 1, z, ROAD if ra <= u <= rb else STONE)
                g.fill(x, GL, z, x, GL + 4, z, "minecraft:air")
                if u in (ra - 1, rb + 1):
                    g.set(x, GL, z, RAIL)
                    if k % 7 == 0:
                        g.set(x, GL + 1, z, "minecraft:lantern[hanging=false]")
            # the arch: a shoulder either side of each pier, under the deck
            if k % 7 in (2, 6):
                f_out, f_in = {"south": ("north", "south"), "north": ("south", "north"),
                               "east": ("west", "east"), "west": ("east", "west")}[side]
                for u in range(ra - 1, rb + 2):
                    x, z = cell(u, w)
                    if border[x, z] >= 5:
                        g.stair(x, GL - 3, z, STONE_STAIR, f_out if k % 7 == 2 else f_in, half="top")
            w += 1
            k += 1
        # a jetty for the boats on the shore to the right of the bridge
        jside = {"south": "east", "north": "west", "east": "north", "west": "south"}[side]
        if jside in ("east", "west"):
            jz = Z // 2
            xs_ = range(X - 1, X - 14, -1) if jside == "east" else range(0, 13)
            for i, x in enumerate(xs_):
                for z in (jz - 1, jz, jz + 1):
                    g.set(x, top + 1, z, "minecraft:spruce_planks")
                    if i >= 5 and i % 4 == 1 and z != jz:
                        g.fill(x, bed, z, x, top, z, "minecraft:spruce_log[axis=y]")
                    g.fill(x, top + 2, z, x, top + 5, z, "minecraft:air")
                if i >= 5:
                    g.set(x, top + 2, jz - 2, "minecraft:spruce_fence") if g.get(x, top + 1, jz - 2) != "minecraft:air" else None
            end = xs_[-1]
            g.barrel(end, top + 2, jz - 1, None); g.barrel(end, top + 2, jz + 1, None)
            g.set(end, top + 2, jz, "minecraft:lantern[hanging=false]")
        else:
            jx = X // 2
            zs_ = range(Z - 1, Z - 14, -1) if jside == "south" else range(0, 13)
            for i, z in enumerate(zs_):
                for x in (jx - 1, jx, jx + 1):
                    g.set(x, top + 1, z, "minecraft:spruce_planks")
                    if i >= 5 and i % 4 == 1 and x != jx:
                        g.fill(x, bed, z, x, top, z, "minecraft:spruce_log[axis=y]")
                    g.fill(x, top + 2, z, x, top + 5, z, "minecraft:air")
            end = zs_[-1]
            g.barrel(jx - 1, top + 2, end, None); g.barrel(jx + 1, top + 2, end, None)
            g.set(jx, top + 2, end, "minecraft:lantern[hanging=false]")
        whiten(g)
        meta = dict(meta)
        meta["name"] = name
        meta["GL"] = GL
        if meta.get("levels"):
            meta["levels"] = tuple(v + K for v in meta["levels"])
        return g, meta
    build.__name__ = name
    build.__defaults__ = (fn.__defaults__[0] + seed_shift,)
    build.cap_style = style
    return build
