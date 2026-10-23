"""After the build and the furniture: every door with its mouth clear on both sides, every room and every walled yard
with a way in.

The builders lay rooms floor over floor and the furnisher fills them; a room nobody can walk into is a room the
garrison can't sleep in and a player can't loot. This pass walks the plan from the gate the way the audit does, and
for each room still shut off it adds the way in a mason would have: a door through a party wall to a room that is
reached, else a ladder through the floor or the ceiling to a floor that is, else a doorway through the wall to the
reached ground outside it. Sealed yards get a doorway through the thinnest wall into ground already reached, never
from the field outside the walls."""
from collections import deque

import numpy as np

import furnish
import walk

AIR = "minecraft:air"
FURNITURE = ("barrel", "chest", "hay_block", "composter", "cauldron", "anvil", "smoker", "furnace", "loom", "lectern",
             "bookshelf", "crafting_table", "fletching_table", "smithing_table", "grindstone", "stonecutter",
             "cartography_table", "decorated_pot", "flower_pot", "potted_", "_bed", "melon", "pumpkin", "target",
             "brewing_stand", "cake", "candle", "campfire", "pressure_plate", "armor_stand", "_fence[", "_fence",
             "jukebox", "note_block", "bell", "beehive", "barrel", "spruce_slab[type=top]", "dark_oak_slab[type=top]",
             "_carpet", "_wool", "lantern[hanging=false]", "skull", "_head")
NOT_WALL = ("glass", "pane", "door", "bars", "fence", "gate", "ladder", "trapdoor", "sign", "banner", "torch", "lantern",
            "chest", "barrel", "bed", "slab", "stairs", "carpet", "leaves", "water", "lava", "chain", "pot", "candle",
            "campfire", "portcullis", "air", "<void>")


def is_wall(s):
    return not any(k in s for k in NOT_WALL)


def stand(g, x, y, z):
    return walk.passable(g.get(x, y, z)) and walk.passable(g.get(x, y + 1, z)) and walk.supports(g.get(x, y - 1, z))


def _remove(g, x, y, z):
    """Take a piece of furniture away, both halves of a bed."""
    s = g.get(x, y, z)
    if "_bed" in s:
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            t = g.get(x + dx, y, z + dz)
            if "_bed" in t and t.split("[")[0] == s.split("[")[0]:
                g.set(x + dx, y, z + dz, AIR)
                break
    g.set(x, y, z, AIR)


def free_door_mouths(g):
    """Every door's two sides: furniture standing in a door's mouth is taken out of it. Returns how many."""
    n = 0
    pal = g.palette
    door_ids = [i for i, s in enumerate(pal) if "_door[" in s and "half=lower" in s and "trapdoor" not in s]
    if not door_ids:
        return 0
    for (x, z, y) in np.argwhere(np.isin(g.a, door_ids)):
        x, y, z = int(x), int(y), int(z)
        s = g.get(x, y, z)
        axis_x = "facing=east" in s or "facing=west" in s
        for dx, dz in (((1, 0), (-1, 0)) if axis_x else ((0, 1), (0, -1))):
            nx, nz = x + dx, z + dz
            if not walk.supports(g.get(nx, y - 1, nz)):
                continue
            for yy in (y, y + 1):
                t = g.get(nx, yy, nz)
                if not walk.passable(t) and any(k in t for k in FURNITURE):
                    _remove(g, nx, yy, nz)
                    n += 1
    return n


def _start(g):
    gate = g.markers.get("gate", [None])[0]
    return gate


def _field(g, GL, cells):
    """The ground outside the walls: reached cells joined to the plan's edge on the flat without going through a gate
    passage (a portcullis or a door)."""
    X, Z = g.X, g.Z
    by_xz = {}
    for (x, y, z) in cells:
        if GL - 3 <= y <= GL + 3:
            by_xz.setdefault((x, z), y)
    seen = set()
    q = deque()
    for (x, z), y in by_xz.items():
        if x <= 1 or z <= 1 or x >= X - 2 or z >= Z - 2:
            seen.add((x, z))
            q.append((x, z))
    while q:
        x, z = q.popleft()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in seen or n not in by_xz:
                continue
            here = g.get(n[0], by_xz[n], n[1])
            up = g.get(n[0], by_xz[n] + 1, n[1])
            if "portcullis" in here or "portcullis" in up or "_door" in here:
                continue
            # a gate passage is roofed: the field is under the sky
            if any(not walk.passable(g.get(n[0], by_xz[n] + h, n[1])) for h in range(2, 10)):
                continue
            seen.add(n)
            q.append(n)
    return seen


