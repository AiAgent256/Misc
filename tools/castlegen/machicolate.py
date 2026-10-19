"""Machicolations: holes in a corbelled parapet's floor, so the men on the wall can shoot straight down at its foot.

A parapet carried out on corbels has dead ground under it: a bowman's eye stands 1.6 over his floor and half a
block back from its edge, so the steepest he can look down is 1.6 / 0.5 = 3.2 blocks per block out, and at the
foot of a wall H high he can't see the first (H - 1.1) / 3.2 blocks past the ledge. A hole in the ledge, with the
man standing at its lip, lets him look straight down the face.

Every two-wide crenel on a corbelled parapet gets its second cell opened: the sill, the ledge under it and any
corbel, down to the open air below. The man on the crenel's sill stands at the hole's lip, and a second post on
the walk behind the hole leans over it from the inside. The layout carries each lip post with the side it leans
to; the mod stands a ranged man there LIP off his block's centre.
"""
import numpy as np

from walk import passable, supports

AIR = "minecraft:air"
DIRS = ((0, -1), (1, 0), (0, 1), (-1, 0))
LIP = 0.45                                          # how far off his block's centre a man at a lip stands


def _sill(st):
    return "slab" in st and "type=bottom" in st


def projecting(g, x, y, z, GL):
    """A sill on a parapet carried out on corbels, not on the wall's body: open air under its ledge for most of
    the way down."""
    below = [passable(g.get(x, yy, z)) for yy in range(GL + 2, y - 2)]
    return len(below) >= 2 and sum(below) >= 0.6 * len(below)


def open_crenels(g, GL):
    """Open the second cell of every two-wide crenel on a corbelled parapet. Returns the holes cut; remembers
    each crenel's remaining sill and its hole in g.hole_of."""
    ids = [i for i, st in enumerate(g.palette) if _sill(st)]
    g.hole_of = {}
    if not ids:
        return []
    sills = set()
    xs, zs, ys = np.nonzero(np.isin(g.a, ids))
    for x, z, y in zip(xs.tolist(), zs.tolist(), ys.tolist()):
        if y > GL + 3:
            sills.add((x, y, z))
    used = set()
    protected = getattr(g, "protected_xz", set())
    for (x, y, z) in sorted(sills):
        if (x, y, z) in used:
            continue
        for dx, dz in ((1, 0), (0, 1)):
            n = (x + dx, y, z + dz)
            if n not in sills or n in used or (n[0], n[2]) in protected:
                continue
            if min(x, z, g.X - 1 - n[0], g.Z - 1 - n[2]) < 1:
                continue                      # a crenel on the plan's edge: the walk behind it runs off the grid
            # both cells open to the sky, both stepped onto from the walk (a crenel's sill, not a merlon's cap
            # slab), and both on a ledge over open air
            if not all(passable(g.get(a, y + k, c)) for (a, _, c) in ((x, y, z), n) for k in (1, 2)):
                continue
            if not all(any(_floor(g, a + dx2, y, c + dz2) for dx2, dz2 in DIRS) for (a, _, c) in ((x, y, z), n)):
                continue
            if not (projecting(g, x, y, z, GL) and projecting(g, n[0], y, n[2], GL)):
                continue
            used.add((x, y, z)); used.add(n)
            for yy in (y, y - 1, y - 2):
                g.set(n[0], yy, n[2], AIR)
            g.hole_of[(x, y, z)] = n
            break
    return list(g.hole_of.values())


def _floor(g, x, y, z):
    return passable(g.get(x, y, z)) and passable(g.get(x, y + 1, z)) and supports(g.get(x, y - 1, z))


def assign_lips(g, roles=("archer", "crossbowman")):
    """After the men are on their sills: each sill man beside his crenel's hole leans over it, and a second man
    goes on the walk behind the hole, leaning out over it. g.lips maps a post to (dx, dz)."""
    g.lips = dict(getattr(g, "lips", {}))          # a plan may have put men at its own holes (murder holes)
    hole_of = getattr(g, "hole_of", {})
    taken = {tuple(p) for r in roles for p in g.markers.get(r, [])}
    for role in roles:
        extra = []
        for p in g.markers.get(role, []):
            p = tuple(p)
            h = hole_of.get(p)
            if h is None:
                continue
            g.lips[p] = (h[0] - p[0], h[2] - p[2])
            # the walk behind the hole: the neighbour of the hole that isn't the sill, has a floor, and has the
            # open drop of the hole between it and the field
            hx, y, hz = h
            for dx, dz in DIRS:
                w = (hx - dx, y, hz - dz)
                if w == p or w in taken or not _floor(g, *w):
                    continue
                if not passable(g.get(hx + dx, y, hz + dz)):
                    continue                      # beyond the hole must be the open air, not the merlon
                if not all(_floor(g, w[0] - k * dx, y, w[2] - k * dz) for k in (1, 2)):
                    continue                      # and behind him a walk two more deep, not a squeeze by a tower
                extra.append(w)
                taken.add(w)
                g.lips[w] = (dx, dz)
                break
        g.markers[role] = list(g.markers.get(role, [])) + extra
    return len(g.lips)
