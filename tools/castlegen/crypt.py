"""Crypts under the keep.

Every holdfast and great castle (and the city) gets a vaulted crypt dug into the foundation under its
keep: a ladder down from a ground-floor room, a hall of tombs along both walls with candles on the lids,
alcoves of skulls between them, and at the far end the founder's tomb on a dais between the house's
banners, with the family's treasure behind it. The temper colours it: soul fire and chains for a grim
house, candles everywhere for a pious one, gold on the founder's lid for a rich one.
"""
import numpy as np

import furnish
from grid import AIR

WALL = "minecraft:deepslate_bricks"
WALL_MIX = ("minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:polished_deepslate")
FLOOR_A = "minecraft:polished_deepslate"
FLOOR_B = "minecraft:cobbled_deepslate"
VAULT = "minecraft:deepslate_brick_stairs"
TOMB = "minecraft:polished_deepslate"
LID = "minecraft:chiseled_deepslate"
LOOT = "stormhold:chests/"

HEIGHT = 3        # air blocks from the crypt floor to its ceiling: the room floor above is the vault's cap
REACH = 16        # how far from the lord's seat the crypt may spread


def _lord(g):
    m = g.markers.get("lord")
    return m[0] if m else None


def _solid_box(g, x0, y0, z0, x1, y1, z1):
    """True if every cell of the box is solid rock (nothing built there, no water, no air)."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            for y in range(y0, y1 + 1):
                if not g.is_solid(x, y, z) or g.get(x, y, z) in ("minecraft:ladder[facing=west]",):
                    return False
    return True


def _entrance(g, GL, lx, lz, protected):
    """A floor cell near the seat, on the ground floor or a step or two up, against a wall the ladder can hang on.
    Returns (score, x, z, dx, dz, ladder facing, floor y)."""
    best = None
    doorish = set()
    rooms = furnish.find_rooms(g, GL, protected)
    for room in rooms:
        if GL <= room.y <= GL + 2:
            doorish |= room.doorways
    for y in (GL, GL + 1, GL + 2):
        for x in range(max(2, lx - REACH), min(g.X - 2, lx + REACH + 1)):
            for z in range(max(2, lz - REACH), min(g.Z - 2, lz + REACH + 1)):
                if (x, z) in protected or (x, z) in doorish:
                    continue
                if g.get(x, y, z) != "minecraft:air" or g.get(x, y + 1, z) != "minecraft:air" or not g.is_solid(x, y - 1, z):
                    continue
                for (dx, dz, facing) in ((1, 0, "west"), (-1, 0, "east"), (0, 1, "north"), (0, -1, "south")):
                    if not (g.is_solid(x + dx, y, z + dz) and g.is_solid(x + dx, y + 1, z + dz) and g.is_solid(x + dx, y + 2, z + dz)):
                        continue
                    # a wall, not a post or a table: its neighbours along the wall are solid too
                    px, pz = (0, 1) if dx else (1, 0)
                    if not (g.is_solid(x + dx + px, y, z + dz + pz) and g.is_solid(x + dx - px, y, z + dz - pz)):
                        continue
                    # and the other three sides open, so the hatch doesn't sit in a doorway
                    opens = sum(1 for (ox, oz) in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (ox, oz) != (dx, dz)
                                and g.get(x + ox, y, z + oz) == "minecraft:air")
                    if opens < 3:
                        continue
                    score = -abs(x - lx) - abs(z - lz) - 20 * (y - GL)
                    if best is None or score > best[0]:
                        best = (score, x, z, dx, dz, facing, y)
    return best


def dig(g, GL, character, tier=2, keep_gl=None):
    """Carve the crypt. Returns a dict of counts, or None if there was no room for one."""
    lord = _lord(g)
    if lord is None:
        return None
    KGL = GL if keep_gl is None else keep_gl
    lx, _, lz = lord
    # the crypt sits in the foundation right under the keep's floor: floor slab, three of air, and the room's
    # own floor for a ceiling (made good in stone, so no gravel paving drops in)
    CY = KGL - 1 - HEIGHT
    if CY - 1 < 0:
        return None
    rng = g.rng
    protected = getattr(g, "protected_xz", set())
    ent = _entrance(g, KGL, lx, lz, protected)
    if ent is None:
        return None
    _, ex, ez, dx, dz, facing, EY = ent
    # the hall runs along the castle's longer axis, under the seat, and the ladder comes down inside it
    along_x = g.X >= g.Z
    half_w = 3
    length = 0
    hall = None
    for L in range(10, 2 * REACH + 1, 2):
        hl = L // 2
        if along_x:
            cx = min(max(lx, ex - hl + 2), ex + hl - 2)
            box = (cx - hl, ez - half_w, cx + hl, ez + half_w)
        else:
            cz = min(max(lz, ez - hl + 2), ez + hl - 2)
            box = (ex - half_w, cz - hl, ex + half_w, cz + hl)
        x0, z0, x1, z1 = box
        if x0 < 2 or z0 < 2 or x1 > g.X - 3 or z1 > g.Z - 3:
            break
        if not _solid_box(g, x0 - 1, CY - 1, z0 - 1, x1 + 1, KGL - 1, z1 + 1):
            break
        length = L
        hall = box
    if length < 10:
        return None
    x0, z0, x1, z1 = hall

    # ---- the hall: floor, walls, vault
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            g.set(x, CY - 1, z, FLOOR_A if (x + z) % 2 == 0 else FLOOR_B)
            for y in range(CY, CY + HEIGHT):
                g.set(x, y, z, "minecraft:air")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            edge = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
            if edge:
                for y in range(CY - 1, CY + HEIGHT):
                    g.set(x, y, z, WALL_MIX[int(rng.integers(0, 10)) % 3] if rng.integers(0, 3) == 0 else WALL)
            else:
                g.set(x, CY + HEIGHT, z, WALL)      # the ceiling is the room floor above, remade in stone
    # a vault: upside-down stairs along the top of the long walls, a rib every fourth cell
    top = CY + HEIGHT - 1
    if along_x:
        for x in range(x0, x1 + 1):
            g.set(x, top, z0, f"{VAULT}[facing=south,half=top]")
            g.set(x, top, z1, f"{VAULT}[facing=north,half=top]")
            if (x - x0) % 4 == 2:
                for z in range(z0 + 1, z1):
                    g.set(x, top, z, "minecraft:deepslate_brick_slab[type=top]")
    else:
        for z in range(z0, z1 + 1):
            g.set(x0, top, z, f"{VAULT}[facing=east,half=top]")
            g.set(x1, top, z, f"{VAULT}[facing=west,half=top]")
            if (z - z0) % 4 == 2:
                for x in range(x0 + 1, x1):
                    g.set(x, top, z, "minecraft:deepslate_brick_slab[type=top]")

    # ---- the ladder shaft from the room above
    for y in range(CY, EY):
        g.set(ex, y, ez, f"minecraft:ladder[facing={facing}]")
        if not g.is_solid(ex + dx, y, ez + dz):
            g.set(ex + dx, y, ez + dz, WALL)
    # the room's floor around the hatch gets a lip so nobody walks into it in the dark
    for (ox, oz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if (ox, oz) == (dx, dz):
            continue
        if g.get(ex + ox, EY, ez + oz) == "minecraft:air" and g.is_solid(ex + ox, EY - 1, ez + oz):
            g.set(ex + ox, EY, ez + oz, "minecraft:deepslate_brick_wall")
    # the ladder lands in the hall: nothing may stand on the cell below it or beside it
    reserved = {(ex, ez), (ex + dx, ez + dz)}
    for (ox, oz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        reserved.add((ex + ox, ez + oz))

    # ---- tombs along both long walls, alcoves of skulls between them
    tombs = 0
    skulls = 0
    grim = character == "grim"
    pious = character == "pious"
    rich = character == "rich"
    lamp = "minecraft:soul_lantern[hanging=true]" if grim else "minecraft:lantern[hanging=true]"
    if along_x:
        far_end = x1 if (lx - x0) < (x1 - lx) else x0       # the founder lies at the end away from the ladder
        axis = [(x, None) for x in range(x0 + 1, x1 - 4)]
    else:
        far_end = z1 if (lz - z0) < (z1 - lz) else z0
        axis = [(None, z) for z in range(z0 + 1, z1 - 4)]

    dais_len = 5
    # tombs run from the ladder's end of the hall up to the gate before the dais
    if along_x:
        t0, t1 = (x0 + 1, x1 - dais_len - 2) if far_end == x1 else (x0 + dais_len + 2, x1 - 1)
    else:
        t0, t1 = (z0 + 1, z1 - dais_len - 2) if far_end == z1 else (z0 + dais_len + 2, z1 - 1)

    def side_cells(i, side):
        """Three cells of a tomb along the wall at position i, on one side."""
        if along_x:
            z = z0 if side == 0 else z1
            return [(t0 + i + k, z) for k in range(3)]
        x = x0 if side == 0 else x1
        return [(x, t0 + i + k) for k in range(3)]

    usable = t1 - t0 + 1
    i = 0
    while i + 3 <= usable:
        for side in (0, 1):
            cells = side_cells(i, side)
            if any(c in reserved for c in cells):
                continue
            # a tomb: a long block of polished stone with a carved lid at the head
            for k, (cx, cz) in enumerate(cells):
                g.set(cx, CY, cz, LID if k == 0 else TOMB)
            hx, hz = cells[0]
            if pious or rng.integers(0, 2) == 0:
                g.set(hx, CY + 1, hz, "minecraft:candle[candles=2,lit=true]" if pious else "minecraft:candle[candles=1,lit=true]")
            tombs += 1
            # an alcove in the wall behind the tomb's foot, with a skull on its shelf
            fx, fz = cells[2]
            wx, wz = (fx, fz + (-1 if side == 0 else 1)) if along_x else (fx + (-1 if side == 0 else 1), fz)
            if g.is_solid(wx, CY + 2, wz) and _solid_box(g, wx, CY + 1, wz, wx, CY + 2, wz):
                g.set(wx, CY + 2, wz, "minecraft:air")
                g.set(wx, CY + 1, wz, "minecraft:polished_deepslate_slab[type=bottom]")
                rot = int(rng.integers(0, 16))
                g.set(wx, CY + 2, wz, f"minecraft:skeleton_skull[rotation={rot}]" if not grim or rng.integers(0, 3) else f"minecraft:wither_skeleton_skull[rotation={rot}]")
                skulls += 1
        # lanterns down the middle of the vault
        if along_x:
            if (t0 + 1 + i, (z0 + z1) // 2) not in reserved:
                g.set(t0 + 1 + i, top, (z0 + z1) // 2, lamp)
        else:
            if ((x0 + x1) // 2, t0 + 1 + i) not in reserved:
                g.set((x0 + x1) // 2, top, t0 + 1 + i, lamp)
        if grim and rng.integers(0, 2) == 0:
            # chains from the vault
            if along_x:
                g.set(t0 + i, top, z0 + 1, "minecraft:chain[axis=y]"); g.set(t0 + i, top - 1, z0 + 1, "minecraft:chain[axis=y]")
            else:
                g.set(x0 + 1, top, t0 + i, "minecraft:chain[axis=y]"); g.set(x0 + 1, top - 1, t0 + i, "minecraft:chain[axis=y]")
        i += 4

    # ---- an iron gate across the hall before the founder's end
    if along_x:
        gx = far_end - dais_len - 1 if far_end == x1 else far_end + dais_len + 1
        for z in range(z0, z1 + 1):
            if (gx, z) in reserved:
                continue
            g.set(gx, CY, z, "minecraft:iron_bars" if z != (z0 + z1) // 2 else "minecraft:iron_door[facing=east,half=lower,hinge=left,open=true]")
            g.set(gx, CY + 1, z, "minecraft:iron_bars" if z != (z0 + z1) // 2 else "minecraft:iron_door[facing=east,half=upper,hinge=left,open=true]")
            g.set(gx, CY + 2, z, "minecraft:iron_bars")
    else:
        gz = far_end - dais_len - 1 if far_end == z1 else far_end + dais_len + 1
        for x in range(x0, x1 + 1):
            if (x, gz) in reserved:
                continue
            g.set(x, CY, gz, "minecraft:iron_bars" if x != (x0 + x1) // 2 else "minecraft:iron_door[facing=south,half=lower,hinge=left,open=true]")
            g.set(x, CY + 1, gz, "minecraft:iron_bars" if x != (x0 + x1) // 2 else "minecraft:iron_door[facing=south,half=upper,hinge=left,open=true]")
            g.set(x, CY + 2, gz, "minecraft:iron_bars")

    # ---- the founder's tomb on a dais at the far end, banners either side, the treasure behind
    if along_x:
        dx0, dx1 = (far_end - dais_len + 1, far_end) if far_end == x1 else (far_end, far_end + dais_len - 1)
        for x in range(dx0, dx1 + 1):
            for z in range(z0, z1 + 1):
                g.set(x, CY, z, "minecraft:polished_deepslate_slab[type=bottom]")
        mz = (z0 + z1) // 2
        tx0 = dx0 + 1
        for x in range(tx0, tx0 + 3):
            g.set(x, CY, mz, TOMB); g.set(x, CY + 1, mz, LID if x == tx0 else TOMB)
        if rich:
            g.set(tx0 + 1, CY + 1, mz, "minecraft:gold_block")
        g.set(tx0, CY + 2, mz, "minecraft:candle[candles=3,lit=true]")
        g.set(tx0 + 2, CY + 2, mz, "minecraft:candle[candles=3,lit=true]")
        g.wall_banner(tx0 + 1, CY + 2, z0, "south"); g.wall_banner(tx0 + 1, CY + 2, z1, "north")
        chest_x = far_end
        g.chest(chest_x, CY + 1, mz, "west" if far_end == x1 else "east",
                LOOT + ("castle_treasury" if tier >= 3 else "holdfast_treasury"))
        g.set(chest_x, CY + 1, mz - 1, "minecraft:skeleton_skull[rotation=%d]" % (4 if far_end == x1 else 12))
        g.set(chest_x, CY + 1, mz + 1, "minecraft:skeleton_skull[rotation=%d]" % (4 if far_end == x1 else 12))
        g.mark("crypt", dx0 - 3 if far_end == x1 else dx1 + 3, CY, mz)     # just inside the gate, before the dais
    else:
        dz0, dz1 = (far_end - dais_len + 1, far_end) if far_end == z1 else (far_end, far_end + dais_len - 1)
        for z in range(dz0, dz1 + 1):
            for x in range(x0, x1 + 1):
                g.set(x, CY, z, "minecraft:polished_deepslate_slab[type=bottom]")
        mx = (x0 + x1) // 2
        tz0 = dz0 + 1
        for z in range(tz0, tz0 + 3):
            g.set(mx, CY, z, TOMB); g.set(mx, CY + 1, z, LID if z == tz0 else TOMB)
        if rich:
            g.set(mx, CY + 1, tz0 + 1, "minecraft:gold_block")
        g.set(mx, CY + 2, tz0, "minecraft:candle[candles=3,lit=true]")
        g.set(mx, CY + 2, tz0 + 2, "minecraft:candle[candles=3,lit=true]")
        g.wall_banner(x0, CY + 2, tz0 + 1, "east"); g.wall_banner(x1, CY + 2, tz0 + 1, "west")
        chest_z = far_end
        g.chest(mx, CY + 1, chest_z, "north" if far_end == z1 else "south",
                LOOT + ("castle_treasury" if tier >= 3 else "holdfast_treasury"))
        g.set(mx - 1, CY + 1, chest_z, "minecraft:skeleton_skull[rotation=%d]" % (0 if far_end == z1 else 8))
        g.set(mx + 1, CY + 1, chest_z, "minecraft:skeleton_skull[rotation=%d]" % (0 if far_end == z1 else 8))
        g.mark("crypt", mx, CY, dz0 - 3 if far_end == z1 else dz1 + 3)
    # cobwebs in the corners
    for (cx, cz) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        if (cx, cz) not in reserved and g.get(cx, CY + HEIGHT - 1, cz) == "minecraft:air":
            g.set(cx, CY + HEIGHT - 1, cz, "minecraft:cobweb")
    # the shaft is nobody's room
    g.__dict__.setdefault("protected_xz", set()).add((ex, ez))
    return {"crypt_tombs": tombs, "crypt_skulls": skulls, "crypt_len": length}
