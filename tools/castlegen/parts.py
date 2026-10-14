"""Reusable castle parts: materials, curtain walls, towers, buildings."""
import numpy as np

from grid import (Mix, disk, ring, rect, rect_ring, angle_index, facing_to_center_grid,
                  spiral_stair, gable_roof, cone_roof, FACING_VEC, OPPOSITE)

STONE = Mix(("minecraft:stone_bricks", 50), ("minecraft:cracked_stone_bricks", 12),
            ("minecraft:mossy_stone_bricks", 5), ("minecraft:cobblestone", 9),
            ("minecraft:andesite", 10), ("minecraft:stone", 9), ("minecraft:tuff", 5))
DARK = Mix(("minecraft:deepslate_bricks", 45), ("minecraft:cracked_deepslate_bricks", 10),
           ("minecraft:cobbled_deepslate", 30), ("minecraft:polished_deepslate", 15))
FOUND = Mix(("minecraft:cobblestone", 40), ("minecraft:stone", 40), ("minecraft:andesite", 20))
PAVE = Mix(("minecraft:cobblestone", 30), ("minecraft:gravel", 14), ("minecraft:stone", 10),
           ("minecraft:andesite", 16), ("minecraft:coarse_dirt", 12), ("minecraft:packed_mud", 8),
           ("minecraft:mossy_cobblestone", 6), ("minecraft:dirt_path", 4))
ROAD = Mix(("minecraft:stone_bricks", 40), ("minecraft:polished_andesite", 25),
           ("minecraft:andesite", 20), ("minecraft:cracked_stone_bricks", 15))
FLOOR_STONE = Mix(("minecraft:polished_andesite", 50), ("minecraft:stone_bricks", 50))
ROOF = "minecraft:deepslate_tiles"
ROOF_STAIR = "minecraft:deepslate_tile_stairs"
STONE_STAIR = "minecraft:stone_brick_stairs"
DARK_STAIR = "minecraft:deepslate_brick_stairs"
SLAB_CAP = "minecraft:stone_brick_slab[type=bottom]"
RAIL = "minecraft:stone_brick_wall"
PLANK = "minecraft:spruce_planks"
DARK_PLANK = "minecraft:dark_oak_planks"
PORTCULLIS = "stormhold:portcullis"
GLASS = "minecraft:gray_stained_glass_pane"


def side_grids(g, a, b):
    """For a square wall from a..b: distance from outer face d, along-wall coord, outward facing."""
    xs, zs = g.xz()
    xs = xs + 0 * zs
    zs = zs + 0 * xs
    dn, ds, dw, de = zs - a, b - zs, xs - a, b - xs
    stack = np.stack([dn, ds, dw, de])
    side = np.argmin(stack, axis=0)  # 0 N,1 S,2 W,3 E
    d = np.min(stack, axis=0)
    along = np.where(side < 2, xs, zs)
    out = np.empty(d.shape, dtype=object)
    out[side == 0] = "north"; out[side == 1] = "south"; out[side == 2] = "west"; out[side == 3] = "east"
    return d, along, out


def stairs_by_facing(g, mask, y, block, facing_grid, half="bottom", invert=False):
    for f in ("north", "south", "east", "west"):
        m = mask & (facing_grid == f)
        ff = OPPOSITE[f] if invert else f
        g.fill_mask(m, y, y, f"{block}[facing={ff},half={half}]")


