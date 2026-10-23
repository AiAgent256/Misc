"""Render a Grid the way the game draws it: each block from its real blockstate and model, with the real textures,
in perspective from a man's eye or in a textured isometric close-up.

Needs the game's assets unpacked under MC_ASSETS (default ~/mcassets/assets/minecraft: blockstates, models/block,
textures/block, taken from the client jar), and the mod's own under src/main/resources/assets/stormhold.

    from mcrender import Scene
    sc = Scene(g)
    sc.eye(path, pos=(x, y, z), look=(x, y, z))       # street level, 70 degree field like the game's
    sc.iso(path, box=(x0, y0, z0, x1, y1, z1), s=24)   # a textured close-up from above a corner
"""
import json
import math
import os
from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MC = os.environ.get("MC_ASSETS", os.path.expanduser("~/mcassets/assets/minecraft"))
if not os.path.isdir(MC):
    MC = "/home/claude/mcassets/assets/minecraft"
MOD = os.path.join(ROOT, "src/main/resources/assets/stormhold")

AIR = ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "<void>")
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
GRASS, FOLIAGE, WATER = (124, 189, 107), (89, 174, 48), (63, 118, 228)
TINTS = {"grass_block": GRASS, "grass": GRASS, "tall_grass": GRASS, "fern": GRASS, "large_fern": GRASS, "vine": FOLIAGE,
         "lily_pad": (32, 128, 48), "oak_leaves": FOLIAGE, "jungle_leaves": FOLIAGE, "acacia_leaves": FOLIAGE,
         "dark_oak_leaves": FOLIAGE, "mangrove_leaves": (146, 199, 71), "birch_leaves": (128, 167, 85),
         "spruce_leaves": (97, 153, 97), "water": WATER, "water_cauldron": WATER, "sugar_cane": GRASS,
         "attached_melon_stem": (224, 199, 28), "melon_stem": (224, 199, 28), "pumpkin_stem": (224, 199, 28)}


def split_state(state):
    name, _, rest = state.partition("[")
    props = {}
    if rest:
        for kv in rest.rstrip("]").split(","):
            if "=" in kv:
                k, v = kv.split("=", 1)
                props[k.strip()] = v.strip()
    if ":" not in name:
        name = "minecraft:" + name
    return name, props


def _asset(ns, kind, path, ext):
    base = MC if ns == "minecraft" else MOD if ns == "stormhold" else None
    if base is None:
        return None
    f = os.path.join(base, kind, path + ext)
    return f if os.path.exists(f) else None


@lru_cache(maxsize=None)
def _json(ns, kind, path):
    f = _asset(ns, kind, path, ".json")
    if f is None:
        return None
    with open(f) as fh:
        return json.load(fh)


def _ref(s, default_ns="minecraft"):
    ns, _, p = s.partition(":") if ":" in s else (default_ns, "", s)
    return ns, p


@lru_cache(maxsize=None)
def texture(ref):
    """A texture as a 16x16 (first frame) RGBA image, or None."""
    ns, p = _ref(ref)
    if not p.startswith("block/") and not p.startswith("item/") and not p.startswith("entity/"):
        p = "block/" + p
    f = _asset(ns, "textures", p, ".png")
    if f is None:
        return None
    im = Image.open(f).convert("RGBA")
    w, h = im.size
    if h > w:
        im = im.crop((0, 0, w, w))
    if im.size != (16, 16):
        im = im.resize((16, 16), Image.NEAREST)
    return im


@lru_cache(maxsize=None)
def model(ref):
    """A model resolved through its parents: (textures dict, elements list)."""
    ns, p = _ref(ref)
    data = _json(ns, "models", p)
    if data is None:
        return {}, []
    tex, elems = {}, None
    if "parent" in data:
        ptex, pel = model(data["parent"] if ":" in data["parent"] else f"{ns}:{data['parent']}")
        tex.update(ptex)
        elems = pel
    tex.update(data.get("textures", {}))
    if "elements" in data:
        elems = data["elements"]
    return tex, elems or []


def _resolve_tex(tex, name, depth=0):
    while name.startswith("#") and depth < 10:
        name = tex.get(name[1:], "")
        depth += 1
    return name or None


