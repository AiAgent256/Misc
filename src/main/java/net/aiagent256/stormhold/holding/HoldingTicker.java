package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Drives every holding in a dimension. */
public final class HoldingTicker {

    public static void tick(ServerLevel level) {
        long t = level.getGameTime();
        HoldingManager mgr = HoldingManager.get(level);
        if (t % 100 == 13) Detector.detect(level, mgr);
        Roads.tick(level, mgr);
        if (t % 20 != 0) return;
        for (Holding h : mgr.holdings.values()) tickHolding(level, mgr, h, t);
        if (t - mgr.lastStandingDrift >= 24000) {
            mgr.lastStandingDrift = t;
            net.aiagent256.stormhold.house.Standing.drift(mgr);
        }
        Campaigns.tick(level, mgr, t);
        Economy.tickShipments(level, mgr);
    }

    private static boolean near(ServerLevel level, Holding h) {
        int d = Config.MATERIALIZE_DISTANCE.get();
        int minX = h.box.minX() - d, maxX = h.box.maxX() + d, minZ = h.box.minZ() - d, maxZ = h.box.maxZ() + d;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ) return true;
        }
        return false;
    }

    private static void tickHolding(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        Economy.tickHolding(level, mgr, h, now);
        Campaigns.scheduleRaids(level, mgr, h, now);
        if (h.isPlayerOwned() && h.mode == Holding.Mode.ATTACK && h.attackTarget != null && now >= h.nextCampaign) {
            h.nextCampaign = now + 6000;
            Campaigns.launchParty(level, mgr, h, h.attackTarget);
        }
        if (!near(level, h)) {
            if (!h.live.isEmpty()) Garrisons.pruneLive(level, h.live);
            return;
        }
        BlockPos c = h.center();
        if (!level.isLoaded(c)) return;
        if (!h.scanned) {
            if (!PostScanner.allLoaded(level, h)) return;
            PostScanner.scan(level, h);
            mgr.setDirty();
        }
        if (h.bannersDirty) Garrisons.recolorBanners(level, mgr, h);
        else if (now % 200 == 0) Garrisons.placeStand(level, mgr, h);
        // The village search behind road planning is a /locate; only run it once the server has caught up.
        if (!h.surroundingsDone && PostScanner.allLoaded(level, h) && level.getServer().getAverageTickTime() < 45f)
            Roads.plan(level, mgr, h);
        Garrisons.materialize(level, mgr, h);
        if (!h.villagersSpawned) Garrisons.spawnVillagers(level, h);
        HoldingEvents.contest(level, mgr, h);
    }

    private HoldingTicker() {
    }
}
