"""Pack each layout's visible voxels into a lossless PNG for the 3D viewer artifact.

Pixel pairs: (x, y, z, 255) then (palette index, 0, 0, 255). Only voxels with an exposed face are
kept, which is what you can see from outside or through the y-cut. meta.json lists the layouts and
a shared palette with a colour per block.
"""
import hashlib
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from render import BASE

AIRLIKE = ("<void>", "minecraft:air", "minecraft:cave_air")


def colour(name):
    base = name.split("[")[0].split(":")[-1]
    if base in BASE:
        return list(BASE[base])
    for k, v in BASE.items():
        if base.endswith(k) or k in base:
            return list(v)
    h = int(hashlib.md5(base.encode()).hexdigest()[:6], 16)
    return [100 + (h >> 16) % 120, 100 + (h >> 8) % 120, 100 + h % 120]


def visible(g):
    a = g.a
    air_ids = np.array([i for i, s in enumerate(g.palette) if s in AIRLIKE or "glass_pane" in s], dtype=np.int32)
    is_air = np.isin(a, air_ids)
    solid = ~is_air
    pad = np.pad(is_air, 1, constant_values=True)
    exposed = np.zeros_like(solid)
    for sh in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
        x, z, y = sh
        exposed |= pad[1 + x:a.shape[0] + 1 + x, 1 + z:a.shape[1] + 1 + z, 1 + y:a.shape[2] + 1 + y]
    return solid & exposed


def write(layout_fns, out_dir, furnish_fn=None):
    os.makedirs(out_dir, exist_ok=True)
    palette, index = [], {}
    layouts = []
    for fn in layout_fns:
        g, meta = fn()
        if furnish_fn:
            furnish_fn(g, meta["GL"])
        vis = visible(g)
        xs, zs, ys = vis.nonzero()
        ids = g.a[xs, zs, ys]
        local = []
        for pid in range(len(g.palette)):
            name = g.palette[pid].split("[")[0]
            if name not in index:
                index[name] = len(palette)
                palette.append(name)
            local.append(index[name])
        local = np.array(local, dtype=np.int32)
        pal = local[ids]
        n = len(xs)
        width = 2048
        rows = (n * 2 + width - 1) // width
        img = np.zeros((rows * width, 4), dtype=np.uint8)
        img[0:n * 2:2, 0] = xs
        img[0:n * 2:2, 1] = ys
        img[0:n * 2:2, 2] = zs
        img[0:n * 2:2, 3] = 255
        img[1:n * 2:2, 0] = pal & 255
        img[1:n * 2:2, 1] = pal >> 8
        img[1:n * 2:2, 3] = 255
        img = img.reshape(rows, width, 4)
        Image.fromarray(img, "RGBA").save(os.path.join(out_dir, meta["name"] + ".png"), optimize=True)
        layouts.append({"name": meta["name"], "tier": meta["tier"], "size": [g.X, g.Y, g.Z], "count": int(n),
                        "file": meta["name"] + ".png", "width": width})
        print(meta["name"], n, "voxels")
    with open(os.path.join(out_dir, "meta.json"), "w") as f:
        json.dump({"palette": palette, "colors": [colour(p) for p in palette], "layouts": layouts}, f)


if __name__ == "__main__":
    import build_all
    import furnish
    write(build_all.LAYOUTS, sys.argv[1] if len(sys.argv) > 1 else os.path.join(build_all.ROOT, "build/view"), furnish.furnish)
