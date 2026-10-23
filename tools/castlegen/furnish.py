"""Lived-in interiors.

Finds every roofed, enclosed room in a layout (any floor, any tower), works out what it is for from
what's already in it (beds, a hearth, an anvil, the lord's seat, treasury chests, pews) or failing
that from its size, and dresses it: beds and footlockers in barracks, trestle tables and banners in
halls, stacked barrels and hay in stores, racks of weapon chests in armouries, a bed, books and a
strongbox in a chamber. Stairs, doorways, posts and anything already placed are left alone.
"""
import numpy as np
from scipy import ndimage

from grid import FACING_VEC

AIR = "minecraft:air"
LOOT = "stormhold:chests/"
MIN_ROOM, MAX_ROOM = 9, 1400

# ---------------------------------------------------------------- block classes


def _classes(g):
    pal = g.palette
    n = len(pal)
    air = np.zeros(n, bool)
    solid = np.zeros(n, bool)
    stairs = np.zeros(n, bool)
    openish = np.zeros(n, bool)     # fences, panes, bars, walls: you can see through, can't walk through
    for i, s in enumerate(pal):
        name = s.split("[")[0]
        if s in ("<void>", AIR) or "water" in name or "lava" in name:
            air[i] = s == AIR
            continue
        solid[i] = True
        if name.endswith("_stairs"):
            stairs[i] = True
        if any(k in name for k in ("fence", "glass_pane", "iron_bars", "_wall", "chain", "lantern", "candle", "door", "gate", "ladder")):
            openish[i] = True
    return air, solid, stairs, openish


class _Lazy:
    """A palette class array that grows with the palette (dressing adds new block states)."""

    def __init__(self, g, which):
        self.g, self.which = g, which
        self.n = -1

    def _arr(self):
        if len(self.g.palette) != self.n:
            self.arrs = _classes(self.g)
            self.n = len(self.g.palette)
        return self.arrs[self.which]

    def __getitem__(self, i):
        return self._arr()[i]


# ---------------------------------------------------------------- rooms


class Room:
    def __init__(self, y, cells, g):
        self.y = y
        self.cells = cells                       # set of (x, z)
        xs = [c[0] for c in cells]
        zs = [c[1] for c in cells]
        self.x0, self.x1, self.z0, self.z1 = min(xs), max(xs), min(zs), max(zs)
        self.kind = None
        self.doorways = set()                    # cells inside the room next to an opening
        self.height = 3
        self.g = g

    def size(self):
        return len(self.cells)

    def w(self):
        return self.x1 - self.x0 + 1

    def d(self):
        return self.z1 - self.z0 + 1


def find_rooms(g, GL, protected):
    air, solid, stairs, openish = _classes(g)
    A = g.a
    rooms = []
    for y in range(GL, g.Y - 3):
        here = air[A[:, :, y]] & air[A[:, :, y + 1]]
        below = A[:, :, y - 1]
        floor = solid[below] & ~stairs[below] & ~openish[below]
        roofed = np.zeros_like(here)
        for h in range(2, 15):
            if y + h < g.Y:
                roofed |= solid[A[:, :, y + h]]
        mask = here & floor & roofed
        if not mask.any():
            continue
        lab, n = ndimage.label(mask)
        if n == 0:
            continue
        sizes = ndimage.sum(mask, lab, range(1, n + 1))
        for i, sz in enumerate(sizes, start=1):
            if sz < MIN_ROOM or sz > MAX_ROOM:
                continue
            cells = set(zip(*[c.tolist() for c in (lab == i).nonzero()]))
            room = Room(y, cells, g)
            if min(room.w(), room.d()) <= 2:
                continue  # a corridor or gallery
            if sz < 0.5 * room.w() * room.d():
                continue  # a ring gallery or an L of corridors, not a room
            # must be mostly walled in: count what surrounds it at this height
            open_edges = 0
            edges = 0
            doorways = set()
            for (x, z) in cells:
                for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, nz = x + dx, z + dz
                    if (nx, nz) in cells:
                        continue
                    edges += 1
                    if not g.inb(nx, y, nz):
                        open_edges += 1
                        continue
                    pid = A[nx, nz, y]
                    name = g.palette[pid]
                    if air[pid] or "door" in name or "gate" in name or "ladder" in name:
                        open_edges += 1
                        doorways.add((x, z))
            if edges == 0 or open_edges / edges > 0.30:
                continue
            # the gate passage is not a room
            if _near_portcullis(g, room):
                continue
            room.doorways = doorways
            room.height = _height(g, room, solid)
            rooms.append(room)
    return rooms


def _near_portcullis(g, room):
    pc = g.index.get("stormhold:portcullis")
    if pc is None:
        return False
    sub = g.a[max(0, room.x0 - 2):room.x1 + 3, max(0, room.z0 - 2):room.z1 + 3, room.y:room.y + 4]
    return bool((sub == pc).any())


