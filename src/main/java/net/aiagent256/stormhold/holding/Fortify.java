package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The great works of fortification, past the first three (palisade, moat, hoardings; see {@link Builder}): stone
 * towers at the corners of the palisade's ring, a gatehouse over the road with a deck and a portcullis's teeth, a
 * stone curtain two thick with a wall-walk behind its merlons that takes the palisade's place, bastions standing out
 * from the curtain, and last braziers on every tower and a field of stakes beyond the moat. All in the country's
 * stone, following the land, skipping the road out of the gate and anything of the town's or the fief's. Each tower,
 * bastion and stretch of wall-walk gets archers' posts, so the garrison stands on them.
 */
final class Fortify {
    /** The palisade's ring (and the curtain's outer face): this far out from the castle's walls. */
    static final int RING = 7;
    /** A corner or gatehouse tower: five across, its platform this high over its base. */
    static final int TOWER = 9, GATE_TOWER = 10, DECK = 6;
    /** Bastions along the curtain, about this far apart. */
    static final int BASTION_EVERY = 16;
    /** The stakes beyond the moat: from and to this far out. */
    static final int STAKES_FROM = 14, STAKES_TO = 17;

    /** Archers' posts for the stage being built, added to the castle's when it's finished. Not saved: a restart replans. */
    private static final Map<UUID, List<BlockPos>> POSTS = new HashMap<>();

    record Stone(Block block, Block slab) {
    }

