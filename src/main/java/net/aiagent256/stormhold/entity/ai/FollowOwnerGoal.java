package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.holding.Garrisons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * A soldier in a player's company keeps up with them. Within a few blocks they stand easy; further out
 * they walk, and if the player rides off and leaves them well behind they catch up the way a wolf does,
 * so a company on foot can follow a horse.
 */
public class FollowOwnerGoal extends Goal {
    private static final double STAND = 4.0, WALK = 7.0, CATCH_UP = 28.0;
    private final GarrisonEntity mob;
    private Player owner;
    private int repath;

    public FollowOwnerGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.owner() == null || mob.getTarget() != null) return false;
        Player p = mob.ownerPlayer();
        if (p == null || p.isSpectator()) return false;
        if (mob.distanceToSqr(p) < WALK * WALK) return false;
        owner = p;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && mob.getTarget() == null && mob.distanceToSqr(owner) > STAND * STAND;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(owner, 10.0F, mob.getMaxHeadXRot());
        if (--repath > 0) return;
        repath = 10;
        double d2 = mob.distanceToSqr(owner);
        if (d2 > CATCH_UP * CATCH_UP && mob.level() instanceof ServerLevel level) {
            if (teleportNear(level)) return;
        }
        double speed = d2 > 14 * 14 ? 1.35 : 1.1;
        mob.getNavigation().moveTo(owner, speed);
    }

    private boolean teleportNear(ServerLevel level) {
        BlockPos o = owner.blockPosition();
        for (int i = 0; i < 10; i++) {
            int dx = mob.getRandom().nextInt(7) - 3, dz = mob.getRandom().nextInt(7) - 3;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos p = Garrisons.standable(level, o.offset(dx, 0, dz));
            if (p == null) continue;
            mob.getNavigation().stop();
            mob.teleportTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            return true;
        }
        return false;
    }
}
