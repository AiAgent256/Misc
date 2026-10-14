"""Write worldgen JSON: one structure per (tier, theme), one structure set per tier, biome tags."""
import json
import os

from themes import BIOMES

TIERS = {
    "great_castle": {"layouts": ["great_castle_a", "great_castle_b", "great_castle_c"],
                     "spacing": 46, "separation": 20, "salt": 718213044},
    "holdfast": {"layouts": ["holdfast_a", "holdfast_b"], "spacing": 30, "separation": 12, "salt": 515870611},
    "watchtower": {"layouts": ["watchtower_a", "watchtower_b"], "spacing": 18, "separation": 7, "salt": 93011254},
}
# Castles keep clear of these (and smaller castles keep clear of bigger ones). Siting shifts a castle
# away from a possible village rather than dropping it. Modded entries are optional.
AVOID_EXTRA = ["nova_structures:village_jungle", "nova_structures:village_swamp", "nova_structures:ruin_town",
               "dungeons_arise:bandit_village", "dungeons_arise:mushroom_village", "dungeons_arise:keep_kayra",
               "dungeons_arise:illager_fort", "dungeons_arise:coliseum", "dungeons_arise:monastery"]
AVOID_EXTRA += [f"valarian_conquest:{f}_{k}" for f in ("barathian", "valarian", "orleanian", "templar", "hospitaller",
                                                         "visgothian", "burgundian", "bohemian", "neutral") for k in ("castle", "outpost")
                if not (f == "bohemian" and k == "castle") and not (f == "neutral" and k == "castle")]
AVOID_EXTRA += ["valarian_conquest:bandit_camp", "valarian_conquest:neutral_tent_camp", "valarian_conquest:neutral_tent_camp_2"]
THEME_WEIGHT = {"north": 3, "riverlands": 3, "reach": 2, "dorne": 2, "stormlands": 2}


def write(root, layout_meta):
    base = os.path.join(root, "src/main/resources/data/stormhold")
    sdir = os.path.join(base, "worldgen/structure")
    setdir = os.path.join(base, "worldgen/structure_set")
    tagdir = os.path.join(base, "tags/worldgen/biome/has_castle")
    for d in (sdir, setdir, tagdir):
        os.makedirs(d, exist_ok=True)
    for theme, biomes in BIOMES.items():
        vals = []
        for b in biomes:
            vals.append({"id": b, "required": False} if b.startswith("#") else b)
        with open(os.path.join(tagdir, f"{theme}.json"), "w") as f:
            json.dump({"replace": False, "values": vals}, f, indent=1)
    for tier, cfg in TIERS.items():
        entries = []
        for theme in BIOMES:
            sid = f"{tier}_{theme}"
            layouts = []
            for ln in cfg["layouts"]:
                m = layout_meta[ln]
                layouts.append({"name": ln, "size_x": m["size"][0], "size_z": m["size"][2],
                                "ground": m["ground"], "weight": 1})
            st = {
                "type": "stormhold:castle",
                "biomes": f"#stormhold:has_castle/{theme}",
                "step": "surface_structures",
                "terrain_adaptation": "beard_box",
                "spawn_overrides": {},
                "theme": theme,
                "layouts": layouts,
            }
            with open(os.path.join(sdir, f"{sid}.json"), "w") as f:
                json.dump(st, f, indent=1)
            entries.append({"structure": f"stormhold:{sid}", "weight": THEME_WEIGHT[theme]})
        sset = {
            "structures": entries,
            "placement": {
                "type": "minecraft:random_spread",
                "spacing": cfg["spacing"], "separation": cfg["separation"], "salt": cfg["salt"],
            },
        }
        with open(os.path.join(setdir, f"{tier}s.json"), "w") as f:
            json.dump(sset, f, indent=1)
    avoid_dir = os.path.join(base, "tags/worldgen/structure")
    os.makedirs(avoid_dir, exist_ok=True)
    vals = ["#minecraft:village"] + [{"id": i, "required": False} for i in AVOID_EXTRA]
    vals += [f"stormhold:{t}_{th}" for t in TIERS for th in BIOMES]
    with open(os.path.join(avoid_dir, "castle_avoid.json"), "w") as f:
        json.dump({"replace": False, "values": vals}, f, indent=1)
