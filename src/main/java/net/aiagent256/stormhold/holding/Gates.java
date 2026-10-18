package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.block.PortcullisBlock;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        public final List<BlockPos> blocks = new ArrayList<>();
        AABB area;
        boolean open;
        int holdTicks;
    }

    private static final java.util.Map<java.util.UUID, Long> TOLD = new java.util.HashMap<>();

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        // an inner gate whose chunks weren't loaded at the last look is looked for again in a while
        if (h.gates != null && h.gatesRescanAt > 0 && level.getGameTime() >= h.gatesRescanAt) h.gates = null;
        if (h.gates == null) {
            if (!scan(level, mgr, h)) return;
        }
        for (Plane p : h.gates) tickPlane(level, mgr, h, p);
    }

    /** Nobody about: any gate left up comes down, so a castle never sits there with its gate invisible. */
    public static void idle(ServerLevel level, Holding h) {
        if (h.gates == null) return;
        for (Plane p : h.gates) {
            if (p.open && level.isLoaded(p.blocks.get(0))) set(level, p, false, false);
        }
    }

    /**
     * Put back any gate the castle's layout has and the world doesn't: castles generated before their
     * template had a gate, and gates rammed in a raid that's since ended. Only fills air (and grass,
     * snow and the like), never a block someone placed. Returns the blocks placed.
     */
    public static int restore(ServerLevel level, HoldingManager mgr, Holding h) {
        Layout l = h.plans();
        if (l == null || l.gatePlanes.isEmpty()) return 0;
        for (Campaign c : mgr.campaigns) {
            if (c.stage != Campaign.Stage.DONE && h.id.equals(c.target)) return 0;     // not mid-siege
        }
        BlockPos o = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
        BlockState gate = ModBlocks.PORTCULLIS.get().defaultBlockState();
        int placed = 0;
        for (int[] b : l.gatePlanes) {
            BlockPos a = o.offset(b[0], b[1], b[2]), z = o.offset(b[3], b[4], b[5]);
            if (!level.isLoaded(a) || !level.isLoaded(z)) continue;
            boolean standing = false;
            for (BlockPos q : BlockPos.betweenClosed(a, z)) {
                if (level.getBlockState(q).is(ModBlocks.PORTCULLIS.get())) {
                    standing = true;
                    break;
                }
            }
            if (standing) continue;
            for (BlockPos q : BlockPos.betweenClosed(a, z)) {
                BlockState s = level.getBlockState(q);
                if (s.isAir() || (s.canBeReplaced() && s.getFluidState().isEmpty())) {
                    level.setBlock(q.immutable(), gate, 3);
                    placed++;
                }
            }
        }
        if (placed > 0) {
            net.aiagent256.stormhold.Stormhold.LOGGER.info("Stormhold: rebuilt the gate of {} ({} blocks)", h.name, placed);
        }
        return placed;
    }

    /**
     * Find every portcullis plane of the castle: the ones round the gate markers, and every plane the plans
     * record (the inner ward's gate, a shell keep's, a city's inner and upper gates), wherever they stand.
     * False if the gate markers' chunks aren't loaded yet; a recorded plane in an unloaded chunk is looked
     * for again later.
     */
    private static boolean scan(ServerLevel level, HoldingManager mgr, Holding h) {
        List<BlockPos> markers = new ArrayList<>();
        if (!h.gatePos.equals(BlockPos.ZERO)) markers.add(h.gatePos);
        for (var e : h.extras.entrySet()) if (e.getKey().startsWith("gate")) markers.addAll(e.getValue());
        if (markers.isEmpty()) {
            h.gates = List.of();
            return true;
        }
        for (BlockPos m : markers) {
            for (int x = m.getX() - SCAN; x <= m.getX() + SCAN + 15; x += 16) {
                for (int z = m.getZ() - SCAN; z <= m.getZ() + SCAN + 15; z += 16) {
                    if (!level.isLoaded(new BlockPos(Math.min(x, m.getX() + SCAN), m.getY(), Math.min(z, m.getZ() + SCAN)))) return false;
                }
            }
        }
        restore(level, mgr, h);
        Set<BlockPos> seen = new HashSet<>();
        List<Plane> planes = new ArrayList<>();
        h.gatesRescanAt = 0;
        Layout l = h.plans();
        if (l != null) {
            BlockPos o = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
            for (int[] b : l.gatePlanes) {
                BlockPos a = o.offset(b[0] - 1, b[1] - 1, b[2] - 1), z = o.offset(b[3] + 1, b[4] + 1, b[5] + 1);
                if (!level.isLoaded(a) || !level.isLoaded(z)) {
                    h.gatesRescanAt = level.getGameTime() + 400;
                    continue;
                }
                for (BlockPos q : BlockPos.betweenClosed(a, z)) {
                    if (seen.contains(q) || !level.getBlockState(q).is(ModBlocks.PORTCULLIS.get())) continue;
                    planes.add(flood(level, q.immutable(), seen));
                }
            }
        }
        for (BlockPos m : markers) {
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
        // a fresh look: every gate starts down, whatever state it was saved in
        for (Plane p : planes) if (p.open) set(level, p, false, false);
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
        Entity holder = null;
        List<ServerPlayer> waiting = new ArrayList<>();
        List<Entity> about = level.getEntities((Entity) null, plane.area.inflate(HOLD_RANGE, 1, HOLD_RANGE));
        // anyone the garrison at the gate is fighting right now is an enemy whatever their standing
        java.util.Set<java.util.UUID> fought = new java.util.HashSet<>();
        for (Entity e : about) {
            if (e instanceof GarrisonEntity g && g.side().equals(h.owner) && g.getTarget() != null) fought.add(g.getTarget().getUUID());
        }
        for (Entity e : about) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (e instanceof Player p && p.isSpectator()) continue;
            boolean creative = e instanceof Player p && p.isCreative();
            double d = distance(plane.area, e);
            if (!fought.contains(e.getUUID()) && isFriend(mgr, h, e)) {
                if (d <= OPEN_RANGE) {
                    friend = true;
                    if (e instanceof ServerPlayer sp) waiting.add(sp);
                }
            } else if (!creative && (fought.contains(e.getUUID()) || isFoe(mgr, h, e))) {
                foe = true;
                if (holder == null) holder = e;
            }
        }
        boolean want = friend && !foe;
        if (foe && !plane.open) {
            // a traveller at a shut gate is told what's keeping it shut, so he can deal with it or wait
            for (ServerPlayer sp : waiting) {
                Long last = TOLD.get(sp.getUUID());
                long now = level.getGameTime();
                if (last != null && now - last < 100) continue;
                TOLD.put(sp.getUUID(), now);
                String who = holder.getDisplayName().getString();
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("The gate stays down: " + who
                        + " is at it (" + holder.blockPosition().getX() + ", " + holder.blockPosition().getY() + ", "
                        + holder.blockPosition().getZ() + ").").withStyle(net.minecraft.ChatFormatting.GRAY), true);
            }
        }
        if (want == plane.open) return;
        // don't slam it up and down every half second
        if (plane.holdTicks > 0) {
            plane.holdTicks--;
            return;
        }
        plane.holdTicks = 3;
        set(level, plane, want, true);
    }

    private static void set(ServerLevel level, Plane plane, boolean open, boolean sound) {
        plane.open = open;
        for (BlockPos p : plane.blocks) {
            BlockState s = level.getBlockState(p);
            if (s.is(ModBlocks.PORTCULLIS.get()) && s.getValue(PortcullisBlock.OPEN) != open) {
                level.setBlock(p, s.setValue(PortcullisBlock.OPEN, open), 3);
            }
        }
        if (!sound) return;
        BlockPos c = BlockPos.containing(plane.area.getCenter());
        level.playSound(null, c, open ? SoundEvents.CHAIN_BREAK : SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 1.5F, open ? 0.6F : 0.5F);
        level.playSound(null, c, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.5F);
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
     * allies, and players. A house's gate opens to any traveller it has no quarrel with: everyone starts
     * wary, and wary is let in. Only a player the house holds hostile, or one its men are fighting at the
     * gate, is shut out. A player's own castle opens only for its owner.
     */
    private static boolean isFriend(HoldingManager mgr, Holding h, Entity e) {
        if (e instanceof GarrisonEntity g) {
            if (g.side().isEmpty()) return false;
            if (!g.side().equals(h.owner)) return welcomes(mgr, h, g.side());   // a traveller's own men come in with him
            return g.marchTarget() != null || g.owner() != null || g.shipmentId() != null
                    || g.role() == net.aiagent256.stormhold.entity.Role.COURIER
                    || (g.getTarget() != null && !g.holdsPosition());
        }
        if (e instanceof Player p) return welcomes(mgr, h, Sides.player(p.getUUID()));
        String rs = RecruitsCompat.sideOf(e);
        return rs != null && welcomes(mgr, h, rs);
    }

    /** The castle's own side, or a player the house doesn't hold hostile. */
    private static boolean welcomes(HoldingManager mgr, Holding h, String side) {
        if (side.equals(h.owner)) return true;
        java.util.UUID pid = Sides.playerId(side);
        return pid != null && h.isHouseOwned() && !Standing.hostile(mgr, h.houseId, pid);
    }

    /** Someone the gate stays down for: soldiers of another side, monsters, and players the house holds hostile
     *  (or anyone at all, at a castle a player owns). Villagers and animals don't count. */
    private static boolean isFoe(HoldingManager mgr, Holding h, Entity e) {
        if (e instanceof GarrisonEntity g) return !g.side().isEmpty() && !welcomes(mgr, h, g.side());
        if (e instanceof Player p) return !h.isHouseOwned() || Standing.hostile(mgr, h.houseId, p.getUUID());
        if (e instanceof Enemy) return true;
        String rs = RecruitsCompat.sideOf(e);
        return rs != null && !welcomes(mgr, h, rs);
    }

    private Gates() {
    }
}
