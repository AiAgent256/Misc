package net.aiagent256.stormhold.entity;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.house.House;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/** Gear by role and tier. Uses Epic Knights armor when installed, vanilla otherwise. */
public final class Loadouts {
    private static Boolean ekLoaded;

    private static boolean ek() {
        if (ekLoaded == null) ekLoaded = ModList.get().isLoaded("magistuarmory");
        return ekLoaded && Config.EPIC_KNIGHTS_GEAR.get();
    }

    private static ItemStack pick(String ekName, Item fallback) {
        if (ek()) {
            Item i = ForgeRegistries.ITEMS.getValue(new ResourceLocation("magistuarmory", ekName));
            if (i != null && i != Items.AIR) return new ItemStack(i);
        }
        return new ItemStack(fallback);
    }

    private static ItemStack dyed(ItemStack s, DyeColor c) {
        if (s.getItem() instanceof DyeableLeatherItem d) d.setColor(s, c.getFireworkColor());
        return s;
    }

    public static ItemStack shield(DyeColor base, @Nullable House house) {
        ItemStack s = new ItemStack(Items.SHIELD);
        CompoundTag bet = new CompoundTag();
        bet.putInt("Base", (house != null ? house.base : base).getId());
        if (house != null) bet.put("Patterns", house.patterns());
        s.addTagElement("BlockEntityTag", bet);
        return s;
    }

    public static void equip(GarrisonEntity e, Role role, int tier, DyeColor color, @Nullable House house) {
        RandomSource r = e.getRandom();
        DyeColor c = house != null ? house.base : color;
        for (EquipmentSlot s : EquipmentSlot.values()) e.setItemSlot(s, ItemStack.EMPTY);
        switch (role) {
            case LORD -> {
                set(e, pick("grand_bascinet", Items.NETHERITE_HELMET), pick("gothic_chestplate", Items.NETHERITE_CHESTPLATE),
                        pick("gothic_leggings", Items.NETHERITE_LEGGINGS), pick("gothic_boots", Items.NETHERITE_BOOTS));
                ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
                sword.enchant(Enchantments.SHARPNESS, tier);
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case CAPTAIN -> {
                set(e, pick("maximilian_helmet", Items.DIAMOND_HELMET), pick("maximilian_chestplate", Items.DIAMOND_CHESTPLATE),
                        pick("maximilian_leggings", Items.IRON_LEGGINGS), pick("maximilian_boots", Items.IRON_BOOTS));
                ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
                if (tier > 1) sword.enchant(Enchantments.SHARPNESS, tier - 1);
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case KNIGHT -> {
                if (tier <= 1) {
                    set(e, pick("kettlehat", Items.CHAINMAIL_HELMET), pick("chainmail_chestplate", Items.CHAINMAIL_CHESTPLATE),
                            pick("chainmail_leggings", Items.CHAINMAIL_LEGGINGS), pick("chainmail_boots", Items.CHAINMAIL_BOOTS));
                } else {
                    String helm = r.nextBoolean() ? "armet" : (r.nextBoolean() ? "bascinet" : "barbute");
                    set(e, pick(helm, Items.IRON_HELMET), pick("knight_chestplate", Items.IRON_CHESTPLATE),
                            pick("knight_leggings", Items.IRON_LEGGINGS), pick("knight_boots", Items.IRON_BOOTS));
                }
                ItemStack sword = new ItemStack(tier >= 3 && r.nextFloat() < 0.3F ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
                if (tier >= 3) sword.enchant(Enchantments.SHARPNESS, 1);
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case ARCHER -> {
                set(e, dyed(pick("coif", Items.LEATHER_HELMET), c), dyed(pick("gambeson_chestplate", Items.LEATHER_CHESTPLATE), c),
                        dyed(new ItemStack(Items.LEATHER_LEGGINGS), c), dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                ItemStack bow = pick("longbow", Items.BOW);
                if (!(bow.getItem() instanceof BowItem)) bow = new ItemStack(Items.BOW);
                if (tier >= 2) bow.enchant(Enchantments.POWER_ARROWS, tier - 1);
                e.setItemSlot(EquipmentSlot.MAINHAND, bow);
            }
            case CROSSBOWMAN -> {
                set(e, pick("kettlehat", Items.IRON_HELMET), pick("brigandine_chestplate", Items.CHAINMAIL_CHESTPLATE),
                        pick("chainmail_leggings", Items.CHAINMAIL_LEGGINGS), dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                ItemStack xbow = pick("heavy_crossbow", Items.CROSSBOW);
                if (!(xbow.getItem() instanceof CrossbowItem)) xbow = new ItemStack(Items.CROSSBOW);
                e.setItemSlot(EquipmentSlot.MAINHAND, xbow);
            }
            case COURIER -> {
                e.setItemSlot(EquipmentSlot.CHEST, dyed(new ItemStack(Items.LEATHER_CHESTPLATE), c));
                e.setItemSlot(EquipmentSlot.FEET, dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                e.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BUNDLE));
            }
        }
    }

    private static void set(GarrisonEntity e, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {
        e.setItemSlot(EquipmentSlot.HEAD, head);
        e.setItemSlot(EquipmentSlot.CHEST, chest);
        e.setItemSlot(EquipmentSlot.LEGS, legs);
        e.setItemSlot(EquipmentSlot.FEET, feet);
    }

    private Loadouts() {
    }
}
