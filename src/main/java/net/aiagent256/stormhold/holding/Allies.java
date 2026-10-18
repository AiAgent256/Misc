package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player's pets (a tamed dragon, a wolf, a horse) and the soldiers who aren't his enemies can't hurt each
 * other. A dragon's fire across a melee, an archer's arrow through the press: an accident, and nobody turns
 * on anybody for it. That holds for his own garrisons, his company, his Recruits units, and the garrisons of
 * every house that doesn't hold him hostile. A house that does is fair game both ways.
 */
public final class Allies {
    /** True if this blow should not land. */
    public static boolean spared(ServerLevel level, @Nullable Entity attacker, LivingEntity victim) {
        if (attacker == null || attacker == victim) return false;
        UUID pet = petOwner(attacker);
        if (pet != null) {
            String side = sideOf(victim);
            return side != null && friendly(level, side, pet);
        }
        UUID victimsOwner = petOwner(victim);
        if (victimsOwner != null) {
            String side = sideOf(attacker);
            return side != null && friendly(level, side, victimsOwner);
        }
        return false;
    }

    /** The player a tamed animal belongs to; soldiers and Recruits units aren't pets. */
    @Nullable
    public static UUID petOwner(Entity e) {
        if (e instanceof GarrisonEntity || RecruitsCompat.sideOf(e) != null) return null;
        if (e instanceof TamableAnimal t) return t.isTame() ? t.getOwnerUUID() : null;
        if (e instanceof OwnableEntity o) return o.getOwnerUUID();
        return null;
    }

    /** Whose a soldier is: a house, a player (his garrisons and company), or nobody's. */
    @Nullable
    private static String sideOf(Entity e) {
        if (e instanceof GarrisonEntity g) return g.side().isEmpty() ? null : g.side();
        return RecruitsCompat.sideOf(e);
    }

    private static boolean friendly(ServerLevel level, String side, UUID player) {
        if (side.equals(Sides.player(player))) return true;
        String hid = Sides.houseId(side);
        if (hid == null) return false;                       // another player's men
        return !Standing.hostile(HoldingManager.get(level), hid, player);
    }

    private Allies() {
    }
}
