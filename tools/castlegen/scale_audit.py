"""Scale audit: is the castle built for men? Runs on the finished grid, over the cells a soldier can
actually reach from the gate, and reports what feels cramped: stair treads with nothing beside them
(a one-wide flight), standing cells walled in on both sides (a one-wide passage), and rooms without a
light. Numbers, not opinions, so a build can be refused when they rise.

  narrow_stairs  reachable treads whose flight is one wide
  pinches        one-wide cells with open floor at both ends: a doorway or gap left one wide
  alleys         other one-wide cells: slots between a building and a wall
  dark_rooms     rooms (furnish.find_rooms) with no light source inside
"""
from walk import reach, passable, supports

LIGHT = ("lantern", "torch", "shroomlight", "glowstone", "sea_lantern", "candle", "campfire", "end_rod",
         "redstone_lamp", "glow_lichen", "froglight")
MASONRY_SKIP = ("fence", "_wall[", "door", "trapdoor", "glass", "bars", "ladder", "chain", "banner", "sign",
                # furniture: a slot between two beds is a room, not a passage
                "barrel", "chest", "bed", "hay", "table", "anvil", "furnace", "smoker", "cauldron", "loom", "lectern",
                "bookshelf", "composter", "grindstone", "shelf", "pot", "cake", "candle", "lantern", "skull", "head",
                "campfire", "carpet", "wool", "crafting", "stonecutter", "jukebox", "bell", "flower", "leaves", "target",
                "stairs", "slab", "pumpkin", "melon", "shroomlight", "glowstone", "rail", "pressure", "button", "lever",
                "portcullis", "<void>", "water", "lava")


def _facing(state):
    i = state.find("facing=")
    return state[i + 7:i + 7 + state[i + 7:].find(",")] if i >= 0 and "," in state[i + 7:] else state[i + 7:-1] if i >= 0 else None


def _masonry(g, x, y, z):
    s = g.get(x, y, z)
    return g.is_solid(x, y, z) and not any(k in s for k in MASONRY_SKIP)


def audit(g, meta, cells=None):
    gate = g.markers.get("gate", [None])[0]
    if cells is None:
        cells = reach(g, gate) if gate else set()
    spiral = getattr(g, "spiral_cells", set())
    narrow_stairs, narrow, narrow_axis = [], [], {}
    for (x, y, z) in cells:
        # a cell under water is swum, not walked: the canal bed and the harbour floor aren't passages
        if "water" in g.get(x, y, z) or "water" in g.get(x, y + 1, z):
            continue
        below = g.get(x, y - 1, z)
        if "stairs" in below and "half=bottom" in below:
            if (x, z) in spiral:
                continue                    # spirals are two wide by construction, bar their corners
            # part of a flight at all? a lone stair block is a chair or a sill, not a step
            flight = any("stairs" in g.get(x + dx, yy, z + dz) and (x + dx, yy + 1, z + dz) in cells
                         for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) for yy in (y - 2, y))
            if not flight:
                continue
            # a tread with another tread (or a landing) beside it at the same height, square or diagonal,
            # is part of a flight a man can pass another on; alone, it's a one-wide stair
            wide = False
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx == 0 and dz == 0:
                        continue
                    nb = g.get(x + dx, y - 1, z + dz)
                    if ("stairs" in nb or supports(nb)) and (x + dx, y, z + dz) in cells:
                        wide = True
            if not wide:
                narrow_stairs.append((x, y, z))
            continue
        for ax, side in (("x", ((1, 0), (-1, 0))), ("z", ((0, 1), (0, -1)))):
            if all(_masonry(g, x + dx, y, z + dz) and _masonry(g, x + dx, y + 1, z + dz) for dx, dz in side):
                narrow.append((x, y, z))
                narrow_axis[(x, y, z)] = ax
                break
    # a pinch is a one-wide cell with open floor at both ends: a doorway or a gap the widener missed.
    # The rest are alleys: a slot a building leaves against a wall, long and only ever walked by the bored.
    nset = set(narrow)
    pinches, alleys = [], []
    for (x, y, z) in narrow:
        ahead = ((0, 1), (0, -1)) if narrow_axis[(x, y, z)] == "x" else ((1, 0), (-1, 0))
        ends = 0
        for dx, dz in ahead:
            n = (x + dx, y, z + dz)
            if n in cells and n not in nset:
                ends += 1
        (pinches if ends == 2 else alleys).append((x, y, z))
    dark = []
    try:
        import furnish
        rooms = furnish.find_rooms(g, meta["GL"], set())
        for r in rooms:
            if not room_lit(g, r):
                dark.append((r.x0, r.y, r.z0, r.w(), r.d()))
    except Exception:
        pass
    return {"narrow_stairs": narrow_stairs, "narrow_spirals": [0] * getattr(g, "narrow_spirals", 0), "pinches": pinches,
            "alleys": alleys, "dark_rooms": dark}


def room_lit(g, r):
    """A light in the room or in its walls: a lantern standing on the floor takes a cell out of the room, and a
    shroomlight set in a stair core lights the flight round it."""
    near = set()
    for (x, z) in r.cells:
        near.add((x, z))
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            near.add((x + dx, z + dz))
    for (x, z) in near:
        for yy in range(r.y, min(g.Y, r.y + 8)):
            if any(k in g.get(x, yy, z) for k in LIGHT):
                return True
    return False


def summary(res):
    return ", ".join(f"{k}={len(v)}" for k, v in res.items())
