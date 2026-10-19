"""Isometric preview renderer for Grid voxels."""
import re

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

BASE = {
    "stone_bricks": (125, 124, 124), "cracked_stone_bricks": (112, 111, 111), "mossy_stone_bricks": (106, 118, 96),
    "cobblestone": (118, 118, 118), "andesite": (138, 138, 138), "stone": (128, 128, 128), "tuff": (108, 109, 102),
    "deepslate_bricks": (72, 72, 74), "cracked_deepslate_bricks": (66, 66, 68), "cobbled_deepslate": (78, 78, 82),
    "polished_deepslate": (74, 74, 78), "deepslate_tiles": (52, 52, 58), "deepslate_tile_stairs": (52, 52, 58),
    "deepslate_brick_stairs": (72, 72, 74), "stone_brick_stairs": (125, 124, 124), "stone_brick_slab": (125, 124, 124),
    "stone_brick_wall": (120, 119, 119), "polished_andesite": (132, 135, 134), "chiseled_stone_bricks": (120, 119, 119),
    "spruce_planks": (114, 84, 48), "dark_oak_planks": (66, 43, 20), "dark_oak_log": (60, 46, 26),
    "dark_oak_fence": (66, 43, 20), "spruce_fence": (114, 84, 48), "dark_oak_slab": (66, 43, 20),
    "dark_oak_stairs": (66, 43, 20), "spruce_stairs": (114, 84, 48), "water": (52, 88, 190), "gravel": (131, 127, 126),
    "grass_block": (92, 150, 58), "podzol": (91, 63, 24), "coarse_dirt": (119, 85, 59), "dirt_path": (148, 122, 65),
    "packed_mud": (142, 106, 79), "mossy_cobblestone": (110, 118, 94), "clay": (160, 166, 179), "mud": (60, 57, 60),
    "hay_block": (166, 136, 38), "iron_bars": (100, 100, 100), "chain": (60, 60, 70), "lantern": (230, 180, 80),
    "gold_block": (246, 208, 61), "red_carpet": (160, 39, 34), "bookshelf": (117, 94, 59), "bricks": (150, 97, 83),
    "campfire": (200, 120, 50), "portcullis": (60, 60, 64), "lightning_rod": (200, 120, 80),
    "nether_wart_block": (146, 22, 22), "red_mushroom_block": (190, 40, 40), "birch_wood": (216, 215, 210),
    "white_wall_banner": (180, 30, 30), "white_wool": (230, 230, 230), "red_wool": (160, 39, 34),
    "yellow_wool": (248, 197, 39), "blue_wool": (53, 57, 157), "barrel": (120, 90, 50), "chest": (160, 110, 40),
    "polished_blackstone_bricks": (48, 42, 49), "target": (220, 210, 200), "carved_pumpkin": (200, 120, 20),
    "shroomlight": (240, 146, 70), "glass_pane": (190, 210, 220), "gray_stained_glass_pane": (80, 80, 90),
    "pointed_dripstone": (130, 100, 85), "polished_deepslate_stairs": (74, 74, 78), "fern": (80, 130, 50),
    "white_terracotta": (210, 178, 161), "light_gray_terracotta": (135, 107, 98), "yellow_terracotta": (186, 133, 35),
    "orange_terracotta": (162, 84, 38), "brown_terracotta": (77, 51, 36), "red_terracotta": (143, 61, 47),
    "terracotta": (152, 94, 68), "mud_bricks": (137, 104, 79), "mud_brick_stairs": (137, 104, 79),
    "mud_brick_slab": (137, 104, 79), "brick_stairs": (150, 97, 83), "brick_slab": (150, 97, 83),
    "stripped_dark_oak_log": (97, 76, 50), "stripped_spruce_log": (116, 90, 52), "dark_oak_trapdoor": (66, 43, 20),
    "spruce_trapdoor": (114, 84, 48), "spruce_log": (58, 37, 16), "oak_planks": (162, 130, 78), "oak_log": (109, 85, 50),
    "stripped_oak_log": (177, 144, 86), "decorated_pot": (150, 90, 60), "cake": (240, 230, 220), "smoker": (90, 80, 70),
    "blast_furnace": (80, 80, 84), "anvil": (70, 70, 72), "loom": (140, 110, 70), "cauldron": (60, 60, 64),
    "crafting_table": (120, 90, 50), "composter": (110, 80, 40), "fletching_table": (170, 140, 90),
    "cartography_table": (100, 70, 40), "smithing_table": (60, 50, 50), "grindstone": (120, 120, 120),
    "oak_fence": (162, 130, 78), "dark_oak_wood": (60, 46, 26), "cobblestone_stairs": (118, 118, 118),
    "cobblestone_slab": (118, 118, 118), "cobblestone_wall": (118, 118, 118), "andesite_stairs": (138, 138, 138),
    "andesite_slab": (138, 138, 138), "andesite_wall": (138, 138, 138), "oak_stairs": (162, 130, 78), "oak_slab": (162, 130, 78),
    "dark_oak_door": (66, 43, 20), "spruce_door": (114, 84, 48), "oak_door": (162, 130, 78), "iron_door": (190, 190, 190),
    "hanging_sign": (114, 84, 48), "wall_hanging_sign": (114, 84, 48), "flower_pot": (150, 90, 60), "lectern": (160, 120, 70),
    "melon": (120, 160, 50), "pumpkin": (200, 120, 20), "wheat": (190, 170, 80), "farmland": (100, 70, 40),
    "oak_leaves": (60, 120, 40), "azalea_leaves": (80, 130, 50), "sweet_berry_bush": (70, 110, 50), "mossy_stone_brick_stairs": (106, 118, 96),
    "polished_andesite_stairs": (132, 135, 134), "polished_andesite_slab": (132, 135, 134), "stone_stairs": (128, 128, 128),
    "stone_slab": (128, 128, 128), "smooth_stone": (160, 160, 160), "smooth_stone_slab": (160, 160, 160),
    "brick_wall": (150, 97, 83), "mud_brick_wall": (137, 104, 79), "sandstone": (216, 203, 155), "cut_sandstone": (216, 203, 155),
    "quartz_bricks": (234, 229, 221), "calcite": (222, 223, 219), "diorite": (190, 190, 190), "polished_diorite": (196, 197, 198),
    "smooth_quartz": (236, 231, 224), "chiseled_quartz_block": (231, 226, 218), "quartz_stairs": (234, 229, 221),
    "quartz_slab": (234, 229, 221), "smooth_quartz_stairs": (236, 231, 224), "smooth_quartz_slab": (236, 231, 224),
    "diorite_stairs": (190, 190, 190), "diorite_slab": (190, 190, 190), "diorite_wall": (190, 190, 190),
    "polished_diorite_stairs": (196, 197, 198), "polished_diorite_slab": (196, 197, 198), "sand": (219, 207, 163),
    "sugar_cane": (140, 190, 90), "lily_pad": (40, 110, 40),
}
WOOL = {"red": (160, 39, 34), "yellow": (248, 197, 39), "blue": (53, 57, 157), "green": (84, 109, 27), "white": (230, 230, 230),
        "orange": (240, 118, 19), "purple": (121, 42, 172), "lime": (112, 185, 25), "brown": (114, 71, 40), "cyan": (21, 137, 145),
        "gray": (62, 68, 71), "light_gray": (142, 142, 134), "black": (20, 21, 25), "pink": (237, 141, 172), "magenta": (189, 68, 179),
        "light_blue": (58, 175, 217)}


