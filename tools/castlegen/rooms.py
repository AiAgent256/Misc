"""What each room is for, and how it's dressed.

A castle is a programme, not a bag of rooms: the kitchen is next to the hall, the cellar under it,
the chapel up a tower, the lord's chamber at the top of the keep, guardrooms by the gate, the dungeon
as low and far from the gate as it gets. plan() hands out those jobs by position, with quotas, so a
watchtower doesn't end up with two mess halls and no kitchen. Each castle also has a character (a
temper) that colours the dressing: a martial house hangs racks and targets, a rich one carpets and
tapestries, a pious one candles, a grim one chains.
"""
import math

import shell

from furnish import AIR, LOOT, Dresser, _Guarded, find_rooms

CHARACTERS = {
    # carpets, torch, lantern, wall-banner spacing, the flavour rooms it wants, and a few knobs
    "martial": dict(carpets=("gray", "red", "light_gray"), torch="minecraft:wall_torch", lantern="lantern",
                    banner_every=4, flavour=("armoury", "guardroom", "mews", "armoury"), cobweb=0.0, skull=False,
                    canopy="minecraft:red_wool", rug="gray"),
    "rich": dict(carpets=("red", "purple", "blue"), torch="minecraft:wall_torch", lantern="lantern",
                 banner_every=3, flavour=("library", "wardrobe", "infirmary", "chapel"), cobweb=0.0, skull=False,
                 canopy="minecraft:purple_wool", rug="red"),
    "pious": dict(carpets=("white", "purple", "light_gray"), torch="minecraft:wall_torch", lantern="lantern",
                  banner_every=5, flavour=("chapel", "scriptorium", "infirmary", "oratory"), cobweb=0.0, skull=False,
                  canopy="minecraft:white_wool", rug="white"),
    "grim": dict(carpets=("black", "gray"), torch="minecraft:soul_wall_torch", lantern="soul_lantern",
                 banner_every=5, flavour=("dungeon", "dungeon", "guardroom", "cellar"), cobweb=0.15, skull=True,
                 canopy="minecraft:black_wool", rug="black"),
    "rustic": dict(carpets=("brown", "green", "orange"), torch="minecraft:wall_torch", lantern="lantern",
                   banner_every=6, flavour=("brewhouse", "kennels", "store", "stable"), cobweb=0.05, skull=False,
                   canopy="minecraft:brown_wool", rug="brown"),
}


# captain + knights + archers + crossbowmen the castle must sleep, by tier (mirrors Holding.CAPACITY)
MEN = {1: 12, 2: 24, 3: 46, 4: 79}


def beds_in(g, cells=None, y=None):
    """Beds (foot blocks) in the whole grid, or in one room's cells at its level and the bunk above."""
    n = 0
    if cells is None:
        for st in g.palette:
            if st.endswith("part=foot]") and "_bed[" in st:
                n += int((g.a == g.index[st]).sum())
        return n
    scan = set(cells)
    for (x, z) in cells:
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            scan.add((x + dx, z + dz))
    for (x, z) in scan:
        for yy in (y, y + 1):
            st = g.get(x, yy, z)
            if "_bed[" in st and "part=foot" in st:
                n += 1
    return n


def character_for(name, seed=0):
    """A stable temper per layout name; the second variant of a layout gets a different one."""
    order = ["martial", "rich", "grim", "rustic", "pious"]
    h = sum(ord(c) * (i + 1) for i, c in enumerate(name)) + seed * 7
    return order[h % len(order)]


# ---------------------------------------------------------------- what is this room?


def _contents(g, room):
    y = room.y
    names, loot, markers = {}, {}, set()
    scan = set(room.cells)
    for (x, z) in room.cells:
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            scan.add((x + dx, z + dz))
    for (x, z) in scan:
        for yy in (y, y + 1):
            s = g.get(x, yy, z).split("[")[0]
            names[s] = names.get(s, 0) + 1
            nb = g.block_nbt.get((x, yy, z))
            if nb and "LootTable" in nb:
                k = nb["LootTable"].rsplit("/", 1)[-1]
                loot[k] = loot.get(k, 0) + 1
    for role, pts in g.markers.items():
        for (mx, my, mz) in pts:
            if my == y and (mx, mz) in room.cells:
                markers.add(role)
    return names, loot, markers


def fixed_kind(g, room):
    """Rooms whose builder already said what they are. None if open; False if not a room to dress."""
    names, loot, markers = _contents(g, room)
    beds = sum(v for k, v in names.items() if k.endswith("_bed")) // 2
    if any(k in markers for k in ("notice_board", "inn", "smithy", "house")):
        return False                                   # the town builder dressed these
    if "lord" in markers or "war_table" in markers:
        return "hall" if room.size() > 60 else "chamber"
    carpets = sum(v for k, v in names.items() if k.endswith("_carpet"))
    if carpets >= 8 or (names.get("minecraft:dark_oak_fence", 0) >= 4 and sum(v for k, v in names.items() if k.endswith("_slab")) >= 4):
        return "hall" if room.size() > 60 else "chamber"     # the builder laid the tables already
    if "banner_stand" in markers:
        return False
    if any(k in names for k in ("minecraft:smoker", "minecraft:water_cauldron")) or "castle_kitchen" in loot:
        return "kitchen"
    if "minecraft:anvil" in names or "castle_smithy" in loot:
        return "smithy"
    if any("treasury" in k for k in loot):
        return "treasury"
    if beds >= 3:
        return "barracks"
    if beds >= 1:
        return "lodging"
    if names.get("minecraft:candle", 0) + sum(v for k, v in names.items() if "stained_glass" in k and "gray" not in k) >= 5:
        return "chapel"
    if names.get("minecraft:hay_block", 0) >= 3 and room.size() > 40:
        return "stable"
    if any(k in markers for k in ("notice_board", "inn", "smithy", "house")):
        return False
    return None


# ---------------------------------------------------------------- the programme


class Site:
    """A room plus where it sits in the castle."""

    def __init__(self, room, GL, gate, lord, g):
        self.room = room
        self.lvl = room.y - GL
        self.cx, self.cz = (room.x0 + room.x1) / 2, (room.z0 + room.z1) / 2
        self.gate_d = abs(self.cx - gate[0]) + abs(self.cz - gate[2]) if gate else 999
        self.in_keep = bool(lord) and abs(self.cx - lord[0]) <= 18 and abs(self.cz - lord[2]) <= 18
        w, d = room.w(), room.d()
        self.tower = max(w, d) <= 17 and 0.6 <= w / d <= 1.6 and room.size() <= 160
        self.rect = room.size() >= 0.8 * w * d
        self.top = True          # set by plan(): no room above in the same column
        self.kind = None
        dr = Dresser(_Guarded(g), room, protected_of(g))
        self.usable = len(dr.wall) + len(dr.inner)

    def dist(self, other):
        return abs(self.cx - other.cx) + abs(self.cz - other.cz) + abs(self.room.y - other.room.y) * 2


def protected_of(g):
    protected = set()
    for role, pts in g.markers.items():
        for (x, y, z) in pts:
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    protected.add((x + dx, y, z + dz))
                    protected.add((x + dx, y + 1, z + dz))
    return protected


