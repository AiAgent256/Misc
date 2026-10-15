package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Garrisons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Shoots at the target. Field archers close to range. Wall archers work their stretch of wall: when the
 * shot is blocked or too long they look for a spot near their post with a clear line, and if the fight
 * has moved inside where no spot on the wall can see it, they come down for a while. Anyone who climbs
 * up next to them gets the bow in the face.
 */
public class GarrisonRangedGoal extends Goal {
    /** How far a wall archer wanders from the post looking for a shot. */
    private static final int WALL_REACH = 6;
    /** Blocked for this long with nowhere on the wall to shoot from: go down and fight. */
    private static final int SORTIE_AFTER = 100;
    private static final int SORTIE_LENGTH = 500;

    private final GarrisonEntity mob;
    private int cooldown;
    private int seeTime;
    private int blindTime;
    private int repositionCooldown;
    private int meleeCooldown;
    @Nullable private BlockPos perch;

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
        blindTime = 0;
        perch = null;
    }

    @Override
    public void stop() {
        mob.setAggressive(false);
        seeTime = 0;
        blindTime = 0;
        perch = null;
        mob.getNavigation().stop();
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
        boolean inRange = d2 <= range * range;
        seeTime = see ? seeTime + 1 : 0;
        blindTime = see && inRange ? 0 : blindTime + 1;
        if (repositionCooldown > 0) repositionCooldown--;
        if (meleeCooldown > 0) meleeCooldown--;
        mob.getLookControl().setLookAt(t, 30.0F, 30.0F);

        // someone is on the wall with us: hit them
        if (d2 < 2.4 * 2.4 && meleeCooldown <= 0 && mob.hasLineOfSight(t)) {
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            mob.doHurtTarget(t);
            meleeCooldown = 20;
        }

        boolean onWall = mob.holdsPosition() && mob.post() != null && mob.sortieTicks <= 0;
        if (onWall) {
            tickWall(t, see, inRange, d2);
        } else {
            if (!see || d2 > range * range * 0.6) {
                mob.getNavigation().moveTo(t, 1.0);
            } else {
                mob.getNavigation().stop();
            }
        }

        if (--cooldown <= 0 && see && seeTime >= 5 && inRange) {
            float power = (float) Math.min(1.0, Math.sqrt(d2) / range + 0.4);
            mob.performRangedAttack(t, power);
            int base = role == Role.CROSSBOWMAN ? 45 : 28;
            cooldown = base + mob.getRandom().nextInt(15) - mob.tier() * 2;
        }
    }

    private void tickWall(LivingEntity t, boolean see, boolean inRange, double d2) {
        if (see && inRange) {
            mob.getNavigation().stop();
            perch = null;
            return;
        }
        // walking to a perch we already picked
        if (perch != null) {
            if (mob.distanceToSqr(Vec3.atBottomCenterOf(perch)) < 1.2 || mob.getNavigation().isDone()) perch = null;
            else return;
        }
        if (repositionCooldown <= 0) {
            repositionCooldown = 30;
            BlockPos p = findPerch(t);
            if (p != null) {
                perch = p;
                mob.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 1.1);
                return;
            }
        }
        // nothing on the wall can see them and they're close: come down and fight for a while
        double dx = t.getX() - mob.getX(), dz = t.getZ() - mob.getZ();
        if (blindTime > SORTIE_AFTER && dx * dx + dz * dz < 40 * 40 && t.getY() < mob.getY() + 2) {
            mob.sortieTicks = SORTIE_LENGTH;
            blindTime = 0;
        }
    }

    /** A spot within WALL_REACH of the post, at the wall's height, with a clear line to the target and closer than here. */
    @Nullable
    private BlockPos findPerch(LivingEntity t) {
        if (!(mob.level() instanceof ServerLevel level)) return null;
        BlockPos post = mob.post();
        if (post == null) return null;
        Vec3 eyeTarget = t.getEyePosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -WALL_REACH; dx <= WALL_REACH; dx++) {
            for (int dz = -WALL_REACH; dz <= WALL_REACH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = post.offset(dx, dy, dz);
                    if (p.equals(mob.blockPosition())) continue;
                    if (!Garrisons.canStand(level, p)) continue;
                    Vec3 eye = new Vec3(p.getX() + 0.5, p.getY() + mob.getEyeHeight(), p.getZ() + 0.5);
                    double d = eye.distanceToSqr(eyeTarget);
                    if (d > mob.role().range * mob.role().range) continue;
                    if (!seesFrom(level, eye, eyeTarget)) continue;
                    // prefer a short walk and a shorter shot
                    double score = p.distSqr(mob.blockPosition()) * 0.6 + d * 0.1;
                    if (score < bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
        }
        return best;
    }

    private boolean seesFrom(Level level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob)).getType() == HitResult.Type.MISS;
    }
}
