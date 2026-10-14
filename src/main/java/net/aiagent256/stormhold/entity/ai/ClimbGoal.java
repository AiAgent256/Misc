package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Soldiers use ladders, vines and scaffolding. When the target is above and there is something
 * climbable nearby, the knight walks to its foot and goes up it. Siege ladders a player leaves on
 * the wall work for the garrison too.
 */
public class ClimbGoal extends Goal {
    private final GarrisonEntity mob;
    private BlockPos ladderFoot;
    private int ticks;

    public ClimbGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || mob.role().ranged) return false;
        double dy = t.getY() - mob.getY();
        if (dy < 2.0) return false;
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        if (dx * dx + dz * dz > 14 * 14) return false;
        ladderFoot = findLadder(t);
        return ladderFoot != null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        return t != null && ticks < 300 && ladderFoot != null && t.getY() - mob.getY() > 0.5;
    }

    @Override
    public void start() {
        ticks = 0;
        mob.climbing = true;
        mob.getNavigation().moveTo(ladderFoot.getX() + 0.5, ladderFoot.getY(), ladderFoot.getZ() + 0.5, 1.2);
    }

    @Override
    public void tick() {
        ticks++;
        LivingEntity t = mob.getTarget();
        if (t == null) return;
        if (mob.onClimbable()) {
            mob.getNavigation().stop();
            // press into the ladder and go up
            Vec3 d = mob.getDeltaMovement();
            mob.setDeltaMovement(d.x * 0.5, 0.22, d.z * 0.5);
            mob.getLookControl().setLookAt(t, 30F, 30F);
            mob.setJumping(true);
        } else if (mob.getNavigation().isDone()) {
            double dist = mob.distanceToSqr(ladderFoot.getX() + 0.5, ladderFoot.getY(), ladderFoot.getZ() + 0.5);
            if (dist > 2.5) mob.getNavigation().moveTo(ladderFoot.getX() + 0.5, ladderFoot.getY(), ladderFoot.getZ() + 0.5, 1.2);
            else {
                // nudge onto the climbable block
                Vec3 to = new Vec3(ladderFoot.getX() + 0.5 - mob.getX(), 0, ladderFoot.getZ() + 0.5 - mob.getZ()).normalize().scale(0.12);
                mob.setDeltaMovement(mob.getDeltaMovement().add(to));
            }
        }
    }

    @Override
    public void stop() {
        mob.climbing = false;
        mob.setJumping(false);
        ladderFoot = null;
    }

    /** The bottom of the climbable column nearest the target, within 7 blocks of the knight. */
    private BlockPos findLadder(LivingEntity t) {
        Level level = mob.level();
        BlockPos me = mob.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos p = me.offset(dx, dy, dz);
                    if (!level.getBlockState(p).is(BlockTags.CLIMBABLE)) continue;
                    // walk down to the foot of this column
                    BlockPos foot = p;
                    while (level.getBlockState(foot.below()).is(BlockTags.CLIMBABLE) && me.getY() - foot.getY() < 4) foot = foot.below();
                    // and make sure it goes high enough to matter
                    BlockPos top = p;
                    while (level.getBlockState(top.above()).is(BlockTags.CLIMBABLE)) top = top.above();
                    if (top.getY() < t.getY() - 1.5) continue;
                    double score = foot.distSqr(me) + 0.5 * top.distSqr(t.blockPosition());
                    if (score < bestScore) {
                        bestScore = score;
                        best = foot;
                    }
                }
            }
        }
        return best;
    }
}
