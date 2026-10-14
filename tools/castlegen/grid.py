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


def spiral_stair(g, cx, cz, y_start, y_end, step_block, core_block, clear=True):
    """Square newel spiral in a 5x5 shaft centred on (cx, cz).
    Steps rise one block per cell, 16 cells per turn. Last step top at y_end."""
    ringcells = []
    for x in range(cx - 2, cx + 2):
        ringcells.append((x, cz - 2, "east"))
    for z in range(cz - 2, cz + 2):
        ringcells.append((cx + 2, z, "south"))
    for x in range(cx + 2, cx - 2, -1):
        ringcells.append((x, cz + 2, "west"))
    for z in range(cz + 2, cz - 2, -1):
        ringcells.append((cx - 2, z, "north"))
    if clear:
        g.fill(cx - 2, y_start, cz - 2, cx + 2, y_end + 3, cz + 2, "minecraft:air")
    g.fill(cx - 1, y_start - 1, cz - 1, cx + 1, y_end + 2, cz + 1, core_block)
    i = 0
    while y_start + i <= y_end:
        x, z, f = ringcells[i % 16]
        g.set(x, y_start + i, z, f"{step_block}[facing={f},half=bottom]")
        if i > 0:
            g.set(x, y_start + i - 1, z, core_block)
        i += 1
    # light the shaft: lanterns on top of the newel core edge every 8
    for y in range(y_start + 3, y_end, 6):
        g.set(cx - 1, y, cz - 1, "minecraft:shroomlight")
        g.set(cx + 1, y + 3, cz + 1, "minecraft:shroomlight")
    return ringcells


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
