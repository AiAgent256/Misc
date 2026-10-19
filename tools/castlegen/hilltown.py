"""The walled hill town: a town big enough to need a citadel, inside a curtain that follows the hill's brow
instead of a surveyor's square. Ten straight sides of wall, a tower at every angle and drum towers between
them so no stretch of wall runs more than eighteen blocks without a tower standing out from its face; one
gate between two towers on the south; a citadel on a raised terrace at the top of the town with its own wall,
corner towers and a keep where the reeve sits; a church, a market square, an inn, streets of houses roofed in
red tile and slate, a pond and gardens under the citadel.

Defences after the castle rules (see defense_audit): walks four wide behind crenels with half-block sills,
every tower standing out at least three blocks beyond the face so the men on it see along the wall's foot,
three-wide flights up to the walk from the streets, wide spirals in the towers, no ladders on any soldier's
way. The drums between the great towers are open at the back, level with the walk, under a low tile hat on
posts; the great towers' cones stand on posts over their merlons the same way."""
import math

import numpy as np

import parts
import town as _town
from castle import portcullis_plane
from city import inn, SQUARE, tree_oak
from grid import Grid, Mix, disk, ring, angle_index, cone_roof
from market_town import (ground, road, church, notice_board, sell_homes, smithy, tithe_barn,
                         TRADES_A, TRADES_B, TRADES_C, _framed)
from parts import (SILL, STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, STONE_STAIR, SLAB_CAP,
                   curtain, posts_on_walk, round_tower, square_tower, building, lamp_post, pointed_arch, side_grids)
from town import terrace, stall_cluster, well as town_well

LOOT = "stormhold:chests/"
AIR = "minecraft:air"
FLOWERS = ("minecraft:poppy", "minecraft:dandelion", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
           "minecraft:cornflower", "minecraft:red_tulip", "minecraft:allium")
HEDGE = "minecraft:oak_leaves[persistent=true]"
# the town's roofs: red tile mostly, some slate and some mud-brick
TOWER_ROOF = "minecraft:terracotta"
TILE_ROOFS = ["minecraft:brick_stairs", "minecraft:brick_stairs", "minecraft:brick_stairs",
              "minecraft:deepslate_tile_stairs", "minecraft:mud_brick_stairs"]

# the curtain's outer line, round the brow of the hill: ten sides, the south one straight for the gate
OUTLINE = [(40, 132), (104, 132), (126, 110), (126, 56), (110, 26), (78, 12), (40, 16), (14, 44), (14, 98), (22, 120)]


def polygon_grids(g, pts):
    """For a convex outline: d, whole blocks in from the nearest side (0 on the outer face, negative outside);
    along, how far along that side (a running count round the outline); out, the side's outward facing. The
    same three grids parts.curtain takes for a square or a round wall."""
    xs, zs = np.meshgrid(np.arange(g.X), np.arange(g.Z), indexing="ij")
    cx = sum(p[0] for p in pts) / len(pts)
    cz = sum(p[1] for p in pts) / len(pts)
    dists, alongs, facings = [], [], []
    run = 0.0
    for i in range(len(pts)):
        (ax, az), (bx, bz) = pts[i], pts[(i + 1) % len(pts)]
        L = math.hypot(bx - ax, bz - az)
        tx, tz = (bx - ax) / L, (bz - az) / L
        nx, nz = -tz, tx
        if (cx - ax) * nx + (cz - az) * nz < 0:
            nx, nz = -nx, -nz                                     # the normal points into the town
        dists.append((xs - ax) * nx + (zs - az) * nz)
        alongs.append(run + (xs - ax) * tx + (zs - az) * tz)
        run += L
        ox, oz = -nx, -nz
        facings.append(("east" if ox > 0 else "west") if abs(ox) >= abs(oz) else ("south" if oz > 0 else "north"))
    D = np.stack(dists)
    k = np.argmin(D, axis=0)
    d = np.floor(np.min(D, axis=0) + 0.5).astype(int)
    along = np.rint(np.take_along_axis(np.stack(alongs), k[None], 0)[0]).astype(int)
    out = np.array(facings, dtype=object)[k]
    return d, along, out


