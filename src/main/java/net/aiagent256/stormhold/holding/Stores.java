package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The realm's stores. Every castle keeps its own stock, but what a player holds is one realm: anything
 * that spends from a castle's stores spends from that castle first and then from the others the same
 * player holds. Couriers still carry goods to real chests; this is the ledger.
 */
public final class Stores {
    /** The player's castles, the given one first. */
    public static List<Holding> realm(HoldingManager mgr, @Nullable Holding first, UUID player) {
        List<Holding> out = new ArrayList<>();
        if (first != null) out.add(first);
        String side = Sides.player(player);
        for (Holding h : mgr.holdings.values()) if (h != first && h.owner.equals(side)) out.add(h);
        return out;
    }

    public static int realmStock(HoldingManager mgr, @Nullable Holding first, UUID player, ResourceLocation item) {
        int n = 0;
        for (Holding h : realm(mgr, first, player)) n += h.stock(item);
        return n;
    }

    /** Take up to n of an item across the realm, nearest castle first. Returns how many could not be found. */
    public static int take(HoldingManager mgr, @Nullable Holding first, UUID player, ResourceLocation item, int n) {
        for (Holding h : realm(mgr, first, player)) {
            int k = Math.min(n, h.stock(item));
            if (k > 0) {
                h.addStock(item, -k);
                n -= k;
            }
            if (n <= 0) break;
        }
        if (n < 0) n = 0;
        mgr.setDirty();
        return n;
    }

    /** Take from the player's pack first, then the realm. Returns how many are still owed. */
    public static int takeFromPackThenRealm(HoldingManager mgr, @Nullable Holding first, Player player, Item item, int n) {
        for (ItemStack s : player.getInventory().items) {
            if (n == 0) break;
            if (!s.is(item)) continue;
            int k = Math.min(n, s.getCount());
            s.shrink(k);
            n -= k;
        }
        if (n <= 0) return 0;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? n : take(mgr, first, player.getUUID(), id, n);
    }

    public static int packPlusRealm(HoldingManager mgr, @Nullable Holding first, Player player, Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return player.getInventory().countItem(item) + (id == null ? 0 : realmStock(mgr, first, player.getUUID(), id));
    }

    private Stores() {
    }
}
