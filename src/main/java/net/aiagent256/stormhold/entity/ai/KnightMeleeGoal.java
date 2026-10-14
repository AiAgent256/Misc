package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Melee with a player's reach. Vanilla mobs can only hit what is about two blocks away and level
 * with them, so a player standing two blocks up can swing at a knight who can't swing back. Knights
 * reach three blocks in any direction, like the player does.
 */
public class KnightMeleeGoal extends MeleeAttackGoal {
    public static final double REACH = 3.0;
    private final GarrisonEntity knight;

    public KnightMeleeGoal(GarrisonEntity mob, double speed) {
        super(mob, speed, true);
        this.knight = mob;
    }

    @Override
    protected double getAttackReachSqr(LivingEntity target) {
        double r = REACH + target.getBbWidth() * 0.5;
        return r * r;
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity target, double distSqr) {
        // measure to the nearest point of the target's body rather than its feet, so standing on a
        // ledge above the knight is no safer than standing in front of him
        double dx = target.getX() - knight.getX(), dz = target.getZ() - knight.getZ();
        double dy = Math.max(0, Math.max(target.getY() - (knight.getY() + knight.getBbHeight()),
                knight.getY() - (target.getY() + target.getBbHeight())));
        double d = dx * dx + dz * dz + dy * dy;
        super.checkAndPerformAttack(target, d);
    }
}
