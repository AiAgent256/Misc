package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.holding.Orders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/** A man in a company that was told to hold a spot or clear a building goes there and stays. */
public class CompanyPostGoal extends Goal {
    private final GarrisonEntity mob;
    @Nullable private BlockPos spot;
    private int repath;

    public CompanyPostGoal(GarrisonEntity mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Nullable
    private BlockPos wanted() {
        if (mob.owner() == null || mob.getTarget() != null) return null;
        Orders.Mode m = Orders.modeOf(mob);
        if (m != Orders.Mode.HOLD && m != Orders.Mode.CLEAR) return null;
        return Orders.posOf(mob);
    }

    private double leash() {
        return Orders.modeOf(mob) == Orders.Mode.CLEAR ? 4.0 : 1.5;
    }

    @Override
    public boolean canUse() {
        BlockPos p = wanted();
        if (p == null) return false;
        spot = Orders.spot(mob, p);
        return mob.distanceToSqr(Vec3.atBottomCenterOf(spot)) > leash() * leash();
    }

    @Override
    public boolean canContinueToUse() {
        BlockPos p = wanted();
        return p != null && spot != null && mob.distanceToSqr(Vec3.atBottomCenterOf(spot)) > 1.0;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        spot = null;
    }

    @Override
    public void tick() {
        if (spot == null || --repath > 0) return;
        repath = 20;
        mob.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.2);
    }
}
