package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Shoots at the target. Wall archers hold their post; field archers close to range. */
public class GarrisonRangedGoal extends Goal {
    private final GarrisonEntity mob;
    private int cooldown;
    private int seeTime;

    public GarrisonRangedGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        return t != null && t.isAlive();
    }

    @Override
    public void start() {
        mob.setAggressive(true);
        cooldown = 10;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        seeTime = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        Role role = mob.role();
        double range = role.range;
        double d2 = mob.distanceToSqr(t);
        boolean see = mob.getSensing().hasLineOfSight(t);
        seeTime = see ? seeTime + 1 : 0;
        if (!mob.holdsPosition()) {
            if (!see || d2 > range * range * 0.6) {
                mob.getNavigation().moveTo(t, 1.0);
            } else {
                mob.getNavigation().stop();
            }
        }
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);
        if (--cooldown <= 0 && see && seeTime >= 5 && d2 <= range * range) {
            float power = (float) Math.min(1.0, Math.sqrt(d2) / range + 0.4);
            mob.performRangedAttack(t, power);
            int base = role == Role.CROSSBOWMAN ? 45 : 28;
            cooldown = base + mob.getRandom().nextInt(15) - mob.tier() * 2;
        }
    }
}
