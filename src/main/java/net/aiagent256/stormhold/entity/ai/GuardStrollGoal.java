package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Short patrols around the guard post. */
public class GuardStrollGoal extends Goal {
    private final GarrisonEntity mob;

    public GuardStrollGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.post() != null && mob.marchTarget() == null && mob.getTarget() == null
                && mob.getNavigation().isDone() && mob.getRandom().nextInt(160) == 0;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.getNavigation().isDone() && mob.getTarget() == null;
    }

    @Override
    public void start() {
        BlockPos p = mob.post();
        if (p == null) return;
        int dx = mob.getRandom().nextInt(11) - 5;
        int dz = mob.getRandom().nextInt(11) - 5;
        mob.getNavigation().moveTo(p.getX() + dx + 0.5, p.getY(), p.getZ() + dz + 0.5, 0.6);
    }
}
