package net.aiagent256.stormhold.compat;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/** Talks to Villager Recruits through reflection so Stormhold runs with or without it. */
public final class RecruitsCompat {
    private static Boolean loaded;
    private static Class<?> recruitClass;
    private static Method getOwnerUUID, getIsOwned, hire, getInventory;

    public static boolean loaded() {
        if (loaded == null) {
            loaded = false;
            if (ModList.get().isLoaded("recruits")) {
                try {
                    recruitClass = Class.forName("com.talhanation.recruits.entities.AbstractRecruitEntity");
                    getOwnerUUID = recruitClass.getMethod("getOwnerUUID");
                    getIsOwned = recruitClass.getMethod("getIsOwned");
                    Class<?> group = Class.forName("com.talhanation.recruits.world.RecruitsGroup");
                    hire = recruitClass.getMethod("hire", Player.class, group, boolean.class);
                    try {
                        getInventory = recruitClass.getMethod("getInventory");
                    } catch (Throwable t) {
                        Stormhold.LOGGER.warn("Stormhold: can't reach recruit inventories; armoury will only set equipment", t);
                    }
                    loaded = true;
                } catch (Throwable t) {
                    Stormhold.LOGGER.warn("Stormhold: Villager Recruits found but its API changed; muster disabled", t);
                }
            }
        }
        return loaded;
    }

    /** "player:&lt;uuid&gt;" for an owned recruit, otherwise null. */
    @Nullable
    public static String sideOf(Entity e) {
        if (!loaded() || !recruitClass.isInstance(e)) return null;
        try {
            if (!(Boolean) getIsOwned.invoke(e)) return null;
            Object id = getOwnerUUID.invoke(e);
            return id instanceof UUID u ? Sides.player(u) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A recruit's own inventory (slots 0-3 armour head to feet, 4 off hand, 5 main hand), or null. */
    @Nullable
    public static net.minecraft.world.Container inventoryOf(Entity e) {
        if (!loaded() || getInventory == null || !recruitClass.isInstance(e)) return null;
        try {
            Object inv = getInventory.invoke(e);
            return inv instanceof net.minecraft.world.Container c ? c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which inventory slot a recruit keeps a piece of equipment in. */
    public static int inventorySlot(net.minecraft.world.entity.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            case OFFHAND -> 4;
            case MAINHAND -> 5;
        };
    }

    /** The Stormhold role a Recruits unit stands in for, by its type; null if it isn't a recruit. */
    @Nullable
    public static Role roleOf(Entity e) {
        if (!loaded() || !recruitClass.isInstance(e)) return null;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        if (id == null) return Role.KNIGHT;
        return switch (id.getPath()) {
            case "bowman" -> Role.ARCHER;
            case "crossbowman" -> Role.CROSSBOWMAN;
            case "captain" -> Role.CAPTAIN;
            default -> Role.KNIGHT;
        };
    }

    private static String typeFor(Role r) {
        return switch (r) {
            case ARCHER -> "bowman";
            case CROSSBOWMAN -> "crossbowman";
            case KNIGHT -> "recruit_shieldman";
            default -> "recruit";
        };
    }

    /** Spawn a hired Recruits unit next to the player. Returns false if Recruits refused. */
    public static boolean muster(ServerLevel level, Player player, Role role) {
        if (!loaded()) return false;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", typeFor(role)));
        if (type == null) return false;
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) return false;
        BlockPos p = null;
        for (int i = 0; i < 8 && p == null; i++) {
            BlockPos q = player.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
            BlockPos free = net.aiagent256.stormhold.holding.Garrisons.standable(level, q);
            if (free != null && free.distSqr(player.blockPosition()) <= 16) p = free;
        }
        if (p == null) p = player.blockPosition();
        mob.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, player.getYRot(), 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.MOB_SUMMONED, null, null);
        level.addFreshEntity(mob);
        try {
            Object ok = hire.invoke(mob, player, null, false);
            if (ok instanceof Boolean b && !b) {
                mob.discard();
                return false;
            }
            return true;
        } catch (Throwable t) {
            Stormhold.LOGGER.warn("Stormhold: hiring recruit failed", t);
            mob.discard();
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static Optional<Object> none() {
        return Optional.empty();
    }

    private RecruitsCompat() {
    }
}