def _edges(pts):
    """Each side: start, end, unit direction, inward normal, length."""
    cx = sum(p[0] for p in pts) / len(pts)
    cz = sum(p[1] for p in pts) / len(pts)
    out = []
    for i in range(len(pts)):
        (ax, az), (bx, bz) = pts[i], pts[(i + 1) % len(pts)]
        L = math.hypot(bx - ax, bz - az)
        tx, tz = (bx - ax) / L, (bz - az) / L
        nx, nz = -tz, tx
        if (cx - ax) * nx + (cz - az) * nz < 0:
            nx, nz = -nx, -nz
        out.append(((ax, az), (bx, bz), (tx, tz), (nx, nz), L))
    return out


def _unit(dx, dz):
    n = math.hypot(dx, dz) or 1.0
    return dx / n, dz / n


def drum(g, cx, cz, r, walk, GL, d):
    """A drum tower open at the back: a solid half-round standing out from the curtain, its top a platform
    level with the walk, which runs out onto it, behind a crenellated rim with half-block sills. It needs no
    stair and no room: the walk is its way up, and from its rim the men see along the wall both ways."""
    outside = d < 0
    full = disk(g, cx, cz, r + 1) & outside
    top = disk(g, cx, cz, r) & outside
    rim = ring(g, cx, cz, r + 1, r) & outside
    g.fill_mask(full, 0, walk - 1, STONE)
    g.fill_mask(rim, 0, GL + 1, DARK)                                   # its plinth
    g.fill_mask(full, walk, walk + 5, AIR)                              # the curtain's parapet gives way to the rim
    ai = angle_index(g, cx, cz, r + 1)
    merlon = rim & (np.mod(ai, 4) < 2)
    g.fill_mask(rim, walk, walk, SILL)
    g.fill_mask(merlon, walk, walk + 2, STONE)
    g.fill_mask(merlon, walk + 3, walk + 3, SLAB_CAP)
    g.fill_mask(top & ~rim, walk - 1, walk - 1, FLOOR_STONE)
    # a low tile hat on posts over the merlons, high enough to walk and shoot under: from the field the wall
    # reads as a string of round towers
    g.fill_mask(merlon, walk + 3, walk + 3, parts.PIER_TOP)
    cone_roof(g, cx, cz, r, walk + 4, TOWER_ROOF, height_factor=1.3)
    # the rim's corbel course under the parapet, every other block
    from grid import facing_to_center_grid
    fgrid = facing_to_center_grid(g, cx, cz)
    parts.stairs_by_facing(g, rim & (np.mod(ai, 2) == 0), walk - 2, STONE_STAIR, fgrid, half="top")


def tile_cap(g, cx, cz, R, top, block=None, height_factor=1.9):
    """A tower's roof on posts over its merlons, as parts.cap_roof lifts it, but fitted to the parapet: a
    steep cone no wider than the tower's corbelled top, so a string of them reads as towers, not hats."""
    parts._piers(g, cx, cz, R, top)
    cone_roof(g, cx, cz, R, top + parts.LIFT, block or TOWER_ROOF, height_factor=height_factor)


def close_slits(g, cx, cz, R, t, GL, top):
    """A round tower's slits are cut two high, which in a shell two thick is a doorway: a man in the room steps
    up into one and out onto the plinth. Brick up the lower course; the upper stays a loop to look out of."""
    shell = ring(g, cx, cz, R, R - t) & (np.mod(angle_index(g, cx, cz, R), 7) == 0)
    for y in range(GL + 5, top - 4, 7):
        xs, zs = np.nonzero(shell)
        for x, z in zip(xs, zs):
            if g.get(int(x), y, int(z)) == AIR and g.get(int(x), y + 1, int(z)) == AIR:
                g.set(int(x), y, int(z), STONE)


def walk_door(g, cx, cz, R, t, ux, uz, y):
    """A door three wide from the wall walk into a round tower, cut through the shell only. (The tower's own
    door cutter runs a diagonal door two cells further in, which at walk height takes treads out of the
    spiral.)"""
    px, pz = -uz, ux
    k = -1.0
    while k <= t - 0.5:
        rr = R - k
        for w in (-1, 0, 1):
            x = int(round(cx + ux * rr + px * w))
            z = int(round(cz + uz * rr + pz * w))
            if (x - cx) ** 2 + (z - cz) ** 2 > (R - t + 0.35) ** 2:
                g.fill(x, y, z, x, y + 2, z, AIR)
        k += 0.5