def curtain(g, a, b, thick, walk_y, GL, gallery=True, banners_every=16):
    """Square curtain wall. Outer face at a/b. Walkway standing level walk_y."""
    d, along, out = side_grids(g, a, b)
    body = (d >= 0) & (d < thick)
    g.fill_mask(body, 0, walk_y - 1, STONE)
    # string courses
    g.fill_mask(body & (d == 0), GL + 3, GL + 3, DARK)
    g.fill_mask(body & (d == 0), walk_y - 6, walk_y - 6, DARK)
    # battered plinth (stairs face inward = opposite of outward)
    g.fill_mask(d == -1, 0, GL + 1, DARK)
    stairs_by_facing(g, d == -1, GL + 2, DARK_STAIR, out, invert=True)
    g.fill_mask(d == -2, 0, GL - 1, DARK)
    stairs_by_facing(g, d == -2, GL, DARK_STAIR, out, invert=True)
    # mural gallery with arrow slits
    gy = GL + 9
    if gallery and thick >= 5:
        gal = (d >= 2) & (d <= thick - 3)
        g.fill_mask(gal, gy, gy + 2, "minecraft:air")
        slit = (d >= 0) & (d <= 1) & (np.mod(along, 6) == 3)
        g.fill_mask(slit, gy + 1, gy + 2, "minecraft:air")
        lamp = (d == 2) & (np.mod(along, 10) == 5)
        g.fill_mask(lamp, gy + 2, gy + 2, "minecraft:lantern[hanging=true]")
    # walkway + projecting parapet on corbels
    g.fill_mask(body, walk_y, walk_y + 4, "minecraft:air")
    proj = d == -1
    g.fill_mask(proj, walk_y - 1, walk_y, STONE)
    merlon = proj & (np.mod(along, 4) < 2)
    g.fill_mask(merlon, walk_y + 1, walk_y + 2, STONE)
    g.fill_mask(merlon, walk_y + 3, walk_y + 3, SLAB_CAP)
    stairs_by_facing(g, proj & (np.mod(along, 2) == 0), walk_y - 2, STONE_STAIR, out, half="top", invert=True)
    g.fill_mask(proj & (np.mod(along, 2) == 1), walk_y - 2, walk_y - 2, "minecraft:air")
    rail = d == thick - 1
    g.fill_mask(rail & (d >= 0), walk_y, walk_y, RAIL)
    g.fill_mask(rail & (np.mod(along, 8) == 4), walk_y + 1, walk_y + 1, "minecraft:lantern[hanging=false]")
    # banners on outer face
    if banners_every:
        xs, zs = np.nonzero((d == -1) & (np.mod(along, banners_every) == banners_every // 2))
        for x, z in zip(xs, zs):
            g.wall_banner(int(x), walk_y - 6, int(z), out[x, z])
    return d, along, out


def posts_on_walk(g, d, along, walk_y, role, dsel=1, every=5, offset=0):
    xs, zs = np.nonzero((d == dsel) & (np.mod(along, every) == offset))
    for x, z in zip(xs, zs):
        g.mark(role, x, walk_y, z)


def round_tower(g, cx, cz, r, top, GL, floors=(), spiral=True, cone=False, t=3,
                doors=(), banner_dirs=(), lantern_floors=True):
    """Round tower from y=0. Roof platform standing level = top. Returns spiral ring."""
    full = disk(g, cx, cz, r)
    shell = ring(g, cx, cz, r, r - t)
    inner = full & ~shell
    g.fill_mask(full, 0, top - 1, STONE)
    g.fill_mask(inner, GL, top - 2, "minecraft:air")
    g.fill_mask(shell & ~ring(g, cx, cz, r, r - 1), 0, 0, DARK)
    # plinth
    pl = ring(g, cx, cz, r + 1, r)
    fgrid = facing_to_center_grid(g, cx, cz)
    g.fill_mask(pl, 0, GL + 1, DARK)
    stairs_by_facing(g, pl, GL + 2, DARK_STAIR, fgrid)
    # band
    g.fill_mask(ring(g, cx, cz, r, r - 1), GL + 3, GL + 3, DARK)
    # floors
    for fy in floors:
        g.fill_mask(inner, fy, fy, PLANK)
        if lantern_floors and fy - 1 > GL:
            g.lantern(cx + (2 if r >= 6 else 0), fy - 1, cz + (2 if r >= 6 else 0))
    g.fill_mask(inner, GL - 1, GL - 1, FLOOR_STONE)
    g.fill_mask(inner, top - 1, top - 1, STONE)
    # arrow slits
    ai = angle_index(g, cx, cz, r)
    outer_shell = ring(g, cx, cz, r, r - 1)
    for y in range(GL + 5, top - 4, 7):
        g.fill_mask(shell & (np.mod(ai, 7) == 0), y, y + 1, "minecraft:air")
    # corbelled parapet
    proj = ring(g, cx, cz, r + 1, r)
    stairs_by_facing(g, proj & (np.mod(ai, 2) == 0), top - 2, STONE_STAIR, fgrid, half="top")
    g.fill_mask(proj, top - 1, top, STONE)
    ai2 = angle_index(g, cx, cz, r + 1)
    merlon = proj & (np.mod(ai2, 4) < 2)
    g.fill_mask(merlon, top + 1, top + 2, STONE)
    g.fill_mask(merlon, top + 3, top + 3, SLAB_CAP)
    g.fill_mask(full, top, top + 6, "minecraft:air")
    g.fill_mask(proj & ~merlon, top + 1, top + 6, "minecraft:air")
    g.fill_mask(proj, top, top, STONE)
    sp = None
    if spiral and r - t >= 3:
        sp = spiral_stair(g, cx, cz, GL, top - 1, STONE_STAIR, STONE)
        # rail around the shaft at roof level, leaving the exit open
        last = (top - 1 - GL) % 16
        exit_cells = {sp[(last + k) % 16][:2] for k in range(-3, 2)}
        for (x, z, _) in sp:
            if (x, z) not in exit_cells:
                g.set(x, top, z, RAIL)
    for (dx, dz, y) in doors:
        for k in range(t + 1):
            x = cx + int(round(dx * (r - k)))
            z = cz + int(round(dz * (r - k)))
            for xx, zz in ((x, z), (x + (1 if dz else 0), z + (1 if dx else 0))):
                g.fill(xx, y, zz, xx, y + 1, zz, "minecraft:air")
    if cone:
        g.fill_mask(full, top, top + 6, "minecraft:air")
        cone_roof(g, cx, cz, r, top, ROOF)
    for (dx, dz) in banner_dirs:
        x = cx + int(round(dx * (r + 1)))
        z = cz + int(round(dz * (r + 1)))
        f = "east" if dx > 0 else "west" if dx < 0 else "south" if dz > 0 else "north"
        g.wall_banner(x, top - 6, z, f)
    return sp


def square_tower(g, x0, z0, x1, z1, top, GL, floors=(), spiral_at=None, t=3, cone=False,
                 banner_sides=()):
    full = rect(g, x0, z0, x1, z1)
    inner = rect(g, x0 + t, z0 + t, x1 - t, z1 - t)
    g.fill_mask(full, 0, top - 1, STONE)
    g.fill_mask(inner, GL, top - 2, "minecraft:air")
    # quoins in dark stone at corners
    for (x, z) in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        for y in range(GL, top - 1, 2):
            g.set(x, y, z, "minecraft:deepslate_bricks")
    # plinth
    pl = rect_ring(g, x0 - 1, z0 - 1, x1 + 1, z1 + 1, 1)
    fgrid = facing_to_center_grid(g, (x0 + x1) / 2, (z0 + z1) / 2)
    g.fill_mask(pl, 0, GL + 1, DARK)
    stairs_by_facing(g, pl, GL + 2, DARK_STAIR, fgrid)
    g.fill_mask(rect_ring(g, x0, z0, x1, z1, 1), GL + 3, GL + 3, DARK)
    for fy in floors:
        g.fill_mask(inner, fy, fy, PLANK)
        g.lantern((x0 + x1) // 2 + 1, fy - 1, (z0 + z1) // 2 + 1)
    g.fill_mask(inner, GL - 1, GL - 1, FLOOR_STONE)
    g.fill_mask(inner, top - 1, top - 1, STONE)
    # slits
    xs, zs = g.xz()
    shell = full & ~inner
    along = (xs + zs) + 0 * xs
    corners = (rect(g, x0, z0, x0 + t, z0 + t) | rect(g, x1 - t, z0, x1, z0 + t) |
               rect(g, x0, z1 - t, x0 + t, z1) | rect(g, x1 - t, z1 - t, x1, z1))
    for y in range(GL + 5, top - 4, 7):
        g.fill_mask(shell & ~corners & (np.mod(along, 5) == 2), y, y + 1, "minecraft:air")
    # corbels + parapet
    proj = rect_ring(g, x0 - 1, z0 - 1, x1 + 1, z1 + 1, 1)
    stairs_by_facing(g, proj & (np.mod(along, 2) == 0), top - 2, STONE_STAIR, fgrid, half="top")
    g.fill_mask(proj, top - 1, top, STONE)
    g.fill_mask(rect(g, x0, z0, x1, z1), top, top + 6, "minecraft:air")
    merlon = proj & (np.mod(along, 4) < 2)
    g.fill_mask(merlon, top + 1, top + 2, STONE)
    g.fill_mask(merlon, top + 3, top + 3, SLAB_CAP)
    g.fill_mask(proj & ~merlon, top + 1, top + 6, "minecraft:air")
    sp = None
    if spiral_at:
        cx, cz = spiral_at
        sp = spiral_stair(g, cx, cz, GL, top - 1, STONE_STAIR, STONE)
        last = (top - 1 - GL) % 16
        exit_cells = {sp[(last + k) % 16][:2] for k in range(-3, 2)}
        for (x, z, _) in sp:
            if (x, z) not in exit_cells:
                g.set(x, top, z, RAIL)
    if cone:
        cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
        cone_roof(g, cx, cz, (x1 - x0) // 2, top, ROOF)
    for side in banner_sides:
        cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
        if side == "north":
            g.wall_banner(cx, top - 6, z0 - 1, "north")
        elif side == "south":
            g.wall_banner(cx, top - 6, z1 + 1, "south")
        elif side == "west":
            g.wall_banner(x0 - 1, top - 6, cz, "west")
        else:
            g.wall_banner(x1 + 1, top - 6, cz, "east")
    return sp


def straight_wall(g, x0, z0, x1, z1, walk_y, outward, GL):
    """Wall whose body is rect x0..x1/z0..z1. Parapet on the outward edge."""
    g.fill(x0, 0, z0, x1, walk_y - 1, z1, STONE)
    g.fill(x0, walk_y, z0, x1, walk_y + 4, z1, "minecraft:air")
    if outward in ("north", "south"):
        pz = z0 if outward == "north" else z1
        rz = z1 if outward == "north" else z0
        for x in range(x0, x1 + 1):
            g.set(x, walk_y, pz, STONE)
            if x % 4 < 2:
                g.set(x, walk_y + 1, pz, STONE); g.set(x, walk_y + 2, pz, SLAB_CAP)
            g.set(x, walk_y, rz, RAIL)
            g.set(x, GL + 3, pz, DARK)
            pz2 = pz - 1 if outward == "north" else pz + 1
            g.set(x, GL + 1, pz2, DARK); g.set(x, 0, pz2, DARK)
            for y in range(0, GL + 1):
                g.set(x, y, pz2, DARK)
            g.stair(x, GL + 2, pz2, DARK_STAIR, OPPOSITE[outward])
    else:
        px = x0 if outward == "west" else x1
        rx = x1 if outward == "west" else x0
        for z in range(z0, z1 + 1):
            g.set(px, walk_y, z, STONE)
            if z % 4 < 2:
                g.set(px, walk_y + 1, z, STONE); g.set(px, walk_y + 2, z, SLAB_CAP)
            g.set(rx, walk_y, z, RAIL)
            g.set(px, GL + 3, z, DARK)
            px2 = px - 1 if outward == "west" else px + 1
            for y in range(0, GL + 2):
                g.set(px2, y, z, DARK)
            g.stair(px2, GL + 2, z, DARK_STAIR, OPPOSITE[outward])


def pointed_arch(g, x0, x1, y0, height, z0, z1, axis="z", block="minecraft:air"):
    """Carve a passage with a pointed top. Passage runs along axis between z0..z1 (or x0..x1)."""
    w = x1 - x0 + 1
    for y in range(y0, y0 + height):
        shrink = max(0, (y - (y0 + height - 3)) )
        shrink = shrink * (1 if w <= 5 else 2) if y >= y0 + height - 2 else 0
        a, b = x0 + shrink, x1 - shrink
        if a > b:
            continue
        if axis == "z":
            g.fill(a, y, z0, b, y, z1, block)
        else:
            g.fill(z0, y, a, z1, y, b, block)


def building(g, x0, z0, x1, z1, GL, wall_h, axis, door_cells=(), upper_floor=None,
             wall_block=STONE, chimney=None, timber=False):
    """Rectangular building with gable roof. Returns interior bounds."""
    g.fill(x0, 0, z0, x1, GL - 2, z1, FOUND)
    g.fill(x0, GL - 1, z0, x1, GL - 1, z1, PLANK if timber else FLOOR_STONE)
    g.fill(x0, GL, z0, x1, GL + wall_h - 1, z1, wall_block)
    g.fill(x0 + 1, GL, z0 + 1, x1 - 1, GL + wall_h - 1, z1 - 1, "minecraft:air")
    # timber framing on upper storey
    if timber:
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                if (x - x0) % 4 == 0 or x in (x0, x1):
                    g.fill(x, GL + 4, z, x, GL + wall_h - 1, z, "minecraft:dark_oak_log[axis=y]")
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                if (z - z0) % 4 == 0 or z in (z0, z1):
                    g.fill(x, GL + 4, z, x, GL + wall_h - 1, z, "minecraft:dark_oak_log[axis=y]")
        g.fill(x0, GL + 4, z0, x1, GL + 4, z0, "minecraft:dark_oak_log[axis=x]")
        g.fill(x0, GL + 4, z1, x1, GL + 4, z1, "minecraft:dark_oak_log[axis=x]")
        g.fill(x0, GL + 4, z0, x0, GL + 4, z1, "minecraft:dark_oak_log[axis=z]")
        g.fill(x1, GL + 4, z0, x1, GL + 4, z1, "minecraft:dark_oak_log[axis=z]")
    # windows
    for x in range(x0 + 2, x1 - 1, 4):
        for z in (z0, z1):
            g.fill(x, GL + 1, z, x, GL + 2, z, GLASS)
            if wall_h > 6:
                g.fill(x, GL + 6, z, x, GL + 7, z, GLASS)
    for z in range(z0 + 2, z1 - 1, 4):
        for x in (x0, x1):
            g.fill(x, GL + 1, z, x, GL + 2, z, GLASS)
            if wall_h > 6:
                g.fill(x, GL + 6, z, x, GL + 7, z, GLASS)
    for (x, z) in door_cells:
        g.fill(x, GL, z, x, GL + 2, z, "minecraft:air")
    if upper_floor:
        g.fill(x0 + 1, upper_floor, z0 + 1, x1 - 1, upper_floor, z1 - 1, PLANK)
    top = gable_roof(g, x0, z0, x1, z1, GL + wall_h, axis, ROOF_STAIR, wall_block)
    if chimney:
        cx, cz = chimney
        g.fill(cx, GL, cz, cx, top + 1, cz, "minecraft:bricks")
        g.set(cx, top + 2, cz, "minecraft:campfire[lit=true,signal_fire=false]")
    # interior light
    for x in range(x0 + 3, x1 - 1, 6):
        for z in range(z0 + 3, z1 - 1, 6):
            y = (upper_floor - 1) if upper_floor else GL + wall_h - 1
            g.lantern(x, y, z)
            if upper_floor:
                g.lantern(x, GL + wall_h - 1, z)
    return (x0 + 1, z0 + 1, x1 - 1, z1 - 1)


def lamp_post(g, x, z, GL, h=3):
    g.fill(x, GL, z, x, GL + h - 1, z, "minecraft:dark_oak_fence")
    g.set(x, GL + h, z, "minecraft:lantern[hanging=false]")


def tree_weirwood(g, cx, cz, GL):
    """A pale tree with blood-red canopy."""
    for y in range(GL, GL + 7):
        g.fill(cx, y, cz, cx + 1, y, cz + 1, "minecraft:birch_wood[axis=y]")
    for dx, dz in ((-1, 0), (2, 1), (0, 2), (1, -1)):
        g.set(cx + dx, GL, cz + dz, "minecraft:birch_wood[axis=y]")
    canopy = disk(g, cx + 0.5, cz + 0.5, 5)
    g.fill_mask(canopy, GL + 6, GL + 7, Mix(("minecraft:nether_wart_block", 80), ("minecraft:red_mushroom_block", 20)))
    g.fill_mask(disk(g, cx + 0.5, cz + 0.5, 3.6), GL + 8, GL + 9, "minecraft:nether_wart_block")
    g.fill_mask(disk(g, cx + 0.5, cz + 0.5, 2), GL + 10, GL + 10, "minecraft:nether_wart_block")
