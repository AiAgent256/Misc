"""Dense town fabric for the Great City: party-wall terraces of tall narrow houses, jettied upper
storeys leaning over alleys, open shopfronts with awnings and goods, light-well courts, covered
passages, bridges between houses, arcaded market edges and stall clusters.

A real walled city grew upward and inward: lanes a cart wide, upper floors overhanging the street,
trades clustered by street, the market ringed by arcades. That's what these pieces reproduce.
"""
import numpy as np

from grid import Mix, gable_roof, FACING_VEC, OPPOSITE
from parts import STONE, DARK, FOUND, PLANK, DARK_PLANK, GLASS, lamp_post

AIR = "minecraft:air"
LOOT = "stormhold:chests/"

PLASTER = Mix(("minecraft:white_terracotta", 45), ("minecraft:light_gray_terracotta", 15),
              ("minecraft:white_concrete_powder", 15), ("minecraft:mud_bricks", 15), ("minecraft:smooth_stone", 10))
OCHRE = Mix(("minecraft:yellow_terracotta", 40), ("minecraft:orange_terracotta", 15), ("minecraft:mud_bricks", 25),
            ("minecraft:white_terracotta", 20))
RUBBLE = Mix(("minecraft:cobblestone", 40), ("minecraft:stone_bricks", 25), ("minecraft:andesite", 20),
             ("minecraft:mossy_cobblestone", 8), ("minecraft:tuff", 7))
BRICK = Mix(("minecraft:bricks", 70), ("minecraft:mud_bricks", 30))
WALLS = [PLASTER, PLASTER, OCHRE, RUBBLE, BRICK, STONE]
ALLEY = Mix(("minecraft:cobblestone", 45), ("minecraft:gravel", 15), ("minecraft:andesite", 20),
            ("minecraft:mossy_cobblestone", 12), ("minecraft:packed_mud", 8))
ROOFS = ["minecraft:deepslate_tile_stairs", "minecraft:deepslate_tile_stairs", "minecraft:brick_stairs",
         "minecraft:dark_oak_stairs", "minecraft:spruce_stairs", "minecraft:mud_brick_stairs",
         "minecraft:cobbled_deepslate_stairs"]
ROOF_FILL = {"minecraft:deepslate_tile_stairs": "minecraft:deepslate_tiles", "minecraft:brick_stairs": "minecraft:bricks",
             "minecraft:dark_oak_stairs": "minecraft:dark_oak_planks", "minecraft:spruce_stairs": "minecraft:spruce_planks",
             "minecraft:mud_brick_stairs": "minecraft:mud_bricks", "minecraft:cobbled_deepslate_stairs": "minecraft:cobbled_deepslate"}
TIMBER = "minecraft:dark_oak_log"
BEAM = "minecraft:stripped_dark_oak_log"
SHUTTER = "minecraft:dark_oak_trapdoor"
AWNING_COLORS = ["red", "yellow", "blue", "green", "white", "orange", "purple", "lime", "brown", "cyan"]
BED_COLORS = ["red", "brown", "green", "blue", "gray", "white", "purple", "light_gray"]
GOODS = ["minecraft:hay_block[axis=y]", "minecraft:barrel[facing=up]", "minecraft:decorated_pot", "minecraft:cake",
         "minecraft:composter", "minecraft:lantern[hanging=false]", "minecraft:flower_pot", "minecraft:melon",
         "minecraft:pumpkin", "minecraft:bookshelf", "minecraft:cauldron", "minecraft:red_wool", "minecraft:blue_wool",
         "minecraft:bread", "minecraft:chest[facing=north]", "minecraft:loom[facing=north]"]
GOODS = [b for b in GOODS if b not in ("minecraft:bread", "minecraft:cake")] + ["minecraft:cake"]
# Timber-framed ground storeys (the market towns from their third revision): log posts at the corners and every
# three blocks, a beam at the storey line, the wall between them as infill, shutters on every window, some barred,
# and a planter of flowers on the sill. Off for the cities, so their plans rebuild as they were.
FACADES = False
POTS = ("minecraft:potted_red_tulip", "minecraft:potted_poppy", "minecraft:potted_azure_bluet",
        "minecraft:potted_flowering_azalea_bush", "minecraft:potted_oxeye_daisy", "minecraft:potted_cornflower")

