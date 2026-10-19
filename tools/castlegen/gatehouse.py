"""The gatehouse castle: a tier-2 seat built round its gate.

A square curtain five thick with a walk four men wide, tall square towers at the corners standing well out
from both faces, a bastion halfway along each of the other three sides, and on the south side the gatehouse
itself: two tall towers either side of an arched passage with a portcullis at each end, a fighting platform
over the gate on a machicolated parapet, one tower roofed in timber on posts like a belfry, the other open
behind deep crenellations. Inside, a half-timbered great hall with dormers, the lord's chambers over it, and
barracks, a stable and a smithy against the walls.

Every tower and bastion stands out from the curtain so the men on its top, and at the loops low in its
rooms, see along the walls' faces: nobody stands at the foot of this wall unseen.
"""
import numpy as np

from grid import Grid, Mix, rect, rect_ring, dogleg_stair, gable_roof
from parts import (STONE, DARK, FOUND, PAVE, ROAD, FLOOR_STONE, ROOF, ROOF_STAIR, STONE_STAIR, DARK_STAIR, SLAB_CAP,
                   SILL, RAIL, PLANK, DARK_PLANK, GLASS, curtain, posts_on_walk, square_tower, pointed_arch,
                   building, lamp_post, wall_stair, _piers)
from castle import portcullis_plane, bartizan

LOOT = "stormhold:chests/"
AIR = "minecraft:air"
PLASTER = Mix(("minecraft:white_terracotta", 45), ("minecraft:light_gray_terracotta", 15),
              ("minecraft:smooth_sandstone", 25), ("minecraft:birch_planks", 15))
BEAM_Y = "minecraft:dark_oak_log[axis=y]"
BEAM_X = "minecraft:dark_oak_log[axis=x]"
BEAM_Z = "minecraft:dark_oak_log[axis=z]"
GRASS = Mix(("minecraft:grass_block", 70), ("minecraft:coarse_dirt", 15), ("minecraft:podzol", 10),
            ("minecraft:dirt_path", 5))