def flight(g, lo, hi, rail, GL, walk, rise_toward):
    """A straight flight three wide climbing from the street to the wall walk, `rise_toward` the wall. `lo..hi`
    is its width across the climb; `rail` the row of the walk's inner rail, where the top step lands level
    with the walk's floor."""
    n = walk - GL
    sgn = 1 if rise_toward in ("south", "east") else -1
    for k in range(n):
        c = rail - sgn * (n - 1 - k)
        for w in range(lo, hi + 1):
            x, z = (w, c) if rise_toward in ("north", "south") else (c, w)
            if k > 0:
                g.fill(x, 0, z, x, GL + k - 1, z, STONE)
            g.stair(x, GL + k, z, STONE_STAIR, rise_toward)
            g.fill(x, GL + k + 1, z, x, GL + k + 5, z, AIR)
    # the foot of the flight: a landing clear of whatever stood there
    c = rail - sgn * n
    for w in range(lo, hi + 1):
        x, z = (w, c) if rise_toward in ("north", "south") else (c, w)
        g.fill(x, GL, z, x, GL + 4, z, AIR)
        g.set(x, GL - 1, z, ROAD)


def pond(g, x0, z0, x1, z1, GL, rng):
    """A pond in a stone kerb, two deep, lilies on it, reeds at the edge."""
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    rx, rz = (x1 - x0) / 2, (z1 - z0) / 2
    xs, zs = g.xz()
    e = ((xs - cx) / rx) ** 2 + ((zs - cz) / rz) ** 2
    water = e <= 0.78
    kerb = (e <= 1.0) & ~water
    g.fill_mask(water, GL - 3, GL - 3, "minecraft:clay")
    g.fill_mask(water, GL - 2, GL - 1, "minecraft:water")
    g.fill_mask(water, GL, GL + 3, AIR)
    g.fill_mask(kerb, GL - 1, GL - 1, Mix(("minecraft:mossy_stone_bricks", 40), ("minecraft:stone_bricks", 40),
                                            ("minecraft:mossy_cobblestone", 20)))
    for _ in range(9):
        x = int(rng.integers(x0 + 2, x1 - 1)); z = int(rng.integers(z0 + 2, z1 - 1))
        if water[x, z] and g.get(x, GL, z) == AIR:
            g.set(x, GL, z, "minecraft:lily_pad")


