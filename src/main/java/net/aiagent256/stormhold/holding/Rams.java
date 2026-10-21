package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.registry.ModBlocks;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Your own ram. Sneak-use the War Horn looking at the gate of a castle not yours, from close in, and your men bring up
 * a battering ram before it: the Siege Machines ram when that mod is in the pack, else a timber shed with a beam. While
 * two of your company stand at it, it swings every three seconds; ten blows and the outer gate is down. Their archers
 * will go for the crew. Costs planks and iron, from your pack and then the realm's stores.
 */
public final class Rams {
    public static final int PLANKS = 32, IRON = 6, CREW = 2, CREW_RANGE = 8, REACH = 32;

    /** A ram of a player's standing before a gate. */
    public static final class Ram {
        public UUID owner;
        public UUID target;
        public BlockPos head = BlockPos.ZERO;
        public Direction in = Direction.NORTH;
        @Nullable public UUID machine;
        public int hits, seconds;
        public boolean idleWarned;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Owner", owner);
            t.putUUID("Target", target);
            t.put("Head", NbtUtils.writeBlockPos(head));
            t.putString("In", in.getName());
            if (machine != null) t.putUUID("Machine", machine);
            t.putInt("Hits", hits);
            return t;
        }

        public static Ram load(CompoundTag t) {
            Ram r = new Ram();
            r.owner = t.getUUID("Owner");
            r.target = t.getUUID("Target");
            r.head = NbtUtils.readBlockPos(t.getCompound("Head"));
            Direction d = Direction.byName(t.getString("In"));
            r.in = d == null ? Direction.NORTH : d;
            if (t.hasUUID("Machine")) r.machine = t.getUUID("Machine");
            r.hits = t.getInt("Hits");
            return r;
        }

