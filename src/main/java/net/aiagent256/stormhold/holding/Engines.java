package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The trebuchet: sneak-use the War Horn looking at the walls of a castle that isn't yours, from more than
 * thirty blocks off, and your men raise one where you stand, aimed at the wall facing you. While two of
 * your company crew it and you're within reach, it throws a stone every six seconds; each lands with a
 * crash that breaks the masonry where it strikes, and the shots walk along the wall. Thirty throws and the
 * arm gives. Costs planks and iron, from your pack and then the realm's stores.
 */
public final class Engines {
    public static final int PLANKS = 64, IRON = 8, SHOTS = 30, RELOAD = 6, CREW = 2, CREW_RANGE = 12, MIN_RANGE = 32, MAX_RANGE = 140;
    public static final float BLAST = 3.0F;

    /** A standing engine: where, whose, aimed at what, and how many throws it has left. */
    public static final class Engine {
        public UUID id = UUID.randomUUID();
        public BlockPos pos = BlockPos.ZERO;
        public UUID owner;
        public UUID target;
        public int shots = SHOTS;
        public long next;
        public boolean idleWarned;
        /** The Siege Machines trebuchet standing for it, when that mod is in the pack (the stones are still ours). */
        @Nullable public UUID machine;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.put("Pos", NbtUtils.writeBlockPos(pos));
            if (owner != null) t.putUUID("Owner", owner);
            if (target != null) t.putUUID("Target", target);
            t.putInt("Shots", shots);
            if (machine != null) t.putUUID("Machine", machine);
            return t;
        }