def garden(g, x0, z0, x1, z1, GL, rng):
    """A walled garden of beds: box hedges, flowers, gravel paths, fruit trees, a bench under each tree."""
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, "minecraft:grass_block")
    g.fill(x0, GL, z0, x1, GL + 6, z1, AIR)
    # gravel paths: one round the edge, a cross through the middle
    mx, mz = (x0 + x1) // 2, (z0 + z1) // 2
    g.fill(x0, GL - 1, mz - 1, x1, GL - 1, mz, "minecraft:gravel")
    g.fill(mx - 1, GL - 1, z0, mx, GL - 1, z1, "minecraft:gravel")
    for (a0, b0, a1, b1) in ((x0 + 1, z0 + 1, mx - 3, mz - 3), (mx + 2, z0 + 1, x1 - 1, mz - 3),
                             (x0 + 1, mz + 2, mx - 3, z1 - 1), (mx + 2, mz + 2, x1 - 1, z1 - 1)):
        if a1 - a0 < 2 or b1 - b0 < 2:
            continue
        # a low box hedge round each bed, the bed planted up
        for x in range(a0, a1 + 1):
            for z in range(b0, b1 + 1):
                edge = x in (a0, a1) or z in (b0, b1)
                if edge:
                    g.set(x, GL, z, HEDGE)
                else:
                    g.set(x, GL - 1, z, "minecraft:podzol" if (x + z) % 5 == 0 else "minecraft:grass_block")
                    g.set(x, GL, z, FLOWERS[int(rng.integers(0, len(FLOWERS)))])
        g.set((a0 + a1) // 2, GL, b0, AIR)                      # a gap in the hedge
    for (tx, tz) in ((x0 + 1, z0 + 1), (x1 - 1, z1 - 1)):
        tree_oak(g, tx, tz, GL, rng)


@_framed
def hill_town(seed=311):
    """A walled hill town with a citadel: see the module's note."""
    X, Z, H = 144, 144, 72
    GL = 5
    g = Grid(X, H, Z, seed)
    rng = g.rng
    saved_roofs = list(_town.ROOFS)
    _town.ROOFS[:] = TILE_ROOFS
    try:
        _build(g, X, Z, GL, rng)
    finally:
        _town.ROOFS[:] = saved_roofs
    return g, {"name": "hill_town", "tier": 1, "GL": GL, "kind": "town", "character": "rustic"}


def _build(g, X, Z, GL, rng):
    ground(g, GL, X, Z)
    walk = GL + 9
    gx0, gx1 = 70, 74
    zf = OUTLINE[0][1]
    # ---- streets first: the curtain goes up over them and the gate is cut back through it ----
    road(g, gx0, 101, gx1, Z - 1, GL)                     # the main street, from the gate up to the square
    road(g, 19, 97, 121, 100, GL)                         # the cross street under the square
    road(g, 44, 37, 47, 96, GL)                           # the west street, past the church to the gardens
    road(g, 97, 37, 100, 96, GL)                          # the east street, past the inn
    road(g, 97, 72, 121, 73, GL)                          # a lane east under the citadel
    road(g, 108, 40, 110, 71, GL)                         # and a back lane up from it
    g.fill(50, GL - 1, 69, 94, GL - 1, 96, SQUARE)        # the market square under the citadel
    g.fill(50, GL, 69, 94, GL + 10, 96, AIR)
    # ---- the curtain ----
    d, along, out = polygon_grids(g, OUTLINE)
    curtain(g, None, None, 5, walk, GL, gallery=False, banners_every=16, grids=(d, along, out))
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=4, offset=2)
    edges = _edges(OUTLINE)
    # towers: a great one at every angle and either side of the gate, drums between, none more than 18 apart
    R, r = 6, 4
    great = []
    for i, (a, b, t, n, L) in enumerate(edges):
        pt, pn = edges[i - 1][2], edges[i - 1][3]
        bis = _unit(n[0] + pn[0], n[1] + pn[1])
        great.append((a[0] + 2 * bis[0], a[1] + 2 * bis[1], (-pt[0], -pt[1]), t, bis))
    gate_towers = [(gx0 - 7, zf - 2), (gx1 + 7, zf - 2)]
    # drums between the towers on each side, evenly, so no two towers stand more than 17 apart
    drums = []
    for i, (a, b, t, n, L) in enumerate(edges):
        fixed = [(great[i][0], great[i][1]), (great[(i + 1) % len(great)][0], great[(i + 1) % len(great)][1])]
        if i == 0:
            fixed += gate_towers
        stops = sorted((p[0] - a[0]) * t[0] + (p[1] - a[1]) * t[1] for p in fixed)
        for j0, (s0, s1) in enumerate(zip(stops, stops[1:])):
            if i == 0 and j0 == 1:
                continue                                  # the gate is between these two
            k = max(0, math.ceil((s1 - s0) / 17.0) - 1)
            for j in range(1, k + 1):
                s_ = s0 + (s1 - s0) * j / (k + 1)
                drums.append((a[0] + t[0] * s_, a[1] + t[1] * s_))
    for (x, z) in drums:
        tx, tz = int(round(x)), int(round(z))
        drum(g, tx, tz, r, walk, GL, d)
        # three bowmen to a drum: one looking each way along the wall, one out
        e = min(edges, key=lambda E: abs((x - E[0][0]) * E[3][0] + (z - E[0][1]) * E[3][1]))
        for (ux, uz) in (e[2], (-e[2][0], -e[2][1]), (-e[3][0], -e[3][1])):
            g.mark("archer", int(round(tx + (r - 1) * ux)), walk, int(round(tz + (r - 1) * uz)))
    top = walk + 6
    # the towers, roofed in tile like the town's houses
    for (x, z, back, ahead, bis) in great:
        cx, cz = int(round(x)), int(round(z))
        round_tower(g, cx, cz, R, top, GL, floors=(GL + 3, walk - 1), spiral=True, cone=False, t=2,
                    doors=((bis[0], bis[1], GL),))
        tile_cap(g, cx, cz, R, top)
        close_slits(g, cx, cz, R, 2, GL, top)
        walk_door(g, cx, cz, R, 2, back[0], back[1], walk)
        walk_door(g, cx, cz, R, 2, ahead[0], ahead[1], walk)
        for (ux, uz) in (back, ahead, (-bis[0], -bis[1])):
            g.mark("crossbowman" if (ux, uz) == (-bis[0], -bis[1]) else "archer",
                   int(round(cx + (R - 1) * ux)), top, int(round(cz + (R - 1) * uz)))
    for (cx, cz) in gate_towers:
        round_tower(g, cx, cz, R, top + 2, GL, floors=(GL + 3, walk - 1), spiral=True, cone=False, t=2,
                    doors=((0, -1, GL), (1, 0, walk), (-1, 0, walk)))
        tile_cap(g, cx, cz, R, top + 2, height_factor=2.3)
        close_slits(g, cx, cz, R, 2, GL, top + 2)
        for (ux, uz) in ((1, 0), (-1, 0), (0, 1)):
            g.mark("crossbowman" if uz else "archer", cx + (R - 1) * ux, top + 2, cz + (R - 1) * uz)
    # ---- the gate: an arched passage under the walk, portcullises at both ends ----
    pointed_arch(g, gx0, gx1, GL, 7, zf - 5, zf + 2)
    g.fill(gx0, GL - 1, zf - 5, gx1, GL - 1, Z - 1, ROAD)
    g.fill(gx0, GL, zf + 1, gx1, GL + 6, Z - 1, AIR)
    portcullis_plane(g, gx0, gx1, GL, 7, zf)
    portcullis_plane(g, gx0, gx1, GL, 7, zf - 4)
    g.wall_banner(gx0 - 1, GL + 6, zf + 2, "south"); g.wall_banner(gx1 + 1, GL + 6, zf + 2, "south")
    g.mark("gate", (gx0 + gx1) // 2, GL, Z - 1)
    # ---- flights up to the walk: either side of the gate, on the east wall and the west ----
    flight(g, 48, 50, zf - 4, GL, walk, "south")
    flight(g, 94, 96, zf - 4, GL, walk, "south")
    flight(g, 92, 94, OUTLINE[2][0] - 4, GL, walk, "east")
    flight(g, 85, 87, OUTLINE[7][0] + 4, GL, walk, "west")
    # ---- the citadel on its terrace at the top of the town ----
    citadel(g, GL, rng)
    # ---- the square under the citadel: the market, the well, the notice board ----
    stall_cluster(g, 54, 82, 67, 94, GL, rng, trades=["baker", "fletcher", "general", "farmer"])
    stall_cluster(g, 77, 82, 90, 94, GL, rng, trades=["butcher", "cloth", "potter", "fish"])
    town_well(g, 72, 88, GL)
    notice_board(g, 72, 78, GL, facing="south")
    g.mark("captain", 72, GL, 81)
    g.mark("rally", 72, GL, 79)
    # ---- the church on the west street; its tower is the bell-ringers', not a post ----
    before = {k: len(v) for k, v in g.markers.items()}
    church(g, 20, 66, GL, rng, along="x")
    for y in range(GL, GL + 20):
        for x in range(27, 34):
            for z in range(66, 73):
                if "ladder" in g.get(x, y, z):
                    g.set(x, y, z, AIR)
    for role in ("archer", "banner_stand"):
        g.markers[role] = g.markers[role][:before.get(role, 0)]
    # ---- the inn on the east street ----
    inn(g, 102, 75, 116, 89, GL, rng, "west")
    # ---- streets of houses ----
    terrace(g, 26, 42, 96, 7, GL, rng, "south", trades=TRADES_A)          # north of the cross street, west
    terrace(g, 101, 112, 96, 6, GL, rng, "south", trades=["none", "none"]) # and east
    terrace(g, 26, 66, 101, 8, GL, rng, "north", trades=TRADES_B)          # south of it, west of the main street
    terrace(g, 78, 114, 101, 8, GL, rng, "north", trades=["none", "none", "apothecary", "none", "general"])
    terrace(g, 112, 120, 69, 8, GL, rng, "east", trades=["none"])         # down the main street, both sides
    terrace(g, 112, 120, 75, 8, GL, rng, "west", trades=["none", "none"])
    terrace(g, 52, 64, 43, 6, GL, rng, "east", trades=["none"])           # up the west street
    terrace(g, 36, 71, 101, 7, GL, rng, "west", trades=TRADES_C)          # up the east street
    terrace(g, 63, 71, 111, 8, GL, rng, "west", trades=["none"])          # and the back lane
    terrace(g, 52, 61, 112, 7, GL, rng, "north", trades=["none"])         # behind the cross street, by the gate
    smithy(g, 85, 112, 92, 120, GL, rng, door="west")
    tithe_barn(g, 32, 112, 44, 121, GL, rng, door="north")
    # ---- the gardens and the pond ----
    garden(g, 30, 37, 43, 51, GL, rng)
    garden(g, 98, 110, 108, 119, GL, rng)
    pond(g, 21, 52, 35, 64, GL, rng)
    for (x, z) in ((47, 112), (56, 123), (100, 26), (95, 22)):
        tree_oak(g, x, z, GL, rng)
    # ---- lamps, the watch ----
    for z in range(106, 126, 8):
        lamp_post(g, gx0 - 1, z, GL) if g.get(gx0 - 1, GL, z) == AIR else None
        lamp_post(g, gx1 + 1, z, GL) if g.get(gx1 + 1, GL, z) == AIR else None
    for x in range(24, 120, 12):
        if not (gx0 - 2 <= x <= gx1 + 2) and g.get(x, GL, 97) == AIR:
            lamp_post(g, x, 97, GL)
    for (x, z) in ((72, 108), (72, 120), (40, 98), (104, 98), (60, 78), (84, 78), (46, 90), (98, 92)):
        g.mark("knight", x, GL, z)
    sell_homes(g, rng, n=14)


def citadel(g, GL, rng):
    """The citadel: a terrace six up with a wall four thick on it, a round tower at each corner, a gate in the
    south wall reached by a broad flight from the square, a keep in the north where the reeve sits over the
    town's strongbox, a barracks and a store along the south wall."""
    GL2 = GL + 6
    a, b, az, bz = 54, 90, 32, 64
    walk2 = GL2 + 9
    # the terrace: made ground, five blocks wider than the citadel's wall all round, held up by a battered
    # retaining wall with a rail along its top
    m = 5
    g.fill(a - m, 0, az - m, b + m, GL2 - 2, bz + m, FOUND)
    g.fill(a - m, GL2 - 1, az - m, b + m, GL2 - 1, bz + m, PAVE)
    g.fill(a - m, GL2, az - m, b + m, GL2 + 12, bz + m, AIR)
    for (x0, z0, x1, z1) in ((a - m, az - m, b + m, az - m), (a - m, bz + m, b + m, bz + m),
                             (a - m, az - m, a - m, bz + m), (b + m, az - m, b + m, bz + m)):
        g.fill(x0, 0, z0, x1, GL2 - 1, z1, STONE)
        g.fill(x0, GL2, z0, x1, GL2, z1, "minecraft:stone_brick_wall")
        g.fill(x0, GL - 1, z0, x1, GL + 1, z1, parts.WALL_FOOT)
    g.fill(a - m, GL + 2, az - m, b + m, GL + 2, az - m, DARK)
    g.fill(a - m, GL + 2, bz + m, b + m, GL + 2, bz + m, DARK)
    g.fill(a - m, GL + 2, az - m, a - m, GL + 2, bz + m, DARK)
    g.fill(b + m, GL + 2, az - m, b + m, GL + 2, bz + m, DARK)
    g.fill(a, 0, az, b, GL2 - 2, bz, FOUND)
    g.fill(a, GL2 - 1, az, b, GL2 - 1, bz, PAVE)
    g.fill(a, GL2, az, b, GL2 + 30, bz, AIR)
    d, along, out = side_grids(g, a, b, az, bz)
    curtain(g, a, b, 4, walk2, GL2, gallery=False, banners_every=12, az=az, bz=bz)
    posts_on_walk(g, d, along, walk2, "archer", dsel=1, every=4, offset=2)
    # corner towers
    top2 = walk2 + 6
    for (cx, cz) in ((a, az), (b, az), (a, bz), (b, bz)):
        ix, iz = (1 if cx == a else -1), (1 if cz == az else -1)
        inward = _unit(ix, iz)
        round_tower(g, cx, cz, 6, top2, GL2, floors=(GL2 + 3, walk2 - 1), spiral=True, cone=False, t=2,
                    doors=((inward[0], inward[1], GL2), (ix, 0, walk2), (0, iz, walk2)))
        tile_cap(g, cx, cz, 6, top2, block=parts.ROOF, height_factor=2.1)
        close_slits(g, cx, cz, 6, 2, GL2, top2)
        g.mark("crossbowman", cx - ix * 4, top2, cz - iz * 4)
        g.mark("archer", cx - ix * 5, top2, cz)
        g.mark("archer", cx, top2, cz - iz * 5)
    # the gate and the broad flight up to it from the square
    gx0, gx1 = 70, 74
    pointed_arch(g, gx0, gx1, GL2, 6, bz - 4, bz)
    g.fill(gx0, GL2 - 1, bz - 4, gx1, GL2 - 1, bz, ROAD)
    portcullis_plane(g, gx0, gx1, GL2, 6, bz)
    g.fill(gx0 - 1, GL2 - 1, bz + 1, gx1 + 1, GL2 - 1, bz + m, ROAD)
    g.fill(gx0 - 1, GL2, bz + 1, gx1 + 1, GL2 + 6, bz + m, AIR)           # the wall's plinth, the terrace's rail
    for k in range(GL2 - GL):
        z = bz + m + (GL2 - GL) - k
        if k > 0:
            g.fill(gx0 - 1, 0, z, gx1 + 1, GL + k - 1, z, STONE)
        for x in range(gx0 - 1, gx1 + 2):
            g.stair(x, GL + k, z, STONE_STAIR, "north")
        g.fill(gx0 - 1, GL + k + 1, z, gx1 + 1, GL + k + 6, z, AIR)
    g.wall_banner(gx0 - 1, GL2 + 6, bz + 2, "south"); g.wall_banner(gx1 + 1, GL2 + 6, bz + 2, "south")
    # the keep: four floors over the town, a stair hall, the reeve's hall on the first floor, a roof on its
    # merlons
    kx0, kz0, kx1, kz1 = 63, 36, 81, 49
    ktop = GL2 + 26
    F1 = GL2 + 7
    saved = parts.CAP_STYLE
    parts.CAP_STYLE = "pyramid"
    try:
        square_tower(g, kx0, kz0, kx1, kz1, ktop, GL2, floors=(F1, GL2 + 14, GL2 + 20), stair="north",
                     banner_sides=("south", "east", "west"), cone=True)
    finally:
        parts.CAP_STYLE = saved
    g.fill(71, GL2, kz1 - 3, 73, GL2 + 2, kz1 + 1, AIR)                  # the door, through wall and plinth
    g.fill(71, GL2 - 1, kz1 + 1, 73, GL2 - 1, kz1 + 1, ROAD)
    for x in range(kx0 + 4, kx1 - 3, 4):
        for fy in (F1, GL2 + 14):
            g.fill(x, fy + 2, kz1, x, fy + 3, kz1, parts.GLASS)
    # the reeve's hall
    # (the stair hall takes the east half of the floor: the hall is the west half)
    g.mark("lord", 68, F1 + 1, 44)
    g.stair(68, F1 + 1, 45, "minecraft:dark_oak_stairs", "north")
    g.mark("war_table", 68, F1 + 1, 41)
    g.chest(kx0 + 3, F1 + 1, kz0 + 3, "east", LOOT + "watchtower_treasury")
    g.chest(kx0 + 3, F1 + 1, kz0 + 4, "east", LOOT + "watchtower_treasury")
    g.wall_banner(68, F1 + 4, kz0 + 3, "south")
    g.mark("banner_stand", kx1 - 4, ktop, kz1 - 3)
    for (x, z) in ((kx0 + 4, kz0 + 3), (kx0 + 10, kz0 + 3), (kx0 + 4, kz1 - 3)):
        g.mark("archer", x, ktop, z)
    # the barracks and the store along the south wall, either side of the way in
    building(g, 57, 52, 67, 58, GL2, 6, "x", door_cells=((67, 55),), chimney=(58, 53))
    for x in range(59, 66, 3):
        g.bed(x, GL2, 53, "south", "brown")
    g.barrel(66, GL2, 57, LOOT + "castle_barracks", facing="west")
    building(g, 77, 52, 87, 58, GL2, 6, "x", door_cells=((77, 55),))
    for x in range(79, 86, 2):
        g.set(x, GL2, 57, "minecraft:hay_block[axis=y]")
    g.barrel(86, GL2, 53, LOOT + "castle_store", facing="west")
    for (x, z) in ((70, 56), (75, 56), (58, 40), (86, 42)):
        g.mark("knight", x, GL2, z)
