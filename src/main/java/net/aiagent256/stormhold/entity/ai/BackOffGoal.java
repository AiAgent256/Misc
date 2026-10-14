package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A knight who has been hit for a while by someone he can't reach (a player on a ledge, say)
 * doesn't stand there taking it. He backs out of reach and lets the archers work, and ignores
 * that attacker until they come down.
 */
public class BackOffGoal extends Goal {
    private final GarrisonEntity mob;
    private Vec3 dest;

    public BackOffGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.backOffTicks > 0 && mob.backOffFrom != null;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.backOffTicks > 0 && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.setTarget(null);
        Vec3 away = DefaultRandomPos.getPosAway(mob, 12, 5, mob.backOffFrom);
        dest = away != null ? away : mob.position().add(mob.position().subtract(mob.backOffFrom).normalize().scale(10));
        mob.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.25);
    }

    @Override
    public void tick() {
        if (mob.getNavigation().isDone() && mob.backOffTicks > 0 && mob.distanceToSqr(mob.backOffFrom) < 8 * 8) {
            start();
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
