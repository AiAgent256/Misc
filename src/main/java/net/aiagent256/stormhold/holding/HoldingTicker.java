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
        Chart.drawTick(level, mgr);
        Engines.tickStones(level);
        if (t % 10 == 5) {
            for (Holding h : mgr.holdings.values()) {
                if (!level.isLoaded(h.center())) continue;
                if (near(level, h)) {
                    Gates.tick(level, mgr, h);
                    Work.tick(level, mgr, h, t);
                } else {
                    Gates.idle(level, h);
                }
            }
        }
        if (t % 20 != 0) return;
        Orders.tick(level);
        Engines.tick(level, mgr);
        Rams.tick(level, mgr);
        for (Holding h : mgr.holdings.values()) tickHolding(level, mgr, h, t);
        if (t - mgr.lastStandingDrift >= 24000) {
            mgr.lastStandingDrift = t;
            net.aiagent256.stormhold.house.Standing.drift(mgr);
        }
        Campaigns.tick(level, mgr, t);
        Tourney.tick(level, mgr);
        Truces.tick(level, mgr);
        Economy.tickShipments(level, mgr);
        net.aiagent256.stormhold.house.Politics.tick(level, mgr, t);
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
        Warbands.schedule(level, mgr, h, now);
        Columns.tickSupply(level, mgr, h, now);
        City.tickPostings(level, mgr, h, now);
        Chart.tick(level, mgr, h, now);
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
        if (!h.cityBlocksPlaced) City.placeBlocks(level, h);
        // The village search behind road planning is a /locate; only run it once the server has caught up.
        if (!h.surroundingsDone && PostScanner.allLoaded(level, h) && level.getServer().getAverageTickTime() < 45f)
            Roads.plan(level, mgr, h);
        Garrisons.materialize(level, mgr, h);
        if (h.surroundingsDone && Config.FIEFS_ENABLED.get()) {
            if (h.fiefPlan == null) Fief.plan(level, h);
            if (!h.fiefPlan.isEmpty() && now % 40 == 0) Fief.buildNext(level, mgr, h);
            if (now % 40 == 20) Town.tick(level, mgr, h);
            if (now % 1200 == 600) Labour.needMine(level, h);
        }
        Builder.tick(level, mgr, h);
        Produce.tick(level, mgr, h, now);
        if (now % 60 == 20) Dragons.watch(level, mgr, h, now);
        if (now % 200 == 0) Builder.ensureBuilder(level, h);
        if (now % 200 == 140 && h.scanned && h.waystone == null && net.aiagent256.stormhold.compat.WaystonesCompat.ensure(level, h)) mgr.setDirty();
        greet(level, mgr, h);
        if (!h.villagersSpawned) Garrisons.spawnVillagers(level, h);
        if (now % 200 == 100) Labour.tick(level, mgr, h);
        if (now % 200 == 160) {
            Steward.tick(level, mgr, h);
            Scouts.hangPending(level, mgr, h);
            Spoils.unload(level, mgr, h);
        }
        if (now % 600 == 460 && h.isPlayerOwned()) Spoils.gather(level, mgr, h, 16, false);
        if (now % 600 == 500 && h.tidy) Tidy.tick(level, mgr, h);
        if (now % 100 == 40) {
            Market.tick(level, mgr, h);
            Shops.tick(level, mgr, h);
        }
        HoldingEvents.contest(level, mgr, h);
    }

    /** Word of a castle's wealth, once, as you come within sight of it. */
    private static void greet(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.name.isEmpty()) return;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || h.greeted.contains(p.getUUID())) continue;
            if (p.blockPosition().distSqr(h.center()) > 140.0 * 140.0) continue;
            h.greeted.add(p.getUUID());
            String owner = HoldingEvents.ownerName(level, mgr, h.owner);
            String wealth = h.renown >= 5 ? "Its treasury is the stuff of songs." : h.renown == 4 ? "Its armoury is famed."
                    : h.renown == 3 ? "It does well for itself." : h.renown == 2 ? "A modest place." : "A poor, hard place.";
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(h.name + ", " + Holding.renownName(h.renown) + " "
                    + h.kindName().toLowerCase() + " of " + owner + ". " + wealth).withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            // and what's here and which way: nothing in a castle is signposted
            for (var line : Guide.lines(level, mgr, h, p)) p.sendSystemMessage(line);
        }
    }

    private HoldingTicker() {
    }
}
