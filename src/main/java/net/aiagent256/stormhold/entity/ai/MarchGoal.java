package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/** Walks toward a distant target in legs short enough for the pathfinder. */
public class MarchGoal extends Goal {
    private final GarrisonEntity mob;
    private int repath;

    public MarchGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        BlockPos t = mob.marchTarget();
        return t != null && mob.getTarget() == null && mob.distanceToSqr(Vec3.atBottomCenterOf(t)) > 9.0;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void tick() {
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = 40;
        BlockPos t = mob.marchTarget();
        if (t == null) return;
        Vec3 here = mob.position();
        Vec3 dir = Vec3.atBottomCenterOf(t).subtract(here);
        double dist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (dist < 28) {
            mob.getNavigation().moveTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5, 1.0);
            return;
        }
        double step = 24.0 / dist;
        int x = (int) Math.floor(here.x + dir.x * step);
        int z = (int) Math.floor(here.z + dir.z * step);
        if (!mob.level().isLoaded(new BlockPos(x, 0, z))) return;
        int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0);
    }
}
