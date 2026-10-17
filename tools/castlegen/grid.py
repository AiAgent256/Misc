"""Voxel grid with a block-state palette, plus building primitives.

Coordinates: x east, y up, z south. Array is stored as a[x, z, y].
Palette id 0 = structure void (not placed), 1 = explicit air.
"""
import math
from collections import defaultdict

import numpy as np

VOID = 0
AIR = 1

FACING_VEC = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def facing_toward(dx, dz):
    """Cardinal facing that points along (dx, dz)."""
    if abs(dx) >= abs(dz):
        return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"


class Mix:
    """Weighted random block choice."""

    def __init__(self, *pairs):
        self.pairs = pairs


class Grid:
    def __init__(self, X, Y, Z, seed=1):
        self.X, self.Y, self.Z = X, Y, Z
        self.a = np.zeros((X, Z, Y), dtype=np.int32)
        self.palette = ["<void>", "minecraft:air"]
        self.index = {"<void>": 0, "minecraft:air": 1}
        self.rng = np.random.default_rng(seed)
        self.block_nbt = {}  # (x,y,z) -> dict
        self.markers = defaultdict(list)  # role -> [(x,y,z)]
        self.banners = []  # (x,y,z)

    # ---------- palette ----------
    def pid(self, state):
        if state not in self.index:
            self.index[state] = len(self.palette)
            self.palette.append(state)
        return self.index[state]

    def _ids_for(self, block, n):
        if isinstance(block, Mix):
            ids = np.array([self.pid(s) for s, _ in block.pairs])
            w = np.array([w for _, w in block.pairs], dtype=float)
            return self.rng.choice(ids, size=n, p=w / w.sum())
        return np.full(n, self.pid(block), dtype=np.int32)

    # ---------- point ops ----------
    def inb(self, x, y, z):
        return 0 <= x < self.X and 0 <= y < self.Y and 0 <= z < self.Z

    def set(self, x, y, z, block, nbt=None):
        if not self.inb(x, y, z):
            return
        if isinstance(block, Mix):
            self.a[x, z, y] = self._ids_for(block, 1)[0]
        else:
            self.a[x, z, y] = self.pid(block)
        if nbt is not None:
            self.block_nbt[(x, y, z)] = nbt
        else:
            self.block_nbt.pop((x, y, z), None)

    def get(self, x, y, z):
        if not self.inb(x, y, z):
            return "<void>"
        return self.palette[self.a[x, z, y]]

    def is_solid(self, x, y, z):
        s = self.get(x, y, z)
        return s not in ("<void>", "minecraft:air") and "water" not in s

    # ---------- region ops ----------
    def fill(self, x0, y0, z0, x1, y1, z1, block):
        xa, xb = sorted((x0, x1))
        ya, yb = sorted((y0, y1))
        za, zb = sorted((z0, z1))
        xa, za, ya = max(xa, 0), max(za, 0), max(ya, 0)
        xb, zb, yb = min(xb, self.X - 1), min(zb, self.Z - 1), min(yb, self.Y - 1)
        if xa > xb or ya > yb or za > zb:
            return
        sub = self.a[xa:xb + 1, za:zb + 1, ya:yb + 1]
        sub[...] = self._ids_for(block, sub.size).reshape(sub.shape)
        for k in [k for k in self.block_nbt if xa <= k[0] <= xb and ya <= k[1] <= yb and za <= k[2] <= zb]:
            del self.block_nbt[k]

    def fill_mask(self, mask2d, y0, y1, block):
        """mask2d over (X, Z); fills y0..y1 inclusive."""
        y0, y1 = max(y0, 0), min(y1, self.Y - 1)
        if y0 > y1:
            return
        sub = self.a[:, :, y0:y1 + 1]
        n = int(mask2d.sum()) * (y1 - y0 + 1)
        if n == 0:
            return
        sub[mask2d] = self._ids_for(block, n).reshape(-1, y1 - y0 + 1)

    def replace(self, x0, y0, z0, x1, y1, z1, frm, to):
        fid = self.index.get(frm)
        if fid is None:
            return
        xa, xb = sorted((x0, x1)); ya, yb = sorted((y0, y1)); za, zb = sorted((z0, z1))
        sub = self.a[xa:xb + 1, za:zb + 1, ya:yb + 1]
        sub[sub == fid] = self.pid(to)

    def xz(self):
        return np.ogrid[0:self.X, 0:self.Z]

    # ---------- markers ----------
    def mark(self, role, x, y, z):
        self.markers[role].append((int(x), int(y), int(z)))

    def chest(self, x, y, z, facing, loot):
        self.set(x, y, z, f"minecraft:chest[facing={facing}]",
                 nbt={"id": "minecraft:chest", "LootTable": loot})

    def barrel(self, x, y, z, loot=None, facing="up"):
        nbt = {"id": "minecraft:barrel"}
        if loot:
            nbt["LootTable"] = loot
        self.set(x, y, z, f"minecraft:barrel[facing={facing}]", nbt=nbt)

    def wall_banner(self, x, y, z, facing):
        """Banner hanging on the block behind it; recolored to the house at runtime."""
        self.set(x, y, z, f"minecraft:white_wall_banner[facing={facing}]")
        self.banners.append((x, y, z))

    def lantern(self, x, y, z, hanging=True):
        self.set(x, y, z, f"minecraft:lantern[hanging={'true' if hanging else 'false'}]")

    def stair(self, x, y, z, block, facing, half="bottom"):
        self.set(x, y, z, f"{block}[facing={facing},half={half}]")

    def bed(self, x, y, z, facing, color="red"):
        """Foot at (x,z); head one step toward facing."""
        dx, dz = FACING_VEC[facing]
        self.set(x, y, z, f"minecraft:{color}_bed[facing={facing},part=foot]")
        self.set(x + dx, y, z + dz, f"minecraft:{color}_bed[facing={facing},part=head]")