    static Stone stone(Holding h) {
        if (h.isNether()) return new Stone(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
        return switch (h.theme == null ? "north" : h.theme) {
            case "dorne" -> new Stone(Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB);
            case "reach" -> new Stone(Blocks.POLISHED_DIORITE, Blocks.POLISHED_DIORITE_SLAB);
            case "stormlands" -> new Stone(Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICK_SLAB);
            case "nether" -> new Stone(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
            default -> new Stone(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB);
        };
    }

    /** The land's top block at (x, z), under plants, trees, and the palisade's stakes and posts. */
    static int ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int g = 0; g < 24; g++, y--) {
            BlockState s = level.getBlockState(new BlockPos(x, y, z));
            if (s.isAir() || s.canBeReplaced() || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FENCES)
                    || s.is(Blocks.POINTED_DRIPSTONE)) continue;
            return y;
        }
        return y;
    }

    /** Can the works take this block: open air, the land, growing things, the palisade, or what they've set already. */
    static boolean takes(BlockState s, Stone st) {
        return s.isAir() || s.canBeReplaced() || Builder.natural(s) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.FENCES)
                || s.is(Blocks.POINTED_DRIPSTONE) || s.is(st.block()) || s.is(st.slab()) || s.is(Blocks.LADDER);
    }

    /** Is the column the town's, or a fief building's? The works keep off it. */
    static boolean someones(Holding h, int x, int z) {
        BoundingBox col = new BoundingBox(x, -64, z, x, 320, z);
        if (Town.blocks(h, col)) return true;
        for (Holding.FiefBuilding b : h.fief) if (Town.flat(b.box()).isInside(new BlockPos(x, 0, z))) return true;
        return false;
    }

    /** The ring's corners, x then z: the palisade runs between them. */
    static int[] ring(Holding h, int d) {
        return new int[]{h.box.minX() - d, h.box.minZ() - d, h.box.maxX() + d, h.box.maxZ() + d};
    }

    /** Which way the gate looks out, or null for a place with no gate. */
    @Nullable
    static Direction out(Holding h) {
        if (h.gatePos.equals(BlockPos.ZERO)) return null;
        BlockPos c = h.center(), g = Campaigns.gateOutside(h);
        Direction d = Direction.getNearest(g.getX() - c.getX(), 0, g.getZ() - c.getZ());
        return d.getAxis() == Direction.Axis.Y ? null : d;
    }

    /** Where the road from the gate crosses the ring: its x (for a gate looking north or south) or z. */
    static int crossing(Holding h, Direction out) {
        BlockPos c = h.center(), g = Campaigns.gateOutside(h);
        int[] r = ring(h, RING);
        double dx = g.getX() - c.getX(), dz = g.getZ() - c.getZ();
        if (out.getAxis() == Direction.Axis.Z) {
            int z = out == Direction.SOUTH ? r[3] : r[1];
            return dz == 0 ? g.getX() : (int) Math.round(c.getX() + dx / dz * (z - c.getZ()));
        }
        int x = out == Direction.EAST ? r[2] : r[0];
        return dx == 0 ? g.getZ() : (int) Math.round(c.getZ() + dz / dx * (x - c.getX()));
    }

    /** The two gatehouse towers' middles. Empty with no gate. */
    static List<BlockPos> gateTowers(Holding h) {
        Direction out = out(h);
        List<BlockPos> t = new ArrayList<>();
        if (out == null) return t;
        int[] r = ring(h, RING);
        int across = crossing(h, out);
        if (out.getAxis() == Direction.Axis.Z) {
            int z = out == Direction.SOUTH ? r[3] : r[1];
            t.add(new BlockPos(across - 7, 0, z));
            t.add(new BlockPos(across + 7, 0, z));
        } else {
            int x = out == Direction.EAST ? r[2] : r[0];
            t.add(new BlockPos(x, 0, across - 7));
            t.add(new BlockPos(x, 0, across + 7));
        }
        return t;
    }

    static List<BlockPos> cornerTowers(Holding h) {
        int[] r = ring(h, RING);
        return List.of(new BlockPos(r[0], 0, r[1]), new BlockPos(r[2], 0, r[1]), new BlockPos(r[0], 0, r[3]), new BlockPos(r[2], 0, r[3]));
    }

    /** Where the bastions stand on the curtain: every so often along each side, clear of the corners and the gate. */
    static List<BlockPos> bastions(Holding h) {
        int[] r = ring(h, RING);
        List<BlockPos> out = new ArrayList<>();
        List<BlockPos> gates = gateTowers(h);
        for (int side = 0; side < 4; side++) {
            boolean alongX = side < 2;
            int lo = alongX ? r[0] : r[1], hi = alongX ? r[2] : r[3];
            int fixed = switch (side) {
                case 0 -> r[1];
                case 1 -> r[3];
                case 2 -> r[0];
                default -> r[2];
            };
            int span = hi - lo, n = Math.max(0, span / BASTION_EVERY - 1);
            for (int i = 1; i <= n; i++) {
                int a = lo + span * i / (n + 1);
                BlockPos p = alongX ? new BlockPos(a, 0, fixed) : new BlockPos(fixed, 0, a);
                boolean nearGate = false;
                for (BlockPos g : gates) if (Math.abs(g.getX() - p.getX()) + Math.abs(g.getZ() - p.getZ()) < 12) nearGate = true;
                if (!nearGate && a - lo >= 8 && hi - a >= 8 && !Builder.onCauseway(h, p.getX(), p.getZ(), 15)) out.add(p);
            }
        }
        return out;
    }

    /** The outward direction of a cell outside the castle: away from the nearer face of its walls (along x at a corner). */
    static Direction outward(Holding h, int x, int z) {
        int dx = Math.max(h.box.minX() - x, x - h.box.maxX()), dz = Math.max(h.box.minZ() - z, z - h.box.maxZ());
        if (dx >= dz) return x < h.box.minX() ? Direction.WEST : Direction.EAST;
        return z < h.box.minZ() ? Direction.NORTH : Direction.SOUTH;
    }

    // ------------------------------------------------------------ planning

    static void plan(ServerLevel level, Holding h, int stage, java.util.Deque<Builder.Job> queue) {
        Works q = new Works();
        List<BlockPos> posts = new ArrayList<>();
        POSTS.put(h.id, posts);
        Stone st = stone(h);
        switch (stage) {
            case 4 -> {
                for (BlockPos c : cornerTowers(h)) {
                    Direction outX = c.getX() < h.center().getX() ? Direction.WEST : Direction.EAST;
                    tower(level, h, st, q, posts, c.getX(), c.getZ(), TOWER, outX);
                }
            }
            case 5 -> gatehouse(level, h, st, q, posts);
            case 6 -> curtain(level, h, st, q, posts);
            case 7 -> {
                for (BlockPos b : bastions(h)) bastion(level, h, st, q, posts, b);
            }
            case 8 -> braziersAndStakes(level, h, st, q);
            default -> {
            }
        }
        q.into(queue);
    }

    /** Archers' posts on the stage just finished go to the garrison. */
    static void finished(Holding h) {
        List<BlockPos> posts = POSTS.remove(h.id);
        if (posts == null || posts.isEmpty()) return;
        List<BlockPos> archers = h.posts.computeIfAbsent(Role.ARCHER, k -> new ArrayList<>());
        for (BlockPos p : posts) if (!archers.contains(p)) archers.add(p);
    }

    /** A stage's blocks, one to a place (a later one replaces an earlier), then the ones that only go where there's room. */
    static final class Works {
        final Map<BlockPos, BlockState> hard = new java.util.LinkedHashMap<>();
        final List<Builder.Job> soft = new ArrayList<>();

        void set(BlockPos p, BlockState s) {
            BlockPos k = p.immutable();
            hard.remove(k);
            hard.put(k, s);
        }

        void soft(BlockPos p, BlockState s) {
            soft.add(new Builder.Job(p.immutable(), s);
        }

        void into(java.util.Deque<Builder.Job> q) {
            hard.forEach((p, s) -> q.add(new Builder.Job(p, s, false)));
            q.addAll(soft);
        }
    }

    private static void set(Works q, BlockPos p, BlockState s) {
        q.set(p, s);
    }

    /**
     * A square tower five across round (cx, cz): stone from the land up to its platform, hollow inside with a ladder up
     * the outer wall, a door on the side away from {@code outX}, merlons round the top, an archer's post in the middle.
     * The base is the highest ground under it; the foundation's filled down to the land. False if the ground's someone's.
     */
    static boolean tower(ServerLevel level, Holding h, Stone st, Works q, List<BlockPos> posts, int cx, int cz, int height,
                         Direction outX) {
        int base = Integer.MIN_VALUE;
        int[][] g = new int[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                int x = cx - 2 + i, z = cz - 2 + j;
                if (!level.isLoaded(new BlockPos(x, 64, z)) || someones(h, x, z)) return false;
                g[i][j] = ground(level, x, z);
                base = Math.max(base, g[i][j]);
            }
        }
        int top = base + height;
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                int x = cx - 2 + i, z = cz - 2 + j;
                for (int y = g[i][j] + 1; y <= top + 1; y++) {
                    BlockState now = level.getBlockState(new BlockPos(x, y, z));
                    if (!takes(now, st)) return false;
                }
            }
        }
        int sx = outX == Direction.EAST ? 1 : -1;
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                int x = cx - 2 + i, z = cz - 2 + j;
                boolean edge = i == 0 || i == 4 || j == 0 || j == 4;
                for (int y = g[i][j] + 1; y <= base; y++) set(q, new BlockPos(x, y, z), st.block().defaultBlockState());
                for (int y = base + 1; y < top; y++) set(q, new BlockPos(x, y, z), edge ? st.block().defaultBlockState() : Blocks.AIR.defaultBlockState());
                set(q, new BlockPos(x, top, z), st.block().defaultBlockState());
                if (edge) set(q, new BlockPos(x, top + 1, z), Math.floorMod(x + z, 2) == 0 ? st.block().defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        // the door, toward the castle; the ladder against the far wall, through a hole in the platform
        for (int y = base + 1; y <= base + 2; y++) set(q, new BlockPos(cx - sx * 2, y, cz), Blocks.AIR.defaultBlockState());
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, sx > 0 ? Direction.WEST : Direction.EAST);
        for (int y = base + 1; y <= top; y++) set(q, new BlockPos(cx + sx, y, cz), ladder);
        set(q, new BlockPos(cx, top - 1, cz), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING);
        posts.add(new BlockPos(cx, top + 1, cz));
        posts.add(new BlockPos(cx - 1, top + 1, cz + 1));
        return true;
    }

    /** Two towers flanking the road where it crosses the ring, a deck between them over the road, the portcullis's teeth under its outer edge. */
    static void gatehouse(ServerLevel level, Holding h, Stone st, Works q, List<BlockPos> posts) {
        Direction out = out(h);
        if (out == null) return;
        List<BlockPos> towers = gateTowers(h);
        BlockPos mid = new BlockPos((towers.get(0).getX() + towers.get(1).getX()) / 2, 0, (towers.get(0).getZ() + towers.get(1).getZ()) / 2);
        Direction outX = out.getAxis() == Direction.Axis.X ? out : Direction.EAST;
        for (BlockPos t : towers) tower(level, h, st, q, posts, t.getX(), t.getZ(), GATE_TOWER, outX);
        int base = ground(level, mid.getX(), mid.getZ());
        Direction along = out.getClockWise();
        for (int a = -5; a <= 5; a++) {
            for (int k = -1; k <= 1; k++) {
                BlockPos col = mid.relative(along, a).relative(out, k);
                if (!level.isLoaded(col.atY(64))) continue;
                set(q, col.atY(base + DECK), st.block().defaultBlockState());
                // parapets both sides of the deck
                if (k != 0) set(q, col.atY(base + DECK + 1), Math.floorMod(a, 2) == 0 ? st.block().defaultBlockState() : st.slab().defaultBlockState());
                if (k == 1) set(q, col.atY(base + DECK - 1), Blocks.IRON_BARS.defaultBlockState());
            }
        }
        // doors from the deck into each tower
        for (int sgn : new int[]{-1, 1}) {
            BlockPos d = mid.relative(along, sgn * 5);
            for (int y = base + DECK + 1; y <= base + DECK + 2; y++) set(q, d.atY(y), Blocks.AIR.defaultBlockState());
        }
        set(q, mid.atY(base + DECK - 1).relative(out, -1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING);
        posts.add(mid.relative(along, -2).atY(base + DECK + 1));
        posts.add(mid.relative(along, 2).atY(base + DECK + 1));
    }

    /**
     * The curtain: the palisade's ring in stone, two thick and five high, the outer face carried up into merlons two
     * high, the inner a wall-walk behind them; ladders up from the inside every so often. The road's gap stays open
     * (the gatehouse spans it); towers' footprints are left to them.
     */
    static void curtain(ServerLevel level, Holding h, Stone st, Works q, List<BlockPos> posts) {
        List<BlockPos> towers = new ArrayList<>(cornerTowers(h));
        towers.addAll(gateTowers(h));
        Direction gate = out(h);
        for (int d : new int[]{RING, RING - 1}) {
            int[] r = ring(h, d);
            int n = 0;
            for (int x = r[0]; x <= r[2]; x++) {
                for (int z = r[1]; z <= r[3]; z++) {
                    if (x != r[0] && x != r[2] && z != r[1] && z != r[3]) continue;
                    if (inTower(towers, x, z) || Builder.onCauseway(h, x, z, gate == null ? 9 : 11)) continue;
                    if (!level.isLoaded(new BlockPos(x, 64, z)) || someones(h, x, z)) continue;
                    int g = ground(level, x, z);
                    boolean clear = true;
                    for (int y = g + 1; y <= g + 7 && clear; y++) clear = takes(level.getBlockState(new BlockPos(x, y, z)), st);
                    if (!clear) continue;
                    for (int y = g + 1; y <= g + 5; y++) set(q, new BlockPos(x, y, z), st.block().defaultBlockState());
                    if (d == RING) {
                        boolean merlon = Math.floorMod(x + z, 2) == 0;
                        for (int y = g + 6; y <= g + 7; y++)
                            set(q, new BlockPos(x, y, z), merlon ? st.block().defaultBlockState() : Blocks.AIR.defaultBlockState());
                    } else {
                        if (++n % 6 == 0) posts.add(new BlockPos(x, g + 6, z));
                        // a ladder up the inside every twenty blocks
                        if (n % 20 == 10) {
                            Direction in = outward(h, x, z).getOpposite();
                            BlockPos lad = new BlockPos(x, 0, z).relative(in);
                            if (!someones(h, lad.getX(), lad.getZ()) && !h.box.isInside(lad.atY(h.box.minY() + 1)))
                                for (int y = g + 1; y <= g + 5; y++) set(q, lad.atY(y), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, in));
                        }
                    }
                }
            }
        }
    }

    private static boolean inTower(List<BlockPos> towers, int x, int z) {
        for (BlockPos t : towers) if (Math.abs(x - t.getX()) <= 2 && Math.abs(z - t.getZ()) <= 2) return true;
        return false;
    }

    /** A bastion: three wide, standing two out from the curtain, flush with its wall-walk, merlons two high round it, an archer's post on it. */
    static void bastion(ServerLevel level, Holding h, Stone st, Works q, List<BlockPos> posts, BlockPos at) {
        Direction out = outward(h, at.getX(), at.getZ());
        Direction along = out.getClockWise();
        int g0 = ground(level, at.getX(), at.getZ());
        int top = g0 + 5;
        for (int a = -1; a <= 1; a++) {
            for (int k = 1; k <= 2; k++) {
                BlockPos col = at.relative(along, a).relative(out, k);
                if (!level.isLoaded(col.atY(64)) || someones(h, col.getX(), col.getZ())) return;
            }
        }
        for (int a = -1; a <= 1; a++) {
            for (int k = 0; k <= 2; k++) {
                BlockPos col = at.relative(along, a).relative(out, k);
                int g = k == 0 ? g0 : Math.min(g0, ground(level, col.getX(), col.getZ()));
                for (int y = g + 1; y <= top; y++) set(q, col.atY(y), st.block().defaultBlockState());
                if (k == 0) {
                    // the curtain's merlons give way: the bastion opens off the wall-walk
                    for (int y = top + 1; y <= top + 2; y++) set(q, col.atY(y), Blocks.AIR.defaultBlockState());
                } else if (k == 2 || a != 0) {
                    boolean merlon = Math.floorMod(col.getX() + col.getZ(), 2) == 0;
                    for (int y = top + 1; y <= top + 2; y++) set(q, col.atY(y), merlon ? st.block().defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        posts.add(at.relative(out, 1).atY(top + 1));
    }

    /** Braziers on every tower and bastion, lanterns along the merlons, and staggered stakes in the field past the moat. */
    static void braziersAndStakes(ServerLevel level, Holding h, Stone st, Works q) {
        List<BlockPos> tops = new ArrayList<>(cornerTowers(h));
        tops.addAll(gateTowers(h));
        for (BlockPos t : tops) {
            // the brazier on the platform's corner, on whatever's highest there
            int x = t.getX() + 1, z = t.getZ() - 1;
            if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (level.getBlockState(new BlockPos(x, y - 1, z)).is(stone(h).block()))
                q.soft(new BlockPos(x, y, z), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        }
        for (BlockPos b : bastions(h)) {
            Direction out = outward(h, b.getX(), b.getZ());
            BlockPos c = b.relative(out, 2);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
            if (level.isLoaded(c.atY(64)) && level.getBlockState(c.atY(y - 1)).is(st.block()))
                q.soft(c.atY(y), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, false));
        }
        int[] r = ring(h, RING);
        int n = 0;
        for (int x = r[0]; x <= r[2]; x++) {
            for (int z = r[1]; z <= r[3]; z++) {
                if (x != r[0] && x != r[2] && z != r[1] && z != r[3]) continue;
                if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState top = level.getBlockState(new BlockPos(x, y - 1, z));
                if (top.is(st.block()) && Math.floorMod(x + z, 2) == 0 && ++n % 4 == 0)
                    q.soft(new BlockPos(x, y, z), Blocks.LANTERN.defaultBlockState());
            }
        }
        // the stakes: a staggered band, the road and anything of the town's or the fief's left clear
        int[] o = ring(h, STAKES_TO);
        for (int x = o[0]; x <= o[2]; x++) {
            for (int z = o[1]; z <= o[3]; z++) {
                int dx = Math.max(h.box.minX() - x, x - h.box.maxX()), dz = Math.max(h.box.minZ() - z, z - h.box.maxZ());
                int d = Math.max(dx, dz);
                if (d < STAKES_FROM || d > STAKES_TO || Math.floorMod(x * 3 + z * 5, 7) != 0) continue;
                if (Builder.onCauseway(h, x, z, 11) || !level.isLoaded(new BlockPos(x, 64, z)) || someones(h, x, z)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState ground = level.getBlockState(new BlockPos(x, y - 1, z));
                if (!ground.getFluidState().isEmpty() || !Builder.natural(ground) || ground.is(Blocks.DIRT_PATH)) continue;
                q.soft(new BlockPos(x, y, z), Blocks.POINTED_DRIPSTONE.defaultBlockState());
            }
        }
    }

    private Fortify() {
    }
}
