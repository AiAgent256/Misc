"""Flanking loops: arrow slits low in the towers, cut where they see along the curtain's face.

A man on the wall walk can't see the foot of his own wall: the parapet he leans over hides it. Real
castles answered with towers standing out from the curtain and loops in their flanks looking along the
face, so a man at the foot of the wall with a ladder is in someone's sights. This finds, for whatever plan
it's given, the slits that cover the most dead ground: a one-wide, two-high cut through a tower's shell from
a room inside to the open air outside, with a bowman's post behind it. It takes the best slit, then the
next best for what's still dead, until the slits stop paying.
"""
import numpy as np

import defense_audit as da
import walk
from walk import passable, supports

MASONRY = ("brick", "stone", "andesite", "tuff", "deepslate", "cobble", "diorite", "granite", "calcite", "blackstone")
NOT_MASONRY = ("stairs", "slab", "wall", "door", "lantern", "banner", "ladder", "glass", "bars", "chest", "barrel",
               "torch", "sign", "button", "lever", "portcullis")
DIRS = ((1, 0), (-1, 0), (0, 1), (0, -1))


def _masonry(st):
    return any(k in st for k in MASONRY) and not any(k in st for k in NOT_MASONRY)


def _standable(g, x, y, z):
    return (0 < x < g.X - 1 and 0 < z < g.Z - 1 and passable(g.get(x, y, z)) and passable(g.get(x, y + 1, z))
            and supports(g.get(x, y - 1, z)))


def candidates(g, GL, outside2d, lo=2, hi=30, max_thick=6, reach=None):
    """Every place a loop could go: a floor inside, then masonry, then open air over the outside. The loop is a
    niche cut two high through the masonry, a man's height, ending in a slit one block square in the outer face,
    at his eye: he sees out, nobody climbs in. Yields (post, niche cells, slit cell, direction)."""
    prot = getattr(g, "protected_xz", set())
    out = []
    for y in range(GL + lo, GL + hi):
        for x in range(1, g.X - 1):
            for z in range(1, g.Z - 1):
                if outside2d[x, z] or not _standable(g, x, y, z):
                    continue
                if reach is not None and (x, y, z) not in reach:
                    continue                 # a sealed void, not a room the garrison can get to
                outdoors = all(passable(g.get(x, yy, z)) for yy in range(y + 2, min(g.Y, y + 8)))
                for dx, dz in DIRS:
                    run = []
                    k = 1
                    while k <= max_thick:
                        cx, cz = x + dx * k, z + dz * k
                        if not (0 < cx < g.X - 1 and 0 < cz < g.Z - 1) or (cx, cz) in prot:
                            run = None
                            break
                        if _masonry(g.get(cx, y + 1, cz)):
                            if passable(g.get(cx, y + 2, cz)):
                                run = None           # it's the floor of a walk or a room above: leave it be
                                break
                            run.append((cx, cz))
                            k += 1
                            continue
                        break
                    if not run or k > max_thick:
                        continue
                    ox, oz = x + dx * k, z + dz * k
                    if not (passable(g.get(ox, y + 1, oz)) and outside2d[ox, oz]):
                        continue
                    niche, slit = run[:-1], run[-1]
                    if outdoors and (niche or y < GL + 3):
                        continue             # under the sky only a loop in a merlon, up on a walk: no niches dug from the yard
                    # the niche must be cuttable all the way down to the floor he stands on
                    if not all(_masonry(g.get(cx, y, cz)) for cx, cz in niche):
                        continue
                    post = (niche[-1][0], y, niche[-1][1]) if niche else (x, y, z)
                    out.append((post, niche, slit, (dx, dz)))
    return out


def cut(g, post, niche, slit, d):
    y = post[1]
    for (cx, cz) in niche:
        g.set(cx, y, cz, "minecraft:air")
        g.set(cx, y + 1, cz, "minecraft:air")
        # the floor of the niche: the masonry under it holds him
    g.set(slit[0], y + 1, slit[1], "minecraft:air")
    # a chiseled stone over the slit, so the loop reads from outside
    if _masonry(g.get(slit[0], y + 2, slit[1])):
        g.set(slit[0], y + 2, slit[1], "minecraft:chiseled_stone_bricks")