# ---------- shape masks ----------

def disk(g, cx, cz, r):
    xs, zs = g.xz()
    return (xs - cx) ** 2 + (zs - cz) ** 2 <= (r + 0.35) ** 2


def ring(g, cx, cz, r_out, r_in):
    xs, zs = g.xz()
    d2 = (xs - cx) ** 2 + (zs - cz) ** 2
    return (d2 <= (r_out + 0.35) ** 2) & (d2 > (r_in + 0.35) ** 2)


def rect(g, x0, z0, x1, z1):
    xs, zs = g.xz()
    return (xs >= x0) & (xs <= x1) & (zs >= z0) & (zs <= z1)


def rect_ring(g, x0, z0, x1, z1, t):
    return rect(g, x0, z0, x1, z1) & ~rect(g, x0 + t, z0 + t, x1 - t, z1 - t)


def angle_index(g, cx, cz, r):
    xs, zs = g.xz()
    ang = np.arctan2(zs - cz, xs - cx)
    return np.round(ang * r).astype(int)


def facing_to_center_grid(g, cx, cz):
    """2D array of facing strings pointing from each cell toward (cx, cz)."""
    xs, zs = g.xz()
    dx = cx - xs + 0 * zs
    dz = cz - zs + 0 * xs
    out = np.empty(dx.shape, dtype=object)
    horiz = np.abs(dx) >= np.abs(dz)
    out[horiz & (dx > 0)] = "east"
    out[horiz & (dx <= 0)] = "west"
    out[~horiz & (dz > 0)] = "south"
    out[~horiz & (dz <= 0)] = "north"
    return out


