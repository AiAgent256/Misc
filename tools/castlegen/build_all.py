"""Generate all Stormhold castles: NBT tiles, layout JSON, preview renders.

usage: python3 build_all.py [--render-only NAME] [--out RENDER_DIR]
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import castle
import castle2
import city
import city2
import crypt
import gatehouse
import defense_audit
import loops
import manor
import market_town
import export
import fixup
import parts
import furnish
import rooms
import scale
import scale_audit
import render
import themes
import walk
import worldgen
import variety

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
STRUCT_DIR = os.path.join(ROOT, "src/main/resources/data/stormhold/structures")
LAYOUT_DIR = os.path.join(ROOT, "src/main/resources/data/stormhold/castle_layouts")


LAYOUTS = (castle.great_castle_a, castle2.great_castle_b, castle2.great_castle_c,
           castle.holdfast_a, castle2.holdfast_b, castle2.holdfast_c, castle.watchtower_a, castle2.watchtower_b, castle2.watchtower_c, city.great_city, city2.canal_city,
           market_town.town_walled, market_town.town_cross, market_town.town_green,
           variety.temple_ruin, variety.great_castle_d,
           variety.moated(castle2.holdfast_b, "spire"), variety.moated(castle2.holdfast_c, "cone"),
           variety.moated(castle.great_castle_a, "spire"), variety.moated(castle2.great_castle_c, "hoard"),
           variety.lake(castle2.great_castle_b, "spire"), manor.fortified_manor, gatehouse.gatehouse_castle)
# A second build of each castle with a different seed, roof style and temper, so two castles of the same
# plan don't read as copies. The city is one of a kind.
VARIANTS = {"fortified_manor": ("pyramid", 3), "lake_castle": ("cone", 4), "great_castle_d": ("cone", 3), "great_castle_a": ("hoard", 2), "great_castle_b": ("spire", 3), "great_castle_c": ("low", 1),
            "holdfast_a": ("pyramid", 4), "holdfast_b": ("hoard", 2), "holdfast_c": ("spire", 3),
            "gatehouse_castle": ("flat", 3), "watchtower_a": ("flat", 3), "watchtower_b": ("spire", 1), "watchtower_c": ("hoard", 2)}


# What a plan may get away with before the build is refused. Round towers too small for a two-wide spiral
# and the gate halls' spirals are the narrow stairs still allowed; passages are the one-wide slots the
# doorway widener could not open (between a window and a buttress, say).
# The big plans carry a backlog the audit found on its first run (quay stairs in the cities, a few one-wide
# gaps on their walks, tower rooms the lamplighter can't reach); their limits sit at those counts so a new
# cramped spot fails the build while the old ones wait their turn.
SCALE_LIMITS = {"*": {"narrow_stairs": 0, "pinches": 3, "dark_rooms": 0},
                "great_castle_a": {"narrow_stairs": 0, "pinches": 8, "dark_rooms": 0},
                "great_castle_a2": {"narrow_stairs": 0, "pinches": 8, "dark_rooms": 0},
                "great_castle_b": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                "great_castle_b2": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                "great_castle_c": {"narrow_stairs": 1, "pinches": 6, "dark_rooms": 0},
                "great_castle_c2": {"narrow_stairs": 1, "pinches": 6, "dark_rooms": 0},
                "town_walled": {"narrow_stairs": 0, "pinches": 7, "dark_rooms": 0},
                "town_cross": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                "town_green": {"narrow_stairs": 0, "pinches": 5, "dark_rooms": 0},
                # second revisions: the quay houses moved against the wall with their doors on the quay, the
                # terrace trees stood off the keep wall, every tower room lit. What's left is house doors
                # against party walls and a few gaps on the walks.
                "great_city": {"narrow_stairs": 0, "pinches": 17, "dark_rooms": 0},
                "canal_city": {"narrow_stairs": 0, "pinches": 17, "dark_rooms": 0},
                # a ruin is meant to be broken: gaps between fallen drums, shade under the last of the entablature
                "temple_ruin": {"narrow_stairs": 0, "pinches": 10, "dark_rooms": 3},
                "great_castle_d": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                "great_castle_d2": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                # the ring fortress on its island: the same castle as great_castle_b inside
                "lake_castle": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0},
                "lake_castle2": {"narrow_stairs": 0, "pinches": 6, "dark_rooms": 0}}


# What a plan's defences must reach before it's exported (see defense_audit): bowmen on walks a man can pass
# another on, the walls' foot covered by flanking fire, the field before them in bowshot.
DEFENSE_LIMITS = {"*": {"one_wide": 0, "two_wide": 0.10, "foot": 0.35, "field": 0.85},
                  # an open market town has no wall to man: a man or two up its church tower
                  "town": {"one_wide": 0, "two_wide": 1.0, "foot": 0.0, "field": 0.0},
                  # the manor's walls are low: its men see their foot, and the bar is set higher to match
                  "fortified_manor": {"one_wide": 0, "two_wide": 0.10, "foot": 0.50, "field": 0.85},
                  # the walled town's curtain is held to a castle's walks
                  "town_walled": {"one_wide": 0, "two_wide": 0.10, "foot": 0.35, "field": 0.80},
                  # the backlog the audit found on its first run: tower tops crowded by their stair heads and
                  # turret roofs, the keep wall's walk in great castle C. A new narrow walk still fails the build.
                  "great_castle_a": {"one_wide": 0, "two_wide": 0.20, "foot": 0.35, "field": 0.85},
                  "great_castle_a2": {"one_wide": 0, "two_wide": 0.20, "foot": 0.35, "field": 0.85},
                  "great_castle_c": {"one_wide": 0, "two_wide": 0.15, "foot": 0.35, "field": 0.85},
                  "great_castle_c2": {"one_wide": 0, "two_wide": 0.15, "foot": 0.35, "field": 0.85},
                  # the cities' walls run for half a mile: their foot is watched from the towers, not all of it
                  "great_city": {"one_wide": 3, "two_wide": 0.12, "foot": 0.10, "field": 0.80},
                  "canal_city": {"one_wide": 3, "two_wide": 0.12, "foot": 0.10, "field": 0.80},
                  # the gatehouse castle was built to the audit: its towers and bastions flank every wall
                  "gatehouse_castle": {"one_wide": 0, "two_wide": 0.10, "foot": 0.60, "field": 0.85}}


# Plans rebuilt since they first went out into worlds. A rebuilt plan is exported under a new name
# (great_city_r2), so the castles already standing, and the half-generated ones whose chunks are still
# to come, keep the tiles they were started with and the Master Builder repairs them to their own plans.
# New castles use the newest revision. Bump a plan's number whenever its blocks change.
REVISIONS = {"great_city": 2, "canal_city": 2, "town_walled": 3, "town_cross": 3, "town_green": 3}


def export_name(name):
    rev = REVISIONS.get(name, 1)
    return name if rev == 1 else f"{name}_r{rev}"


def builds():
    for fn in LAYOUTS:
        yield fn, fn.__name__, getattr(fn, "cap_style", "cone"), None
        if fn.__name__ in VARIANTS:
            style, seed_shift = VARIANTS[fn.__name__]
            yield fn, fn.__name__ + "2", style, seed_shift


# Plans first built after the defence rebuild: they start at their first revision.
NEW_PLANS = ("temple_ruin", "fortified_manor", "fortified_manor2", "gatehouse_castle", "gatehouse_castle2",
             "hill_town")


# The defence rebuild touched every plan but the temple ruin: crenels with half-block sills a bowman stands up
# on, tower roofs standing on the merlons so the men under them see out, loops cut low in the towers to cover
# the walls' foot, walks a man can pass another on. A revision each, so castles already standing keep theirs.
for _fn, _name, _style, _shift in builds():
    if _name not in NEW_PLANS:                     # new plans start at their first revision
        REVISIONS[_name] = REVISIONS.get(_name, 1) + 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=None)
    ap.add_argument("--renders", default=os.path.join(ROOT, "build/renders"))
    ap.add_argument("--no-export", action="store_true")
    ap.add_argument("--no-render", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.renders, exist_ok=True)
    themes.write_json(os.path.join(ROOT, "src/main/resources/data/stormhold/castle_themes"))
    metas = {}
    for fn, name, style, seed_shift in builds():
        if args.only and name != args.only:
            continue
        t = time.time()
        parts.CAP_STYLE = style
        g, meta = fn() if seed_shift is None else fn(seed=fn.__defaults__[0] + seed_shift)
        parts.CAP_STYLE = "cone"
        at_embrasures = fixup.embrasures(g)
        meta["name"] = name
        character = meta.get("character") or rooms.character_for(name, seed_shift or 0)
        meta["character"] = character
        landmark = meta.get("kind") == "landmark"
        # a landmark is built as it stands: no widening the gaps between its columns, nobody's furniture in it
        widened = 0 if landmark else scale.widen_doorways(g, meta["GL"])
        kinds = {"beds": 0, "men": 0} if landmark else rooms.furnish(g, meta["GL"], character, tier=meta.get("tier", 2))
        kinds["doorways_widened"] = widened
        meta["beds"] = kinds["beds"]
        if meta.get("tier", 2) >= 2 and meta.get("kind") != "town":
            # a crypt under the keep: the city's keep stands on its top terrace
            keep_gl = meta["levels"][-1] if meta.get("levels") else meta["GL"]
            cr = crypt.dig(g, meta["GL"], character, tier=meta.get("tier", 2), keep_gl=keep_gl)
            if cr is None:
                raise SystemExit(f"{name}: no room for a crypt under the keep")
            kinds.update(cr)
        g.markers.pop("dwelling", None)              # homes not put up for sale are just homes
        kinds["lit"] = 0 if landmark else fixup.light_rooms(g, meta["GL"], rev=REVISIONS.get(name, 1))
        opened = 0 if landmark else fixup.clear_blocked_doorways(g, meta["GL"], tuple(meta.get("levels", ())))
        # loops low in the towers where the walk can't see the walls' foot: after the furniture, so no slit
        # opens behind a bed, and after the doorways, so a slit's sill isn't taken for a blocked door
        kinds["loops"] = 0 if landmark else loops.flank(g, meta["GL"])[0]
        dropped = fixup.drop_buried_markers(g)
        bad, checked = walk.audit(g, meta)
        unreachable = sum(len(v) for v in bad.values())
        print(f"{name} ({character}): built in {time.time() - t:.1f}s, palette {len(g.palette)}, rooms " +
              ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())))
        print(f"  walkability: {checked} posts checked, {unreachable} unreachable, {opened} doorways opened, {dropped} buried posts dropped")
        if unreachable:
            for k, v in bad.items():
                print(f"    {k}: {v[:6]}")
            raise SystemExit(f"{name}: {unreachable} posts cannot be walked to from the gate")
        sc = scale_audit.audit(g, meta)
        print(f"  scale: {scale_audit.summary(sc)}")
        for k, v in sc.items():
            if v:
                print(f"    {k}: {sorted(v)[:5]}")
        # a moated plan holds to its castle's limits
        limit = SCALE_LIMITS.get(name) or SCALE_LIMITS.get(name.replace("_moat", ""), SCALE_LIMITS["*"])
        over = [k for k in ("narrow_stairs", "pinches", "dark_rooms") if len(sc[k]) > limit[k]]
        if over:
            raise SystemExit(f"{name}: cramped: " + ", ".join(f"{k} {len(sc[k])} > {limit[k]}" for k in over))
        if not landmark:
            da = defense_audit.audit(g, meta)
            print(f"  defence: {defense_audit.summary(da)}")
            lim = DEFENSE_LIMITS.get(name) or DEFENSE_LIMITS.get(name.replace("_moat", "")) or DEFENSE_LIMITS.get(name.rstrip("2"))
            lim = lim or DEFENSE_LIMITS["town" if meta.get("kind") == "town" else "*"]
            walks = max(1, da["posts"])
            bad = []
            if len(da["one_wide"]) > lim["one_wide"]:
                bad.append(f"{len(da['one_wide'])} bowmen on one-wide walks, e.g. {da['one_wide'][:3]}")
            if len(da["two_wide"]) > lim["two_wide"] * walks:
                bad.append(f"{len(da['two_wide'])} of {walks} bowmen on two-wide walks, e.g. {da['two_wide'][:3]}")
            if da["cover"] < lim["foot"]:
                bad.append(f"the walls' foot is {da['cover'] * 100:.0f}% covered (< {lim['foot'] * 100:.0f}%), dead e.g. {da['dead'][:3]}")
            if da["field"] < lim["field"]:
                bad.append(f"the field is {da['field'] * 100:.0f}% covered (< {lim['field'] * 100:.0f}%)")
            if bad:
                raise SystemExit(f"{name}: weak defences: " + "; ".join(bad))
        if kinds["beds"] < kinds["men"] and meta.get("kind") != "landmark":
            raise SystemExit(f"{name}: {kinds['beds']} beds for {kinds['men']} men")
        if not args.no_export:
            out = export_name(name)
            meta["name"] = out
            tiles = export.write_tiles(g, os.path.join(STRUCT_DIR, out), out)
            layout = export.write_layout(g, meta, tiles, os.path.join(LAYOUT_DIR, f"{out}.json"))
            metas[name] = layout
            print(f"  tiles {len(tiles)}, blocks {sum(t['blocks'] for t in tiles)}, posts " +
                  ", ".join(f"{k}={len(v)}" for k, v in layout["posts"].items()) +
                  f", banners {len(layout['banners'])}")
        if not args.no_render:
            s = 4 if g.X > 64 else 7
            for rot in range(4):
                render.render_iso(g, os.path.join(args.renders, f"{name}_iso{rot}.png"), s=s, rot=rot)
            render.render_plan(g, os.path.join(args.renders, f"{name}_plan.png"))
            print(f"  renders done in {time.time() - t:.1f}s")

    if not args.only and not args.no_export:
        worldgen.write(ROOT, metas, export_name)


if __name__ == "__main__":
    main()
