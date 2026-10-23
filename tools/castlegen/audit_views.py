"""Walkpath and interior audit of every plan, with pictures.

For each plan: the build as the export makes it, then a walk from the gate over every floor, every room, every
door and every market lane; and renders to look at it by: the outside from two corners, a cutaway at each floor,
and a top-down plan of each floor with what a soldier can reach painted green and what he can't painted red,
the rooms labelled with what they're for. Writes build/audit/<name>_*.png and build/audit/audit.json.

usage: python3 audit_views.py [--only NAME] [--out DIR]
"""
import argparse
import json
import os
import sys
import time

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
import build_all
import furnish
import render
import rooms
import scale_audit
import walk
from render import color_of

ROOT = build_all.ROOT

FURNITURE = ("bed", "chest", "barrel", "table", "lectern", "bookshelf", "anvil", "furnace", "smoker", "cauldron",
             "loom", "campfire", "brewing", "grindstone", "stonecutter", "composter", "hay", "carpet", "banner",
             "lantern", "torch", "candle", "flower_pot", "armor_stand", "smithing", "fletching", "cartography",
             "chain", "ladder", "trapdoor", "fence", "wall_sign", "sign", "skull", "head", "cake", "pot", "bell")


def plan_rooms(g, meta):
    """Every room after furnishing, with its kind from the furnishing report where one was dressed."""
    rs = furnish.find_rooms(g, meta["GL"], set())
    dressed = meta.get("rooms", [])
    out = []
    for r in rs:
        cx, cz = (r.x0 + r.x1) / 2, (r.z0 + r.z1) / 2
        best, bd = None, 1e9
        for kind, y, x0, z0, w, d, size in dressed:
            if y != r.y or not (x0 - 1 <= cx <= x0 + w and z0 - 1 <= cz <= z0 + d):
                continue
            dist = abs(x0 + w / 2 - cx) + abs(z0 + d / 2 - cz)
            if dist < bd:
                best, bd = kind, dist
        r.kind = best
        out.append(r)
    return out


def room_audit(g, meta, cells):
    """Per room: reachable floor share, kind, furniture, light, doors; and the problems."""
    GL = meta["GL"]
    found = []
    problems = []
    for r in plan_rooms(g, meta):
        reach = sum(1 for (x, z) in r.cells if (x, r.y, z) in cells)
        share = reach / max(1, len(r.cells))
        furn = 0
        scan = set(r.cells)
        for (x, z) in r.cells:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                scan.add((x + dx, z + dz))
        for (x, z) in scan:
            for yy in (r.y, r.y + 1, r.y + 2):
                s = g.get(x, yy, z)
                if s == "minecraft:air":
                    continue
                if any(k in s for k in ("lantern", "torch", "candle", "campfire", "glowstone", "sea_lantern", "ladder", "trapdoor", "sign")):
                    continue
                if any(k in s for k in FURNITURE):
                    furn += 1
        lights = 1 if scale_audit.room_lit(g, r) else 0
        doors = 0
        for (x, z) in r.cells:
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, nz = x + dx, z + dz
                if (nx, nz) in r.cells:
                    continue
                s = g.get(nx, r.y, nz)
                if "door" in s or ("minecraft:air" == s and walk.passable(g.get(nx, r.y + 1, nz)) and walk.supports(g.get(nx, r.y - 1, nz))):
                    doors += 1
        entry = dict(y=r.y, lvl=r.y - GL, x0=r.x0, z0=r.z0, w=r.w(), d=r.d(), size=len(r.cells), kind=r.kind,
                     reach=round(share, 2), furniture=furn, lights=lights, openings=doors)
        found.append(entry)
        tag = f"{r.kind or 'unnamed'} {r.w()}x{r.d()} at ({r.x0},{r.y},{r.z0})"
        if len(r.cells) >= 12:
            if share == 0:
                problems.append(("unreachable room", tag))
            elif share < 0.6:
                problems.append(("half-reachable room", f"{tag}: {share:.0%} of its floor"))
            if doors == 0 and share == 0:
                problems.append(("no way in", tag))
            if len(r.cells) >= 20 and r.kind and furn == 0:
                problems.append(("bare room", tag))
            if len(r.cells) >= 20 and lights == 0:
                problems.append(("dark room", tag))
            if len(r.cells) >= 20 and r.kind is None:
                problems.append(("room with no purpose", tag))
    return found, problems


