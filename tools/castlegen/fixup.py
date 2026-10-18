"""Last pass before export: clear what blocks the garrison's way.

Hand-built plinths, string courses and stair skirts have a habit of running straight across a doorway
that was cut through the wall behind them. Rather than chase each one, look for the pattern: a low
solid cell with open floor on both sides of it along one axis, at ground level, and open it.
"""
from walk import passable, supports

AIR = "minecraft:air"


def _open(g, x, y, z):
    return passable(g.get(x, y, z)) and passable(g.get(x, y + 1, z))


def clear_blocked_doorways(g, GL, levels=()):
    """
    For each standing level (ground and any terraces), any 1-thick obstacle up to 3 high with walkable
    floor on both sides of it (same level) is a doorway blocker: open it and make sure there's a floor.
    Returns the number of cells opened.
    """
    n = 0
    for y0 in (GL,) + tuple(levels):
        for x in range(1, g.X - 1):
            for z in range(1, g.Z - 1):
                here = g.get(x, y0, z)
                if passable(here) or "water" in here:
                    continue
                if any(k in here for k in ("fence", "_wall", "lantern", "campfire", "chest", "barrel", "bed", "table",
                                            "anvil", "furnace", "smoker", "loom", "pot", "cauldron", "sign", "stairs", "slab",
                                            "log", "leaves", "hay", "wool", "glass", "composter", "bookshelf")):
                    continue                      # furniture, posts and steps, not masonry
                # obstacle no more than 3 high, open sky-side above it isn't required (it's a door)
                h = 0
                while h < 3 and not passable(g.get(x, y0 + h, z)):
                    h += 1
                if h == 3 and not passable(g.get(x, y0 + 3, z)):
                    continue                      # a wall, not a sill
                for (dx, dz) in ((1, 0), (0, 1)):
                    a = (x - dx, z - dz)
                    b = (x + dx, z + dz)
                    # part of a run of masonry (a sill, a plinth), not a lone block
                    run = (not passable(g.get(x + dz, y0, z + dx))) or (not passable(g.get(x - dz, y0, z - dx)))
                    if run and _open(g, a[0], y0, a[1]) and _open(g, b[0], y0, b[1]) \
                            and supports(g.get(a[0], y0 - 1, a[1])) and supports(g.get(b[0], y0 - 1, b[1])):
                        # don't cut through something that is itself a floor edge (a step in the terrain)
                        for yy in range(y0, y0 + max(h, 2)):
                            g.set(x, yy, z, AIR)
                        if not supports(g.get(x, y0 - 1, z)):
                            g.set(x, y0 - 1, z, "minecraft:stone_bricks")
                        n += 1
                        break
    return n


def drop_buried_markers(g, keep=("gate", "lord", "war_table", "banner_stand", "rally")):
    """Posts that ended up inside masonry (a gatehouse built over a wall-walk post) are dropped."""
    n = 0
    for role, lst in list(g.markers.items()):
        if role in keep:
            continue
        good = []
        for (x, y, z) in lst:
            if passable(g.get(x, y, z)) and passable(g.get(x, y + 1, z)):
                good.append((x, y, z))
            else:
                n += 1
        g.markers[role] = good
    return n


def light_rooms(g, GL, rev=1):
    """Every room gets a light: a lantern hung from the ceiling over its middle, where one is missing.
    From the second revision of a plan a light set in the room's walls counts, and a room with nothing over
    its middle to hang from gets a lantern standing in a corner. The first revision is kept exactly as it
    was, so plans already out in the world rebuild to the same blocks."""
    import furnish
    from scale_audit import LIGHT, room_lit
    n = 0
    for r in furnish.find_rooms(g, GL, set()):
        if rev >= 2:
            if room_lit(g, r):
                continue
        elif any(any(k in g.get(x, yy, z) for k in LIGHT) for (x, z) in r.cells for yy in range(r.y, min(g.Y, r.y + 8))):
            continue
        cx, cz = (r.x0 + r.x1) // 2, (r.z0 + r.z1) // 2
        if (cx, cz) not in r.cells:
            cx, cz = next(iter(sorted(r.cells)))
        yy = r.y + 2
        while yy + 1 < g.Y and g.get(cx, yy + 1, cz) == "minecraft:air":
            yy += 1
        if g.is_solid(cx, yy + 1, cz) and yy >= r.y + 2:
            # a tall room gets its lantern on a chain, so the light is down where the people are
            ly = min(yy, r.y + 5)
            g.fill(cx, ly + 1, cz, cx, yy, cz, "minecraft:chain[axis=y]") if yy > ly else None
            g.set(cx, ly, cz, "minecraft:lantern[hanging=true]")
            n += 1
            continue
        if rev < 2:
            continue
        # nothing over the middle to hang it from (a roof of stairs, a tower top): a lantern on the floor in
        # a corner, out of the way, with walls on two sides and no door beside it
        spot = _corner(g, r)
        if spot is not None:
            g.set(spot[0], r.y, spot[1], "minecraft:lantern[hanging=false]")
            n += 1
    return n


def _corner(g, r):
    best, best_walls = None, 1
    for (x, z) in sorted(r.cells):
        if g.get(x, r.y, z) != "minecraft:air":
            continue
        walls, door = 0, False
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            s = g.get(x + dx, r.y, z + dz)
            if "door" in s or "gate" in s:
                door = True
            if g.is_solid(x + dx, r.y, z + dz) and "door" not in s:
                walls += 1
        if door or (x, z + 0) not in r.cells:
            continue
        if walls > best_walls:
            best, best_walls = (x, z), walls
            if walls >= 2:
                break
    return best
