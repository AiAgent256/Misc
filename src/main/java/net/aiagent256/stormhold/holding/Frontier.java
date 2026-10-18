package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Stormhold;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The farther out, the harder the world. Zones count out from the frontier's centre (world spawn until a
 * player sets it with {@code /stormhold frontier here}): zone 1 at the centre, one more every
 * {@link Config#FRONTIER_BAND} blocks, zone 9 from eight bands out. The Nether counts eight to one; the End
 * counts out from its middle, ten to one, so the outer islands are the far frontier.
 *
 * <p>A castle's people are kitted at its renown or its zone, whichever is higher, up to netherite with
 * Protection IV on every knight in zone 9 (see Loadouts). Its markets stock better goods (see Shops). And
 * every hostile mob, bosses too, gets more health, damage and armour as it joins the world, and the
 * armed ones better gear.
 */
public final class Frontier {
    public static final int MAX = 9;
    public static final String TAG = "StormholdZone";

    private static final UUID HEALTH = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d01");
    private static final UUID DAMAGE = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d02");
    private static final UUID ARMOR = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d03");
    private static final UUID TOUGH = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d04");
    private static final UUID STEADY = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d05");
    private static final UUID SPEED = UUID.fromString("6f1d3a4e-2c1b-4b8e-9a51-3e0f7a1c2d06");

    private static final String[] NAMES = {"", "the Heartland", "the Near Marches", "the Marches", "the Borderlands",
            "the Outlands", "the Wilds", "the Far Wilds", "the Frontier", "the World's Edge"};

    @Nullable private static MinecraftServer cachedFor;
    @Nullable private static BlockPos cachedCentre;

    public static boolean enabled() {
        return Config.FRONTIER_ENABLED.get();
    }

    public static String name(int zone) {
        return NAMES[Math.max(1, Math.min(MAX, zone))];
    }

    /** Where the zones count out from, in overworld blocks. */
    public static BlockPos centre(MinecraftServer server) {
        if (cachedFor == server && cachedCentre != null) return cachedCentre;
        HoldingManager m = HoldingManager.get(server.overworld());
        BlockPos c;
        if (m.frontierSet) c = new BlockPos(m.frontierX, 0, m.frontierZ);
        else {
            BlockPos s = server.overworld().getSharedSpawnPos();
            c = new BlockPos(s.getX(), 0, s.getZ());
        }
        cachedFor = server;
        cachedCentre = c;
        return c;
    }

    /** Set the centre where a player stands (in overworld blocks, whatever world he's in). */
    public static void setCentre(MinecraftServer server, ServerLevel level, double x, double z) {
        double s = level.dimension() == Level.END ? 1 : level.dimensionType().coordinateScale();
        HoldingManager m = HoldingManager.get(server.overworld());
        m.frontierSet = true;
        m.frontierX = (int) Math.floor(x * s);
        m.frontierZ = (int) Math.floor(z * s);
        m.setDirty();
        cachedCentre = null;
    }

    /** How far out a spot is, in overworld blocks. */
    public static double distance(ServerLevel level, double x, double z) {
        return distance(level, x, z, centre(level.getServer()));
    }

    public static double distance(ServerLevel level, double x, double z, BlockPos centre) {
        if (level.dimension() == Level.END) return Math.sqrt(x * x + z * z) * 10;
        double s = level.dimensionType().coordinateScale();
        double dx = x * s - centre.getX(), dz = z * s - centre.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** The zone so many blocks out: 1 to 9. */
    public static int zoneAt(double distance) {
        if (!enabled()) return 1;
        return Math.max(1, Math.min(MAX, 1 + (int) (distance / Config.FRONTIER_BAND.get())));
    }

    public static int zone(ServerLevel level, double x, double z) {
        return zoneAt(distance(level, x, z));
    }

    public static int zone(ServerLevel level, BlockPos p) {
        return zone(level, p.getX() + 0.5, p.getZ() + 0.5);
    }

    /** The level the land itself gives: a castle's renown or its zone, whichever is higher (1 to 9). For armies sent against it. */
    public static int baseLevel(ServerLevel level, @Nullable Holding h) {
        if (h == null) return 2;
        return Math.max(h.renown, zone(level, h.center()));
    }

    /** The level a castle's people are kitted at: its renown, its zone, or what its smiths have forged, whichever is highest (1 to 9). */
    public static int level(ServerLevel level, @Nullable Holding h) {
        if (h == null) return 2;
        return Math.max(Math.max(h.renown, h.forged), zone(level, h.center()));
    }

    // ------------------------------------------------------------ the zone you're in

    private static final Map<UUID, Integer> LAST_ZONE = new HashMap<>();

    /** Tell a traveller when he crosses into another zone. */
    public static void watch(ServerPlayer p) {
        if (!enabled()) return;
        int z = zone(p.serverLevel(), p.getX(), p.getZ());
        Integer was = LAST_ZONE.put(p.getUUID(), z);
        if (was == null || was == z) return;
        ChatFormatting c = z >= 8 ? ChatFormatting.DARK_RED : z >= 6 ? ChatFormatting.RED : z >= 4 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
        String how = z > was ? "Harder country: " : "Gentler country: ";
        p.displayClientMessage(Component.literal(how + name(z) + ", zone " + z + " of " + MAX + ".").withStyle(c), true);
    }

    // ------------------------------------------------------------ wild mobs

    /** A hostile mob joining the world is made as hard as the zone it's in, once. */
    public static void onJoin(ServerLevel level, Mob mob) {
        if (!enabled() || !Config.FRONTIER_MOBS.get()) return;
        if (!(mob instanceof Enemy) && mob.getType().getCategory() != MobCategory.MONSTER) return;
        CompoundTag data = mob.getPersistentData();
        if (data.contains(TAG)) return;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
        if (id != null && id.getNamespace().equals(Stormhold.MODID)) return;   // our own people are kitted by Loadouts
        int z = zone(level, mob.getX(), mob.getZ());
        data.putInt(TAG, z);
        if (z > 1) scale(mob, z);
    }

    /** More health, harder blows, armour and footing, a little speed; better gear for the armed kinds. */
    public static void scale(Mob mob, int zone) {
        int k = Math.max(0, Math.min(MAX, zone) - 1);
        if (k == 0) return;
        add(mob, Attributes.MAX_HEALTH, HEALTH, "Frontier health", 0.25 * k, AttributeModifier.Operation.MULTIPLY_TOTAL);
        add(mob, Attributes.ATTACK_DAMAGE, DAMAGE, "Frontier damage", 0.125 * k, AttributeModifier.Operation.MULTIPLY_TOTAL);
        add(mob, Attributes.ARMOR, ARMOR, "Frontier armour", k, AttributeModifier.Operation.ADDITION);
        add(mob, Attributes.ARMOR_TOUGHNESS, TOUGH, "Frontier toughness", 0.5 * k, AttributeModifier.Operation.ADDITION);
        add(mob, Attributes.KNOCKBACK_RESISTANCE, STEADY, "Frontier footing", 0.05 * k, AttributeModifier.Operation.ADDITION);
        add(mob, Attributes.MOVEMENT_SPEED, SPEED, "Frontier speed", 0.015 * k, AttributeModifier.Operation.MULTIPLY_BASE);
        mob.setHealth(mob.getMaxHealth());
        if (mob instanceof Creeper c && k >= 3) {
            CompoundTag t = new CompoundTag();
            c.addAdditionalSaveData(t);
            t.putByte("ExplosionRadius", (byte) Math.min(6, 3 + k / 3));
            c.readAdditionalSaveData(t);
        }
        gear(mob, k + 1);
    }

    private static void add(Mob m, Attribute a, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance ai = m.getAttribute(a);
        if (ai == null || ai.getModifier(id) != null) return;
        ai.addPermanentModifier(new AttributeModifier(id, name, amount, op));
    }

    /** From zone 3 the armed kinds come armoured: iron, then diamond from 5, netherite from 7, enchanted. */
    private static void gear(Mob mob, int zone) {
        if (zone < 3) return;
        boolean armed = mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof AbstractPiglin
                || mob instanceof AbstractIllager;
        if (!armed) return;
        var r = mob.getRandom();
        int metal = zone >= 7 ? 3 : zone >= 5 ? 2 : 1;
        int prot = Math.min(4, (zone - 1) / 2);
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Item[][] sets = {
                {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
                {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
                {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}};
        for (int i = 0; i < 4; i++) {
            if (!mob.getItemBySlot(slots[i]).isEmpty() || r.nextFloat() >= 0.08F * zone) continue;
            ItemStack piece = new ItemStack(sets[metal - 1][i]);
            piece.enchant(Enchantments.ALL_DAMAGE_PROTECTION, prot);
            mob.setItemSlot(slots[i], piece);
            mob.setDropChance(slots[i], 0.02F);
        }
        ItemStack hand = mob.getMainHandItem();
        int sharp = Math.min(5, (zone - 1) / 2 + (zone >= 9 ? 1 : 0));
        if (hand.getItem() instanceof BowItem) {
            if (!hand.isEnchanted()) hand.enchant(Enchantments.POWER_ARROWS, sharp);
        } else if (hand.getItem() instanceof CrossbowItem) {
            if (!hand.isEnchanted() && zone >= 4) hand.enchant(Enchantments.QUICK_CHARGE, Math.min(3, (zone - 1) / 3));
        } else if (hand.isEmpty() && !(mob instanceof AbstractSkeleton) && r.nextFloat() < 0.08F * zone) {
            ItemStack sword = new ItemStack(metal == 3 ? Items.NETHERITE_SWORD : metal == 2 ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
            sword.enchant(Enchantments.SHARPNESS, sharp);
            mob.setItemSlot(EquipmentSlot.MAINHAND, sword);
            mob.setDropChance(EquipmentSlot.MAINHAND, 0.02F);
        } else if (!hand.isEmpty() && !hand.isEnchanted() && hand.isEnchantable()) {
            hand.enchant(Enchantments.SHARPNESS, sharp);
        }
    }

    private Frontier() {
    }
}
