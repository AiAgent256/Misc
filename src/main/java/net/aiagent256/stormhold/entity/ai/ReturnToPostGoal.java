package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Goes back to the guard post when there is nothing to fight. */
public class ReturnToPostGoal extends Goal {
    private final GarrisonEntity mob;
    private int stuck;

    public ReturnToPostGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private double leash() {
        return mob.holdsPosition() ? 1.5 : 7.0;
    }

    @Override
    public boolean canUse() {
        BlockPos p = mob.post();
        if (p == null || mob.marchTarget() != null || mob.getTarget() != null) return false;
        double l = leash();
        return mob.distanceToSqr(Vec3.atBottomCenterOf(p)) > l * l;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() == null && !mob.getNavigation().isDone() && stuck < 200;
    }

    @Override
    public void start() {
        stuck = 0;
        BlockPos p = mob.post();
        if (p != null) mob.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.0);
    }

    @Override
    public void tick() {
        stuck++;
    }

    @Override
    public void stop() {
        // pathing failed for a long time: put wall archers back on the wall
        BlockPos p = mob.post();
        if (p != null && stuck >= 200 && mob.holdsPosition() && mob.distanceToSqr(Vec3.atBottomCenterOf(p)) > 64) {
            mob.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        }
    }
}
