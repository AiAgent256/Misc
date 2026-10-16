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

    /** Knights' sidearms: one-handed, so the shield stays up. */
    private static final String[] KNIGHT_ARMS = {"shortsword", "katzbalger", "heavymace", "morgenstern", "shortsword"};

    /** An Epic Knights weapon of the given metal (iron, diamond, netherite), or the vanilla sword. */
    public static ItemStack weapon(String metal, String kind, Item fallback) {
        return pick(metal + "_" + kind, fallback);
    }

    /** 0 none, 1 iron, 2 diamond, 3 netherite; reads Epic Knights weapons by their metal too. */
    public static int weaponMetal(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.is(Items.NETHERITE_SWORD)) return 3;
        if (s.is(Items.DIAMOND_SWORD)) return 2;
        if (s.is(Items.IRON_SWORD) || s.is(Items.BOW) || s.is(Items.CROSSBOW)) return 1;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(s.getItem());
        if (id != null && id.getNamespace().equals("magistuarmory")) {
            String n = id.getPath();
            if (n.startsWith("netherite_")) return 3;
            if (n.startsWith("diamond_")) return 2;
            if (n.startsWith("iron_") || n.equals("longbow") || n.endsWith("crossbow")) return 1;
        }
        return 0;
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
        equip(e, role, tier, 2, color, house);
    }

    /**
     * Renown decides the metal and the magic. 1: chain and leather. 2: iron. 3: iron with Protection I,
     * diamond captains. 4: diamond, Protection II, Sharpness I-II. 5: diamond Protection IV, Sharpness III,
     * Unbreaking, netherite lords. Archers and crossbowmen keep light armour but their bows climb too.
     */
    public static void equip(GarrisonEntity e, Role role, int tier, int renown, DyeColor color, @Nullable House house) {
        RandomSource r = e.getRandom();
        DyeColor c = house != null ? house.base : color;
        int rn = Math.max(1, Math.min(5, renown));
        for (EquipmentSlot s : EquipmentSlot.values()) e.setItemSlot(s, ItemStack.EMPTY);
        switch (role) {
            case LORD -> {
                boolean nether = rn >= 5 || (rn == 4 && r.nextBoolean());
                set(e, pick("grand_bascinet", nether ? Items.NETHERITE_HELMET : Items.DIAMOND_HELMET),
                        pick("gothic_chestplate", nether ? Items.NETHERITE_CHESTPLATE : Items.DIAMOND_CHESTPLATE),
                        pick("gothic_leggings", nether ? Items.NETHERITE_LEGGINGS : Items.DIAMOND_LEGGINGS),
                        pick("gothic_boots", nether ? Items.NETHERITE_BOOTS : Items.DIAMOND_BOOTS));
                protect(e, Math.max(1, rn - 1), rn >= 4);
                ItemStack sword = weapon(nether ? "netherite" : "diamond", "flamebladedsword", nether ? Items.NETHERITE_SWORD : Items.DIAMOND_SWORD);
                sword.enchant(Enchantments.SHARPNESS, Math.min(5, Math.max(1, rn)));
                if (rn >= 4) sword.enchant(Enchantments.FIRE_ASPECT, 1);
                if (rn >= 5) sword.enchant(Enchantments.UNBREAKING, 3);
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case CAPTAIN -> {
                boolean diamond = rn >= 3;
                set(e, pick("maximilian_helmet", diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET),
                        pick("maximilian_chestplate", diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE),
                        pick("maximilian_leggings", diamond ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS),
                        pick("maximilian_boots", diamond ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS));
                if (rn >= 3) protect(e, rn - 2, rn >= 5);
                ItemStack sword = weapon(diamond ? "diamond" : "iron", "bastardsword", diamond ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
                if (rn >= 2) sword.enchant(Enchantments.SHARPNESS, Math.min(4, rn - 1));
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case KNIGHT -> {
                if (rn <= 1) {
                    set(e, pick("kettlehat", Items.CHAINMAIL_HELMET), pick("chainmail_chestplate", Items.CHAINMAIL_CHESTPLATE),
                            pick("chainmail_leggings", Items.CHAINMAIL_LEGGINGS), pick("chainmail_boots", Items.CHAINMAIL_BOOTS));
                } else {
                    boolean diamond = rn >= 4;
                    String helm = r.nextBoolean() ? "armet" : (r.nextBoolean() ? "bascinet" : "barbute");
                    set(e, pick(helm, diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET),
                            pick("knight_chestplate", diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE),
                            pick("knight_leggings", diamond ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS),
                            pick("knight_boots", diamond ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS));
                }
                if (rn >= 3) protect(e, rn == 3 ? 1 : rn == 4 ? 2 : 4, rn >= 5);
                boolean dsword = rn >= 4 || (rn == 3 && r.nextFloat() < 0.3F);
                ItemStack sword = weapon(dsword ? "diamond" : "iron", KNIGHT_ARMS[r.nextInt(KNIGHT_ARMS.length)],
                        dsword ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
                if (rn >= 3) sword.enchant(Enchantments.SHARPNESS, rn == 3 ? 1 : rn == 4 ? 1 + r.nextInt(2) : 3);
                if (rn >= 5) sword.enchant(Enchantments.UNBREAKING, 2);
                e.setItemSlot(EquipmentSlot.MAINHAND, sword);
                e.setItemSlot(EquipmentSlot.OFFHAND, shield(c, house));
            }
            case ARCHER -> {
                set(e, dyed(pick("coif", Items.LEATHER_HELMET), c), dyed(pick("gambeson_chestplate", Items.LEATHER_CHESTPLATE), c),
                        dyed(new ItemStack(Items.LEATHER_LEGGINGS), c), dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                if (rn >= 4) protect(e, rn - 3, false);
                ItemStack bow = pick("longbow", Items.BOW);
                if (!(bow.getItem() instanceof BowItem)) bow = new ItemStack(Items.BOW);
                if (rn >= 2) bow.enchant(Enchantments.POWER_ARROWS, Math.min(5, rn - 1));
                if (rn >= 4) bow.enchant(Enchantments.PUNCH_ARROWS, 1);
                if (rn >= 5) bow.enchant(Enchantments.FLAMING_ARROWS, 1);
                e.setItemSlot(EquipmentSlot.MAINHAND, bow);
            }
            case CROSSBOWMAN -> {
                set(e, pick("kettlehat", Items.IRON_HELMET), pick("brigandine_chestplate", rn >= 4 ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE),
                        pick("chainmail_leggings", Items.CHAINMAIL_LEGGINGS), dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                if (rn >= 4) protect(e, rn - 3, false);
                ItemStack xbow = pick("heavy_crossbow", Items.CROSSBOW);
                if (!(xbow.getItem() instanceof CrossbowItem)) xbow = new ItemStack(Items.CROSSBOW);
                if (rn >= 3) xbow.enchant(Enchantments.QUICK_CHARGE, Math.min(3, rn - 2));
                if (rn >= 4) xbow.enchant(Enchantments.PIERCING, rn - 3);
                e.setItemSlot(EquipmentSlot.MAINHAND, xbow);
            }
            case COURIER -> {
                e.setItemSlot(EquipmentSlot.CHEST, dyed(new ItemStack(Items.LEATHER_CHESTPLATE), c));
                e.setItemSlot(EquipmentSlot.FEET, dyed(new ItemStack(Items.LEATHER_BOOTS), c));
                e.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BUNDLE));
            }
        }
    }

    /** Protection on every worn piece, plus Unbreaking when asked. */
    private static void protect(GarrisonEntity e, int level, boolean unbreaking) {
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = e.getItemBySlot(s);
            if (it.isEmpty()) continue;
            it.enchant(Enchantments.ALL_DAMAGE_PROTECTION, Math.max(1, Math.min(4, level)));
            if (unbreaking) it.enchant(Enchantments.UNBREAKING, 3);
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