def flank(g, GL, role="archer", max_loops=48, min_gain=4, spacing=3):
    """Cut the slits that see the most of the walls' dead foot, best first. Returns (loops cut, foot seen before, after)."""
    clear, gate = da._padded(g, GL)
    foot, _ = da.targets(clear, gate, GL)
    if len(foot) == 0:
        return 0, 1.0, 1.0
    outside_p = da._outside(clear, gate, GL)
    outside2d = outside_p[da.PAD:-da.PAD, da.PAD:-da.PAD]
    before, _, _, _ = da.flanking(g, GL, stride=1)
    eyes, ranges = [], []
    for r in ("archer", "crossbowman"):
        for (x, y, z) in g.markers.get(r, []):
            if da._stand(g, x, y, z):
                eyes.append((x + da.PAD + 0.5, y + (0.5 if da._sill(g, x, y, z) else 0.0) + da.EYE, z + da.PAD + 0.5))
                ranges.append(da.RANGES.get(r, 24))
    seen = np.zeros(len(foot), dtype=bool)
    if eyes:
        seen, _ = da._seen(clear, np.array(eyes, float), np.array(ranges, float), foot, GL)
    gate = g.markers.get("gate", [None])[0]
    cands = candidates(g, GL, outside2d, reach=walk.reach(g, gate) if gate else None)
    if not cands:
        return 0, before, before
    # what each slit would see, with its own cut opened
    tgt = np.column_stack([foot[:, 0] + 0.5, np.full(len(foot), GL + da.EYE), foot[:, 1] + 0.5])
    sees = []
    reach_base = da.RANGES.get(role, 24)
    for (post, niche, slit, d) in cands:
        x, y, z = post
        cells = niche + [slit]
        e = np.array([x + da.PAD + 0.5, y + da.EYE, z + da.PAD + 0.5])
        dx = tgt[:, 0] - e[0]
        dz = tgt[:, 2] - e[2]
        dy = e[1] - tgt[:, 1]
        # only spots ahead of the slit, the way it faces
        ahead = dx * d[0] + dz * d[1] > 0
        reach = reach_base + np.minimum(24.0, np.maximum(0.0, dy))
        idx = np.nonzero(ahead & ~seen & (dx * dx + dz * dz + dy * dy <= reach * reach))[0]
        if len(idx) == 0:
            sees.append(np.zeros(0, dtype=int))
            continue
        saved = [(cx, cz, clear[cx + da.PAD, cz + da.PAD, y], clear[cx + da.PAD, cz + da.PAD, y + 1]) for cx, cz in cells]
        for (cx, cz) in niche:
            clear[cx + da.PAD, cz + da.PAD, y] = 1
            clear[cx + da.PAD, cz + da.PAD, y + 1] = 1
        clear[slit[0] + da.PAD, slit[1] + da.PAD, y + 1] = 1
        ok = da._visible(clear, np.repeat(e[None, :], len(idx), axis=0), tgt[idx])
        for (cx, cz, a, b) in saved:
            clear[cx + da.PAD, cz + da.PAD, y] = a
            clear[cx + da.PAD, cz + da.PAD, y + 1] = b
        sees.append(idx[ok])
    used_posts = []
    loops = 0
    while loops < max_loops:
        best, gain = None, 0
        for i, s in enumerate(sees):
            if len(s) == 0:
                continue
            gnew = int((~seen[s]).sum())
            if gnew > gain:
                p = cands[i][0]
                if any(abs(p[0] - q[0]) + abs(p[2] - q[2]) < spacing and abs(p[1] - q[1]) < 3 for q in used_posts):
                    continue
                best, gain = i, gnew
        if best is None or gain < min_gain:
            break
        post, niche, slit, d = cands[best]
        cut(g, post, niche, slit, d)
        g.mark(role, *post)
        g.__dict__.setdefault("loop_posts", set()).add(tuple(post))
        used_posts.append(post)
        seen[sees[best]] = True
        loops += 1
    after, _, _, _ = da.flanking(g, GL, stride=1)
    return loops, before, after
