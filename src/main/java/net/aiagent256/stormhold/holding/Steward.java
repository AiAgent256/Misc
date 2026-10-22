package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The steward: standing orders written on a sign on a chest (or barrel) inside a castle or town you hold.
 * The first line says what to do with it, the lines under it how much or when:
 * <ul>
 * <li><b>Sell</b>: everything a shop would buy is sold at the shops' price, the emeralds left in the chest.</li>
 * <li><b>Stores</b>: everything goes into the castle's stores, where the builder, smiths and armoury draw on it.</li>
 * <li><b>Send</b> and a place's name: a courier carries it to that place's stores.</li>
 * <li><b>Keep</b> with lines like "64 bread": the chest is kept topped up from the realm's stores.</li>
 * <li><b>Buy</b> with the same lines: topped up, and what the stores lack is bought at the shops with the
 * emeralds in the chest, then the stores'.</li>
 * </ul>
 * "when full" on Sell, Stores or Send waits until the chest has no empty slot; "over 64" leaves that many of
 * each thing. Enchanted gear and anything else carrying data is left alone. He looks every ten seconds while
 * you're near, and tells you each morning what he's done.
 */
public final class Steward {
    public static final String SELL = "sell", STORES = "stores", SEND = "send", KEEP = "keep", BUY = "buy", SPOILS = "spoils", TREASURY = "treasury";

    /** A standing order read off a sign. */
    public record Rule(String kind, boolean whenFull, int over, Map<Item, Integer> want, String place, boolean sell) {
    }

    /** Where each place's spoils chest and treasury chest are, as last seen. */
    static final Map<UUID, BlockPos> SPOILS_AT = new HashMap<>(), TREASURY_AT = new HashMap<>();

