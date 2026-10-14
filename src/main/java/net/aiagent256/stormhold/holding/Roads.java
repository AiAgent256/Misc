package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Stormhold;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Lived-in surroundings: a road from the gate to the nearest village, painted chunk by chunk as the
 * world loads, plus fields, an orchard and hay around the walls.
 */
public final class Roads {
    private static final Map<ServerLevel, ArrayDeque<Long>> QUEUE = new HashMap<>();

    public static void enqueue(ServerLevel level, ChunkPos c) {
        QUEUE.computeIfAbsent(level, l -> new ArrayDeque<>()).add(c.toLong());
    }

    /** Called once per holding when its castle area is loaded. */
    public static void plan(ServerLevel level, HoldingManager mgr, Holding h) {
        h.surroundingsDone = true;
        mgr.setDirty();
        if (h.layout != null) paintFields(level, h);
        if (!Config.ROADS_ENABLED.get()) return;
        BlockPos start = h.gatePos.equals(BlockPos.ZERO) ? h.center() : h.gatePos;
        BlockPos village;
        try {
            village = level.findNearestMapStructure(StructureTags.VILLAGE, start, Config.ROAD_MAX_CHUNKS.get() / 2, false);
        } catch (Exception e) {
            Stormhold.LOGGER.warn("Stormhold: village search failed near {}", start, e);
            return;
        }
        if (village == null) return;
        double dist = Math.sqrt(village.distSqr(start));
        if (dist < 40 || dist > Config.ROAD_MAX_CHUNKS.get() * 16) return;
        RoadPlan r = new RoadPlan();
        r.from = start;
        r.to = village;
        r.seed = h.id.getLeastSignificantBits();
        r.theme = h.theme;
        mgr.roads.add(r);
        int minX = Math.min(start.getX(), village.getX()) >> 4, maxX = Math.max(start.getX(), village.getX()) >> 4;
        int minZ = Math.min(start.getZ(), village.getZ()) >> 4, maxZ = Math.max(start.getZ(), village.getZ()) >> 4;
        for (int cx = minX - 1; cx <= maxX + 1; cx++) {
            for (int cz = minZ - 1; cz <= maxZ + 1; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) != null) enqueue(level, new ChunkPos(cx, cz));
            }
        }
        Stormhold.LOGGER.info("Stormhold: road planned from {} to village at {}", h.name, village);
    }

    public static void tick(ServerLevel level, HoldingManager mgr) {
        ArrayDeque<Long> q = QUEUE.get(level);
        if (q == null || q.isEmpty() || mgr.roads.isEmpty()) {
            if (q != null && mgr.roads.isEmpty()) q.clear();
            return;
        }
        for (int i = 0; i < 3 && !q.isEmpty(); i++) {
            ChunkPos c = new ChunkPos(q.poll());
            if (level.getChunkSource().getChunkNow(c.x, c.z) == null) continue;
            for (RoadPlan r : mgr.roads) {
                if (r.painted.contains(c.toLong()) || !r.touches(c)) continue;
                paintChunk(level, mgr, r, c);
                r.painted.add(c.toLong());
                mgr.setDirty();
            }
        }
    }

    private static void paintChunk(ServerLevel level, HoldingManager mgr, RoadPlan r, ChunkPos c) {
        double len = r.length();
        if (len < 1) return;
        double end = Math.max(0, 1 - 12 / len);
        int steps = (int) (len * 2);
        RandomSource rnd = RandomSource.create(r.seed ^ c.toLong());
        double[] prev = r.at(0);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            if (t > end) break;
            double[] p = r.at(t);
            double dx = p[0] - prev[0], dz = p[1] - prev[1];
            double l = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
            double nx = -dz / l, nz = dx / l;
            prev = p;
            for (int w = -1; w <= 1; w++) {
                int x = (int) Math.floor(p[0] + nx * w), z = (int) Math.floor(p[1] + nz * w);
                if ((x >> 4) != c.x || (z >> 4) != c.z) continue;
                BlockPos bp = new BlockPos(x, 0, z);
                if (mgr.at(bp.atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)), 1) != null) continue;
                paintCell(level, x, z, r.theme, rnd);
            }
            // lamp post every ~48 blocks on the right shoulder
            int lampEvery = Math.max(1, (int) (48 / len * steps));
            if (i % lampEvery == lampEvery / 2) {
                int x = (int) Math.floor(p[0] + nx * 2.5), z = (int) Math.floor(p[1] + nz * 2.5);
                if ((x >> 4) == c.x && (z >> 4) == c.z) lamp(level, x, z);
            }
        }
    }

    private static boolean ground(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY) || s.is(Blocks.TERRACOTTA) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.RED_SAND) || s.is(Blocks.SANDSTONE);
    }

    private static void paintCell(ServerLevel level, int x, int z, String theme, RandomSource rnd) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos p = new BlockPos(x, top, z);
        BlockState s = level.getBlockState(p);
        if (s.is(BlockTags.LOGS) || s.getBlock() instanceof LeavesBlock) return;
        if (s.getFluidState().is(FluidTags.WATER)) {
            BlockPos deck = p.above();
            if (level.getBlockState(deck).isAir()) level.setBlock(deck, Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
            return;
        }
        if (s.is(Blocks.SNOW) || s.canBeReplaced()) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            p = p.below();
            s = level.getBlockState(p);
        }
        if (!ground(s)) return;
        BlockState above = level.getBlockState(p.above());
        if (!above.isAir() && above.canBeReplaced()) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 2);
        Block road;
        float f = rnd.nextFloat();
        if (theme.equals("dorne")) road = f < 0.5F ? Blocks.SMOOTH_SANDSTONE : f < 0.8F ? Blocks.COARSE_DIRT : Blocks.GRAVEL;
        else if (theme.equals("reach")) road = f < 0.6F ? Blocks.DIRT_PATH : f < 0.85F ? Blocks.GRAVEL : Blocks.COBBLESTONE;
        else road = f < 0.62F ? Blocks.DIRT_PATH : f < 0.82F ? Blocks.COARSE_DIRT : Blocks.GRAVEL;
        if (road == Blocks.DIRT_PATH && !level.getBlockState(p.above()).isAir()) road = Blocks.COARSE_DIRT;
        level.setBlock(p, road.defaultBlockState(), 2);
    }

    private static void lamp(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos base = new BlockPos(x, y, z);
        BlockState below = level.getBlockState(base.below());
        if (!below.isSolidRender(level, base.below()) || below.is(BlockTags.LEAVES)) return;
        for (int i = 0; i < 3; i++) {
            if (!level.getBlockState(base.above(i)).isAir() && !level.getBlockState(base.above(i)).canBeReplaced()) return;
        }
        level.setBlock(base, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        level.setBlock(base.above(), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), 3);
    }

    // ------------------------------------------------------------ fields and orchards

    /** Farmland plots, an orchard and haystacks on flat ground outside the walls. */
    public static void paintFields(ServerLevel level, Holding h) {
        RandomSource rnd = RandomSource.create(h.id.getMostSignificantBits());
        BlockPos c = h.center();
        int rx = h.box.getXSpan() / 2, rz = h.box.getZSpan() / 2;
        int plots = 2 + h.tier;
        int placed = 0;
        for (int attempt = 0; attempt < 24 && placed < plots; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 1.0 + rnd.nextDouble() * 0.5;
            int px = c.getX() + (int) (Math.cos(ang) * (rx + 12) * dist);
            int pz = c.getZ() + (int) (Math.sin(ang) * (rz + 12) * dist);
            if (h.box.inflatedBy(6).isInside(new BlockPos(px, h.box.minY() + 5, pz))) continue;
            boolean orchard = placed == plots - 1 && rnd.nextBoolean();
            if (plot(level, h, px, pz, 9 + rnd.nextInt(4), 7 + rnd.nextInt(4), orchard, rnd)) placed++;
        }
    }

    private static boolean plot(ServerLevel level, Holding h, int x0, int z0, int w, int d, boolean orchard, RandomSource rnd) {
        int[] hs = new int[4];
        int k = 0;
        for (int[] o : new int[][]{{0, 0}, {w, 0}, {0, d}, {w, d}}) {
            BlockPos p = new BlockPos(x0 + o[0], 0, z0 + o[1]);
            if (!level.isLoaded(p)) return false;
            hs[k++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        }
        int min = Math.min(Math.min(hs[0], hs[1]), Math.min(hs[2], hs[3]));
        int max = Math.max(Math.max(hs[0], hs[1]), Math.max(hs[2], hs[3]));
        if (max - min > 2) return false;
        Block crop = switch (rnd.nextInt(4)) {
            case 0 -> Blocks.CARROTS;
            case 1 -> Blocks.POTATOES;
            case 2 -> Blocks.BEETROOTS;
            default -> Blocks.WHEAT;
        };
        int y = min - 1;
        for (int x = x0; x <= x0 + w; x++) {
            for (int z = z0; z <= z0 + d; z++) {
                BlockPos g = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                BlockState s = level.getBlockState(g);
                if (s.canBeReplaced() && !s.getFluidState().isEmpty()) return true;
                if (s.canBeReplaced()) {
                    level.setBlock(g, Blocks.AIR.defaultBlockState(), 2);
                    g = g.below();
                    s = level.getBlockState(g);
                }
                if (!s.is(BlockTags.DIRT) && !s.is(BlockTags.SAND)) continue;
                boolean edge = x == x0 || x == x0 + w || z == z0 || z == z0 + d;
                if (edge) {
                    boolean gap = z == z0 + d && x == x0 + w / 2;
                    if (!gap && level.getBlockState(g.above()).isAir()) {
                        level.setBlock(g.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);
                    }
                    continue;
                }
                if (orchard) {
                    level.setBlock(g, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    if ((x - x0) % 4 == 2 && (z - z0) % 4 == 2) tree(level, g.above(), rnd);
                    continue;
                }
                if ((x - x0) % 5 == 0) {
                    level.setBlock(g, Blocks.WATER.defaultBlockState(), 3);
                    continue;
                }
                level.setBlock(g, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), 2);
                if (crop instanceof CropBlock cb) {
                    level.setBlock(g.above(), cb.getStateForAge(rnd.nextInt(cb.getMaxAge() + 1)), 2);
                }
            }
        }
        // a haystack and a scarecrow
        BlockPos hay = new BlockPos(x0 - 2, y + 1, z0 + 1);
        if (level.getBlockState(hay).isAir() && level.getBlockState(hay.below()).isSolidRender(level, hay.below())) {
            level.setBlock(hay, Blocks.HAY_BLOCK.defaultBlockState(), 3);
            if (level.getBlockState(hay.above()).isAir()) level.setBlock(hay.above(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
        }
        if (!orchard) {
            BlockPos sc = new BlockPos(x0 + w / 2, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + w / 2, z0 + d / 2), z0 + d / 2);
            if (level.getBlockState(sc).isAir() || level.getBlockState(sc).getBlock() instanceof CropBlock) {
                level.setBlock(sc, Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(sc.above(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
                level.setBlock(sc.above(2), Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
            }
        }
        return true;
    }

    private static void tree(ServerLevel level, BlockPos base, RandomSource rnd) {
        for (int i = 0; i < 3; i++) {
            if (!level.getBlockState(base.above(i)).isAir()) return;
        }
        for (int i = 0; i < 3; i++) level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 2);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 2; dy <= 3; dy++) {
                    BlockPos lp = base.offset(dx, dy, dz);
                    if (level.getBlockState(lp).isAir()) level.setBlock(lp, leaves, 2);
                }
            }
        }
        if (level.getBlockState(base.above(4)).isAir()) level.setBlock(base.above(4), leaves, 2);
    }

    private Roads() {
    }
}