def _height(g, room, solid):
    hs = []
    for (x, z) in list(room.cells)[:40]:
        for h in range(2, 12):
            if room.y + h >= g.Y or solid[g.a[x, z, room.y + h]]:
                hs.append(h)
                break
    return int(np.median(hs)) if hs else 3


# ---------------------------------------------------------------- what is this room?


def classify(g, room, GL):
    y = room.y
    names = {}
    loot = {}
    # look at the room's free floor and the cells touching it: the beds and hearths that say what a
    # room is for are exactly the cells that aren't free, but a walled-off vault next door is not ours
    scan = set(room.cells)
    for (x, z) in room.cells:
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            scan.add((x + dx, z + dz))
    for (x, z) in scan:
        if True:
            for yy in (y, y + 1):
                s = g.get(x, yy, z).split("[")[0]
                names[s] = names.get(s, 0) + 1
                nb = g.block_nbt.get((x, yy, z))
                if nb and "LootTable" in nb:
                    k = nb["LootTable"].rsplit("/", 1)[-1]
                    loot[k] = loot.get(k, 0) + 1
    markers = set()
    for role, pts in g.markers.items():
        for (mx, my, mz) in pts:
            if my == y and (mx, mz) in room.cells:
                markers.add(role)
    beds = sum(v for k, v in names.items() if k.endswith("_bed")) // 2
    if "lord" in markers or "war_table" in markers:
        return "hall" if room.size() > 60 else "chamber"
    if "banner_stand" in markers:
        return None
    if any(k in names for k in ("minecraft:smoker", "minecraft:water_cauldron")) or "castle_kitchen" in loot:
        return "kitchen"
    if "minecraft:anvil" in names or "castle_smithy" in loot:
        return "smithy"
    if any("treasury" in k for k in loot):
        return "treasury"
    if beds >= 3:
        return "barracks"
    if beds >= 1:
        return "chamber"
    if names.get("minecraft:candle", 0) + sum(v for k, v in names.items() if "stained_glass" in k and "gray" not in k) >= 5:
        return "chapel"
    if names.get("minecraft:hay_block", 0) >= 3 and room.size() > 40:
        return "stable"
    if "notice_board" in markers or "inn" in markers or "smithy" in markers or "house" in markers:
        return None
    # empty: by size and position
    s = room.size()
    if s < 36:
        return "store" if g.rng.random() < 0.55 else "guardroom"
    if s < 140:
        return g.rng.choice(["quarters", "armoury", "mess", "store"], p=[0.4, 0.25, 0.2, 0.15])
    return "hall"


# ---------------------------------------------------------------- placement helpers