    /** Chests the steward has already told the lord he's taken charge of, by holding. Not saved: he says it again after a restart. */
    private static final Map<UUID, Set<BlockPos>> KNOWN = new HashMap<>();

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.isPlayerOwned()) return;
        UUID owner = h.ownerPlayer();
        if (owner == null) return;
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> known = KNOWN.computeIfAbsent(h.id, k -> new HashSet<>());
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof SignBlockEntity sign) || !h.box.isInside(be.getBlockPos())) continue;
                    Rule r = read(sign);
                    if (r == null) continue;
                    BlockPos at = containerPos(level, sign);
                    if (at == null || seen.contains(at)) continue;
                    Container c = container(level, at);
                    if (c == null) continue;
                    seen.add(at);
                    if (r.kind().equals(SPOILS)) SPOILS_AT.putIfAbsent(h.id, at);
                    if (r.kind().equals(TREASURY)) TREASURY_AT.putIfAbsent(h.id, at);
                    if (known.add(at)) announce(level, h, r, at);
                    run(level, mgr, h, owner, r, c);
                }
            }
        }
        known.retainAll(seen);
    }

    /** The place's spoils chest: the one last seen, else the first signed "Spoils" in it, else null. */
    @Nullable
    public static BlockPos spoilsChest(ServerLevel level, Holding h) {
        return signed(level, h, SPOILS, SPOILS_AT);
    }

    /** The place's treasury chest: the one last seen, else the first signed "Treasury" in it, else null. */
    @Nullable
    public static BlockPos treasuryChest(ServerLevel level, Holding h) {
        return signed(level, h, TREASURY, TREASURY_AT);
    }

    @Nullable
    private static BlockPos signed(ServerLevel level, Holding h, String kind, Map<UUID, BlockPos> cache) {
        BlockPos at = cache.get(h.id);
        if (at != null && level.isLoaded(at) && container(level, at) != null) return at;
        cache.remove(h.id);
        for (int cx = h.box.minX() >> 4; cx <= h.box.maxX() >> 4; cx++) {
            for (int cz = h.box.minZ() >> 4; cz <= h.box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof SignBlockEntity sign) || !h.box.isInside(be.getBlockPos())) continue;
                    Rule r = read(sign);
                    if (r == null || !r.kind().equals(kind)) continue;
                    BlockPos p = containerPos(level, sign);
                    if (p == null) continue;
                    cache.put(h.id, p);
                    return p;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ reading the sign

    @Nullable
    public static Rule read(SignBlockEntity sign) {
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) lines[i] = clean(sign.getFrontText().getMessage(i, false).getString());
        if (lines[0].isEmpty() && lines[1].isEmpty()) for (int i = 0; i < 4; i++) lines[i] = clean(sign.getBackText().getMessage(i, false).getString());
        return parse(lines);
    }

    private static String clean(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\[\\]():!.,]", " ").trim().replaceAll("\\s+", " ");
    }

    /** The rule four sign lines spell, or null if it isn't one. */
    @Nullable
    public static Rule parse(String[] lines) {
        String head = lines[0];
        String kind = switch (head) {
            case "sell", "sell all", "steward sell" -> SELL;
            case "stores", "store", "to stores", "to the stores", "steward stores" -> STORES;
            case "send", "send to", "ship", "ship to" -> SEND;
            case "keep", "keep stocked", "stock", "steward keep" -> KEEP;
            case "buy", "keep and buy", "steward buy" -> BUY;
            case "spoils", "loot", "the spoils", "spoils of war" -> SPOILS;
            case "treasury", "the treasury", "coffers", "the coffers", "treasure" -> TREASURY;
            default -> head.startsWith("send to ") || head.startsWith("ship to ") ? SEND : null;
        };
        if (kind == null) return null;
        boolean full = false, sellToo = false;
        int over = 0;
        Map<Item, Integer> want = new java.util.LinkedHashMap<>();
        String place = head.startsWith("send to ") ? head.substring(8) : head.startsWith("ship to ") ? head.substring(8) : "";
        for (int i = 1; i < 4; i++) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            if (l.contains("when full") || l.equals("full") || l.equals("if full")) {
                full = true;
                continue;
            }
            if (kind.equals(SPOILS) && (l.equals("sell") || l.equals("and sell") || l.equals("sell them") || l.equals("sell it"))) {
                sellToo = true;
                continue;
            }
            var m = java.util.regex.Pattern.compile("^(?:over|keep|leave) (\\d+)$").matcher(l);
            if (m.find()) {
                over = Integer.parseInt(m.group(1));
                continue;
            }
            if (kind.equals(KEEP) || kind.equals(BUY)) {
                var q = java.util.regex.Pattern.compile("^(\\d+)x? (.+)$").matcher(l);
                var q2 = java.util.regex.Pattern.compile("^(.+?) x?(\\d+)$").matcher(l);
                int n;
                String name;
                if (q.find()) {
                    n = Integer.parseInt(q.group(1));
                    name = q.group(2);
                } else if (q2.find()) {
                    n = Integer.parseInt(q2.group(2));
                    name = q2.group(1);
                } else {
                    n = -1;
                    name = l;
                }
                Item item = item(name);
                if (item == null) continue;
                want.put(item, n < 0 ? item.getMaxStackSize() : Math.min(n, 27 * 64));
            } else if (kind.equals(SEND) && place.isEmpty()) {
                place = l;
            }
        }
        if ((kind.equals(KEEP) || kind.equals(BUY)) && want.isEmpty()) return null;
        if (kind.equals(SEND) && place.isEmpty()) return null;
        return new Rule(kind, full, over, want, place, sellToo);
    }

    private static Map<String, Item> byName;

    /** An item by its everyday name: "bread", "iron ingots", "arrows", "torches"; vanilla first. */
    @Nullable
    public static Item item(String name) {
        if (byName == null) {
            Map<String, Item> m = new HashMap<>();
            for (var e : ForgeRegistries.ITEMS.getEntries()) {
                ResourceLocation id = e.getKey().location();
                String path = id.getPath();
                if (id.getNamespace().equals("minecraft") || !m.containsKey(path)) m.put(path, e.getValue());
            }
            byName = m;
        }
        String k = name.trim().replace(' ', '_');
        if (k.contains(":")) {
            Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(k));
            return i == null || i == Items.AIR ? null : i;
        }
        for (String c : new String[]{k, k.endsWith("es") ? k.substring(0, k.length() - 2) : k, k.endsWith("s") ? k.substring(0, k.length() - 1) : k}) {
            Item i = byName.get(c);
            if (i != null && i != Items.AIR) return i;
        }
        return null;
    }

    // ------------------------------------------------------------ the chest

    /** The chest or barrel the sign is on: behind a wall sign, under a standing one, else beside it. */
    @Nullable
    public static BlockPos containerPos(ServerLevel level, SignBlockEntity sign) {
        BlockPos p = sign.getBlockPos();
        BlockState s = sign.getBlockState();
        List<BlockPos> tries = new ArrayList<>();
        if (s.getBlock() instanceof WallSignBlock && s.hasProperty(WallSignBlock.FACING)) tries.add(p.relative(s.getValue(WallSignBlock.FACING).getOpposite()));
        tries.add(p.below());
        tries.add(p.above());
        for (Direction d : Direction.Plane.HORIZONTAL) tries.add(p.relative(d));
        for (BlockPos t : tries) if (container(level, t) != null) return t;
        return null;
    }

    @Nullable
    public static Container container(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof ChestBlock chest) return ChestBlock.getContainer(chest, s, level, p, true);
        BlockEntity be = level.getBlockEntity(p);
        if (be instanceof SignBlockEntity) return null;
        return be instanceof Container c && c.getContainerSize() >= 5 ? c : null;
    }

    private static boolean full(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return false;
        return true;
    }

    /** Things the steward handles: plain goods, no enchantments, names, contents or damage. */
    private static boolean plain(ItemStack s) {
        return !s.isEmpty() && !s.hasTag();
    }

    // ------------------------------------------------------------ the work

    static void run(ServerLevel level, HoldingManager mgr, Holding h, UUID owner, Rule r, Container c) {
        switch (r.kind()) {
            case SELL -> {
                if (r.whenFull() && !full(c)) return;
                sell(h, r, c);
            }
            case STORES -> {
                if (r.whenFull() && !full(c)) return;
                for (var e : takeOut(c, r.over(), s -> true).entrySet()) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(e.getKey());
                    if (id == null) continue;
                    h.addStock(id, e.getValue());
                    h.stewardStored += e.getValue();
                }
            }
            case SEND -> {
                if (r.whenFull() && !full(c)) return;
                send(level, mgr, h, owner, r, c);
            }
            case KEEP, BUY -> keep(mgr, h, owner, r, c);
            case SPOILS -> {
                if (r.sell() && (!r.whenFull() || full(c))) sell(h, r, c);
            }
            default -> {
            }
        }
        c.setChanged();
        mgr.setDirty();
    }

    /** Take plain goods out beyond {@code over} of each kind; what came out, by item. */
    private static Map<Item, Integer> takeOut(Container c, int over, java.util.function.Predicate<Item> which) {
        Map<Item, Integer> have = new HashMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (plain(s) && which.test(s.getItem())) have.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        Map<Item, Integer> out = new HashMap<>();
        for (var e : have.entrySet()) {
            int n = e.getValue() - over;
            if (n > 0) out.put(e.getKey(), n);
        }
        for (var e : out.entrySet()) remove(c, e.getKey(), e.getValue());
        return out;
    }

    private static int remove(Container c, Item item, int n) {
        int left = n;
        for (int i = c.getContainerSize() - 1; i >= 0 && left > 0; i--) {
            ItemStack s = c.getItem(i);
            if (!plain(s) || !s.is(item)) continue;
            int k = Math.min(left, s.getCount());
            s.shrink(k);
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            left -= k;
        }
        return n - left;
    }

    private static int count(Container c, Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (plain(s) && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** Put in what fits; what's left over. */
    private static int insert(Container c, Item item, int n) {
        int left = n, max = Math.min(item.getMaxStackSize(), c.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack s = c.getItem(i);
            if (plain(s) && s.is(item) && s.getCount() < max) {
                int k = Math.min(left, max - s.getCount());
                s.grow(k);
                left -= k;
            }
        }
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            if (!c.getItem(i).isEmpty()) continue;
            int k = Math.min(left, max);
            c.setItem(i, new ItemStack(item, k));
            left -= k;
        }
        return left;
    }

    private static void sell(Holding h, Rule r, Container c) {
        Map<Item, int[]> prices = sellPrices();
        Map<Item, Integer> goods = takeOut(c, r.over(), prices::containsKey);
        int emeralds = 0, sold = 0;
        for (var e : goods.entrySet()) {
            int[] p = prices.get(e.getKey());
            int lots = e.getValue() / p[0];
            int rest = e.getValue() - lots * p[0];
            emeralds += lots * p[1];
            sold += lots * p[0];
            if (rest > 0) insert(c, e.getKey(), rest);   // short of a lot: it waits for more
        }
        if (emeralds <= 0) return;
        int left = insert(c, Items.EMERALD, emeralds);
        if (left > 0) h.addStock(new ResourceLocation("minecraft", "emerald"), left);
        h.stewardSold += sold;
        h.stewardEarned += emeralds;
    }

    private static void send(ServerLevel level, HoldingManager mgr, Holding h, UUID owner, Rule r, Container c) {
        Holding dest = null;
        for (Holding o : mgr.holdings.values()) {
            if (o == h || !owner.equals(o.ownerPlayer()) || o.name.isEmpty()) continue;
            String n = o.name.toLowerCase(Locale.ROOT);
            if (n.equals(r.place()) || n.startsWith(r.place())) {
                dest = o;
                if (n.equals(r.place())) break;
            }
        }
        if (dest == null) return;
        for (Shipment s : mgr.shipments) if (s.origin.equals(h.id) && dest.id.equals(s.targetHolding)) return;   // one courier on the road at a time
        Map<Item, Integer> goods = takeOut(c, r.over(), i -> true);
        if (goods.isEmpty()) return;
        Shipment s = new Shipment();
        s.origin = h.id;
        s.targetHolding = dest.id;
        s.from = h.gatePos.equals(net.minecraft.core.BlockPos.ZERO) ? h.center() : h.gatePos;
        s.to = dest.gatePos.equals(net.minecraft.core.BlockPos.ZERO) ? dest.center() : dest.gatePos;
        int n = 0;
        for (var e : goods.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int k = Math.min(left, e.getKey().getMaxStackSize());
                s.cargo.add(new ItemStack(e.getKey(), k));
                left -= k;
            }
            n += e.getValue();
        }
        mgr.shipments.add(s);
        h.stewardSent += n;
    }

    private static void keep(HoldingManager mgr, Holding h, UUID owner, Rule r, Container c) {
        ResourceLocation emerald = new ResourceLocation("minecraft", "emerald");
        Map<Item, int[]> buy = r.kind().equals(BUY) ? buyPrices() : Map.of();
        for (var e : r.want().entrySet()) {
            Item item = e.getKey();
            int short_ = e.getValue() - count(c, item);
            if (short_ <= 0) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) continue;
            // from the realm's stores first
            int fromStores = Math.min(short_, Stores.realmStock(mgr, h, owner, id));
            if (fromStores > 0) {
                int left = insert(c, item, fromStores);
                Stores.take(mgr, h, owner, id, fromStores - left);
                h.stewardStocked += fromStores - left;
                short_ -= fromStores - left;
                if (left > 0) continue;   // the chest is full
            }
            if (short_ <= 0 || !buy.containsKey(item) || item == Items.EMERALD) continue;
            // then bought: whole lots, with the chest's emeralds and then the stores'
            int[] p = buy.get(item);
            int lots = (short_ + p[0] - 1) / p[0];
            int purse = count(c, Items.EMERALD) + Stores.realmStock(mgr, h, owner, emerald);
            lots = Math.min(lots, purse / p[1]);
            if (lots <= 0) continue;
            int cost = lots * p[1];
            int fromChest = Math.min(cost, count(c, Items.EMERALD));
            remove(c, Items.EMERALD, fromChest);
            if (cost > fromChest) Stores.take(mgr, h, owner, emerald, cost - fromChest);
            int left = insert(c, item, lots * p[0]);
            if (left > 0) h.addStock(id, left);
            h.stewardBought += lots * p[0];
            h.stewardSpent += cost;
        }
    }

    // ------------------------------------------------------------ prices, from the shops' own lists

    private static Map<Item, int[]> sells, buys;

    /** What a shop pays: per item, how many make a lot and how many emeralds the lot fetches (the best rate). */
    public static Map<Item, int[]> sellPrices() {
        if (sells == null) {
            Map<Item, int[]> m = new HashMap<>();
            for (Shops.Type t : Shops.types().values()) {
                for (MerchantOffer o : t.stock()) {
                    ItemStack a = o.getBaseCostA(), b = o.getCostB(), res = o.getResult();
                    if (!res.is(Items.EMERALD) || !b.isEmpty() || !plain(a)) continue;
                    int[] p = {a.getCount(), res.getCount()};
                    int[] old = m.get(a.getItem());
                    if (old == null || (double) p[1] / p[0] > (double) old[1] / old[0]) m.put(a.getItem(), p);
                }
            }
            sells = m;
        }
        return sells;
    }

    /** What a shop asks: per item, how many come in a lot and what the lot costs in emeralds (the best rate). */
    public static Map<Item, int[]> buyPrices() {
        if (buys == null) {
            Map<Item, int[]> m = new HashMap<>();
            for (Shops.Type t : Shops.types().values()) {
                for (MerchantOffer o : t.stock()) {
                    ItemStack a = o.getBaseCostA(), b = o.getCostB(), res = o.getResult();
                    if (!a.is(Items.EMERALD) || !plain(res) || res.is(Items.EMERALD)) continue;
                    int cost = a.getCount() + (b.isEmpty() ? 0 : b.is(Items.EMERALD_BLOCK) ? 9 * b.getCount() : -100000);
                    if (cost <= 0) continue;
                    int[] p = {res.getCount(), cost};
                    int[] old = m.get(res.getItem());
                    if (old == null || (double) p[1] / p[0] < (double) old[1] / old[0]) m.put(res.getItem(), p);
                }
            }
            buys = m;
        }
        return buys;
    }

    // ------------------------------------------------------------ what he tells you

    private static void announce(ServerLevel level, Holding h, Rule r, BlockPos at) {
        if (h.stewardName.isEmpty()) h.stewardName = NameGen.first(level.random);
        String what = switch (r.kind()) {
            case SELL -> "sells what the shops will buy" + (r.over() > 0 ? " beyond " + r.over() + " of each" : "") + ", the emeralds left in it";
            case STORES -> "empties it into the stores" + (r.over() > 0 ? ", leaving " + r.over() + " of each" : "");
            case SEND -> "sends it by courier to " + r.place();
            case KEEP -> "keeps it stocked from the stores: " + wants(r);
            case SPOILS -> "has the garrison put what they take off the dead in it" + (r.sell() ? ", and sells what the shops will buy" : "");
            case TREASURY -> "keeps the realm's coin in it: rents, tolls, tribute, spoils and purses";
            default -> "keeps it stocked, buying what the stores lack: " + wants(r);
        };
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Steward " + h.stewardName + " of " + h.name + " takes charge of the chest at "
                + at.getX() + ", " + at.getY() + ", " + at.getZ() + ": he " + what + (r.whenFull() ? ", whenever it's full" : "") + ".")
                .withStyle(ChatFormatting.GOLD));
    }

    private static String wants(Rule r) {
        List<String> parts = new ArrayList<>();
        for (var e : r.want().entrySet()) parts.add(e.getValue() + " " + e.getKey().getDescription().getString().toLowerCase(Locale.ROOT));
        return String.join(", ", parts);
    }

    /** The morning's line for a holding, if the steward did anything since the last; and the tally starts again. */
    @Nullable
    public static Component report(Holding h) {
        List<String> parts = new ArrayList<>();
        if (h.stewardSold > 0) parts.add("sold " + h.stewardSold + " goods for " + h.stewardEarned + " emeralds");
        if (h.stewardStored > 0) parts.add("put " + h.stewardStored + " into the stores");
        if (h.stewardSent > 0) parts.add("sent " + h.stewardSent + " by courier");
        if (h.stewardStocked > 0) parts.add("stocked " + h.stewardStocked + " from the stores");
        if (h.stewardBought > 0) parts.add("bought " + h.stewardBought + " for " + h.stewardSpent + " emeralds");
        if (h.spoilsTaken > 0) parts.add("put " + h.spoilsTaken + " things the garrison took off the dead in the spoils chest");
        if (h.tidied > 0) parts.add("moved " + h.tidied + " things into their chests and sorted them");
        h.stewardSold = h.stewardEarned = h.stewardStored = h.stewardSent = h.stewardStocked = h.stewardBought = h.stewardSpent = 0;
        h.spoilsTaken = 0;
        h.tidied = 0;
        if (parts.isEmpty()) return null;
        return Component.literal("Steward " + (h.stewardName.isEmpty() ? "" : h.stewardName + " ") + "of " + h.name + ": "
                + String.join(", ", parts) + ".").withStyle(ChatFormatting.GRAY);
    }

    private Steward() {
    }
}
