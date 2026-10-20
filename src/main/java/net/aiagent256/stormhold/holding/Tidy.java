package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.mixin.LootTableAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The steward keeps a castle's chests in order when its lord asks him to (the War Table's Works tab). Every half
 * minute while you're about: the spoils chest is emptied into the storage chests, each kind of thing is gathered into
 * the chest that already holds the most of it, and every storage chest is stacked and sorted. Storage chests are the
 * chests and barrels inside the walls with no sign on them: a signed chest (the steward's own, or one you've put a
 * sign on to keep him out) is left as it is, and so is a loot chest nobody has opened.
 */
public final class Tidy {
    /** One pass. Returns how many items were moved from one chest to another. */
    public static int tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.tidy || !h.isPlayerOwned()) return 0;
        Set<BlockPos> signed = new HashSet<>();
        List<BlockPos> found = new ArrayList<>();
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (!h.box.isInside(p)) continue;
                    if (be instanceof SignBlockEntity sign) {
                        BlockPos c = Steward.containerPos(level, sign);
                        if (c != null) signed.add(c);
                    } else if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) {
                        if (be instanceof RandomizableContainerBlockEntity r && ((LootTableAccessor) r).stormhold$lootTable() != null) continue;
                        found.add(p.immutable());
                    }
                }
            }
        }
        BlockPos spoilsAt = Steward.spoilsChest(level, h);
        // a sign on one half of a double chest keeps him out of both halves; so does the spoils chest's
        Set<BlockPos> keepOut = new HashSet<>();
        for (BlockPos p : signed) withPartner(level, p, keepOut);
        if (spoilsAt != null) withPartner(level, spoilsAt, keepOut);
        List<Container> store = new ArrayList<>();
        for (BlockPos p : found) {
            if (keepOut.contains(p)) continue;
            if (level.getBlockEntity(p) instanceof Container c) store.add(c);     // each half of a double chest on its own
        }
        if (store.isEmpty()) return 0;
        Set<Container> touched = new HashSet<>();
        // each kind's home: the chest that holds the most of it
        Map<Item, Container> home = new HashMap<>();
        Map<Item, Integer> most = new HashMap<>();
        for (Container c : store) {
            Map<Item, Integer> n = new HashMap<>();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) n.merge(s.getItem(), s.getCount(), Integer::sum);
            }
            for (var e : n.entrySet()) {
                if (e.getValue() > most.getOrDefault(e.getKey(), 0)) {
                    most.put(e.getKey(), e.getValue());
                    home.put(e.getKey(), c);
                }
            }
        }
        int moved = 0;
        // everything to its home chest
        for (Container c : store) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                Container to = home.get(s.getItem());
                if (to == null || to == c) continue;
                int k = put(to, c, i);
                if (k > 0) {
                    moved += k;
                    touched.add(c);
                    touched.add(to);
                }
            }
        }
        // the spoils chest emptied: into the kind's home, else the emptiest chest
        Container spoils = spoilsAt == null ? null : Steward.container(level, spoilsAt);
        if (spoils != null) {
            for (int i = 0; i < spoils.getContainerSize(); i++) {
                ItemStack s = spoils.getItem(i);
                if (s.isEmpty()) continue;
                Container to = home.get(s.getItem());
                if (to == null) to = emptiest(store);
                if (to == null) break;
                int k = put(to, spoils, i);
                if (k == 0 && to != emptiest(store)) {
                    to = emptiest(store);
                    if (to != null) k = put(to, spoils, i);
                }
                if (k > 0) {
                    moved += k;
                    touched.add(spoils);
                    touched.add(to);
                    home.putIfAbsent(s.getItem(), to);
                }
            }
        }
        // every chest stacked and sorted
        for (Container c : store) if (compact(c)) touched.add(c);
        for (Container c : touched) c.setChanged();
        if (moved > 0) {
            h.tidied += moved;
            mgr.setDirty();
        }
        return moved;
    }

    private static void withPartner(ServerLevel level, BlockPos p, Set<BlockPos> into) {
        into.add(p);
        var st = level.getBlockState(p);
        if (st.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && st.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)
                && st.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
            into.add(p.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(st)));
    }

    /** Move what of slot i in {@code from} fits into {@code to}. How many went. */
    private static int put(Container to, Container from, int i) {
        ItemStack s = from.getItem(i);
        ItemStack copy = s.copy();
        int before = copy.getCount();
        ItemStack left = Spoils.put(to, copy);
        int k = before - left.getCount();
        if (k > 0) from.removeItem(i, k);
        return k;
    }

    private static Container emptiest(List<Container> store) {
        Container best = null;
        int bestFree = 0;
        for (Container c : store) {
            int free = 0;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) free++;
            if (free > bestFree) {
                bestFree = free;
                best = c;
            }
        }
        return best;
    }

    /** Stacks merged and sorted by name, fullest first. False if the chest was in order already. */
    static boolean compact(Container c) {
        List<ItemStack> merged = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i).copy();
            if (s.isEmpty()) continue;
            for (ItemStack m : merged) {
                if (s.isEmpty()) break;
                if (!ItemStack.isSameItemSameTags(m, s)) continue;
                int room = Math.min(m.getMaxStackSize(), c.getMaxStackSize()) - m.getCount();
                if (room <= 0) continue;
                int k = Math.min(room, s.getCount());
                m.grow(k);
                s.shrink(k);
            }
            if (!s.isEmpty()) merged.add(s);
        }
        merged.sort(Comparator.comparing((ItemStack s) -> String.valueOf(ForgeRegistries.ITEMS.getKey(s.getItem())))
                .thenComparing(s -> -s.getCount()));
        if (merged.size() > c.getContainerSize()) return false;
        boolean same = true;
        for (int i = 0; i < c.getContainerSize() && same; i++) {
            ItemStack want = i < merged.size() ? merged.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(c.getItem(i), want)) same = false;
        }
        if (same) return false;
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, i < merged.size() ? merged.get(i) : ItemStack.EMPTY);
        return true;
    }

    private Tidy() {
    }
}