def _covered(g, x, y, z, h=8):
    return any(not walk.passable(g.get(x, y + k, z)) for k in range(2, h))


def _ladder_wall(g, x, z, y0, y1):
    """The side of (x, z) with a wall from y0 to y1 to hang a ladder on, as the ladder's facing; or None."""
    for dx, dz, f in ((1, 0, "west"), (-1, 0, "east"), (0, 1, "north"), (0, -1, "south")):
        if all(is_wall(g.get(x + dx, y, z + dz)) for y in range(y0, y1 + 1)):
            return f
    return None


def _try_ladders(g, room, cells, protected, max_rungs=16):
    """A ladder joining the room to a reached floor over its ceiling or under its floor. Returns True if laid."""
    y = room.y
    best = None
    for (x, z) in sorted(room.cells):
        if (x, z) in protected:
            continue
        # up: the room's ceiling, one block thick, with reached floor on it
        yc = y + 2
        while yc < g.Y - 2 and walk.passable(g.get(x, yc, z)) and yc - y < max_rungs:
            yc += 1
        if yc - y < max_rungs and not walk.passable(g.get(x, yc, z)) and (x, yc + 1, z) in cells and is_wall(g.get(x, yc, z)):
            if all(g.get(x, yy, z) == AIR for yy in range(y, yc)):
                f = _ladder_wall(g, x, z, y, yc)
                if f and (best is None or yc - y < best[0]):
                    best = (yc - y, x, z, y, yc, f)
        # down: a reached floor under the room's floor
        yf = y - 1
        yb = yf - 1
        while yb > 1 and walk.passable(g.get(x, yb, z)) and yf - yb < max_rungs:
            yb -= 1
        ys = yb + 1                                  # standing level below
        if ys < yf and (x, ys, z) in cells and is_wall(g.get(x, yf, z)) and yf - ys < max_rungs:
            if all(g.get(x, yy, z) == AIR for yy in range(ys, yf)):
                f = _ladder_wall(g, x, z, ys, yf)
                if f and (best is None or yf - ys < best[0]):
                    best = (yf - ys, x, z, ys, yf, f)
    if best is None:
        return False
    _, x, z, ya, yb, f = best
    for yy in range(ya, yb + 1):
        g.set(x, yy, z, f"minecraft:ladder[facing={f}]")
    g.protected_xz = getattr(g, "protected_xz", set()) | {(x, z)}
    return True


def _try_door(g, room, cells, field, protected, covered_only, max_t=3):
    """A doorway from the room through its wall (up to max_t thick) to a reached cell on the same floor; a door hung
    in it when the wall is one thick. Returns True if cut."""
    y = room.y
    best = None
    for (x, z) in sorted(room.cells):
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, z + dz) in room.cells:
                continue
            t = 0
            while t < max_t and is_wall(g.get(x + dx * (t + 1), y, z + dz * (t + 1))) and \
                    is_wall(g.get(x + dx * (t + 1), y + 1, z + dz * (t + 1))):
                t += 1
            if t == 0:
                continue
            fx, fz = x + dx * (t + 1), z + dz * (t + 1)
            if (fx, y, fz) not in cells or not stand(g, fx, y, fz) or (fx, fz) in field:
                continue
            if covered_only and not _covered(g, fx, y, fz):
                continue
            if any((x + dx * k, z + dz * k) in protected for k in range(1, t + 1)):
                continue
            # keep the masonry over the opening: a lintel
            if not all(not walk.passable(g.get(x + dx * k, y + 2, z + dz * k)) for k in range(1, t + 1)):
                continue
            score = (t, 0 if _covered(g, fx, y, fz) else 1)
            if best is None or score < best[0]:
                best = (score, x, z, dx, dz, t)
    if best is None:
        return False
    _, x, z, dx, dz, t = best
    for k in range(1, t + 1):
        cx, cz = x + dx * k, z + dz * k
        g.set(cx, y, cz, AIR)
        g.set(cx, y + 1, cz, AIR)
        if not walk.supports(g.get(cx, y - 1, cz)):
            g.set(cx, y - 1, cz, "minecraft:stone_bricks")
    # a door hung at the room's end of the way through, however thick the wall
    f = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(dx, dz)]
    g.set(x + dx, y, z + dz, f"minecraft:spruce_door[facing={f},half=lower,hinge=left]")
    g.set(x + dx, y + 1, z + dz, f"minecraft:spruce_door[facing={f},half=upper,hinge=left]")
    return True


