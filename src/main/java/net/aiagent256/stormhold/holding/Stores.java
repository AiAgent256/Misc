package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.mixin.LootTableAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The realm's stores. Every castle keeps its own stock, but what a player holds is one realm: anything
 * that spends from a castle's stores spends from that castle first and then from the others the same
 * player holds. Couriers still carry goods to real chests; this is the ledger.
 *
 * Work done at a castle (repairs, fortifying, torches, roads, kits, the smiths' forging, a new fief building)
 * also draws on the chests, barrels and boxes inside that castle's walls and its fief's buildings, once the
 * ledger runs short: {@link #stockWithChests} and {@link #takeWithChests}. Only ground that's loaded is searched,
 * only plain goods are taken (nothing named or enchanted), and a loot chest nobody has opened stays sealed.
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

    // ------------------------------------------------------------ the castle's own chests

    /** The ledger across the realm, and what lies in the chests at {@code first}. */
    public static int stockWithChests(HoldingManager mgr, @Nullable Holding first, UUID player, ResourceLocation item) {
        return realmStock(mgr, first, player, item) + chestStock(mgr, first, item);
    }

    /** Spend from the ledger across the realm, then from the chests at {@code first}. Returns how many are still owed. */
    public static int takeWithChests(HoldingManager mgr, @Nullable Holding first, UUID player, ResourceLocation item, int n) {
        n = take(mgr, first, player, item, n);
        return n <= 0 ? 0 : takeFromChests(mgr, first, item, n);
    }

    /** The same for a castle with no lord: its own ledger, then its chests. */
    public static int stockWithChests(HoldingManager mgr, Holding h, ResourceLocation item) {
        return h.stock(item) + chestStock(mgr, h, item);
    }

    public static int takeWithChests(HoldingManager mgr, Holding h, ResourceLocation item, int n) {
        int k = Math.min(n, h.stock(item));
        if (k > 0) {
            h.addStock(item, -k);
            mgr.setDirty();
        }
        n -= k;
        return n <= 0 ? 0 : takeFromChests(mgr, h, item, n);
    }

    private record Count(long tick, Map<Item, Integer> items) {
    }

    /** A castle's chests counted at most once a tick: the builder asks after his stone every few ticks. */
    private static final Map<Holding, Count> COUNTS = new WeakHashMap<>();

    public static int chestStock(HoldingManager mgr, @Nullable Holding h, ResourceLocation id) {
        if (h == null) return 0;
        ServerLevel level = level(mgr);
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (level == null || item == null) return 0;
        long now = level.getGameTime();
        Count c = COUNTS.get(h);
        if (c == null || c.tick() != now) {
            Map<Item, Integer> m = new HashMap<>();
            for (Container ct : containers(level, h))
                for (int i = 0; i < ct.getContainerSize(); i++) {
                    ItemStack s = ct.getItem(i);
                    if (!s.isEmpty() && !s.hasTag()) m.merge(s.getItem(), s.getCount(), Integer::sum);
                }
            c = new Count(now, m);
            COUNTS.put(h, c);
        }
        return c.items().getOrDefault(item, 0);
    }

    private static int takeFromChests(HoldingManager mgr, @Nullable Holding h, ResourceLocation id, int n) {
        if (h == null) return n;
        ServerLevel level = level(mgr);
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (level == null || item == null) return n;
        for (Container ct : containers(level, h)) {
            boolean took = false;
            for (int i = 0; i < ct.getContainerSize() && n > 0; i++) {
                ItemStack s = ct.getItem(i);
                if (s.isEmpty() || !s.is(item) || s.hasTag()) continue;
                int k = Math.min(n, s.getCount());
                ct.removeItem(i, k);
                n -= k;
                took = true;
            }
            if (took) ct.setChanged();
            if (n <= 0) break;
        }
        COUNTS.remove(h);
        return Math.max(0, n);
    }

    /** Chests, barrels, shulker boxes and the like inside the castle's walls and its fief's buildings, on loaded ground. */
    private static List<Container> containers(ServerLevel level, Holding h) {
        List<BoundingBox> boxes = new ArrayList<>();
        boxes.add(h.box);
        for (Holding.FiefBuilding f : h.fief) boxes.add(f.box());
        List<Container> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BoundingBox b : boxes) {
            if (b.getXSpan() <= 1 && b.getZSpan() <= 1) continue;
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++)
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (!(be instanceof Container ct)) continue;
                        // a furnace's fuel and a brewing stand's bottles are in use, not in store
                        if (be instanceof AbstractFurnaceBlockEntity || be instanceof BrewingStandBlockEntity) continue;
                        if (be instanceof RandomizableContainerBlockEntity r && ((LootTableAccessor) r).stormhold$lootTable() != null) continue;
                        BlockPos p = be.getBlockPos();
                        if (!b.isInside(p) || !seen.add(p.immutable())) continue;
                        out.add(ct);
                    }
                }
        }
        return out;
    }

    /** The level whose holdings these are: the overworld, as a rule. */
    @Nullable
    private static ServerLevel level(HoldingManager mgr) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        if (HoldingManager.get(server.overworld()) == mgr) return server.overworld();
        for (ServerLevel l : server.getAllLevels()) if (l != server.overworld() && HoldingManager.get(l) == mgr) return l;
        return null;
    }

    private Stores() {
    }
}
