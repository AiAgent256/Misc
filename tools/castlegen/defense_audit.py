"""Defence audit: does the plan hold up as a fortification, not just a pile of stone?

Three questions, answered from the voxels and the posts:

walks     How wide is the walk under every archer and crossbowman? A man must be able to pass another
          behind the parapet: a one-wide ledge fails the build, two-wide is allowed only here and there.
flanking  Can the garrison shoot at a man standing at the foot of the outer wall? Each spot along the outside
          foot is checked for a clear line from some archer's eye within bow range. A wall seen only
          from its own walk has dead ground at its foot; towers standing out from the face see along it.
blind     Archer posts that can't see a single spot outside the walls (inner wards excepted: posts that see
          into a ward they overlook count as seeing).
"""
import numpy as np

from walk import passable, supports
from machicolate import LIP

RANGES = {"archer": 28, "crossbowman": 24}      # the roles' ranges in the mod (Role.java)
EYE = 1.6
STEP = 0.25


def _clear_lookup(g):
    """clear[x, z, y]: can an arrow pass through this cell? 1: all of it, 0: none, 2: the top half only (a
    bottom slab: the sills the bowmen stand on)."""
    see = np.zeros(len(g.palette), dtype=np.int8)
    for i, st in enumerate(g.palette):
        see[i] = 1 if passable(st) else 2 if ("slab" in st and "type=bottom" in st) else 0   # glass and bars stop an arrow
    see[0] = 1
    see[1] = 1
    return see[g.a]


def _sill(g, x, y, z):
    st = g.get(x, y, z)
    return "slab" in st and "type=bottom" in st


def _stand(g, x, y, z):
    """Can a man stand here: in open air on a floor, or up on a crenel's half-block sill."""
    if not (0 <= x < g.X and 0 <= z < g.Z and 1 <= y < g.Y - 2):
        return False
    if _sill(g, x, y, z):
        return passable(g.get(x, y + 1, z)) and passable(g.get(x, y + 2, z))
    return passable(g.get(x, y, z)) and passable(g.get(x, y + 1, z)) and supports(g.get(x, y - 1, z))


def _walk_cell(g, x, y, z):
    """The walk a post stands on: the post itself, or for a man on a sill the open cell behind him."""
    if not _sill(g, x, y, z):
        return (x, y, z)
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if passable(g.get(x + dx, y, z + dz)) and supports(g.get(x + dx, y - 1, z + dz)) \
                and not passable(g.get(x - dx, y - 1, z - dz)) is False:
            return (x + dx, y, z + dz)
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if passable(g.get(x + dx, y, z + dz)) and supports(g.get(x + dx, y - 1, z + dz)):
            return (x + dx, y, z + dz)
    return (x, y, z)


def _run(g, x, y, z, dx, dz, cap=8):
    n = 1
    for s in (1, -1):
        k = 1
        while k < cap and _stand(g, x + s * dx * k, y, z + s * dz * k):
            k += 1
        n += k - 1
    return n


def walk_widths(g, roles=("archer", "crossbowman")):
    """Width of the walk under each post: the narrower of its two runs."""
    out = []
    for role in roles:
        for (x, y, z) in g.markers.get(role, []):
            if not _stand(g, x, y, z) or (x, y, z) in getattr(g, "loop_posts", ()):
                continue                    # a man at a loop stands in its niche, not on a walk
            wx, wy, wz = _walk_cell(g, x, y, z)
            if not (passable(g.get(wx, wy, wz)) and supports(g.get(wx, wy - 1, wz))):
                continue
            # a walk is open to the sky; a man at a loop in a tower room isn't on one
            if not _sill(g, x, y, z) and any(not passable(g.get(wx, yy, wz)) for yy in range(wy + 2, min(g.Y, wy + 6))):
                continue
            # the narrower of the two runs, square to the grid or on the diagonals (a ring wall's walk runs
            # at every angle; a diagonal cell is half again as far across)
            square = min(_run(g, wx, wy, wz, 1, 0), _run(g, wx, wy, wz, 0, 1))
            diag = int(min(_run(g, wx, wy, wz, 1, 1), _run(g, wx, wy, wz, 1, -1)) * 1.41)
            out.append(((x, y, z), max(square, diag)))
    return out


