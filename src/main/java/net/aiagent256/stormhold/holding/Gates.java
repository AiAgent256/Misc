package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.block.PortcullisBlock;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The gate guard. Each portcullis plane of a castle raises when one of the castle's own men, or a
 * player the castle is friendly to, comes up to it, and drops again once they're through. It never
 * raises while an enemy is near it, so you still have to break in, and the garrison can only sortie
 * once you've backed off.
 */
public final class Gates {
    /** A friend this close to the plane raises it. */
    private static final double OPEN_RANGE = 6.0;
    /** An enemy this close keeps it down. */
    private static final double HOLD_RANGE = 7.0;
    /** How far around the gate markers to look for portcullis blocks. */
    private static final int SCAN = 16;

    public static final class Plane {
        final List<BlockPos> blocks = new ArrayList<>();
        AABB area;
        boolean open;
        int holdTicks;
    }

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.gates == null) {
            if (!scan(level, h)) return;
        }
        for (Plane p : h.gates) tickPlane(level, mgr, h, p);
    }

    /** Find the portcullis planes around the gate markers. False if those chunks aren't loaded yet. */
    private static boolean scan(ServerLevel level, Holding h) {
        List<BlockPos> markers = new ArrayList<>();
        if (!h.gatePos.equals(BlockPos.ZERO)) markers.add(h.gatePos);
        for (var e : h.extras.entrySet()) if (e.getKey().startsWith("gate")) markers.addAll(e.getValue());
        if (markers.isEmpty()) {
            h.gates = List.of();
            return true;
        }
        Set<BlockPos> seen = new HashSet<>();
        List<Plane> planes = new ArrayList<>();
        for (BlockPos m : markers) {
            for (int x = m.getX() - SCAN; x <= m.getX() + SCAN; x += 16) {
                for (int z = m.getZ() - SCAN; z <= m.getZ() + SCAN; z += 16) {
                    if (!level.isLoaded(new BlockPos(x, m.getY(), z))) return false;
                }
            }
            for (int x = m.getX() - SCAN; x <= m.getX() + SCAN; x++) {
                for (int z = m.getZ() - SCAN; z <= m.getZ() + SCAN; z++) {
                    for (int y = m.getY() - 2; y <= m.getY() + 14; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (seen.contains(p) || !level.getBlockState(p).is(ModBlocks.PORTCULLIS.get())) continue;
                        planes.add(flood(level, p, seen));
                    }
                }
            }
        }
        h.gates = planes;
        return true;
    }

    private static Plane flood(ServerLevel level, BlockPos start, Set<BlockPos> seen) {
        Plane plane = new Plane();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        int minX = start.getX(), maxX = minX, minY = start.getY(), maxY = minY, minZ = start.getZ(), maxZ = minZ;
        while (!q.isEmpty() && plane.blocks.size() < 400) {
            BlockPos p = q.poll();
            plane.blocks.add(p);
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            for (BlockPos n : new BlockPos[]{p.above(), p.below(), p.north(), p.south(), p.east(), p.west()}) {
                if (!seen.contains(n) && level.getBlockState(n).is(ModBlocks.PORTCULLIS.get())) {
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        plane.area = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        plane.open = level.getBlockState(start).getValue(PortcullisBlock.OPEN);
        return plane;
    }

    private static void tickPlane(ServerLevel level, HoldingManager mgr, Holding h, Plane plane) {
        if (!level.isLoaded(plane.blocks.get(0))) return;
        boolean friend = false, foe = false;
        for (Entity e : level.getEntities((Entity) null, plane.area.inflate(HOLD_RANGE, 1, HOLD_RANGE))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (e instanceof Player p && (p.isSpectator() || p.isCreative())) continue;
            double d = distance(plane.area, e);
            if (isFriend(mgr, h, e)) {
                if (d <= OPEN_RANGE) friend = true;
            } else if (isFoe(h, e)) {
                foe = true;
            }
        }
        boolean want = friend && !foe;
        if (want == plane.open) return;
        // don't slam it up and down every half second
        if (plane.holdTicks > 0) {
            plane.holdTicks--;
            return;
        }
        plane.holdTicks = 3;
        plane.open = want;
        for (BlockPos p : plane.blocks) {
            BlockState s = level.getBlockState(p);
            if (s.is(ModBlocks.PORTCULLIS.get()) && s.getValue(PortcullisBlock.OPEN) != want) {
                level.setBlock(p, s.setValue(PortcullisBlock.OPEN, want), 3);
            }
        }
        BlockPos c = BlockPos.containing(plane.area.getCenter());
        level.playSound(null, c, want ? SoundEvents.CHAIN_BREAK : SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1.5F, want ? 0.6F : 0.5F);
        level.playSound(null, c, want ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.5F);
    }

    /** Raiders with a ram: every portcullis of the castle comes down in splinters. */
    public static boolean breach(ServerLevel level, Holding h) {
        if (h.gates == null || h.gates.isEmpty()) return false;
        boolean any = false;
        for (Plane plane : h.gates) {
            for (BlockPos p : plane.blocks) {
                if (level.isLoaded(p) && level.getBlockState(p).is(ModBlocks.PORTCULLIS.get())) {
                    level.destroyBlock(p, false);
                    any = true;
                }
            }
            if (any) {
                BlockPos c = BlockPos.containing(plane.area.getCenter());
                level.playSound(null, c, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 2.0F, 0.7F);
                level.playSound(null, c, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 2.0F, 0.5F);
            }
        }
        h.gates = List.of();
        return any;
    }

    private static double distance(AABB box, Entity e) {
        double x = Math.max(0, Math.max(box.minX - e.getX(), e.getX() - box.maxX));
        double z = Math.max(0, Math.max(box.minZ - e.getZ(), e.getZ() - box.maxZ));
        return Math.sqrt(x * x + z * z);
    }

    /**
     * Someone the gate opens for: the castle's own men when they're going somewhere (marching, chasing,
     * riding with a player, carrying goods; a guard posted beside the gate doesn't hold it open), their
     * allies, and players the house likes.
     */
    private static boolean isFriend(HoldingManager mgr, Holding h, Entity e) {
        if (e instanceof GarrisonEntity g) {
            if (g.side().isEmpty() || !g.side().equals(h.owner)) return false;
            return g.marchTarget() != null || g.owner() != null || g.shipmentId() != null
                    || g.role() == net.aiagent256.stormhold.entity.Role.COURIER
                    || (g.getTarget() != null && !g.holdsPosition());
        }
        if (e instanceof Player p) {
            if (h.owner.equals(Sides.player(p.getUUID()))) return true;
            return h.isHouseOwned() && Standing.friendly(mgr, h.houseId, p.getUUID());
        }
        String rs = RecruitsCompat.sideOf(e);
        return rs != null && rs.equals(h.owner);
    }

    /** Someone the gate stays down for: anyone armed who isn't a friend. Villagers and animals don't count. */
    private static boolean isFoe(Holding h, Entity e) {
        if (e instanceof GarrisonEntity g) return !g.side().equals(h.owner);
        if (e instanceof Player) return true;
        if (e instanceof Enemy) return true;
        return RecruitsCompat.sideOf(e) != null;
    }

    private Gates() {
    }
}
