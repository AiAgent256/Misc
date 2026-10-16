"""Walkability audit: can a soldier walk from the gate to every post? Mirrors what the mob pathfinder
accepts (two blocks of headroom, one-block steps, doors, ladders, open trapdoors) and reports the
markers it can't reach, so a furnished chest in a doorway or a parapet with no stair shows up at
build time instead of in game."""
from collections import deque

PASS_SUBSTR = ("door", "carpet", "pressure_plate", "button", "sign", "banner", "torch", "rail", "chain[",
               "_wall_hanging_sign", "ladder", "vine", "scaffolding", "water")
BLOCK_EXACT = ("minecraft:iron_door", "stormhold:portcullis")


def passable(state):
    """Can a 1x1x1 body occupy this cell?"""
    if state in ("minecraft:air", "<void>", "minecraft:cave_air"):
        return True
    if state.startswith("stormhold:portcullis"):
        return True                      # raised for the garrison
    if "trapdoor" in state:
        return "open=true" in state
    if "lantern" in state or "campfire" in state:
        return False
    return any(k in state for k in PASS_SUBSTR)


def climbable(state):
    return "ladder" in state or "vine" in state or "scaffolding" in state


def supports(state):
    """Can something stand on this cell? Solid, or a slab/stair."""
    if state in ("minecraft:air", "<void>", "minecraft:cave_air") or "water" in state or "lava" in state:
        return False
    if passable(state) and not climbable(state):
        return "carpet" in state
    return True


def reach(g, start, limit=4_000_000):
    """All cells a soldier can stand in, starting from `start` (x, y, z)."""
    X, Y, Z = g.X, g.Y, g.Z
    get = g.get
    seen = set()
    q = deque()

    def ok(x, y, z):
        if not (0 <= x < X and 1 <= y < Y - 1 and 0 <= z < Z):
            return False
        here, above = get(x, y, z), get(x, y + 1, z)
        if not (passable(here) and passable(above)):
            return False
        below = get(x, y - 1, z)
        return supports(below) or climbable(here) or climbable(below)

    sx, sy, sz = start
    # drop to the floor at the start
    while sy > 1 and not ok(sx, sy, sz) and ok(sx, sy - 1, sz):
        sy -= 1
    if not ok(sx, sy, sz):
        for dy in range(-3, 4):
            if ok(sx, sy + dy, sz):
                sy += dy
                break
    q.append((sx, sy, sz))
    seen.add((sx, sy, sz))
    while q and len(seen) < limit:
        x, y, z = q.popleft()
        here = get(x, y, z)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for dy in (0, 1, -1, -2, -3):
                nx, ny, nz = x + dx, y + dy, z + dz
                if (nx, ny, nz) in seen or not ok(nx, ny, nz):
                    continue
                if dy == 1 and not passable(get(x, y + 2, z)):
                    continue             # need headroom to step up
                if dy == 1 and not (supports(get(nx, ny - 1, nz))):
                    continue
                seen.add((nx, ny, nz))
                q.append((nx, ny, nz))
                break                    # take the highest reachable step in this direction
        if climbable(here) or climbable(get(x, y - 1, z)):
            for ny in (y + 1, y - 1):
                if (x, ny, z) not in seen and ok(x, ny, z):
                    seen.add((x, ny, z))
                    q.append((x, ny, z))
    return seen


def audit(g, meta, roles=("lord", "war_table", "banner_stand", "rally", "captain", "knight", "crossbowman", "archer",
                          "builder", "notice_board", "inn", "smithy", "barracks", "house")):
    """Return {role: [unreachable positions]} and the count checked."""
    gate = g.markers.get("gate", [None])[0]
    if gate is None:
        return {}, 0
    cells = reach(g, gate)
    bad = {}
    n = 0
    for role in roles:
        for (x, y, z) in g.markers.get(role, []):
            n += 1
            hit = any((x, y + dy, z) in cells for dy in (0, -1, 1, -2))
            if not hit:
                # a post may be marked a block off the floor; look at the 3x3 around it too
                hit = any((x + dx, y + dy, z + dz) in cells for dx in (-1, 0, 1) for dz in (-1, 0, 1) for dy in (-1, 0, 1))
            if not hit:
                bad.setdefault(role, []).append((x, y, z))
    return bad, n
