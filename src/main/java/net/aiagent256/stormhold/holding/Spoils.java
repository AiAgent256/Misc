package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.UUID;

/**
 * The garrison's takings: whatever your men kill in or near a place you hold (mobs, a dragon, raiders,
 * a house's knights) they strip and carry to the place's spoils chest, a chest with a sign reading
 * "Spoils". If there's none they set one up beside the War Table. What won't fit goes to the stores, and
 * gear that the stores can't hold is left where it fell.
 */
public final class Spoils {
    /** How far from a place's walls its men still bring the takings home. */
    public static final int REACH = 160;

    public static void onDrops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (!(dead.level() instanceof ServerLevel level) || dead instanceof Player || e.getDrops().isEmpty()) return;
        Entity killer = e.getSource().getEntity();
        if (!(killer instanceof GarrisonEntity g)) return;
        UUID lord = Sides.playerId(g.side());
        if (lord == null) return;
        if (dead instanceof GarrisonEntity d && lord.equals(Sides.playerId(d.side()))) return;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = home(mgr, g, lord, dead.blockPosition());
        java.util.function.Predicate<ItemStack> which = s -> true;
        if (h == null && dead instanceof GarrisonEntity foe && Sides.playerId(foe.side()) == null) {
            // a house's man cut down on campaign, far from home: his kit goes back on the baggage carts
            h = nearestOwned(mgr, lord, dead.blockPosition());
            which = ItemStack::isDamageableItem;
        }
        if (h == null) return;
        boolean carried = !h.box.inflatedBy(REACH).isInside(dead.blockPosition());
        int moved = take(level, mgr, h, e.getDrops().iterator(), which, carried);
        if (moved > 0) {
            h.spoilsTaken += moved;
            mgr.setDirty();
        }
    }

    /** The place a man brings his takings to: his own castle, else the nearest place his lord holds, if near. */
    @Nullable
    static Holding home(HoldingManager mgr, GarrisonEntity g, UUID lord, BlockPos at) {
        if (g.holdingId() != null) {
            Holding own = mgr.holdings.get(g.holdingId());
            if (own != null && lord.equals(own.ownerPlayer()) && own.box.inflatedBy(REACH).isInside(at)) return own;
        }
        Holding best = null;
        double bestD = Double.MAX_VALUE;
        for (Holding h : mgr.holdings.values()) {
            if (!lord.equals(h.ownerPlayer()) || !h.box.inflatedBy(REACH).isInside(at)) continue;
            double d = h.center().distSqr(at);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** The place the lord holds nearest to {@code at}, however far. */
    @Nullable
    static Holding nearestOwned(HoldingManager mgr, UUID lord, BlockPos at) {
        Holding best = null;
        double bestD = Double.MAX_VALUE;
        for (Holding h : mgr.holdings.values()) {
            if (!lord.equals(h.ownerPlayer())) continue;
            double d = h.center().distSqr(at);
            if (d < bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    /** Into the chest, then the stores. How many items were carried off the ground. */
    public static int take(ServerLevel level, HoldingManager mgr, Holding h, Iterator<ItemEntity> drops) {
        return take(level, mgr, h, drops, s -> true, false);
    }

    /**
     * As above, for the drops {@code which} picks. {@code carried}: taken on campaign, far from the place, so
     * whatever can't go in its chest right now rides home and goes in when the place is next loaded.
     */
    public static int take(ServerLevel level, HoldingManager mgr, Holding h, Iterator<ItemEntity> drops,
                           java.util.function.Predicate<ItemStack> which, boolean carried) {
        Container chest = null;
        BlockPos at = Steward.spoilsChest(level, h);
        if (at == null) at = setUp(level, h);
        if (at != null) chest = Steward.container(level, at);
        int moved = 0;
        while (drops.hasNext()) {
            ItemEntity ie = drops.next();
            if (!which.test(ie.getItem())) continue;
            ItemStack st = ie.getItem().copy();
            int before = st.getCount();
            if (chest != null) st = put(chest, st);
            if (!st.isEmpty() && !st.hasTag()) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.getItem());
                if (id != null) {
                    h.addStock(id, st.getCount());
                    st = ItemStack.EMPTY;
                }
            }
            if (!st.isEmpty() && carried && h.spoilsCarried.size() < CARRY) {
                h.spoilsCarried.add(st);
                st = ItemStack.EMPTY;
            }
            moved += before - st.getCount();
            if (st.isEmpty()) {
                drops.remove();
                ie.discard();
            } else {
                ie.setItem(st);
            }
        }
        if (chest != null) chest.setChanged();
        return moved;
    }

    /** How much kit the baggage carts hold for a place before what's taken is left on the field. */
    public static final int CARRY = 54;

    /** The kit carried home from campaign goes into the spoils chest, once the place is loaded. Once in a while. */
    public static void unload(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.spoilsCarried.isEmpty()) return;
        BlockPos at = Steward.spoilsChest(level, h);
        if (at == null) at = setUp(level, h);
        Container chest = at == null ? null : Steward.container(level, at);
        if (chest == null) return;
        int in = 0;
        for (java.util.Iterator<ItemStack> it = h.spoilsCarried.iterator(); it.hasNext(); ) {
            ItemStack left = put(chest, it.next());
            if (!left.isEmpty()) break;
            it.remove();
            in++;
        }
        if (in == 0) return;
        chest.setChanged();
        mgr.setDirty();
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The baggage carts unload at " + h.name + ": " + in
                + (in == 1 ? " piece" : " pieces") + " of kit taken off the enemy dead, in the spoils chest ("
                + at.getX() + ", " + at.getY() + ", " + at.getZ() + ")" + (h.spoilsCarried.isEmpty() ? "." : ". The chest is full: "
                + h.spoilsCarried.size() + " more wait on the carts.")).withStyle(ChatFormatting.GOLD));
    }

    /** Into a container: onto stacks of the same thing first, then empty slots. What didn't fit. */
    static ItemStack put(Container c, ItemStack st) {
        int max = Math.min(st.getMaxStackSize(), c.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize() && !st.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (in.isEmpty() || in.getCount() >= max || !ItemStack.isSameItemSameTags(in, st)) continue;
            int k = Math.min(st.getCount(), max - in.getCount());
            in.grow(k);
            st.shrink(k);
        }
        for (int i = 0; i < c.getContainerSize() && !st.isEmpty(); i++) {
            if (!c.getItem(i).isEmpty()) continue;
            int k = Math.min(st.getCount(), max);
            c.setItem(i, st.split(k));
        }
        return st;
    }

    /** A chest beside the War Table with a "Spoils" sign on it, the first time there's something to put in it. */
    @Nullable
    public static BlockPos setUp(ServerLevel level, Holding h) {
        BlockPos table = h.warTablePos;
        if (table.equals(BlockPos.ZERO) || !level.isLoaded(table)) return null;
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos p = table.offset(dx, 0, dz);
                    BlockState here = level.getBlockState(p), up = level.getBlockState(p.above());
                    if (!here.isAir() || !up.isAir() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                    if (!h.box.isInside(p) || !level.getBlockState(p.above(2)).isAir()) continue;
                    level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
                    level.setBlock(p.above(), Blocks.OAK_SIGN.defaultBlockState(), 3);
                    if (level.getBlockEntity(p.above()) instanceof SignBlockEntity sign) {
                        sign.setText(new SignText().setMessage(0, Component.literal("Spoils")), true);
                        sign.setChanged();
                    }
                    Steward.SPOILS_AT.put(h.id, p);
                    HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The garrison of " + h.name + " has set up a spoils chest beside "
                            + "the War Table (" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "). What your men take off the dead goes in it."
                            + " Write \"sell\" under \"Spoils\" on its sign and the steward sells it for you.").withStyle(ChatFormatting.GOLD));
                    return p;
                }
            }
        }
        return null;
    }

    private Spoils() {
    }
}
