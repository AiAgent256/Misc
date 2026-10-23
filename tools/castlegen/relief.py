"""Relief: the depth a mason gives a wall, so a plan reads at a man's eye and not only from the air.

The builders lay walls as flat planes of block. This pass walks every outside face of the finished plan and adds
what a wall of that kind has in stone: a battered plinth stepping out at its foot, shallow buttresses breaking a long
face into bays, a belt course of corbelled stone marking the floors, sills under the windows and hoods over them;
and on a plastered house, a footing course of rubble under the plaster.

Every piece goes only where it takes nothing from a man: the batter and the buttresses only where the ground in
front stays at least two wide, the courses and hoods only above head height, none of it where a wall carries a
corbelled parapet (the drop from a machicolation runs down that face), in a gate passage, on a stair or by a door.
The blocks are the ones every regional theme remaps, so a brick castle in the riverlands gets brick relief."""
import numpy as np

AIR_STATES = ("minecraft:air", "<void>", "minecraft:cave_air")
STONE_FACE = {"stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "chiseled_stone_bricks", "cobblestone",
              "mossy_cobblestone", "andesite", "polished_andesite", "stone", "tuff", "deepslate_bricks",
              "cracked_deepslate_bricks", "polished_deepslate", "cobbled_deepslate", "bricks"}
DARK_FACE = {"deepslate_bricks", "cracked_deepslate_bricks", "polished_deepslate", "cobbled_deepslate"}
PLASTER_FACE = {"white_terracotta", "light_gray_terracotta", "white_concrete_powder", "mud_bricks", "yellow_terracotta",
                "orange_terracotta", "smooth_stone"}
OPENING = ("pane", "glass", "bars", "door", "trapdoor", "fence_gate")
STAIR = "minecraft:stone_brick_stairs"
DARK_STAIR = "minecraft:deepslate_brick_stairs"
RUBBLE = ("minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:andesite", "minecraft:cobblestone",
          "minecraft:mossy_cobblestone")
DIRS = ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north"))
OPP = {"east": "west", "west": "east", "south": "north", "north": "south"}


def _h(x, y, z):
    return (x * 73856093) ^ (y * 19349663) ^ (z * 83492791)


class _Plan:
    def __init__(self, g):
        self.g = g
        pal = g.palette
        self.names = [s.split("[")[0].split(":")[-1] for s in pal]
        self.air_id = np.array([s in AIR_STATES for s in pal])
        self.refresh()

    def refresh(self):
        g = self.g
        if len(self.air_id) < len(g.palette):
            pal = g.palette
            self.names = [s.split("[")[0].split(":")[-1] for s in pal]
            self.air_id = np.array([s in AIR_STATES for s in pal])
        air = self.air_id[g.a]
        self.air = air
        nonair = ~air
        above = np.flip(np.cumsum(np.flip(nonair, axis=2), axis=2), axis=2)
        self.sky = air & (above == 0)
        # where each air run starts: the standing level of the column under (x, y, z)
        X, Z, Y = g.a.shape
        start = np.full((X, Z, Y), -1, dtype=np.int32)
        prev = np.full((X, Z), -1, dtype=np.int32)
        for y in range(Y):
            cur = np.where(air[:, :, y], np.where(prev >= 0, prev, y), -1)
            start[:, :, y] = cur
            prev = cur
        self.start = start

    def name(self, x, y, z):
        s = self.g.get(x, y, z)
        return s.split("[")[0].split(":")[-1]

    def is_air(self, x, y, z):
        g = self.g
        return not g.inb(x, y, z) or g.palette[g.a[x, z, y]] in AIR_STATES

    def sky_from(self, x, y, z):
        """Open to the sky from y up."""
        g = self.g
        return g.inb(x, y, z) and bool(self.sky[x, z, y])

    def stand(self, x, y, z):
        g = self.g
        if not (g.inb(x, y, z) and g.inb(x, y + 1, z)):
            return False
        return self.is_air(x, y, z) and self.is_air(x, y + 1, z) and not self.is_air(x, y - 1, z) and \
            "water" not in g.get(x, y - 1, z) and "lava" not in g.get(x, y - 1, z)


def _keep_off(g):
    cells = set()
    for pts in g.markers.values():
        for (x, y, z) in pts:
            cells |= {(x, y, z), (x, y + 1, z)}
    for (x, y, z) in getattr(g, "banners", []):
        cells.add((x, y, z))
    return cells, getattr(g, "protected_xz", set())


