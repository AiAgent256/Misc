"""Regional styles. Templates are built in the 'north' palette; a theme maps each source block
to weighted targets. Properties (facing, half, type, ...) carry over when the target has them.
The mod applies the same JSON at worldgen through a structure processor."""
import json
import os
import zlib

M = "minecraft:"


def w(*pairs):
    return [[M + b if ":" not in b else b, n] for b, n in pairs]


THEMES = {
    "north": {},
    "riverlands": {
        M + "stone_bricks": w(("bricks", 6), ("mud_bricks", 2), ("stone_bricks", 2)),
        M + "cracked_stone_bricks": w(("bricks", 1), ("mud_bricks", 1)),
        M + "andesite": w(("bricks", 2), ("polished_granite", 1)),
        M + "stone": w(("bricks", 3), ("stone_bricks", 1)),
        M + "tuff": w(("mud_bricks", 1)),
        M + "stone_brick_stairs": w(("brick_stairs", 1)),
        M + "stone_brick_slab": w(("brick_slab", 1)),
        M + "stone_brick_wall": w(("brick_wall", 1)),
        M + "deepslate_bricks": w(("stone_bricks", 3), ("cobblestone", 2)),
        M + "cracked_deepslate_bricks": w(("cracked_stone_bricks", 1)),
        M + "cobbled_deepslate": w(("cobblestone", 1)),
        M + "polished_deepslate": w(("polished_andesite", 1)),
        M + "deepslate_brick_stairs": w(("stone_brick_stairs", 1)),
        M + "polished_deepslate_stairs": w(("polished_andesite_stairs", 1)),
        M + "deepslate_tiles": w(("dark_oak_planks", 1)),
        M + "deepslate_tile_stairs": w(("dark_oak_stairs", 1)),
        M + "gray_stained_glass_pane": w(("glass_pane", 1)),
    },
    "reach": {
        M + "stone_bricks": w(("calcite", 3), ("polished_diorite", 3), ("quartz_bricks", 2), ("diorite", 1)),
        M + "cracked_stone_bricks": w(("diorite", 1), ("calcite", 1)),
        M + "cobblestone": w(("diorite", 2), ("cobblestone", 1)),
        M + "andesite": w(("polished_diorite", 1)),
        M + "stone": w(("calcite", 2), ("diorite", 1)),
        M + "tuff": w(("smooth_quartz", 1)),
        M + "chiseled_stone_bricks": w(("chiseled_quartz_block", 1)),
        M + "stone_brick_stairs": w(("polished_diorite_stairs", 1)),
        M + "stone_brick_slab": w(("polished_diorite_slab", 1)),
        M + "stone_brick_wall": w(("diorite_wall", 1)),
        M + "deepslate_bricks": w(("stone_bricks", 2), ("polished_andesite", 1)),
        M + "cracked_deepslate_bricks": w(("cracked_stone_bricks", 1)),
        M + "cobbled_deepslate": w(("andesite", 1)),
        M + "polished_deepslate": w(("polished_andesite", 1)),
        M + "deepslate_brick_stairs": w(("stone_brick_stairs", 1)),
        M + "polished_deepslate_stairs": w(("polished_andesite_stairs", 1)),
        M + "deepslate_tiles": w(("waxed_oxidized_cut_copper", 1)),
        M + "deepslate_tile_stairs": w(("waxed_oxidized_cut_copper_stairs", 1)),
        M + "spruce_planks": w(("oak_planks", 1)),
        M + "spruce_stairs": w(("oak_stairs", 1)),
        M + "spruce_fence": w(("oak_fence", 1)),
        M + "gray_stained_glass_pane": w(("light_blue_stained_glass_pane", 1)),
    },
    "dorne": {
        M + "stone_bricks": w(("cut_sandstone", 4), ("smooth_sandstone", 3), ("sandstone", 3)),
        M + "cracked_stone_bricks": w(("sandstone", 1)),
        M + "mossy_stone_bricks": w(("smooth_sandstone", 1)),
        M + "cobblestone": w(("sandstone", 2), ("smooth_sandstone", 1)),
        M + "mossy_cobblestone": w(("sandstone", 1)),
        M + "andesite": w(("smooth_sandstone", 1)),
        M + "stone": w(("sandstone", 1)),
        M + "tuff": w(("orange_terracotta", 1)),
        M + "chiseled_stone_bricks": w(("chiseled_sandstone", 1)),
        M + "stone_brick_stairs": w(("smooth_sandstone_stairs", 1)),
        M + "stone_brick_slab": w(("smooth_sandstone_slab", 1)),
        M + "stone_brick_wall": w(("sandstone_wall", 1)),
        M + "deepslate_bricks": w(("cut_red_sandstone", 2), ("red_sandstone", 1)),
        M + "cracked_deepslate_bricks": w(("red_sandstone", 1)),
        M + "cobbled_deepslate": w(("red_sandstone", 1)),
        M + "polished_deepslate": w(("smooth_red_sandstone", 1)),
        M + "deepslate_brick_stairs": w(("red_sandstone_stairs", 1)),
        M + "polished_deepslate_stairs": w(("smooth_red_sandstone_stairs", 1)),
        M + "polished_andesite": w(("smooth_sandstone", 1)),
        M + "deepslate_tiles": w(("waxed_cut_copper", 1)),
        M + "deepslate_tile_stairs": w(("waxed_cut_copper_stairs", 1)),
        M + "spruce_planks": w(("acacia_planks", 1)),
        M + "spruce_stairs": w(("acacia_stairs", 1)),
        M + "spruce_fence": w(("acacia_fence", 1)),
        M + "grass_block": w(("sand", 1)),
        M + "podzol": w(("sand", 1)),
        M + "fern": w(("dead_bush", 1)),
        M + "gray_stained_glass_pane": w(("orange_stained_glass_pane", 1)),
    },
    "stormlands": {
        M + "stone_bricks": w(("polished_blackstone_bricks", 4), ("deepslate_bricks", 3), ("cracked_polished_blackstone_bricks", 1)),
        M + "cracked_stone_bricks": w(("cracked_deepslate_bricks", 1)),
        M + "mossy_stone_bricks": w(("mossy_cobblestone", 1)),
        M + "cobblestone": w(("cobbled_deepslate", 1)),
        M + "andesite": w(("polished_blackstone_bricks", 1), ("deepslate_bricks", 1)),
        M + "stone": w(("deepslate_bricks", 1)),
        M + "chiseled_stone_bricks": w(("chiseled_polished_blackstone", 1)),
        M + "stone_brick_stairs": w(("polished_blackstone_brick_stairs", 1)),
        M + "stone_brick_slab": w(("polished_blackstone_brick_slab", 1)),
        M + "stone_brick_wall": w(("polished_blackstone_brick_wall", 1)),
        M + "deepslate_bricks": w(("blackstone", 1)),
        M + "cracked_deepslate_bricks": w(("cracked_polished_blackstone_bricks", 1)),
        M + "cobbled_deepslate": w(("blackstone", 1)),
        M + "polished_deepslate": w(("polished_blackstone", 1)),
        M + "deepslate_brick_stairs": w(("blackstone_stairs", 1)),
        M + "polished_deepslate_stairs": w(("polished_blackstone_stairs", 1)),
        M + "deepslate_tiles": w(("nether_bricks", 1)),
        M + "deepslate_tile_stairs": w(("nether_brick_stairs", 1)),
        M + "spruce_planks": w(("dark_oak_planks", 1)),
        M + "spruce_stairs": w(("dark_oak_stairs", 1)),
        M + "spruce_fence": w(("dark_oak_fence", 1)),
    },
    # the Nether holdfast: blackstone and basalt, nether brick where the north uses deepslate, crimson timber,
    # soul fire for light, and no water (it boils off down there): magma where a well would be
    "nether": {
        M + "stone_bricks": w(("polished_blackstone_bricks", 5), ("blackstone", 2), ("cracked_polished_blackstone_bricks", 1), ("nether_bricks", 2)),
        M + "cracked_stone_bricks": w(("cracked_polished_blackstone_bricks", 2), ("cracked_nether_bricks", 1)),
        M + "mossy_stone_bricks": w(("nether_bricks", 1)),
        M + "cobblestone": w(("blackstone", 3), ("basalt", 1)),
        M + "mossy_cobblestone": w(("blackstone", 1)),
        M + "andesite": w(("polished_basalt", 1), ("blackstone", 1)),
        M + "polished_andesite": w(("polished_basalt", 1)),
        M + "stone": w(("basalt", 2), ("blackstone", 1)),
        M + "tuff": w(("smooth_basalt", 1)),
        M + "gravel": w(("soul_soil", 1)),
        M + "chiseled_stone_bricks": w(("chiseled_polished_blackstone", 1)),
        M + "stone_brick_stairs": w(("polished_blackstone_brick_stairs", 1)),
        M + "stone_brick_slab": w(("polished_blackstone_brick_slab", 1)),
        M + "stone_brick_wall": w(("polished_blackstone_brick_wall", 1)),
        M + "cobblestone_wall": w(("blackstone_wall", 1)),
        M + "deepslate_bricks": w(("nether_bricks", 3), ("red_nether_bricks", 1)),
        M + "cracked_deepslate_bricks": w(("cracked_nether_bricks", 1)),
        M + "cobbled_deepslate": w(("blackstone", 1)),
        M + "polished_deepslate": w(("polished_blackstone", 1)),
        M + "chiseled_deepslate": w(("chiseled_nether_bricks", 1)),
        M + "deepslate_brick_stairs": w(("nether_brick_stairs", 1)),
        M + "deepslate_brick_slab": w(("nether_brick_slab", 1)),
        M + "deepslate_brick_wall": w(("nether_brick_wall", 1)),
        M + "polished_deepslate_stairs": w(("polished_blackstone_stairs", 1)),
        M + "polished_deepslate_slab": w(("polished_blackstone_slab", 1)),
        M + "deepslate_tiles": w(("red_nether_bricks", 1)),
        M + "deepslate_tile_stairs": w(("red_nether_brick_stairs", 1)),
        M + "spruce_planks": w(("crimson_planks", 1)),
        M + "spruce_stairs": w(("crimson_stairs", 1)),
        M + "spruce_slab": w(("crimson_slab", 1)),
        M + "spruce_fence": w(("crimson_fence", 1)),
        M + "spruce_fence_gate": w(("crimson_fence_gate", 1)),
        M + "spruce_log": w(("crimson_stem", 1)),
        M + "spruce_door": w(("crimson_door", 1)),
        M + "spruce_trapdoor": w(("crimson_trapdoor", 1)),
        M + "dark_oak_planks": w(("warped_planks", 1)),
        M + "dark_oak_stairs": w(("warped_stairs", 1)),
        M + "dark_oak_fence": w(("warped_fence", 1)),
        M + "dark_oak_log": w(("warped_stem", 1)),
        M + "oak_planks": w(("crimson_planks", 1)),
        M + "oak_stairs": w(("crimson_stairs", 1)),
        M + "oak_fence": w(("crimson_fence", 1)),
        M + "oak_log": w(("crimson_stem", 1)),
        M + "oak_leaves": w(("nether_wart_block", 1)),
        M + "spruce_leaves": w(("nether_wart_block", 1)),
        M + "grass_block": w(("crimson_nylium", 2), ("netherrack", 1)),
        M + "dirt": w(("netherrack", 1)),
        M + "coarse_dirt": w(("netherrack", 1)),
        M + "dirt_path": w(("soul_soil", 1)),
        M + "podzol": w(("crimson_nylium", 1)),
        M + "grass": w(("crimson_roots", 1)),
        M + "tall_grass": w(("crimson_roots", 1)),
        M + "fern": w(("warped_roots", 1)),
        M + "hay_block": w(("shroomlight", 1)),
        M + "water": w(("magma_block", 1)),
        M + "lantern": w(("soul_lantern", 1)),
        M + "torch": w(("soul_torch", 1)),
        M + "wall_torch": w(("soul_wall_torch", 1)),
        M + "campfire": w(("soul_campfire", 1)),
        M + "gray_stained_glass_pane": w(("red_stained_glass_pane", 1)),
        M + "glass_pane": w(("red_stained_glass_pane", 1)),
        M + "cobweb": w(("cobweb", 1)),
        M + "skeleton_skull": w(("wither_skeleton_skull", 1)),
    },
}