def door_audit(g, cells):
    """Doors a man can't use: against a wall, hung over a drop, or on a cell nobody can reach."""
    bad = []
    seen = set()
    for x in range(g.X):
        for z in range(g.Z):
            for y in range(1, g.Y - 1):
                s = g.get(x, y, z)
                if "_door" not in s or "half=upper" in s or "trapdoor" in s:
                    continue
                if (x, y, z) in seen:
                    continue
                seen.add((x, y, z))
                sides = []
                axis_x = "facing=east" in s or "facing=west" in s
                for dx, dz in (((1, 0), (-1, 0)) if axis_x else ((0, 1), (0, -1))):
                    nx, nz = x + dx, z + dz
                    ok = walk.passable(g.get(nx, y, nz)) and walk.passable(g.get(nx, y + 1, nz)) and walk.supports(g.get(nx, y - 1, nz))
                    sides.append(ok)
                if not all(sides):
                    bad.append(("door against a wall or a drop", (x, y, z), s.split("[")[0].split(":")[-1]))
                elif not any((x + dx, y, z + dz) in cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (0, 0))):
                    bad.append(("door nobody reaches", (x, y, z), s.split("[")[0].split(":")[-1]))
    return bad


def lane_audit(g, meta, cells):
    """Outdoor ground passages: how many reachable ground cells are one-wide corridors (a lane you can't pass in)."""
    GL = meta["GL"]
    ground = [(x, y, z) for (x, y, z) in cells if GL <= y <= GL + 2 and not any(
        not walk.passable(g.get(x, y + h, z)) for h in range(2, 7))]
    one_wide = 0
    for (x, y, z) in ground:
        ew = ((x + 1, y, z) in cells) + ((x - 1, y, z) in cells)
        ns = ((x, y, z + 1) in cells) + ((x, y, z - 1) in cells)
        if (ew == 2 and ns == 0) or (ns == 2 and ew == 0):
            one_wide += 1
    return len(ground), one_wide


def yard_audit(g, meta, cells):
    """Open ground inside the plan a man can stand on but never reach: sealed wards and yards, as (cells, x, z) each."""
    from scipy import ndimage
    GL = meta["GL"]
    X, Z = g.X, g.Z
    mask = np.zeros((X, Z), dtype=bool)
    for x in range(1, X - 1):
        for z in range(1, Z - 1):
            for y in (GL, GL + 1, GL - 1, GL + 2):
                if y < 1 or (x, y, z) in cells:
                    break
                if walk.passable(g.get(x, y, z)) and walk.passable(g.get(x, y + 1, z)) and walk.supports(g.get(x, y - 1, z)) \
                        and all(walk.passable(g.get(x, y + h, z)) for h in range(2, 12)):
                    mask[x, z] = True
                    break
    lab, n = ndimage.label(mask)
    out = []
    for i in range(1, n + 1):
        pts = np.argwhere(lab == i)
        if len(pts) >= 30:
            # drop the field outside the walls: anything touching the edge of the plan
            if pts[:, 0].min() <= 1 or pts[:, 1].min() <= 1 or pts[:, 0].max() >= X - 2 or pts[:, 1].max() >= Z - 2:
                continue
            out.append((int(len(pts)), int(pts[:, 0].mean()), int(pts[:, 1].mean())))
    out.sort(reverse=True)
    return out


def markers_by_level(g):
    out = {}
    for role, pts in g.markers.items():
        for (x, y, z) in pts:
            out.setdefault(y, []).append((role, x, z))
    return out


# ---------------------------------------------------------------- pictures