TRADES = {
    "smith": dict(items=["minecraft:anvil[facing=east]", "minecraft:blast_furnace[facing=south,lit=true]",
                         "minecraft:smithing_table", "minecraft:grindstone[face=floor,facing=north]"], awning="gray",
                  sign=("Smithy", "Tools"), chimney=True),
    "armoury": dict(items=["minecraft:anvil[facing=east]", "minecraft:smithing_table", "minecraft:chest[facing=north]",
                           "minecraft:barrel[facing=up]"], awning="light_gray", sign=("Armoury",)),
    "weapons": dict(items=["minecraft:grindstone[face=floor,facing=north]", "minecraft:anvil[facing=east]",
                           "minecraft:barrel[facing=up]", "minecraft:chest[facing=north]"], awning="black", sign=("Weapon", "smith")),
    "fletcher": dict(items=["minecraft:fletching_table", "minecraft:target", "minecraft:barrel[facing=up]",
                            "minecraft:hay_block[axis=y]"], awning="lime", sign=("Fletcher", "Bows")),
    "farmer": dict(items=["minecraft:composter", "minecraft:hay_block[axis=y]", "minecraft:pumpkin",
                          "minecraft:barrel[facing=up]"], awning="green", sign=("Farm", "Supply")),
    "baker": dict(items=["minecraft:smoker[facing=south,lit=true]", "minecraft:cake", "minecraft:hay_block[axis=y]",
                         "minecraft:barrel[facing=up]"], awning="yellow", sign=("Bakery",), chimney=True),
    "fish": dict(items=["minecraft:barrel[facing=up]", "minecraft:campfire[lit=true,signal_fire=false]",
                        "minecraft:cauldron", "minecraft:chest[facing=north]"], awning="blue", sign=("Fish",)),
    "cloth": dict(items=["minecraft:loom[facing=north]", "minecraft:red_wool", "minecraft:purple_wool",
                         "minecraft:lime_wool", "minecraft:white_wool"], awning="purple", sign=("Draper", "Tailor")),
    "tanner": dict(items=["minecraft:cauldron", "minecraft:barrel[facing=up]", "minecraft:brown_wool",
                          "minecraft:fletching_table"], awning="brown", sign=("Saddler",)),
    "butcher": dict(items=["minecraft:barrel[facing=up]", "minecraft:chest[facing=north]", "minecraft:smoker[facing=south,lit=false]",
                           "minecraft:cauldron"], awning="red", sign=("Butcher",)),
    "potter": dict(items=["minecraft:decorated_pot", "minecraft:flower_pot", "minecraft:decorated_pot", "minecraft:clay"],
                   awning="orange", sign=("Mason &", "Potter")),
    "apothecary": dict(items=["minecraft:brewing_stand", "minecraft:bookshelf", "minecraft:cauldron", "minecraft:lectern[facing=north]"],
                       awning="cyan", sign=("Apothe-", "cary")),
    "chandler": dict(items=["minecraft:lantern[hanging=false]", "minecraft:candle[candles=3,lit=true]",
                            "minecraft:barrel[facing=up]", "minecraft:white_candle[candles=4,lit=true]"], awning="white", sign=("Chandler",)),
    "general": dict(items=GOODS, awning=None, sign=("General", "Store")),
}


def sign_nbt(lines, sign_id="minecraft:hanging_sign"):
    """Waxed sign text, centred on the lines a sign has."""
    msgs = list(lines) + [""] * (4 - len(lines))
    if len(lines) <= 2:
        msgs = [""] + list(lines) + [""] * (3 - len(lines))
    return {"id": sign_id, "is_waxed": 1,
            "front_text": {"messages": [f'{{"text":"{m}"}}' for m in msgs[:4]], "color": "black", "has_glowing_text": 0},
            "back_text": {"messages": [f'{{"text":"{m}"}}' for m in msgs[:4]], "color": "black", "has_glowing_text": 0}}


def _dir(street):
    """(dx, dz) pointing from the house toward the street."""
    return FACING_VEC[street]


