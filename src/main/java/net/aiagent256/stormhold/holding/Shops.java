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
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SlabBlock;
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
 * The shops of a town, a city or a great castle's yard market. Every stall and shopfront the plans mark
 * ({@code shop_<kind>}), and every stall a castle puts up ({@link Market}), gets a
 * keeper who stands behind his counter all day, sells a fixed stock for emeralds and buys a few goods of
 * his trade, and restocks every morning. A keeper killed is replaced the next morning. The houses that
 * hold the player hostile won't sell to him.
 */
public final class Shops {
    public static final String TAG = "StormholdShop";
    public static final String HOLDING_TAG = "StormholdShopHolding";

    public record Type(String id, String title, String keeper, VillagerProfession profession, String[] sign, List<MerchantOffer> stock) {
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

    /**
     * The trades the first plans marked, folded into today's shops: the baker, butcher and fishmonger into
     * Food & Provisions, the draper and farm supply into the General Store, the chandler into the Enchanter,
     * the potter into the Mason & Carpenter, the saddler into Stables & Tack, the apothecary into the Alchemist.
     */
    private static final Map<String, String> FOLDED = Map.of("baker", "provisioner", "butcher", "provisioner",
            "fish", "provisioner", "cloth", "general", "farmer", "general", "chandler", "enchanter", "potter", "mason",
            "tanner", "stables", "apothecary", "alchemist");

    /** Today's name for a trade the plans mark. */
    public static String canonical(String id) {
        return FOLDED.getOrDefault(id, id);
    }

    @Nullable
    public static Type type(String id) {
        return types().get(canonical(id));
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

    /** A dear thing: emeralds and emerald blocks. */
    private static MerchantOffer dear(ItemStack result, int emeralds, int blocks) {
        int uses = blocks >= 4 ? 2 : 3;
        return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), blocks > 0 ? new ItemStack(Items.EMERALD_BLOCK, blocks) : ItemStack.EMPTY,
                result, uses, 5, 0.05F);
    }

    /** An item with enchantments: pairs of enchantment and level. */
    private static ItemStack ench(Item item, Object... pairs) {
        ItemStack s = new ItemStack(item);
        for (int i = 0; i + 1 < pairs.length; i += 2) s.enchant((net.minecraft.world.item.enchantment.Enchantment) pairs[i], (Integer) pairs[i + 1]);
        return s;
    }

    private static ItemStack book(net.minecraft.world.item.enchantment.Enchantment e, int lvl) {
        return net.minecraft.world.item.EnchantedBookItem.createForEnchantment(new net.minecraft.world.item.enchantment.EnchantmentInstance(e, lvl));
    }

    record Extra(int zone, MerchantOffer offer) {
    }

    private static Map<String, List<Extra>> frontier;