def _faces(P, kinds):
    """Outside faces of walls of these block names: (x, y, z, dx, dz, facing) with air in front."""
    g = P.g
    names = [s_.split("[")[0].split(":")[-1] for s_ in g.palette]
    want = np.array([n in kinds for n in names])
    M = want[g.a]
    out = []
    for dx, dz, f in DIRS:
        front = np.roll(P.air, shift=(-dx, -dz), axis=(0, 1))
        if dx == 1:
            front[-1, :, :] = False
        elif dx == -1:
            front[0, :, :] = False
        if dz == 1:
            front[:, -1, :] = False
        elif dz == -1:
            front[:, 0, :] = False
        for (x, z, y) in np.argwhere(M & front):
            out.append((int(x), int(y), int(z), dx, dz, f))
    return out


def _face_ok(P, x, y, z, dx, dz, kinds, lat=1):
    """The wall at (x, y, z) runs on unbroken for `lat` cells each side along the face."""
    px, pz = dz, dx
    for k in range(-lat, lat + 1):
        if P.name(x + px * k, y, z + pz * k) not in kinds:
            return False
        if not P.is_air(x + px * k + dx, y, z + pz * k + dz):
            return False
    return True


def _openings_near(P, x, y, z, dx, dz, dy=1, lat=1):
    g = P.g
    px, pz = dz, dx
    for k in range(-lat, lat + 1):
        for j in range(-dy, dy + 1):
            s = g.get(x + px * k, y + j, z + pz * k)
            if s in AIR_STATES or any(o in s for o in OPENING):
                return True
    return False


