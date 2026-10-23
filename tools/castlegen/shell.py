"""The shell of a room, done before the furniture goes in: what every good interior builder spends the time on.

A room out of the builders is a flat box. The shell pass gives its walls, ceiling and floor the depth a real one has:
a skirting course at the foot of the walls, pilasters breaking a long wall into bays, a cornice of upside-down stairs
where wall meets ceiling, a bordered floor, and a headboard over every bed. The furniture dressers run after it and
place on what's left. Nothing here takes a cell a man walks through: the pilasters stand on wall cells the dressers
would have filled anyway, and everything else is above head height or in the wall and floor blocks themselves.

Blocks are the ones every regional theme remaps, so a riverlands brick castle gets brick and a Reach one pale stone."""
import os

AIR = "minecraft:air"

STONE_NAMES = ("stone", "andesite", "deepslate", "cobble", "tuff", "brick", "granite", "diorite", "calcite", "quartz",
               "sandstone", "blackstone", "basalt")
TIMBER_NAMES = ("planks", "terracotta", "concrete", "mud", "log", "wood", "wool", "calcite")
PLAIN_FLOOR = ("planks", "stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "andesite", "polished_andesite",
               "cobblestone", "stone", "tuff", "deepslate_bricks", "cobbled_deepslate", "polished_deepslate",
               "smooth_stone", "mud_bricks", "bricks", "dirt", "coarse_dirt", "gravel", "packed_mud")

STYLES = {
    "stone": dict(skirting="minecraft:polished_deepslate", pilaster="minecraft:deepslate_bricks",
                  cap="minecraft:deepslate_brick_stairs", cornice="minecraft:stone_brick_stairs",
                  border="minecraft:polished_deepslate", headboard="minecraft:spruce_trapdoor"),
    "timber": dict(skirting="minecraft:spruce_planks", pilaster="minecraft:dark_oak_log",
                   cap="minecraft:dark_oak_stairs", cornice="minecraft:dark_oak_stairs",
                   border="minecraft:dark_oak_planks", headboard="minecraft:dark_oak_trapdoor"),
}


def _short(s):
    return s.split("[")[0].split(":")[-1]


def wall_style(d):
    """'stone' or 'timber', from what the walls are made of at head height."""
    g = d.g
    stone = timber = 0
    for (x, z), f in d.wall.items():
        n = _short(g.get(x - f[0], d.y + 1, z - f[1]))
        if any(k in n for k in ("planks", "terracotta", "concrete", "mud_brick", "_log", "_wood", "wool")):
            timber += 1
        elif any(k in n for k in STONE_NAMES):
            stone += 1
    return "timber" if timber > stone else "stone"


def _wall_cells_all(d):
    """Every room cell against a solid wall, with the facing into the room, doorway cells and all."""
    g, y, r = d.g, d.y, d.room
    out = {}
    for (x, z) in r.cells:
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = x + dx, z + dz
            if (nx, nz) in r.cells or not g.inb(nx, y, nz):
                continue
            b = g.a[nx, nz, y]
            if d.solid[b] and not d.openish[b] and not d.stairs[b]:
                out[(x, z)] = (-dx, -dz)
                break
    return out


