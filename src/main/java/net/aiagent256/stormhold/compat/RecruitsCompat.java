package net.aiagent256.stormhold.compat;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static Method getOwnerUUID, getIsOwned, hire;

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

    private static String typeFor(Role r) {
        return switch (r) {
            case ARCHER -> "bowman";
            case CROSSBOWMAN -> "crossbowman";
            case KNIGHT -> "recruit_shieldman";
            default -> "recruit";
        };
    }

    /** Spawn a hired Recruits unit next to the player. Returns false if Recruits refused. */
    public static boolean muster(ServerLevel level, ServerPlayer player, Role role) {
        if (!loaded()) return false;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("recruits", typeFor(role)));
        if (type == null) return false;
        Entity e = type.create(level);
        if (!(e instanceof Mob mob)) return false;
        BlockPos p = player.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
        mob.moveTo(p.getX() + 0.5, player.getY(), p.getZ() + 0.5, player.getYRot(), 0);
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