def relieve(g, meta):
    """Add the relief. Returns counts of each piece laid."""
    P = _Plan(g)
    keep, prot = _keep_off(g)
    n = {"batter": 0, "buttress": 0, "course": 0, "sill": 0, "hood": 0, "footing": 0}
    placed = set()

    def free(x, y, z):
        return P.is_air(x, y, z) and (x, y, z) not in keep and (x, z) not in prot and (x, y, z) not in placed and g.inb(x, y, z)

    def put(x, y, z, st):
        g.set(x, y, z, st)
        placed.add((x, y, z))

    import walk as _walk

    def stand_at(x, y, z):
        return g.inb(x, y, z) and _walk.passable(g.get(x, y, z)) and _walk.passable(g.get(x, y + 1, z)) and \
            _walk.supports(g.get(x, y - 1, z))

    def lonely(ax, y, az, dx=0, dz=0):
        """Nobody could get onto a block set at (ax, y, az): no standing place beside it a block lower (a step up onto
        it), none beside it or over it up to three higher (a drop onto it). Relief must never become a stair up a
        wall or a way down off a roof."""
        for cx, cz in ((ax, az), (ax + 1, az), (ax - 1, az), (ax, az + 1), (ax, az - 1)):
            for ys in ((y, y + 2, y + 3, y + 4) if (cx, cz) != (ax, az) else (y + 2, y + 3, y + 4)):
                if stand_at(cx, ys, cz):
                    return False
        return True

    faces = _faces(P, STONE_FACE)
    # ---------------- the foot: batter and buttresses ----------------
    runs = {}
    for (x, y, z, dx, dz, f) in faces:
        ax, az = x + dx, z + dz
        if not g.inb(ax, y, az) or P.start[ax, az, y] != y:
            continue                                  # not the lowest course over the ground in front
        if not P.stand(ax, y, az) or not P.sky_from(ax, y, az):
            continue
        if not (P.stand(ax + dx, y, az + dz) and P.stand(ax + 2 * dx, y, az + 2 * dz)):
            continue                                  # the ground in front stays two wide
        if not _face_ok(P, x, y, z, dx, dz, STONE_FACE, lat=1) or not _face_ok(P, x, y + 1, z, dx, dz, STONE_FACE, lat=1):
            continue
        if _openings_near(P, x, y + 1, z, dx, dz, dy=1, lat=2):
            continue                                  # by a door or a low window
        if not free(ax, y, az):
            continue
        # a batter is a step: nothing a man could step up to from its top
        if any(stand_at(cx, y + 2, cz) for (cx, cz) in ((ax, az), (ax + 1, az), (ax - 1, az), (ax, az + 1), (ax, az - 1))):
            continue
        # the wall must stand at least a storey over the ground
        if sum(1 for k in range(0, 6) if P.name(x, y + k, z) in STONE_FACE) < 6:
            continue
        key = (dx, dz, y, x if dx else z)            # one run per face line
        runs.setdefault(key, []).append((x if dz else z, x, y, z))
    for (dx, dz, y, line), cells in runs.items():
        cells.sort()
        # split into contiguous runs
        groups, cur = [], [cells[0]]
        for c in cells[1:]:
            if c[0] == cur[-1][0] + 1:
                cur.append(c)
            else:
                groups.append(cur)
                cur = [c]
        groups.append(cur)
        f = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(dx, dz)]
        for grp in groups:
            if len(grp) < 3:
                continue
            L = len(grp)
            buttress_at = set()
            if L >= 9:
                for i in range(3, L - 3, 6):
                    buttress_at.add(i)
            for i, (_, x, y_, z) in enumerate(grp):
                ax, az = x + dx, z + dz
                dark = P.name(x, y_, z) in DARK_FACE or P.name(x, y_ + 1, z) in DARK_FACE
                if i in buttress_at:
                    # a pilaster buttress up the face, stopping three under the wall top, weathered with a stair
                    top = y_
                    while top - y_ < 9 and P.name(x, top + 1, z) in STONE_FACE and P.name(x, top + 4, z) in STONE_FACE and \
                            free(ax, top + 1, az) and P.sky_from(ax, top + 1, az) and \
                            not _openings_near(P, x, top + 1, z, dx, dz, dy=0, lat=1):
                        top += 1
                    if top - y_ >= 3:
                        st = g.get(x, y_ + 2, z)
                        for yy in range(y_, top + 1):
                            put(ax, yy, az, st if "deepslate" not in st else "minecraft:stone_bricks")
                        put(ax, top + 1, az, f"{STAIR}[facing={OPP[f]},half=bottom]")
                        n["buttress"] += 1
                        continue
                put(ax, y_, az, f"{DARK_STAIR if dark else STAIR}[facing={OPP[f]},half=bottom]")
                n["batter"] += 1
    # ---------------- belt courses ----------------
    P.refresh()
    for (x, y, z, dx, dz, f) in _faces(P, STONE_FACE):
        ax, az = x + dx, z + dz
        if not g.inb(ax, y, az) or not P.sky_from(ax, y, az):
            continue
        s0 = int(P.start[ax, az, y])
        if s0 < 0:
            continue
        h = y - s0
        if h < 4 or (h - 4) % 6:
            continue
        # a wall that rises at least three more over the course, and has a run of face to carry it
        if not all(P.name(x, y + k, z) in STONE_FACE for k in range(1, 4)):
            continue
        if not _face_ok(P, x, y, z, dx, dz, STONE_FACE, lat=1):
            continue
        if _openings_near(P, x, y, z, dx, dz, dy=1, lat=0):
            continue
        if not free(ax, y, az) or not P.is_air(ax, y - 1, az):
            continue
        if not lonely(ax, y, az):
            continue
        put(ax, y, az, f"{STAIR}[facing={OPP[f]},half=top]")
        n["course"] += 1
    # ---------------- sills and hoods on the windows ----------------
    pane_ids = [i for i, s in enumerate(g.palette) if "pane" in s or s.endswith("glass")]
    for (x, z, y) in np.argwhere(np.isin(g.a, pane_ids)):
        x, y, z = int(x), int(y), int(z)
        for dx, dz, f in DIRS:
            ax, az = x + dx, z + dz
            if not P.is_air(ax, y, az):
                continue
            # the pane sits in a wall plane: masonry either side along the face, or the next pane
            px, pz = dz, dx
            side = [g.get(x + px * k, y, z + pz * k) for k in (-1, 1)]
            if not all(("pane" in s_) or (s_.split("[")[0].split(":")[-1] in STONE_FACE | PLASTER_FACE) or "planks" in s_ or "log" in s_
                       for s_ in side):
                continue
            # bottom of the window: the sill
            below = g.get(x, y - 1, z)
            if "pane" not in below and P.is_air(ax, y - 1, az) and free(ax, y - 1, az):
                s0 = P.start[ax, az, y - 1] if g.inb(ax, y - 1, az) else -1
                if s0 >= 0 and (y - 1) - s0 >= 3 and P.name(x, y - 1, z) in STONE_FACE | PLASTER_FACE and lonely(ax, y - 1, az, dx, dz):
                    put(ax, y - 1, az, f"{STAIR}[facing={OPP[f]},half=top]")
                    n["sill"] += 1
            above = g.get(x, y + 1, z)
            if "pane" not in above and P.name(x, y + 1, z) in STONE_FACE | PLASTER_FACE and free(ax, y + 1, az):
                s0 = P.start[ax, az, y + 1] if g.inb(ax, y + 1, az) else -1
                if s0 >= 0 and (y + 1) - s0 >= 3 and P.is_air(ax, y + 2, az) and lonely(ax, y + 1, az, dx, dz):
                    put(ax, y + 1, az, "minecraft:stone_brick_slab[type=top]")
                    n["hood"] += 1
    # ---------------- footings under plaster ----------------
    for (x, y, z, dx, dz, f) in _faces(P, PLASTER_FACE):
        ax, az = x + dx, z + dz
        if not g.inb(ax, y, az) or P.start[ax, az, y] != y or not P.stand(ax, y, az):
            continue
        if (x, y, z) in keep:
            continue
        g.set(x, y, z, RUBBLE[_h(x, y, z) % len(RUBBLE)])
        n["footing"] += 1
    return n
