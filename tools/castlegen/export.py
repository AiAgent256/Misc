"""Export Grid to vanilla structure NBT tiles + a layout JSON the mod reads."""
import gzip
import io
import json
import os
import re

import nbtlib
from nbtlib import Compound, List, Int, String, Long
from nbtlib.tag import IntArray

DATA_VERSION = 3465  # 1.20.1
TILE = 32


def parse_state(s):
    m = re.match(r"([a-z0-9_:\-/.]+)(?:\[(.*)\])?$", s)
    name, props = m.group(1), m.group(2)
    c = Compound({"Name": String(name)})
    if props:
        pr = Compound()
        for kv in props.split(","):
            k, v = kv.split("=")
            pr[k] = String(v)
        c["Properties"] = pr
    return c


def to_tag(v):
    if isinstance(v, dict):
        return Compound({k: to_tag(x) for k, x in v.items()})
    if isinstance(v, str):
        return String(v)
    if isinstance(v, bool):
        return nbtlib.Byte(1 if v else 0)
    if isinstance(v, int):
        return Int(v)
    if isinstance(v, list):
        return nbtlib.List[String]([String(x) for x in v]) if all(isinstance(x, str) for x in v) else nbtlib.List([to_tag(x) for x in v])
    raise TypeError(v)


BLOCK_ENTITY_BLOCKS = ("chest", "barrel", "sign", "lectern", "decorated_pot", "skull", "head", "campfire", "brewing_stand",
                       "chiseled_bookshelf", "beehive", "smoker", "furnace", "hopper", "dispenser", "dropper", "banner",
                       "shulker_box", "jukebox", "bell", "bed", "enchanting_table", "beacon", "spawner")


def has_block_entity(state):
    """A fill over a chest leaves its loot tag behind; only blocks that keep a block entity get nbt written."""
    name = state.split("[")[0].split(":")[-1]
    return any(name == k or name.endswith("_" + k) or name.endswith(k) for k in BLOCK_ENTITY_BLOCKS)


def write_tiles(g, out_dir, name):
    os.makedirs(out_dir, exist_ok=True)
    X, Z, Y = g.a.shape
    tiles = []
    for tx in range(0, X, TILE):
        for tz in range(0, Z, TILE):
            sx, sz = min(TILE, X - tx), min(TILE, Z - tz)
            sub = g.a[tx:tx + sx, tz:tz + sz, :]
            used = sorted(set(int(v) for v in sub.flatten()) - {0})
            if not used:
                continue
            local = {pid: i for i, pid in enumerate(used)}
            palette = List[Compound]([parse_state(g.palette[pid]) for pid in used])
            blocks = []
            nz = (sub != 0).nonzero()
            for x, z, y in zip(*nz):
                pid = int(sub[x, z, y])
                c = Compound({"pos": List[Int]([Int(int(x)), Int(int(y)), Int(int(z))]),
                              "state": Int(local[pid])})
                key = (int(x) + tx, int(y), int(z) + tz)
                if key in g.block_nbt and has_block_entity(g.palette[pid]):
                    c["nbt"] = to_tag(g.block_nbt[key])
                blocks.append(c)
            root = Compound({
                "DataVersion": Int(DATA_VERSION),
                "size": List[Int]([Int(sx), Int(Y), Int(sz)]),
                "palette": palette,
                "blocks": List[Compound](blocks),
                "entities": List[Compound]([]),
            })
            fname = f"tile_{tx // TILE}_{tz // TILE}.nbt"
            nbtlib.File(root).save(os.path.join(out_dir, fname), gzipped=True)
            tiles.append({"file": f"{name}/tile_{tx // TILE}_{tz // TILE}", "offset": [tx, 0, tz],
                          "blocks": len(blocks)})
    return tiles


def clean_posts(g):
    """Keep only posts with headroom and a floor."""
    out = {}
    for role, pts in g.markers.items():
        keep = []
        seen = set()
        for (x, y, z) in pts:
            if (x, y, z) in seen:
                continue
            seen.add((x, y, z))
            if role in ("banner_stand", "war_table", "gate", "rally", "lord"):
                keep.append([x, y, z])
                continue
            here, above = g.get(x, y, z), g.get(x, y + 1, z)
            if here in ("minecraft:air",) and above in ("minecraft:air", "<void>") and g.is_solid(x, y - 1, z):
                keep.append([x, y, z])
            elif "slab" in here and "type=bottom" in here and above in ("minecraft:air", "<void>") \
                    and g.get(x, y + 2, z) in ("minecraft:air", "<void>"):
                keep.append([x, y, z])                 # a bowman up on a crenel's sill
            elif role == "house" and "door" in here:
                keep.append([x, y, z])                 # a house is marked by its door
        out[role] = keep
    return out


def footprint(g, GL):
    """One string per row (z), a '1' for every column the castle stands on: anything but structure void at the
    ground floor or the layer under it. The game clears the land above these columns before placing the castle,
    so a hill the siting allowed doesn't hang in the yard or pour sand into the doorways."""
    a = g.a
    on = (a[:, :, GL] != 0) | (a[:, :, GL - 1] != 0)
    return ["".join("1" if on[x, z] else "0" for x in range(a.shape[0])) for z in range(a.shape[1])]


def write_layout(g, meta, tiles, path):
    X, Z, Y = g.a.shape
    posts = clean_posts(g)
    for role in ("lord", "war_table", "banner_stand"):
        x, y, z = g.markers[role][0]
        here, above = g.get(x, y, z), g.get(x, y + 1, z)
        if here not in ("minecraft:air", "<void>") or above not in ("minecraft:air", "<void>") or not g.is_solid(x, y - 1, z):
            raise SystemExit(f"{meta['name']}: {role} at {(x, y, z)} is not a clear spot on a floor (here={here}, below={g.get(x, y - 1, z)})")
    layout = {
        "name": meta["name"], "tier": meta["tier"], "size": [X, Y, Z], "ground": meta["GL"],
        "tiles": tiles,
        "lord": posts.pop("lord", [[X // 2, meta["GL"], Z // 2]])[0],
        "banner_stand": posts.pop("banner_stand")[0],
        "war_table": posts.pop("war_table", [[X // 2, meta["GL"], Z // 2]])[0],
        "gate": posts.pop("gate")[0],
        "rally": posts.pop("rally", [[X // 2, meta["GL"], Z // 2]])[0],
        "posts": posts,
        "beds": meta.get("beds", 0), "gate_planes": [list(p) for p in getattr(g, "gate_planes", [])],
        "banners": [list(b) for b in g.banners if g.get(*b).endswith("wall_banner" ) or "wall_banner" in g.get(*b)],
        "footprint": footprint(g, meta["GL"]),
    }
    if meta.get("kind") == "landmark":
        layout["kind"] = "landmark"            # a place, not a holding: the game makes no garrison for it
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(layout, f, separators=(",", ":"))
    return layout