def floor(d, st):
    """A border one cell in from the walls, in the darker block, where the floor is plain."""
    g, y, r = d.g, d.y, d.room
    if r.size() < 20 or min(r.w(), r.d()) < 5:
        return 0
    n = 0
    for (x, z) in r.cells:
        edge = any((x + dx, z + dz) not in r.cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if not edge:
            continue
        cur = g.get(x, y - 1, z)
        if _short(cur) not in PLAIN_FLOOR or "stairs" in cur or "slab" in cur:
            continue
        g.set(x, y - 1, z, st["border"])
        n += 1
    return n


def skirting(d, st):
    """The wall's lowest course, seen from the room, in the darker block: only where the wall is two thick or the far
    side is another room at the same level, so no outside face changes."""
    g, y, r = d.g, d.y, d.room
    n = 0
    for (x, z), f in _wall_cells_all(d).items():
        wx, wz = x - f[0], z - f[1]
        beyond = (wx - f[0], wz - f[1])
        cur = g.get(wx, y, wz)
        if "stairs" in cur or "slab" in cur or _short(cur) == _short(st["skirting"]):
            continue
        if not g.inb(beyond[0], y, beyond[1]):
            continue
        b = g.get(beyond[0], y, beyond[1])
        thick = d.solid[g.a[beyond[0], beyond[1], y]] and not d.openish[g.a[beyond[0], beyond[1], y]]
        other_room = b == AIR and g.get(beyond[0], y + 1, beyond[1]) == AIR and d.solid[g.a[beyond[0], beyond[1], y - 1]]
        if not (thick or other_room):
            continue
        g.set(wx, y, wz, st["skirting"])
        n += 1
    return n


TAPESTRY_ROOMS = ("hall", "chamber", "solar", "library", "chapel", "treasury", "scriptorium", "oratory", "mess", "wardrobe")


def tapestries(d, st, ch, kind=None):
    """Hangings set into the wall at head height: a panel of the house's cloth three wide and two high, one in the
    middle of each long stretch of wall. Only in the rooms a lord would hang cloth in, and only where the wall is
    two thick or backs onto another room, so no outside face changes."""
    g, y, r = d.g, d.y, d.room
    if kind not in TAPESTRY_ROOMS or r.height < 4 or r.size() < 40:
        return 0
    cloth = ch["carpets"][1] if len(ch["carpets"]) > 1 else ch["rug"]     # the house's second colour: red for the soldiers, purple for the pious
    wool = f"minecraft:{cloth}_wool"
    trim = "minecraft:dark_oak_planks"

    def backed(x, z, f):
        """A wall cell whose wall is solid three high behind it. "in" when the wall is thick (or shared with another
        room) so the panel can sit in it; "front" when the wall is one thick and the panel must hang on it instead."""
        wx, wz = x - f[0], z - f[1]
        for h in (1, 2, 3):
            b = g.get(wx, y + h, wz)
            if not d.solid[g.a[wx, wz, y + h]] or "stairs" in b or "slab" in b or "door" in b:
                return None
        bx, bz = wx - f[0], wz - f[1]
        deep = True
        for h in (2, 3):
            if not g.inb(bx, y + h, bz):
                deep = False
                break
            b = g.a[bx, bz, y + h]
            thick = d.solid[b] and not d.openish[b]
            other_room = g.get(bx, y + h, bz) == AIR and g.get(bx, y + h - 1, bz) == AIR
            if not (thick or other_room):
                deep = False
                break
        if deep:
            return "in"
        if r.height >= 5 and all(g.get(x, y + h, z) == AIR for h in (2, 3)):
            return "front"
        return None

    n = 0
    for f in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        along_z = f[0] != 0                        # the wall stands at a fixed x, its cells vary in z
        how = {p: backed(p[0], p[1], f) for p, pf in d.wall.items() if pf == f}
        cells = [p for p, h in how.items() if h]
        lines = {}
        for p in cells:
            lines.setdefault(p[0] if along_z else p[1], []).append(p)
        for _, line in lines.items():
            line.sort(key=lambda p: p[1] if along_z else p[0])
            bay = 0
            for run in _runs(line, not along_z):
                if len(run) < 3:
                    continue
                if len(run) < 7:
                    # a bay between pilasters: cloth in every other one
                    bay += 1
                    if bay % 2 == 0:
                        continue
                k = max(1, len(run) // 9)
                for i in range(k):
                    c = run[(2 * i + 1) * len(run) // (2 * k)]
                    panel = [(c[0], c[1] + j) if along_z else (c[0] + j, c[1]) for j in (-1, 0, 1)]
                    if any(p not in run for p in panel):
                        continue
                    sunk = all(how[p] == "in" for p in panel)
                    for (x, z) in panel:
                        wx, wz = (x - f[0], z - f[1]) if sunk else (x, z)
                        g.set(wx, y + 2, wz, wool)
                        g.set(wx, y + 3, wz, wool)
                        if sunk and y + 4 < y + r.height and d.solid[g.a[wx, wz, y + 4]] and "stairs" not in g.get(wx, y + 4, wz):
                            g.set(wx, y + 4, wz, trim)
                    n += 1
    return n


def pilasters(d, st, every=4, kind=None):
    """Columns against the long walls, floor to ceiling, breaking the wall into bays; a corbel cap at the top.
    Not in the sleeping rooms: every wall cell there is a bed."""
    g, y, r = d.g, d.y, d.room
    H = r.height
    if H < 4 or min(r.w(), r.d()) < 7 or r.size() < 60 or kind in ("barracks", "quarters", "lodging", "chamber"):
        return 0
    top = y + H
    n = 0
    # walk each wall in order so the spacing is even
    for side in ("x0", "x1", "z0", "z1"):
        row = d.side_cells(side, 1)[0]
        f = d.side_facing(side)
        k = 0
        for (x, z) in row:
            k += 1
            if k % every != every // 2:
                continue
            if (x, z) not in d.wall or d.wall[(x, z)] != f or not d.free(x, z, H):
                continue
            if top >= g.Y or not d.solid[g.a[x, z, top]]:
                continue
            # the wall behind must run the full height
            if not all(d.solid[g.a[x - f[0], z - f[1], yy]] for yy in range(y, top)):
                continue
            # not beside the fire or a bed the builder laid: only plain wall either side
            for yy in range(y, top - 1):
                g.set(x, yy, z, st["pilaster"] + ("[axis=y]" if "log" in st["pilaster"] else ""))
            g.set(x, top - 1, z, f"{st['cap']}[facing={d.facing((-f[0], -f[1]))},half=top]")
            d.take(x, z)
            n += 1
    return n


def cornice(d, st):
    """Upside-down stairs along the top of every wall, the step toward the room."""
    g, y, r = d.g, d.y, d.room
    H = r.height
    if H < 3:
        return 0
    hy = y + H - 1
    n = 0
    for (x, z), f in _wall_cells_all(d).items():
        if g.get(x, hy, z) != AIR:
            continue
        if not d.solid[g.a[x - f[0], z - f[1], hy]]:
            continue
        # keep the headroom over a stair or a ladder
        if any(d.stairs[g.a[x, z, yy]] or "ladder" in g.get(x, yy, z) for yy in range(y, hy)):
            continue
        g.set(x, hy, z, f"{st['cornice']}[facing={d.facing((-f[0], -f[1]))},half=top]")
        n += 1
    return n


def headboards(d, st):
    """A trapdoor standing on the wall over the head of every bed whose head lies against one."""
    g, y, r = d.g, d.y, d.room
    n = 0
    for (x, z) in r.cells:
        for yy in (y, y + 1):
            s = g.get(x, yy, z)
            if "_bed[" not in s or "part=head" not in s:
                continue
            facing = s.split("facing=")[1].split(",")[0].split("]")[0]
            f = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]   # foot -> head
            wx, wz = x + f[0], z + f[1]
            if not g.inb(wx, yy, wz) or not d.solid[g.a[wx, wz, yy + 1]] or g.get(x, yy + 1, z) != AIR:
                continue
            opp = {"north": "south", "south": "north", "east": "west", "west": "east"}[facing]
            g.set(x, yy + 1, z, f"{st['headboard']}[facing={opp},half=bottom,open=true]")
            n += 1
    return n


def dress(d, room, ch, kind):
    """The whole shell. Returns counts."""
    st = STYLES[wall_style(d)]
    out = {"floor": floor(d, st), "skirting": skirting(d, st), "pilasters": pilasters(d, st, kind=kind), "cornice": cornice(d, st),
           "tapestries": tapestries(d, st, ch, kind=kind)}
    return out


def finish(d, room, ch, kind):
    """After the furniture: headboards over the beds."""
    st = STYLES[wall_style(d)]
    return {"headboards": headboards(d, st)}


# ---------------------------------------------------------------- partition: the big floors into chambers


def _wall_block(d):
    """The block the room's walls are mostly made of at head height."""
    from collections import Counter
    c = Counter()
    for (x, z), f in d.wall.items():
        s = d.g.get(x - f[0], d.y + 1, z - f[1])
        if "stairs" not in s and "slab" not in s:
            c[s] += 1
    return c.most_common(1)[0][0] if c else "minecraft:stone_bricks"


def _connected(d, cells, walls, keep=None):
    """Do the room's doorways and kept-clear cells stay joined once `walls` (a set of (x, z)) are solid?"""
    doors = list(d.room.doorways) + [c for c in (d.clear if keep is None else keep) if c in cells]
    if len(doors) < 2:
        return True
    start = doors[0]
    seen = {start}
    stack = [start]
    while stack:
        x, z = stack.pop()
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in seen or n not in cells or n in walls:
                continue
            seen.add(n)
            stack.append(n)
    return all(dr in seen for dr in doors)


def _pieces(cells):
    """The 4-connected pieces of a set of (x, z) cells."""
    from scipy import ndimage
    import numpy as np
    if not cells:
        return []
    xs = [c[0] for c in cells]
    zs = [c[1] for c in cells]
    x0, z0 = min(xs), min(zs)
    arr = np.zeros((max(xs) - x0 + 1, max(zs) - z0 + 1), dtype=bool)
    for (x, z) in cells:
        arr[x - x0, z - z0] = True
    lab, k = ndimage.label(arr)
    return [{(int(px) + x0, int(pz) + z0) for px, pz in zip(*np.nonzero(lab == i))} for i in range(1, k + 1)]


def _runs(cells, along_x):
    """Contiguous runs of a line of cells, in order along the line."""
    key = (lambda p: p[0]) if along_x else (lambda p: p[1])
    cells = sorted(cells, key=key)
    out, cur = [], []
    for p in cells:
        if cur and key(p) != key(cur[-1]) + 1:
            out.append(cur)
            cur = []
        cur.append(p)
    if cur:
        out.append(cur)
    return out


def partition(g, GL, protected, fixed_kind, big=150, chamber=110):
    """Cut every big open floor the builders left into chambers, the way a keep's floors really are. Each floor is
    split across its long axis at the middle, a two-wide doorway with doors hung in every run of the new wall, and
    each half split again until no chamber is bigger than `chamber` cells. Halls, chapels and stables stay whole.
    Returns the number of wall cells."""
    import furnish
    from scipy import ndimage
    import numpy as np
    n = 0
    for room in furnish.find_rooms(g, GL, protected):
        if room.size() < big or min(room.w(), room.d()) < 9 or room.height < 3:
            continue
        fill = room.size() / (room.w() * room.d())
        if fill < 0.6 or (fill < 0.8 and room.size() < 300):
            continue                                   # a ring, an L, a round tower floor: not a floor to divide
        k = fixed_kind(g, room)
        if k in ("hall", "chapel", "stable", False):
            continue
        d = furnish.Dresser(furnish._Guarded(g), room, protected)
        block = _wall_block(d)
        y, H = room.y, room.height
        top = y + H
        if top >= g.Y:
            continue
        # what a partition wall may not stand on: the doorways and the cell before each, the stairs and the cells
        # beside them, and the protected columns
        keep = set(getattr(g, "protected_xz", set()))
        for (x, z) in room.doorways:
            for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                keep.add((x + dx, z + dz))
        for (x, z) in room.cells:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if not g.inb(nx, y, nz):
                    continue
                b = g.a[nx, nz, y - 1]
                if not d.solid[b] or d.stairs[b] or d.stairs[g.a[nx, nz, y]] or "ladder" in g.get(nx, y, nz):
                    keep.add((x, z))
            if (x, y, z) in protected or (x, y + 1, z) in protected:
                keep.add((x, z))

        def column(p):
            ys = []
            for yy in range(y, top + 1):
                s_ = g.get(p[0], yy, p[1])
                if s_ == AIR or "lantern" in s_ or "chain" in s_:
                    ys.append(yy)
                else:
                    break
            return ys if len(ys) >= 3 else None

        def split(cells):
            """One wall across the component's long axis, near its middle. Returns (walls, doors) or None."""
            xs = sorted(c[0] for c in cells)
            zs = sorted(c[1] for c in cells)
            along_x = (xs[-1] - xs[0]) >= (zs[-1] - zs[0])     # the wall runs across the long axis
            coords = xs if along_x else zs
            med = coords[len(coords) // 2]
            for off in (0, 1, -1, 2, -2, 3, -3):
                c = med + off
                full = sorted([p for p in cells if (p[0] if along_x else p[1]) == c], key=lambda p: p[1] if along_x else p[0])
                ok = {p for p in full if p not in keep and column(p)}
                pos = lambda p: p[1] if along_x else p[0]
                # a wall run that stops short leaves a gap, and a gap must be three wide to pass in: runs are
                # trimmed back where a gap would be narrower, and runs too short to keep become gaps themselves,
                # until nothing changes
                for _ in range(16):
                    runs_ = []
                    changed = False
                    for run in _runs([p for p in full if p in ok], not along_x):
                        before = [p for p in full if pos(p) < pos(run[0])]
                        after = [p for p in full if pos(p) > pos(run[-1])]
                        gap_b = 0
                        for p in reversed(before):
                            if pos(p) != pos(run[0]) - gap_b - 1 or p in ok:
                                break
                            gap_b += 1
                        gap_a = 0
                        for p in after:
                            if pos(p) != pos(run[-1]) + gap_a + 1 or p in ok:
                                break
                            gap_a += 1
                        bounded_b = bool(before) and pos(before[-1]) == pos(run[0]) - 1
                        bounded_a = bool(after) and pos(after[0]) == pos(run[-1]) + 1
                        cut = set()
                        if bounded_b and 0 < gap_b < 3:
                            cut |= set(run[:3 - gap_b])
                        if bounded_a and 0 < gap_a < 3:
                            cut |= set(run[len(run) - (3 - gap_a):])
                        # a wall cell beside a one-wide slot against existing masonry would pinch it: drop it
                        for p in run:
                            if p in cut:
                                continue
                            for sgn in (-1, 1):
                                nb = (p[0] + sgn, p[1]) if along_x else (p[0], p[1] + sgn)
                                far = (p[0] + 2 * sgn, p[1]) if along_x else (p[0], p[1] + 2 * sgn)
                                ends = ((nb[0], nb[1] - 1), (nb[0], nb[1] + 1)) if along_x else ((nb[0] - 1, nb[1]), (nb[0] + 1, nb[1]))
                                if nb in cells and far not in cells and g.inb(far[0], y, far[1]) and g.get(far[0], y, far[1]) != AIR \
                                        and all(g.inb(e[0], y, e[1]) and g.get(e[0], y, e[1]) == AIR for e in ends):
                                    cut.add(p)
                        kept = [p for p in run if p not in cut]
                        if len(kept) < 3:
                            cut |= set(run)
                            kept = []
                        if cut:
                            ok -= cut
                            changed = True
                        if kept:
                            runs_.append(kept)
                    if not changed:
                        break
                walls, doors = set(), {}
                for run in runs_:
                    if len(run) < 3:
                        continue
                    m = len(run) // 2
                    dcells = [run[m]] if len(run) == 3 else [run[m - 1], run[m]]
                    walls |= set(run) - set(dcells)
                    for p in dcells:
                        doors[p] = "east" if along_x else "south"
                if len(walls) < 3:
                    continue
                # the wall must span the room, not stand in a corner of it
                if len(walls) + len(doors) < 0.6 * len(full):
                    continue
                # both sides must be rooms worth having, and everything must still join up
                side_a = [p for p in cells if (p[0] if along_x else p[1]) < c]
                side_b = [p for p in cells if (p[0] if along_x else p[1]) > c]
                if len(side_a) < 20 or len(side_b) < 20:
                    continue
                # neither side a slot: at least four cells deep each, measured from every wall cell
                depth_a = c - min(p[0] if along_x else p[1] for p in side_a)
                depth_b = max(p[0] if along_x else p[1] for p in side_b) - c
                if depth_a < 4 or depth_b < 4:
                    continue
                shallow = 0
                for w in list(walls) + list(doors):
                    for sgn in (-1, 1):
                        q, n_ = w, 0
                        while n_ < 3:
                            q = (q[0] + sgn, q[1]) if along_x else (q[0], q[1] + sgn)
                            if q in cells and q not in walls and q not in doors:
                                n_ += 1
                            else:
                                break
                        if n_ < 3:
                            shallow += 1
                if shallow > 3:                       # a pillar beside the wall is fine; a slot along it is not
                    continue
                # and the wall must really divide the room in two: a wall that leaves one piece just
                # gets another wall put beside it next round
                rest_ = cells - walls - set(doors)
                pieces = _pieces(rest_)
                if len(pieces) < 2 or max(len(p) for p in pieces) > 0.8 * len(rest_):
                    continue
                if not _connected(d, cells, walls, keep):
                    continue
                if os.environ.get("PARTITION_DEBUG"):
                    print("PARTITION", room.y, "along_x" if along_x else "along_z", "c", c, "walls", len(walls), "doors", sorted(doors),
                          "sides", len(side_a), len(side_b), "depths", depth_a, depth_b, "cells", len(cells),
                          "bbox", (min(p[0] for p in cells), min(p[1] for p in cells), max(p[0] for p in cells), max(p[1] for p in cells)))
                return walls, doors
            return None

        queue = [set(room.cells)]
        rounds = 0
        while queue and rounds < 16:
            rounds += 1
            cells = queue.pop()
            if len(cells) <= chamber:
                continue
            got = split(cells)
            if got is None:
                continue
            walls, doors = got
            for (x, z) in walls:
                for yy in column((x, z)):
                    g.set(x, yy, z, block)
            for (x, z), f in doors.items():
                col = column((x, z))
                if not col or g.get(x, y - 1, z) == AIR:
                    continue
                g.set(x, y, z, f"minecraft:spruce_door[facing={f},half=lower,hinge=left]")
                g.set(x, y + 1, z, f"minecraft:spruce_door[facing={f},half=upper,hinge=left]")
                for yy in col[2:]:
                    g.set(x, yy, z, block)
            n += len(walls)
            rest = cells - walls - set(doors)
            # the parts either side, as connected pieces
            for part in _pieces(rest):
                if len(part) > chamber:
                    queue.append(part)
    return n