def link_rooms(g, meta, rounds=5):
    """Ways into the rooms nobody can reach. Returns how many rooms were joined."""
    GL = meta["GL"]
    start = _start(g)
    if start is None:
        return 0
    town = meta.get("kind") == "town"
    joined = 0
    for _ in range(rounds):
        cells = walk.reach(g, start)
        field = _field(g, GL, cells)
        protected = getattr(g, "protected_xz", set())
        shut = []
        for r in furnish.find_rooms(g, GL, set()):
            if len(r.cells) < 12:
                continue
            if not any((x, r.y, z) in cells for (x, z) in r.cells):
                shut.append(r)
        if not shut:
            break
        n = 0
        for r in shut:
            if _try_door(g, r, cells, field, protected, covered_only=True, max_t=1) or \
                    _try_ladders(g, r, cells, protected) or \
                    _try_door(g, r, cells, field if not town else set(), protected, covered_only=False, max_t=3):
                n += 1
        joined += n
        if n == 0:
            break
    return joined


def link_yards(g, meta):
    """Doorways into the walled yards nobody can reach, from reached ground inside the walls. Returns how many."""
    from scipy import ndimage
    GL = meta["GL"]
    start = _start(g)
    if start is None:
        return 0
    cells = walk.reach(g, start)
    field = _field(g, GL, cells) if meta.get("kind") != "town" else set()
    X, Z = g.X, g.Z
    mask = np.zeros((X, Z), dtype=bool)
    level = {}
    for x in range(1, X - 1):
        for z in range(1, Z - 1):
            for y in (GL, GL + 1, GL - 1, GL + 2):
                if y < 1 or (x, y, z) in cells:
                    break
                if stand(g, x, y, z) and all(walk.passable(g.get(x, y + h, z)) for h in range(2, 12)):
                    mask[x, z] = True
                    level[(x, z)] = y
                    break
    lab, n = ndimage.label(mask)
    opened = 0
    protected = getattr(g, "protected_xz", set())
    for i in range(1, n + 1):
        pts = np.argwhere(lab == i)
        if len(pts) < 30 or pts[:, 0].min() <= 1 or pts[:, 1].min() <= 1 or pts[:, 0].max() >= X - 2 or pts[:, 1].max() >= Z - 2:
            continue
        best = None
        for (x, z) in pts:
            x, z = int(x), int(z)
            y = level[(x, z)]
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                t = 0
                while t < 8 and is_wall(g.get(x + dx * (t + 1), y, z + dz * (t + 1))) and \
                        is_wall(g.get(x + dx * (t + 1), y + 1, z + dz * (t + 1))):
                    t += 1
                if t == 0:
                    continue
                fx, fz = x + dx * (t + 1), z + dz * (t + 1)
                if (fx, y, fz) not in cells or (fx, fz) in field or not stand(g, fx, y, fz):
                    continue
                if any((x + dx * k, z + dz * k) in protected for k in range(1, t + 1)):
                    continue
                if not all(not walk.passable(g.get(x + dx * k, y + 2, z + dz * k)) for k in range(1, t + 1)):
                    continue
                # two wide when the wall beside is as thin
                px, pz = dz, dx
                wide = all(is_wall(g.get(x + px + dx * k, yy, z + pz + dz * k)) for k in range(1, t + 1) for yy in (y, y + 1)) and \
                    stand(g, x + px, y, z + pz) and stand(g, fx + px, y, fz + pz)
                score = (0 if wide else 1, t)
                if best is None or score < best[0]:
                    best = (score, x, z, y, dx, dz, t, wide, px, pz)
        if best is None:
            continue
        _, x, z, y, dx, dz, t, wide, px, pz = best
        for k in range(1, t + 1):
            for (ox, oz) in ((0, 0), (px, pz)) if wide else ((0, 0),):
                cx, cz = x + ox + dx * k, z + oz + dz * k
                g.set(cx, y, cz, AIR)
                g.set(cx, y + 1, cz, AIR)
                if not walk.supports(g.get(cx, y - 1, cz)):
                    g.set(cx, y - 1, cz, "minecraft:stone_bricks")
        opened += 1
    return opened
