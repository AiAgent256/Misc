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
import net.minecraft.world.entity.LivingEntity;
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
 * What a house's army does at your shut gate while you're there to see it: they ram it. A quarter minute held at the
 * walls and they bring up a ram (the Siege Machines battering ram when that mod is in the pack, else a timber shed with
 * a log beam slung inside) and swing it against the portcullis every three seconds while a man of theirs is at it.
 * Ten blows and the gate gives. Smash the ram (or break or burn the shed's beam: two logs left and it's done for), or
 * cut down its crew, and the gate holds. A wrecked ram is replaced 45 seconds later, three rams in all; a minute after
 * the last is wrecked they give it up and the attack is thrown back. No ladders, no axes: the gate is the only way in.
 */
public final class SiegeWorks {
    /** Seconds held at the walls before the first ram; after a ram's wrecked, before the next; rams in all; seconds after the last before they go. */
    public static final int RAM_AFTER = 15, RAM_AGAIN = 45, RAMS = 3, GIVE_UP = 60;
    public static final int RAM_HITS = 10, RAM_EVERY = 3, CREW_RANGE = 8;
    /** Seconds a Siege Machines ram takes to be ready once it's brought up. */
    static final int DEPLOY = 4;
    /** The shed's length out from the gate, and the beam's: drawn back it lies in cells 1 to BEAM, struck in 0 to BEAM - 1. */
    static final int SHED = 5, BEAM = 4;

    /** The cell before the outer portcullis where the ram's head strikes, and the way into the castle from it. */
    public record Site(BlockPos head, Direction in) {
    }