def plan(g, GL, character, tier=2):
    """Assign a kind to every room. Returns [(room, kind)] for the ones to dress."""
    rooms = find_rooms(g, GL, set())
    gate = (g.markers.get("gate") or [None])[0]
    lord = (g.markers.get("lord") or [None])[0]
    sites = [Site(r, GL, gate, lord, g) for r in rooms]
    for s in sites:
        for o in sites:
            if o is not s and o.room.y > s.room.y and abs(o.cx - s.cx) < 5 and abs(o.cz - s.cz) < 5:
                s.top = False
    for s in sites:
        k = fixed_kind(g, s.room)
        if k is not None:
            s.kind = k
    rng = g.rng
    ch = CHARACTERS[character]
    have = lambda kind: [s for s in sites if s.kind == kind]
    free = lambda: [s for s in sites if s.kind is None]

    def pick(kind, ok, score, n=1):
        for _ in range(n):
            cands = [s for s in free() if ok(s)]
            if not cands:
                return
            best = max(cands, key=score)
            best.kind = kind

    def nearest(kind, s):
        hs = have(kind)
        return min((s.dist(h) for h in hs), default=60)

    n_rooms = len(sites)
    def sleep_the_men():
        # barracks: enough to sleep the garrison. Bunks along the walls, two beds a bunk, one bunk per three wall cells.
        men = MEN.get(tier, 24)
        have_beds = sum(beds_in(g, s.room.cells, s.room.y) for s in sites if s.kind in ("barracks", "lodging", "chamber"))
        while have_beds < men:
            # a plain rectangular range first; a lodging the builder laid out with a few beds will do; a tower floor last
            cands = [s for s in free() if 0 <= s.lvl <= 3 and s.room.size() >= 36 and s.usable >= 18 and not s.in_keep and s.rect and not s.tower]
            if not cands:
                cands = [s for s in sites if s.kind == "lodging" and s.usable >= 18] + \
                        [s for s in free() if s.room.size() >= 30 and s.usable >= 14]
            if not cands:
                break
            best = max(cands, key=lambda s: s.usable)
            if best.kind == "lodging":
                have_beds -= beds_in(g, best.room.cells, best.room.y)
            best.kind = "barracks"
            have_beds += min(48, (best.usable // 4) * 2)

    # a small castle sleeps its men before it cooks for them
    if n_rooms < 8:
        sleep_the_men()
    # the kitchen: ground, by the hall, not at the gate
    if not have("kitchen"):
        pick("kitchen", lambda s: 0 <= s.lvl <= 3 and 28 <= s.room.size() <= 220 and s.gate_d > 14 and s.usable >= 16 and (not s.in_keep or n_rooms < 6),
             lambda s: -nearest("hall", s) - (10 if s.tower else 0) - abs(s.room.size() - 70) / 8)
    # the cellar: as low as it goes, under the kitchen if possible
    pick("cellar", lambda s: s.lvl <= 1 and 16 <= s.room.size() <= 320,
         lambda s: -s.lvl * 12 - nearest("kitchen", s))
    # guardrooms: anything on the ground within reach of the gate
    pick("guardroom", lambda s: s.lvl <= 2 and s.gate_d <= 18 and s.room.size() <= 130, lambda s: -s.gate_d, n=3)
    # the lord's chamber: top of the keep
    if n_rooms >= 4:
        pick("solar", lambda s: s.lvl >= 2 and 24 <= s.room.size() <= 220 and s.usable >= 20 and (s.in_keep or not lord),
             lambda s: s.room.y * 2 + (8 if s.top else 0) + s.usable / 4)
        if not have("solar"):
            pick("solar", lambda s: s.lvl >= 2 and 24 <= s.room.size() <= 220 and s.usable >= 16, lambda s: s.room.y + s.usable / 4)
    # the chapel: up a tower, with a window
    if n_rooms >= 5:
        pick("chapel", lambda s: s.lvl >= 1 and 24 <= s.room.size() <= 170 and s.usable >= 16,
             lambda s: s.lvl * 2 + (6 if s.tower else 0) + (4 if s.top else 0) + (4 if s.rect else 0))
    if n_rooms >= 8:
        sleep_the_men()
    if n_rooms >= 5:
        pick("armoury", lambda s: 18 <= s.room.size() <= 140, lambda s: -nearest("barracks", s) - nearest("guardroom", s) / 3)
    # the library, in the keep if there is one
    if n_rooms >= 7:
        pick("library", lambda s: s.lvl >= 1 and 20 <= s.room.size() <= 140 and s.usable >= 14,
             lambda s: s.lvl + (5 if s.in_keep else 0) - (3 if s.tower else 0))
    # the well: a small ground room near the kitchen
    if n_rooms >= 8:
        pick("well", lambda s: 0 <= s.lvl <= 1 and 25 <= s.room.size() <= 80 and min(s.room.w(), s.room.d()) >= 7,
             lambda s: -nearest("kitchen", s))
    # the dungeon: low and far from the gate
    if n_rooms >= 8:
        pick("dungeon", lambda s: s.lvl <= 1 and 24 <= s.room.size() <= 220 and min(s.room.w(), s.room.d()) >= 5 and s.rect and s.usable >= 20,
             lambda s: -s.lvl * 6 + s.gate_d / 8 + (4 if s.in_keep else 0))
    # a smithy by the gate
    if not have("smithy") and n_rooms >= 9:
        pick("smithy", lambda s: 0 <= s.lvl <= 1 and 18 <= s.room.size() <= 110, lambda s: -s.gate_d)
    # one mess for the men, the biggest ordinary room low down
    if n_rooms >= 12:
        pick("mess", lambda s: 0 <= s.lvl <= 3 and 48 <= s.room.size() <= 400 and not s.in_keep, lambda s: s.room.size())
    # a treasury in the keep if the builder didn't make one
    if not have("treasury") and n_rooms >= 10:
        pick("treasury", lambda s: 12 <= s.room.size() <= 90 and (s.in_keep or s.lvl < 0), lambda s: abs(s.lvl) * 2 + (3 if s.in_keep else 0))
    # what the house's temper asks for
    extra = {"library": 20, "wardrobe": 12, "infirmary": 30, "chapel": 24, "scriptorium": 20, "oratory": 12,
             "dungeon": 24, "guardroom": 12, "cellar": 16, "brewhouse": 16, "kennels": 16, "store": 12, "stable": 40,
             "armoury": 18, "mews": 16}
    for i, kind in enumerate(ch["flavour"]):
        if n_rooms < 6 + i * 3:
            break
        lo = extra[kind]
        pick(kind, lambda s, lo=lo, kind=kind: lo <= s.room.size() <= 200 and s.usable >= 10 and
             (s.lvl <= 1 if kind in ("dungeon", "cellar", "brewhouse", "kennels", "stable", "mews", "guardroom") else s.lvl >= 1),
             lambda s, kind=kind: (-s.lvl if kind in ("dungeon", "cellar") else s.lvl) + rng.random())
    # the rest: a spread, so a castle with forty rooms isn't thirty stores
    UPPER = (("quarters", 30), ("store", 12), ("armoury", 9), ("library", 7), ("oratory", 5), ("infirmary", 6),
             ("wardrobe", 5), ("guardroom", 8), ("chamber", 8), ("scriptorium", 4), ("mews", 3), ("cellar", 3))
    GROUND = (("store", 26), ("guardroom", 22), ("cellar", 12), ("kennels", 6), ("brewhouse", 8), ("stable", 5),
              ("smithy", 5), ("well", 4), ("armoury", 6), ("quarters", 6))
    NEEDS = {"well": 25, "stable": 40, "library": 14, "infirmary": 20, "chamber": 16, "oratory": 14, "scriptorium": 14,
             "wardrobe": 10, "brewhouse": 12, "kennels": 10, "mews": 10, "smithy": 14, "cellar": 10, "armoury": 10}
    for s in free():
        table = UPPER if s.lvl >= 1 else GROUND
        kinds = [k for k, w in table if s.usable >= NEEDS.get(k, 0) and not (k == "well" and min(s.room.w(), s.room.d()) < 7)]
        ws = [w for k, w in table if k in kinds]
        if not kinds:
            kinds, ws = ["store", "guardroom"], [1, 1]
        s.kind = rng.choice(kinds, p=[w / sum(ws) for w in ws])
    return [(s.room, s.kind) for s in sites if s.kind]


# ---------------------------------------------------------------- dressing


def _wall_banners(d, every, p=1.0):
    n = 0
    for (x, z), f in d.wall_cells(shuffle=False):
        n += 1
        if n % every:
            continue
        if d.g.get(x, d.y + 2, z) != AIR:
            continue
        wx, wz = x - f[0], z - f[1]
        if d.solid[d.g.a[wx, wz, d.y + 2]] and d.rng.random() < p:
            d.g.wall_banner(x, d.y + 2, z, d.facing(f))


def _skulls(d, ch, n=2):
    if not ch["skull"]:
        return
    k = 0
    for (x, z), f in d.wall_cells():
        if k >= n:
            break
        if d.free(x, z):
            d.put(x, z, f"minecraft:skeleton_skull[rotation={(x * 3 + z) % 16}]")
            k += 1


def _cobwebs(d, ch):
    if ch["cobweb"] <= 0:
        return
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y + 2, z) == AIR and d.rng.random() < ch["cobweb"]:
            d.g.set(x, d.y + 2, z, "minecraft:cobweb")


def _finish(d, ch, every=5, beams=True, sconce_every=4):
    if beams:
        d.beams()
    d.sconces(every=sconce_every, block=ch["torch"])
    d.lanterns(every=every)
    _cobwebs(d, ch)


def dress_hall(d, room, ch, dais=True):
    axis = "x" if room.w() >= room.d() else "z"
    long_ = room.w() if axis == "x" else room.d()
    short = room.d() if axis == "x" else room.w()
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    side = d.far_side()
    raised = None
    if dais and long_ >= 11 and ((side in ("x0", "x1")) == (axis == "x")):
        raised = d.dais(side)
    if raised:
        # the high table across the dais, chairs on the wall side only
        f = d.side_facing(side)
        row = d.side_cells(side, 1)[0]
        inner = [c for c in row if c in raised]
        if len(inner) >= 5:
            k = (len(inner) - min(len(inner) - 2, 9)) // 2
            cells = inner[k:k + min(len(inner) - 2, 9)]
            # table one cell in from the wall so the lord can sit behind it
            x, z = cells[0][0] + f[0], cells[0][1] + f[1]
            d.table(x, z, len(cells), "z" if side in ("x0", "x1") else "x", dy=1, chair_sides=((-f[0], -f[1]),), ignore_used=True,
                    dressing=["minecraft:candle[candles=3,lit=true]", "minecraft:cake", "minecraft:lantern[hanging=false]", AIR])
            for (x, z) in cells:
                if d.g.get(x, d.y + 3, z) == AIR:
                    wx, wz = x - f[0], z - f[1]
                    if d.solid[d.g.a[wx, wz, d.y + 3]] and (x + z) % 3 == 0:
                        d.g.wall_banner(x, d.y + 3, z, d.facing(f))
    tlen = max(4, min(14, long_ - (9 if raised else 6)))
    rows = [0] if short < 9 else [-2, 2] if short < 15 else [-3, 0, 3]
    # tables sit toward the door end, leaving the dais its own space
    shift0 = 0 if not raised else (-2 if side in ("x1", "z1") else 2)
    for off in rows:
        for shift in (shift0, shift0 - 3, shift0 + 3, shift0 - 5, shift0 + 5):
            if axis == "x":
                ok = d.table(cx - tlen // 2 + shift, cz + off, tlen, "x")
            else:
                ok = d.table(cx + off, cz - tlen // 2 + shift, tlen, "z")
            if ok:
                break
    _wall_banners(d, ch["banner_every"])
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
                 lambda x, z, f: d.put(x, z, f"minecraft:{ch['lantern']}[hanging=false]")], limit=max(4, room.size() // 25))
    if raised:
        d.runner(ch["rug"] if ch["rug"] != "black" else "red", side)
    else:
        d.carpet(ch["carpets"][0], p=0.12)
    _skulls(d, ch, 1)
    _finish(d, ch, every=6)


def dress_mess(d, room, ch):
    dress_hall(d, room, ch, dais=False)


def dress_kitchen(d, room, ch):
    # the hearth wall: fire in the middle, ovens either side, a brick hood and a chimney breast
    side = d.far_side()
    f = d.side_facing(side)
    row = d.side_cells(side, 1)[0]
    mid = len(row) // 2
    placed = False
    for k in (mid, mid - 1, mid + 1, mid - 2, mid + 2):
        if 0 < k < len(row) - 1:
            (x, z), (ax, az), (bx, bz) = row[k], row[k - 1], row[k + 1]
            if all(d.free(c[0], c[1]) for c in ((x, z), (ax, az), (bx, bz))) and d.free(x + f[0], z + f[1]):
                d.put(x, z, "minecraft:campfire[lit=true,signal_fire=false]")
                d.put(ax, az, f"minecraft:smoker[facing={d.facing(f)},lit=true]")
                d.put(bx, bz, f"minecraft:furnace[facing={d.facing(f)},lit=false]")
                d.take(x + f[0], z + f[1])
                for (cx_, cz_) in ((ax, az), (bx, bz)):
                    if d.g.get(cx_, d.y + 1, cz_) == AIR:
                        d.g.set(cx_, d.y + 1, cz_, "minecraft:bricks")
                if d.g.get(x, d.y + 1, z) == AIR:
                    d.g.set(x, d.y + 1, z, AIR)
                for (cx_, cz_) in ((x, z), (ax, az), (bx, bz)):
                    if d.g.get(cx_, d.y + 2, cz_) == AIR:
                        d.g.stair(cx_, d.y + 2, cz_, "minecraft:brick_stairs", d.facing(f), half="top")
                    for h in range(3, room.height):
                        if d.g.get(cx_, d.y + h, cz_) == AIR:
                            d.g.set(cx_, d.y + h, cz_, "minecraft:bricks")
                placed = True
                break
    if not placed:
        d.hearth()
    # a prep table down the middle, no chairs
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    axis = "x" if room.w() >= room.d() else "z"
    ln = max(3, min(7, (room.w() if axis == "x" else room.d()) - 4))
    d.table(cx - ln // 2 if axis == "x" else cx, cz if axis == "x" else cz - ln // 2, ln, axis, chairs=False,
            top="minecraft:dark_oak_slab[type=top]",
            dressing=["minecraft:cake", "minecraft:candle[candles=1,lit=true]", "minecraft:decorated_pot",
                      "minecraft:flower_pot", AIR])
    d.fill_wall([lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f),
                 lambda x, z, f: (d.barrel(x, z, "castle_granary", f=f), d.g.barrel(x, d.y + 1, z, LOOT + "castle_granary", facing=d.facing(f))),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:crafting_table"),
                 lambda x, z, f: d.put(x, z, "minecraft:composter[level=3]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_kitchen"),
                 lambda x, z, f: d.put(x, z, "minecraft:pumpkin"),
                 lambda x, z, f: d.put(x, z, "minecraft:honey_block" if d.rng.random() < 0.3 else "minecraft:melon")],
                limit=max(6, int(room.size() * 0.3)))
    # shelves over the stores
    for (x, z), f in d.wall_cells(shuffle=False):
        if (x, z) in d.used and d.g.get(x, d.y + 1, z) == AIR and d.g.get(x, d.y + 2, z) == AIR and (x * 5 + z) % 3 == 0:
            d.g.set(x, d.y + 1, z, "minecraft:dark_oak_slab[type=top]")
            d.g.set(x, d.y + 2, z, d.rng.choice(["minecraft:flower_pot", "minecraft:decorated_pot", "minecraft:candle[candles=2,lit=true]", "minecraft:cake"]))
    # hams and herbs from the beams
    for (x, z) in list(d.inner)[::4]:
        d.hang(x, z, drop=1, end=d.rng.choice([f"minecraft:{ch['lantern']}[hanging=true]", "minecraft:hay_block[axis=y]", AIR]))
    _finish(d, ch, every=5, sconce_every=5)


def _doors_connected(d, room):
    """Can you still walk between every doorway of the room? (Furniture may not cut the room in two.)"""
    # doorways, and every cell the dresser keeps clear (stair heads, passages): all must stay joined
    doors = list(room.doorways) + [c for c in d.clear if c in room.cells]
    if len(doors) < 2:
        return True
    g = d.g

    def open_(x, z):
        a, b = g.get(x, d.y, z), g.get(x, d.y + 1, z)
        return (a == AIR or "carpet" in a) and b == AIR

    start = doors[0]
    seen = {start}
    stack = [start]
    while stack:
        x, z = stack.pop()
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in seen or n not in room.cells:
                continue
            if n in room.doorways or open_(*n):
                seen.add(n)
                stack.append(n)
    return all(dr in seen or not open_(*dr) for dr in doors)


def _unbed(d, x, z, f):
    for (cx, cz) in ((x, z), (x + f[0], z + f[1])):
        for yy in (d.y, d.y + 1):
            d.g.set(cx, yy, cz, AIR)
        d.used.discard((cx, cz))


def dress_barracks(d, room, ch, bunks=True):
    colors = ["brown", "gray", "light_gray", "white"]
    beds = chests = 0
    cap = 24 if bunks else 6
    for (x, z), f in d.wall_cells(shuffle=False):
        if beds >= cap or beds * 2 + 2 > room.size() // (2 if bunks else 3):
            break
        if (x + z) % 3 == 0:
            ok = d.bunk(x, z, f, colors[(x * 7 + z) % 4]) if bunks else d.bed(x, z, f, colors[(x * 7 + z) % 4])
            if ok and not _doors_connected(d, room):
                _unbed(d, x, z, f)
                ok = False
            if ok:
                beds += 1
        elif (x + z) % 3 == 1 and d.free(x, z) and chests < beds:
            d.chest(x, z, f, "castle_barracks")
            chests += 1
    # a big barracks sleeps its men in rows down the middle too, an aisle between every pair of rows;
    # a row that would cut the way between the room's doors is taken out again
    if bunks and len(d.inner) >= 40:
        axis = "x" if room.w() >= room.d() else "z"
        inner = sorted(d.inner, key=lambda c: (c[1], c[0]) if axis == "x" else (c[0], c[1]))
        rows = {}
        for (x, z) in inner:
            row = (z - room.z0) if axis == "x" else (x - room.x0)
            if row % 3 == 1 and ((x - room.x0) if axis == "x" else (z - room.z0)) % 3 == 0:
                rows.setdefault(row, []).append((x, z))
        f = (1, 0) if axis == "x" else (0, 1)
        for row in sorted(rows):
            if beds >= 30:
                break
            placed = []
            for (x, z) in rows[row]:
                if d.bunk(x, z, f, colors[(x * 7 + z) % 4]):
                    placed.append((x, z))
                    beds += 1
            if placed and not _doors_connected(d, room):
                for (x, z) in placed:
                    _unbed(d, x, z, f)
                    beds -= 1
    d.fill_wall([lambda x, z, f: d.barrel(x, z, "castle_barracks", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:crafting_table"),
                 lambda x, z, f: d.put(x, z, f"minecraft:grindstone[face=floor,facing={d.facing(f)}]"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f)], limit=3)
    if room.size() > 40 and beds < 12:
        cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
        axis = "x" if room.w() >= room.d() else "z"
        d.table(cx - 2 if axis == "x" else cx, cz if axis == "x" else cz - 2, 5, axis)
    if room.size() > 60:
        d.hearth()
    d.pegs()
    if ch["rug"] == "gray":
        d.carpet("gray", p=0.1)
    _finish(d, ch, every=5, sconce_every=5)


def dress_quarters(d, room, ch):
    dress_barracks(d, room, ch, bunks=False)
    d.carpet(d.rng.choice(list(ch["carpets"])), p=0.2)


def dress_chamber(d, room, ch):
    has_bed = any("_bed[" in d.g.get(x, d.y, z) for (x, z) in room.cells)
    if not has_bed:
        for (x, z), f in d.wall_cells():
            if d.canopy_bed(x, z, f, "red", canopy=ch["canopy"]):
                break
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_solar"),
                 lambda x, z, f: d.put(x, z, f"minecraft:chiseled_bookshelf[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, f"minecraft:lectern[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.put(x, z, "minecraft:potted_azalea_bush" if d.rng.random() < 0.5 else "minecraft:flower_pot"),
                 lambda x, z, f: d.put(x, z, "minecraft:cartography_table"),
                 lambda x, z, f: d.barrel(x, z, "castle_solar", f=f)], limit=max(3, room.size() // 8))
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y, z) == "minecraft:bookshelf" and d.g.get(x, d.y + 1, z) == AIR:
            d.g.set(x, d.y + 1, z, "minecraft:bookshelf")
    d.rug(ch["rug"], min(5, room.w() - 2), min(5, room.d() - 2))
    if room.size() > 30:
        d.hearth()
    _wall_banners(d, ch["banner_every"] + 2)
    _finish(d, ch, every=4)


def dress_lodging(d, room, ch):
    """A room the builder already put a bed in: a chest, pegs, a rug, a candle. Light."""
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_barracks"),
                 lambda x, z, f: d.put(x, z, "minecraft:barrel[facing=up]", nbt={"id": "minecraft:barrel", "LootTable": LOOT + "castle_barracks"}),
                 lambda x, z, f: d.put(x, z, "minecraft:flower_pot"),
                 lambda x, z, f: d.put(x, z, "minecraft:crafting_table")], limit=max(2, room.size() // 14))
    d.pegs(every=4)
    d.rug(d.rng.choice(list(ch["carpets"])), 3, 3)
    _finish(d, ch, every=5, beams=room.size() > 40, sconce_every=6)


def dress_solar(d, room, ch):
    """The lord's own chamber: a four-poster, a desk by the window, a strongbox, the house colours."""
    side = d.far_side()
    d.window(side, "minecraft:glass_pane", width=3 if room.w() > 7 else 1, height=2)
    for (x, z), f in d.wall_cells():
        if d.canopy_bed(x, z, f, "red", canopy=ch["canopy"]):
            break
    # a desk: a table of two with one chair, by the far wall
    row = d.side_cells(side, 2)
    f = d.side_facing(side)
    for (x, z) in row[1]:
        if d.free(x, z, 2):
            ax = "x" if side in ("z0", "z1") else "z"
            if d.table(x, z, 2, ax, chairs=False, top="minecraft:dark_oak_slab[type=top]",
                       dressing=["minecraft:candle[candles=1,lit=true]", "minecraft:flower_pot"]):
                sx, sz = x + f[0], z + f[1]
                if d.free(sx, sz):
                    d.g.stair(sx, d.y, sz, "minecraft:dark_oak_stairs", d.facing((-f[0], -f[1])))
                    d.take(sx, sz)
                break
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_solar"),
                 lambda x, z, f: d.put(x, z, f"minecraft:chiseled_bookshelf[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.barrel(x, z, "castle_solar", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:potted_azalea_bush"),
                 lambda x, z, f: d.put(x, z, f"minecraft:lectern[facing={d.facing(f)}]")], limit=max(4, room.size() // 7))
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y, z) == "minecraft:bookshelf" and d.g.get(x, d.y + 1, z) == AIR:
            d.g.set(x, d.y + 1, z, "minecraft:bookshelf")
    d.rug(ch["rug"], min(7, room.w() - 2), min(7, room.d() - 2))
    d.hearth()
    _wall_banners(d, max(2, ch["banner_every"] - 1))
    _finish(d, ch, every=4)


def dress_library(d, room, ch):
    # shelves two high on every free wall cell, a reading table in the middle, candles
    for (x, z), f in d.wall_cells(shuffle=False):
        if d.free(x, z) and (x + z) % 5 != 0:
            d.put(x, z, "minecraft:bookshelf" if (x * 3 + z) % 4 else f"minecraft:chiseled_bookshelf[facing={d.facing(f)}]")
            if d.g.get(x, d.y + 1, z) == AIR:
                d.g.set(x, d.y + 1, z, "minecraft:bookshelf")
            if d.g.get(x, d.y + 2, z) == AIR and room.height >= 4 and (x + z) % 2:
                d.g.set(x, d.y + 2, z, "minecraft:bookshelf")
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    axis = "x" if room.w() >= room.d() else "z"
    ln = max(3, min(7, (room.w() if axis == "x" else room.d()) - 4))
    d.table(cx - ln // 2 if axis == "x" else cx, cz if axis == "x" else cz - ln // 2, ln, axis,
            dressing=["minecraft:candle[candles=3,lit=true]", "minecraft:candle[candles=1,lit=true]", AIR])
    d.fill_wall([lambda x, z, f: d.put(x, z, f"minecraft:lectern[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:cartography_table"),
                 lambda x, z, f: d.chest(x, z, f, "castle_solar")], limit=3)
    d.rug(ch["carpets"][1], min(5, room.w() - 2), min(5, room.d() - 2))
    _finish(d, ch, every=4, sconce_every=6)


def dress_scriptorium(d, room, ch):
    dress_library(d, room, ch)
    for (x, z) in d.inner:
        if d.free(x, z, 2) and (x * 7 + z) % 6 == 0:
            d.put(x, z, f"minecraft:lectern[facing={['north', 'south', 'east', 'west'][(x + z) % 4]}]")


def dress_chapel(d, room, ch):
    side = d.far_side()
    f = d.side_facing(side)
    axis = "z" if side in ("x0", "x1") else "x"      # the aisle runs toward the altar
    d.window(side, "minecraft:purple_stained_glass_pane" if ch["rug"] != "white" else "minecraft:light_blue_stained_glass_pane",
             width=3 if (room.w() if axis == "z" else room.d()) >= 7 else 1, height=3 if room.height >= 5 else 2)
    raised = d.dais(side, depth=1, block="minecraft:polished_andesite", step="minecraft:polished_andesite_stairs") if room.height >= 4 else None
    row = d.side_cells(side, 1)[0]
    if row:
        mid = row[len(row) // 2]
        if raised and mid in raised:
            d.g.set(mid[0], d.y + 1, mid[1], "minecraft:quartz_slab[type=bottom]")
            d.g.set(mid[0], d.y + 2, mid[1], "minecraft:candle[candles=4,lit=true]")
            for (dx, dz) in ((f[1], -f[0]), (-f[1], f[0])):
                x, z = mid[0] + dx, mid[1] + dz
                if (x, z) in raised:
                    d.g.set(x, d.y + 1, z, "minecraft:gold_block" if ch["rug"] in ("red", "purple") else "minecraft:candle[candles=3,lit=true]")
        elif d.free(mid[0], mid[1]):
            d.put(mid[0], mid[1], "minecraft:quartz_block")
            d.g.set(mid[0], d.y + 1, mid[1], "minecraft:candle[candles=4,lit=true]")
    # pews: rows of stairs facing the altar, an aisle down the middle
    r = room
    if axis == "z":
        mid_z = (r.z0 + r.z1) // 2
        xs = range(r.x1 - 3, r.x0 + 1, -2) if side == "x1" else range(r.x0 + 3, r.x1 - 1, 2)
        for x in xs:
            for z in range(r.z0 + 1, r.z1):
                if z != mid_z and abs(z - mid_z) > 0 and d.free(x, z):
                    d.g.stair(x, d.y, z, "minecraft:dark_oak_stairs", "east" if side == "x0" else "west")
                    d.take(x, z)
    else:
        mid_x = (r.x0 + r.x1) // 2
        zs = range(r.z1 - 3, r.z0 + 1, -2) if side == "z1" else range(r.z0 + 3, r.z1 - 1, 2)
        for z in zs:
            for x in range(r.x0 + 1, r.x1):
                if x != mid_x and d.free(x, z):
                    d.g.stair(x, d.y, z, "minecraft:dark_oak_stairs", "south" if side == "z0" else "north")
                    d.take(x, z)
    d.runner(ch["rug"] if ch["rug"] != "black" else "purple", side)
    for (x, z), f2 in d.wall_cells(shuffle=False):
        if (x + z) % 2 == 0 and d.free(x, z):
            d.put(x, z, f"minecraft:candle[candles={1 + (x * z) % 4},lit=true]")
    _wall_banners(d, 6)
    _finish(d, ch, every=5, sconce_every=7)


def dress_oratory(d, room, ch):
    dress_chapel(d, room, ch)


def dress_cellar(d, room, ch):
    # barrels in ranks against the walls, two high; crates on the floor; a wine rack
    def stack(x, z, f, loot):
        d.barrel(x, z, loot, f=f)
        if d.g.get(x, d.y + 1, z) == AIR:
            d.g.barrel(x, d.y + 1, z, LOOT + loot, facing=d.facing(f))

    d.fill_wall([lambda x, z, f: stack(x, z, f, "castle_cellar"),
                 lambda x, z, f: stack(x, z, f, "castle_cellar"),
                 lambda x, z, f: stack(x, z, f, "castle_granary"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_cellar"),
                 lambda x, z, f: stack(x, z, f, "castle_cellar")], limit=max(6, int(room.size() * 0.4)))
    # a rank of casks down the middle, lying on their sides
    if room.size() > 40:
        cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
        axis = "x" if room.w() >= room.d() else "z"
        ln = max(3, min(8, (room.w() if axis == "x" else room.d()) - 4))
        for i in range(ln):
            x, z = (cx - ln // 2 + i, cz) if axis == "x" else (cx, cz - ln // 2 + i)
            if d.free(x, z, 2):
                d.barrel(x, z, "castle_cellar", f=(0, 1) if axis == "x" else (1, 0))
                d.g.barrel(x, d.y + 1, z, LOOT + "castle_cellar", facing="south" if axis == "x" else "east")
    for (x, z) in d.inner:
        if d.free(x, z) and d.rng.random() < 0.1:
            d.put(x, z, "minecraft:barrel[facing=up]", nbt={"id": "minecraft:barrel", "LootTable": LOOT + "castle_granary"})
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y + 2, z) == AIR and d.rng.random() < 0.3:
            d.g.set(x, d.y + 2, z, "minecraft:cobweb")
            break
    _finish(d, ch, every=7, beams=True, sconce_every=8)


def dress_store(d, room, ch):
    def stack(x, z, f, loot):
        d.barrel(x, z, loot, f=f)
        if d.g.get(x, d.y + 1, z) == AIR:
            d.g.barrel(x, d.y + 1, z, LOOT + loot, facing=d.facing(f))
    d.fill_wall([lambda x, z, f: stack(x, z, f, "castle_granary"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_store"),
                 lambda x, z, f: stack(x, z, f, "castle_granary"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=x]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_cellar")],
                limit=max(6, int(room.size() * 0.35)))
    for (x, z) in d.inner:
        if d.free(x, z) and d.rng.random() < 0.12:
            d.put(x, z, "minecraft:barrel[facing=up]", nbt={"id": "minecraft:barrel", "LootTable": LOOT + "castle_granary"})
    _finish(d, ch, every=6, sconce_every=8)


def dress_armoury(d, room, ch):
    # racks along the walls: a rail with hooks over a bench of stone
    for (x, z), f in d.wall_cells(shuffle=False):
        if d.free(x, z) and (x + z) % 4 in (0, 1):
            d.put(x, z, "minecraft:dark_oak_fence")
            d.g.set(x, d.y + 1, z, "minecraft:dark_oak_slab[type=top]")
            wx, wz = x - f[0], z - f[1]
            if d.g.get(x, d.y + 2, z) == AIR and d.solid[d.g.a[wx, wz, d.y + 2]]:
                d.g.set(x, d.y + 2, z, f"minecraft:tripwire_hook[facing={d.facing(f)},attached=false,powered=false]")
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_armory"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, f"minecraft:grindstone[face=floor,facing={d.facing(f)}]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_armory"),
                 lambda x, z, f: d.put(x, z, f"minecraft:anvil[facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:smithing_table"),
                 lambda x, z, f: d.put(x, z, "minecraft:target"),
                 lambda x, z, f: d.put(x, z, "minecraft:iron_block")],
                limit=max(5, int(room.size() * 0.22)))
    _wall_banners(d, ch["banner_every"] + 1)
    _finish(d, ch, every=4, sconce_every=5)


def dress_guardroom(d, room, ch):
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    ok = d.table(cx - 1, cz, 3, "x", dressing=["minecraft:candle[candles=1,lit=true]", "minecraft:stone_button[face=floor]", AIR]) \
        or d.table(cx, cz - 1, 3, "z", dressing=["minecraft:candle[candles=1,lit=true]", "minecraft:stone_button[face=floor]", AIR])
    # a cot for the man off duty, a brazier, racks
    for (x, z), f in d.wall_cells():
        if d.bed(x, z, f, "gray"):
            break
    for (x, z), f in d.wall_cells():
        if d.free(x, z) and d.free(x + f[0], z + f[1]):
            d.brazier(x, z)
            break
    d.pegs(every=4)
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_barracks"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:target"),
                 lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f)], limit=max(3, room.size() // 6))
    _skulls(d, ch, 1)
    _finish(d, ch, every=5)


def dress_smithy(d, room, ch):
    # the forge: a brick box with fire on top and a hood, the anvil in front, the quench beside
    side = d.far_side()
    f = d.side_facing(side)
    row = d.side_cells(side, 1)[0]
    done = False
    for k in range(len(row) // 2, len(row) - 1):
        (x, z), (bx, bz) = row[k], row[k + 1]
        if d.free(x, z) and d.free(bx, bz) and d.free(x + f[0], z + f[1]) and d.free(bx + f[0], bz + f[1]):
            d.put(x, z, "minecraft:bricks")
            d.g.set(x, d.y + 1, z, "minecraft:campfire[lit=true,signal_fire=false]")
            d.put(bx, bz, f"minecraft:blast_furnace[facing={d.facing(f)},lit=true]")
            for (cx_, cz_) in ((x, z), (bx, bz)):
                for h in range(2, room.height):
                    if d.g.get(cx_, d.y + h, cz_) == AIR:
                        d.g.set(cx_, d.y + h, cz_, "minecraft:bricks" if h > 2 else f"minecraft:brick_stairs[facing={d.facing(f)},half=top]")
            ax, az = x + f[0], z + f[1]
            d.put(ax, az, f"minecraft:anvil[facing={'east' if f[1] else 'north'}]")
            d.put(bx + f[0], bz + f[1], "minecraft:water_cauldron[level=3]")
            done = True
            break
    if not done:
        d.fill_wall([lambda x, z, f: d.put(x, z, f"minecraft:blast_furnace[facing={d.facing(f)},lit=false]"),
                     lambda x, z, f: d.put(x, z, f"minecraft:anvil[facing={d.facing(f)}]")], limit=2)
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_smithy"),
                 lambda x, z, f: d.barrel(x, z, "castle_armory", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:iron_block" if d.rng.random() < 0.3 else "minecraft:coal_block"),
                 lambda x, z, f: d.put(x, z, f"minecraft:grindstone[face=floor,facing={d.facing(f)}]"),
                 lambda x, z, f: d.put(x, z, "minecraft:smithing_table"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]")],
                limit=max(4, room.size() // 5))
    for (x, z) in list(d.inner)[::5]:
        d.hang(x, z, drop=1, end="minecraft:chain[axis=y]")
    _finish(d, ch, every=4, sconce_every=6)


def dress_treasury(d, room, ch):
    """Strongboxes and casks against the walls, a counting table, the gold stacked in one corner behind bars."""
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_treasury"),
                 lambda x, z, f: d.barrel(x, z, "castle_treasury", f=f),
                 lambda x, z, f: d.chest(x, z, f, "castle_treasury"),
                 lambda x, z, f: d.put(x, z, f"minecraft:{ch['lantern']}[hanging=false]")],
                limit=max(3, min(12, room.size() // 10)))
    # the strongroom corner: gold two high behind iron bars
    for (x, z), f in d.wall_cells(shuffle=False):
        sx, sz = x - f[1], z + f[0]
        bx, bz = x + f[0], z + f[1]
        if d.free(x, z) and d.free(sx, sz) and d.free(bx, bz) and d.free(sx + f[0], sz + f[1]):
            for (cx, cz) in ((x, z), (sx, sz)):
                d.put(cx, cz, "minecraft:gold_block")
                if d.g.get(cx, d.y + 1, cz) == AIR:
                    d.g.set(cx, d.y + 1, cz, "minecraft:raw_gold_block" if (cx + cz) % 2 else "minecraft:gold_block")
            for (cx, cz) in ((bx, bz), (sx + f[0], sz + f[1])):
                d.put(cx, cz, "minecraft:iron_bars")
                if d.g.get(cx, d.y + 1, cz) == AIR:
                    d.g.set(cx, d.y + 1, cz, "minecraft:iron_bars")
            break
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    d.table(cx - 1, cz, 3, "x", chairs=True, dressing=["minecraft:candle[candles=3,lit=true]", "minecraft:gold_block", AIR])
    d.sconces(every=5, block="minecraft:soul_wall_torch")
    d.rug(ch["rug"] if ch["rug"] != "brown" else "red", 3, 3)
    d.lanterns(every=5)


def _cells_along(d, room, side, ch):
    """Prison cells two wide and two deep along one wall, behind iron bars. Returns how many."""
    rows = d.side_cells(side, 3)
    if len(rows) < 3 or len(rows[0]) < 5:
        return 0
    back, mid, front = rows
    f = d.side_facing(side)
    n = min(len(back), len(mid), len(front))
    placed = 0
    k = 0
    while k + 1 < n:
        cells = [back[k], back[k + 1], mid[k], mid[k + 1]]
        fr = [front[k], front[k + 1]]
        if all(d.free(x, z) for (x, z) in cells + fr) and all(
                abs(back[k][0] - back[k + 1][0]) + abs(back[k][1] - back[k + 1][1]) == 1 for _ in (0,)):
            bx, bz = back[k]
            d.put(bx, bz, "minecraft:hay_block[axis=y]")
            for c in (back[k + 1], mid[k], mid[k + 1]):
                d.take(*c)
            if ch["skull"] or d.rng.random() < 0.4:
                d.g.set(back[k + 1][0], d.y, back[k + 1][1], f"minecraft:skeleton_skull[rotation={(k * 5) % 16}]")
            else:
                d.g.chest(back[k + 1][0], d.y, back[k + 1][1], d.facing(f), LOOT + "castle_barracks")
            d.hang(mid[k][0], mid[k][1], drop=1, end=AIR)
            for (x, z) in fr:
                d.put(x, z, "minecraft:iron_bars")
                for h in (1, 2):
                    if d.g.get(x, d.y + h, z) == AIR and (h == 1 or room.height <= 4):
                        d.g.set(x, d.y + h, z, "minecraft:iron_bars")
            # a partition of bars to the next cell
            if k + 2 < n:
                for c in (back[k + 2], mid[k + 2], front[k + 2]):
                    if d.free(*c):
                        d.put(c[0], c[1], "minecraft:iron_bars")
                        if d.g.get(c[0], d.y + 1, c[1]) == AIR:
                            d.g.set(c[0], d.y + 1, c[1], "minecraft:iron_bars")
            placed += 1
            k += 3
        else:
            k += 1
    return placed


def dress_dungeon(d, room, ch):
    """Cells along the walls behind iron bars; chains, straw, a brazier and a table for the gaoler."""
    r = room
    sides = [d.far_side()] + [s for s in ("x0", "x1", "z0", "z1") if s != d.far_side()]
    total = 0
    for side in sides:
        total += _cells_along(d, room, side, ch)
        if total >= 3:
            break
    if total == 0:
        return dress_guardroom(d, room, ch)
    for (x, z), f in d.wall_cells():
        if d.free(x, z) and d.free(x + f[0], z + f[1]):
            d.brazier(x, z)
            break
    cx, cz = (r.x0 + r.x1) // 2, (r.z0 + r.z1) // 2
    d.table(cx - 1, cz, 3, "x", dressing=["minecraft:candle[candles=1,lit=true]", AIR]) or \
        d.table(cx, cz - 1, 3, "z", dressing=["minecraft:candle[candles=1,lit=true]", AIR])
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_barracks"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=2]")], limit=2)
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y + 2, z) == AIR and d.rng.random() < 0.25:
            d.g.set(x, d.y + 2, z, "minecraft:cobweb")
    d.sconces(every=6, block="minecraft:soul_wall_torch")
    d.lanterns(every=8)


def dress_well(d, room, ch):
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    cells = [(cx + dx, cz + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
    if all(d.free(x, z) for (x, z) in cells) and room.height >= 4:
        d.g.set(cx, d.y - 1, cz, "minecraft:water")
        d.g.set(cx, d.y - 2, cz, "minecraft:stone_bricks")
        for (x, z) in cells:
            if (x, z) != (cx, cz):
                d.put(x, z, "minecraft:cobblestone_wall")
        d.take(cx, cz)
        for (x, z) in ((cx - 1, cz - 1), (cx + 1, cz + 1)):
            d.g.set(x, d.y + 1, z, "minecraft:dark_oak_fence")
            d.g.set(x, d.y + 2, z, "minecraft:dark_oak_fence")
        for (x, z) in cells:
            d.g.set(x, d.y + 3, z, "minecraft:dark_oak_slab[type=bottom]")
        d.g.set(cx, d.y + 2, cz, "minecraft:chain[axis=y]")
        d.g.set(cx, d.y + 1, cz, "minecraft:water_cauldron[level=3]")
    d.fill_wall([lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.barrel(x, z, "castle_kitchen", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:mossy_cobblestone")], limit=4)
    _finish(d, ch, every=5, beams=False, sconce_every=6)


def dress_stable(d, room, ch):
    # stalls: fence partitions off the long wall, hay in each
    side = d.far_side()
    rows = d.side_cells(side, 3)
    if len(rows) == 3 and len(rows[0]) >= 6:
        back, mid, front = rows
        for k in range(0, len(back) - 1, 3):
            (x, z) = back[k]
            if d.free(x, z):
                d.put(x, z, "minecraft:hay_block[axis=y]")
            if k + 2 < min(len(back), len(mid)):
                for (px, pz) in (back[k + 2], mid[k + 2]):
                    if d.free(px, pz):
                        d.put(px, pz, "minecraft:dark_oak_fence")
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_stables"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.barrel(x, z, "castle_stables", f=f),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=z]")], limit=max(4, room.size() // 8))
    _finish(d, ch, every=6, sconce_every=8)


def dress_kennels(d, room, ch):
    dress_stable(d, room, ch)
    for (x, z) in d.inner:
        if d.free(x, z) and d.rng.random() < 0.08:
            d.put(x, z, "minecraft:bone_block[axis=x]")


def dress_mews(d, room, ch):
    """Where the hawks are kept: perches and a few feathers."""
    for (x, z), f in d.wall_cells(shuffle=False):
        if d.free(x, z) and (x + z) % 3 == 0:
            d.put(x, z, "minecraft:dark_oak_fence")
            d.g.set(x, d.y + 1, z, "minecraft:dark_oak_fence")
            if d.g.get(x, d.y + 2, z) == AIR:
                d.g.set(x, d.y + 2, z, "minecraft:dark_oak_slab[type=bottom]")
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_barracks"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=1]")], limit=3)
    _finish(d, ch, every=5)


def dress_infirmary(d, room, ch):
    beds = 0
    for (x, z), f in d.wall_cells(shuffle=False):
        if beds >= 6:
            break
        if (x + z) % 3 == 0 and d.bed(x, z, f, "white"):
            beds += 1
    d.fill_wall([lambda x, z, f: d.put(x, z, "minecraft:brewing_stand"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]"),
                 lambda x, z, f: d.chest(x, z, f, "castle_solar"),
                 lambda x, z, f: d.put(x, z, "minecraft:bookshelf"),
                 lambda x, z, f: d.put(x, z, "minecraft:potted_azalea_bush"),
                 lambda x, z, f: d.put(x, z, "minecraft:flower_pot")], limit=max(4, room.size() // 8))
    cx, cz = (room.x0 + room.x1) // 2, (room.z0 + room.z1) // 2
    d.table(cx - 1, cz, 3, "x", chairs=False, top="minecraft:white_wool", dressing=["minecraft:candle[candles=2,lit=true]", AIR]) or \
        d.table(cx, cz - 1, 3, "z", chairs=False, top="minecraft:white_wool", dressing=["minecraft:candle[candles=2,lit=true]", AIR])
    d.carpet("white", p=0.15)
    _finish(d, ch, every=4)


def dress_brewhouse(d, room, ch):
    def stack(x, z, f, loot):
        d.barrel(x, z, loot, f=f)
        if d.g.get(x, d.y + 1, z) == AIR:
            d.g.barrel(x, d.y + 1, z, LOOT + loot, facing=d.facing(f))
    # the copper: a cauldron over a fire, with a hood
    for (x, z), f in d.wall_cells():
        if d.free(x, z) and d.free(x + f[0], z + f[1]) and room.height >= 4:
            d.put(x, z, "minecraft:campfire[lit=true,signal_fire=false]")
            d.g.set(x, d.y + 1, z, "minecraft:water_cauldron[level=3]")
            d.g.set(x, d.y + 2, z, f"minecraft:brick_stairs[facing={d.facing(f)},half=top]")
            d.take(x + f[0], z + f[1])
            break
    d.fill_wall([lambda x, z, f: stack(x, z, f, "castle_cellar"),
                 lambda x, z, f: d.put(x, z, "minecraft:hay_block[axis=y]"),
                 lambda x, z, f: d.put(x, z, "minecraft:composter[level=5]"),
                 lambda x, z, f: stack(x, z, f, "castle_cellar"),
                 lambda x, z, f: d.put(x, z, "minecraft:water_cauldron[level=3]")], limit=max(5, room.size() // 5))
    _finish(d, ch, every=5, sconce_every=7)


def dress_wardrobe(d, room, ch):
    """The lord's wardrobe: chests, cloth, a mirror of sorts."""
    d.fill_wall([lambda x, z, f: d.chest(x, z, f, "castle_solar"),
                 lambda x, z, f: d.put(x, z, f"minecraft:{d.rng.choice(ch['carpets'])}_wool"),
                 lambda x, z, f: d.put(x, z, "minecraft:loom"),
                 lambda x, z, f: d.barrel(x, z, "castle_solar", f=f),
                 lambda x, z, f: d.put(x, z, f"minecraft:{d.rng.choice(ch['carpets'])}_wool")], limit=max(4, room.size() // 5))
    for (x, z), f in d.wall_cells():
        if d.g.get(x, d.y, z).endswith("_wool") and d.g.get(x, d.y + 1, z) == AIR:
            d.g.set(x, d.y + 1, z, d.g.get(x, d.y, z))
    d.rug(ch["rug"], 3, 3)
    _finish(d, ch, every=5)


DRESSERS = {
    "hall": dress_hall, "mess": dress_mess, "kitchen": dress_kitchen, "barracks": dress_barracks, "quarters": dress_quarters,
    "chamber": dress_chamber, "lodging": dress_lodging, "solar": dress_solar, "library": dress_library, "scriptorium": dress_scriptorium,
    "chapel": dress_chapel, "oratory": dress_oratory, "cellar": dress_cellar, "store": dress_store, "armoury": dress_armoury,
    "guardroom": dress_guardroom, "smithy": dress_smithy, "treasury": dress_treasury, "dungeon": dress_dungeon,
    "well": dress_well, "stable": dress_stable, "kennels": dress_kennels, "mews": dress_mews, "infirmary": dress_infirmary,
    "brewhouse": dress_brewhouse, "wardrobe": dress_wardrobe,
}


def furnish(g, GL, character="martial", report=None, tier=2):
    """Plan and dress every room. Returns {kind: count}, plus "beds" and "men"."""
    protected = set()
    for role, pts in g.markers.items():
        for (x, y, z) in pts:
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    protected.add((x + dx, y, z + dz))
                    protected.add((x + dx, y + 1, z + dz))
    ch = CHARACTERS[character]
    counts = {"partition_walls": shell.partition(g, GL, protected, fixed_kind)}
    for room, kind in plan(g, GL, character, tier):
        if len(room.cells) < 12 or (len(room.cells) <= 16 and len(room.doorways) >= 2):
            continue                                  # a stair head or a tower passage, not a room
        room.kind = kind
        d = Dresser(_Guarded(g), room, protected)
        if len(d.wall) + len(d.inner) < 6:
            continue                                  # a stair landing: nothing fits
        for k, v in shell.dress(d, room, ch, kind).items():
            counts["shell_" + k] = counts.get("shell_" + k, 0) + v
        DRESSERS[kind](d, room, ch)
        for k, v in shell.finish(d, room, ch, kind).items():
            counts["shell_" + k] = counts.get("shell_" + k, 0) + v
        counts[kind] = counts.get(kind, 0) + 1
        if report is not None:
            report.append((kind, room.y, room.x0, room.z0, room.w(), room.d(), room.size()))
    counts["beds"] = beds_in(g)
    counts["men"] = MEN.get(tier, 24)
    # the partition walls make smaller rooms than the planner reckoned on: bunks go into the lodgings, the
    # quarters and the guardrooms, biggest first, until the garrison has its beds
    rounds = (("lodging", "quarters", "guardroom", "store", "chamber"), ("barracks", "armoury", "cellar", "mess"))
    for round_, kinds_ in enumerate(rounds):
        if counts["beds"] >= counts["men"] or report is None:
            break
        rs = find_rooms(g, GL, set())
        for kind, y, x0, z0, w, dd, size in sorted(report, key=lambda e: -e[6]):
            if counts["beds"] >= counts["men"]:
                break
            if kind not in kinds_:
                continue
            # the furniture has shrunk the room's footprint since the plan: match by overlap, not by corner
            best, rm = 0, None
            for cand in rs:
                if cand.y != y:
                    continue
                ov = max(0, min(x0 + w, cand.x0 + cand.w()) - max(x0, cand.x0)) * max(0, min(z0 + dd, cand.z0 + cand.d()) - max(z0, cand.z0))
                if ov > best:
                    best, rm = ov, cand
            if rm is None:
                continue
            d = Dresser(_Guarded(g), rm, protected)
            colors = ["brown", "gray", "light_gray", "white"]
            for (x, z), f in d.wall_cells(shuffle=False):
                if counts["beds"] >= counts["men"]:
                    break
                if ((x + z) % 2 or round_) and d.bunk(x, z, f, colors[(x * 7 + z) % 4]):
                    if not _doors_connected(d, rm):
                        _unbed(d, x, z, f)
                    else:
                        counts["beds"] = beds_in(g)
                        counts["bunks_added"] = counts.get("bunks_added", 0) + 1
    return counts
