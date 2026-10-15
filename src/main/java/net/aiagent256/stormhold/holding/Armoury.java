package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Kitting out the men who ride with you. A kit is a full set of armour and a weapon for the man's role in
 * one metal, paid in that metal from your pack first and then from the castle's stores (if you're near it).
 * Enchanting a kit costs experience levels. Whatever they were wearing comes back to you.
 */
public final class Armoury {
    public enum Kit {
        IRON(1, Items.IRON_INGOT, 26, 0, "iron"),
        DIAMOND(2, Items.DIAMOND, 26, 0, "diamond"),
        NETHERITE(3, Items.DIAMOND, 26, 5, "netherite");

        public final int tier;
        public final Item metal;
        public final int metalCost;
        public final int netheriteCost;
        public final String word;

        Kit(int tier, Item metal, int metalCost, int netheriteCost, String word) {
            this.tier = tier;
            this.metal = metal;
            this.metalCost = metalCost;
            this.netheriteCost = netheriteCost;
            this.word = word;
        }

        @Nullable
        public static Kit byTier(int t) {
            for (Kit k : values()) if (k.tier == t) return k;
            return null;
        }
    }

    /** Experience levels to enchant one man's kit. */
    public static final int ENCHANT_LEVELS = 10;
    public static final int RANGE = Company.DISMISS_RANGE;

    private record Man(LivingEntity entity, Role role, int tier, boolean enchanted) {
    }

