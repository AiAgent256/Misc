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
import export
import fixup
import parts
import furnish
import rooms
import render
import themes
import walk
import worldgen

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
STRUCT_DIR = os.path.join(ROOT, "src/main/resources/data/stormhold/structures")
LAYOUT_DIR = os.path.join(ROOT, "src/main/resources/data/stormhold/castle_layouts")


LAYOUTS = (castle.great_castle_a, castle2.great_castle_b, castle2.great_castle_c,
           castle.holdfast_a, castle2.holdfast_b, castle.watchtower_a, castle2.watchtower_b, city.great_city)
# A second build of each castle with a different seed, roof style and temper, so two castles of the same
# plan don't read as copies. The city is one of a kind.
VARIANTS = {"great_castle_a": ("hoard", 2), "great_castle_b": ("spire", 3), "great_castle_c": ("low", 1),
            "holdfast_a": ("pyramid", 4), "holdfast_b": ("hoard", 2), "watchtower_a": ("flat", 3), "watchtower_b": ("spire", 1)}


def builds():
    for fn in LAYOUTS:
        yield fn, fn.__name__, "cone", None
        if fn.__name__ in VARIANTS:
            style, seed_shift = VARIANTS[fn.__name__]
            yield fn, fn.__name__ + "2", style, seed_shift


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
        meta["name"] = name
        character = meta.get("character") or rooms.character_for(name, seed_shift or 0)
        meta["character"] = character
        kinds = rooms.furnish(g, meta["GL"], character)
        opened = fixup.clear_blocked_doorways(g, meta["GL"], tuple(meta.get("levels", ())))
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
        if not args.no_export:
            tiles = export.write_tiles(g, os.path.join(STRUCT_DIR, name), name)
            layout = export.write_layout(g, meta, tiles, os.path.join(LAYOUT_DIR, f"{name}.json"))
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
        worldgen.write(ROOT, metas)


if __name__ == "__main__":
    main()