    /** What a shop adds the farther out it stands: the frontier's markets carry the frontier's goods. */
    private static Map<String, List<Extra>> frontierStock() {
        if (frontier != null) return frontier;
        var P = Enchantments.ALL_DAMAGE_PROTECTION;
        var U = Enchantments.UNBREAKING;
        var S = Enchantments.SHARPNESS;
        Map<String, List<Extra>> m = new LinkedHashMap<>();
        m.put("armoury", List.of(
                new Extra(4, sell(ench(Items.DIAMOND_HELMET, P, 2), 14)), new Extra(4, dear(ench(Items.DIAMOND_CHESTPLATE, P, 2), 28, 0)),
                new Extra(4, dear(ench(Items.DIAMOND_LEGGINGS, P, 2), 24, 0)), new Extra(4, sell(ench(Items.DIAMOND_BOOTS, P, 2), 12)),
                new Extra(6, dear(new ItemStack(Items.NETHERITE_HELMET), 32, 2)), new Extra(6, dear(new ItemStack(Items.NETHERITE_CHESTPLATE), 48, 4)),
                new Extra(6, dear(new ItemStack(Items.NETHERITE_LEGGINGS), 40, 3)), new Extra(6, dear(new ItemStack(Items.NETHERITE_BOOTS), 32, 2)),
                new Extra(8, dear(ench(Items.NETHERITE_HELMET, P, 4, U, 3), 64, 4)), new Extra(8, dear(ench(Items.NETHERITE_CHESTPLATE, P, 4, U, 3), 64, 8)),
                new Extra(8, dear(ench(Items.NETHERITE_LEGGINGS, P, 4, U, 3), 64, 6)), new Extra(8, dear(ench(Items.NETHERITE_BOOTS, P, 4, U, 3), 64, 4))));
        m.put("weapons", List.of(
                new Extra(4, dear(ench(Items.DIAMOND_SWORD, S, 3), 16, 0)), new Extra(4, dear(ench(Items.DIAMOND_AXE, S, 3), 16, 0)),
                new Extra(6, dear(new ItemStack(Items.NETHERITE_SWORD), 40, 2)), new Extra(6, dear(new ItemStack(Items.NETHERITE_AXE), 40, 2)),
                new Extra(8, dear(ench(Items.NETHERITE_SWORD, S, 5, U, 3, Enchantments.FIRE_ASPECT, 2), 64, 4)),
                new Extra(8, dear(ench(Items.BOW, Enchantments.POWER_ARROWS, 5, U, 3), 24, 0)),
                new Extra(8, dear(ench(Items.CROSSBOW, Enchantments.QUICK_CHARGE, 3, Enchantments.MULTISHOT, 1), 24, 0))));
        m.put("smith", List.of(
                new Extra(5, dear(ench(Items.DIAMOND_PICKAXE, Enchantments.BLOCK_EFFICIENCY, 4), 24, 0)),
                new Extra(6, dear(new ItemStack(Items.NETHERITE_INGOT), 32, 2)),
                new Extra(6, sell(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 24)),
                new Extra(8, dear(ench(Items.NETHERITE_PICKAXE, Enchantments.BLOCK_EFFICIENCY, 5, Enchantments.BLOCK_FORTUNE, 3, U, 3), 64, 4))));
        m.put("fletcher", List.of(
                new Extra(5, dear(ench(Items.BOW, Enchantments.POWER_ARROWS, 4), 12, 0)),
                new Extra(6, sell(potion(Items.TIPPED_ARROW, Potions.STRONG_HARMING).copyWithCount(8), 6)),
                new Extra(8, dear(ench(Items.BOW, Enchantments.POWER_ARROWS, 5, Enchantments.INFINITY_ARROWS, 1, U, 3), 32, 0))));
        m.put("alchemist", List.of(
                new Extra(4, sell(potion(Items.POTION, Potions.STRONG_STRENGTH), 6)), new Extra(4, sell(potion(Items.SPLASH_POTION, Potions.STRONG_HEALING), 6)),
                new Extra(4, sell(potion(Items.POTION, Potions.STRONG_REGENERATION), 8)), new Extra(6, sell(Items.GOLDEN_APPLE, 1, 4)),
                new Extra(8, dear(new ItemStack(Items.TOTEM_OF_UNDYING), 48, 4)), new Extra(9, dear(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), 64, 8))));
        m.put("general", List.of(
                new Extra(4, sell(Items.ENDER_PEARL, 4, 4)), new Extra(5, sell(Items.EXPERIENCE_BOTTLE, 8, 6)),
                new Extra(7, dear(new ItemStack(Items.SHULKER_SHELL), 24, 0))));
        m.put("enchanter", List.of(
                new Extra(5, dear(book(P, 4), 24, 0)), new Extra(5, sell(book(U, 3), 16)),
                new Extra(6, dear(book(Enchantments.BLOCK_EFFICIENCY, 5), 24, 0)), new Extra(7, dear(book(S, 5), 32, 0)),
                new Extra(8, dear(book(Enchantments.MENDING, 1), 48, 2))));
        m.put("quartermaster", List.of(
                new Extra(4, sell(Items.GOLDEN_CARROT, 8, 4)), new Extra(6, sell(Items.GOLDEN_APPLE, 1, 4)),
                new Extra(8, dear(new ItemStack(Items.TOTEM_OF_UNDYING), 48, 4))));
        m.put("stables", List.of(new Extra(6, sell(Items.DIAMOND_HORSE_ARMOR, 1, 12))));
        m.put("provisioner", List.of(new Extra(4, sell(Items.GOLDEN_CARROT, 8, 4)), new Extra(6, sell(Items.GOLDEN_APPLE, 1, 4))));
        frontier = m;
        return m;
    }

    /** A keeper's stock in a zone: the trade's own, and what the frontier adds out there. */
    public static MerchantOffers offers(Type t, int zone) {
        MerchantOffers o = t.offers();
        for (Extra f : frontierStock().getOrDefault(t.id(), List.of())) if (zone >= f.zone()) o.add(new MerchantOffer(f.offer().createTag()));
        return o;
    }

    /** An Epic Knights item, when that mod is here. */
    @Nullable
    private static Item ek(String name) {
        Item i = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("magistuarmory", name));
        return i == null || i == Items.AIR ? null : i;
    }

    private static Type type(String id, String title, String keeper, VillagerProfession prof, String[] sign, List<MerchantOffer> stock) {
        return new Type(id, title, keeper, prof, sign, List.copyOf(stock));
    }

    /**
     * Every shop's stock. Prices run in three bands: one to three emeralds for what you use up, five to
     * fifteen for tools and armour, twenty and up (and emerald blocks) for enchanted and frontier gear. Every
     * shop buys the goods of its trade, the valuable ones dear: diamonds, blaze rods, ghast tears, ender pearls,
     * saddles and horse armour found in the world.
     */
    private static Map<String, Type> build() {
        var P = Enchantments.ALL_DAMAGE_PROTECTION;
        Map<String, Type> m = new LinkedHashMap<>();
        m.put("general", type("general", "General Store", "Shopkeeper", VillagerProfession.NITWIT, new String[]{"General", "Store"}, List.of(
                sell(Items.TORCH, 16, 1), sell(Items.BREAD, 6, 1), sell(Items.LADDER, 8, 1), sell(Items.CHEST, 2, 1),
                sell(Items.CRAFTING_TABLE, 1, 1), sell(Items.FURNACE, 1, 1), sell(Items.WHITE_BED, 1, 2), sell(Items.BUCKET, 1, 2),
                sell(Items.SHEARS, 1, 2), sell(Items.FLINT_AND_STEEL, 1, 2), sell(Items.FISHING_ROD, 1, 2), sell(Items.LEAD, 1, 2),
                sell(Items.OAK_BOAT, 1, 1), sell(Items.COMPASS, 1, 3), sell(Items.CLOCK, 1, 3), sell(Items.MAP, 1, 2),
                sell(Items.SPYGLASS, 1, 4), sell(Items.PAPER, 16, 1), sell(Items.BOOK, 4, 1), sell(Items.STRING, 8, 1),
                sell(Items.COAL, 8, 1), sell(Items.STONE_PICKAXE, 1, 1), sell(Items.STONE_AXE, 1, 1), sell(Items.IRON_SHOVEL, 1, 2),
                sell(Items.IRON_AXE, 1, 4), sell(Items.IRON_PICKAXE, 1, 5), sell(Items.WHEAT_SEEDS, 16, 1), sell(Items.OAK_SAPLING, 4, 1),
                sell(Items.BONE_MEAL, 16, 1), sell(Items.WHITE_WOOL, 8, 1), sell(Items.CANDLE, 4, 1), sell(Items.NAME_TAG, 1, 10),
                buy(Items.COBBLESTONE, 32, 1), buy(Items.OAK_LOG, 16, 1), buy(Items.SPRUCE_LOG, 16, 1), buy(Items.BIRCH_LOG, 16, 1),
                buy(Items.WHEAT, 20, 1), buy(Items.CARROT, 22, 1), buy(Items.POTATO, 26, 1), buy(Items.MELON, 4, 1),
                buy(Items.PUMPKIN, 6, 1), buy(Items.SUGAR_CANE, 16, 1), buy(Items.ROTTEN_FLESH, 32, 1), buy(Items.BONE, 16, 1),
                buy(Items.GUNPOWDER, 6, 1), buy(Items.STRING, 12, 1), buy(Items.LEATHER, 6, 1), buy(Items.WHITE_WOOL, 16, 1),
                buy(Items.SLIME_BALL, 4, 1), buy(Items.INK_SAC, 8, 1))));
        m.put("provisioner", type("provisioner", "Food & Provisions", "Provisioner", VillagerProfession.BUTCHER, new String[]{"Food &", "Provisions"}, List.of(
                sell(Items.BREAD, 6, 1), sell(Items.BAKED_POTATO, 8, 1), sell(Items.APPLE, 4, 1), sell(Items.COOKIE, 16, 1),
                sell(Items.PUMPKIN_PIE, 4, 1), sell(Items.COOKED_BEEF, 5, 1), sell(Items.COOKED_PORKCHOP, 5, 1),
                sell(Items.COOKED_CHICKEN, 6, 1), sell(Items.COOKED_MUTTON, 5, 1), sell(Items.COOKED_COD, 6, 1),
                sell(Items.COOKED_SALMON, 5, 1), sell(Items.RABBIT_STEW, 1, 1), sell(Items.MUSHROOM_STEW, 2, 1),
                sell(Items.HONEY_BOTTLE, 2, 1), sell(Items.SWEET_BERRIES, 16, 1), sell(Items.MILK_BUCKET, 1, 2), sell(Items.CAKE, 1, 2),
                sell(Items.GOLDEN_CARROT, 3, 3),
                buy(Items.BEEF, 10, 1), buy(Items.PORKCHOP, 7, 1), buy(Items.CHICKEN, 14, 1), buy(Items.MUTTON, 7, 1),
                buy(Items.RABBIT, 4, 1), buy(Items.COD, 15, 1), buy(Items.SALMON, 13, 1), buy(Items.TROPICAL_FISH, 6, 1),
                buy(Items.EGG, 16, 1), buy(Items.WHEAT, 20, 1), buy(Items.COCOA_BEANS, 12, 1), buy(Items.SWEET_BERRIES, 16, 1),
                buy(Items.HONEYCOMB, 6, 1))));
        m.put("armoury", type("armoury", "Armoury", "Armourer", VillagerProfession.ARMORER, new String[]{"Armoury", ""}, List.of(
                sell(Items.LEATHER_HELMET, 1, 1), sell(Items.LEATHER_CHESTPLATE, 1, 2), sell(Items.LEATHER_LEGGINGS, 1, 2),
                sell(Items.LEATHER_BOOTS, 1, 1), sell(Items.CHAINMAIL_HELMET, 1, 3), sell(Items.CHAINMAIL_CHESTPLATE, 1, 6),
                sell(Items.CHAINMAIL_LEGGINGS, 1, 5), sell(Items.CHAINMAIL_BOOTS, 1, 3), sell(Items.IRON_HELMET, 1, 5),
                sell(Items.IRON_CHESTPLATE, 1, 9), sell(Items.IRON_LEGGINGS, 1, 7), sell(Items.IRON_BOOTS, 1, 4), sell(Items.SHIELD, 1, 3),
                sell(Items.DIAMOND_HELMET, 1, 13), sell(Items.DIAMOND_CHESTPLATE, 1, 21), sell(Items.DIAMOND_LEGGINGS, 1, 19),
                sell(Items.DIAMOND_BOOTS, 1, 13),
                buy(Items.IRON_INGOT, 4, 1), buy(Items.COAL, 15, 1), buy(Items.LEATHER, 6, 1), buy(Items.LAVA_BUCKET, 1, 1),
                buy(Items.DIAMOND, 1, 5))));
        List<MerchantOffer> arms = new ArrayList<>(List.of(
                sell(Items.STONE_SWORD, 1, 1), sell(Items.IRON_SWORD, 1, 4), sell(Items.IRON_AXE, 1, 4),
                sell(Items.DIAMOND_SWORD, 1, 12), sell(Items.DIAMOND_AXE, 1, 12)));
        String[][] ekArms = {{"iron_shortsword", "4"}, {"iron_bastardsword", "6"}, {"iron_heavymace", "6"}, {"iron_pike", "6"},
                {"iron_concavehalberd", "7"}, {"iron_lochaberaxe", "7"}, {"iron_claymore", "8"}, {"diamond_bastardsword", "14"},
                {"diamond_heavymace", "14"}, {"diamond_claymore", "16"}};
        for (String[] w : ekArms) {
            Item i = ek(w[0]);
            if (i != null) arms.add(sell(i, 1, Integer.parseInt(w[1])));
        }
        arms.addAll(List.of(buy(Items.FLINT, 20, 1), buy(Items.IRON_INGOT, 4, 1), buy(Items.COAL, 15, 1), buy(Items.DIAMOND, 1, 5),
                buy(Items.WITHER_SKELETON_SKULL, 1, 12)));
        m.put("weapons", type("weapons", "Weaponsmith", "Weaponsmith", VillagerProfession.WEAPONSMITH, new String[]{"Weapon", "smith"}, arms));
        m.put("smith", type("smith", "Smithy", "Smith", VillagerProfession.TOOLSMITH, new String[]{"Smithy", "Tools"}, List.of(
                sell(Items.IRON_INGOT, 2, 1), sell(Items.CHAIN, 4, 1), sell(Items.IRON_BARS, 16, 1), sell(Items.LANTERN, 4, 1),
                sell(Items.BUCKET, 1, 2), sell(Items.SHEARS, 1, 2), sell(Items.CAULDRON, 1, 2), sell(Items.IRON_SHOVEL, 1, 2),
                sell(Items.IRON_HOE, 1, 2), sell(Items.IRON_AXE, 1, 4), sell(Items.IRON_PICKAXE, 1, 5), sell(Items.RAIL, 16, 2),
                sell(Items.MINECART, 1, 3), sell(Items.HOPPER, 1, 5), sell(Items.SMITHING_TABLE, 1, 2), sell(Items.GRINDSTONE, 1, 2),
                sell(Items.ANVIL, 1, 8), sell(Items.DIAMOND_SHOVEL, 1, 8), sell(Items.DIAMOND_HOE, 1, 6), sell(Items.DIAMOND_AXE, 1, 12),
                sell(Items.DIAMOND_PICKAXE, 1, 14),
                buy(Items.COAL, 12, 1), buy(Items.RAW_IRON, 4, 1), buy(Items.IRON_INGOT, 3, 1), buy(Items.RAW_GOLD, 4, 1),
                buy(Items.GOLD_INGOT, 3, 1), buy(Items.RAW_COPPER, 12, 1), buy(Items.COPPER_INGOT, 10, 1), buy(Items.QUARTZ, 12, 1),
                buy(Items.AMETHYST_SHARD, 8, 1), buy(Items.DIAMOND, 1, 5), buy(Items.NETHERITE_SCRAP, 1, 12))));
        m.put("fletcher", type("fletcher", "Fletcher", "Fletcher", VillagerProfession.FLETCHER, new String[]{"Fletcher", "Bows"}, List.of(
                sell(Items.ARROW, 16, 1), sell(Items.TARGET, 1, 1), sell(Items.FLETCHING_TABLE, 1, 1), sell(Items.FIREWORK_ROCKET, 8, 1),
                sell(Items.BOW, 1, 2), sell(Items.CROSSBOW, 1, 3), sell(Items.SPECTRAL_ARROW, 8, 2),
                sell(potion(Items.TIPPED_ARROW, Potions.SLOWNESS).copyWithCount(8), 3),
                sell(potion(Items.TIPPED_ARROW, Potions.POISON).copyWithCount(8), 3),
                sell(potion(Items.TIPPED_ARROW, Potions.HARMING).copyWithCount(8), 4),
                buy(Items.STICK, 32, 1), buy(Items.FEATHER, 20, 1), buy(Items.STRING, 12, 1), buy(Items.FLINT, 20, 1))));
        m.put("enchanter", type("enchanter", "Enchanter", "Enchanter", VillagerProfession.LIBRARIAN, new String[]{"Enchanter", ""}, List.of(
                sell(Items.LAPIS_LAZULI, 8, 1), sell(Items.BOOK, 4, 1), sell(Items.BOOKSHELF, 1, 2), sell(Items.EXPERIENCE_BOTTLE, 4, 3),
                sell(Items.GRINDSTONE, 1, 2), sell(Items.ANVIL, 1, 8), sell(Items.ENCHANTING_TABLE, 1, 10),
                sell(book(Enchantments.BLOCK_EFFICIENCY, 3), 8), sell(book(Enchantments.UNBREAKING, 2), 8), sell(book(P, 2), 8),
                sell(book(Enchantments.SHARPNESS, 2), 8), sell(book(Enchantments.POWER_ARROWS, 2), 8),
                sell(book(Enchantments.FALL_PROTECTION, 3), 8), sell(book(Enchantments.RESPIRATION, 2), 6),
                sell(book(Enchantments.AQUA_AFFINITY, 1), 6), sell(book(Enchantments.DEPTH_STRIDER, 2), 8),
                sell(book(Enchantments.BLOCK_FORTUNE, 1), 10), sell(book(Enchantments.MOB_LOOTING, 1), 10),
                sell(book(Enchantments.SILK_TOUCH, 1), 12),
                buy(Items.PAPER, 24, 1), buy(Items.BOOK, 4, 1), buy(Items.INK_SAC, 6, 1), buy(Items.LAPIS_LAZULI, 16, 1))));
        m.put("alchemist", type("alchemist", "Alchemist", "Alchemist", VillagerProfession.CLERIC, new String[]{"Alchemist", ""}, List.of(
                sell(Items.GLASS_BOTTLE, 8, 1), sell(Items.REDSTONE, 4, 1), sell(Items.GLOWSTONE_DUST, 4, 1),
                sell(Items.FERMENTED_SPIDER_EYE, 2, 1), sell(Items.NETHER_WART, 8, 2), sell(Items.BLAZE_POWDER, 2, 2),
                sell(Items.MAGMA_CREAM, 1, 2), sell(Items.GLISTERING_MELON_SLICE, 1, 2), sell(Items.BREWING_STAND, 1, 3),
                sell(potion(Items.POTION, Potions.HEALING), 3), sell(potion(Items.POTION, Potions.SWIFTNESS), 3),
                sell(potion(Items.POTION, Potions.NIGHT_VISION), 3), sell(potion(Items.POTION, Potions.WATER_BREATHING), 3),
                sell(potion(Items.POTION, Potions.FIRE_RESISTANCE), 4), sell(potion(Items.POTION, Potions.REGENERATION), 4),
                sell(potion(Items.POTION, Potions.STRENGTH), 4), sell(potion(Items.SPLASH_POTION, Potions.HEALING), 4),
                sell(Items.GOLDEN_APPLE, 1, 6),
                buy(Items.ROTTEN_FLESH, 32, 1), buy(Items.SPIDER_EYE, 6, 1), buy(Items.NETHER_WART, 16, 1), buy(Items.GLOWSTONE_DUST, 8, 1),
                buy(Items.GUNPOWDER, 6, 1), buy(Items.MAGMA_CREAM, 2, 1), buy(Items.ENDER_PEARL, 1, 1), buy(Items.BLAZE_ROD, 1, 2),
                buy(Items.PHANTOM_MEMBRANE, 1, 2), buy(Items.RABBIT_FOOT, 1, 2), buy(Items.GHAST_TEAR, 1, 4))));
        m.put("mason", type("mason", "Mason & Carpenter", "Mason", VillagerProfession.MASON, new String[]{"Mason &", "Carpenter"}, List.of(
                sell(Items.STONE_BRICKS, 16, 1), sell(Items.BRICKS, 10, 1), sell(Items.SMOOTH_STONE, 16, 1),
                sell(Items.POLISHED_ANDESITE, 16, 1), sell(Items.POLISHED_DIORITE, 16, 1), sell(Items.POLISHED_GRANITE, 16, 1),
                sell(Items.CHISELED_STONE_BRICKS, 8, 1), sell(Items.DEEPSLATE_TILES, 16, 1), sell(Items.MUD_BRICKS, 16, 1),
                sell(Items.TERRACOTTA, 8, 1), sell(Items.WHITE_CONCRETE, 8, 1), sell(Items.QUARTZ_BLOCK, 4, 1), sell(Items.GLASS, 8, 1),
                sell(Items.OAK_PLANKS, 32, 1), sell(Items.SPRUCE_PLANKS, 32, 1), sell(Items.OAK_LOG, 8, 1), sell(Items.SCAFFOLDING, 16, 1),
                sell(Items.LANTERN, 3, 1), sell(Items.FLOWER_POT, 4, 1), sell(Items.DECORATED_POT, 1, 1), sell(Items.STONECUTTER, 1, 1),
                buy(Items.CLAY_BALL, 10, 1), buy(Items.STONE, 20, 1), buy(Items.COBBLED_DEEPSLATE, 32, 1), buy(Items.GRANITE, 16, 1),
                buy(Items.DIORITE, 16, 1), buy(Items.ANDESITE, 16, 1), buy(Items.SAND, 24, 1), buy(Items.GRAVEL, 24, 1),
                buy(Items.QUARTZ, 12, 1), buy(Items.OBSIDIAN, 4, 1))));
        m.put("stables", type("stables", "Stables & Tack", "Ostler", VillagerProfession.LEATHERWORKER, new String[]{"Stables &", "Tack"}, List.of(
                sell(Items.LEATHER, 2, 1), sell(Items.HAY_BLOCK, 2, 1), sell(Items.APPLE, 4, 1), sell(Items.GOLDEN_CARROT, 3, 3),
                sell(Items.LEAD, 1, 2), sell(Items.CARROT_ON_A_STICK, 1, 1), sell(Items.LEATHER_HORSE_ARMOR, 1, 3),
                sell(Items.SADDLE, 1, 6), sell(Items.IRON_HORSE_ARMOR, 1, 6), sell(Items.GOLDEN_HORSE_ARMOR, 1, 8),
                sell(Items.NAME_TAG, 1, 10), sell(Items.DIAMOND_HORSE_ARMOR, 1, 16),
                buy(Items.LEATHER, 6, 1), buy(Items.RABBIT_HIDE, 6, 1), buy(Items.WHEAT, 20, 1), buy(Items.SADDLE, 1, 3),
                buy(Items.NAME_TAG, 1, 4), buy(Items.IRON_HORSE_ARMOR, 1, 3), buy(Items.GOLDEN_HORSE_ARMOR, 1, 4),
                buy(Items.DIAMOND_HORSE_ARMOR, 1, 8))));
        m.put("quartermaster", type("quartermaster", "Quartermaster", "Quartermaster", VillagerProfession.CARTOGRAPHER, new String[]{"Quarter-", "master"}, List.of(
                sell(Items.BREAD, 6, 1), sell(Items.COOKED_BEEF, 5, 1), sell(Items.ARROW, 16, 1), sell(Items.TORCH, 16, 1),
                sell(Items.CAMPFIRE, 1, 1), sell(Items.LEAD, 1, 2), sell(potion(Items.POTION, Potions.HEALING), 3),
                sell(Items.WHITE_BED, 1, 2), sell(Items.MAP, 1, 2), sell(Items.SPYGLASS, 1, 4), sell(Items.SHIELD, 1, 3),
                sell(Items.SADDLE, 1, 6),
                buy(Items.IRON_INGOT, 4, 1), buy(Items.LEATHER, 6, 1), buy(Items.WHEAT, 20, 1), buy(Items.STRING, 12, 1),
                buy(Items.BONE, 16, 1), buy(Items.DIAMOND, 1, 5))));
        return m;
    }

    // ------------------------------------------------------------ keepers

    /** How many keepers of one trade a place keeps: a town's stalls repeat, and a city has a street of them. */
    private static int cap(Holding h) {
        return h.isCity() ? 5 : 3;
    }

    /** Whether a place has shops at all: towns, cities and the great castles' yard markets. */
    public static boolean hasShops(Holding h) {
        return h.isTown() || h.isCity() || Market.wants(h);
    }

    /** The shops a place keeps, by today's trade, with every post that is staffed. */
    public static Map<String, List<BlockPos>> posts(Holding h) {
        Map<String, List<BlockPos>> out = new LinkedHashMap<>();
        for (var e : h.extras.entrySet()) {
            if (!e.getKey().startsWith("shop_")) continue;
            Type t = type(e.getKey().substring(5));
            if (t == null) continue;
            List<BlockPos> list = out.computeIfAbsent(t.id(), k -> new ArrayList<>());
            for (BlockPos p : e.getValue()) if (list.size() < cap(h)) list.add(p);
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
        Map<String, Integer> staffed = new java.util.HashMap<>();
        for (var e : h.extras.entrySet()) {
            if (!e.getKey().startsWith("shop_")) continue;
            Type t = type(e.getKey().substring(5));
            if (t == null) continue;
            List<BlockPos> ps = e.getValue();
            for (int i = 0; i < ps.size(); i++) {
                if (staffed.merge(t.id(), 1, Integer::sum) > cap(h)) break;
                BlockPos post = ps.get(i);
                if (!level.isLoaded(post) || !level.areEntitiesLoaded(ChunkPos.asLong(post))) continue;
                if (!h.shopkeepers.containsKey(post)) {
                    // a place an older plan built can have its counter a step off where today's plan puts it
                    BlockPos at = standAt(level, post);
                    if (at == null) continue;
                    if (!at.equals(post)) {
                        ps.set(i, at);
                        post = at;
                        dirty = true;
                    }
                }
                Villager v = keeperAt(level, h, post);
                if (v != null) {
                    if (!v.getUUID().equals(h.shopkeepers.get(post))) {
                        h.shopkeepers.put(post.immutable(), v.getUUID());
                        dirty = true;
                    }
                    if (v.distanceToSqr(post.getX() + 0.5, post.getY(), post.getZ() + 0.5) > 1.0) {
                        v.teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
                    }
                    if (!t.id().equals(v.getPersistentData().getString(TAG))) {
                        convert(level, v, t, post);                // a keeper of a trade since folded into another
                    } else if (newDay) {
                        restock(level, v, t.id(), post);
                    }
                    continue;
                }
                if (h.shopkeepers.containsKey(post) && !newDay) continue;      // killed: his stall stands empty till morning
                Villager k = spawnKeeper(level, h, t.id(), post);
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
                if (h.ownedHouses.containsKey(q) || q.distSqr(p.blockPosition()) > 25 || !doorNear(level, q)) continue;
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

    /**
     * Where a keeper stands for a post: the post itself if a man can stand there, else the nearest spot within
     * two blocks that is clear and has a counter beside it. Null if there's no such spot.
     */
    @Nullable
    public static BlockPos standAt(ServerLevel level, BlockPos post) {
        if (standable(level, post)) return post;
        BlockPos best = null;
        int bd = Integer.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(post.offset(-2, -1, -2), post.offset(2, 1, 2))) {
            if (!level.isLoaded(q) || !standable(level, q) || !counterBeside(level, q)) continue;
            int d = q.distManhattan(post) * 2 + (q.getY() != post.getY() ? 1 : 0);
            if (d < bd) {
                bd = d;
                best = q.immutable();
            }
        }
        return best;
    }

    /** Room for a man: nothing solid at his feet or head, and a floor under him. */
    static boolean standable(ServerLevel level, BlockPos p) {
        if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return false;
        if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) return false;
        if (!level.getFluidState(p).isEmpty()) return false;
        return !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
    }

    /** A counter beside this spot: a trapdoor, a fence or a slab at waist or chest height. */
    private static boolean counterBeside(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                var b = level.getBlockState(p.relative(d).above(dy)).getBlock();
                if (b instanceof TrapDoorBlock || b instanceof FenceBlock || b instanceof SlabBlock) return true;
            }
        }
        return false;
    }

    /** Whether a home for sale has its door where the plans say: a place an older plan built can differ. */
    private static boolean doorNear(ServerLevel level, BlockPos plot) {
        for (BlockPos q : BlockPos.betweenClosed(plot.offset(-2, 0, -2), plot.offset(2, 1, 2))) {
            if (q.distManhattan(plot) - Math.abs(q.getY() - plot.getY()) > 2) continue;
            if (level.getBlockState(q).getBlock() instanceof DoorBlock) return true;
        }
        return false;
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
        tag.put("Offers", offers(t, Frontier.zone(level, post)).createTag());
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

    /** A new morning: the keeper's stock laid out fresh, as his trade and his zone keep it. */
    public static void restock(ServerLevel level, Villager v, String kind, BlockPos post) {
        Type t = type(kind);
        if (t == null) {
            restock(v);
            return;
        }
        if (v.getTradingPlayer() != null) {
            restock(v);                                       // mid-trade: back in stock now, new goods tomorrow
            return;
        }
        MerchantOffers fresh = offers(t, Frontier.zone(level, post));
        v.getOffers().clear();
        v.getOffers().addAll(fresh);
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

    /** Every word a shop's sign has ever said: the first towns' labels, the plans' signs and today's names. */
    private static final Set<String> SHOP_WORDS = Set.of("merchant", "smithy", "tools", "armoury", "weapon", "smith",
            "weaponsmith", "fletcher", "bows", "farm", "supply", "bakery", "fish", "fishmonger", "draper", "tailor", "saddler",
            "tannery", "butcher", "mason", "potter", "apothe", "cary", "apothecary", "chandler", "general", "store", "food",
            "provisions", "enchanter", "alchemist", "carpenter", "stables", "tack", "quarter", "master", "quartermaster");

    private static boolean shopSign(SignBlockEntity s) {
        for (int i = 0; i < 4; i++) {
            String w = s.getFrontText().getMessage(i, false).getString().toLowerCase().replaceAll("[^a-z]", "");
            if (SHOP_WORDS.contains(w)) return true;
        }
        return false;
    }

    /** The shop's sign shows today's name: the nearest shop label near the counter is rewritten. */
    private static void labelSign(ServerLevel level, BlockPos post, Type t) {
        SignBlockEntity best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(post.offset(-3, 0, -3), post.offset(3, 3, 3))) {
            if (!(level.getBlockState(q).getBlock() instanceof SignBlock)) continue;
            if (!(level.getBlockEntity(q) instanceof SignBlockEntity s) || !shopSign(s)) continue;
            double d = q.distSqr(post);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        if (best == null) return;
        String l1 = best.getFrontText().getMessage(1, false).getString(), l2 = best.getFrontText().getMessage(2, false).getString();
        if (l1.equals(t.sign()[0]) && l2.equals(t.sign()[1])) return;
        write(level, best, "", t.sign()[0], t.sign()[1], "");
    }

    /** A keeper of a trade since folded into another takes the new one: its apron, name, stock and sign. */
    public static void convert(ServerLevel level, Villager v, Type t, BlockPos post) {
        v.setVillagerData(v.getVillagerData().setProfession(t.profession()).setLevel(5));
        String first = v.hasCustomName() ? v.getCustomName().getString().split(" ")[0] : NameGen.commoner(level.random, "none").split(" ")[0];
        v.setCustomName(Component.literal(first + " the " + t.keeper()));
        v.getPersistentData().putString(TAG, t.id());
        if (v.getTradingPlayer() == null) {
            MerchantOffers fresh = offers(t, Frontier.zone(level, post));
            v.getOffers().clear();
            v.getOffers().addAll(fresh);
        }
        labelSign(level, post, t);
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

    // ------------------------------------------------------------ regulars

    /** Trades at a place's shops for each percent off, up to {@link #MAX_DISCOUNT}. */
    public static final int TRADES_PER_PERCENT = 6, MAX_DISCOUNT = 25;

    /** The percent a place's shops take off for a regular. */
    public static int discount(Holding h, UUID player) {
        return Math.min(MAX_DISCOUNT, h.custom.getOrDefault(player, 0) / TRADES_PER_PERCENT);
    }

    @Nullable
    private static Holding placeOf(ServerLevel level, Villager v) {
        if (!v.getPersistentData().contains(TAG) || !v.getPersistentData().hasUUID(HOLDING_TAG)) return null;
        return HoldingManager.get(level).holdings.get(v.getPersistentData().getUUID(HOLDING_TAG));
    }

    /** As a regular opens a keeper's trades: his discount comes off every price in emeralds. */
    public static void welcome(ServerLevel level, ServerPlayer p, Villager v) {
        Holding h = placeOf(level, v);
        if (h == null || v.getTradingPlayer() != null || v.isBaby() || !v.isAlive()) return;
        for (MerchantOffer o : v.getOffers()) o.resetSpecialPriceDiff();
        int pct = discount(h, p.getUUID());
        if (pct <= 0) return;
        applyDiscount(v, pct);
        p.displayClientMessage(Component.literal("A regular in " + h.name + ": " + pct + "% off.").withStyle(ChatFormatting.GREEN), true);
    }

    /** Take a percent off every price in emeralds (the game puts it back when the trading window closes). */
    public static void applyDiscount(Villager v, int pct) {
        for (MerchantOffer o : v.getOffers()) {
            if (!o.getBaseCostA().is(Items.EMERALD)) continue;
            int cut = (int) Math.floor(o.getBaseCostA().getCount() * pct / 100.0);
            if (cut > 0) o.addToSpecialPriceDiff(-cut);
        }
    }

    /** A trade done at one of a place's shops counts toward being a regular there. */
    public static void traded(ServerLevel level, ServerPlayer p, Villager v) {
        Holding h = placeOf(level, v);
        if (h == null) return;
        int before = discount(h, p.getUUID());
        h.custom.merge(p.getUUID(), 1, Integer::sum);
        HoldingManager.get(level).setDirty();
        int after = discount(h, p.getUUID());
        if (after > before && after % 5 == 0)
            p.sendSystemMessage(Component.literal("The shops of " + h.name + " know you now: " + after + "% off from here on.")
                    .withStyle(ChatFormatting.GREEN));
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