    /**
     * Once a second while a watched raid is held at the walls. True when they give it up: three rams wrecked and a
     * minute more before the gate, and the attack is thrown back.
     */
    public static boolean tick(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        if (c.rammed) return false;
        c.siegeSeconds++;
        List<Mob> men = live(level, c);
        if (!shut(level, h)) {
            // nothing standing in the gate (broken, or never there): straight in
            c.rammed = true;
            clear(level, c);
            return false;
        }
        boolean up = c.ramHead != null;
        if (!up && c.ramsUsed < RAMS && c.siegeSeconds >= Math.max(RAM_AFTER, c.nextRamAt)) {
            Site s = site(level, h);
            c.ramsUsed++;
            c.ramDone = false;
            boolean machine = s != null && raiseMachine(level, c, s);
            if (s != null && (machine || raise(level, c, s))) {
                c.ramRaisedAt = c.siegeSeconds;
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal((c.ramsUsed == 1 ? "They bring up a ram" : "They bring up another ram")
                        + " against the gate of " + h.name + " (" + s.head().getX() + ", " + s.head().getY() + ", " + s.head().getZ() + ")! "
                        + RAM_HITS + " blows and it's down: " + (machine ? "smash the ram" : "break or burn its beam") + ", or kill the men working it.")
                        .withStyle(ChatFormatting.RED));
            } else {
                c.ramsUsed = RAMS;           // no room for one before this gate
                c.ramDone = true;
                c.nextRamAt = c.siegeSeconds;
            }
        }
        if (c.ramHead != null) {
            Site s = new Site(c.ramHead, c.ramIn);
            Entity m = c.ramMachine != null ? level.getEntity(c.ramMachine) : null;
            boolean wrecked = c.ramMachine != null ? (m == null && level.isLoaded(s.head()) || m != null && !m.isAlive()) : beam(level, s) < 2;
            if (wrecked) {
                HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The ram before " + h.name + " is wrecked!"
                        + (c.ramsUsed < RAMS ? " They'll bring up another." : " It was their last.")).withStyle(ChatFormatting.GREEN));
                level.playSound(null, s.head(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
                clear(level, c);
                c.ramDone = true;
                c.nextRamAt = c.siegeSeconds + RAM_AGAIN;
            } else if (crewed(s, men) && c.siegeSeconds - c.ramRaisedAt >= DEPLOY) {
                int phase = c.siegeSeconds % RAM_EVERY;
                if (phase == 0) {
                    if (m != null) {
                        useMachine(m, nearest(men, m));
                        strike(level, h, s);
                    } else {
                        swing(level, s, true);
                        strike(level, h, s);
                    }
                    if (++c.ramHits >= RAM_HITS) {
                        breach(level, h, c, "The ram staves in the gate of " + h.name + "! They're inside the walls.");
                        return false;
                    }
                } else if (phase == 1 && m == null) {
                    swing(level, s, false);
                }
            }
        }
        // no ram left to them, and a minute more before the gate: they give it up
        return c.ramHead == null && c.ramsUsed >= RAMS && c.siegeSeconds >= c.nextRamAt + GIVE_UP;
    }

    // ------------------------------------------------------------ the garrison's part

    /**
     * Raiders come first. Every man of the garrison within 48 blocks of a raider, idle or busy with some monster in
     * the field, goes for the nearest raider, however high or low he stands (a guard's own eye looks only four blocks
     * up or down, so a man on a roof or a wall-walk was left alone). How many were set on one.
     */
    public static int hunt(ServerLevel level, Holding h, List<? extends Mob> raiders) {
        if (raiders.isEmpty()) return 0;
        java.util.Set<UUID> theirs = new java.util.HashSet<>();
        for (Mob r : raiders) theirs.add(r.getUUID());
        int set = 0;
        for (UUID id : h.live.keySet()) {
            if (!(level.getEntity(id) instanceof GarrisonEntity g) || !g.isAlive() || g.role() == net.aiagent256.stormhold.entity.Role.COURIER) continue;
            LivingEntity t = g.getTarget();
            if (t != null && t.isAlive() && theirs.contains(t.getUUID())) continue;
            // the nearest raider with room on him, so the garrison meets them man to man rather than all on the first
            List<Mob> near = new java.util.ArrayList<>();
            for (Mob r : raiders) if (r.isAlive() && r.distanceToSqr(g) < 48.0 * 48.0) near.add(r);
            LivingEntity best = net.aiagent256.stormhold.entity.ai.Engagement.pick(g, near);
            if (best == null) continue;
            g.setTarget(best);
            set++;
        }
        return set;
    }

    /**
     * When four in five of an attack have fallen, the rest lose heart: after fifteen seconds of it they run, and the
     * attack is broken. True when they've gone.
     */
    public static boolean rout(ServerLevel level, Holding h, Campaign c, String who) {
        if (c.mustered <= 0 || c.total() * 5 > c.mustered) {
            c.routSeconds = 0;
            return false;
        }
        if (++c.routSeconds < 15) return false;
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The last " + c.total() + " of " + who + " lose heart and run from "
                + h.name + ".").withStyle(ChatFormatting.GREEN));
        clear(level, c);
        return true;
    }

    @Nullable
    private static LivingEntity nearest(List<Mob> men, Entity to) {
        Mob best = null;
        for (Mob x : men) if (best == null || x.distanceToSqr(to) < best.distanceToSqr(to)) best = x;
        return best;
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
        if (!raiseShed(level, s)) return false;
        c.ramHead = s.head();
        c.ramIn = s.in();
        c.ramHits = 0;
        return true;
    }

    /** The shed and its beam before a gate, whoever's. False if the beam won't fit. */
    static boolean raiseShed(ServerLevel level, Site s) {
        // a bank of earth or snow where the beam swings is dug out; stone, timber or anything built is not
        List<BlockPos> dig = new ArrayList<>();
        for (int k = 1; k <= BEAM; k++) {
            BlockPos p = cell(s, k, 0, 1);
            BlockState here = level.getBlockState(p);
            if (here.isAir() || here.canBeReplaced()) continue;
            if (here.is(net.minecraft.tags.BlockTags.DIRT) || here.is(net.minecraft.tags.BlockTags.SAND) || here.is(Blocks.GRAVEL)
                    || here.is(Blocks.SNOW_BLOCK) || here.is(Blocks.CLAY) || here.is(Blocks.DIRT_PATH)) dig.add(p);
            else return false;
        }
        for (BlockPos p : dig) level.destroyBlock(p, false);
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

    static void strike(ServerLevel level, Holding h, Site s) {
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
        if (c.ramMachine != null) {
            Entity m = level.getEntity(c.ramMachine);
            if (m != null) m.discard();
            c.ramMachine = null;
        }
        if (c.ramHead == null) return;
        clearShed(level, new Site(c.ramHead, c.ramIn));
        c.ramHead = null;
    }

    /** What's left of a shed and beam before a gate. */
    static void clearShed(ServerLevel level, Site s) {
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
    }

    // ------------------------------------------------------------ the Siege Machines ram

    private static final net.minecraft.resources.ResourceLocation SM_RAM = new net.minecraft.resources.ResourceLocation("siegemachines", "battering_ram");
    @Nullable private static java.lang.reflect.Method USE;

    /** The Siege Machines battering ram, brought up before the gate facing it, if that mod is in the pack. */
    static boolean raiseMachine(ServerLevel level, Campaign c, Site s) {
        Entity e = machine(level, SM_RAM, s.head().relative(s.in().getOpposite(), 3), s.in().toYRot());
        if (e == null) return false;
        c.ramMachine = e.getUUID();
        c.ramHead = s.head();
        c.ramIn = s.in();
        c.ramHits = 0;
        return true;
    }

    /** A Siege Machines engine of this kind set down at {@code at} facing {@code yaw}; null if the mod isn't in the pack. */
    @Nullable
    static Entity machine(ServerLevel level, net.minecraft.resources.ResourceLocation id, BlockPos at, float yaw) {
        if (!net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.containsKey(id)) return null;
        net.minecraft.world.entity.EntityType<?> type = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(id);
        if (type == null) return null;
        Entity e;
        try {
            e = type.create(level);
        } catch (Exception ex) {
            return null;
        }
        if (e == null) return null;
        e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0.0F);
        e.setYHeadRot(yaw);
        e.setYBodyRot(yaw);
        if (e instanceof Mob mob) mob.setPersistenceRequired();
        if (!level.addFreshEntity(e)) return null;
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 2.0F, 0.5F);
        return e;
    }

    static final net.minecraft.resources.ResourceLocation SM_TREBUCHET = new net.minecraft.resources.ResourceLocation("siegemachines", "trebuchet");
    static net.minecraft.resources.ResourceLocation smRam() {
        return SM_RAM;
    }

    /** Swing it: its own blow and its own animation, worked by the nearest of the crew. */
    static void useMachine(Entity machine, @Nullable LivingEntity by) {
        if (by == null) return;
        try {
            if (USE == null || !USE.getDeclaringClass().isInstance(machine)) USE = machine.getClass().getMethod("use", LivingEntity.class);
            USE.invoke(machine, by);
        } catch (Throwable t) {
            // a version of the mod without it: the blow still counts, it just doesn't swing
        }
    }

    /** Anyone close to the ram, for the tests. */
    static boolean near(Entity e, Site s, int r) {
        return e.distanceToSqr(Vec3.atCenterOf(s.head())) <= r * r;
    }

    private SiegeWorks() {
    }
}
