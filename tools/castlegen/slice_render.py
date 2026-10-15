"""Top-down render of one floor (y..y+2) for eyeballing interiors."""
import sys, hashlib
import numpy as np
from PIL import Image
from render import BASE

def color(name):
    base = name.split("[")[0].split(":")[-1]
    if base in BASE: return BASE[base]
    h = int(hashlib.md5(base.encode()).hexdigest()[:6], 16)
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)

def render_slice(g, y, path, x0=0, z0=0, x1=None, z1=None, s=6):
    x1 = g.X if x1 is None else x1; z1 = g.Z if z1 is None else z1
    im = Image.new("RGB", ((x1 - x0) * s, (z1 - z0) * s), (20, 20, 20))
    px = im.load()
    for x in range(x0, x1):
        for z in range(z0, z1):
            c = None
            for yy in (y + 2, y + 1, y):
                n = g.get(x, yy, z)
                if n not in ("minecraft:air", "<void>"):
                    c = color(n)
                    if yy == y + 1: c = tuple(min(255, v + 40) for v in c)
                    if yy == y + 2: c = tuple(min(255, v + 80) for v in c)
                    break
            if c is None:
                below = g.get(x, y - 1, z)
                c = tuple(v // 3 for v in color(below)) if below not in ("minecraft:air", "<void>") else (0, 0, 0)
            for i in range(s):
                for j in range(s):
                    px[(x - x0) * s + i, (z - z0) * s + j] = c
    im.save(path)