PAD = 24                         # open country round the plan: the grid stops at the walls' feet


def _padded(g, GL):
    """clear[x, z, y] over the plan with PAD blocks of open, level country all round it."""
    clear = _clear_lookup(g)
    p = np.pad(clear, ((PAD, PAD), (PAD, PAD), (0, 0)), constant_values=1)
    ground = np.zeros(p.shape[:2], dtype=bool)
    ground[:PAD, :] = ground[-PAD:, :] = True
    ground[:, :PAD] = ground[:, -PAD:] = True
    p[ground, :GL] = 0
    # the plan's own empty margin is ground too
    void = g.a == 0
    vp = np.pad(void, ((PAD, PAD), (PAD, PAD), (0, 0)), constant_values=False)
    below = np.zeros_like(vp)
    below[:, :, :GL] = True
    p[vp & below] = 0
    shut = np.zeros(len(g.palette), dtype=bool)
    for i, st in enumerate(g.palette):
        shut[i] = any(k in st for k in ("door", "portcullis", "fence_gate", "iron_bars"))
    gate = np.pad(shut[g.a], ((PAD, PAD), (PAD, PAD), (0, 0)), constant_values=False)
    return p, gate


def _outside(clear, gate, GL):
    """Columns outside the walls at ground level: open at GL and GL+1 and joined to the edge of the country."""
    X, Z = clear.shape[:2]
    open2d = (clear[:, :, GL] == 1) & (clear[:, :, GL + 1] == 1) & ~gate[:, :, GL] & ~gate[:, :, GL + 1]
    seen = np.zeros_like(open2d)
    stack = [(x, z) for x in range(X) for z in (0, Z - 1)] + [(x, z) for z in range(Z) for x in (0, X - 1)]
    stack = [(x, z) for x, z in stack if open2d[x, z]]
    for x, z in stack:
        seen[x, z] = True
    while stack:
        x, z = stack.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            a, b = x + dx, z + dz
            if 0 <= a < X and 0 <= b < Z and open2d[a, b] and not seen[a, b]:
                seen[a, b] = True
                stack.append((a, b))
    return seen


def _band(mask, lo, hi):
    """Cells whose distance (chessboard) to `mask` is between lo and hi."""
    dist = np.full(mask.shape, 99, dtype=int)
    dist[mask] = 0
    cur = mask.copy()
    for k in range(1, hi + 1):
        grown = cur.copy()
        grown[1:, :] |= cur[:-1, :]; grown[:-1, :] |= cur[1:, :]
        grown[:, 1:] |= cur[:, :-1]; grown[:, :-1] |= cur[:, 1:]
        grown[1:, 1:] |= cur[:-1, :-1]; grown[:-1, :-1] |= cur[1:, 1:]
        grown[1:, :-1] |= cur[:-1, 1:]; grown[:-1, 1:] |= cur[1:, :-1]
        dist[grown & (dist == 99)] = k
        cur = grown
    return (dist >= lo) & (dist <= hi)


def targets(clear, gate, GL, tall=6):
    """Where attackers stand: at the foot of the walls (1 to 3 out) and in the field before them (6 to 30 out)."""
    outside = _outside(clear, gate, GL)
    walls = (clear[:, :, GL:GL + tall] != 1).all(axis=2)
    foot = np.argwhere(outside & _band(walls, 1, 3))
    field = np.argwhere(outside & _band(walls, 6, 30))
    return foot, field


def _visible(clear, eyes, targets):
    """For each (eye, target) pair: a clear straight line? eyes, targets: (n, 3) float arrays of x, y, z."""
    d = targets - eyes
    dist = np.linalg.norm(d, axis=1)
    n = int(np.ceil(dist.max() / STEP)) if len(dist) else 0
    ok = np.ones(len(eyes), dtype=bool)
    X, Z, Y = clear.shape
    for k in range(1, n):
        t = np.minimum(k * STEP / np.maximum(dist, 1e-6), 1.0)
        live = ok & (k * STEP < dist - 0.5)
        if not live.any():
            break
        p = eyes[live] + d[live] * t[live, None]
        xi = np.clip(np.floor(p[:, 0]).astype(int), 0, X - 1)
        yi = np.clip(np.floor(p[:, 1]).astype(int), 0, Y - 1)
        zi = np.clip(np.floor(p[:, 2]).astype(int), 0, Z - 1)
        idx = np.nonzero(live)[0]
        c = clear[xi, zi, yi]
        ok[idx] &= (c == 1) | ((c == 2) & (p[:, 1] - yi >= 0.5))
    return ok


