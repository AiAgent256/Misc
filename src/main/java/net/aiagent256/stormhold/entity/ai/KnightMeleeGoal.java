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

    /** Which way he circles between blows, and how long before he thinks again. */
    private int side = 1, sideTicks;

    /**
     * Between blows a knight in reach doesn't stand pressed against his man: he gives a little ground and circles,
     * sword up, the way two men fight rather than a mob, and steps in again when his arm is ready.
     */
    @Override
    public void tick() {
        super.tick();
        LivingEntity t = this.mob.getTarget();
        if (t == null || !t.isAlive()) return;
        if (knight.holdsPosition() || knight.climbing || knight.onClimbable() || knight.isInWater() || !knight.onGround()) return;
        if (this.getTicksUntilNextAttack() <= 6) return;
        double dx = t.getX() - knight.getX(), dz = t.getZ() - knight.getZ(), d2 = dx * dx + dz * dz;
        if (d2 > getAttackReachSqr(t) || Math.abs(t.getY() - knight.getY()) > 1.5) return;
        if (--sideTicks <= 0) {
            sideTicks = 25 + knight.getRandom().nextInt(40);
            if (knight.getRandom().nextFloat() < 0.6F) side = -side;
        }
        knight.getNavigation().stop();
        knight.getMoveControl().strafe(d2 < 2.0 * 2.0 ? -0.6F : 0.0F, 0.7F * side);
        knight.getLookControl().setLookAt(t, 30.0F, 30.0F);
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