def floor_plan(g, y, cells, path, rooms_here, marks, s=5):
    """Top down over y..y+2: blocks coloured, reachable floor green-tinted, unreachable floor red-tinted, rooms labelled."""
    im = Image.new("RGB", (g.X * s, g.Z * s), (24, 24, 28))
    dr = ImageDraw.Draw(im)
    for x in range(g.X):
        for z in range(g.Z):
            top = None
            for yy in (y + 2, y + 1, y):
                st = g.palette[g.a[x, z, yy]]
                if st not in ("minecraft:air", "<void>", "minecraft:cave_air"):
                    top = st
                    break
            if top is None:
                below = g.palette[g.a[x, z, y - 1]]
                if below in ("minecraft:air", "<void>"):
                    continue
                c = color_of(below)
                c = tuple(int(v * 0.55) for v in c)
                if (x, y, z) in cells:
                    c = (c[0] // 2, min(255, c[1] // 2 + 110), c[2] // 2)
                elif walk.supports(below):
                    c = (min(255, c[0] // 2 + 120), c[1] // 2, c[2] // 2)
            else:
                c = color_of(top)
                if walk.passable(top) and (x, y, z) in cells:
                    c = (c[0] // 2, min(255, c[1] // 2 + 90), c[2] // 2)
            dr.rectangle([x * s, z * s, x * s + s - 1, z * s + s - 1], fill=c)
    for r in rooms_here:
        label = (r.kind or "?")[:10]
        cx, cz = (r.x0 + r.x1) / 2 * s, (r.z0 + r.z1) / 2 * s
        dr.text((cx - len(label) * 3, cz - 5), label, fill=(255, 255, 160))
    for role, x, z in marks:
        dr.rectangle([x * s + 1, z * s + 1, x * s + s - 2, z * s + s - 2], outline=(80, 200, 255))
        dr.text((x * s + s, z * s - 4), role[:6], fill=(120, 220, 255))
    im.save(path)


def _ground_at(g, x, z, GL):
    """The standing level at (x, z): over the first floor down from the sky; past the plan's edge, GL."""
    if not (0 <= x < g.X and 0 <= z < g.Z):
        return GL
    for y in range(g.Y - 2, 0, -1):
        if walk.supports(g.get(x, y - 1, z)) and walk.passable(g.get(x, y, z)) and walk.passable(g.get(x, y + 1, z)):
            return y
    return GL


def _walk_level(cells, x, z, GL):
    """The level a man walks at in column (x, z), the one nearest the ground; None if he never stands there."""
    ys = [y for y in range(GL - 6, GL + 40) if (x, y, z) in cells]
    return min(ys, key=lambda y: abs(y - GL)) if ys else None


def eye_views(g, meta, rs, cells, out, name, size=(1120, 630)):
    """Pictures at a man's eye, the real textures: the gate from the road, the way in from inside the gate, the
    biggest room a man can walk into and the biggest upstairs. [(caption, file)]."""
    import mcrender
    GL = meta["GL"]
    sc = mcrender.Scene(g, GL)
    views = []
    gate = g.markers.get("gate", [None])[0]
    if gate:
        gx, gy, gz = gate
        d = min(((gx, (-1, 0)), (g.X - 1 - gx, (1, 0)), (gz, (0, -1)), (g.Z - 1 - gz, (0, 1))))[1]
        ox, oz = d
        # the road outside, far enough to take in the gate's towers
        far = 26 if g.X >= 100 else 18
        cx, cz = gx + ox * far + 0.5, gz + oz * far + 0.5
        cy = _ground_at(g, int(cx), int(cz), GL) + 1.62
        views.append(("The gate from the road", (cx, cy, cz), (gx + 0.5, gy + 6, gz + 0.5), 70))
        # inside: walk in past the gate passage to open sky, then look on in
        k, ix, iz = 3, gx, gz
        while k < 60:
            ix, iz = gx - ox * k, gz - oz * k
            if not (0 <= ix < g.X and 0 <= iz < g.Z):
                break
            y = _walk_level(cells, ix, iz, GL)
            if y is not None and k >= 8 and all(walk.passable(g.get(ix, y + h, iz)) for h in range(2, 14)):
                break
            k += 1
        y = _walk_level(cells, ix, iz, GL) or _ground_at(g, ix, iz, GL)
        views.append(("Inside the gate", (ix + 0.5, y + 1.62, iz + 0.5), (ix - ox * 30 + 0.5, y + 4, iz - oz * 30 + 0.5), 70))
    big = sorted([r for r in rs if len(r.cells) >= 20 and any((x, r.y, z) in cells for (x, z) in r.cells)],
                 key=lambda r: -len(r.cells))
    picked = []
    if big:
        picked.append(("Inside the biggest room", big[0]))
        up = [r for r in big if r.y > GL + 2 and r is not big[0]]
        if up:
            picked.append(("Upstairs", up[0]))
    for cap, r in picked:
        # from the middle of an end wall down the room, standing where a man can: of the four ends, the one that sees
        # furthest before a wall or a pillar
        mx, mz = (r.x0 + r.x1) / 2, (r.z0 + r.z1) / 2
        free = [c for c in r.cells if (c[0], r.y, c[1]) in cells and walk.passable(g.get(c[0], r.y + 1, c[1]))] or list(r.cells)
        cands = [(min(free, key=lambda c: (c[0] - r.x0) * 3 + abs(c[1] - mz)), (r.x1 + 0.5, mz + 0.5)),
                 (min(free, key=lambda c: (r.x1 - c[0]) * 3 + abs(c[1] - mz)), (r.x0 + 0.5, mz + 0.5)),
                 (min(free, key=lambda c: (c[1] - r.z0) * 3 + abs(c[0] - mx)), (mx + 0.5, r.z1 + 0.5)),
                 (min(free, key=lambda c: (r.z1 - c[1]) * 3 + abs(c[0] - mx)), (mx + 0.5, r.z0 + 0.5))]

        def clear(c, t):
            (x0, z0), (tx, tz) = c, t
            d = max(0.5, ((tx - x0 - 0.5) ** 2 + (tz - z0 - 0.5) ** 2) ** 0.5)
            n = 0
            for k in range(1, int(d * 2)):
                px, pz = x0 + 0.5 + (tx - x0 - 0.5) * k / (d * 2), z0 + 0.5 + (tz - z0 - 0.5) * k / (d * 2)
                if not walk.passable(g.get(int(px), r.y + 1, int(pz))):
                    break
                n += 1
            return n
        (x0, z0), (tx, tz) = max(cands, key=lambda ct: clear(*ct))
        kind = getattr(r, "kind", None)
        views.append((f"{cap}: {kind or 'a room'} {r.w()} by {r.d()}", (x0 + 0.5, r.y + 1.62, z0 + 0.5), (tx, r.y + 1.0, tz), 85))
    outp = []
    for i, (cap, pos, look, fov) in enumerate(views):
        p = f"{name}_eye{i}.jpg"
        try:
            tmp = os.path.join(out, p.replace(".jpg", ".png"))
            sc.eye(tmp, pos=pos, look=look, size=size, fov=fov)
            Image.open(tmp).convert("RGB").save(os.path.join(out, p), quality=84)
            os.remove(tmp)
            outp.append((cap, p))
        except Exception as e:                       # a camera inside a wall, a degenerate view: skip it
            print("    eye view skipped:", cap, e)
    return outp, sc


def tex_iso(sc, g, out, p, box, cut=False, rot=0, width=1500):
    """A textured isometric picture of the box, scaled to about `width` across, saved as a JPEG."""
    x0, y0, z0, x1, y1, z1 = box
    s = max(3, min(14, int(width / (0.72 * ((x1 - x0) + (z1 - z0)) + 1))))
    tmp = os.path.join(out, p.replace(".jpg", ".png"))
    sc.iso(tmp, box, s=s, rot=rot, cut=cut)
    Image.open(tmp).convert("RGB").save(os.path.join(out, p), quality=84)
    os.remove(tmp)
    return p


def cutaway(g, path, y_cut, s, rot):
    def cut(a):
        a[:, :, y_cut:] = 1
    render.render_iso(g, path, s=s, rot=rot, cut=cut)


def run(only=None, out=None, pics=True):
    out = out or os.path.join(ROOT, "build", "audit")
    os.makedirs(out, exist_ok=True)
    old = {}
    if (not pics or only) and os.path.exists(os.path.join(out, "audit.json")):
        old = json.load(open(os.path.join(out, "audit.json")))
    results = dict(old) if only else {}
    for fn, name, style, seed_shift in build_all.builds():
        if only and name != only:
            continue
        t = time.time()
        g, meta, kinds, audits = build_all.build_plan(fn, name, style, seed_shift, strict=False)
        GL = meta["GL"]
        gate = g.markers.get("gate", [None])[0]
        cells = walk.reach(g, gate) if gate else set()
        found, problems = room_audit(g, meta, cells)
        doors = door_audit(g, cells)
        ground, one_wide = lane_audit(g, meta, cells)
        yards = yard_audit(g, meta, cells)
        levels = sorted({r["y"] for r in found if r["size"] >= 12})
        marks = markers_by_level(g)
        rs = plan_rooms(g, meta)
        s = 7 if g.X <= 70 else 5 if g.X <= 130 else 4
        fs = max(3, min(9, 1100 // g.X))
        pics_ = {"iso": [], "cut": [], "floor": [], "eye": []}
        if not pics:
            pics_ = old.get(name, {}).get("pics", pics_)
        if pics is True:
            # at a man's eye, with the game's own textures, and the whole plan from a corner
            pics_["eye"], sc = eye_views(g, meta, rs, cells, out, name)
            whole = (0, max(0, GL - 3), 0, g.X, g.Y, g.Z)
            pics_["iso"].append(tex_iso(sc, g, out, f"{name}_iso0.jpg", whole))
            # cutaways, textured: just over the ground floor and over the two busiest floors above it
            busy = sorted({y for y in levels if y > GL + 1}, key=lambda y: -sum(1 for r in rs if r.y == y and len(r.cells) >= 12))[:2]
            for y in [GL] + sorted(busy):
                p = f"{name}_cut{y - GL}.jpg"
                tex_iso(sc, g, out, p, (0, max(0, GL - 3), 0, g.X, y + 3, g.Z), cut=True)
                pics_["cut"].append((y - GL, p))
        if pics == "floors":
            pics_["floor"] = []
        for y in (sorted(set([GL] + levels)) if pics else ()):
            here = [r for r in rs if r.y == y and len(r.cells) >= 12]
            if y != GL and not here:
                continue
            p = f"{name}_floor{y - GL}.png"
            floor_plan(g, y, cells, os.path.join(out, p), here, marks.get(y, []) + marks.get(y + 1, []), s=fs)
            pics_["floor"].append((y - GL, p, len(here)))
        res = dict(name=name, kind=meta.get("kind", "castle"), tier=meta.get("tier"), character=meta["character"],
                   size=[g.X, g.Y, g.Z], kinds=kinds, rooms=found, problems=problems, doors=doors,
                   ground_cells=ground, one_wide_lanes=one_wide, sealed_yards=yards, walk_checked=audits["walk_checked"],
                   walk_bad={k: v[:10] for k, v in audits["walk_bad"].items()},
                   scale={k: len(v) for k, v in audits["scale"].items()},
                   defence=None if audits["defence"] is None else {k: (len(v) if isinstance(v, list) else v) for k, v in audits["defence"].items()},
                   failures=audits["failures"], pics=pics_, seconds=round(time.time() - t, 1))
        results[name] = res
        print(f"  audit: {len(found)} rooms, {len(problems)} room problems, {len(doors)} door problems, "
              f"{one_wide}/{ground} ground cells one-wide; pictures in {time.time() - t:.0f}s")
        with open(os.path.join(out, "audit.json"), "w") as f:
            json.dump(results, f, indent=1)
    return results


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--out")
    ap.add_argument("--no-pics", action="store_true")
    ap.add_argument("--floors", action="store_true")
    a = ap.parse_args()
    run(a.only, a.out, pics="floors" if a.floors else not a.no_pics)
