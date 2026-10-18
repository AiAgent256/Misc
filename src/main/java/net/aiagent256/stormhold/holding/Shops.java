package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The shops of a town or a city. Every stall and shopfront the plans mark ({@code shop_<kind>}) gets a
 * keeper who stands behind his counter all day, sells a fixed stock for emeralds and buys a few goods of
 * his trade, and restocks every morning. A keeper killed is replaced the next morning. The houses that
 * hold the player hostile won't sell to him.
 */
public final class Shops {
    public static final String TAG = "StormholdShop";
    public static final String HOLDING_TAG = "StormholdShopHolding";

    public record Type(String id, String title, String keeper, VillagerProfession profession, List<MerchantOffer> stock) {
        public MerchantOffers offers() {
            MerchantOffers o = new MerchantOffers();
            for (MerchantOffer m : stock) o.add(new MerchantOffer(m.createTag()));
            return o;
        }
    }

    private static Map<String, Type> types;

    /** Every kind of shop, by the name the plans use. */
    public static Map<String, Type> types() {
        if (types == null) types = build();
        return types;
    }

    @Nullable
    public static Type type(String id) {
        return types().get(id);
    }

    // ------------------------------------------------------------ stock

    private static MerchantOffer sell(Item item, int count, int emeralds) {
        return sell(new ItemStack(item, count), emeralds);
    }