    /**
     * Arm every man of the player's within RANGE who is below {@code kit} (or unenchanted, when enchanting).
     * Returns how many were armed.
     */
    public static int arm(ServerLevel level, HoldingManager mgr, Player player, @Nullable Holding h, Kit kit, boolean enchant) {
        List<Man> men = new ArrayList<>();
        String me = Sides.player(player.getUUID());
        for (var e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(RANGE))) {
            Role r = null;
            if (e instanceof GarrisonEntity g) {
                if (g.owner() != null && g.owner().equals(player.getUUID())) r = g.role();
            } else if (me.equals(RecruitsCompat.sideOf(e))) {
                r = RecruitsCompat.roleOf(e);
            }
            if (r == null || r == Role.LORD || r == Role.COURIER) continue;
            ItemStack chest = e.getItemBySlot(EquipmentSlot.CHEST);
            int have = tierOf(chest);
            boolean ench = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.ALL_DAMAGE_PROTECTION, chest) >= protLevel(kit);
            if (have > kit.tier || (have == kit.tier && (!enchant || ench))) continue;
            men.add(new Man(e, r, have, ench));
        }
        if (men.isEmpty()) {
            player.sendSystemMessage(Component.literal("None of your men within " + RANGE + " blocks need " + kit.word + " kit"
                    + (enchant ? " with enchantments." : ".")).withStyle(ChatFormatting.RED));
            return 0;
        }
        men.sort(Comparator.comparingDouble(m -> m.entity.distanceToSqr(player)));
        boolean stores = h != null;
        int armed = 0, fromStores = 0, netherFromStores = 0;
        String stopped = null;
        for (Man m : men) {
            // a man already in this metal only needs the enchantments
            int metal = m.tier == kit.tier ? 0 : kit.metalCost;
            int nether = m.tier == kit.tier ? 0 : kit.netheriteCost;
            int packMetal = player.getInventory().countItem(kit.metal), packNether = player.getInventory().countItem(Items.NETHERITE_INGOT);
            int storeMetal = stores ? Stores.realmStock(mgr, h, player.getUUID(), key(kit.metal)) : 0;
            int storeNether = stores ? Stores.realmStock(mgr, h, player.getUUID(), key(Items.NETHERITE_INGOT)) : 0;
            if (packMetal + storeMetal < metal) {
                stopped = "out of " + name(kit.metal) + " (" + metal + " per man)";
                break;
            }
            if (packNether + storeNether < nether) {
                stopped = "out of netherite ingots (" + nether + " per man)";
                break;
            }
            if (enchant && !player.isCreative() && player.experienceLevel < ENCHANT_LEVELS) {
                stopped = "out of experience (" + ENCHANT_LEVELS + " levels per man)";
                break;
            }
            // pay
            int takeMetal = take(player, kit.metal, metal);
            if (takeMetal > 0) {
                Stores.take(mgr, h, player.getUUID(), key(kit.metal), takeMetal);
                fromStores += takeMetal;
            }
            int takeNether = take(player, Items.NETHERITE_INGOT, nether);
            if (takeNether > 0) {
                Stores.take(mgr, h, player.getUUID(), key(Items.NETHERITE_INGOT), takeNether);
                netherFromStores += takeNether;
            }
            if (enchant && !player.isCreative()) player.giveExperienceLevels(-ENCHANT_LEVELS);
            // dress
            dress(m.entity, m.role, kit, enchant, player);
            armed++;
        }
        if (armed > 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.PLAYERS, 1.0F, 1.0F);
            StringBuilder msg = new StringBuilder(armed + (armed == 1 ? " man" : " men") + " now in " + (enchant ? "enchanted " : "")
                    + kit.word + " kit.");
            if (fromStores > 0 || netherFromStores > 0) {
                msg.append(" The realm's stores paid ");
                if (fromStores > 0) msg.append(fromStores).append(" ").append(name(kit.metal));
                if (fromStores > 0 && netherFromStores > 0) msg.append(" and ");
                if (netherFromStores > 0) msg.append(netherFromStores).append(" netherite");
                msg.append(".");
            }
            player.sendSystemMessage(HoldingEvents.gold(msg.toString()));
            if (mgr != null) mgr.setDirty();
        }
        if (stopped != null) {
            player.sendSystemMessage(Component.literal((armed > 0 ? "Then " : "") + "The quartermaster ran " + stopped + ".")
                    .withStyle(ChatFormatting.RED));
        }
        return armed;
    }

    /** Take up to n from the player's pack; returns how many are still owed (to come from stores). */
    private static int take(Player p, Item item, int n) {
        int left = n;
        for (ItemStack s : p.getInventory().items) {
            if (left == 0) break;
            if (!s.is(item)) continue;
            int k = Math.min(left, s.getCount());
            s.shrink(k);
            left -= k;
        }
        return left;
    }

    private static ResourceLocation key(Item i) {
        return ForgeRegistries.ITEMS.getKey(i);
    }

    private static String name(Item i) {
        return i == Items.IRON_INGOT ? "iron" : i == Items.DIAMOND ? "diamonds" : i.getDescription().getString().toLowerCase();
    }

    public static int tierOf(ItemStack chest) {
        if (!(chest.getItem() instanceof ArmorItem a)) return 0;
        var m = a.getMaterial();
        if (m == ArmorMaterials.NETHERITE) return 3;
        if (m == ArmorMaterials.DIAMOND) return 2;
        if (m == ArmorMaterials.IRON) return 1;
        // modded armour: judge by toughness/defence
        if (m.getToughness() >= 3) return 3;
        if (m.getToughness() >= 2) return 2;
        return m.getDefenseForType(net.minecraft.world.item.ArmorItem.Type.CHESTPLATE) >= 6 ? 1 : 0;
    }

    private static int protLevel(Kit kit) {
        return Math.min(4, kit.tier + 1);
    }

    private static void dress(LivingEntity e, Role role, Kit kit, boolean enchant, Player player) {
        boolean nether = kit == Kit.NETHERITE, diamond = kit == Kit.DIAMOND;
        ItemStack head = new ItemStack(nether ? Items.NETHERITE_HELMET : diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET);
        ItemStack chest = new ItemStack(nether ? Items.NETHERITE_CHESTPLATE : diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE);
        ItemStack legs = new ItemStack(nether ? Items.NETHERITE_LEGGINGS : diamond ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS);
        ItemStack feet = new ItemStack(nether ? Items.NETHERITE_BOOTS : diamond ? Items.DIAMOND_BOOTS : Items.IRON_BOOTS);
        ItemStack weapon = switch (role) {
            case ARCHER -> new ItemStack(Items.BOW);
            case CROSSBOWMAN -> new ItemStack(Items.CROSSBOW);
            default -> new ItemStack(nether ? Items.NETHERITE_SWORD : diamond ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
        };
        if (enchant) {
            int p = protLevel(kit);
            for (ItemStack a : new ItemStack[]{head, chest, legs, feet}) {
                a.enchant(Enchantments.ALL_DAMAGE_PROTECTION, p);
                a.enchant(Enchantments.UNBREAKING, 3);
            }
            switch (role) {
                case ARCHER -> {
                    weapon.enchant(Enchantments.POWER_ARROWS, p);
                    if (nether) weapon.enchant(Enchantments.FLAMING_ARROWS, 1);
                }
                case CROSSBOWMAN -> {
                    weapon.enchant(Enchantments.QUICK_CHARGE, Math.min(3, kit.tier));
                    if (nether) weapon.enchant(Enchantments.PIERCING, 2);
                }
                default -> {
                    weapon.enchant(Enchantments.SHARPNESS, p);
                    if (nether) weapon.enchant(Enchantments.FIRE_ASPECT, 1);
                }
            }
            weapon.enchant(Enchantments.UNBREAKING, 3);
        }
        put(e, EquipmentSlot.HEAD, head, player);
        put(e, EquipmentSlot.CHEST, chest, player);
        put(e, EquipmentSlot.LEGS, legs, player);
        put(e, EquipmentSlot.FEET, feet, player);
        // a man with a modded or enchanted weapon better than plain keeps it
        ItemStack held = e.getItemBySlot(EquipmentSlot.MAINHAND);
        if (held.isEmpty() || weaponTier(held) < kit.tier || (enchant && !held.isEnchanted())) {
            put(e, EquipmentSlot.MAINHAND, weapon, player);
        }
        if (e instanceof Mob mob) {
            for (EquipmentSlot s : EquipmentSlot.values()) mob.setDropChance(s, 0.0F);
        }
    }

    private static int weaponTier(ItemStack s) {
        if (s.is(Items.NETHERITE_SWORD)) return 3;
        if (s.is(Items.DIAMOND_SWORD)) return 2;
        if (s.is(Items.IRON_SWORD) || s.is(Items.BOW) || s.is(Items.CROSSBOW)) return 1;
        return 0;
    }

    /** Swap in a piece of kit; the old piece goes to the player. Recruits units also get their inventory slot set. */
    private static void put(LivingEntity e, EquipmentSlot slot, ItemStack stack, Player player) {
        ItemStack old = e.getItemBySlot(slot).copy();
        e.setItemSlot(slot, stack.copy());
        Container inv = RecruitsCompat.inventoryOf(e);
        if (inv != null) {
            int idx = RecruitsCompat.inventorySlot(slot);
            if (idx >= 0 && idx < inv.getContainerSize()) inv.setItem(idx, stack.copy());
        }
        if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
    }

    private Armoury() {
    }
}