def townhouse(g, x0, z0, x1, z1, GL, rng, street, storeys=None, wall=None, trade=None, jetty=True,
              left_party=False, right_party=False, roof=None, passage=False):
    """
    A tall narrow town house whose front is on the `street` side. Ground storey is a shop when `trade`
    is given (open front, counter, awning, goods, hanging sign), else a dwelling. Upper storeys are
    jettied one block over the street on corbels. Party walls are shared with neighbours.
    `passage` makes the ground floor an open arched way through the house (an alley under the rooms).
    Returns the door cell (x, GL, z) on the street.
    """
    storeys = storeys or int(rng.integers(2, 5))
    wall = wall if wall is not None else rng.choice(WALLS)
    roof = roof or rng.choice(ROOFS)
    sh = 4                                     # storey height
    top = GL + sh * storeys                    # first roof row
    dx, dz = _dir(street)
    # the street-facing face coordinates
    fx = x1 if dx > 0 else x0
    fz = z1 if dz > 0 else z0
    along_x = dz != 0                          # frontage runs along x
    # ---- shell ----
    g.fill(x0, 0, z0, x1, GL - 2, z1, FOUND)
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, PLANK if wall in (PLASTER, OCHRE) else "minecraft:stone_bricks")
    g.fill(x0, GL, z0, x1, top - 1, z1, wall)
    g.fill(x0 + 1, GL, z0 + 1, x1 - 1, top - 1, z1 - 1, AIR)
    # ---- jetty: every upper storey steps one block out over the street ----
    jx0, jz0, jx1, jz1 = x0, z0, x1, z1
    if jetty and storeys >= 2:
        if along_x:
            if dz > 0: jz1 = z1 + 1
            else: jz0 = z0 - 1
        else:
            if dx > 0: jx1 = x1 + 1
            else: jx0 = x0 - 1
        y0 = GL + sh
        g.fill(jx0, y0, jz0, jx1, top - 1, jz1, wall)
        g.fill(jx0 + 1, y0, jz0 + 1, jx1 - 1, top - 1, jz1 - 1, AIR)
        # bressummer beam along the front, corbels under it
        if along_x:
            zz = jz1 if dz > 0 else jz0
            g.fill(jx0, y0, zz, jx1, y0, zz, f"{BEAM}[axis=x]")
            for x in range(jx0, jx1 + 1, 2):
                g.stair(x, y0 - 1, zz, "minecraft:dark_oak_stairs", OPPOSITE[street], half="top")
        else:
            xx = jx1 if dx > 0 else jx0
            g.fill(xx, y0, jz0, xx, y0, jz1, f"{BEAM}[axis=z]")
            for z in range(jz0, jz1 + 1, 2):
                g.stair(xx, y0 - 1, z, "minecraft:dark_oak_stairs", OPPOSITE[street], half="top")
    # ---- floors ----
    for s in range(1, storeys):
        y = GL + sh * s - 1
        g.fill(jx0 + 1, y, jz0 + 1, jx1 - 1, y, jz1 - 1, PLANK)
    # ---- timber frame on the upper storeys (plaster houses) ----
    timber = wall in (PLASTER, OCHRE)
    if timber and storeys >= 2:
        y0 = GL + sh
        for s in range(1, storeys):
            yb = GL + sh * s
            # horizontal beams at each storey line, corner posts, studs every 3
            g.fill(jx0, yb, jz0, jx1, yb, jz0, f"{TIMBER}[axis=x]"); g.fill(jx0, yb, jz1, jx1, yb, jz1, f"{TIMBER}[axis=x]")
            g.fill(jx0, yb, jz0, jx0, yb, jz1, f"{TIMBER}[axis=z]"); g.fill(jx1, yb, jz0, jx1, yb, jz1, f"{TIMBER}[axis=z]")
            for (x, z) in ((jx0, jz0), (jx1, jz0), (jx0, jz1), (jx1, jz1)):
                g.fill(x, yb, z, x, yb + sh - 1, z, f"{TIMBER}[axis=y]")
            for x in range(jx0 + 3, jx1 - 1, 3):
                for z in (jz0, jz1):
                    g.fill(x, yb + 1, z, x, yb + sh - 1, z, f"{TIMBER}[axis=y]")
            for z in range(jz0 + 3, jz1 - 1, 3):
                for x in (jx0, jx1):
                    g.fill(x, yb + 1, z, x, yb + sh - 1, z, f"{TIMBER}[axis=y]")
    # ---- quoins on stone houses ----
    if not timber:
        for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
            for y in range(GL, GL + sh, 2):
                g.set(x, y, z, "minecraft:polished_andesite")
    # ---- windows: panes, sills, shutters, on street and open sides ----
    def window(x, y, z, face):
        ground = FACADES and y == GL + 1
        g.fill(x, y, z, x, y + 1, z, "minecraft:iron_bars" if ground and rng.random() < 0.3 else GLASS)
        ox, oz = x + FACING_VEC[face][0], z + FACING_VEC[face][1]
        if g.get(ox, y - 1, z if face in ("north", "south") else oz) == AIR and g.get(ox, y - 1, oz) == AIR:
            if ground:
                # a planter on the sill: a box of dark oak with a pot of flowers on it
                g.set(ox, y - 1, oz, "minecraft:dark_oak_slab[type=top]")
                if g.get(ox, y, oz) == AIR:
                    g.set(ox, y, oz, POTS[int(rng.integers(0, len(POTS)))])
            else:
                g.stair(ox, y - 1, oz, "minecraft:stone_brick_stairs", OPPOSITE[face], half="top")
        if ground or rng.random() < 0.5:
            for s_ in (-1, 1):
                sx, sz = (x + s_, z) if face in ("north", "south") else (x, z + s_)
                if g.get(sx + FACING_VEC[face][0], y, sz + FACING_VEC[face][1]) == AIR and g.get(sx, y, sz) != AIR:
                    g.set(sx + FACING_VEC[face][0], y, sz + FACING_VEC[face][1], f"{SHUTTER}[facing={OPPOSITE[face]},open=true,half=bottom]")
    faces = [street]
    if not left_party or not right_party:
        for f in ("north", "south", "east", "west"):
            if f != street and f != OPPOSITE[street]:
                faces.append(f)
    for s in range(storeys):
        y = GL + sh * s + 1
        bx0, bz0, bx1, bz1 = (x0, z0, x1, z1) if s == 0 else (jx0, jz0, jx1, jz1)
        for f in faces:
            if f in ("north", "south"):
                zz = bz0 if f == "north" else bz1
                for x in range(bx0 + 2, bx1 - 1, 3):
                    if s == 0 and f == street and trade: continue
                    window(x, y, zz, f)
            else:
                xx = bx0 if f == "west" else bx1
                for z in range(bz0 + 2, bz1 - 1, 3):
                    if s == 0 and f == street and trade: continue
                    window(xx, y, z, f)
    # ---- door, step, awning, lantern ----
    if along_x:
        cx = (x0 + x1) // 2
        door = (cx, fz)
    else:
        cz = (z0 + z1) // 2
        door = (fx, cz)
    ddx, ddz = dx, dz
    if passage:
        # an alley through the ground floor, open both ends
        if along_x:
            g.fill(x0 + 1, GL, z0, x1 - 1, GL + 2, z1, AIR)
            g.fill(x0 + 1, GL - 1, z0, x1 - 1, GL - 1, z1, ALLEY)
        else:
            g.fill(x0, GL, z0 + 1, x1, GL + 2, z1 - 1, AIR)
            g.fill(x0, GL - 1, z0 + 1, x1, GL - 1, z1 - 1, ALLEY)
        g.lantern(door[0], GL + 3, door[1] - ddz if along_x else door[1])
    else:
        g.fill(door[0], GL, door[1], door[0], GL + 1, door[1], AIR)
        g.set(door[0], GL, door[1], f"minecraft:dark_oak_door[facing={street},half=lower,hinge=left]")
        g.set(door[0], GL + 1, door[1], f"minecraft:dark_oak_door[facing={street},half=upper,hinge=left]")
        sx, sz = door[0] + ddx, door[1] + ddz
        g.set(sx, GL - 1, sz, "minecraft:stone_bricks")
        if g.get(sx, GL + 2, sz) == AIR and not (jetty and storeys >= 2):
            g.stair(sx, GL + 2, sz, "minecraft:dark_oak_stairs", OPPOSITE[street], half="top")
        g.set(door[0] + (1 if along_x else 0) + ddx, GL + 2, door[1] + (0 if along_x else 1) + ddz, "minecraft:lantern[hanging=true]") \
            if g.get(door[0] + (1 if along_x else 0) + ddx, GL + 3, door[1] + (0 if along_x else 1) + ddz) != AIR else None
    # ---- shopfront ----
    if trade and not passage:
        t = TRADES[trade]
        # open the front: a counter of trapdoors on fence under the jetty, goods behind
        if along_x:
            for x in range(x0 + 1, x1):
                if x == door[0]: continue
                g.fill(x, GL, fz, x, GL + 2, fz, AIR)
                g.set(x, GL, fz, "minecraft:dark_oak_fence")
                g.set(x, GL + 1, fz, f"{SHUTTER}[facing={street},open=false,half=top]")
            inside = [(x, z0 + 1 if dz > 0 else z1 - 1) for x in range(x0 + 1, x1) if x != door[0]]
            front = [(x, fz + dz) for x in range(x0, x1 + 1)]
        else:
            for z in range(z0 + 1, z1):
                if z == door[1]: continue
                g.fill(fx, GL, z, fx, GL + 2, z, AIR)
                g.set(fx, GL, z, "minecraft:dark_oak_fence")
                g.set(fx, GL + 1, z, f"{SHUTTER}[facing={street},open=false,half=top]")
            inside = [(x0 + 1 if dx > 0 else x1 - 1, z) for z in range(z0 + 1, z1) if z != door[1]]
            front = [(fx + dx, z) for z in range(z0, z1 + 1)]
        items = list(t["items"])
        for i, (x, z) in enumerate(inside[:4]):
            g.set(x, GL, z, items[i % len(items)])
        # awning: a row of coloured wool one out over the pavement, on fence props, unless the jetty covers it
        col = t["awning"] or rng.choice(AWNING_COLORS)
        if not (jetty and storeys >= 2):
            for (x, z) in front:
                g.set(x, GL + 3, z, f"minecraft:{col}_wool")
            g.fill(front[0][0], GL, front[0][1], front[0][0], GL + 2, front[0][1], "minecraft:dark_oak_fence")
            g.fill(front[-1][0], GL, front[-1][1], front[-1][0], GL + 2, front[-1][1], "minecraft:dark_oak_fence")
        else:
            # a hanging banner-awning under the jetty instead
            for (x, z) in front[1:-1]:
                g.set(x, GL + 3, z, f"minecraft:{col}_wool")
        # a crate or two on the pavement
        for (x, z) in (front[1], front[-2]):
            if rng.random() < 0.5:
                g.set(x, GL, z, rng.choice(["minecraft:barrel[facing=up]", "minecraft:hay_block[axis=y]", "minecraft:decorated_pot"]))
        # hanging sign beside the door
        sgx, sgz = door[0] + (2 if along_x else 0) + ddx, door[1] + (0 if along_x else 2) + ddz
        g.set(sgx, GL + 2, sgz, f"minecraft:dark_oak_wall_hanging_sign[facing={street}]", nbt=sign_nbt(t["sign"]))
        # the shopkeeper stands behind the counter, in the middle of the shop and clear of the door
        if along_x:
            cells = [x for x in range(x0 + 1, x1) if abs(x - door[0]) > 1] or [x for x in range(x0 + 1, x1) if x != door[0]]
            kx = cells[len(cells) // 2]
            g.mark("shop_" + trade, kx, GL, fz - dz)
        else:
            cells = [z for z in range(z0 + 1, z1) if abs(z - door[1]) > 1] or [z for z in range(z0 + 1, z1) if z != door[1]]
            kz = cells[len(cells) // 2]
            g.mark("shop_" + trade, fx - dx, GL, kz)
        if t.get("chimney"):
            chim = (x0 + 1, z0 + 1) if dz > 0 else (x1 - 1, z1 - 1)
        else:
            chim = None
    else:
        chim = (x0 + 1, z0 + 1) if rng.random() < 0.5 else None
        if not passage:
            # a home: towns sell some of these to travellers
            g.mark("dwelling", door[0], GL, door[-1])
            # a bed and a chest on the ground floor
            bx, bz = (x0 + 1, z1 - 1) if dz > 0 else (x1 - 1, z0 + 1)
            g.bed(bx, GL, bz, "east" if bx == x0 + 1 else "west", rng.choice(BED_COLORS))
            g.barrel(x1 - 1 if dz > 0 else x0 + 1, GL, z1 - 1 if dz > 0 else z0 + 1, LOOT + "town_house")
    # upper floors: a bed each
    for s in range(1, storeys):
        y = GL + sh * s
        bx, bz = (jx0 + 1, jz1 - 1) if dz > 0 else (jx1 - 1, jz0 + 1)
        g.bed(bx, y, bz, "east" if bx == jx0 + 1 else "west", rng.choice(BED_COLORS))
        g.lantern(jx0 + 2, y + sh - 2, jz0 + 2)
    g.lantern(x0 + 2, GL + sh - 2, z0 + 2)
    # ---- the timber frame of the ground storey ----
    if FACADES:
        yb = GL + sh - 1

        def infill(st):
            return st not in (AIR, "<void>") and not any(k in st for k in ("door", "glass", "bars", "fence", "trapdoor", "sign",
                                                                           "lantern", "pot", "slab", "stairs", "torch"))
        for f in faces:
            shop = f == street and trade and not passage
            if f in ("north", "south"):
                zz = z0 if f == "north" else z1
                for x in range(x0, x1 + 1):
                    if infill(g.get(x, yb, zz)):
                        g.set(x, yb, zz, f"{BEAM}[axis=x]")
                for x in sorted(set(list(range(x0, x1 + 1, 3)) + [x1])):
                    if shop and x0 < x < x1:
                        continue
                    for y in range(GL, yb):
                        if infill(g.get(x, y, zz)):
                            g.set(x, y, zz, f"{TIMBER}[axis=y]")
            else:
                xx = x0 if f == "west" else x1
                for z in range(z0, z1 + 1):
                    if infill(g.get(xx, yb, z)):
                        g.set(xx, yb, z, f"{BEAM}[axis=z]")
                for z in sorted(set(list(range(z0, z1 + 1, 3)) + [z1])):
                    if shop and z0 < z < z1:
                        continue
                    for y in range(GL, yb):
                        if infill(g.get(xx, y, z)):
                            g.set(xx, y, z, f"{TIMBER}[axis=y]")
    # ---- roof: gable, ridge along the party-wall direction so neighbours' roofs line up ----
    axis = "x" if along_x else "z"
    fill = ROOF_FILL.get(roof, "minecraft:deepslate_tiles")
    rtop = gable_roof(g, jx0, jz0, jx1, jz1, top, axis, roof, wall, overhang=1)
    # dormer on the street slope for tall houses
    if storeys >= 3 and rng.random() < 0.6:
        if along_x:
            mx = (jx0 + jx1) // 2
            zz = jz1 if dz > 0 else jz0
            for y in (top, top + 1):
                g.fill(mx - 1, y, zz, mx + 1, y, zz, wall)
            g.fill(mx, top, zz, mx, top + 1, zz, GLASS)
            g.fill(mx - 1, top + 2, zz - 1, mx + 1, top + 2, zz + 1, fill)
        else:
            mz = (jz0 + jz1) // 2
            xx = jx1 if dx > 0 else jx0
            for y in (top, top + 1):
                g.fill(xx, y, mz - 1, xx, y, mz + 1, wall)
            g.fill(xx, top, mz, xx, top + 1, mz, GLASS)
            g.fill(xx - 1, top + 2, mz - 1, xx + 1, top + 2, mz + 1, fill)
    # ---- chimney with cap and smoke ----
    if chim:
        cx_, cz_ = chim
        g.fill(cx_, GL, cz_, cx_, rtop + 1, cz_, "minecraft:bricks")
        g.set(cx_, rtop + 2, cz_, "minecraft:brick_wall")
        g.set(cx_, rtop + 3, cz_, "minecraft:campfire[lit=true,signal_fire=false]")
    # ---- balcony or flower box on an upper storey, street side ----
    if storeys >= 2 and rng.random() < 0.35:
        y = GL + sh * (storeys - 1)
        if along_x:
            zz = (jz1 if dz > 0 else jz0) + dz
            bx = (jx0 + jx1) // 2
            g.fill(bx - 1, y - 1, zz, bx + 1, y - 1, zz, "minecraft:dark_oak_slab[type=top]")
            g.fill(bx - 1, y, zz, bx + 1, y, zz, "minecraft:dark_oak_fence")
            g.fill(bx, y, zz - dz, bx, y + 1, zz - dz, AIR)
        else:
            xx = (jx1 if dx > 0 else jx0) + dx
            bz = (jz0 + jz1) // 2
            g.fill(xx, y - 1, bz - 1, xx, y - 1, bz + 1, "minecraft:dark_oak_slab[type=top]")
            g.fill(xx, y, bz - 1, xx, y, bz + 1, "minecraft:dark_oak_fence")
            g.fill(xx - dx, y, bz, xx - dx, y + 1, bz, AIR)
    return (door[0], GL, door[1])


def terrace(g, a0, a1, front, depth, GL, rng, street, trades=(), lot=(5, 9), passage_at=None):
    """
    A contiguous row of party-wall houses along the `street` face. `a0..a1` is the along-street span,
    `front` the coordinate of the street-facing wall, `depth` how far back the houses go.
    `trades` is a list of trade names to hand out to shops (None = dwelling). Returns door cells.
    """
    along_x = street in ("north", "south")
    dx, dz = _dir(street)
    doors = []
    a = a0
    i = 0
    while a + lot[0] - 1 <= a1:
        w = int(rng.integers(lot[0], lot[1] + 1))
        b = min(a + w - 1, a1)
        if a1 - b < lot[0]:            # don't leave a stub: stretch the last house
            b = a1
        if along_x:
            z0, z1 = (front, front + depth - 1) if dz < 0 else (front - depth + 1, front)
            box = (a, z0, b, z1)
        else:
            x0, x1 = (front, front + depth - 1) if dx < 0 else (front - depth + 1, front)
            box = (x0, a, x1, b)
        trade = trades[i % len(trades)] if trades else None
        if trade == "none": trade = None
        passage = passage_at is not None and a <= passage_at <= b
        d = townhouse(g, *box, GL, rng, street, trade=trade, left_party=a > a0, right_party=b < a1, passage=passage)
        doors.append(d)
        a = b + 1
        i += 1
    return doors


def dense_block(g, x0, z0, x1, z1, GL, rng, depth=8, sides=("north", "south", "west", "east"), trades=None,
                court=True, alley=None):
    """
    A city block packed solid: terraces on every street side, an alley or light-well court in the middle
    with a well, and a covered passage through one terrace to reach it. `alley` = ('x', z) or ('z', x)
    cuts a 3-wide lane through the block first, splitting it in two.
    """
    doors = []
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, ALLEY)
    if alley:
        ax, pos = alley
        if ax == "x":
            doors += dense_block(g, x0, z0, x1, pos - 2, GL, rng, depth, sides, trades, court)
            doors += dense_block(g, x0, pos + 2, x1, z1, GL, rng, depth, sides, trades, court)
            lane(g, x0, pos - 1, x1, pos + 1, GL, rng)
        else:
            doors += dense_block(g, x0, z0, pos - 2, z1, GL, rng, depth, sides, trades, court)
            doors += dense_block(g, pos + 2, z0, x1, z1, GL, rng, depth, sides, trades, court)
            lane(g, pos - 1, z0, pos + 1, z1, GL, rng)
        return doors
    W, D = x1 - x0 + 1, z1 - z0 + 1
    d_ = min(depth, (min(W, D) - 3) // 2) if len(sides) > 1 else depth
    d_ = max(5, d_)
    tr = trades or ["none"]
    # which terrace carries the passage to the court: the longest street side
    pas = rng.choice([s for s in sides]) if court else None
    if "north" in sides:
        doors += terrace(g, x0, x1, z0, d_, GL, rng, "north", trades=tr, passage_at=(x0 + x1) // 2 if pas == "north" else None)
    if "south" in sides:
        doors += terrace(g, x0, x1, z1, d_, GL, rng, "south", trades=tr, passage_at=(x0 + x1) // 2 if pas == "south" else None)
    zc0 = z0 + (d_ if "north" in sides else 0)
    zc1 = z1 - (d_ if "south" in sides else 0)
    if "west" in sides and zc1 - zc0 >= 5:
        doors += terrace(g, zc0, zc1, x0, d_, GL, rng, "west", trades=tr, passage_at=(zc0 + zc1) // 2 if pas == "west" else None)
    if "east" in sides and zc1 - zc0 >= 5:
        doors += terrace(g, zc0, zc1, x1, d_, GL, rng, "east", trades=tr, passage_at=(zc0 + zc1) // 2 if pas == "east" else None)
    # the court
    cx0 = x0 + (d_ if "west" in sides else 0)
    cx1 = x1 - (d_ if "east" in sides else 0)
    if court and cx1 - cx0 >= 2 and zc1 - zc0 >= 2:
        g.fill(cx0, GL, zc0, cx1, GL + 12, zc1, AIR)
        g.fill(cx0, GL - 1, zc0, cx1, GL - 1, zc1, ALLEY)
        yx, yz = (cx0 + cx1) // 2, (zc0 + zc1) // 2
        if cx1 - cx0 >= 4 and zc1 - zc0 >= 4:
            well(g, yx, yz, GL)
        else:
            g.set(yx, GL, yz, "minecraft:composter")
            g.set(yx, GL + 1, yz, "minecraft:lantern[hanging=false]")
        # washing lines across the court
        if cx1 - cx0 >= 3:
            for z in range(zc0 + 1, zc1, 3):
                g.fill(cx0, GL + 6, z, cx1, GL + 6, z, "minecraft:chain[axis=x]")
                for x in range(cx0 + 1, cx1, 2):
                    g.set(x, GL + 5, z, f"minecraft:{rng.choice(AWNING_COLORS)}_wall_banner[facing=south]")
    return doors


def lane(g, x0, z0, x1, z1, GL, rng):
    """A back lane: paving, a lantern now and then, a bridge between the houses above it."""
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, ALLEY)
    g.fill(x0, GL, z0, x1, GL + 12, z1, AIR)
    along_x = (x1 - x0) >= (z1 - z0)
    n = (x1 - x0 if along_x else z1 - z0) + 1
    for k in range(4, n - 2, 9):
        if along_x:
            x = x0 + k
            bridge(g, x, x + 1, z0 - 1, z1 + 1, GL + 8, rng, axis="z")
        else:
            z = z0 + k
            bridge(g, x0 - 1, x1 + 1, z, z + 1, GL + 8, rng, axis="x")
    for k in range(2, n - 1, 7):
        if along_x:
            g.set(x0 + k, GL + 3, z0, "minecraft:lantern[hanging=true]") if g.get(x0 + k, GL + 4, z0) != AIR else None
        else:
            g.set(x0, GL + 3, z0 + k, "minecraft:lantern[hanging=true]") if g.get(x0, GL + 4, z0 + k) != AIR else None


def bridge(g, x0, x1, z0, z1, y, rng, axis):
    """An enclosed timber bridge joining the upper floors on either side of a lane."""
    g.fill(x0, y - 1, z0, x1, y - 1, z1, DARK_PLANK)
    g.fill(x0, y, z0, x1, y + 2, z1, PLASTER)
    g.fill(x0, y + 3, z0, x1, y + 3, z1, "minecraft:dark_oak_slab[type=bottom]")
    if axis == "z":
        g.fill(x0, y, z0 + 1, x1, y + 2, z1 - 1, AIR)
        g.fill(x0, y, z0 + 1, x1, y + 2, z1 - 1, AIR)
        for z in range(z0 + 1, z1):
            g.set(x0 - 1, y + 1, z, GLASS) if False else None
        g.fill(x0 - 1, y + 1, z0 + 1, x0 - 1, y + 1, z1 - 1, GLASS) if g.get(x0 - 1, y + 1, z0 + 1) == AIR else None
        g.fill(x0, y - 2, z0 + 1, x1, y - 2, z1 - 1, AIR)
        for z in (z0 + 1, z1 - 1):
            g.stair(x0, y - 2, z, "minecraft:dark_oak_stairs", "north" if z == z0 + 1 else "south", half="top")
    else:
        g.fill(x0 + 1, y, z0, x1 - 1, y + 2, z1, AIR)
        for x in (x0 + 1, x1 - 1):
            g.stair(x, y - 2, z0, "minecraft:dark_oak_stairs", "west" if x == x0 + 1 else "east", half="top")
    # open the house walls at either end
    if axis == "z":
        g.fill(x0, y, z0 - 1, x1, y + 1, z0 - 1, AIR); g.fill(x0, y, z1 + 1, x1, y + 1, z1 + 1, AIR)
    else:
        g.fill(x0 - 1, y, z0, x0 - 1, y + 1, z1, AIR); g.fill(x1 + 1, y, z0, x1 + 1, y + 1, z1, AIR)


def well(g, x, z, GL):
    g.fill(x - 1, GL - 3, z - 1, x + 1, GL - 1, z + 1, "minecraft:stone_bricks")
    g.set(x, GL - 1, z, "minecraft:water"); g.set(x, GL - 2, z, "minecraft:water")
    g.set(x, GL, z, AIR)
    for (a, b) in ((x - 1, z - 1), (x + 1, z - 1), (x - 1, z + 1), (x + 1, z + 1)):
        g.fill(a, GL, b, a, GL + 2, b, "minecraft:dark_oak_fence")
    g.fill(x - 1, GL + 3, z - 1, x + 1, GL + 3, z + 1, "minecraft:dark_oak_slab[type=bottom]")
    g.set(x, GL + 2, z, "minecraft:chain[axis=y]")
    g.set(x, GL + 1, z, "minecraft:cauldron")


def stall(g, x, z, GL, rng, facing="south", trade=None):
    """A 3x3 market stall: four posts, a coloured awning, a counter with goods."""
    t = TRADES[trade] if trade else None
    col = (t and t["awning"]) or rng.choice(AWNING_COLORS)
    for (a, b) in ((x - 1, z - 1), (x + 1, z - 1), (x - 1, z + 1), (x + 1, z + 1)):
        g.fill(a, GL, b, a, GL + 2, b, "minecraft:spruce_fence")
    g.fill(x - 1, GL + 3, z - 1, x + 1, GL + 3, z + 1, f"minecraft:{col}_wool")
    # a pitched awning: slabs stepping up to the middle
    g.fill(x - 1, GL + 4, z - 1, x + 1, GL + 4, z + 1, f"minecraft:{col}_carpet")
    dx, dz = FACING_VEC[facing]
    items = list(t["items"]) if t else GOODS
    if dz:
        cz = z + dz
        for a in (x - 1, x, x + 1):
            g.set(a, GL, cz, f"minecraft:spruce_trapdoor[facing={facing},open=false,half=top]")
        g.set(x - 1, GL, z, items[int(rng.integers(len(items)))]); g.set(x + 1, GL, z, items[int(rng.integers(len(items)))])
        g.set(x, GL, z - dz, "minecraft:barrel[facing=up]")
    else:
        cx = x + dx
        for b in (z - 1, z, z + 1):
            g.set(cx, GL, b, f"minecraft:spruce_trapdoor[facing={facing},open=false,half=top]")
        g.set(x, GL, z - 1, items[int(rng.integers(len(items)))]); g.set(x, GL, z + 1, items[int(rng.integers(len(items)))])
        g.set(x - dx, GL, z, "minecraft:barrel[facing=up]")
    g.set(x, GL + 2, z, "minecraft:lantern[hanging=true]")
    if trade:
        g.mark("shop_" + trade, x, GL, z)
        rot = {"south": 0, "west": 4, "north": 8, "east": 12}[facing]
        g.set(x + dx, GL + 2, z + dz, f"minecraft:spruce_hanging_sign[rotation={rot},attached=false]", nbt=sign_nbt(t["sign"]))


def arcade(g, x0, z0, x1, z1, GL, rng, inward, trades=()):
    """
    A covered colonnade along one edge of a square: piers every 3 blocks, a slab roof at GL+4, goods and
    stalls under it, a balustrade above (the houses' upper floors use it as a gallery).
    `inward` is the facing from the arcade toward the square. The arcade is 3 deep.
    """
    along_x = inward in ("north", "south")
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, "minecraft:polished_andesite")
    g.fill(x0, GL, z0, x1, GL + 3, z1, AIR)
    g.fill(x0, GL + 4, z0, x1, GL + 4, z1, "minecraft:stone_bricks")
    g.fill(x0, GL + 5, z0, x1, GL + 5, z1, "minecraft:stone_brick_wall")
    # outer piers with arches
    if along_x:
        zz = z1 if inward == "south" else z0
        for x in range(x0, x1 + 1, 3):
            g.fill(x, GL, zz, x, GL + 3, zz, "minecraft:stone_bricks")
            g.stair(x + 1, GL + 3, zz, "minecraft:stone_brick_stairs", "east", half="top") if x + 1 <= x1 else None
            g.stair(x - 1, GL + 3, zz, "minecraft:stone_brick_stairs", "west", half="top") if x - 1 >= x0 else None
        bz = z0 + 1 if inward == "south" else z1 - 1
        k = 0
        for x in range(x0 + 1, x1 - 1, 3):
            if rng.random() < 0.7:
                tr = trades[k % len(trades)] if trades else None
                items = TRADES[tr]["items"] if tr else GOODS
                g.set(x, GL, bz, items[int(rng.integers(len(items)))])
                g.set(x + 1, GL, bz, f"minecraft:spruce_trapdoor[facing={inward},open=false,half=top]")
                k += 1
        for x in range(x0 + 2, x1, 6):
            g.set(x, GL + 3, zz - (1 if inward == "south" else -1), "minecraft:lantern[hanging=true]")
    else:
        xx = x1 if inward == "east" else x0
        for z in range(z0, z1 + 1, 3):
            g.fill(xx, GL, z, xx, GL + 3, z, "minecraft:stone_bricks")
            g.stair(xx, GL + 3, z + 1, "minecraft:stone_brick_stairs", "south", half="top") if z + 1 <= z1 else None
            g.stair(xx, GL + 3, z - 1, "minecraft:stone_brick_stairs", "north", half="top") if z - 1 >= z0 else None
        bx = x0 + 1 if inward == "east" else x1 - 1
        k = 0
        for z in range(z0 + 1, z1 - 1, 3):
            if rng.random() < 0.7:
                tr = trades[k % len(trades)] if trades else None
                items = TRADES[tr]["items"] if tr else GOODS
                g.set(bx, GL, z, items[int(rng.integers(len(items)))])
                g.set(bx, GL, z + 1, f"minecraft:spruce_trapdoor[facing={inward},open=false,half=top]")
                k += 1
        for z in range(z0 + 2, z1, 6):
            g.set(xx - (1 if inward == "east" else -1), GL + 3, z, "minecraft:lantern[hanging=true]")


def stall_cluster(g, x0, z0, x1, z1, GL, rng, trades=()):
    """Stalls packed in staggered rows with 1-wide lanes between."""
    k = 0
    for z in range(z0 + 1, z1 - 1, 4):
        off = 2 if ((z - z0) // 4) % 2 else 0
        for x in range(x0 + 1 + off, x1 - 1, 4):
            tr = trades[k % len(trades)] if trades else None
            stall(g, x, z, GL, rng, facing=rng.choice(["north", "south"]), trade=tr)
            k += 1