def spiral_stair(g, cx, cz, y_start, y_end, step_block, core_block, clear=True, landings=(), flat=None, wide=False):
    """Square newel spiral centred on (cx, cz): a 5x5 shaft with one-wide treads (16 cells a turn), or, where
    the tower has the room, a 7x7 shaft with treads two wide (24 cells a turn, a gentler climb a man can pass
    another on). `wide=None` picks the wide stair when the 7x7 ring is open at the foot.
    Steps rise one block per outer cell. Last step top at y_end.
    `landings` are standing levels (a floor's y + 1): when the stair reaches one it runs flat for
    `flat` cells, a half turn of level floor, so a door at that level on any side of that half can be
    walked to from the stair. Returns the ring cells; g.spiral_last holds the index of the last step."""
    if wide is None:
        # the wide shaft needs the whole 7x7 column free of anything but air and the tower's own floors, from
        # the foot to a few blocks over the head: a wall-walk, a parapet or a keep wall crossing it stays narrow
        def free(x, z):
            if not g.inb(x, y_start, z):
                return False
            for yy in range(y_start, min(g.Y, y_end + 4)):
                if yy == y_end:
                    continue                 # the tower's own roof slab, which the stair comes up through
                s_ = g.get(x, yy, z)
                if s_ not in ("minecraft:air", "<void>") and "planks" not in s_ and "lantern" not in s_:
                    return False
            return True
        wide = all(free(x, z) for x in range(cx - 3, cx + 4) for z in range(cz - 3, cz + 4))
    R = 3 if wide else 2
    n = 8 * R
    if flat is None:
        # a half turn of landing for the narrow stair; all but five cells of the turn for the wide one, so a door
        # on any side of the tower meets the landing (a turn must still rise five, or the treads after the landing bury the heads of those below)
        flat = n - 5 if wide else n // 2
    ringcells = []
    for x in range(cx - R, cx + R):
        ringcells.append((x, cz - R, "east"))
    for z in range(cz - R, cz + R):
        ringcells.append((cx + R, z, "south"))
    for x in range(cx + R, cx - R, -1):
        ringcells.append((x, cz + R, "west"))
    for z in range(cz + R, cz - R, -1):
        ringcells.append((cx - R, z, "north"))

    def inner_of(x, z):
        """The tread cell between an outer ring cell and the core, for the wide stair."""
        return (min(max(x, cx - 2), cx + 2), min(max(z, cz - 2), cz + 2))

    if clear:
        g.fill(cx - R, y_start, cz - R, cx + R, y_end + 3, cz + R, "minecraft:air")
    g.fill(cx - 1, y_start - 1, cz - 1, cx + 1, y_end + 2, cz + 1, core_block)
    # the shaft is nobody's room: furnishing keeps out of it
    g.__dict__.setdefault("protected_xz", set()).update((x, z) for x in range(cx - R, cx + R + 1) for z in range(cz - R, cz + R + 1))
    g.__dict__.setdefault("spiral_cells", set()).update((x, z) for x in range(cx - R, cx + R + 1) for z in range(cz - R, cz + R + 1))
    g.__dict__.setdefault("narrow_spirals", 0)
    if not wide:
        g.narrow_spirals += 1
    i = 0
    y = y_start
    flat_left = 0
    done = set()

    def tread(x, z, yy, state):
        g.set(x, yy, z, state)
        if wide:
            ix, iz = inner_of(x, z)
            g.set(ix, yy, iz, state)

    def under(x, z, yy):
        g.set(x, yy, z, core_block)
        if wide:
            ix, iz = inner_of(x, z)
            g.set(ix, yy, iz, core_block)

    def over(x, z, y0, y1):
        g.fill(x, y0, z, x, y1, z, "minecraft:air")
        if wide:
            ix, iz = inner_of(x, z)
            g.fill(ix, y0, iz, ix, y1, iz, "minecraft:air")

    while y <= y_end:
        x, z, f = ringcells[i % n]
        if flat_left == 0 and (y + 1) in landings and (y + 1) not in done and y > y_start:
            flat_left = flat
            done.add(y + 1)
        if i > 0:
            under(x, z, y - 1)
        if flat_left > 0:
            tread(x, z, y, core_block)
            over(x, z, y + 1, y + 3)
            flat_left -= 1
        else:
            tread(x, z, y, f"{step_block}[facing={f},half=bottom]")
            over(x, z, y + 1, y + 3)
            y += 1
        i += 1
    g.spiral_last = (i - 1) % n
    # a landing round the stair head: every ring cell not under the last few steps gets a floor at
    # y_end, so you can step off the top step in any direction instead of into the shaft
    last = i - 1
    tail = 4 if not wide else 6
    for j, (x, z, _) in enumerate(ringcells):
        if any(((last - k) % n) == j for k in range(0, tail)):
            continue
        if g.get(x, y_end, z) == "minecraft:air":
            g.set(x, y_end, z, core_block)
        if wide:
            ix, iz = inner_of(x, z)
            if g.get(ix, y_end, iz) == "minecraft:air":
                g.set(ix, y_end, iz, core_block)
    # light the shaft: lanterns on top of the newel core edge every 8
    for yy in range(y_start + 3, y_end, 6):
        g.set(cx - 1, yy, cz - 1, "minecraft:shroomlight")
        g.set(cx + 1, yy + 3, cz + 1, "minecraft:shroomlight")
    return ringcells


