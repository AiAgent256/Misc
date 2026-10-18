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
    private net.minecraft.world.phys.Vec3 lastPos = net.minecraft.world.phys.Vec3.ZERO;
    private int stuck;

    public FollowOwnerGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private boolean following() {
        var m = net.aiagent256.stormhold.holding.Orders.modeOf(mob);
        return m == net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW || m == net.aiagent256.stormhold.holding.Orders.Mode.AGGRESSIVE;
    }

    @Override
    public boolean canUse() {
        if (mob.owner() == null || mob.getTarget() != null || !following()) return false;
        Player p = mob.ownerPlayer();
        if (p == null || p.isSpectator()) return false;
        if (mob.distanceToSqr(p) < WALK * WALK) return false;
        owner = p;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && mob.getTarget() == null && following() && mob.distanceToSqr(owner) > STAND * STAND;
    }

    @Override
    public void start() {
        repath = 0;
        stuck = 0;
        lastPos = mob.position();
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
        // a ladder, vine, rope or scaffold between us: use it
        double dy = owner.getY() - mob.getY();
        if (dy > 2.0 && d2 < 14 * 14 && mob.onClimbable()) {
            mob.getNavigation().stop();
            var d = mob.getDeltaMovement();
            mob.setDeltaMovement(d.x * 0.5, 0.22, d.z * 0.5);
            mob.setJumping(true);
            return;
        }
        mob.setJumping(false);
        if (dy > 2.0 && d2 < 14 * 14) {
            BlockPos foot = ladderFoot();
            if (foot != null) {
                mob.getNavigation().moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 1.2);
                if (mob.distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(foot)) < 2.5) {
                    var to = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5 - mob.getX(), 0, foot.getZ() + 0.5 - mob.getZ()).normalize().scale(0.12);
                    mob.setDeltaMovement(mob.getDeltaMovement().add(to));
                }
                return;
            }
        }
        // no way there on foot (cliff, water, a wall): after a few seconds of no headway, find one
        boolean moved = mob.position().distanceToSqr(lastPos) > 1.0;
        lastPos = mob.position();
        stuck = moved ? 0 : stuck + 1;
        if (mob.level() instanceof ServerLevel level && (d2 > CATCH_UP * CATCH_UP || (stuck >= 6 && d2 > 8 * 8))) {
            if (teleportNear(level)) {
                stuck = 0;
                return;
            }
        }
        double speed = d2 > 14 * 14 ? 1.35 : 1.1;
        mob.getNavigation().moveTo(owner, speed);
    }

    /** The bottom of the nearest climbable column that reaches up toward the owner. */
    @org.jetbrains.annotations.Nullable
    private BlockPos ladderFoot() {
        var level = mob.level();
        BlockPos me = mob.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos p = me.offset(dx, dy, dz);
                    if (!level.getBlockState(p).is(net.minecraft.tags.BlockTags.CLIMBABLE)) continue;
                    BlockPos foot = p;
                    while (level.getBlockState(foot.below()).is(net.minecraft.tags.BlockTags.CLIMBABLE) && me.getY() - foot.getY() < 4) foot = foot.below();
                    BlockPos top = p;
                    while (level.getBlockState(top.above()).is(net.minecraft.tags.BlockTags.CLIMBABLE)) top = top.above();
                    if (top.getY() < owner.getY() - 2.5) continue;
                    double score = foot.distSqr(me);
                    if (score < bestScore) {
                        bestScore = score;
                        best = foot;
                    }
                }
            }
        }
        return best;
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