def _timber_roof(g, x0, z0, x1, z1, top):
    """A belfry roof over a square tower's platform: timber posts on the merlons, a dark plank eave out over the
    parapet, and a slate pyramid stepping up from it with a finial. The crenels under it stay open."""
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    _piers(g, cx, cz, (x1 - x0) // 2, top, square=(x0, z0, x1, z1))
    y = top + 4
    g.fill(x0 - 2, y, z0 - 2, x1 + 2, y, z1 + 2, DARK_PLANK)
    for (a0, a1, b0, b1) in ((x0 - 2, x1 + 2, z0 - 2, z0 - 2), (x0 - 2, x1 + 2, z1 + 2, z1 + 2)):
        g.fill(a0, y - 1, b0, a1, y - 1, b1, "minecraft:dark_oak_slab[type=top]")
    for (a0, a1, b0, b1) in ((x0 - 2, x0 - 2, z0 - 2, z1 + 2), (x1 + 2, x1 + 2, z0 - 2, z1 + 2)):
        g.fill(a0, y - 1, b0, a1, y - 1, b1, "minecraft:dark_oak_slab[type=top]")
    k = 0
    y += 1
    while x0 - 1 + k <= x1 + 1 - k and z0 - 1 + k <= z1 + 1 - k:
        g.fill(x0 - 1 + k, y, z0 - 1 + k, x1 + 1 - k, y, z1 + 1 - k, ROOF)
        k += 1
        y += 1
    g.fill(cx, y, cz, cx, y + 2, cz, "minecraft:dark_oak_fence")
    g.set(cx, y + 3, cz, "minecraft:lightning_rod")
    return y


def _tower_posts(g, x0, z0, x1, z1, top, role="archer", every=3):
    """A bowman's place round the inside of a tower's parapet; the embrasure pass puts each on a sill."""
    for x in range(x0 + 1, x1, every):
        g.mark(role, x, top, z0); g.mark(role, x, top, z1)
    for z in range(z0 + 1 + every, z1 - 1, every):
        g.mark(role, x0, top, z); g.mark(role, x1, top, z)


def gatehouse_castle(seed=37):
    """Tier 2: a square curtain round a great hall, tall corner towers, three bastions and a twin-towered gatehouse."""
    W, H, GL = 96, 72, 5
    g = Grid(W, H, W, seed)
    a, b = 14, W - 15                 # the curtain's outer faces: 14 .. 81
    thick = 5                         # a walk four men wide behind the parapet
    walk = GL + 12
    c = (a + b) // 2                  # 47
    xs, zs = g.xz()
    inner = (xs >= a) & (xs <= b) & (zs >= a) & (zs <= b)
    # the ground: turf outside, a cobbled and trodden bailey inside
    g.fill(0, 0, 0, W - 1, GL - 2, W - 1, FOUND)
    g.fill_mask(~inner, GL - 1, GL - 1, GRASS)
    g.fill_mask(inner, GL - 1, GL - 1, PAVE)
    g.fill(0, GL, 0, W - 1, H - 1, W - 1, AIR)

    # ---- the curtain ----
    d, along, out = curtain(g, a, b, thick, walk, GL, gallery=False, banners_every=0)
    posts_on_walk(g, d, along, walk, "archer", dsel=1, every=4, offset=2)

    # ---- the corner towers: nineteen square, six out beyond both faces, a stair hall toward the outer corner ----
    ttop = walk + 9
    cap = __import__("parts").CAP_STYLE
    for k, (sx, sz) in enumerate(((1, 1), (-1, 1), (1, -1), (-1, -1))):
        xo = a - 6 if sx > 0 else b + 6
        zo = a - 6 if sz > 0 else b + 6
        x0, x1 = (xo, xo + 18) if sx > 0 else (xo - 18, xo)
        z0, z1 = (zo, zo + 18) if sz > 0 else (zo - 18, zo)
        roofed = cap != "flat" and k in (0, 3)
        square_tower(g, x0, z0, x1, z1, ttop, GL, floors=(walk - 1,), t=2, cone=False)
        ix0, iz0, ix1, iz1 = x0 + 2, z0 + 2, x1 - 2, z1 - 2
        # two clear of the walls, so the platform round the stair head stays four wide for the bowmen
        wx0 = ix0 + 2 if sx > 0 else ix1 - 8
        wz0 = iz0 + 2 if sz > 0 else iz1 - 8
        dogleg_stair(g, wx0, wz0, GL, ttop - 1, STONE_STAIR, STONE, PLANK, levels=(walk,),
                     back="north" if sz > 0 else "south", rail=RAIL)
        # the walk runs through the tower room: doors in its two inner walls, the width of the walk
        wxs = (a, a + thick - 2) if sx > 0 else (b - thick + 2, b)
        wzs = (a, a + thick - 2) if sz > 0 else (b - thick + 2, b)
        dx0, dx1 = (x1 - 1, x1) if sx > 0 else (x0, x0 + 1)
        dz0, dz1 = (z1 - 1, z1) if sz > 0 else (z0, z0 + 1)
        g.fill(dx0, walk, wzs[0], dx1, walk + 2, wzs[1], AIR)
        g.fill(wxs[0], walk, dz0, wxs[1], walk + 2, dz1, AIR)
        # and a door from the bailey into its ground floor, three wide
        gx = (a + thick, a + thick + 2) if sx > 0 else (b - thick - 2, b - thick)
        g.fill(gx[0], GL, dz0, gx[1], GL + 2, dz1, AIR)
        if roofed:
            _timber_roof(g, x0, z0, x1, z1, ttop)
        _tower_posts(g, x0, z0, x1, z1, ttop)
        g.wall_banner(x0 + 9, ttop - 6, z0 - 1 if sz > 0 else z1 + 1, "north" if sz > 0 else "south")

    # ---- the bastions: halfway along the north, west and east walls, six out, a guardroom inside ----
    for side in ("north", "west", "east"):
        if side == "north":
            x0, x1, z0, z1 = c - 5, c + 5, a - 6, a + thick - 1
            outer = [(x, z0) for x in range(x0, x1 + 1)] + [(x, z) for x in (x0, x1) for z in range(z0, a)]
        elif side == "west":
            x0, x1, z0, z1 = a - 6, a + thick - 1, c - 5, c + 5
            outer = [(x0, z) for z in range(z0, z1 + 1)] + [(x, z) for z in (z0, z1) for x in range(x0, a)]
        else:
            x0, x1, z0, z1 = b - thick + 1, b + 6, c - 5, c + 5
            outer = [(x1, z) for z in range(z0, z1 + 1)] + [(x, z) for z in (z0, z1) for x in range(b + 1, x1 + 1)]
        g.fill(x0, 0, z0, x1, walk - 1, z1, STONE)
        g.fill(x0, walk, z0, x1, walk + 4, z1, AIR)
        # its plinth and string course, like the towers'
        for (x, z) in outer:
            g.set(x, GL + 3, z, DARK)
        # the parapet round its three outer sides: half-block sills between the merlons
        for (x, z) in outer:
            m = (x + z) % 4 < 2
            g.set(x, walk, z, STONE if m else SILL)
            if m:
                g.set(x, walk + 1, z, STONE); g.set(x, walk + 2, z, SLAB_CAP)
        # corbels under the parapet
        for (x, z) in outer:
            if (x + z) % 2 == 0:
                fx = "west" if (side == "west" and x == x0) else "east" if (side == "east" and x == x1) else None
                fz = "north" if (side == "north" and z == z0) else None
                if side == "north" and x in (x0, x1) and z != z0:
                    fx = "west" if x == x0 else "east"
                if side in ("west", "east") and z in (z0, z1) and x not in (x0, x1):
                    fz = "north" if z == z0 else "south"
                f = fz or fx
                if f:
                    g.stair(x, walk - 2, z, STONE_STAIR, {"north": "south", "south": "north", "east": "west",
                                                          "west": "east"}[f], half="top")
        # the rail on the curtain's inner edge carries on across the bastion's back
        if side == "north":
            g.fill(x0, walk, z1, x1, walk, z1, RAIL)
            room = (x0 + 1, z0 + 1, x1 - 1, a - 1)
            g.fill(c - 1, GL, a - 1, c + 1, GL + 2, a + thick, AIR)
            g.fill(c - 1, GL - 1, a - 1, c + 1, GL - 1, a + thick, FLOOR_STONE)
            posts = [(x, walk, z0 + 1) for x in range(x0 + 1, x1, 3)] + [(x, walk, z) for x in (x0 + 1, x1 - 1) for z in (z0 + 3,)]
        elif side == "west":
            g.fill(x1, walk, z0, x1, walk, z1, RAIL)
            room = (x0 + 1, z0 + 1, a - 1, z1 - 1)
            g.fill(a - 1, GL, c - 1, a + thick, GL + 2, c + 1, AIR)
            g.fill(a - 1, GL - 1, c - 1, a + thick, GL - 1, c + 1, FLOOR_STONE)
            posts = [(x0 + 1, walk, z) for z in range(z0 + 1, z1, 3)] + [(x0 + 3, walk, z) for z in (z0 + 1, z1 - 1)]
        else:
            g.fill(x0, walk, z0, x0, walk, z1, RAIL)
            room = (b + 1, z0 + 1, x1 - 1, z1 - 1)
            g.fill(b - thick, GL, c - 1, b + 1, GL + 2, c + 1, AIR)
            g.fill(b - thick, GL - 1, c - 1, b + 1, GL - 1, c + 1, FLOOR_STONE)
            posts = [(x1 - 1, walk, z) for z in range(z0 + 1, z1, 3)] + [(x1 - 3, walk, z) for z in (z0 + 1, z1 - 1)]
        rx0, rz0, rx1, rz1 = room
        g.fill(rx0, GL, rz0, rx1, GL + 3, rz1, AIR)
        g.fill(rx0, GL - 1, rz0, rx1, GL - 1, rz1, FLOOR_STONE)
        g.lantern((rx0 + rx1) // 2, GL + 3, (rz0 + rz1) // 2)
        for p in posts:
            g.mark("archer", *p)

    # ---- the gatehouse ----
    gp0, gp1 = c - 3, c + 2                   # the passage, six wide
    gz0, gz1 = b - 8, b + 11                  # front to back: eleven out beyond the curtain's face
    gtop = walk + 16                         # the gate towers stand above everything else
    g.fill(gp0, 0, gz0, gp1, walk - 1, gz1, STONE)
    for (x0, x1, west) in ((c - 18, c - 4, True), (c + 3, c + 17, False)):
        square_tower(g, x0, gz0, x1, gz1, gtop, GL, floors=(GL + 6, walk - 1), t=2, cone=False)
        ix0, iz0, ix1, iz1 = x0 + 2, gz0 + 2, x1 - 2, gz1 - 2
        # the stair hall in the tower's outer half, its landing against the front wall
        dogleg_stair(g, ix0 + 2, iz1 - 8, GL, gtop - 1, STONE_STAIR, STONE, PLANK,
                     levels=(GL + 7, walk), back="south", rail=RAIL)
        # the walk comes in from the curtain on one side and goes out onto the gate platform on the other
        g.fill(x0, walk, b - thick + 2, x0 + 1, walk + 2, b, AIR)
        g.fill(x1 - 1, walk, b - thick + 2, x1, walk + 2, b, AIR)
        # and a door from the bailey into its ground floor
        mx = (x0 + x1) // 2
        g.fill(mx - 1, GL, gz0, mx + 1, GL + 2, gz0 + 1, AIR)
        if west:
            west_tower = (x0, gz0, x1, gz1)
        else:
            east_tower = (x0, gz0, x1, gz1)
        _tower_posts(g, x0, gz0, x1, gz1, gtop, role="crossbowman" if west else "archer", every=3)
    # the passage: arched, a portcullis at each end, the road through it
    pointed_arch(g, gp0, gp1, GL, 8, gz0, gz1)
    g.fill(gp0, GL - 1, gz0, gp1, GL - 1, W - 1, ROAD)
    portcullis_plane(g, gp0, gp1, GL, 8, gz1 - 1)
    portcullis_plane(g, gp0, gp1, GL, 8, gz0 + 1)
    # the fighting platform over the gate, between the towers, open to the sky
    g.fill(gp0, walk, gz0, gp1, walk + 6, gz1, AIR)
    g.fill(gp0, walk - 1, gz0, gp1, walk - 1, gz1, STONE)
    g.fill(gp0, walk, gz0, gp1, walk, gz0, RAIL)
    # machicolation: the platform's front parapet stands out over the gate on corbels
    for x in range(gp0, gp1 + 1):
        g.stair(x, walk - 3, gz1 + 1, STONE_STAIR, "north", half="top")
        g.set(x, walk - 2, gz1 + 1, STONE if x % 2 else AIR)        # the holes between the corbels
        g.set(x, walk - 1, gz1 + 1, STONE)
        m = x % 4 < 2
        g.set(x, walk, gz1 + 1, STONE if m else SILL)
        if m:
            g.set(x, walk + 1, gz1 + 1, STONE); g.set(x, walk + 2, gz1 + 1, SLAB_CAP)
    g.fill(gp0, walk - 1, gz1, gp1, walk - 1, gz1, STONE)
    for x in range(gp0, gp1 + 1, 2):
        g.mark("crossbowman", x, walk, gz1)
    # the west tower takes the timber belfry roof; the east stands open behind its crenels, a turret on each
    # front corner
    _timber_roof(g, *west_tower, gtop)
    ex0, ez0, ex1, ez1 = east_tower
    for x in (ex0 - 1, ex1 + 1):
        bartizan(g, x, ez1 + 1, gtop + 1)
    # string courses round both towers at the gate hall's floor and under the parapet
    for (x0, z0, x1, z1) in (west_tower, east_tower):
        for y in (walk - 1, gtop - 4):
            for x in range(x0, x1 + 1):
                g.set(x, y, z0, DARK); g.set(x, y, z1, DARK)
            for z in range(z0, z1 + 1):
                g.set(x0, y, z, DARK); g.set(x1, y, z, DARK)
        # loops in the front, two storeys of them: a one-block slit framed in dressed stone, too small to climb
        for x in (x0 + 4, x1 - 4):
            for y0 in (GL + 8, walk + 4):
                g.set(x, y0, z1, "minecraft:chiseled_stone_bricks")
                g.set(x, y0 + 1, z1, "minecraft:air")
                g.set(x, y0 + 2, z1, "minecraft:chiseled_stone_bricks")
    g.mark("banner_stand", west_tower[0] + 7, gtop, west_tower[1] + 4)
    for x in (gp0 - 1, gp1 + 1):
        g.wall_banner(x, GL + 10, gz1 + 1, "south")
    g.mark("gate", c, GL, W - 1)

    # ---- stairs from the bailey to the walk, three wide, against the west and east walls ----
    wall_stair(g, a + thick, 58, GL, walk, "z", "west")
    wall_stair(g, b - thick, 58, GL, walk, "z", "east")

    # ---- the great hall: stone below, timber-framed and plastered above, dormers in its roof ----
    hx0, hz0, hx1, hz1 = c - 17, 27, c + 17, 41
    F2 = GL + 6
    eave = GL + 12
    g.fill(hx0, 0, hz0, hx1, GL - 2, hz1, FOUND)
    g.fill(hx0, GL - 1, hz0, hx1, GL - 1, hz1, FLOOR_STONE)
    g.fill(hx0, GL, hz0, hx1, F2, hz1, STONE)
    g.fill(hx0, F2 + 1, hz0, hx1, eave - 1, hz1, PLASTER)
    g.fill(hx0 + 1, GL, hz0 + 1, hx1 - 1, eave - 1, hz1 - 1, AIR)
    g.fill(hx0 + 1, F2, hz0 + 1, hx1 - 1, F2, hz1 - 1, DARK_PLANK)
    g.fill(hx0 + 1, eave - 1, hz0 + 1, hx1 - 1, eave - 1, hz1 - 1, PLANK)
    # the frame: posts every four, a sill beam on the stone and a plate beam under the eave
    for x in range(hx0, hx1 + 1):
        for z in (hz0, hz1):
            if (x - hx0) % 4 == 0:
                g.fill(x, F2 + 1, z, x, eave - 1, z, BEAM_Y)
            g.set(x, F2 + 1, z, BEAM_X); g.set(x, eave - 1, z, BEAM_X)
    for z in range(hz0, hz1 + 1):
        for x in (hx0, hx1):
            if (z - hz0) % 4 == 0 or z == hz1:
                g.fill(x, F2 + 1, z, x, eave - 1, z, BEAM_Y)
            g.set(x, F2 + 1, z, BEAM_Z); g.set(x, eave - 1, z, BEAM_Z)
    # the upper storey's windows between the posts, the hall's tall ones below
    for x in range(hx0 + 2, hx1 - 1, 4):
        for z in (hz0, hz1):
            g.fill(x, F2 + 2, z, x, F2 + 3, z, GLASS)
            g.fill(x, GL + 1, z, x, GL + 3, z, GLASS)
    for z in range(hz0 + 2, hz1 - 1, 4):
        for x in (hx0, hx1):
            g.fill(x, F2 + 2, z, x, F2 + 3, z, GLASS)
    # doors: the great door in the south front, a back door to the north
    g.fill(c - 1, GL, hz1, c + 1, GL + 3, hz1, AIR)
    g.fill(c - 1, GL - 1, hz1 + 1, c + 1, GL - 1, hz1 + 4, ROAD)
    g.stair(c - 2, GL + 4, hz1 + 1, DARK_STAIR, "north", half="top"); g.stair(c + 2, GL + 4, hz1 + 1, DARK_STAIR, "north", half="top")
    g.fill(c - 1, GL + 4, hz1 + 1, c + 1, GL + 4, hz1 + 1, "minecraft:dark_oak_slab[type=top]")
    g.fill(hx1 - 4, GL, hz0, hx1 - 3, GL + 2, hz0, AIR)
    # the stair up to the chambers: a stair hall in the west end
    dogleg_stair(g, hx0 + 1, hz0 + 1, GL, F2, STONE_STAIR, STONE, DARK_PLANK, levels=(F2 + 1,), back="north", rail=RAIL)
    g.fill(hx0 + 8, GL, hz0 + 1, hx0 + 8, F2 - 1, hz1 - 1, STONE)         # the screens wall between stair hall and hall
    g.fill(hx0 + 8, GL, hz0 + 9, hx0 + 8, GL + 2, hz0 + 11, AIR)
    # upstairs: the lord's solar at the east end with the strongboxes, a chamber, a lodging for the household
    g.fill(hx1 - 9, F2 + 1, hz0 + 1, hx1 - 9, eave - 2, hz1 - 1, PLASTER)
    g.fill(hx1 - 9, F2 + 1, hz0 + 6, hx1 - 9, F2 + 3, hz0 + 8, AIR)
    g.fill(c, F2 + 1, hz0 + 1, c, eave - 2, hz1 - 1, PLASTER)
    g.fill(c, F2 + 1, hz0 + 6, c, F2 + 3, hz0 + 8, AIR)
    for z in range(hz0 + 2, hz0 + 10, 2):
        g.chest(hx1 - 2, F2 + 1, z, "west", LOOT + "holdfast_treasury")
    for z in range(hz0 + 2, hz1 - 1, 3):
        g.bed(hx0 + 10, F2 + 1, z, "east", "gray")
    g.bed(hx1 - 5, F2 + 1, hz1 - 3, "west", "red")
    for (x, z) in ((hx0 + 12, hz0 + 7), (c + 6, hz0 + 7), (hx1 - 5, hz0 + 7)):
        g.lantern(x, eave - 2, z)
    # the hall below: the lord's dais at the east end, the tables down the length of it
    xd = hx1 - 2
    g.fill(xd - 1, GL, hz0 + 3, xd, GL, hz1 - 3, "minecraft:polished_deepslate")
    g.stair(xd, GL + 1, (hz0 + hz1) // 2, "minecraft:dark_oak_stairs", "west")
    g.mark("lord", xd - 1, GL + 1, (hz0 + hz1) // 2)
    g.mark("war_table", xd - 1, GL + 1, hz0 + 4)
    g.fill(hx0 + 10, GL, (hz0 + hz1) // 2 - 1, xd - 3, GL, (hz0 + hz1) // 2 + 1, "minecraft:red_carpet")
    for z in (hz0 + 3, hz1 - 3):
        g.fill(hx0 + 11, GL, z, xd - 5, GL, z, "minecraft:dark_oak_fence")
        g.fill(hx0 + 11, GL + 1, z, xd - 5, GL + 1, z, "minecraft:dark_oak_slab[type=bottom]")
    g.wall_banner(hx1 - 1, GL + 5, (hz0 + hz1) // 2 - 2, "west"); g.wall_banner(hx1 - 1, GL + 5, (hz0 + hz1) // 2 + 2, "west")
    for (x, z) in ((hx0 + 14, hz0 + 5), (c + 6, hz0 + 5), (hx0 + 14, hz1 - 5), (c + 6, hz1 - 5)):
        g.lantern(x, F2 - 1, z)
    # the roof: steep slate, gables in plaster and beams, dormers along both sides, a great chimney
    top = gable_roof(g, hx0, hz0, hx1, hz1, eave, "x", ROOF_STAIR, PLASTER)
    for x in range(hx0 + 6, hx1 - 4, 7):
        for (zf, zb, f) in ((hz1, hz1 - 4, "south"), (hz0, hz0 + 4, "north")):
            lo, hi = sorted((zf, zb))
            g.fill(x - 1, eave, lo, x + 1, eave + 3, hi, PLASTER)
            g.fill(x - 1, eave, zf, x - 1, eave + 3, zf, BEAM_Y); g.fill(x + 1, eave, zf, x + 1, eave + 3, zf, BEAM_Y)
            g.fill(x, eave + 1, zf, x, eave + 2, zf, GLASS)
            g.fill(x, eave, lo + 1, x, eave + 2, hi - 1, AIR)
            g.stair(x - 1, eave + 4, zf, ROOF_STAIR, "east"); g.stair(x + 1, eave + 4, zf, ROOF_STAIR, "west")
            g.set(x, eave + 4, zf, "minecraft:deepslate_tile_slab[type=bottom]")
    for x in (hx0 + 3, hx1 - 7):
        g.fill(x, GL, hz0 - 1, x + 1, top + 2, hz0 - 1, "minecraft:bricks")
        g.set(x, top + 3, hz0 - 1, "minecraft:campfire[lit=true,signal_fire=false]")
    g.wall_banner(c - 3, F2 + 3, hz1 + 1, "south"); g.wall_banner(c + 3, F2 + 3, hz1 + 1, "south")

    # ---- the bailey: barracks on the west, stable and smithy on the east, a well, the garrison's places ----
    building(g, a + thick + 4, 47, a + thick + 13, 56, GL, 6, "z", door_cells=((a + thick + 13, 51),), chimney=(a + thick + 6, 49))
    for z in range(48, 56, 3):
        g.bed(a + thick + 5, GL, z, "west", "brown")
    building(g, a + thick + 4, 64, a + thick + 13, 72, GL, 6, "z", door_cells=((a + thick + 13, 68),))
    for z in range(65, 72, 3):
        g.bed(a + thick + 5, GL, z, "west", "brown")
    g.barrel(a + thick + 12, GL, 71, LOOT + "castle_barracks", facing="west")
    building(g, b - thick - 13, 47, b - thick - 4, 56, GL, 6, "z", door_cells=((b - thick - 13, 51),))
    for z in (49, 54):
        g.set(b - thick - 5, GL, z, "minecraft:hay_block[axis=y]")
    building(g, b - thick - 13, 64, b - thick - 4, 72, GL, 5, "x", door_cells=((b - thick - 13, 68),), chimney=(b - thick - 6, 66))
    g.set(b - thick - 11, GL, 65, "minecraft:anvil[facing=north]"); g.set(b - thick - 8, GL, 65, "minecraft:blast_furnace[facing=south]")
    # the well
    g.fill(c + 6, GL - 3, 56, c + 8, GL - 1, 58, "minecraft:stone_bricks")
    g.set(c + 7, GL - 1, 57, "minecraft:water"); g.set(c + 7, GL - 2, 57, "minecraft:water")
    g.set(c + 7, GL, 57, "minecraft:oak_fence"); g.set(c + 7, GL + 1, 57, "minecraft:oak_fence")
    g.set(c + 7, GL + 2, 57, "minecraft:dark_oak_slab[type=bottom]")
    for x in range(c - 12, c + 13, 8):
        lamp_post(g, x, 47, GL)
    for (x, z) in ((c - 8, 50), (c + 8, 50), (c - 8, 62), (c + 8, 62), (c, 66), (c - 4, 44), (c + 4, 44),
                   (a + thick + 2, 30), (b - thick - 2, 30), (c, 23)):
        g.mark("knight", x, GL, z)
    g.mark("captain", c, GL, gz0 - 3)
    g.mark("rally", c, GL, gz0 - 6)
    return g, {"name": "gatehouse_castle", "tier": 2, "GL": GL}


gatehouse_castle.cap_style = "pyramid"