def _match(when, props):
    if "OR" in when:
        return any(_match(w, props) for w in when["OR"])
    if "AND" in when:
        return all(_match(w, props) for w in when["AND"])
    for k, v in when.items():
        if props.get(k, "false" if v in ("true", "false") else None) not in str(v).split("|"):
            return False
    return True


def applies(name, props):
    """The models the blockstate file applies for these properties: [(model ref, x, y)]."""
    ns, p = _ref(name)
    bs = _json(ns, "blockstates", p)
    if bs is None:
        return []
    out = []
    if "variants" in bs:
        best, best_n = None, -1
        for key, v in bs["variants"].items():
            conds = dict(kv.split("=", 1) for kv in key.split(",") if "=" in kv)
            if all(props.get(k) == val for k, val in conds.items()) and len(conds) > best_n:
                best, best_n = v, len(conds)
        if best is None:
            best = next(iter(bs["variants"].values()))
        if isinstance(best, list):
            best = best[0]
        out.append((best["model"], best.get("x", 0), best.get("y", 0)))
    else:
        for part in bs.get("multipart", []):
            if "when" in part and not _match(part["when"], props):
                continue
            ap = part["apply"]
            if isinstance(ap, list):
                ap = ap[0]
            out.append((ap["model"], ap.get("x", 0), ap.get("y", 0)))
    return out


# --------------------------------------------------------------- geometry

DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def _face_corners(f, t, face):
    x1, y1, z1 = f
    x2, y2, z2 = t
    return {
        "north": [(x2, y2, z1), (x1, y2, z1), (x1, y1, z1), (x2, y1, z1)],
        "south": [(x1, y2, z2), (x2, y2, z2), (x2, y1, z2), (x1, y1, z2)],
        "east": [(x2, y2, z2), (x2, y2, z1), (x2, y1, z1), (x2, y1, z2)],
        "west": [(x1, y2, z1), (x1, y2, z2), (x1, y1, z2), (x1, y1, z1)],
        "up": [(x1, y2, z1), (x2, y2, z1), (x2, y2, z2), (x1, y2, z2)],
        "down": [(x1, y1, z2), (x2, y1, z2), (x2, y1, z1), (x1, y1, z1)],
    }[face]


def _default_uv(f, t, face):
    x1, y1, z1 = f
    x2, y2, z2 = t
    return {
        "north": (16 - x2, 16 - y2, 16 - x1, 16 - y1), "south": (x1, 16 - y2, x2, 16 - y1),
        "east": (16 - z2, 16 - y2, 16 - z1, 16 - y1), "west": (z1, 16 - y2, z2, 16 - y1),
        "up": (x1, z1, x2, z2), "down": (x1, 16 - z2, x2, 16 - z1),
    }[face]


