package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds guard posts, a throne spot and a banner spot inside a structure from another mod. */
public final class PostScanner {

    public static boolean allLoaded(ServerLevel level, Holding h) {
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    private static boolean standable(ServerLevel level, BlockPos p) {
        BlockState below = level.getBlockState(p.below());
        if (!below.isFaceSturdy(level, p.below(), Direction.UP)) return false;
        BlockState a = level.getBlockState(p), b = level.getBlockState(p.above());
        return a.getCollisionShape(level, p).isEmpty() && b.getCollisionShape(level, p.above()).isEmpty()
                && a.getFluidState().isEmpty() && b.getFluidState().isEmpty();
    }

    private record Spot(BlockPos pos, boolean sky) {
    }

    public static void scan(ServerLevel level, Holding h) {
        var box = h.box;
        List<Spot> spots = new ArrayList<>();
        int step = Math.max(2, Math.min(box.getXSpan(), box.getZSpan()) / 40);
        for (int x = box.minX() + 1; x < box.maxX(); x += step) {
            for (int z = box.minZ() + 1; z < box.maxZ(); z += step) {
                int top = Math.min(box.maxY(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                for (int y = top; y > box.minY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (standable(level, p)) {
                        spots.add(new Spot(p, level.canSeeSky(p)));
                        y -= 2;
                    }
                }
            }
        }
        if (spots.isEmpty()) {
            BlockPos c = h.center();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
            spots.add(new Spot(new BlockPos(c.getX(), y, c.getZ()), true));
        }
        BlockPos center = h.center();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Spot s : spots) {
            minY = Math.min(minY, s.pos.getY());
            maxY = Math.max(maxY, s.pos.getY());
        }
        final int lo = minY, hi = maxY;
        int highCut = lo + (int) ((hi - lo) * 0.55);
        List<BlockPos> high = new ArrayList<>(), low = new ArrayList<>();
        for (Spot s : spots) {
            if (s.sky && s.pos.getY() >= highCut) high.add(s.pos);
            else low.add(s.pos);
        }
        if (high.isEmpty()) high.addAll(low);
        if (low.isEmpty()) low.addAll(high);
        h.posts.put(Role.ARCHER, new ArrayList<>(high));
        h.posts.put(Role.CROSSBOWMAN, new ArrayList<>(high));
        h.posts.put(Role.KNIGHT, new ArrayList<>(low));
        h.posts.put(Role.CAPTAIN, new ArrayList<>(low));
        // throne: indoors, near the middle, low
        BlockPos lord = spots.stream().filter(s -> !s.sky && s.pos.getY() <= lo + (hi - lo) / 2)
                .map(Spot::pos).min(Comparator.comparingDouble(p -> horiz(p, center)))
                .orElse(low.get(0));
        h.lordPos = lord;
        h.warTablePos = lord.offset(2, 0, 0);
        // banner: the highest open spot near the middle
        BlockPos banner = spots.stream().filter(Spot::sky)
                .max(Comparator.comparingDouble(s -> s.pos.getY() - horiz(s.pos, center) * 0.25))
                .map(Spot::pos).orElse(high.get(0));
        h.bannerPos = banner;
        h.rallyPos = lord;
        int gz = box.maxZ() + 3, gx = center.getX();
        h.gatePos = new BlockPos(gx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz), gz);
        if (Config.DISABLE_FOREIGN_SPAWNERS.get()) removeSpawners(level, h);
        if (Config.CLEAR_FOREIGN_MOBS.get()) clearMobs(level, h);
        h.scanned = true;
        h.bannersDirty = true;
    }

    private static double horiz(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void removeSpawners(ServerLevel level, Holding h) {
        List<BlockPos> found = new ArrayList<>();
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
                if (c == null) continue;
                for (BlockEntity be : c.getBlockEntities().values()) {
                    if (be instanceof SpawnerBlockEntity && h.box.isInside(be.getBlockPos())) found.add(be.getBlockPos());
                }
            }
        }
        for (BlockPos p : found) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void clearMobs(ServerLevel level, Holding h) {
        AABB area = AABB.of(h.box).inflate(4);
        for (Entity e : level.getEntitiesOfClass(Entity.class, area, e -> e instanceof Enemy && !(e instanceof GarrisonEntity))) {
            e.discard();
        }
    }

    private PostScanner() {
    }
}
