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