class Dresser:
    def __init__(self, g, room, protected):
        self.g = g
        self.room = room
        self.y = room.y
        self.rng = g.rng
        self.used = set()
        self.raised = set()
        air, solid, stairs, openish = _classes(g)
        self.solid, self.stairs, self.openish = _Lazy(g, 1), _Lazy(g, 2), _Lazy(g, 3)
        self.clear = set(getattr(g, "protected_xz", set()))
        for (x, z) in room.doorways:
            for dx in (-2, -1, 0, 1, 2):
                for dz in (-2, -1, 0, 1, 2):
                    if abs(dx) + abs(dz) <= 2:
                        self.clear.add((x + dx, z + dz))
        for (x, z) in room.cells:
            # next to a stair, a drop, or a post: keep the way clear
            for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not g.inb(nx, self.y, nz):
                    continue
                b = g.a[nx, nz, self.y - 1]
                if not solid[b] or stairs[b] or stairs[g.a[nx, nz, self.y]]:
                    self.clear.add((x, z))
            if (x, self.y, z) in protected or (x, self.y + 1, z) in protected:
                self.clear.add((x, z))
            # a passage: walls on both sides along one axis. Never put anything in a passage.
            def wallish(nx, nz):
                if not g.inb(nx, self.y, nz):
                    return True
                b = g.a[nx, nz, self.y]
                return solid[b] and not stairs[b]
            def narrow(ax, bx, az, bz):
                # walls within two cells on both sides, no more than two cells wide between them
                l = 1 if wallish(x - ax, z - az) else 2 if wallish(x - bx, z - bz) else 3 if wallish(x - bx - ax, z - bz - az) else None
                r = 1 if wallish(x + ax, z + az) else 2 if wallish(x + bx, z + bz) else 3 if wallish(x + bx + ax, z + bz + az) else None
                return l is not None and r is not None and l + r <= 4
            if narrow(1, 2, 0, 0) or narrow(0, 0, 1, 2):
                self.clear.add((x, z))
                for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    self.clear.add((x + dx, z + dz))
        self.wall = {}   # cell -> facing (into the room) for cells against a solid wall
        for (x, z) in room.cells:
            if (x, z) in self.clear:
                continue
            for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if (nx, nz) in room.cells or not g.inb(nx, self.y, nz):
                    continue
                b = g.a[nx, nz, self.y]
                if solid[b] and not openish[b] and not stairs[b]:
                    self.wall[(x, z)] = (-dx, -dz)   # facing away from the wall
                    break
        self.inner = [c for c in room.cells if c not in self.clear and c not in self.wall
                      and all((c[0] + dx, c[1] + dz) in room.cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))]

    # -- queries
    def free(self, x, z, tall=1, ignore_used=False):
        if (not ignore_used and (x, z) in self.used) or (x, z) in self.clear or (x, z) not in self.room.cells:
            return False
        base = self.y + (1 if (x, z) in self.raised else 0)
        for h in range(tall):
            if self.g.get(x, base + h, z) != AIR:
                return False
        return True

    def wall_cells(self, shuffle=True):
        cells = list(self.wall.items())
        if shuffle:
            self.rng.shuffle(cells)
        return cells

    def take(self, x, z):
        self.used.add((x, z))

    # -- primitives
    def put(self, x, z, block, dy=0, nbt=None):
        self.g.set(x, self.y + dy, z, block, nbt)
        self.take(x, z)

    def facing(self, f):
        dx, dz = f
        return "east" if dx > 0 else "west" if dx < 0 else "south" if dz > 0 else "north"

    def chest(self, x, z, f, loot):
        self.g.chest(x, self.y, z, self.facing(f), LOOT + loot)
        self.take(x, z)

    def barrel(self, x, z, loot, dy=0, f=None):
        self.g.barrel(x, self.y + dy, z, LOOT + loot, facing=self.facing(f) if f else "up")
        self.take(x, z)

    def bed(self, x, z, f, color):
        """Head against the wall at (x, z), foot one cell into the room (so a headboard can stand on the wall)."""
        hx, hz = x + f[0], z + f[1]
        if not self.free(x, z) or not self.free(hx, hz):
            return False
        self.g.bed(hx, self.y, hz, self.facing((-f[0], -f[1])), color)
        self.take(x, z)
        self.take(hx, hz)
        return True

    def table(self, x, z, length, axis, chairs=True, top="minecraft:spruce_slab[type=top]", leg="minecraft:dark_oak_fence",
              dy=0, chair_sides=None, dressing=None, ignore_used=False):
        """A 2-tall trestle table of `length` along axis, with stair chairs either side (or only on `chair_sides`)."""
        cells = [(x + i, z) if axis == "x" else (x, z + i) for i in range(length)]
        for (cx, cz) in cells:
            if not self.free(cx, cz, 2, ignore_used=ignore_used):
                return False
        for (cx, cz) in cells:
            self.put(cx, cz, leg, dy=dy)
            self.g.set(cx, self.y + dy + 1, cz, top)
        if dressing is None:
            dressing = ["minecraft:candle[candles=2,lit=true]", "minecraft:candle[candles=1,lit=true]",
                        "minecraft:lantern[hanging=false]", AIR, AIR]
        for (cx, cz) in cells[::2]:
            self.g.set(cx, self.y + dy + 2, cz, self.rng.choice(dressing))
        if chairs:
            sides = ((0, 1), (0, -1)) if axis == "x" else ((1, 0), (-1, 0))
            if chair_sides is not None:
                sides = tuple(s for s in sides if s in chair_sides)
            for (cx, cz) in cells:
                for (dx, dz) in sides:
                    sx, sz = cx + dx, cz + dz
                    if self.free(sx, sz, 1, ignore_used=ignore_used):      # benches run the table's length
                        self.g.stair(sx, self.y + dy, sz, "minecraft:dark_oak_stairs", self.facing((-dx, -dz)))
                        self.take(sx, sz)
        return True

    # -- structure
    def doorway_centre(self):
        d = list(self.room.doorways)
        if not d:
            return (self.room.x0 + self.room.x1) / 2, (self.room.z0 + self.room.z1) / 2
        return sum(c[0] for c in d) / len(d), sum(c[1] for c in d) / len(d)

    def far_side(self):
        """Which wall of the room is farthest from where you come in: 'x0', 'x1', 'z0' or 'z1'."""
        r = self.room
        dx, dz = self.doorway_centre()
        cands = {"x0": dx - r.x0, "x1": r.x1 - dx, "z0": dz - r.z0, "z1": r.z1 - dz}
        return max(cands, key=cands.get)

    def side_cells(self, side, depth=1):
        """Room cells within `depth` of the given wall, nearest row first."""
        r = self.room
        out = []
        for k in range(depth):
            if side == "x0":
                row = [(r.x0 + k, z) for z in range(r.z0, r.z1 + 1)]
            elif side == "x1":
                row = [(r.x1 - k, z) for z in range(r.z0, r.z1 + 1)]
            elif side == "z0":
                row = [(x, r.z0 + k) for x in range(r.x0, r.x1 + 1)]
            else:
                row = [(x, r.z1 - k) for x in range(r.x0, r.x1 + 1)]
            out.append([c for c in row if c in r.cells])
        return out

    def side_facing(self, side):
        """Facing that points from the wall `side` into the room."""
        return {"x0": (1, 0), "x1": (-1, 0), "z0": (0, 1), "z1": (0, -1)}[side]

    def beams(self, block="minecraft:dark_oak_log", every=3):
        """Ceiling beams across the short axis, just under the ceiling."""
        r = self.room
        if r.height < 4 or min(r.w(), r.d()) < 5:
            return
        top = self.y + r.height
        if top >= self.g.Y:
            return
        hy = top - 1
        axis = "x" if r.w() < r.d() else "z"        # beams span the short way
        rng_ = range(r.z0 + 1, r.z1, every) if axis == "x" else range(r.x0 + 1, r.x1, every)
        for k in rng_:
            cells = [(x, k) for x in range(r.x0, r.x1 + 1)] if axis == "x" else [(k, z) for z in range(r.z0, r.z1 + 1)]
            for (x, z) in cells:
                if (x, z) not in r.cells or self.g.get(x, hy, z) != AIR or not self.solid[self.g.a[x, z, top]]:
                    continue
                # never over a stair or ladder: a soldier climbing needs the headroom
                if any(self.stairs[self.g.a[x, z, yy]] or "ladder" in self.g.get(x, yy, z) for yy in range(self.y, hy)):
                    continue
                self.g.set(x, hy, z, f"{block}[axis={axis}]")

    def rug(self, color, w, d):
        """A carpet patch in the middle of the room, on free cells only."""
        r = self.room
        cx, cz = (r.x0 + r.x1) // 2, (r.z0 + r.z1) // 2
        for x in range(cx - w // 2, cx - w // 2 + w):
            for z in range(cz - d // 2, cz - d // 2 + d):
                if self.free(x, z):
                    self.g.set(x, self.y, z, f"minecraft:{color}_carpet")
                    self.take(x, z)

    def runner(self, color, side):
        """A carpet line down the middle of the room toward `side`."""
        r = self.room
        if side in ("x0", "x1"):
            cz = (r.z0 + r.z1) // 2
            cells = [(x, cz) for x in range(r.x0, r.x1 + 1)]
        else:
            cx = (r.x0 + r.x1) // 2
            cells = [(cx, z) for z in range(r.z0, r.z1 + 1)]
        for (x, z) in cells:
            if (x, z) in r.cells and (x, z) not in self.used and self.g.get(x, self.y, z) == AIR:
                self.g.set(x, self.y, z, f"minecraft:{color}_carpet")

    def dais(self, side, depth=2, block="minecraft:spruce_planks", step="minecraft:spruce_stairs"):
        """Raise the floor `depth` deep along `side` by one block, with a step down into the room.
        Returns the raised cells (standing level y+1) or None."""
        r = self.room
        if r.height < 4:
            return None
        rows = self.side_cells(side, depth + 1)
        raised = [c for row in rows[:depth] for c in row]
        edge = rows[depth]
        if len(raised) < 6 or any(not self.free(x, z) for (x, z) in raised + edge):
            return None
        for (x, z) in raised:
            self.put(x, z, block)
            self.raised.add((x, z))
        f = self.side_facing(side)
        for (x, z) in edge:
            self.g.stair(x, self.y, z, step, self.facing(f))
            self.take(x, z)
        return raised

    def window(self, side, block="minecraft:light_blue_stained_glass_pane", width=1, height=2, dy=1):
        """Cut a window through the middle of `side`'s wall if that wall faces the open air."""
        r = self.room
        f = self.side_facing(side)
        row = self.side_cells(side, 1)[0]
        if not row:
            return False
        mid = row[len(row) // 2]
        wx, wz = mid[0] - f[0], mid[1] - f[1]
        # walk out through the wall: it must be solid for 1..3 blocks and then open
        t = 0
        while t < 4 and self.g.inb(wx, self.y + dy, wz) and self.solid[self.g.a[wx, wz, self.y + dy]]:
            wx, wz = wx - f[0], wz - f[1]
            t += 1
        if t == 0 or t >= 4:
            return False
        if self.g.inb(wx, self.y + dy, wz) and (self.g.get(wx, self.y + dy, wz) not in (AIR, "<void>")
                                                or (self.g.inb(wx, self.y + dy + 8, wz) and self.solid[self.g.a[wx, wz, self.y + dy + 8]])):
            return False
        for k in range(1, t + 1):
            for h in range(height):
                for w in range(-(width // 2), width - width // 2):
                    x = mid[0] - f[0] * k + f[1] * w
                    z = mid[1] - f[1] * k - f[0] * w
                    if (x, z) in r.cells:
                        continue
                    self.g.set(x, self.y + dy + h, z, block)
        return True

    def bunk(self, x, z, f, color):
        """Two beds stacked against the wall."""
        if not self.bed(x, z, f, color):
            return False
        hx, hz = x + f[0], z + f[1]
        if self.g.get(x, self.y + 1, z) == AIR and self.g.get(hx, self.y + 1, hz) == AIR and self.room.height >= 4:
            self.g.bed(hx, self.y + 1, hz, self.facing((-f[0], -f[1])), color)
        return True

    def canopy_bed(self, x, z, f, color, canopy="minecraft:red_wool"):
        """A four-poster: bed, posts, a cloth canopy over it."""
        if not self.bed(x, z, f, color):
            return False
        hx, hz = x + f[0], z + f[1]
        if self.room.height < 4:
            return True
        for (cx, cz) in ((x, z), (hx, hz)):
            if self.g.get(cx, self.y + 1, cz) == AIR:
                self.g.set(cx, self.y + 1, cz, "minecraft:dark_oak_fence")
            if self.g.get(cx, self.y + 2, cz) == AIR:
                self.g.set(cx, self.y + 2, cz, canopy)
        return True

    def brazier(self, x, z):
        self.put(x, z, "minecraft:cobblestone_wall")
        self.g.set(x, self.y + 1, z, "minecraft:campfire[lit=true,signal_fire=false]")

    def hang(self, x, z, drop=1, end=None):
        """A chain from the ceiling, ending in `end` (a lantern, a skull) or nothing."""
        r = self.room
        top = self.y + r.height
        if top >= self.g.Y or not self.solid[self.g.a[x, z, top]]:
            return False
        for i in range(drop):
            if self.g.get(x, top - 1 - i, z) != AIR:
                return False
            self.g.set(x, top - 1 - i, z, "minecraft:chain[axis=y]")
        if end and self.g.get(x, top - 1 - drop, z) == AIR and top - 1 - drop > self.y + 1:
            self.g.set(x, top - 1 - drop, z, end)
        return True

    def lanterns(self, every=5):
        """Hang lanterns from the ceiling if the room has none."""
        r = self.room
        for (x, z) in r.cells:
            for h in range(2, r.height + 1):
                if self.g.get(x, self.y + h, z).startswith("minecraft:lantern"):
                    return
        h = r.height - 1
        if h < 2:
            return
        if self.y + h + 1 >= self.g.Y:
            return
        for x in range(r.x0 + 2, r.x1 - 1, every):
            for z in range(r.z0 + 2, r.z1 - 1, every):
                if (x, z) in r.cells and self.g.get(x, self.y + h, z) == AIR and self.solid[self.g.a[x, z, self.y + h + 1]]:
                    self.g.lantern(x, self.y + h, z)

    def sconces(self, every=4, block="minecraft:wall_torch"):
        """Torches in brackets along the walls at head height."""
        n = 0
        for (x, z), f in self.wall_cells(shuffle=False):
            n += 1
            if n % every:
                continue
            if self.g.get(x, self.y + 2, z) != AIR:
                continue
            wx, wz = x - f[0], z - f[1]
            if not self.solid[self.g.a[wx, wz, self.y + 2]]:
                continue
            self.g.set(x, self.y + 2, z, f"{block}[facing={self.facing(f)}]")

    def chandelier(self, x, z):
        """A chain from the ceiling with a ring of lanterns, over a table."""
        r = self.room
        top = self.y + r.height
        if top >= self.g.Y or not self.solid[self.g.a[x, z, top]] or r.height < 5:
            return False
        for yy in range(top - 1, self.y + 2, -1):
            if self.g.get(x, yy, z) != AIR:
                return False
        drop = max(1, r.height - 4)
        for i in range(drop):
            self.g.set(x, top - 1 - i, z, "minecraft:chain[axis=y]")
        hy = top - 1 - drop
        self.g.set(x, hy, z, "minecraft:dark_oak_fence")
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if self.g.get(x + dx, hy, z + dz) == AIR:
                self.g.set(x + dx, hy, z + dz, "minecraft:dark_oak_fence")
                if self.g.get(x + dx, hy - 1, z + dz) == AIR:
                    self.g.lantern(x + dx, hy - 1, z + dz)
        return True

    def pillars(self, every=6):
        """Columns holding up a big room's ceiling."""
        r = self.room
        if r.height < 5 or r.size() < 120:
            return
        top = self.y + r.height
        for x in range(r.x0 + 3, r.x1 - 2, every):
            for z in range(r.z0 + 3, r.z1 - 2, every):
                if not self.free(x, z, 2) or (x, z) not in self.inner:
                    continue
                if top >= self.g.Y or not self.solid[self.g.a[x, z, top]]:
                    continue
                ok = all(self.g.get(x, yy, z) == AIR for yy in range(self.y, top))
                if not ok:
                    continue
                self.g.fill(x, self.y, z, x, top - 2, z, "minecraft:dark_oak_log[axis=y]")
                self.g.set(x, top - 1, z, "minecraft:dark_oak_planks")
                for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    if self.g.get(x + dx, top - 1, z + dz) == AIR:
                        self.g.set(x + dx, top - 1, z + dz, f"minecraft:dark_oak_stairs[facing={self.facing((-dx, -dz))},half=top]")
                self.take(x, z)

    def hearth(self):
        """A fire against a wall with a stone surround and a hood."""
        for (x, z), f in self.wall_cells():
            if not self.free(x, z) or not self.free(x + f[0], z + f[1]):
                continue
            sx, sz = x - f[1], z + f[0]      # along the wall
            tx, tz = x + f[1], z - f[0]
            if not self.free(sx, sz) or not self.free(tx, tz):
                continue
            self.put(x, z, "minecraft:campfire[lit=true,signal_fire=false]")
            self.put(sx, sz, "minecraft:polished_andesite")
            self.put(tx, tz, "minecraft:polished_andesite")
            for (cx, cz) in ((sx, sz), (tx, tz)):
                if self.g.get(cx, self.y + 1, cz) == AIR:
                    self.g.set(cx, self.y + 1, cz, "minecraft:polished_andesite_slab[type=bottom]")
            if self.g.get(x, self.y + 2, z) == AIR:
                self.g.set(x, self.y + 2, z, f"minecraft:polished_andesite_stairs[facing={self.facing(f)},half=top]")
            # the chimney breast, up to the ceiling, with a mantel shelf along its foot
            for (cx, cz) in ((x, z), (sx, sz), (tx, tz)):
                for h in range(3, self.room.height):
                    if self.g.get(cx, self.y + h, cz) == AIR:
                        self.g.set(cx, self.y + h, cz, "minecraft:polished_andesite")
            for (cx, cz) in ((sx, sz), (tx, tz)):
                if self.g.get(cx, self.y + 2, cz) == AIR:
                    self.g.set(cx, self.y + 2, cz, f"minecraft:dark_oak_trapdoor[facing={self.facing(f)},half=top,open=false]")
            # keep the cell in front of the fire clear
            self.take(x + f[0], z + f[1])
            return True
        return False

    def pegs(self, every=3):
        """Coat pegs along a wall."""
        n = 0
        for (x, z), f in self.wall_cells(shuffle=False):
            n += 1
            if n % every or self.g.get(x, self.y + 1, z) != AIR:
                continue
            wx, wz = x - f[0], z - f[1]
            if self.solid[self.g.a[wx, wz, self.y + 1]]:
                self.g.set(x, self.y + 1, z, f"minecraft:tripwire_hook[facing={self.facing(f)},attached=false,powered=false]")

    def carpet(self, color, p=0.5):
        for (x, z) in self.inner:
            if self.free(x, z) and self.rng.random() < p:
                self.g.set(x, self.y, z, f"minecraft:{color}_carpet")
                self.take(x, z)

    def fill_wall(self, items, limit=None):
        """Place a cycle of wall items along free wall cells. items: list of callables (x, z, f) -> None."""
        n = 0
        for (x, z), f in self.wall_cells():
            if limit is not None and n >= limit:
                break
            if not self.free(x, z):
                continue
            items[n % len(items)](x, z, f)
            n += 1
        return n


# ---------------------------------------------------------------- the rooms


def dress_barracks(d, room):
    colors = ["brown", "gray", "light_gray", "white"]
    beds = 0
    chests = 0
    for (x, z), f in d.wall_cells(shuffle=False):
        if beds >= 6 or beds * 2 + 2 > room.size() // 3:
            break
        # bed every third wall cell, footlocker beside it
        if (x + z) % 3 == 0 and d.bed(x, z, f, colors[(x * 7 + z) % 4]):
            beds += 1
        elif (x + z) % 3 == 1 and d.free(x, z) and chests < beds:
            d.chest(x, z, f, "castle_barracks")
            chests += 1
    d.fill_wall([lambda x, z, f: d.barrel(x, z, "castle_barracks", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:crafting_table"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f)], limit=3)
    if room.size() > 40:
        cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
        axis = "x" if room.w() >= room.d() else "z"
        d.table(cx - 2 if axis == "x" else cx, cz if axis == "x" else cz - 2, 5, axis)
    if room.size() > 60:
        d.hearth()
    d.pegs()
    d.sconces(every=5)
    d.lanterns()


def dress_quarters(d, room):
    dress_barracks(d, room)
    d.carpet(d.rng.choice(["gray", "light_gray", "brown"]), p=0.25)


def dress_chamber(d, room):
    has_bed = any("_bed[" in d.g.get(x, d.y, z) for (x, z) in room.cells)
    if not has_bed:
        for (x, z), f in d.wall_cells():
            if d.bed(x, z, f, "red"):
                break
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_solar"),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.put(x, z, f"minecraft:lectern[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.put(x, z, "minecraft:potted_azalea_bush" if d.rng.random() < 0.5 else "minecraft:flower_pot"),
                 lambda x, z, f: d.put(x, z, "minecraft:cartography_table"),
                 lambda x, z, f: d.barrel(x, z, "castle_solar", f=f)], limit=max(3, room.size() // 8))
    # a bookshelf wall two high where there's room
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y, z) == "minecraft:bookshelf" and d.g.get(x, d.y + 1, z) == AIR:
            d.g.set(x, d.y + 1, z, "minecraft:bookshelf")
    d.carpet("red", p=0.6)
    d.sconces(every=4)
    if room.size() > 30:
        d.hearth()
    d.lanterns(every=4)


def dress_hall(d, room):
    axis = "x" if room.w() >= room.d() else "z"
    long_ = room.w() if axis == "x" else room.d()
    short = room.d() if axis == "x" else room.w()
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    tlen = max(4, min(14, long_ - 6))
    rows = [0] if short < 9 else [-2, 2] if short < 15 else [-3, 0, 3]
    for off in rows:
        for shift in (0, -3, 3, -5, 5):
            if axis == "x":
                ok = d.table(cx - tlen // 2, cz + off + shift, tlen, "x")
            else:
                ok = d.table(cx + off + shift, cz - tlen // 2, tlen, "z")
            if ok:
                break
    # banners on the walls at head height, torches between them, pillars and chandeliers in a big hall
    for (x, z), f in d.wall_cells(shuffle=False):
        if (x * 3 + z * 5) % 7 == 0 and d.g.get(x, d.y + 2, z) == AIR:
            wx, wz = x - f[0], z - f[1]
            if d.solid[d.g.a[wx, wz, d.y + 2]]:
                d.g.wall_banner(x, d.y + 2, z, d.facing(f))
    d.sconces(every=4)
    d.pillars()
    for off in rows:
        if axis == "x":
            d.chandelier(cx - tlen // 4, cz + off); d.chandelier(cx + tlen // 4, cz + off)
        else:
            d.chandelier(cx + off, cz - tlen // 4); d.chandelier(cx + off, cz + tlen // 4)
    d.hearth()
    d.fill_wall([lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:cake"),
                 lambda x, z, f: d.chest(x, z, f, "castle_kitchen"),
                 lambda x, z, f: d.put(x, z, "minecraft:lantern[hanging=false]")], limit=max(4, room.size() // 25))
    d.carpet("red", p=0.12)
    d.lanterns(every=6)


def dress_mess(d, room):
    dress_hall(d, room)


def dress_kitchen(d, room):
    d.hearth()
    d.fill_wall([lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f),
                 lambda x, z, f: (d.barrel(x, z, "castle_granary", f=f), d.g.barrel(x, d.y + 1, z, LOOT + "castle_granary", facing=d.facing(f))),
                 lambda x, z, f: d.put(x, z, f"minecraft:smoker[facing={d.facing(f)},lit=false]"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:crafting_table"),
                 lambda x, z, f: d.put(x, z, f"minecraft:furnace[facing={d.facing(f)},lit=false]"),
                 lambda x, z, f: d.put(x, z, "minecraft:composter[level=3]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_kitchen"),
                 lambda x, z, f: d.put(x, z, "minecraft:pumpkin"),
                 lambda x, z, f: d.put(x, z, "minecraft:honey_block" if d.rng.random() < 0.3 else "minecraft:melon")],
                limit=max(6, int(room.size() * 0.3)))
    if room.size() > 30:
        cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
        axis = "x" if room.w() >= room.d() else "z"
        d.table(cx - 1 if axis == "x" else cx, cz if axis == "x" else cz - 1, 3, axis, chairs=False,
                top="minecraft:dark_oak_slab[type=top]")
    d.lanterns(every=4)


def dress_store(d, room):
    def stack(x, z, f, loot):
        d.barrel(x, z, loot, f=f)
        if d.g.get(x, d.y + 1, z) == AIR:
            d.g.barrel(x, d.y + 1, z, LOOT + loot, facing=d.facing(f))
    d.fill_wall([lambda x, z, f: stack(x, z, f, "castle_granary"),
                 lambda x, z, f: stack(x, z, f, "castle_cellar"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_store"),
                 lambda x, z, f: stack(x, z, f, "castle_granary"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=x]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_cellar")],
                limit=max(6, int(room.size() * 0.35)))
    # a corner of cobwebs and a few crates on the floor
    for (x, z) in d.inner:
        if d.free(x, z) and d.rng.random() < 0.12:
            d.put(x, z, "minecraft:barrel[facing=up]", nbt={"id": "minecraft:barrel", "LootTable": LOOT + "castle_granary"})
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y + 2, z) == AIR and d.rng.random() < 0.3:
            d.g.set(x, d.y + 2, z, "minecraft:cobweb")
            break
    d.lanterns(every=6)


def dress_armoury(d, room):
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_armory"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, f"minecraft:grindstone[face=floor,facing={d.facing(f)}]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_armory"),
                 lambda x, z, f: d.put(x, z, f"minecraft:anvil[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:smithing_table"),
                 lambda x, z, f: d.put(x, z, "minecraft:target"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f)],
                limit=max(5, int(room.size() * 0.22)))
    d.lanterns(every=4)
    d.carpet("gray", p=0.1)


def dress_guardroom(d, room):
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    d.table(cx - 1, cz, 3, "x") or d.table(cx, cz - 1, 3, "z")
    d.sconces(every=4)
    d.pegs(every=4)
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_barracks"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:lantern[hanging=false]"),
                 lambda x, z, f: d.put(x, z, "minecraft:target"),
                 lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f)], limit=max(3, room.size() // 6))
    d.lanterns(every=5)


def dress_smithy(d, room):
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_smithy"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:iron_block" if d.rng.random() < 0.3 else "minecraft:coal_block"),
                 lambda x, z, f: d.put(x, z, f"minecraft:blast_furnace[facing={d.facing(f)},lit=false]"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.put(x, z, f"minecraft:grindstone[face=floor,facing={d.facing(f)}]")],
                limit=max(4, room.size() // 5))
    d.lanterns(every=4)


def dress_treasury(d, room):
    # a few strongboxes and one show of gold; the vault's own chests do the rest
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_treasury"),
                 lambda x, z, f: d.put(x, z, "minecraft:lantern[hanging=false]"),
                 lambda x, z, f: d.barrel(x, z, "castle_treasury", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.put(x, z, "minecraft:gold_block")], limit=max(3, min(10, room.size() // 12)))
    d.sconces(every=5, block="minecraft:soul_wall_torch")
    d.carpet("red", p=0.15)
    d.lanterns(every=5)


def dress_chapel(d, room):
    for (x, z), f in d.wall_cells(shuffle=False):
        if (x + z) % 2 == 0 and d.free(x, z):
            d.put(x, z, f"minecraft:candle[candles={1 + (x * z) % 4},lit=true]")
    d.lanterns(every=5)


def dress_stable(d, room):
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_stables"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.barrel(x, z, "castle_stables", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=z]")], limit=max(4, room.size() // 8))
    d.lanterns(every=6)


DRESSERS = {
    "barracks": dress_barracks, "quarters": dress_quarters, "chamber": dress_chamber, "hall": dress_hall,
    "mess": dress_mess, "kitchen": dress_kitchen, "store": dress_store, "armoury": dress_armoury,
    "guardroom": dress_guardroom, "smithy": dress_smithy, "treasury": dress_treasury, "chapel": dress_chapel,
    "stable": dress_stable,
}


class _Guarded:
    """A view of the grid that refuses to place anything in a protected column (stair shafts)."""

    def __init__(self, g):
        self._g = g
        self._p = getattr(g, "protected_xz", set())

    def __getattr__(self, k):
        return getattr(self._g, k)

    def set(self, x, y, z, block, nbt=None):
        if (x, z) in self._p:
            return
        self._g.set(x, y, z, block, nbt)

    def lantern(self, x, y, z, hanging=True):
        if (x, z) in self._p:
            return
        self._g.lantern(x, y, z, hanging)

    def fill(self, x0, y0, z0, x1, y1, z1, block):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                if (x, z) in self._p:
                    continue
                self._g.fill(x, y0, z, x, y1, z, block)


def furnish(g, GL, report=None):
    """Dress every room. Returns {kind: count}."""
    protected = set()
    for role, pts in g.markers.items():
        for (x, y, z) in pts:
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    protected.add((x + dx, y, z + dz))
                    protected.add((x + dx, y + 1, z + dz))
    rooms = find_rooms(g, GL, protected)
    counts = {}
    for room in rooms:
        kind = classify(g, room, GL)
        if kind is None:
            continue
        room.kind = kind
        if len(room.cells) <= 12 and len(room.doorways) >= 2:
            continue                                  # a stair head or a tower passage, not a room
        d = Dresser(_Guarded(g), room, protected)
        DRESSERS[kind](d, room)
        counts[kind] = counts.get(kind, 0) + 1
        if report is not None:
            report.append((kind, room.y, room.x0, room.z0, room.w(), room.d(), room.size()))
    return counts