def color_of(state):
    name = state.split("[")[0].split(":")[-1]
    if name in BASE:
        return BASE[name]
    for k, v in BASE.items():
        if name.endswith(k):
            return v
    for c, v in WOOL.items():
        if name.startswith(c + "_") and any(name.endswith(k) for k in ("wool", "carpet", "bed", "banner", "wall_banner", "concrete")):
            return v
    if "stained_glass" in name:
        return (150, 150, 220)
    if "bed" in name:
        return (150, 40, 40)
    if "candle" in name:
        return (240, 220, 160)
    return (140, 140, 140)


def is_transparent(state):
    return state in ("<void>", "minecraft:air")


def render_iso(g, path, s=4, rot=0, cut=None, title=None, bg=(212, 222, 232)):
    a = g.a
    for _ in range(rot):
        a = np.rot90(a, k=1, axes=(0, 1))
    if cut is not None:
        a = a.copy()
        cut(a)
    X, Z, Y = a.shape
    solid = np.zeros(a.shape, dtype=bool)
    transparent_ids = {0, 1}
    for i, st in enumerate(g.palette):
        if i in transparent_ids:
            continue
        solid |= (a == i)
    # visible if a neighbour in +x, +z or +y is not solid
    vis = np.zeros_like(solid)
    pad = np.pad(solid, ((0, 1), (0, 1), (0, 1)), constant_values=False)
    vis |= solid & ~pad[1:, :-1, :-1]
    vis |= solid & ~pad[:-1, 1:, :-1]
    vis |= solid & ~pad[:-1, :-1, 1:]
    xs, zs, ys = np.nonzero(vis)
    order = np.argsort(xs + zs + ys * 1.0001, kind="stable")
    xs, zs, ys = xs[order], zs[order], ys[order]
    W = (X + Z) * s + 4 * s
    Hh = (X + Z) * s // 2 + Y * s + 6 * s
    img = Image.new("RGB", (W, Hh), bg)
    ox = Z * s + 2 * s
    oy = Y * s + 2 * s
    cache = {}
    rng = np.random.default_rng(3)

    def sprite(rgb):
        key = rgb
        if key in cache:
            return cache[key]
        sp = Image.new("RGBA", (2 * s, 2 * s), (0, 0, 0, 0))
        dr = ImageDraw.Draw(sp)
        top = [(s, 0), (2 * s, s // 2), (s, s), (0, s // 2)]
        left = [(0, s // 2), (s, s), (s, 2 * s), (0, s + s // 2)]
        right = [(s, s), (2 * s, s // 2), (2 * s, s + s // 2), (s, 2 * s)]
        c = np.array(rgb)
        dr.polygon(top, fill=tuple(np.clip(c * 1.12, 0, 255).astype(int)))
        dr.polygon(left, fill=tuple(np.clip(c * 0.82, 0, 255).astype(int)))
        dr.polygon(right, fill=tuple(np.clip(c * 0.64, 0, 255).astype(int)))
        cache[key] = sp
        return sp

    pal_rgb = [color_of(st) for st in g.palette]
    for x, z, y in zip(xs, zs, ys):
        rgb = pal_rgb[a[x, z, y]]
        jitter = int(rng.integers(-6, 7))
        rgb = tuple(max(0, min(255, c + jitter)) for c in rgb)
        rgb = (rgb[0] // 3 * 3, rgb[1] // 3 * 3, rgb[2] // 3 * 3)
        px = ox + (x - z) * s - s
        py = oy + (x + z) * s // 2 - y * s - s
        img.paste(sprite(rgb), (int(px), int(py)), sprite(rgb))
    if title:
        ImageDraw.Draw(img).text((10, 10), title, fill=(30, 30, 30))
    img.save(path)
    return path


def render_plan(g, path, s=5):
    a = g.a
    X, Z, Y = a.shape
    img = np.zeros((Z, X, 3), dtype=np.uint8)
    pal_rgb = np.array([color_of(st) for st in g.palette], dtype=float)
    solid = a > 1
    top = np.where(solid.any(axis=2), Y - 1 - np.argmax(solid[:, :, ::-1], axis=2), -1)
    for x in range(X):
        for z in range(Z):
            y = top[x, z]
            if y < 0:
                img[z, x] = (212, 222, 232)
                continue
            c = pal_rgb[a[x, z, y]] * (0.55 + 0.45 * y / Y)
            img[z, x] = np.clip(c, 0, 255)
    Image.fromarray(img).resize((X * s, Z * s), Image.NEAREST).save(path)
    return path