def dogleg_stair(g, x0, z0, y_start, y_end, step_block, core_block, floor_block, levels=(), back="south",
                 rail="minecraft:stone_brick_wall"):
    """
    A stair hall: two straight flights three wide with a half landing between, the way a keep or a tower
    house is really climbed. The well is 7 by 7: lane A (three cells), a spine wall (one), lane B (three)
    across, and four cells of flight plus a three-deep landing along `axis`. The open side is the row
    before (x0, z0) along the axis; every floor level in `levels` (standing heights, floor + 1) and the top
    (y_end + 1) is reached by one dog-leg: up lane A to the landing, back along lane B onto the floor.
    Floors inside the well are cut away except under lane A and the arrival cell, so each flight rests on
    the floor it leaves. Rails fence the drop into the well on every floor and the roof.
    Returns the (x, z) cells of the well.
    """
    # (x0, z0) is the well's low corner; `back` is the wall the landing stands against, so the open side
    # (the row before v = 0) faces away from it into the room
    def m(u, v):
        if back == "south":
            return (x0 + u, z0 + v)
        if back == "north":
            return (x0 + u, z0 + 6 - v)
        if back == "east":
            return (x0 + v, z0 + u)
        return (x0 + 6 - v, z0 + u)
    up = back
    down = {"south": "north", "north": "south", "east": "west", "west": "east"}[back]
    AIR = "minecraft:air"
    lv = sorted(set([y_start] + [l for l in levels if y_start < l <= y_end + 1] + [y_end + 1]))
    # a dog-leg climbs eight at most; a taller storey gets a quarter landing part way (a platform on the
    # open side the next dog-leg starts from)
    full, virtual = [lv[0]], set()
    for ya, yb in zip(lv, lv[1:]):
        k = -(-(yb - ya) // 9)
        for i in range(1, k):
            lvl = ya + round((yb - ya) * i / k)
            full.append(lvl)
            virtual.add(lvl)
        full.append(yb)
    lv = full
    cells = [m(u, v) for u in range(7) for v in range(7)]
    # clear the well, floors and all, then put back what the flights rest on
    for (x, z) in cells:
        g.fill(x, y_start, z, x, y_end + 3, z, AIR)
    for (x, z) in cells:
        if not g.is_solid(x, y_start - 1, z):
            g.set(x, y_start - 1, z, floor_block)
    for l in lv[1:]:
        for u in range(0, 3):
            for v in range(0, 4):
                x, z = m(u, v)
                g.set(x, l - 1, z, floor_block)
        x, z = m(3, 0)
        g.set(x, l - 1, z, floor_block)
        if l in virtual:
            # the quarter landing hangs in the storey: a ledge and a rail along its open sides
            for (u, v) in [(-1, v) for v in range(0, 4)] + [(u, -1) for u in range(0, 7)]:
                x, z = m(u, v)
                if g.inb(x, l, z) and g.get(x, l - 1, z) == AIR:
                    g.set(x, l - 1, z, core_block)
                    g.set(x, l, z, rail)
    # the spine between the lanes, open at the mouth so the two lanes join on every landing
    for v in range(1, 4):
        x, z = m(3, v)
        g.fill(x, y_start - 1, z, x, y_end + 1, z, core_block)
    for ya, yb in zip(lv, lv[1:]):
        h = yb - ya
        # flight A is two to four steps: its top tread needs two clear under the next storey's lane A floor,
        # and flight B's footing must clear the flight below it. Flight B may run five, its last tread
        # flush with the floor in the row before the well.
        na = max(2, min(4, h - 3))
        nb = h - na
        yl = ya + na
        # flight A: up lane A
        for k in range(na):
            for u in range(0, 3):
                x, z = m(u, k)
                g.set(x, ya + k, z, f"{step_block}[facing={up},half=bottom]")
                if k > 0:
                    g.fill(x, max(ya, ya + k - 2), z, x, ya + k - 1, z, core_block)
        # the half landing, three deep and the full width, reaching back to meet a short flight
        for u in range(7):
            for v in range(4, 7):
                x, z = m(u, v)
                g.set(x, yl - 1, z, core_block)
        for u in range(0, 3):
            for v in range(na, 4):
                x, z = m(u, v)
                g.set(x, yl - 1, z, core_block)
        for u in range(4, 7):
            for v in range(0, 4 - nb):
                x, z = m(u, v)
                g.set(x, yb - 1, z, floor_block)
        # flight B: back down lane B toward the open side, arriving at the floor
        for j in range(nb):
            v = 3 - j
            for u in range(4, 7):
                x, z = m(u, v)
                if v >= -1:
                    g.set(x, yl + j, z, f"{step_block}[facing={down},half=bottom]")
                    if j > 0 and v >= 0:
                        g.fill(x, max(yl, yl + j - 2), z, x, yl + j - 1, z, core_block)
        # a light on the spine at the landing
        x, z = m(3, 3)
        g.set(x, yl + 1, z, "minecraft:shroomlight")
    # rails along the drop at each floor and on the roof: the landing's void and lane B's mouth
    for l in lv[1:]:
        for (u, v) in [(u, v) for u in range(7) for v in range(4, 7)]:
            for du, dv in ((-1, 0), (1, 0), (0, 1)):
                nu, nv = u + du, v + dv
                if 0 <= nu < 7 and 0 <= nv < 7:
                    continue
                x, z = m(nu, nv)
                if g.inb(x, l, z) and g.get(x, l, z) == AIR and g.is_solid(x, l - 1, z):
                    g.set(x, l, z, rail)
        for v in range(1, 4):
            x, z = m(7, v)
            if g.inb(x, l, z) and g.get(x, l, z) == AIR and g.is_solid(x, l - 1, z):
                g.set(x, l, z, rail)
    g.__dict__.setdefault("protected_xz", set()).update(cells)
    return cells


def gable_roof(g, x0, z0, x1, z1, y, axis, stair, fill_block, overhang=1, ridge_slab=None):
    """Gable roof over rect; ridge runs along `axis` ('x' or 'z'). y = first roof layer."""
    if axis == "z":
        a0, a1 = x0 - overhang, x1 + overhang
        k = 0
        while a0 + k <= a1 - k:
            yy = y + k
            if a0 + k == a1 - k:
                g.fill(a0 + k, yy, z0 - overhang, a0 + k, yy, z1 + overhang, ridge_slab or fill_block)
            elif a0 + k + 1 == a1 - k:
                for xx, f in ((a0 + k, "east"), (a1 - k, "west")):
                    g.fill(xx, yy, z0 - overhang, xx, yy, z1 + overhang, f"{stair}[facing={f},half=bottom]")
            else:
                g.fill(a0 + k, yy, z0 - overhang, a0 + k, yy, z1 + overhang, f"{stair}[facing=east,half=bottom]")
                g.fill(a1 - k, yy, z0 - overhang, a1 - k, yy, z1 + overhang, f"{stair}[facing=west,half=bottom]")
                # gable end walls
                g.fill(a0 + k + 1, yy, z0, a1 - k - 1, yy, z0, fill_block)
                g.fill(a0 + k + 1, yy, z1, a1 - k - 1, yy, z1, fill_block)
                g.fill(a0 + k + 1, yy, z0 + 1, a1 - k - 1, yy, z1 - 1, "minecraft:air")
            k += 1
        return y + k
    else:
        a0, a1 = z0 - overhang, z1 + overhang
        k = 0
        while a0 + k <= a1 - k:
            yy = y + k
            if a0 + k == a1 - k:
                g.fill(x0 - overhang, yy, a0 + k, x1 + overhang, yy, a0 + k, ridge_slab or fill_block)
            elif a0 + k + 1 == a1 - k:
                for zz, f in ((a0 + k, "south"), (a1 - k, "north")):
                    g.fill(x0 - overhang, yy, zz, x1 + overhang, yy, zz, f"{stair}[facing={f},half=bottom]")
            else:
                g.fill(x0 - overhang, yy, a0 + k, x1 + overhang, yy, a0 + k, f"{stair}[facing=south,half=bottom]")
                g.fill(x0 - overhang, yy, a1 - k, x1 + overhang, yy, a1 - k, f"{stair}[facing=north,half=bottom]")
                g.fill(x0, yy, a0 + k + 1, x0, yy, a1 - k - 1, fill_block)
                g.fill(x1, yy, a0 + k + 1, x1, yy, a1 - k - 1, fill_block)
                g.fill(x0 + 1, yy, a0 + k + 1, x1 - 1, yy, a1 - k - 1, "minecraft:air")
            k += 1
        return y + k


def cone_roof(g, cx, cz, r, y, block, height_factor=1.6):
    """Conical roof made of shrinking disks."""
    layers = int(math.ceil((r + 1) * height_factor))
    for k in range(layers):
        rr = (r + 1) * (1 - k / layers)
        if rr < 0.4:
            g.set(cx, y + k, cz, block)
            continue
        g.fill_mask(disk(g, cx, cz, rr), y + k, y + k, block)
    # finial
    g.set(cx, y + layers, cz, "minecraft:lightning_rod")
    return y + layers
