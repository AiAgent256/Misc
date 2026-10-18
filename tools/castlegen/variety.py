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