def _rot_elem(p, rot):
    if not rot:
        return p
    o = [c / 16 for c in rot.get("origin", (8, 8, 8))]
    a = math.radians(rot.get("angle", 0))
    ax = rot.get("axis", "y")
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    c, s = math.cos(a), math.sin(a)
    if ax == "y":
        x, z = x * c + z * s, -x * s + z * c
    elif ax == "x":
        y, z = y * c - z * s, y * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def _rot_block(p, rx, ry):
    x, y, z = p[0] - 0.5, p[1] - 0.5, p[2] - 0.5
    for _ in range((rx // 90) % 4):
        y, z = z, -y
    for _ in range((ry // 90) % 4):
        x, z = -z, x
    return (x + 0.5, y + 0.5, z + 0.5)


@lru_cache(maxsize=None)
def quads(state):
    """The textured quads of a block state in block space: [(corners 4x3, texture ref, uv, uv rotation, tint, normal, cull)].
    cull is the step to the neighbour that hides the quad when it is opaque (the quad lies on that side of the block)."""
    name, props = split_state(state)
    short = name.split(":")[-1]
    raw = _special(short, props)
    if raw is None:
        raw = []
        for mref, rx, ry in applies(name, props):
            tex, elems = model(mref if ":" in mref else "minecraft:" + mref)
            for el in elems:
                f = [c / 16 for c in el["from"]]
                t = [c / 16 for c in el["to"]]
                for face, fd in el.get("faces", {}).items():
                    tref = _resolve_tex(tex, fd.get("texture", ""))
                    if not tref:
                        continue
                    uv = tuple(fd.get("uv") or _default_uv([c * 16 for c in f], [c * 16 for c in t], face))
                    cs = [_rot_block(_rot_elem(p, el.get("rotation")), rx, ry) for p in _face_corners(f, t, face)]
                    tint = TINTS.get(short) if "tintindex" in fd else None
                    raw.append((tuple(cs), tref, uv, fd.get("rotation", 0), tint))
    out = []
    for cs, tref, uv, rot, tint in raw:
        a = np.array(cs, dtype=float)
        n = np.cross(a[3] - a[0], a[1] - a[0])     # corners run TL, TR, BR, BL seen from outside
        ln = np.linalg.norm(n)
        if ln < 1e-9:
            continue
        n = n / ln
        cull = None
        for axis in range(3):
            vals = a[:, axis]
            if vals.max() - vals.min() < 1e-6:
                d = [0, 0, 0]
                if vals[0] >= 1 - 1e-6:
                    d[axis] = 1
                elif vals[0] <= 1e-6:
                    d[axis] = -1
                if any(d):
                    cull = tuple(d)
        out.append((a, tref, uv, rot, tint, n, cull))
    return tuple(out)


def _box(f, t, tex, faces=("north", "south", "east", "west", "up", "down"), tint=None, top=None):
    out = []
    for face in faces:
        uv = _default_uv([c * 16 for c in f], [c * 16 for c in t], face)
        out.append((tuple(_face_corners(f, t, face)), top if (face == "up" and top) else tex, uv, 0, tint))
    return out


WOODS = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped")


def _wood_of(short):
    for w in sorted(WOODS, key=len, reverse=True):
        if short.startswith(w):
            return w
    return "oak"


def _special(short, props):
    """Blocks the game draws with entity renderers or liquids: a fair box of their texture."""
    if short in ("water", "bubble_column"):
        lvl = int(props.get("level", "0"))
        h = 14 / 16 if lvl == 0 else max(2, 14 - 2 * lvl) / 16
        return _box((0, 0, 0), (1, h, 1), "minecraft:block/water_still", tint=WATER)
    if short == "lava":
        return _box((0, 0, 0), (1, 14 / 16, 1), "minecraft:block/lava_still")
    if short in ("chest", "trapped_chest", "ender_chest"):
        tex = "minecraft:block/oak_planks" if short != "ender_chest" else "minecraft:block/obsidian"
        return _box((1 / 16, 0, 1 / 16), (15 / 16, 14 / 16, 15 / 16), tex)
    if short.endswith("_bed"):
        col = short[:-4]
        if props.get("part") == "head":
            return _box((0, 3 / 16, 0), (1, 9 / 16, 1), f"minecraft:block/{col}_wool", top="minecraft:block/white_wool")
        return _box((0, 3 / 16, 0), (1, 9 / 16, 1), f"minecraft:block/{col}_wool")
    if short.endswith("_wall_sign") or short.endswith("_wall_hanging_sign"):
        w = _wood_of(short)
        facing = props.get("facing", "north")
        box = {"north": ((0, 4 / 16, 14 / 16), (1, 12 / 16, 1)), "south": ((0, 4 / 16, 0), (1, 12 / 16, 2 / 16)),
               "east": ((0, 4 / 16, 0), (2 / 16, 12 / 16, 1)), "west": ((14 / 16, 4 / 16, 0), (1, 12 / 16, 1))}[facing]
        return _box(box[0], box[1], f"minecraft:block/{w}_planks")
    if short.endswith("_sign") or short.endswith("_hanging_sign"):
        w = _wood_of(short)
        hang = "hanging" in short
        return _box((1 / 16, 2 / 16 if hang else 8 / 16, 7 / 16), (15 / 16, 12 / 16 if hang else 1, 9 / 16), f"minecraft:block/{w}_planks") + \
            ([] if hang else _box((7 / 16, 0, 7 / 16), (9 / 16, 8 / 16, 9 / 16), f"minecraft:block/{w}_log"))
    if short.endswith("wall_banner"):
        col = short[:-len("_wall_banner")]
        facing = props.get("facing", "north")
        box = {"north": ((1 / 16, -14 / 16, 14 / 16), (15 / 16, 14 / 16, 15 / 16)), "south": ((1 / 16, -14 / 16, 1 / 16), (15 / 16, 14 / 16, 2 / 16)),
               "east": ((1 / 16, -14 / 16, 1 / 16), (2 / 16, 14 / 16, 15 / 16)), "west": ((14 / 16, -14 / 16, 1 / 16), (15 / 16, 14 / 16, 15 / 16))}[facing]
        return _box(box[0], box[1], f"minecraft:block/{col}_wool")
    if short.endswith("_banner"):
        col = short[:-len("_banner")]
        return _box((1 / 16, 0, 7 / 16), (15 / 16, 2, 8 / 16), f"minecraft:block/{col}_wool")
    if short.endswith("_skull") or short.endswith("_head"):
        return _box((4 / 16, 0, 4 / 16), (12 / 16, 8 / 16, 12 / 16), "minecraft:block/bone_block_side")
    if short in ("barrier", "light", "structure_void", "moving_piston"):
        return []
    return None


# --------------------------------------------------------------- fences, walls, panes: their joins

def _joins(g, x, y, z, short):
    """The props a connecting block would have from its neighbours (the grid stores many without them)."""
    out = {}
    kind = "fence" if short.endswith("_fence") else "wall" if short.endswith("_wall") else "pane" if (short.endswith("_pane") or short == "iron_bars") else None
    if kind is None:
        return out
    for d, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("east", (1, 0)), ("west", (-1, 0))):
        n = g.get(x + dx, y, z + dz) if 0 <= x + dx < g.X and 0 <= z + dz < g.Z else "minecraft:air"
        ns = n.split("[")[0].split(":")[-1]
        joined = False
        if kind == "fence":
            joined = ns.endswith("_fence") and ("nether_brick" in ns) == ("nether_brick" in short) or ns.endswith("_fence_gate") or _solid_name(ns)
        elif kind == "wall":
            joined = ns.endswith("_wall") or ns.endswith("_fence_gate") or _solid_name(ns) or ns.endswith("_pane") or ns == "iron_bars"
        else:
            joined = ns.endswith("_pane") or ns == "iron_bars" or ns.endswith("_wall") or _solid_name(ns)
        if kind == "wall":
            out[d] = "low" if joined else "none"
        else:
            out[d] = "true" if joined else "false"
    if kind == "wall":
        above = g.get(x, y + 1, z) if y + 1 < g.Y else "minecraft:air"
        straight = (out["north"] != "none" and out["south"] != "none" and out["east"] == "none" and out["west"] == "none") or \
                   (out["east"] != "none" and out["west"] != "none" and out["north"] == "none" and out["south"] == "none")
        out["up"] = "false" if straight and above in AIR else "true"
    return out


NOT_FULL = ("slab", "stairs", "fence", "wall", "pane", "bars", "door", "trapdoor", "torch", "lantern", "sign", "banner", "carpet",
            "button", "plate", "rail", "ladder", "vine", "flower", "sapling", "grass", "fern", "leaves", "glass", "chain", "bed",
            "chest", "campfire", "cauldron", "anvil", "lectern", "grindstone", "hopper", "candle", "pot", "head", "skull", "cake",
            "scaffolding", "bell", "composter", "brewing", "stonecutter", "path", "farmland", "snow", "water", "lava", "air",
            "cobweb", "crop", "wheat", "carrots", "potatoes", "beetroots", "rod", "pickle", "kelp", "seagrass", "lily", "dripstone",
            "pointed", "bamboo", "cactus", "sugar_cane", "button", "lever", "tripwire", "redstone", "repeater", "comparator",
            "piston_head", "end_rod", "lightning", "amethyst_cluster", "bud", "coral", "mushroom", "portcullis", "banner_stand",
            "war_table", "muster_post", "notice_board", "frame", "daylight", "table", "conduit", "egg", "heavy_core", "dragon")


@lru_cache(maxsize=None)
def _solid_name(short):
    return not any(k in short for k in NOT_FULL) or short in ("grass_block", "glowstone", "sea_lantern", "redstone_block", "redstone_lamp",
                                                            "jack_o_lantern", "smooth_stone_slab_double")


# --------------------------------------------------------------- the scene

class Scene:
    """A plan ready to draw. GL is the plan's ground: beyond the plan's edges the ground runs on as grass at GL."""

    def __init__(self, g, GL=None):
        self.g, self.GL = g, GL
        pal = g.palette
        self.opaque_id = np.array([s not in AIR and _solid_name(split_state(s)[0].split(":")[-1]) for s in pal], dtype=bool)
        self.air_id = np.array([s in AIR for s in pal], dtype=bool)
        self.joins_id = np.zeros(len(pal), dtype=bool)
        for i, s in enumerate(pal):
            name, props = split_state(s)
            sh = name.split(":")[-1]
            if (sh.endswith("_fence") or sh.endswith("_wall") or sh.endswith("_pane") or sh == "iron_bars") and "north" not in props:
                self.joins_id[i] = True

    # ----------------------------------------------------------- gathering the faces

    def collect(self, box, cut=False):
        """Every face in the box that can be seen: world corners (N,4,3) and an index into self.desc for each.
        With cut, the box's top is open: what lies above it is taken away."""
        g = self.g
        x0, y0, z0, x1, y1, z1 = [int(v) for v in box]
        x0, z0, y0 = max(0, x0), max(0, z0), max(0, y0)
        x1, z1, y1 = min(g.X, x1), min(g.Z, z1), min(g.Y, y1)
        sub = g.a[x0:x1, z0:z1, y0:y1]
        # the opaque blocks around the box, one block wider every way; past the plan, the ground below GL
        lo = np.zeros((x1 - x0 + 2, z1 - z0 + 2, y1 - y0 + 2), dtype=bool)
        if self.GL is not None:
            ys = np.arange(y0 - 1, y1 + 1)
            lo[:, :, :] = (ys < self.GL)[None, None, :]
        ax0, az0, ay0 = max(0, x0 - 1), max(0, z0 - 1), max(0, y0 - 1)
        ax1, az1, ay1 = min(g.X, x1 + 1), min(g.Z, z1 + 1), min(g.Y, y1 + 1)
        lo[ax0 - x0 + 1:ax1 - x0 + 1, az0 - z0 + 1:az1 - z0 + 1, ay0 - y0 + 1:ay1 - y0 + 1] = \
            self.opaque_id[g.a[ax0:ax1, az0:az1, ay0:ay1]]
        if ay1 < y1 + 1:                     # above the plan's top: open sky
            lo[:, :, ay1 - y0 + 1:] = False
        if cut:
            lo[:, :, -1] = False
        groups = {}
        for pid in np.unique(sub):
            if self.air_id[pid]:
                continue
            cells = np.argwhere(sub == pid)          # local (x, z, y)
            st = g.palette[pid]
            if self.joins_id[pid]:
                for c in cells:
                    x, z, y = int(c[0]) + x0, int(c[1]) + z0, int(c[2]) + y0
                    name, props = split_state(st)
                    props.update(_joins(g, x, y, z, name.split(":")[-1]))
                    s2 = name + "[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]"
                    groups.setdefault(s2, []).append(c)
            else:
                groups.setdefault(st, []).append(cells)
        Cs, Ds = [], []
        if not hasattr(self, "desc"):
            self.desc, self._desc_ix = [], {}
        for st, parts_ in groups.items():
            cells = np.vstack([np.atleast_2d(p) for p in parts_])
            # a block buried on every side by opaque blocks shows nothing
            lx, lz, ly = cells[:, 0] + 1, cells[:, 1] + 1, cells[:, 2] + 1
            for qi, (a, tref, uv, rot, tint, n, cull) in enumerate(quads(st)):
                if cull is not None:
                    dx, dy, dz = cull
                    vis = ~lo[lx + dx, lz + dz, ly + dy]
                    cs = cells[vis]
                else:
                    cs = cells
                if not len(cs):
                    continue
                key = (st, qi)
                di = self._desc_ix.get(key)
                if di is None:
                    di = len(self.desc)
                    self._desc_ix[key] = di
                    self.desc.append((tref, uv, rot, tint, n))
                off = np.stack([cs[:, 0] + x0, cs[:, 2] + y0, cs[:, 1] + z0], axis=1).astype(float)
                Cs.append(a[None, :, :] + off[:, None, :])
                Ds.append(np.full(len(cs), di, dtype=np.int32))
        if not Cs:
            return np.zeros((0, 4, 3)), np.zeros(0, dtype=np.int32)
        return np.concatenate(Cs), np.concatenate(Ds)

    def _normals(self, D):
        return np.array([d[4] for d in self.desc])[D] if len(D) else np.zeros((0, 3))

    # ----------------------------------------------------------- textures

    def _texture(self, di, shade, fogq):
        key = (di, shade, fogq)
        if not hasattr(self, '_tex'):
            self._tex = {}
        t = self._tex.get(key)
        if t is not None:
            return t
        tref, uv, rot, tint, n = self.desc[di]
        base = texture(tref)
        if base is None:
            base = Image.new("RGBA", (16, 16), (200, 0, 200, 255))
        u1, v1, u2, v2 = uv
        cx0, cx1 = int(math.floor(min(u1, u2))), int(math.ceil(max(u1, u2)))
        cy0, cy1 = int(math.floor(min(v1, v2))), int(math.ceil(max(v1, v2)))
        cx1, cy1 = max(cx1, cx0 + 1), max(cy1, cy0 + 1)
        im = base.crop((max(0, cx0), max(0, cy0), min(16, cx1), min(16, cy1)))
        if u1 > u2:
            im = im.transpose(Image.FLIP_LEFT_RIGHT)
        if v1 > v2:
            im = im.transpose(Image.FLIP_TOP_BOTTOM)
        arr = np.asarray(im).astype(np.float32)
        if tint:
            arr[..., :3] *= np.array(tint, dtype=np.float32) / 255.0
        arr[..., :3] *= shade
        if fogq:
            f = fogq / 20.0
            arr[..., :3] = arr[..., :3] * (1 - f) + np.array(self.fog, dtype=np.float32) * f
        im = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGBA")
        a = arr[..., 3]
        mean = tuple(int(v) for v in (arr[..., :3] * (a[..., None] / 255)).sum((0, 1)) / max(1, (a / 255).sum())) + \
            (255 if a.mean() > 128 else 0,)
        t = (im, rot, mean)
        self._tex[key] = t
        return t

    fog = (200, 218, 238)

    # ----------------------------------------------------------- drawing

    def _paint(self, im, P, order, D, shade, fogq, ss):
        """Paint the faces far to near. P: screen corners (N,4,2) at the output scale."""
        W, H = im.size
        dr = ImageDraw.Draw(im)
        P = P * ss
        mn, mx = P.min(axis=1), P.max(axis=1)
        x0s, y0s = np.floor(mn[:, 0]).astype(int), np.floor(mn[:, 1]).astype(int)
        x1s, y1s = np.ceil(mx[:, 0]).astype(int), np.ceil(mx[:, 1]).astype(int)
        for i in order:
            bx0, by0, bx1, by1 = x0s[i], y0s[i], x1s[i], y1s[i]
            if bx1 < 0 or by1 < 0 or bx0 >= W or by0 >= H:
                continue
            bw, bh = bx1 - bx0, by1 - by0
            if bw > 3 * W or bh > 3 * H:
                continue
            tex, rot, mean = self._texture(int(D[i]), float(shade[i]), int(fogq[i]))
            pts = P[i]
            if bw * bh <= 6:
                if mean[3]:
                    dr.polygon([tuple(p) for p in pts], fill=mean)
                continue
            cw, ch = tex.size
            src = [(0, 0), (cw, 0), (cw, ch), (0, ch)]
            r = (rot // 90) % 4
            src = src[r:] + src[:r]
            dst = pts - (bx0, by0)
            A = np.zeros((8, 8))
            b = np.zeros(8)
            for k in range(4):
                x, y = dst[k]
                u, v = src[k]
                A[2 * k] = (x, y, 1, 0, 0, 0, -u * x, -u * y)
                A[2 * k + 1] = (0, 0, 0, x, y, 1, -v * x, -v * y)
                b[2 * k], b[2 * k + 1] = u, v
            try:
                co = np.linalg.solve(A, b)
            except np.linalg.LinAlgError:
                continue
            w = tex.transform((max(1, bw), max(1, bh)), Image.PERSPECTIVE, tuple(co), Image.NEAREST)
            im.paste(w, (int(bx0), int(by0)), w)

    @staticmethod
    def _shade(Nr):
        s = np.where(np.abs(Nr[:, 2]) > np.abs(Nr[:, 0]), 0.8, 0.6)
        s = np.where(Nr[:, 1] > 0.5, 1.0, s)
        return np.where(Nr[:, 1] < -0.5, 0.5, s)

    def eye(self, path, pos, look, size=(1280, 720), fov=70, reach=150, ss=2, box=None):
        """What a man standing at pos sees looking toward look, with the game's field of view."""
        C = np.array(pos, dtype=float)
        f = np.array(look, dtype=float) - C
        f /= np.linalg.norm(f)
        r = np.cross(f, (0, 1.0, 0))
        r /= np.linalg.norm(r)
        u = np.cross(r, f)
        F = (size[1] / 2) / math.tan(math.radians(fov) / 2)
        near = 0.08
        if box is None:
            box = (C[0] - reach, 0, C[2] - reach, C[0] + reach, self.g.Y, C[2] + reach)
        Wc, D = self.collect(box)
        Nr = self._normals(D)
        cen = Wc.mean(axis=1)
        keep = np.einsum("ij,ij->i", Nr, C - cen) > 1e-6
        Wc, D, Nr, cen = Wc[keep], D[keep], Nr[keep], cen[keep]
        d = Wc - C
        zc = d @ f
        front = (zc > near).all(axis=1)
        straddle = (zc > near).any(axis=1) & ~front
        # faces cut by the near plane: split into quarters until the parts lie wholly in front
        extra_W, extra_D = [], []
        todo = [((Wc[i], ((0.0, 0.0), (1.0, 1.0))), int(D[i]), 0) for i in np.where(straddle)[0]]
        while todo:
            (q, fr), di, depth_ = todo.pop()
            z = (q - C) @ f
            if (z > near).all():
                extra_W.append((q, fr))
                extra_D.append(di)
                continue
            if not (z > near).any() or depth_ >= 4:
                continue
            for sq in _quarter((q, fr)):
                todo.append((sq, di, depth_ + 1))
        sub_uv = {}
        Wc, D = Wc[front], D[front]
        if extra_W:
            # quarters keep their texture: give each its own slice of the face's texture by drawing it as its own face
            ew, ed = self._sub_faces(extra_W, extra_D)
            Wc = np.concatenate([Wc, ew])
            D = np.concatenate([D, ed])
        d = Wc - C
        zc = d @ f
        P = np.stack([size[0] / 2 + (d @ r) / zc * F, size[1] / 2 - (d @ u) / zc * F], axis=2)
        on = ~((P[:, :, 0] < 0).all(1) | (P[:, :, 0] > size[0]).all(1) | (P[:, :, 1] < 0).all(1) | (P[:, :, 1] > size[1]).all(1))
        Wc, D, P = Wc[on], D[on], P[on]
        dist = np.linalg.norm(Wc.mean(axis=1) - C, axis=1)
        shade = self._shade(self._normals(D))
        fogq = np.clip(np.round((dist - 48) / 150 * 20), 0, 16).astype(int)
        order = np.argsort(-dist, kind="stable")
        W, H = size[0] * ss, size[1] * ss
        im = self._sky_and_ground(C, f, r, u, F, size, ss)
        self._paint(im, P, order, D, shade, fogq, ss)
        out = im.resize(size, Image.LANCZOS).convert("RGB")
        out.save(path)
        return path

    def _sub_faces(self, ws, ds):
        """Quarter faces near the eye: each gets a new descriptor whose uv is its slice of the parent's."""
        outW, outD = [], []
        for q, di in zip(ws, ds):
            q, frac = q
            tref, uv, rot, tint, n = self.desc[di]
            (a0, b0), (a1, b1) = frac
            u1, v1, u2, v2 = uv
            # the quarter's corner 0 lies at (a0, b0) of the parent in its corner order (TL, TR, BR, BL)
            nu1, nu2 = u1 + (u2 - u1) * a0, u1 + (u2 - u1) * a1
            nv1, nv2 = v1 + (v2 - v1) * b0, v1 + (v2 - v1) * b1
            key = ("sub", di, round(a0, 4), round(b0, 4), round(a1, 4), round(b1, 4))
            ndi = self._desc_ix.get(key)
            if ndi is None:
                ndi = len(self.desc)
                self._desc_ix[key] = ndi
                self.desc.append((tref, (nu1, nv1, nu2, nv2), rot, tint, n))
            outW.append(q)
            outD.append(ndi)
        return np.array(outW), np.array(outD, dtype=np.int32)

    def _sky_and_ground(self, C, f, r, u, F, size, ss):
        """The sky, and the ground running on past the plan: every pixel's ray met with the plane at GL."""
        W, H = size[0] * ss, size[1] * ss
        px = (np.arange(W) + 0.5) / ss - size[0] / 2
        py = size[1] / 2 - (np.arange(H) + 0.5) / ss
        ray = f[None, None, :] + r[None, None, :] * (px[None, :, None] / F) + u[None, None, :] * (py[:, None, None] / F)
        ray /= np.linalg.norm(ray, axis=2, keepdims=True)
        el = ray[..., 1]
        top = np.array((112, 160, 232), dtype=np.float32)
        hor = np.array(self.fog, dtype=np.float32)
        t = np.clip(el, 0, 1)[..., None] ** 0.6
        img = hor * (1 - t) + top * t
        if self.GL is not None:
            down = el < -1e-4
            tt = np.where(down, (self.GL - C[1]) / np.where(down, el, -1), 0)
            hx = C[0] + ray[..., 0] * tt
            hz = C[2] + ray[..., 2] * tt
            g = np.asarray(texture("minecraft:block/grass_block_top")).astype(np.float32)[..., :3] * np.array(GRASS, np.float32) / 255
            ix = (np.floor((hx % 1) * 16).astype(int)) % 16
            iz = (np.floor((hz % 1) * 16).astype(int)) % 16
            col = g[iz, ix]
            fo = np.clip((tt - 48) / 150, 0, 0.8)[..., None]
            fo = np.where(tt[..., None] > 400, 1.0, fo)
            col = col * (1 - fo) + hor * fo
            img = np.where(down[..., None], col, img)
        a = np.full((H, W, 1), 255, np.float32)
        return Image.fromarray(np.concatenate([img, a], axis=2).astype(np.uint8), "RGBA")

    def iso(self, path, box, s=20, rot=0, cut=False):
        """A textured isometric picture of a box (x0, y0, z0, x1, y1, z1) from above a corner; rot turns it a
        quarter at a time. With cut, the box's top is sliced open to show the floor inside."""
        x0, y0, z0, x1, y1, z1 = box
        ang = math.radians(45 + 90 * rot)
        dirv = np.array([-math.sin(ang), -math.tan(math.radians(30)), -math.cos(ang)])
        dirv /= np.linalg.norm(dirv)
        r = np.cross(dirv, (0, 1.0, 0))
        r /= np.linalg.norm(r)
        u = np.cross(r, dirv)
        Wc, D = self.collect(box, cut=cut)
        Nr = self._normals(D)
        keep = (Nr @ -dirv) > 1e-6
        Wc, D = Wc[keep], D[keep]
        corners = np.array([(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)], dtype=float)
        pxs, pys = corners @ r, corners @ u
        W = int((pxs.max() - pxs.min()) * s) + 2 * s
        H = int((pys.max() - pys.min()) * s) + 2 * s
        ox, oy = -pxs.min() * s + s, pys.max() * s + s
        P = np.stack([ox + (Wc @ r) * s, oy - (Wc @ u) * s], axis=2)
        dist = Wc.mean(axis=1) @ dirv                # along the line of sight: the far faces first
        order = np.argsort(-dist, kind="stable")
        shade = self._shade(self._normals(D))
        im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        self._paint(im, P, order, D, shade, np.zeros(len(D), int), 1)
        bg = Image.new("RGBA", (W, H), (236, 233, 226, 255))
        bg.alpha_composite(im)
        bg.convert("RGB").save(path)
        return path


def _quarter(q):
    """A face split in four, each with the fraction of the parent it covers (for its slice of the texture)."""
    q, fr = q
    (a0, b0), (a1, b1) = fr
    out = []
    TL, TR, BR, BL = q
    def at(s, t):
        top = TL + (TR - TL) * s
        bot = BL + (BR - BL) * s
        return top + (bot - top) * t
    for (s0, t0) in ((0, 0), (0.5, 0), (0, 0.5), (0.5, 0.5)):
        s1, t1 = s0 + 0.5, t0 + 0.5
        sq = np.array([at(s0, t0), at(s1, t0), at(s1, t1), at(s0, t1)])
        nfr = ((a0 + (a1 - a0) * s0, b0 + (b1 - b0) * t0), (a0 + (a1 - a0) * s1, b0 + (b1 - b0) * t1))
        out.append((sq, nfr))
    return out