        public static Engine load(CompoundTag t) {
            Engine e = new Engine();
            e.id = t.getUUID("Id");
            e.pos = NbtUtils.readBlockPos(t.getCompound("Pos"));
            if (t.hasUUID("Owner")) e.owner = t.getUUID("Owner");
            if (t.hasUUID("Target")) e.target = t.getUUID("Target");
            e.shots = t.getInt("Shots");
            if (t.hasUUID("Machine")) e.machine = t.getUUID("Machine");
            return e;
        }
    }

    /** Stones in flight: the falling block and the engine that threw it (not saved: a stone in the air when you quit is lost). */
    private static final Map<UUID, Vec3> FLYING = new HashMap<>();

    // ------------------------------------------------------------ raising one

    /** What the player is looking at, if it's a castle not his own, far enough off for an engine. */
    @Nullable
    public static Holding aimedAt(ServerLevel level, HoldingManager mgr, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(MAX_RANGE + 20));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        if (p.distSqr(player.blockPosition()) < MIN_RANGE * MIN_RANGE) return null;
        Holding h = mgr.at(p, 2);
        if (h == null || player.getUUID().equals(h.ownerPlayer())) return null;
        return h;
    }

    public static boolean raise(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding target) {
        double dist = Math.sqrt(target.center().distSqr(player.blockPosition()));
        if (dist > MAX_RANGE + Math.max(target.box.getXSpan(), target.box.getZSpan()) / 2.0) {
            player.sendSystemMessage(Component.literal(target.name + " is out of a trebuchet's reach; get within " + MAX_RANGE + " blocks of its walls.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.isCreative()) {
            int planks = Stores.packPlusRealm(mgr, null, player, Items.SPRUCE_PLANKS) + Stores.packPlusRealm(mgr, null, player, Items.OAK_PLANKS)
                    + Stores.packPlusRealm(mgr, null, player, Items.DARK_OAK_PLANKS);
            int iron = Stores.packPlusRealm(mgr, null, player, Items.IRON_INGOT);
            if (planks < PLANKS || iron < IRON) {
                player.sendSystemMessage(Component.literal("A trebuchet takes " + PLANKS + " planks and " + IRON + " iron (pack or realm stores); you have "
                        + planks + " and " + iron + ".").withStyle(ChatFormatting.RED));
                return false;
            }
        }
        Direction toward = Direction.getNearest(target.center().getX() - player.getX(), 0, target.center().getZ() - player.getZ());
        if (toward.getAxis() == Direction.Axis.Y) toward = player.getDirection();
        BlockPos base = player.blockPosition().relative(toward.getOpposite(), 2);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ());
        base = new BlockPos(base.getX(), y, base.getZ());
        if (!level.getBlockState(base.below()).getFluidState().isEmpty()) {
            player.sendSystemMessage(Component.literal("Not on water: find dry ground for the trebuchet.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.isCreative()) {
            int owed = PLANKS;
            owed = Stores.takeFromPackThenRealm(mgr, null, player, Items.SPRUCE_PLANKS, owed);
            if (owed > 0) owed = Stores.takeFromPackThenRealm(mgr, null, player, Items.OAK_PLANKS, owed);
            if (owed > 0) Stores.takeFromPackThenRealm(mgr, null, player, Items.DARK_OAK_PLANKS, owed);
            Stores.takeFromPackThenRealm(mgr, null, player, Items.IRON_INGOT, IRON);
        }
        // the Siege Machines trebuchet where that mod is in the pack, else our own timber frame
        net.minecraft.world.entity.Entity machine = SiegeWorks.machine(level, SiegeWorks.SM_TREBUCHET, base, toward.toYRot());
        if (machine == null) build(level, base, toward);
        if (target.isHouseOwned() && Truces.active(mgr, target.houseId, player.getUUID()))
            Truces.broken(level, mgr, target.houseId, player.getUUID(), "you set a trebuchet against " + target.name);
        if (target.isHouseOwned()) net.aiagent256.stormhold.house.Standing.change(level, mgr, target.houseId, player.getUUID(), -10,
                "you set a trebuchet against " + target.name);
        Engine e = new Engine();
        e.pos = base.above(5);
        e.owner = player.getUUID();
        e.target = target.id;
        e.next = level.getGameTime() + 20L * RELOAD;
        if (machine != null) e.machine = machine.getUUID();
        mgr.engines.add(e);
        mgr.setDirty();
        level.playSound(null, base, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.5F, 0.6F);
        player.sendSystemMessage(HoldingEvents.gold("Your men raise a trebuchet aimed at the walls of " + target.name + ", " + (int) dist
                + " blocks off. With two of your company working it (within " + CREW_RANGE + " blocks), it throws a stone every " + RELOAD
                + " seconds: " + SHOTS + " before the arm gives."));
        return true;
    }

    /** The frame: a timber base, two uprights with the axle across them, the arm, and the counterweight box. */
    public static void build(ServerLevel level, BlockPos base, Direction toward) {
        Direction side = toward.getClockWise();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, toward.getAxis());
        BlockState logAcross = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, side.getAxis());
        BlockState upright = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState arm = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, toward.getAxis());
        BlockState weight = Blocks.COBBLESTONE.defaultBlockState();
        for (int a = -3; a <= 3; a++) {
            for (int s : new int[]{-2, 2}) set(level, base.relative(toward, a).relative(side, s), log);
        }
        for (int s = -1; s <= 1; s++) {
            set(level, base.relative(toward, -3).relative(side, s), logAcross);
            set(level, base.relative(toward, 3).relative(side, s), logAcross);
        }
        for (int s : new int[]{-2, 2}) for (int h = 1; h <= 5; h++) set(level, base.relative(side, s).above(h), upright);
        for (int s = -1; s <= 1; s++) set(level, base.relative(side, s).above(5), logAcross);
        // the arm, cocked: long end low behind, short end up front over the weight
        int[][] armCells = {{-4, 3}, {-3, 4}, {-2, 4}, {-1, 5}, {1, 6}, {2, 7}};
        for (int[] c : armCells) set(level, base.relative(toward, c[0]).above(c[1]), arm);
        for (int a = 1; a <= 2; a++) for (int s = -1; s <= 1; s++) for (int h = 2; h <= 3; h++) set(level, base.relative(toward, a).relative(side, s).above(h), weight);
        set(level, base.relative(toward, 2).above(4), Blocks.CHAIN.defaultBlockState());
        set(level, base.relative(toward, 2).above(5), Blocks.CHAIN.defaultBlockState());
    }

    private static void set(ServerLevel level, BlockPos p, BlockState s) {
        BlockState here = level.getBlockState(p);
        if (here.isAir() || here.canBeReplaced()) level.setBlock(p, s, 3);
    }

    // ------------------------------------------------------------ throwing

    /** Once a second: each engine whose lord is near and whose crew is at their posts throws when it's ready. */
    public static void tick(ServerLevel level, HoldingManager mgr) {
        if (mgr.engines.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Engine> it = mgr.engines.iterator(); it.hasNext(); ) {
            Engine e = it.next();
            if (!level.isLoaded(e.pos) || now < e.next) continue;
            Holding target = e.target == null ? null : mgr.holdings.get(e.target);
            ServerPlayer lord = e.owner == null ? null : level.getServer().getPlayerList().getPlayer(e.owner);
            if (target == null || (lord != null && lord.getUUID().equals(target.ownerPlayer()))) {
                if (lord != null) lord.sendSystemMessage(Component.literal("The trebuchet before " + (target == null ? "the walls" : target.name)
                        + " has nothing left to throw at: the castle is yours.").withStyle(ChatFormatting.GOLD));
                it.remove();
                mgr.setDirty();
                continue;
            }
            if (e.machine != null) {
                var m = level.getEntity(e.machine);
                if (m == null ? level.isPositionEntityTicking(e.pos.below(5)) : !m.isAlive()) {
                    if (lord != null) lord.sendSystemMessage(Component.literal("Your trebuchet before " + target.name + " is wrecked.")
                            .withStyle(ChatFormatting.RED));
                    it.remove();
                    mgr.setDirty();
                    continue;
                }
            }
            if (lord == null || lord.level() != level || lord.distanceToSqr(Vec3.atCenterOf(e.pos)) > 200.0 * 200.0) continue;
            int crew = 0;
            for (GarrisonEntity g : Orders.company(level, lord)) if (g.distanceToSqr(Vec3.atCenterOf(e.pos)) <= CREW_RANGE * CREW_RANGE) crew++;
            if (crew < CREW) {
                if (!e.idleWarned) {
                    lord.sendSystemMessage(Component.literal("The trebuchet stands idle: it needs " + CREW + " of your company within " + CREW_RANGE
                            + " blocks to work it.").withStyle(ChatFormatting.GOLD));
                    e.idleWarned = true;
                }
                continue;
            }
            e.idleWarned = false;
            e.next = now + 20L * RELOAD;
            if (fire(level, e, target)) {
                e.shots--;
                mgr.setDirty();
                if (e.shots <= 0) {
                    lord.sendSystemMessage(Component.literal("The trebuchet's arm splits on its last throw. It's done.").withStyle(ChatFormatting.GOLD));
                    it.remove();
                }
            }
        }
    }

    /** A stone at the wall facing the engine: a ballistic throw at a point on the face, walking along it. */
    static boolean fire(ServerLevel level, Engine e, Holding target) {
        Vec3 from = Vec3.atCenterOf(e.pos);
        Warbands.WallFace face = Warbands.wallFace(level, target, from);
        BlockPos aim;
        if (face != null) {
            Direction along = face.in().getClockWise();
            int lateral = level.random.nextInt(9) - 4;
            int h = face.foot().getY() + 3 + level.random.nextInt(Math.max(1, face.top() - face.foot().getY() - 4));
            aim = face.foot().relative(face.in()).relative(along, lateral).atY(h);
        } else {
            aim = target.gatePos.equals(BlockPos.ZERO) ? target.center() : target.gatePos.above(3);
        }
        BlockPos launch = e.pos.above(3);
        if (!level.getBlockState(launch).isAir()) launch = launch.above();
        if (!level.getBlockState(launch).isAir()) return false;
        Vec3 v = velocity(Vec3.atCenterOf(launch), Vec3.atCenterOf(aim));
        FallingBlockEntity stone = FallingBlockEntity.fall(level, launch, Blocks.COBBLESTONE.defaultBlockState());
        stone.dropItem = false;
        stone.setDeltaMovement(v);
        FLYING.put(stone.getUUID(), stone.position());
        level.playSound(null, e.pos, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.5F, 0.5F);
        return true;
    }

    /**
     * The throw: a falling block gains 0.04 a tick downwards and keeps 98% of its speed each tick. The flight time
     * grows with the range; the horizontal speed covers the distance in that time, the vertical speed makes up
     * what gravity takes.
     */
    public static Vec3 velocity(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        int ticks = (int) Math.max(24, Math.min(90, flat / 1.4));
        double s = 0, drag = 1, fall = 0, vy = 0;
        for (int k = 0; k < ticks; k++) {
            s += drag;            // what one unit of starting speed adds up to over the flight
            vy = (vy - 0.04);     // what gravity alone does, tick by tick
            fall += vy;
            vy *= 0.98;
            drag *= 0.98;
        }
        // with drag the gravity-only displacement is slightly off the simple sum, close enough for a stone at a wall
        return new Vec3(dx / s, (dy - fall) / s, dz / s);
    }

    /** Every tick: a stone that has struck brings down the masonry where it lands. */
    public static void tickStones(ServerLevel level) {
        if (FLYING.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Vec3>> it = FLYING.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            var ent = level.getEntity(en.getKey());
            Vec3 p;
            if (ent instanceof FallingBlockEntity stone && !stone.isRemoved()) {
                boolean hit = stone.onGround() || stone.horizontalCollision || stone.verticalCollision || stone.time > 200;
                if (!hit) {
                    en.setValue(stone.position());
                    continue;
                }
                p = stone.position();
                stone.discard();
            } else {
                // landed and set itself down as a block since the last tick: it strikes where it was last seen
                p = en.getValue();
                if (!level.isLoaded(BlockPos.containing(p))) {
                    it.remove();
                    continue;
                }
            }
            it.remove();
            blast(level, p);
        }
    }

    /** A trebuchet stone bursting: an engine's blow, so it can break a castle's stone (see {@link Breach}). */
    public static void blast(ServerLevel level, Vec3 p) {
        Breach.engineBlast = true;
        try {
            level.explode(null, p.x, p.y, p.z, BLAST, Level.ExplosionInteraction.BLOCK);
        } finally {
            Breach.engineBlast = false;
        }
    }

    private Engines() {
    }

    @SuppressWarnings("unused")
    private static List<Engine> none() {
        return new ArrayList<>();
    }
}