    private static MerchantOffer sell(ItemStack result, int emeralds) {
        int uses = emeralds >= 10 ? 3 : emeralds >= 5 ? 6 : 12;
        return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), ItemStack.EMPTY, result, uses, 1, 0.05F);
    }

    private static MerchantOffer buy(Item item, int count, int emeralds) {
        return new MerchantOffer(new ItemStack(item, count), ItemStack.EMPTY, new ItemStack(Items.EMERALD, emeralds), 16, 1, 0.05F);
    }

    private static ItemStack potion(Item kind, Potion p) {
        return PotionUtils.setPotion(new ItemStack(kind), p);
    }

    private static Map<String, Type> build() {
        Map<String, Type> m = new LinkedHashMap<>();
        m.put("general", new Type("general", "General Store", "Shopkeeper", VillagerProfession.NITWIT, List.of(
                sell(Items.TORCH, 16, 1), sell(Items.BREAD, 6, 1), sell(Items.STRING, 8, 1), sell(Items.COAL, 8, 1),
                sell(Items.LADDER, 8, 1), sell(Items.PAPER, 16, 1), sell(Items.BOOK, 3, 1), sell(Items.CHEST, 2, 1),
                sell(Items.BUCKET, 1, 2), sell(Items.SHEARS, 1, 2), sell(Items.FLINT_AND_STEEL, 1, 2), sell(Items.FISHING_ROD, 1, 2),
                sell(Items.LEAD, 1, 2), sell(Items.OAK_BOAT, 1, 2), sell(Items.WHITE_BED, 1, 3), sell(Items.MAP, 1, 3),
                sell(Items.COMPASS, 1, 4),
                buy(Items.COBBLESTONE, 32, 1), buy(Items.OAK_LOG, 16, 1), buy(Items.SPRUCE_LOG, 16, 1), buy(Items.WHEAT, 20, 1),
                buy(Items.ROTTEN_FLESH, 32, 1), buy(Items.BONE, 16, 1), buy(Items.GUNPOWDER, 8, 1))));
        m.put("armoury", new Type("armoury", "Armoury", "Armourer", VillagerProfession.ARMORER, List.of(
                sell(Items.CHAINMAIL_HELMET, 1, 3), sell(Items.CHAINMAIL_CHESTPLATE, 1, 6), sell(Items.CHAINMAIL_LEGGINGS, 1, 5),
                sell(Items.CHAINMAIL_BOOTS, 1, 3), sell(Items.IRON_HELMET, 1, 5), sell(Items.IRON_CHESTPLATE, 1, 9),
                sell(Items.IRON_LEGGINGS, 1, 8), sell(Items.IRON_BOOTS, 1, 4), sell(Items.SHIELD, 1, 4),
                sell(Items.DIAMOND_HELMET, 1, 20), sell(Items.DIAMOND_CHESTPLATE, 1, 32), sell(Items.DIAMOND_LEGGINGS, 1, 28),
                sell(Items.DIAMOND_BOOTS, 1, 18),
                buy(Items.IRON_INGOT, 4, 1), buy(Items.COAL, 15, 1), buy(Items.LAVA_BUCKET, 1, 1))));
        m.put("weapons", new Type("weapons", "Weaponsmith", "Weaponsmith", VillagerProfession.WEAPONSMITH, List.of(
                sell(Items.STONE_SWORD, 1, 1), sell(Items.IRON_SWORD, 1, 4), sell(Items.IRON_AXE, 1, 4), sell(Items.BOW, 1, 3),
                sell(Items.CROSSBOW, 1, 5), sell(Items.SHIELD, 1, 4), sell(Items.DIAMOND_SWORD, 1, 18), sell(Items.DIAMOND_AXE, 1, 18),
                buy(Items.IRON_INGOT, 4, 1), buy(Items.COAL, 15, 1), buy(Items.FLINT, 24, 1))));
        m.put("smith", new Type("smith", "Smithy", "Smith", VillagerProfession.TOOLSMITH, List.of(
                sell(Items.IRON_INGOT, 3, 1), sell(Items.CHAIN, 4, 1), sell(Items.IRON_BARS, 16, 1), sell(Items.IRON_SHOVEL, 1, 2),
                sell(Items.IRON_HOE, 1, 2), sell(Items.SHEARS, 1, 2), sell(Items.BUCKET, 1, 2), sell(Items.SMITHING_TABLE, 1, 2),
                sell(Items.IRON_AXE, 1, 4), sell(Items.IRON_PICKAXE, 1, 5), sell(Items.DIAMOND_SHOVEL, 1, 10), sell(Items.ANVIL, 1, 12),
                sell(Items.DIAMOND_PICKAXE, 1, 20),
                buy(Items.COAL, 15, 1), buy(Items.RAW_IRON, 4, 1), buy(Items.GOLD_INGOT, 3, 1), buy(Items.COPPER_INGOT, 10, 1))));
        m.put("fletcher", new Type("fletcher", "Fletcher", "Fletcher", VillagerProfession.FLETCHER, List.of(
                sell(Items.ARROW, 16, 1), sell(Items.TARGET, 1, 1), sell(Items.SPECTRAL_ARROW, 8, 2), sell(Items.BOW, 1, 3),
                sell(potion(Items.TIPPED_ARROW, Potions.SLOWNESS).copyWithCount(8), 3),
                sell(potion(Items.TIPPED_ARROW, Potions.POISON).copyWithCount(8), 3), sell(Items.CROSSBOW, 1, 5),
                buy(Items.STICK, 32, 1), buy(Items.FEATHER, 24, 1), buy(Items.STRING, 14, 1), buy(Items.FLINT, 26, 1))));
        m.put("baker", new Type("baker", "Bakery", "Baker", VillagerProfession.FARMER, List.of(
                sell(Items.BREAD, 6, 1), sell(Items.COOKIE, 16, 1), sell(Items.PUMPKIN_PIE, 4, 1), sell(Items.APPLE, 4, 1),
                sell(Items.CAKE, 1, 2),
                buy(Items.WHEAT, 20, 1), buy(Items.PUMPKIN, 6, 1), buy(Items.SUGAR_CANE, 16, 1), buy(Items.EGG, 16, 1))));
        m.put("butcher", new Type("butcher", "Butcher", "Butcher", VillagerProfession.BUTCHER, List.of(
                sell(Items.COOKED_BEEF, 5, 1), sell(Items.COOKED_PORKCHOP, 5, 1), sell(Items.COOKED_CHICKEN, 6, 1),
                sell(Items.COOKED_MUTTON, 5, 1), sell(Items.RABBIT_STEW, 1, 1),
                buy(Items.CHICKEN, 14, 1), buy(Items.PORKCHOP, 7, 1), buy(Items.BEEF, 10, 1), buy(Items.MUTTON, 7, 1),
                buy(Items.RABBIT, 4, 1))));
        m.put("fish", new Type("fish", "Fishmonger", "Fishmonger", VillagerProfession.FISHERMAN, List.of(
                sell(Items.COOKED_COD, 6, 1), sell(Items.COOKED_SALMON, 5, 1), sell(Items.CAMPFIRE, 1, 1), sell(Items.FISHING_ROD, 1, 2),
                sell(Items.COD_BUCKET, 1, 3),
                buy(Items.COD, 15, 1), buy(Items.SALMON, 13, 1), buy(Items.TROPICAL_FISH, 6, 1), buy(Items.STRING, 20, 1))));
        m.put("farmer", new Type("farmer", "Farm Supply", "Farmer", VillagerProfession.FARMER, List.of(
                sell(Items.WHEAT_SEEDS, 16, 1), sell(Items.CARROT, 8, 1), sell(Items.POTATO, 8, 1), sell(Items.BEETROOT_SEEDS, 16, 1),
                sell(Items.MELON_SEEDS, 4, 1), sell(Items.PUMPKIN_SEEDS, 4, 1), sell(Items.SUGAR_CANE, 8, 1), sell(Items.BONE_MEAL, 16, 1),
                sell(Items.OAK_SAPLING, 4, 1), sell(Items.HAY_BLOCK, 2, 1), sell(Items.COMPOSTER, 1, 1), sell(Items.IRON_HOE, 1, 2),
                buy(Items.WHEAT, 20, 1), buy(Items.CARROT, 22, 1), buy(Items.POTATO, 26, 1), buy(Items.BEETROOT, 15, 1),
                buy(Items.MELON, 4, 1))));
        m.put("cloth", new Type("cloth", "Draper & Tailor", "Draper", VillagerProfession.SHEPHERD, List.of(
                sell(Items.WHITE_WOOL, 4, 1), sell(Items.RED_WOOL, 4, 1), sell(Items.BLUE_WOOL, 4, 1), sell(Items.BLACK_WOOL, 4, 1),
                sell(Items.YELLOW_WOOL, 4, 1), sell(Items.GREEN_WOOL, 4, 1), sell(Items.WHITE_CARPET, 8, 1),
                sell(Items.LEATHER_HELMET, 1, 1), sell(Items.LEATHER_BOOTS, 1, 1), sell(Items.LEATHER_LEGGINGS, 1, 2),
                sell(Items.LEATHER_CHESTPLATE, 1, 3), sell(Items.PAINTING, 1, 2), sell(Items.WHITE_BANNER, 1, 3),
                sell(Items.RED_BED, 1, 3),
                buy(Items.WHITE_WOOL, 18, 1), buy(Items.STRING, 14, 1))));
        m.put("tanner", new Type("tanner", "Saddler", "Saddler", VillagerProfession.LEATHERWORKER, List.of(
                sell(Items.LEATHER, 2, 1), sell(Items.HAY_BLOCK, 2, 1), sell(Items.LEAD, 1, 2), sell(Items.LEATHER_HORSE_ARMOR, 1, 3),
                sell(Items.SADDLE, 1, 6), sell(Items.IRON_HORSE_ARMOR, 1, 8), sell(Items.NAME_TAG, 1, 10),
                sell(Items.GOLDEN_HORSE_ARMOR, 1, 12), sell(Items.DIAMOND_HORSE_ARMOR, 1, 24),
                buy(Items.LEATHER, 6, 1), buy(Items.RABBIT_HIDE, 9, 1))));
        m.put("potter", new Type("potter", "Mason & Potter", "Mason", VillagerProfession.MASON, List.of(
                sell(Items.STONE_BRICKS, 16, 1), sell(Items.BRICKS, 10, 1), sell(Items.POLISHED_ANDESITE, 16, 1),
                sell(Items.POLISHED_DIORITE, 16, 1), sell(Items.POLISHED_GRANITE, 16, 1), sell(Items.SMOOTH_STONE, 16, 1),
                sell(Items.CHISELED_STONE_BRICKS, 8, 1), sell(Items.TERRACOTTA, 8, 1), sell(Items.GLASS, 8, 1),
                sell(Items.FLOWER_POT, 4, 1), sell(Items.DECORATED_POT, 1, 1),
                buy(Items.CLAY_BALL, 10, 1), buy(Items.STONE, 20, 1), buy(Items.GRANITE, 16, 1))));
        m.put("apothecary", new Type("apothecary", "Apothecary", "Apothecary", VillagerProfession.CLERIC, List.of(
                sell(Items.GLASS_BOTTLE, 8, 1), sell(Items.REDSTONE, 4, 1), sell(Items.GLOWSTONE_DUST, 4, 1),
                sell(Items.GLISTERING_MELON_SLICE, 1, 2), sell(potion(Items.POTION, Potions.HEALING), 3),
                sell(potion(Items.POTION, Potions.SWIFTNESS), 3), sell(potion(Items.POTION, Potions.NIGHT_VISION), 3),
                sell(potion(Items.POTION, Potions.WATER_BREATHING), 3), sell(potion(Items.POTION, Potions.REGENERATION), 4),
                sell(potion(Items.POTION, Potions.STRENGTH), 4), sell(potion(Items.POTION, Potions.FIRE_RESISTANCE), 4),
                sell(potion(Items.SPLASH_POTION, Potions.HEALING), 4), sell(Items.GOLDEN_APPLE, 1, 8),
                buy(Items.ROTTEN_FLESH, 32, 1), buy(Items.SPIDER_EYE, 8, 1), buy(Items.NETHER_WART, 22, 1), buy(Items.GOLD_INGOT, 3, 1))));
        m.put("chandler", new Type("chandler", "Chandler", "Chandler", VillagerProfession.LIBRARIAN, List.of(
                sell(Items.TORCH, 16, 1), sell(Items.LANTERN, 3, 1), sell(Items.CANDLE, 4, 1), sell(Items.WHITE_CANDLE, 4, 1),
                sell(Items.SOUL_LANTERN, 2, 1), sell(Items.CHAIN, 4, 1), sell(Items.CAMPFIRE, 1, 1), sell(Items.SEA_LANTERN, 1, 3),
                buy(Items.HONEYCOMB, 6, 1), buy(Items.COAL, 15, 1))));
        return m;
    }

    // ------------------------------------------------------------ keepers

    /** How many keepers of one trade a place keeps: a town's stalls repeat, and a city has a street of them. */
    private static int cap(Holding h) {
        return h.isCity() ? 5 : 3;
    }

    /** Whether a place has shops at all: towns and cities. */
    public static boolean hasShops(Holding h) {
        return h.isTown() || h.isCity();
    }

    /** The shops a place keeps, by kind, with every post. */
    public static Map<String, List<BlockPos>> posts(Holding h) {
        Map<String, List<BlockPos>> out = new LinkedHashMap<>();
        for (var e : h.extras.entrySet()) {
            if (!e.getKey().startsWith("shop_")) continue;
            String kind = e.getKey().substring(5);
            if (type(kind) == null) continue;
            List<BlockPos> ps = e.getValue();
            out.put(kind, ps.subList(0, Math.min(ps.size(), cap(h))));
        }
        return out;
    }

    /**
     * Keep the shops staffed. Called every few seconds while the place is loaded and near a player: a keeper
     * is put at any post that has none (at once the first time, each morning after), drifted keepers are put
     * back behind their counters, and each new day every keeper restocks.
     */
    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!hasShops(h)) return;
        refreshFromPlans(mgr, h);
        long day = level.getDayTime() / 24000L;
        boolean newDay = day != h.shopsDay;
        boolean dirty = false;
        for (var e : posts(h).entrySet()) {
            for (BlockPos post : e.getValue()) {
                if (!level.isLoaded(post) || !level.areEntitiesLoaded(ChunkPos.asLong(post))) continue;
                Villager v = keeperAt(level, h, post);
                if (v != null) {
                    if (!v.getUUID().equals(h.shopkeepers.get(post))) {
                        h.shopkeepers.put(post.immutable(), v.getUUID());
                        dirty = true;
                    }
                    if (v.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > 1.0) {
                        v.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
                    }
                    if (newDay) restock(v);
                    continue;
                }
                if (h.shopkeepers.containsKey(post) && !newDay) continue;      // killed: his stall stands empty till morning
                Villager k = spawnKeeper(level, h, e.getKey(), post);
                if (k != null) {
                    h.shopkeepers.put(post.immutable(), k.getUUID());
                    dirty = true;
                }
            }
        }
        if (newDay) {
            h.shopsDay = day;
            dirty = true;
        }
        if (dirty) mgr.setDirty();
        forSaleSigns(level, mgr, h);
    }

    /** A traveller walking past a home for sale is told so, and what it costs. */
    private static void forSaleSigns(ServerLevel level, HoldingManager mgr, Holding h) {
        List<BlockPos> plots = h.extras.get("house");
        if (plots == null || plots.isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !h.box.isInside(p.blockPosition())) continue;
            for (BlockPos q : plots) {
                if (h.ownedHouses.containsKey(q) || q.distSqr(p.blockPosition()) > 25) continue;
                if (h.isHouseOwned() && Standing.hostile(mgr, h.houseId, p.getUUID())) break;
                p.displayClientMessage(Component.literal("A house for sale: use its door holding " + City.housePrice(h)
                        + " emeralds to buy it.").withStyle(ChatFormatting.GOLD), true);
                break;
            }
        }
    }

    /** The keeper standing at a post, if one is. */
    @Nullable
    private static Villager keeperAt(ServerLevel level, Holding h, BlockPos post) {
        UUID id = h.shopkeepers.get(post);
        if (id != null && level.getEntity(id) instanceof Villager v && v.isAlive()) return v;
        for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(post).inflate(2.5),
                x -> x.isAlive() && x.getPersistentData().contains(TAG))) {
            return v;
        }
        return null;
    }

    /** Put a keeper of this trade at this post. Null if the post isn't clear to stand in. */
    @Nullable
    public static Villager spawnKeeper(ServerLevel level, Holding h, String kind, BlockPos post) {
        Type t = type(kind);
        if (t == null) return null;
        BlockState here = level.getBlockState(post), head = level.getBlockState(post.above());
        if (!here.getCollisionShape(level, post).isEmpty() || !head.getCollisionShape(level, post.above()).isEmpty()) return null;
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return null;
        float yaw = facing(level, post).toYRot();
        v.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, yaw, 0);
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(post), MobSpawnType.STRUCTURE, null, null);
        v.setVillagerData(v.getVillagerData().setProfession(t.profession()).setLevel(5));
        v.setVillagerXp(250);
        CompoundTag tag = new CompoundTag();
        v.addAdditionalSaveData(tag);
        tag.put("Offers", t.offers().createTag());
        v.readAdditionalSaveData(tag);
        v.setNoAi(true);
        v.setPersistenceRequired();
        v.setYHeadRot(yaw);
        v.setYBodyRot(yaw);
        String first = NameGen.commoner(level.random, "none").split(" ")[0];
        v.setCustomName(Component.literal(first + " the " + t.keeper()));
        v.setCustomNameVisible(true);
        v.getPersistentData().putString(TAG, t.id());
        if (h.id != null) v.getPersistentData().putUUID(HOLDING_TAG, h.id);
        level.addFreshEntity(v);
        labelSign(level, post, t);
        return v;
    }

    /** Every offer back in stock. */
    public static void restock(Villager v) {
        for (MerchantOffer o : v.getOffers()) o.resetUses();
    }

    /** Which way a keeper faces: toward his counter (a trapdoor or a fence beside him), else the open side. */
    private static Direction facing(ServerLevel level, BlockPos post) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockState s = level.getBlockState(post.relative(d)), s1 = level.getBlockState(post.relative(d).above());
            if (s.getBlock() instanceof TrapDoorBlock || s1.getBlock() instanceof TrapDoorBlock) return d;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(post.relative(d)).getBlock() instanceof FenceBlock) return d;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(post.relative(d)).isAir() && level.getBlockState(post.relative(d, 2)).isAir()) return d;
        }
        return Direction.SOUTH;
    }

    /** The labels the first towns were built with, before their shops had proper names. */
    private static final Set<String> OLD_LABELS = Set.of("merchant", "smithy", "bakery", "fishmonger", "draper", "tannery",
            "butcher", "potter", "apothecary", "chandler");

    /** The shop's sign shows its name: rewrites the nearest old-style trade label near the counter. */
    private static void labelSign(ServerLevel level, BlockPos post, Type t) {
        SignBlockEntity best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(post.offset(-3, 0, -3), post.offset(3, 3, 3))) {
            if (!(level.getBlockState(q).getBlock() instanceof SignBlock)) continue;
            if (!(level.getBlockEntity(q) instanceof SignBlockEntity s)) continue;
            String first = s.getFrontText().getMessage(0, false).getString().trim().toLowerCase();
            if (!OLD_LABELS.contains(first)) continue;
            double d = q.distSqr(post);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        if (best == null) return;
        String[] words = t.title().split(" ", 2);
        write(level, best, "", words[0], words.length > 1 ? words[1] : "", "");
    }

    /** Write a sign, both faces, and wax it so it can't be changed. */
    public static void write(ServerLevel level, SignBlockEntity s, String a, String b, String c, String d) {
        SignText text = new SignText()
                .setMessage(0, Component.literal(a)).setMessage(1, Component.literal(b))
                .setMessage(2, Component.literal(c)).setMessage(3, Component.literal(d));
        s.setText(text, true);
        s.setText(text, false);
        s.setWaxed(true);
        s.setChanged();
        BlockState st = level.getBlockState(s.getBlockPos());
        level.sendBlockUpdated(s.getBlockPos(), st, st, 3);
    }

    /** A blank sign on the notice board gets its label, for boards built before they had one. */
    public static void labelBoard(ServerLevel level, BlockPos marker) {
        for (BlockPos q : BlockPos.betweenClosed(marker.offset(-3, 0, -3), marker.offset(3, 2, 3))) {
            if (!(level.getBlockEntity(q) instanceof SignBlockEntity s)) continue;
            boolean blank = true;
            for (int i = 0; i < 4; i++) if (!s.getFrontText().getMessage(i, false).getString().isBlank()) blank = false;
            if (blank) write(level, s, "", "NOTICE", "BOARD", "Right-click");
        }
    }

    /** The first time a place built before it had shops is seen: its plans' shop posts and homes for sale are added. */
    private static void refreshFromPlans(HoldingManager mgr, Holding h) {
        if (h.shopsVersion >= 1) return;
        h.shopsVersion = 1;
        mgr.setDirty();
        Layout l = h.plans();
        if (l == null || h.box == null) return;
        BlockPos o = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
        for (var e : l.extras.entrySet()) {
            String k = e.getKey();
            boolean add = k.startsWith("shop_") || (k.equals("house") && !h.extras.containsKey("house"));
            if (!add) continue;
            List<BlockPos> list = new ArrayList<>();
            for (BlockPos p : e.getValue()) list.add(o.offset(p));
            h.extras.put(k, list);
        }
    }

    /** A keeper of a house that holds the player hostile turns him away. True if the trade is refused. */
    public static boolean refuses(ServerLevel level, ServerPlayer p, Villager v) {
        if (!v.getPersistentData().contains(TAG) || !v.getPersistentData().hasUUID(HOLDING_TAG)) return false;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.holdings.get(v.getPersistentData().getUUID(HOLDING_TAG));
        if (h == null || !h.isHouseOwned() || !Standing.hostile(mgr, h.houseId, p.getUUID())) return false;
        p.displayClientMessage(Component.literal(v.getDisplayName().getString() + " won't deal with you: "
                + mgr.house(h.houseId).title() + " holds you hostile.").withStyle(ChatFormatting.RED), true);
        return true;
    }

    private Shops() {
    }
}
