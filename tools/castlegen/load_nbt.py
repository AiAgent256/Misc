"""Load a vanilla structure .nbt into a Grid (for preview/comparison renders)."""
import nbtlib
import numpy as np

from grid import Grid

SKIP = {"minecraft:structure_void", "minecraft:jigsaw", "minecraft:structure_block", "minecraft:air",
        "minecraft:cave_air", "minecraft:void_air", "minecraft:barrier", "minecraft:light"}


def state_str(entry):
    name = str(entry["Name"])
    props = entry.get("Properties")
    if props:
        return name + "[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]"
    return name


def load(path):
    f = nbtlib.load(path)
    root = f
    size = [int(v) for v in root["size"]]
    X, Y, Z = size
    g = Grid(X, Y, Z)
    palette = root["palette"] if "palette" in root else root["palettes"][0]
    ids = []
    for e in palette:
        n = str(e["Name"])
        ids.append(1 if n in SKIP else g.pid(state_str(e)))
    for b in root["blocks"]:
        x, y, z = (int(v) for v in b["pos"])
        g.a[x, z, y] = ids[int(b["state"])]
    return g