BIOMES = {
    "north": ["minecraft:taiga", "minecraft:snowy_taiga", "minecraft:snowy_plains", "minecraft:old_growth_pine_taiga",
              "minecraft:old_growth_spruce_taiga", "minecraft:grove", "minecraft:windswept_forest",
              "minecraft:windswept_hills", "minecraft:ice_spikes", "#forge:is_snowy", "#forge:is_coniferous"],
    "riverlands": ["minecraft:plains", "minecraft:forest", "minecraft:birch_forest", "minecraft:old_growth_birch_forest",
                   "minecraft:swamp", "#forge:is_plains", "#forge:is_swamp"],
    "reach": ["minecraft:flower_forest", "minecraft:sunflower_plains", "minecraft:meadow", "minecraft:cherry_grove",
              "minecraft:plains"],
    "dorne": ["minecraft:desert", "minecraft:badlands", "minecraft:wooded_badlands", "minecraft:eroded_badlands",
              "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_savanna", "#forge:is_desert",
              "#minecraft:is_badlands", "#minecraft:is_savanna"],
    "stormlands": ["minecraft:dark_forest", "minecraft:mangrove_swamp", "minecraft:windswept_gravelly_hills",
                   "#forge:is_spooky"],
    "nether": ["#minecraft:is_nether"],
}
# the themes a surface castle can wear; the Nether has its own tier
OVERWORLD = ["north", "riverlands", "reach", "dorne", "stormlands"]


