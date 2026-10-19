package net.aiagent256.stormhold.holding;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * What a castle's renown is worth once its lord is dead: the first chests opened in the keep get
 * extra treasure on top of their loot table, scaled from "a modest" to "a famed" seat.
 */
public final class Treasure {
    private static final Item[] DIAMOND_ARMOR = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final Item[] IRON_ARMOR = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final Enchantment[] BOOKS = {Enchantments.SHARPNESS, Enchantments.ALL_DAMAGE_PROTECTION, Enchantments.POWER_ARROWS,
            Enchantments.UNBREAKING, Enchantments.BLOCK_EFFICIENCY, Enchantments.BLOCK_FORTUNE, Enchantments.FIRE_ASPECT, Enchantments.KNOCKBACK};

    public static void fill(ServerLevel level, HoldingManager mgr, Holding h, ChestBlockEntity chest, ServerPlayer player) {
        h.bonusLeft--;
        mgr.setDirty();
        chest.unpackLootTable(player);
        List<ItemStack> bonus = roll(level.random, h.renown);
        int placed = 0;
        for (ItemStack s : bonus) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).isEmpty()) {
                    chest.setItem(i, s);
                    placed++;
                    break;
                }
            }
        }
        chest.setChanged();
        if (placed > 0) {
            player.displayClientMessage(Component.literal("The treasury of " + Holding.renownName(h.renown).replace("a ", "") + " seat.")
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    public static List<ItemStack> roll(RandomSource r, int renown) {
        List<ItemStack> out = new ArrayList<>();
        int rn = Math.max(1, Math.min(5, renown));
        switch (rn) {
            case 2 -> {
                out.add(armor(IRON_ARMOR[r.nextInt(4)], 1, r, false));
                out.add(new ItemStack(Items.GOLDEN_APPLE, 1 + r.nextInt(2)));
            }
            case 3 -> {
                out.add(armor(DIAMOND_ARMOR[r.nextInt(4)], 2, r, false));
                out.add(new ItemStack(Items.DIAMOND, 3 + r.nextInt(4)));
                out.add(book(r, 2 + r.nextInt(2)));
                out.add(new ItemStack(Items.GOLDEN_APPLE, 2));
            }
            case 4 -> {
                out.add(armor(DIAMOND_ARMOR[r.nextInt(4)], 3, r, true));
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(Enchantments.SHARPNESS, 3);
                sword.enchant(Enchantments.UNBREAKING, 2);
                out.add(sword);
                out.add(new ItemStack(Items.NETHERITE_INGOT, 1));
                out.add(new ItemStack(Items.DIAMOND, 5 + r.nextInt(5)));
                out.add(book(r, 3 + r.nextInt(2)));
            }
            case 5 -> {
                int piece = r.nextInt(4);
                out.add(armor(DIAMOND_ARMOR[piece], 4, r, true));
                out.add(armor(DIAMOND_ARMOR[(piece + 1 + r.nextInt(3)) % 4], 4, r, true));
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                sword.enchant(Enchantments.SHARPNESS, 4 + r.nextInt(2));
                sword.enchant(Enchantments.UNBREAKING, 3);
                sword.enchant(Enchantments.FIRE_ASPECT, r.nextBoolean() ? 2 : 1);
                out.add(sword);
                out.add(new ItemStack(Items.NETHERITE_INGOT, 2 + r.nextInt(2)));
                out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
                out.add(new ItemStack(Items.DIAMOND, 8 + r.nextInt(8)));
                out.add(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(Enchantments.MENDING, 1)));
                out.add(book(r, 4));
                out.add(new ItemStack(Items.TOTEM_OF_UNDYING, 1));
            }
            default -> {
            }
        }
        return out;
    }

    // ------------------------------------------------------------ the zone's own kit

    private static final java.util.Set<String> TREASURIES = java.util.Set.of("chests/castle_treasury", "chests/holdfast_treasury", "chests/watchtower_treasury");

    public static boolean isTreasury(@org.jetbrains.annotations.Nullable net.minecraft.resources.ResourceLocation id) {
        return id != null && id.getNamespace().equals(net.aiagent256.stormhold.Stormhold.MODID) && TREASURIES.contains(id.getPath());
    }

    /** Pieces of the zone's own kit a place keeps in its treasury chests, all told: a great castle about a set. */
    public static int gearBudget(Holding h) {
        int n = h.isCity() ? 8 : h.isTown() ? 1 : h.isNether() ? 3 : switch (h.tier) {
            case 1 -> 2;
            case 2 -> 3;
            default -> 5;
        };
        return h.ruined ? (n + 1) / 2 : n;
    }

    /** The kit a treasury chest at {@code at} gives on top of its loot: up to two pieces of the place's level, while they last. */
    public static List<ItemStack> zoneKit(ServerLevel level, net.minecraft.core.BlockPos at, RandomSource r) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.at(at, 0);
        if (h == null) return List.of();
        if (h.gearLeft < 0) h.gearLeft = gearBudget(h);
        int n = Math.min(2, h.gearLeft);
        if (n <= 0) return List.of();
        int lvl = Frontier.level(level, h);
        boolean first = h.gearGiven == 0;
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(kitPiece(nextSlot(h, r), lvl));
        h.gearLeft -= n;
        if (first && lvl >= 7) {
            out.add(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            out.add(new ItemStack(Items.NETHERITE_INGOT, 2));
        } else if (lvl >= 4) {
            out.add(new ItemStack(Items.DIAMOND, 2 + r.nextInt(3)));
        }
        mgr.setDirty();
        return out;
    }

    /** Helmet, chest, legs, boots, sword: each once before any twice. */
    private static int nextSlot(Holding h, RandomSource r) {
        if ((h.gearGiven & 31) == 31) h.gearGiven = 32;          // a full set gone: start over, and remember it's not the first
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < 5; i++) if ((h.gearGiven & (1 << i)) == 0) open.add(i);
        int s = open.get(r.nextInt(open.size()));
        h.gearGiven |= 1 << s;
        return s;
    }

    private static final Item[][] KIT = {
            {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS, Items.IRON_SWORD},
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.DIAMOND_SWORD},
            {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Items.NETHERITE_SWORD}};

    /** One piece of kit at a level, the same metal the level's knights wear, enchanted to match. */
    public static ItemStack kitPiece(int slot, int lvl) {
        int metal = Math.min(3, Readiness.tier(lvl));
        ItemStack s = new ItemStack(KIT[metal][slot]);
        int unbreaking = lvl >= 8 ? 3 : lvl >= 5 ? 2 : 0;
        if (slot < 4) {
            s.enchant(Enchantments.ALL_DAMAGE_PROTECTION, Math.min(4, 1 + lvl / 3));
            if (slot == 3 && lvl >= 3) s.enchant(Enchantments.FALL_PROTECTION, Math.min(4, 1 + lvl / 3));
        } else {
            s.enchant(Enchantments.SHARPNESS, Math.min(5, 1 + lvl / 2));
            if (lvl >= 7) s.enchant(Enchantments.FIRE_ASPECT, lvl >= 9 ? 2 : 1);
        }
        if (unbreaking > 0) s.enchant(Enchantments.UNBREAKING, unbreaking);
        if (lvl >= 9) s.enchant(Enchantments.MENDING, 1);
        return s;
    }

    private static ItemStack armor(Item item, int protection, RandomSource r, boolean unbreaking) {
        ItemStack s = new ItemStack(item);
        s.enchant(Enchantments.ALL_DAMAGE_PROTECTION, protection);
        if (unbreaking) s.enchant(Enchantments.UNBREAKING, 2 + r.nextInt(2));
        if (item == Items.DIAMOND_BOOTS && r.nextBoolean()) s.enchant(Enchantments.FALL_PROTECTION, Math.min(4, protection));
        return s;
    }

    private static ItemStack book(RandomSource r, int level) {
        Enchantment e = BOOKS[r.nextInt(BOOKS.length)];
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(e, Math.min(e.getMaxLevel(), Math.max(1, level))));
    }

    private Treasure() {
    }
}
