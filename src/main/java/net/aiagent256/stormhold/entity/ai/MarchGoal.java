package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.holding.Garrisons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Walks toward a distant target in legs short enough for the pathfinder. A soldier leaving a castle
 * heads for the gate first ({@link GarrisonEntity#marchVia()}); the pathfinder can't see a route over
 * the walls to a point outside, so without that they'd stand on the battlements forever. The gate guard
 * raises the portcullis as they come up to it. A player's own man who still can't find his way out after
 * a good while is moved to the gate, so a war party always gets out of the castle it was raised in.
 */
public class MarchGoal extends Goal {
    private final GarrisonEntity mob;
    private int repath;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuckLegs;

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
        stuckLegs = 0;
        lastPos = mob.position();
    }

    @Override
    public void tick() {
        if (--repath > 0 && !mob.getNavigation().isDone()) return;
        repath = 40;
        BlockPos via = mob.marchVia();
        if (via != null && mob.distanceToSqr(Vec3.atBottomCenterOf(via)) < 4 * 4) {
            // reached this waypoint; the next one (outside the gate) takes its place
            via = mob.marchVia2();
            mob.setMarchVia(via);
            mob.setMarchVia2(null);
            stuckLegs = 0;
        }
        BlockPos t = via != null ? via : mob.marchTarget();
        if (t == null) return;
        // not getting anywhere?
        if (mob.position().distanceToSqr(lastPos) < 1.0) stuckLegs++;
        else stuckLegs = 0;
        lastPos = mob.position();
        if (via != null && stuckLegs >= 8 && !mob.isHouse() && mob.level() instanceof ServerLevel level) {
            BlockPos p = Garrisons.standable(level, via);
            if (p != null) {
                mob.getNavigation().stop();
                mob.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                mob.setMarchVia(mob.marchVia2());
                mob.setMarchVia2(null);
                stuckLegs = 0;
                return;
            }
        }
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
