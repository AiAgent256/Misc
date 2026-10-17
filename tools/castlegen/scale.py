"""Human scale: a man is not a block wide. After a castle is built, every doorway one block wide is opened to two
(and three tall), door blocks become double doors, and straight stairs one wide between walls get a second tread.
Runs before furnishing, so the dressers see the wider openings."""
from walk import passable

AIR = "minecraft:air"


def _air(g, x, y, z):
    return g.get(x, y, z) in (AIR, "<void>")


def _door(g, x, y, z):
    return "_door[" in g.get(x, y, z)


def _solid_wall(g, x, y, z):
    s = g.get(x, y, z)
    return g.is_solid(x, y, z) and "[" not in s.split("minecraft:")[-1].replace("_", "") and not any(
        k in s for k in ("chest", "barrel", "sign", "banner", "bed", "ladder", "torch", "lantern", "stairs", "slab", "wall[", "fence", "door"))


def _plain_solid(g, x, y, z):
    s = g.get(x, y, z)
    if not g.is_solid(x, y, z):
        return False
    return not any(k in s for k in ("chest", "barrel", "sign", "banner", "bed", "ladder", "torch", "lantern", "stairs", "slab",
                                    "_wall", "fence", "door", "glass", "bars", "trapdoor", "anvil", "table", "furnace", "cauldron",
                                    "lectern", "loom", "composter", "grindstone", "shroomlight", "bookshelf", "hay", "cake", "candle",
                                    "skull", "head", "rail", "portcullis"))


def _wallish(g, x, y, z):
    """A wall cell a doorway may be widened into: plain masonry, or the window, shutter or fence beside a door."""
    if _plain_solid(g, x, y, z):
        return True
    s = g.get(x, y, z)
    return any(k in s for k in ("glass_pane", "trapdoor", "fence", "_wall[", "glass")) and "door" not in s.replace("trapdoor", "")


def _passage_cell(g, x, y, z):
    """A standing cell: floor under it, open two high."""
    if not g.is_solid(x, y - 1, z):
        return False
    s0, s1 = g.get(x, y, z), g.get(x, y + 1, z)
    if _door(g, x, y, z):
        return True
    return passable(s0) and passable(s1) and "ladder" not in s0 and "trapdoor" not in s0


def widen_doorways(g, GL, y_top=None):
    """Every one-wide opening through a wall (a doorway, a passage through a thick wall, a door) becomes two
    wide and three tall. Returns how many were opened."""
    protected = getattr(g, "protected_xz", set())
    y_top = g.Y - 4 if y_top is None else y_top
    opened = 0
    seen = set()
    for y in range(GL, y_top):
        for x in range(2, g.X - 2):
            for z in range(2, g.Z - 2):
                if (x, y, z) in seen or (x, z) in protected:
                    continue
                if not _passage_cell(g, x, y, z):
                    continue
                for axis in ("z", "x"):
                    # walls either side across the passage, open along it
                    if axis == "z":
                        side = ((1, 0), (-1, 0))
                        ahead = (0, 1)
                    else:
                        side = ((0, 1), (0, -1))
                        ahead = (1, 0)
                    if not all(_wallish(g, x + sx, y, z + sz) and _wallish(g, x + sx, y + 1, z + sz) for sx, sz in side):
                        continue
                    # the run of such cells along the passage
                    run = [(x, z)]
                    for d in (1, -1):
                        k = 1
                        while True:
                            nx, nz = x + ahead[0] * d * k, z + ahead[1] * d * k
                            if not _passage_cell(g, nx, y, nz) or (nx, nz) in protected:
                                break
                            if not all(_wallish(g, nx + sx, y, nz + sz) and _wallish(g, nx + sx, y + 1, nz + sz) for sx, sz in side):
                                break
                            run.append((nx, nz))
                            k += 1
                            if k > 12:
                                break
                    if len(run) > 12:
                        continue
                    run.sort()
                    (ax, az), (bx, bz) = run[0], run[-1]
                    end0 = (ax - ahead[0], az - ahead[1])
                    end1 = (bx + ahead[0], bz + ahead[1])
                    # both ends open onto floor: a room, a yard, a walk. Not a crenel over a drop, not a dead end.
                    if not all(_passage_cell(g, ex, y, ez) and not _door(g, ex, y, ez) for ex, ez in (end0, end1)):
                        continue
                    chosen = None
                    for sx, sz in side:
                        ok = True
                        for (rx, rz) in run:
                            cx_, cz_ = rx + sx, rz + sz
                            if (cx_, cz_) in protected or not _wallish(g, cx_, y, cz_) or not _wallish(g, cx_, y + 1, cz_):
                                ok = False
                                break
                            # the second leaf must not open sideways into something else: beyond it is wall or the same run
                            bx_, bz_ = cx_ + sx, cz_ + sz
                            if not (_wallish(g, bx_, y, bz_) or (bx_, bz_) in run):
                                ok = False
                                break
                        # and open onto the same two spaces at the ends
                        for ex, ez in (end0, end1):
                            if ok and not _passage_cell(g, ex + sx, y, ez + sz):
                                ok = False
                        if ok:
                            chosen = (sx, sz)
                            break
                    if chosen is None:
                        continue
                    sx, sz = chosen
                    for (rx, rz) in run:
                        seen.add((rx, y, rz))
                        seen.add((rx + sx, y, rz + sz))
                        # the second leaf of the doorway, three high
                        for yy in (y, y + 1, y + 2):
                            if yy == y + 2 and not g.is_solid(rx + sx, y + 3, rz + sz):
                                break
                            g.set(rx + sx, yy, rz + sz, AIR)
                        # the lintel of the first leaf up to three high too
                        if g.is_solid(rx, y + 2, rz) and g.is_solid(rx, y + 3, rz) and _plain_solid(g, rx, y + 2, rz):
                            g.set(rx, y + 2, rz, AIR)
                        # a door becomes a double door
                        d0 = g.get(rx, y, rz)
                        if "_door[" in d0:
                            name, props = d0.split("[")
                            props = props.rstrip("]")
                            kv = dict(p.split("=") for p in props.split(","))
                            kv["hinge"] = "right" if kv.get("hinge") == "left" else "left"
                            lower = name + "[" + ",".join(f"{k}={v}" for k, v in kv.items()) + "]"
                            kv["half"] = "upper"
                            upper = name + "[" + ",".join(f"{k}={v}" for k, v in kv.items()) + "]"
                            g.set(rx + sx, y, rz + sz, lower)
                            g.set(rx + sx, y + 1, rz + sz, upper)
                    opened += 1
                    break
    return opened