        public SiegeWorks.Site site() {
            return new SiegeWorks.Site(head, in);
        }
    }

    /** The castle whose gate the player is looking at, close in, if it isn't his. */
    @Nullable
    public static Holding aimedAt(ServerLevel level, HoldingManager mgr, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getLookAngle().scale(REACH)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos p = hit.getBlockPos();
        Holding h = mgr.at(p, 4);
        if (h == null || player.getUUID().equals(h.ownerPlayer())) return null;
        // the gate itself, or near enough to it
        if (level.getBlockState(p).is(ModBlocks.PORTCULLIS.get())) return h;
        SiegeWorks.Site s = SiegeWorks.site(level, h);
        return s != null && s.head().distSqr(p) <= 8 * 8 ? h : null;
    }

    @Nullable
    static Ram of(HoldingManager mgr, UUID owner, UUID target) {
        for (Ram r : mgr.rams) if (r.owner.equals(owner) && r.target.equals(target)) return r;
        return null;
    }

    public static boolean raise(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding target) {
        SiegeWorks.Site s = SiegeWorks.site(level, target);
        if (s == null) {
            player.sendSystemMessage(Component.literal(target.name + " has no portcullis to ram.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!SiegeWorks.shut(level, target)) {
            player.sendSystemMessage(Component.literal("The gate of " + target.name + " is down already: in!").withStyle(ChatFormatting.GOLD));
            return false;
        }
        if (of(mgr, player.getUUID(), target.id) != null) {
            player.sendSystemMessage(Component.literal("Your ram stands before the gate of " + target.name + " already.").withStyle(ChatFormatting.GOLD));
            return false;
        }
        if (!player.isCreative()) {
            int planks = planks(mgr, player), iron = Stores.packPlusRealm(mgr, null, player, Items.IRON_INGOT);
            if (planks < PLANKS || iron < IRON) {
                player.sendSystemMessage(Component.literal("A ram takes " + PLANKS + " planks and " + IRON + " iron (pack or realm stores); you have "
                        + planks + " and " + iron + ".").withStyle(ChatFormatting.RED));
                return false;
            }
        }
        Ram r = new Ram();
        r.owner = player.getUUID();
        r.target = target.id;
        r.head = s.head();
        r.in = s.in();
        Entity m = SiegeWorks.machine(level, SiegeWorks.smRam(), s.head().relative(s.in().getOpposite(), 3), s.in().toYRot());
        if (m != null) {
            r.machine = m.getUUID();
        } else if (!SiegeWorks.raiseShed(level, s)) {
            player.sendSystemMessage(Component.literal("There's no room before the gate of " + target.name + " for a ram.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.isCreative()) {
            int owed = PLANKS;
            for (var plank : new net.minecraft.world.item.Item[]{Items.SPRUCE_PLANKS, Items.OAK_PLANKS, Items.DARK_OAK_PLANKS, Items.BIRCH_PLANKS})
                if (owed > 0) owed = Stores.takeFromPackThenRealm(mgr, null, player, plank, owed);
            Stores.takeFromPackThenRealm(mgr, null, player, Items.IRON_INGOT, IRON);
        }
        mgr.rams.add(r);
        mgr.setDirty();
        if (target.isHouseOwned()) {
            if (Truces.active(mgr, target.houseId, player.getUUID()))
                Truces.broken(level, mgr, target.houseId, player.getUUID(), "you brought a ram against " + target.name);
            net.aiagent256.stormhold.house.Standing.change(level, mgr, target.houseId, player.getUUID(), -10,
                    "you brought a ram against " + target.name);
        }
        player.sendSystemMessage(HoldingEvents.gold("Your men bring up a ram before the gate of " + target.name + ". With " + CREW
                + " of your company at it (within " + CREW_RANGE + " blocks) it swings every " + SiegeWorks.RAM_EVERY + " seconds: "
                + SiegeWorks.RAM_HITS + " blows and the gate is down. Keep their archers off the crew."));
        return true;
    }

    private static int planks(HoldingManager mgr, ServerPlayer p) {
        int n = 0;
        for (var plank : new net.minecraft.world.item.Item[]{Items.SPRUCE_PLANKS, Items.OAK_PLANKS, Items.DARK_OAK_PLANKS, Items.BIRCH_PLANKS})
            n += Stores.packPlusRealm(mgr, null, p, plank);
        return n;
    }

    /** Where the crew stands: by the machine, or in the shed. */
    static Vec3 crewSpot(ServerLevel level, Ram r) {
        Entity m = r.machine != null ? level.getEntity(r.machine) : null;
        if (m != null) return m.position();
        return Vec3.atCenterOf(r.head.relative(r.in.getOpposite(), 2));
    }

    /** Once a second: each ram whose lord is near and whose crew is at it swings when it's ready. */
    public static void tick(ServerLevel level, HoldingManager mgr) {
        if (mgr.rams.isEmpty()) return;
        for (Iterator<Ram> it = mgr.rams.iterator(); it.hasNext(); ) {
            Ram r = it.next();
            if (!level.isLoaded(r.head)) continue;
            Holding target = mgr.holdings.get(r.target);
            ServerPlayer lord = level.getServer().getPlayerList().getPlayer(r.owner);
            if (target == null || r.owner.equals(target.ownerPlayer()) || !SiegeWorks.shut(level, target)) {
                if (lord != null && target != null && !r.owner.equals(target.ownerPlayer()))
                    lord.sendSystemMessage(HoldingEvents.gold("The gate of " + target.name + " is down. In!"));
                take(level, r);
                it.remove();
                mgr.setDirty();
                continue;
            }
            Entity m = r.machine != null ? level.getEntity(r.machine) : null;
            // a machine not found is wrecked only once its ground is ticking (entities load a moment after the blocks)
            boolean wrecked = r.machine != null ? (m == null ? level.isPositionEntityTicking(r.head) : !m.isAlive())
                    : SiegeWorks.beam(level, r.site()) < 2;
            if (wrecked) {
                if (lord != null) lord.sendSystemMessage(Component.literal("Your ram before " + target.name + " is wrecked.").withStyle(ChatFormatting.RED));
                level.playSound(null, r.head, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
                take(level, r);
                it.remove();
                mgr.setDirty();
                continue;
            }
            if (lord == null || lord.level() != level || lord.distanceToSqr(Vec3.atCenterOf(r.head)) > 96.0 * 96.0) continue;
            Vec3 spot = crewSpot(level, r);
            GarrisonEntity hand = null;
            int crew = 0;
            for (GarrisonEntity g : Orders.company(level, lord)) {
                if (g.distanceToSqr(spot) > CREW_RANGE * CREW_RANGE) continue;
                crew++;
                if (hand == null || g.distanceToSqr(spot) < hand.distanceToSqr(spot)) hand = g;
            }
            if (crew < CREW) {
                if (!r.idleWarned) {
                    lord.sendSystemMessage(Component.literal("Your ram before " + target.name + " stands idle: it needs " + CREW + " of your company within "
                            + CREW_RANGE + " blocks of it.").withStyle(ChatFormatting.GOLD));
                    r.idleWarned = true;
                }
                continue;
            }
            r.idleWarned = false;
            int phase = ++r.seconds % SiegeWorks.RAM_EVERY;
            if (phase == 0) {
                if (blow(level, mgr, r, target, hand)) it.remove();
            } else if (phase == 1 && m == null) {
                SiegeWorks.swing(level, r.site(), false);
            }
        }
    }

    /** One blow at the gate. True when it's the last: the gate's down and the ram taken away (the caller drops it from the list). */
    public static boolean blow(ServerLevel level, HoldingManager mgr, Ram r, Holding target, @Nullable net.minecraft.world.entity.LivingEntity hand) {
        Entity m = r.machine != null ? level.getEntity(r.machine) : null;
        if (m != null) SiegeWorks.useMachine(m, hand);
        else SiegeWorks.swing(level, r.site(), true);
        SiegeWorks.strike(level, target, r.site());
        mgr.setDirty();
        if (++r.hits < SiegeWorks.RAM_HITS) return false;
        if (Gates.breach(level, target)) {
            HoldingEvents.tell(level, r.owner, HoldingEvents.gold("Your ram staves in the gate of " + target.name + "! In!"));
            Chronicle.log(level, mgr, Chronicle.WAR, "Your ram breaks the gate of " + target.name + ".", r.owner);
        }
        take(level, r);
        return true;
    }

    /** The ram's taken away: the machine, or the shed. */
    static void take(ServerLevel level, Ram r) {
        if (r.machine != null) {
            Entity m = level.getEntity(r.machine);
            if (m != null) m.discard();
        } else {
            SiegeWorks.clearShed(level, r.site());
        }
    }

    /** Your rams standing, for a test. */
    public static List<Ram> all(HoldingManager mgr) {
        return mgr.rams;
    }

    private Rams() {
    }
}