def pick(targets, x, y, z):
    if len(targets) == 1:
        return targets[0][0]
    h = zlib.crc32(f"{x},{y},{z}".encode()) % sum(n for _, n in targets)
    for b, n in targets:
        if h < n:
            return b
        h -= n
    return targets[-1][0]


def themed_palette_state(state, theme, x=0, y=0, z=0):
    m = THEMES[theme]
    name = state.split("[")[0]
    if name not in m:
        return state
    props = state[len(name):]
    return pick(m[name], x, y, z) + props


def apply_theme(g, theme):
    """Return a copy of g with blocks themed (for previews)."""
    import copy
    import numpy as np
    if theme == "north":
        return g
    t = copy.copy(g)
    t.palette = list(g.palette)
    t.index = dict(g.index)
    t.a = g.a.copy()
    m = THEMES[theme]
    for pid, st in enumerate(g.palette):
        name = st.split("[")[0]
        if name not in m:
            continue
        props = st[len(name):]
        targets = m[name]
        cells = np.argwhere(g.a == pid)
        if len(targets) == 1:
            t.a[g.a == pid] = t.pid(targets[0][0] + props)
            continue
        for x, z, y in cells:
            t.a[x, z, y] = t.pid(pick(targets, x, y, z) + props)
    return t


def write_json(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, m in THEMES.items():
        with open(os.path.join(out_dir, f"{name}.json"), "w") as f:
            json.dump({"map": m}, f, indent=1)
