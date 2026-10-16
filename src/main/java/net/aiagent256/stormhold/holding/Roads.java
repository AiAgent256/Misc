package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Stormhold;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
            // the same placement maths worldgen uses, so this costs microseconds instead of a /locate
            village = nearestVillage(level, start, Config.ROAD_MAX_CHUNKS.get() * 8);
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

    /** Where the nearest village could start, from structure-set placement and the biome there. Never loads chunks. */
    @org.jetbrains.annotations.Nullable
    public static BlockPos nearestVillage(ServerLevel level, BlockPos from, int radius) {
        var gen = level.getChunkSource().getGenerator();
        var ctx = new net.minecraft.world.level.levelgen.structure.Structure.GenerationContext(level.registryAccess(), gen,
                gen.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(),
                new ChunkPos(from), level, b -> true);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (var t : net.aiagent256.stormhold.worldgen.Avoidance.near(ctx, from.getX(), from.getZ(), radius, Integer.MAX_VALUE)) {
            if (t.castleSize() > 0) continue;
            double d = (t.x() - from.getX()) * (double) (t.x() - from.getX()) + (t.z() - from.getZ()) * (double) (t.z() - from.getZ());
            if (d < bestD) {
                bestD = d;
                best = new BlockPos(t.x(), 0, t.z());
            }
        }
        return best;
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

    /**
     * Fields outside the walls, sized like fields: 24 to 32 long and 16 to 22 across, cut into terraces
     * four rows deep that step down the slope behind cobbled retaining walls, a water channel every
     * five rows, a fence round the lot and a timber barn at the gate. One in three is an orchard.
     */
    public static void paintFields(ServerLevel level, Holding h) {
        RandomSource rnd = RandomSource.create(h.id.getMostSignificantBits());
        BlockPos c = h.center();
        int rx = h.box.getXSpan() / 2, rz = h.box.getZSpan() / 2;
        int plots = 1 + h.tier;
        int placed = 0;
        for (int attempt = 0; attempt < 40 && placed < plots; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = 1.0 + rnd.nextDouble() * 0.6;
            int w = 24 + rnd.nextInt(9), d = 16 + rnd.nextInt(7);
            if (rnd.nextBoolean()) {
                int t = w; w = d; d = t;
            }
            int px = c.getX() + (int) (Math.cos(ang) * (rx + 16 + w / 2) * dist) - w / 2;
            int pz = c.getZ() + (int) (Math.sin(ang) * (rz + 16 + d / 2) * dist) - d / 2;
            if (h.box.inflatedBy(8).intersects(px, pz, px + w, pz + d)) continue;
            boolean overlap = false;
            for (Holding.FiefBuilding fb : h.fief) {
                if (fb.box().intersects(px - 3, pz - 3, px + w + 3, pz + d + 3)) { overlap = true; break; }
            }
            if (overlap) continue;
            boolean orchard = placed > 0 && rnd.nextInt(3) == 0;
            int[] ys = new int[2];
            if (plot(level, h, px, pz, w, d, orchard, rnd, ys)) {
                placed++;
                h.fief.add(new Holding.FiefBuilding(orchard ? "orchard" : "field", new BoundingBox(px - 2, ys[0] - 2, pz - 2, px + w + 2, ys[1] + 6, pz + d + 2)));
            }
        }
    }

    private static final int STRIP = 4;

    private static boolean plot(ServerLevel level, Holding h, int x0, int z0, int w, int d, boolean orchard, RandomSource rnd, int[] yOut) {
        // the ground: heights across the lot, and which way it falls
        int[][] top = new int[w + 1][d + 1];
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0;
        for (int x = 0; x <= w; x++) {
            for (int z = 0; z <= d; z++) {
                BlockPos p = new BlockPos(x0 + x, 0, z0 + z);
                if (!level.isLoaded(p)) return false;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
                top[x][z] = y;
                BlockState g = level.getBlockState(new BlockPos(p.getX(), y - 1, p.getZ()));
                if (!g.getFluidState().isEmpty()) wet++;
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
        }
        if (wet > (w * d) / 20 || max - min > 12) return false;
        // terraces run across the slope: strips along the axis with the smaller fall
        int fallX = 0, fallZ = 0;
        for (int z = 0; z <= d; z++) fallX += Math.abs(top[w][z] - top[0][z]);
        for (int x = 0; x <= w; x++) fallZ += Math.abs(top[x][d] - top[x][0]);
        boolean stripsAlongX = fallZ >= fallX;          // the ground falls along z: strips are rows of constant z
        int strips = (stripsAlongX ? d : w) / STRIP + 1;
        int[] lvl = new int[strips];
        for (int s = 0; s < strips; s++) {
            java.util.List<Integer> hs = new java.util.ArrayList<>();
            for (int a = 0; a <= (stripsAlongX ? w : d); a++) {
                for (int b = s * STRIP; b < Math.min((s + 1) * STRIP, (stripsAlongX ? d : w) + 1); b++) {
                    hs.add(stripsAlongX ? top[a][b] : top[b][a]);
                }
            }
            java.util.Collections.sort(hs);
            lvl[s] = hs.get(hs.size() / 2);
        }
        for (int s = 1; s < strips; s++) lvl[s] = Math.max(lvl[s - 1] - 3, Math.min(lvl[s - 1] + 3, lvl[s]));   // steps of three at most
        Block crop = switch (rnd.nextInt(4)) {
            case 0 -> Blocks.CARROTS;
            case 1 -> Blocks.POTATOES;
            case 2 -> Blocks.BEETROOTS;
            default -> Blocks.WHEAT;
        };
        BlockState wall = Blocks.COBBLESTONE.defaultBlockState();
        int skipped = 0;
        int ymin = Integer.MAX_VALUE, ymax = Integer.MIN_VALUE;
        for (int x = 0; x <= w; x++) {
            for (int z = 0; z <= d; z++) {
                int s = (stripsAlongX ? z : x) / STRIP;
                int y = lvl[s];                                   // standing level of this terrace
                ymin = Math.min(ymin, y);
                ymax = Math.max(ymax, y);
                int t = top[x][z];
                if (t - y > 5 || y - t > 4) { skipped++; continue; }
                BlockPos ground = new BlockPos(x0 + x, y - 1, z0 + z);
                // cut
                for (int yy = y; yy <= t + 1; yy++) {
                    BlockPos p = new BlockPos(x0 + x, yy, z0 + z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
                // fill: dirt inside, a cobbled face where the terrace steps down
                boolean face = false;
                int sNext = stripsAlongX ? (z + 1) / STRIP : (x + 1) / STRIP, sPrev = stripsAlongX ? Math.max(0, z - 1) / STRIP : Math.max(0, x - 1) / STRIP;
                if ((sNext < strips && lvl[sNext] < y && ((stripsAlongX ? z : x) % STRIP == STRIP - 1))
                        || (lvl[sPrev] < y && ((stripsAlongX ? z : x) % STRIP == 0))) face = true;
                for (int yy = t - 1; yy <= y - 1; yy++) {
                    BlockPos p = new BlockPos(x0 + x, yy, z0 + z);
                    if (yy == y - 1) continue;
                    level.setBlock(p, face ? wall : Blocks.DIRT.defaultBlockState(), 2);
                }
                boolean edge = x == 0 || x == w || z == 0 || z == d;
                if (edge) {
                    level.setBlock(ground, face ? wall : Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    boolean gap = z == d && x >= w / 2 - 1 && x <= w / 2 + 1;
                    if (!gap) level.setBlock(ground.above(), Blocks.OAK_FENCE.defaultBlockState(), 3);
                    continue;
                }
                if (face) {
                    level.setBlock(ground, wall, 2);
                    continue;
                }
                if (orchard) {
                    level.setBlock(ground, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    if (x % 5 == 2 && z % 5 == 2) tree(level, ground.above(), rnd);
                    continue;
                }
                int along = stripsAlongX ? x : z;
                if (along % 6 == 3) {
                    level.setBlock(ground, Blocks.WATER.defaultBlockState(), 3);
                    continue;
                }
                level.setBlock(ground, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), 2);
                if (crop instanceof CropBlock cb) {
                    level.setBlock(ground.above(), cb.getStateForAge(rnd.nextInt(cb.getMaxAge() + 1)), 2);
                }
            }
        }
        if (skipped > (w * d) / 4) return false;
        yOut[0] = ymin;
        yOut[1] = ymax;
        // the barn, outside the gate
        barn(level, x0 + w / 2 - 3, z0 + d + 3, lvl[stripsAlongX ? strips - 1 : strips / 2], rnd);
        return true;
    }

    /** A timber barn seven wide and six deep, open to the field, hay inside. */
    private static void barn(ServerLevel level, int x0, int z0, int y, RandomSource rnd) {
        int w = 6, d = 5;
        for (int x = 0; x <= w; x++) {
            for (int z = 0; z <= d; z++) {
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + x, z0 + z);
                if (Math.abs(gy - y) > 3 || !level.getFluidState(new BlockPos(x0 + x, gy - 1, z0 + z)).isEmpty()) return;
            }
        }
        BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState(), log = Blocks.SPRUCE_LOG.defaultBlockState(), cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int x = 0; x <= w; x++) {
            for (int z = 0; z <= d; z++) {
                for (int yy = y - 3; yy <= y + 7; yy++) {
                    BlockPos p = new BlockPos(x0 + x, yy, z0 + z);
                    if (yy < y - 1) { if (level.getBlockState(p).canBeReplaced()) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2); continue; }
                    if (yy == y - 1) { level.setBlock(p, cobble, 2); continue; }
                    boolean corner = (x == 0 || x == w) && (z == 0 || z == d);
                    boolean wallX = x == 0 || x == w, wallZ = z == d;      // open toward the field (z0 side)
                    if (yy <= y + 2) {
                        if (corner) level.setBlock(p, log, 2);
                        else if (wallX || wallZ) level.setBlock(p, (yy == y + 1 && (x + z) % 3 == 0) ? Blocks.OAK_FENCE.defaultBlockState() : plank, 2);
                        else if (yy == y && (x + z) % 2 == 0 && z > 1) level.setBlock(p, Blocks.HAY_BLOCK.defaultBlockState(), 2);
                        else level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    } else {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        // a gable roof over it, ridge along z
        for (int k = 0; k <= w / 2 + 1; k++) {
            int yy = y + 3 + k;
            for (int z = -1; z <= d + 1; z++) {
                int xl = x0 - 1 + k, xr = x0 + w + 1 - k;
                if (xl < xr) {
                    level.setBlock(new BlockPos(xl, yy, z0 + z), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.EAST), 2);
                    level.setBlock(new BlockPos(xr, yy, z0 + z), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, net.minecraft.core.Direction.WEST), 2);
                    if (z == -1 || z == d + 1) continue;
                    for (int x = xl + 1; x < xr; x++) {
                        BlockPos p = new BlockPos(x, yy, z0 + z);
                        if (z == 0 || z == d) level.setBlock(p, plank, 2);
                        else level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                } else if (xl == xr) {
                    level.setBlock(new BlockPos(xl, yy, z0 + z), Blocks.SPRUCE_SLAB.defaultBlockState(), 2);
                }
            }
        }
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
