package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a house's army does at your shut gate while you're there to see it. A quarter minute held at the walls and
 * they bring up a ram: a timber shed on the road before the gate with a log beam slung inside, swung against the
 * portcullis every three seconds while a man of theirs is at it. Ten blows and the gate gives. Break or burn the
 * beam (two logs left and it's done for) or cut down its crew and the gate holds. Forty seconds at the walls and
 * ladders go up the curtain on either side of the gate, three men to each; break the rungs to throw them down.
 * Three minutes and they hack the gate down with axes whatever you do, so an attack never stands there for ever.
 */
public final class SiegeWorks {
    public static final int RAM_AFTER = 15, LADDERS_AFTER = 40, AXES_AFTER = 180;
    public static final int RAM_HITS = 10, RAM_EVERY = 3, CREW_RANGE = 7;
    /** The shed's length out from the gate, and the beam's: drawn back it lies in cells 1 to BEAM, struck in 0 to BEAM - 1. */
    static final int SHED = 5, BEAM = 4;

    /** The cell before the outer portcullis where the ram's head strikes, and the way into the castle from it. */
    public record Site(BlockPos head, Direction in) {
    }

    /** Once a second while a watched raid is held at the walls. */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        if (c.rammed) return;
        c.siegeSeconds++;
        List<Mob> men = live(level, c);
        if (!shut(level, h)) {
            // nothing standing in the gate (broken, or never there): straight in
            c.rammed = true;
            clear(level, c);
            return;
        }
        if (c.ramHead == null && !c.ramDone && c.siegeSeconds >= RAM_AFTER) {
            Site s = site(level, h);
            if (s != null && raise(level, c, s)) {
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("They bring up a ram against the gate of " + h.name + " ("
                        + s.head().getX() + ", " + s.head().getY() + ", " + s.head().getZ() + ")! Ten blows and it's down: break or burn its "
                        + "beam, or kill the men working it.").withStyle(ChatFormatting.RED));
            } else {
                c.ramDone = true;            // no room for one before this gate
            }
        }
        if (c.ramHead != null) {
            Site s = new Site(c.ramHead, c.ramIn);
            if (beam(level, s) < 2) {
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The ram before " + h.name + " is wrecked!").withStyle(ChatFormatting.GREEN));
                level.playSound(null, s.head(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
                clear(level, c);
                c.ramDone = true;
            } else if (crewed(s, men)) {
                int phase = c.siegeSeconds % RAM_EVERY;
                if (phase == 0) {
                    swing(level, s, true);
                    strike(level, h, s);
                    if (++c.ramHits >= RAM_HITS) {
                        breach(level, h, c, "The ram staves in the gate of " + h.name + "! They're inside the walls.");
                        return;
                    }
                } else if (phase == 1) {
                    swing(level, s, false);
                }
            }
        }
        if (!c.laddered && c.siegeSeconds >= LADDERS_AFTER) {
            c.laddered = true;
            Vec3 mid = mid(men, h);
            int raised = Warbands.ladders(level, h, men, mid, c.to);
            if (raised > 0) HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Ladders against the walls of " + h.name + ": "
                    + raised + (raised == 1 ? " place" : " places") + "! Break the rungs to throw them down.").withStyle(ChatFormatting.RED));
        }
        // men sent up a ladder walk to its foot (their march only brings them within a few blocks)
        for (Mob m : men) Warbands.toLadder(m);
        if (c.siegeSeconds >= AXES_AFTER) breach(level, h, c, "Axes hack through the gate of " + h.name + "! They're inside the walls.");
    }

    private static void breach(ServerLevel level, Holding h, Campaign c, String says) {
        c.rammed = true;
        clear(level, c);
        if (Gates.breach(level, h)) HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(says).withStyle(ChatFormatting.RED));
    }

    private static List<Mob> live(ServerLevel level, Campaign c) {
        List<Mob> out = new ArrayList<>();
        for (UUID id : c.live.keySet()) if (level.getEntity(id) instanceof Mob m && m.isAlive()) out.add(m);
        return out;
    }

    private static Vec3 mid(List<Mob> men, Holding h) {
        if (men.isEmpty()) return Vec3.atCenterOf(Campaigns.gateOutside(h));
        double sx = 0, sz = 0;
        for (Mob m : men) {
            sx += m.getX();
            sz += m.getZ();
        }
        return new Vec3(sx / men.size(), 0, sz / men.size());
    }

    // ------------------------------------------------------------ the gate

    /** The outermost portcullis: the plane farthest from the castle's middle. */
    @Nullable
    static Gates.Plane outer(Holding h) {
        if (h.gates == null || h.gates.isEmpty()) return null;
        Vec3 c = Vec3.atCenterOf(h.center());
        Gates.Plane best = null;
        double bestD = -1;
        for (Gates.Plane p : h.gates) {
            if (p.area == null) continue;
            double d = p.area.getCenter().multiply(1, 0, 1).distanceToSqr(c.multiply(1, 0, 1));
            if (d > bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Whether the outer gate still stands. */
    static boolean shut(ServerLevel level, Holding h) {
        if (Gates.down(level, h)) return false;
        Gates.Plane p = outer(h);
        if (p == null) return false;
        for (BlockPos b : p.blocks) if (!level.isLoaded(b) || level.getBlockState(b).is(ModBlocks.PORTCULLIS.get())) return true;
        return false;
    }

    /** Where a ram goes before this castle's gate: the cell outside the outer portcullis, on its floor. */
    @Nullable
    public static Site site(ServerLevel level, Holding h) {
        Gates.Plane p = outer(h);
        if (p == null) return null;
        AABB a = p.area;
        int sx = (int) Math.round(a.maxX - a.minX), sz = (int) Math.round(a.maxZ - a.minZ);
        Vec3 pc = a.getCenter(), hc = Vec3.atCenterOf(h.center());
        Direction out;
        if (sx <= 1 && sz > 1) out = pc.x >= hc.x ? Direction.EAST : Direction.WEST;
        else if (sz <= 1 && sx > 1) out = pc.z >= hc.z ? Direction.SOUTH : Direction.NORTH;
        else return null;
        int y = (int) Math.floor(a.minY);
        int x, z;
        if (out.getAxis() == Direction.Axis.X) {
            x = out == Direction.EAST ? (int) Math.round(a.maxX) : (int) Math.round(a.minX) - 1;
            z = (int) Math.floor(pc.z);
        } else {
            z = out == Direction.SOUTH ? (int) Math.round(a.maxZ) : (int) Math.round(a.minZ) - 1;
            x = (int) Math.floor(pc.x);
        }
        return new Site(new BlockPos(x, y, z), out.getOpposite());
    }

    // ------------------------------------------------------------ the ram

    private static BlockPos cell(Site s, int along, int side, int up) {
        Direction out = s.in().getOpposite(), right = s.in().getClockWise();
        return s.head().relative(out, along).relative(right, side).above(up);
    }

    private static BlockState log(Site s) {
        return Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, s.in().getAxis());
    }

    /** The shed and its beam, drawn back. False (and nothing left standing) if the beam won't fit. */
    public static boolean raise(ServerLevel level, Campaign c, Site s) {
        for (int k = 1; k <= BEAM; k++) {
            BlockState here = level.getBlockState(cell(s, k, 0, 1));
            if (!here.isAir() && !here.canBeReplaced()) return false;
        }
        BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        BlockState roof = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int k = 0; k < SHED; k++) {
            for (int side : new int[]{-1, 1}) {
                put(level, cell(s, k, side, 0), fence);
                put(level, cell(s, k, side, 1), fence);
            }
            for (int side = -1; side <= 1; side++) put(level, cell(s, k, side, 2), roof);
        }
        for (int k = 1; k <= BEAM; k++) put(level, cell(s, k, 0, 1), log(s));
        // the head shod in iron
        level.playSound(null, s.head(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 2.0F, 0.5F);
        c.ramHead = s.head();
        c.ramIn = s.in();
        c.ramHits = 0;
        return true;
    }

    private static void put(ServerLevel level, BlockPos p, BlockState s) {
        BlockState here = level.getBlockState(p);
        if (here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty()) level.setBlock(p, s, 3);
    }

    /** How many logs of the beam are left. */
    public static int beam(ServerLevel level, Site s) {
        int n = 0;
        for (int k = 0; k <= BEAM; k++) if (level.getBlockState(cell(s, k, 0, 1)).is(Blocks.SPRUCE_LOG)) n++;
        return n;
    }

    /** Swing the beam into the gate, or draw it back: the log at one end goes to the other, if both are as they should be. */
    public static void swing(ServerLevel level, Site s, boolean forward) {
        BlockPos from = cell(s, forward ? BEAM : 0, 0, 1), to = cell(s, forward ? 0 : BEAM, 0, 1);
        if (!level.getBlockState(from).is(Blocks.SPRUCE_LOG)) return;
        BlockState t = level.getBlockState(to);
        if (!t.isAir() && !t.canBeReplaced()) return;
        level.setBlock(to, log(s), 3);
        level.setBlock(from, Blocks.AIR.defaultBlockState(), 3);
    }

    private static void strike(ServerLevel level, Holding h, Site s) {
        BlockPos gate = s.head().relative(s.in());
        level.playSound(null, gate, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.HOSTILE, 3.0F, 0.6F);
        level.playSound(null, gate, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.8F, 0.5F);
        BlockState st = level.getBlockState(gate.above());
        if (st.isAir()) st = ModBlocks.PORTCULLIS.get().defaultBlockState();
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), gate.getX() + 0.5, gate.getY() + 1.5, gate.getZ() + 0.5,
                24, 0.6, 0.6, 0.6, 0.1);
    }

    private static boolean crewed(Site s, List<Mob> men) {
        Vec3 at = Vec3.atCenterOf(cell(s, 2, 0, 0));
        for (Mob m : men) if (m.distanceToSqr(at) <= CREW_RANGE * CREW_RANGE) return true;
        return false;
    }

    /** Take what's left of the ram away: the shed and the beam, whatever of it still stands. */
    public static void clear(ServerLevel level, Campaign c) {
        if (c.ramHead == null) return;
        Site s = new Site(c.ramHead, c.ramIn);
        if (!level.isLoaded(s.head())) return;
        for (int k = 0; k < SHED; k++) {
            for (int side = -1; side <= 1; side++) {
                for (int up = 0; up <= 2; up++) {
                    BlockPos p = cell(s, k, side, up);
                    BlockState b = level.getBlockState(p);
                    if (b.is(Blocks.SPRUCE_FENCE) || b.is(Blocks.SPRUCE_SLAB) || b.is(Blocks.SPRUCE_LOG) || b.is(Blocks.FIRE))
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        c.ramHead = null;
    }

    /** Anyone close to the ram, for the tests. */
    static boolean near(Entity e, Site s, int r) {
        return e.distanceToSqr(Vec3.atCenterOf(s.head())) <= r * r;
    }

    private SiegeWorks() {
    }
}
