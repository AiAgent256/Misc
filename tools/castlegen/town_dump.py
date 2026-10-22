"""Draw a town the GameTest wrote to its log (TOWNDUMP lines): an isometric view and a plan.

    python3 town_dump.py gametest.log out_prefix
"""
import sys

import numpy as np

from render import render_iso, render_plan


class Dump:
    def __init__(self, X, Y, Z):
        self.X, self.Y, self.Z = X, Y, Z
        self.palette = ["<void>", "minecraft:air"]
        self.a = np.zeros((X, Z, Y), dtype=np.int32)


def load(path):
    head, pal, cols = None, {}, []
    for line in open(path, errors="replace"):
        i = line.find("TOWNDUMP ")
        if i < 0:
            continue
        f = line[i:].rstrip("\n").split(" ")
        if f[1] == "H":
            head = tuple(int(v) for v in f[2:5])
        elif f[1] == "P":
            pal[int(f[2])] = f[3]
        elif f[1] == "C":
            cols.append((int(f[2]), int(f[3]), [int(v) for v in f[4].split(",")]))
    X, Y, Z = head
    d = Dump(X, Y, Z)
    remap = {}
    for k, st in pal.items():
        if st.startswith("minecraft:air") or st.startswith("minecraft:cave_air"):
            remap[k] = 1
        else:
            remap[k] = len(d.palette)
            d.palette.append(st)
    grass = len(d.palette)
    d.palette.append("minecraft:grass_block")
    d.a[:, :, 0] = grass
    d.a[:, :, 1:] = 1
    for x, z, ids in cols:
        for y, v in enumerate(ids):
            d.a[x, z, y] = remap[v]
    return d


if __name__ == "__main__":
    d = load(sys.argv[1])
    out = sys.argv[2]
    render_iso(d, out + "_iso.png", s=4)
    render_iso(d, out + "_iso_r2.png", s=4, rot=2)
    render_plan(d, out + "_plan.png", s=4)
    print(d.a.shape, len(d.palette), "states")