def _seen(clear, eyes_all, ranges, pts, GL):
    """Which target spots some post can see within its reach, and which posts see any. The target's eye is
    at GL + 1.6, as the game's line of sight runs eye to eye; a man up high reaches a block further per block
    he stands above the target (as the archers' goal reckons it)."""
    tgt = np.column_stack([pts[:, 0] + 0.5, np.full(len(pts), GL + EYE), pts[:, 1] + 0.5])
    seen = np.zeros(len(pts), dtype=bool)
    sees = np.zeros(len(eyes_all), dtype=bool)
    for i, e in enumerate(eyes_all):
        dx = tgt[:, 0] - e[0]
        dz = tgt[:, 2] - e[2]
        dy = e[1] - tgt[:, 1]
        reach = ranges[i] + np.minimum(24.0, np.maximum(0.0, dy))
        cand = np.nonzero(dx * dx + dz * dz + dy * dy <= reach * reach)[0]
        if len(cand) == 0:
            continue
        ok = _visible(clear, np.repeat(e[None, :], len(cand), axis=0), tgt[cand])
        seen[cand[ok]] = True
        sees[i] = ok.any()
    return seen, sees


def flanking(g, GL, roles=("archer", "crossbowman"), stride=2):
    """Share of the walls' foot and of the field before them some archer can see; posts that see neither."""
    clear, gate = _padded(g, GL)
    foot, field = targets(clear, gate, GL)
    if stride > 1:
        foot = foot[(foot[:, 0] + foot[:, 1]) % stride == 0]
        field = field[(field[:, 0] + field[:, 1]) % (stride * 2) == 0]
    eyes, ranges = [], []
    lips = getattr(g, "lips", {})
    for r in roles:
        for (x, y, z) in g.markers.get(r, []):
            if not _stand(g, x, y, z):
                continue
            # a man beside a machicolation stands at its lip, as the mod puts him
            lx, lz = lips.get((x, y, z), (0, 0))
            eyes.append((x + PAD + 0.5 + LIP * lx, y + (0.5 if _sill(g, x, y, z) else 0.0) + EYE, z + PAD + 0.5 + LIP * lz))
            ranges.append(RANGES.get(r, 24))
    posts = np.array(eyes, dtype=float)
    ranges = np.array(ranges, dtype=float)
    if len(posts) == 0:
        return 0.0, 0.0, [(int(x) - PAD, GL, int(z) - PAD) for x, z in foot], 0
    fseen, fsees = _seen(clear, posts, ranges, foot, GL) if len(foot) else (np.ones(0, bool), np.zeros(len(posts), bool))
    dseen, dsees = _seen(clear, posts, ranges, field, GL) if len(field) else (np.ones(0, bool), np.zeros(len(posts), bool))
    blind = int((~(fsees | dsees)).sum())
    dead = [(int(x) - PAD, GL, int(z) - PAD) for x, z in foot[~fseen]]
    return (float(fseen.mean()) if len(foot) else 1.0), (float(dseen.mean()) if len(field) else 1.0), dead, blind


def audit(g, meta):
    GL = meta["GL"]
    widths = walk_widths(g)
    one = [p for p, w in widths if w <= 1]
    two = [p for p, w in widths if w == 2]
    cover, field, dead, blind = flanking(g, GL)
    return {"posts": len(widths), "one_wide": one, "two_wide": two, "cover": cover, "field": field, "dead": dead, "blind": blind}


def summary(r):
    return (f"posts {r['posts']}, one-wide {len(r['one_wide'])}, two-wide {len(r['two_wide'])}, "
            f"foot of walls seen {r['cover'] * 100:.0f}%, field seen {r['field'] * 100:.0f}%, blind posts {r['blind']}")
