package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Raids against player castles and war parties sent out by them. */
public final class Campaigns {
    private static final double MARCH_SPEED = 3.0; // blocks per second while unseen
    /** How near a player must come for a raid on a castle to be fought for real. */
    public static final int WATCH = 160;
    /** Once it's real, how far he must go before it turns back into numbers. */
    public static final int UNWATCH = 320;
    /** Seconds an attack waits before the walls for the lord, while he's online, before it's fought out without him. */
    public static final int WAIT = 300;

    // ------------------------------------------------------------ scheduling

    public static void scheduleRaids(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!Config.RAIDS_ENABLED.get() || !h.isPlayerOwned()) return;
        if (h.nextRaid <= 0) {
            h.nextRaid = now + HoldingEvents.raidDelay(level);
            mgr.setDirty();
            return;
        }
        if (active(mgr, h.id, Campaign.Kind.RAID)) return;
        if (net.aiagent256.stormhold.house.Standing.friendly(mgr, h.houseId, h.ownerPlayer())) {
            // a friendly house doesn't march on you; check again tomorrow
            if (now >= h.nextRaid) h.nextRaid = now + 24000;
            return;
        }
        boolean spy = Council.has(h.ownerPlayer(), Council.Seat.SPYMASTER);
        if (!h.raidWarned && now >= h.nextRaid - (spy ? 72000 : 24000)) {
            h.raidWarned = true;
            long days = Math.max(1, (h.nextRaid - now + 23999) / 24000);
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal((spy ? "Your Spymaster's people report " : "Scouts report ")
                    + mgr.house(h.houseId).title() + " gathering to retake " + h.name + ". They march within "
                    + (days <= 1 ? "a day." : days + " days.")).withStyle(ChatFormatting.RED));
            mgr.setDirty();
        }
        if (now >= h.nextRaid) {
            // one threat at a time: while another of the owner's castles is under attack, this one waits a day
            for (Campaign c : mgr.campaigns) {
                if (c.kind != Campaign.Kind.RAID || c.stage == Campaign.Stage.DONE || c.target == null) continue;
                Holding other = mgr.holdings.get(c.target);
                if (other != null && other != h && other.owner.equals(h.owner)) {
                    h.nextRaid = now + 24000;
                    mgr.setDirty();
                    return;
                }
            }
            launchRaid(level, mgr, h);
            h.nextRaid = now + HoldingEvents.raidDelay(level);
            h.raidWarned = false;
            mgr.setDirty();
        }
    }

    public static boolean active(HoldingManager mgr, UUID holding, Campaign.Kind kind) {
        for (Campaign c : mgr.campaigns) {
            if (c.kind == kind && c.stage != Campaign.Stage.DONE
                    && holding.equals(kind == Campaign.Kind.RAID ? c.target : c.origin)) return true;
        }
        return false;
    }

    public static Campaign launchRaid(ServerLevel level, HoldingManager mgr, Holding h) {
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.RAID;
        c.side = Sides.house(h.houseId);
        c.target = h.id;
        c.tier = h.tier;
        c.renown = Frontier.baseLevel(level, h);       // the house's men: not the castle's own forge
        c.to = h.bannerPos;
        double ang = level.random.nextDouble() * Math.PI * 2;
        BlockPos center = h.center();
        int x = center.getX() + (int) (Math.cos(ang) * 110), z = center.getZ() + (int) (Math.sin(ang) * 110);
        c.from = new BlockPos(x, center.getY(), z);
        c.add(Role.CAPTAIN, 1);
        c.add(Role.KNIGHT, Math.max(4, (int) Math.ceil(h.count(Role.KNIGHT) * 0.8) + 2));
        c.add(Role.CROSSBOWMAN, Math.max(1, (int) Math.ceil(h.count(Role.CROSSBOWMAN) * 0.7)));
        c.add(Role.ARCHER, (int) Math.ceil(h.count(Role.ARCHER) * 0.4));
        c.spoils = c.total();
        mgr.campaigns.add(c);
        h.lastRaidAt = level.getGameTime();
        String where = fromLord(level, h);
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(mgr.house(h.houseId).title() + " marches on "
                + h.name + " with " + c.total() + " men!" + (where.isEmpty() ? "" : " It lies " + where + ".")
                + " They reach the walls in about " + (int) Math.ceil(c.length() / MARCH_SPEED) + " seconds.").withStyle(ChatFormatting.RED));
        alliedHelp(level, mgr, h);
        Vassals.help(level, mgr, h);
        mgr.setDirty();
        return c;
    }

    /** Friendly houses with a castle nearby send a few knights to stand with you. */
    static void alliedHelp(ServerLevel level, HoldingManager mgr, Holding h) {
        java.util.UUID owner = h.ownerPlayer();
        if (owner == null) return;
        java.util.Set<String> helped = new java.util.HashSet<>();
        for (Holding other : mgr.holdings.values()) {
            if (!other.isHouseOwned() || other.houseId.equals(h.houseId) || helped.contains(other.houseId)) continue;
            if (!net.aiagent256.stormhold.house.Standing.friendly(mgr, other.houseId, owner)) continue;
            if (other.center().distSqr(h.center()) > 1500.0 * 1500.0) continue;
            helped.add(other.houseId);
            int n = 2 + h.tier;
            BlockPos at = h.rallyPos.equals(BlockPos.ZERO) ? h.center() : h.rallyPos;
            if (level.isLoaded(at)) Garrisons.reinforce(level, mgr, h, Role.KNIGHT, n, at, null);
            else h.add(Role.KNIGHT, n);
            HoldingEvents.tell(level, owner, HoldingEvents.gold(mgr.house(other.houseId).title() + " sends " + n
                    + " knights from " + other.name + " to hold " + h.name + " with you."));
        }
    }

    @Nullable
    /** How many men of a kind a war party takes from {@code h}: six in ten, leaving three knights and one of the rest; one captain. */
    public static int sends(Holding h, Role r) {
        if (r == Role.CAPTAIN) return h.count(Role.CAPTAIN) > 0 ? 1 : 0;
        int keep = r == Role.KNIGHT ? 3 : 1;
        return Math.max(0, Math.min(h.count(r) - keep, (int) Math.ceil(h.count(r) * 0.6)));
    }

    /** A side's fighting worth in a battle fought out of sight: its numbers by its kit. */
    static double kit(int level) {
        return Readiness.factor(Readiness.tier(level));
    }

    /** The chance a war party from {@code from}, sent alone, takes {@code enemy}: in words, with the odds. */
    public static String odds(ServerLevel level, Holding from, Holding enemy) {
        double att = 0;
        for (Role r : new Role[]{Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) att += sends(from, r) * r.strength();
        att *= kit(Frontier.level(level, from));
        double def = enemy.strength() * 1.3 * kit(Frontier.level(level, enemy));
        if (att <= 0) return "no one to send";
        // each side's luck on the day is 0.8 to 1.2 of its worth
        int win = 0, n = 40;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double a = att * (0.8 + 0.4 * (i + 0.5) / n), d = def * (0.8 + 0.4 * (j + 0.5) / n);
            if (a > d) win++;
        }
        int pct = (int) Math.round(100.0 * win / (n * n));
        return pct >= 95 ? "they'll take it" : pct >= 60 ? "likely to take it (" + pct + "%)" : pct >= 30 ? "a gamble (" + pct + "%)"
                : pct > 0 ? "likely to break (" + pct + "%)" : "they'll break against the walls";
    }

    /**
     * What "All castles march" would come to against this castle: every castle the lord holds (vassals aside) sends
     * its war party, they go in together, and nobody watches. The men, the defenders and the chance, for the Realm map.
     */
    public static String allOdds(ServerLevel level, HoldingManager mgr, UUID lord, Holding enemy) {
        double att = 0;
        int men = 0, parties = 0;
        for (Holding h : mgr.ownedBy(lord)) {
            if (Vassals.is(h)) continue;
            int party = 0;
            double s = 0;
            for (Role r : new Role[]{Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) {
                party += sends(h, r);
                s += sends(h, r) * r.strength();
            }
            if (party < 3) continue;
            men += party;
            parties++;
            att += s * kit(Frontier.level(level, h));
        }
        int theirs = HoldingEvents.defendersInAll(enemy);
        String them = "About " + theirs + " defenders in " + Readiness.kitName(Readiness.tier(Frontier.level(level, enemy))) + ".";
        if (parties == 0) return them + " None of your castles has men to spare for a war party.";
        double def = enemy.strength() * 1.3 * kit(Frontier.level(level, enemy));
        int win = 0, n = 40;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double a = att * (0.8 + 0.4 * (i + 0.5) / n), d = def * (0.8 + 0.4 * (j + 0.5) / n);
            if (a > d) win++;
        }
        int pct = (int) Math.round(100.0 * win / (n * n));
        String verdict = pct >= 95 ? "they'll take it" : pct >= 60 ? "likely to take it" : pct >= 30 ? "a gamble" : pct > 0 ? "likely to break" : "they'd break against the walls";
        return them + " All your castles marching (" + parties + (parties == 1 ? " war party, " : " war parties, ") + men + " men): "
                + verdict + " (" + pct + "%).";
    }

    /** Two war parties of one lord against one castle. */
    static boolean together(Campaign o, Campaign c) {
        return o.kind == Campaign.Kind.PARTY && c.target != null && o.side.equals(c.side) && c.target.equals(o.target);
    }

    /** Another party of ours still on the road to the castle this one stands before. */
    static boolean othersMarching(HoldingManager mgr, Campaign c) {
        for (Campaign o : mgr.campaigns) if (o != c && together(o, c) && o.stage == Campaign.Stage.MARCH) return true;
        return false;
    }

    /**
     * Every party of ours before these walls, nobody watching, goes in as one army: their strength summed, each in
     * its own kit, against the garrison behind its walls. A win takes the castle (and sends them all home inside
     * it); a loss leaves each a third of its men to fall back. True if the castle fell.
     */
    static boolean assault(ServerLevel level, HoldingManager mgr, Campaign c, Holding enemy, long now) {
        List<Campaign> group = new java.util.ArrayList<>();
        for (Campaign o : mgr.campaigns) if (together(o, c) && o.stage == Campaign.Stage.SIEGE && o.live.isEmpty()) group.add(o);
        if (enemy.owner.equals(c.side)) {
            for (Campaign o : group) o.siegeUntil = now;          // already ours: home
            return false;
        }
        List<String> from = new java.util.ArrayList<>();
        double att = 0;
        for (Campaign o : group) {
            att += o.strength() * kit(o.renown);
            Holding oh = o.origin != null ? mgr.holdings.get(o.origin) : null;
            if (oh != null) from.add(oh.name);
        }
        att *= 0.8 + level.random.nextDouble() * 0.4;
        double def = enemy.strength() * 1.3 * (0.8 + level.random.nextDouble() * 0.4) * kit(Frontier.level(level, enemy));
        Holding origin = c.origin != null ? mgr.holdings.get(c.origin) : null;
        java.util.UUID lord = origin != null ? origin.ownerPlayer() : null;
        if (att > def) {
            if (group.size() > 1)
                HoldingEvents.tell(level, lord, HoldingEvents.gold("Your war parties from " + String.join(", ", from) + " storm " + enemy.name + " together."));
            enemy.lordAlive = false;
            HoldingEvents.transfer(level, mgr, enemy, c.side);
            for (Campaign o : group) o.stage = Campaign.Stage.DONE;
            return true;
        }
        for (Campaign o : group) {
            o.counts.replaceAll((r, n) -> n / 3);
            o.siegeUntil = now;
        }
        HoldingEvents.tell(level, lord, Component.literal((group.size() > 1 ? "Your war parties from " + String.join(", ", from) + " broke"
                : "Your war party broke") + " against the walls of " + enemy.name + ". The survivors fall back.").withStyle(ChatFormatting.RED));
        return false;
    }

    public static Campaign launchParty(ServerLevel level, HoldingManager mgr, Holding h, BlockPos target) {
        if (active(mgr, h.id, Campaign.Kind.PARTY)) return null;
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.PARTY;
        c.side = h.owner;
        c.origin = h.id;
        c.tier = h.tier;
        c.renown = Frontier.level(level, h);           // the men ride out in the kit their castle arms them with
        Holding enemy = mgr.atXZ(target, 16);
        c.target = enemy != null && !enemy.owner.equals(h.owner) ? enemy.id : null;
        if (enemy != null && enemy.isHouseOwned() && c.target != null) {
            net.aiagent256.stormhold.house.Standing.change(level, mgr, enemy.houseId, h.ownerPlayer(),
                    net.aiagent256.stormhold.house.Standing.RAIDED_THEM, "you marched on " + enemy.name);
        }
        c.to = enemy != null ? enemy.bannerPos : target;
        c.from = gateOutside(h);
        BlockPos via = gateInside(h), via2 = gateOutside(h);
        for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN, Role.CAPTAIN}) {
            int send = sends(h, r);
            if (send > 0) {
                c.add(r, send);
                h.add(r, -send);
            }
        }
        if (c.total() < 3) {
            for (var e : c.counts.entrySet()) h.add(e.getKey(), e.getValue());
            return null;
        }
        // soldiers already standing in the castle leave with the party
        int toRemove = c.total();
        for (UUID id : new java.util.ArrayList<>(h.live.keySet())) {
            if (toRemove <= 0) break;
            Role r = h.live.get(id);
            if (r == Role.LORD) continue;
            Entity e = level.getEntity(id);
            if (e instanceof GarrisonEntity g && h.count(r) < Garrisons.liveCount(h.live, r)) {
                h.live.remove(id);
                if (g.post() != null) h.usedPosts.remove(g.post());
                g.setup(c.side, null, c.tier, g.renown(), g.color(), null, false);
                g.setCampaignId(c.id);
                g.setMarchVia(via);
                g.setMarchVia2(via2);
                g.setMarchTarget(c.to);
                g.setTarget(null);
                c.live.put(id, r);
                toRemove--;
            }
        }
        mgr.campaigns.add(c);
        HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold("A war party of " + c.total() + " rides out from "
                + h.name + (enemy != null ? " against " + enemy.name : " toward " + target.getX() + ", " + target.getZ()) + "."));
        mgr.setDirty();
        return c;
    }

    /** A spot a few blocks outside a castle's gate, where marching men file in and out. */
    static BlockPos gateOutside(Holding h) {
        if (h.gatePos.equals(BlockPos.ZERO)) return h.center();
        BlockPos c = h.center();
        double dx = h.gatePos.getX() - c.getX(), dz = h.gatePos.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return h.gatePos;
        return h.gatePos.offset((int) Math.round(dx / len * 7), 0, (int) Math.round(dz / len * 7));
    }

    /** A spot in the yard just inside the gatehouse, where men gather before the portcullis is raised for them. */
    static BlockPos gateInside(Holding h) {
        if (h.gatePos.equals(BlockPos.ZERO)) return h.center();
        BlockPos c = h.center();
        double dx = c.getX() - h.gatePos.getX(), dz = c.getZ() - h.gatePos.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return h.gatePos;
        int step = (int) Math.min(18, len * 0.5);
        return h.gatePos.offset((int) Math.round(dx / len * step), 0, (int) Math.round(dz / len * step));
    }

    /** Where a campaign's men should pass on the way to {@code dest}: the gate of the castle they're entering. */
    @Nullable
    private static BlockPos viaFor(HoldingManager mgr, Campaign c, BlockPos dest) {
        Holding h = c.stage == Campaign.Stage.RETURN ? (c.origin != null ? mgr.holdings.get(c.origin) : null)
                : (c.target != null ? mgr.holdings.get(c.target) : null);
        if (h == null || !h.box.inflatedBy(4).isInside(dest)) return null;
        return gateOutside(h);
    }

    // ------------------------------------------------------------ ticking (once a second)

    public static void tick(ServerLevel level, HoldingManager mgr, long now) {
        for (Campaign c : mgr.campaigns) {
            if (c.stage == Campaign.Stage.DONE) continue;
            Garrisons.pruneMarching(level, c.live);
            if (c.kind == Campaign.Kind.RAID) tickRaid(level, mgr, c);
            else if (c.kind == Campaign.Kind.CAMP) tickCamp(level, mgr, c);
            else if (c.kind == Campaign.Kind.COLUMN) Columns.tick(level, mgr, c);
            else if (c.kind == Campaign.Kind.ILLAGERS) Warbands.tick(level, mgr, c);
            else if (c.kind == Campaign.Kind.SCOUT) Scouts.tick(level, mgr, c);
            else tickParty(level, mgr, c, now);
        }
        for (Campaign c : mgr.campaigns) if (c.stage == Campaign.Stage.DONE) clearBar(c);
        if (mgr.campaigns.removeIf(c -> c.stage == Campaign.Stage.DONE)) mgr.setDirty();
    }

    /** Tick one campaign on its own (tests). */
    public static void tickOne(ServerLevel level, HoldingManager mgr, Campaign c) {
        if (c.stage == Campaign.Stage.DONE) return;
        Garrisons.pruneMarching(level, c.live);
        if (c.kind == Campaign.Kind.RAID) tickRaid(level, mgr, c);
        else if (c.kind == Campaign.Kind.ILLAGERS) Warbands.tick(level, mgr, c);
    }

    /** Where a place lies from its lord, if he's online in this world: "412 blocks south-west of you", else "". */
    static String fromLord(ServerLevel level, Holding h) {
        UUID p = h.ownerPlayer();
        ServerPlayer sp = p == null ? null : level.getServer().getPlayerList().getPlayer(p);
        if (sp == null || sp.level() != level) return "";
        String w = Guide.way(sp.blockPosition(), h.center());
        return w.equals("right here") ? "where you stand" : w + " of you";
    }

    static boolean lordOnline(ServerLevel level, Holding h) {
        UUID p = h.ownerPlayer();
        return p != null && level.getServer().getPlayerList().getPlayer(p) != null;
    }

    /**
     * An attack has reached the walls with nobody there. While the lord is online it waits {@link #WAIT} seconds
     * for him to come (a word at the start and a minute before the end); then, or at once if he's away, the garrison
     * fights it out. True while it's still waiting.
     */
    static boolean waitForLord(ServerLevel level, Holding h, Campaign c, String who) {
        if (!lordOnline(level, h) || c.waitSeconds >= WAIT) return false;
        if (c.waitSeconds == 0) {
            String where = fromLord(level, h);
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(who + " stand before the walls of " + h.name
                    + (where.isEmpty() ? "" : ", " + where) + ". The garrison holds them " + WAIT / 60 + " minutes: come within " + WATCH
                    + " blocks to fight it yourself, or it's fought out without you.").withStyle(ChatFormatting.RED));
        } else if (c.waitSeconds == WAIT - 60) {
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("A minute before " + h.name + " fights its attackers out without you.")
                    .withStyle(ChatFormatting.RED));
        }
        c.waitSeconds++;
        return true;
    }

    /** The bar over a fight, for every player within reach of the castle: how many attackers are left. */
    static void bar(ServerLevel level, Holding h, Campaign c, String title) {
        if (c.bar == null) {
            c.barMax = Math.max(1, c.total());
            c.bar = new net.minecraft.server.level.ServerBossEvent(Component.literal(title),
                    net.minecraft.world.BossEvent.BossBarColor.RED, net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10);
        }
        c.bar.setName(Component.literal(title + ": " + c.total() + " of " + c.barMax + " left"));
        c.bar.setProgress(Math.max(0f, Math.min(1f, c.total() / (float) c.barMax)));
        BlockPos at = h.center();
        for (ServerPlayer sp : level.players()) {
            boolean in = sp.blockPosition().distSqr(new BlockPos(at.getX(), sp.getBlockY(), at.getZ())) < (double) UNWATCH * UNWATCH;
            if (in) c.bar.addPlayer(sp);
            else c.bar.removePlayer(sp);
        }
    }

    static void clearBar(Campaign c) {
        if (c.bar != null) {
            c.bar.removeAllPlayers();
            c.bar = null;
        }
    }

    static boolean playerNear(ServerLevel level, BlockPos p, int radius) {
        for (ServerPlayer sp : level.players()) {
            if (!sp.isSpectator() && sp.blockPosition().distSqr(new BlockPos(p.getX(), sp.getBlockY(), p.getZ())) < (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    /** Spawn the missing members of a campaign at its current position, if that ground is loaded. */
    private static void materialize(ServerLevel level, HoldingManager mgr, Campaign c, BlockPos marchTo) {
        BlockPos at = c.position();
        double dx = c.to.getX() - at.getX(), dz = c.to.getZ() - at.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        for (int k = 0; k < 8 && !level.isLoaded(at); k++) {
            at = at.offset((int) (dx / len * 16), 0, (int) (dz / len * 16));
        }
        if (!level.isLoaded(at)) return;
        BlockPos via = marchTo != null ? viaFor(mgr, c, marchTo) : null;
        // already past the gate: don't send them back out to it
        if (via != null && at.distSqr(marchTo) < via.distSqr(marchTo)) via = null;
        int budget = 6;
        for (var e : c.counts.entrySet()) {
            Role r = e.getKey();
            int have = Garrisons.liveCount(c.live, r);
            while (have < e.getValue() && budget > 0) {
                int x = at.getX() + level.random.nextInt(9) - 4, z = at.getZ() + level.random.nextInt(9) - 4;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                GarrisonEntity g = Garrisons.spawn(level, mgr, null, r, p, c.side, false);
                if (g == null) break;
                if (c.renown != 2) {
                    g.setup(c.side, null, c.tier, c.renown, g.color(), null, false);
                    net.aiagent256.stormhold.entity.Loadouts.equip(g, r, c.tier, c.renown, g.color(), mgr.houseOf(c.side));
                }
                g.setCampaignId(c.id);
                g.setMarchVia(via);
                g.setMarchTarget(marchTo);
                g.setPost(null);
                c.live.put(g.getUUID(), r);
                have++;
                budget--;
            }
        }
    }

    private static void syncProgress(ServerLevel level, Campaign c) {
        double sx = 0, sz = 0;
        int n = 0;
        for (UUID id : c.live.keySet()) {
            Entity e = level.getEntity(id);
            if (e != null) {
                sx += e.getX();
                sz += e.getZ();
                n++;
            }
        }
        if (n == 0) return;
        double ax = sx / n - c.from.getX(), az = sz / n - c.from.getZ();
        double ux = (c.to.getX() - c.from.getX()) / c.length(), uz = (c.to.getZ() - c.from.getZ()) / c.length();
        c.progress = Math.max(c.progress, Math.min(1, (ax * ux + az * uz) / c.length()));
    }

    private static void tickRaid(ServerLevel level, HoldingManager mgr, Campaign c) {
        Holding h = c.target != null ? mgr.holdings.get(c.target) : null;
        if (h == null || h.owner.equals(c.side)) {
            Garrisons.discardLive(level, c.live, false);
            c.stage = Campaign.Stage.DONE;
            return;
        }
        if (c.total() <= 0) {
            int spoils = Rewards.raidSpoils(c, true);
            Vassals.favour(level, mgr, h, 10, "you fought for " + h.name);
            boolean pack = Rewards.pay(level, mgr, h.ownerPlayer(), spoils, h) > 0;
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The raid on " + h.name + " is broken! The spoils of the field: "
                    + spoils + " emeralds" + (pack ? "." : ", to its stores.")).withStyle(ChatFormatting.GREEN));
            Chronicle.log(level, mgr, Chronicle.WAR, HoldingEvents.ownerName(level, mgr, c.side) + "'s attack on " + h.name
                    + " is broken at the walls.", h.ownerPlayer());
            c.stage = Campaign.Stage.DONE;
            return;
        }
        boolean near = playerNear(level, h.center(), c.engaged ? UNWATCH : WATCH);
        if (near) {
            c.engaged = true;
            bar(level, h, c, "Raid on " + h.name);
            if (c.live.size() < c.total()) {
                boolean first = c.live.isEmpty();
                materialize(level, mgr, c, c.to);
                if (first && !c.live.isEmpty()) {
                    level.playSound(null, h.center(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 16.0F, 0.9F);
                }
            }
            syncProgress(level, c);
            // held at the gate: after a while they bring the ram up and the portcullis comes down
            if (!c.live.isEmpty() && !c.rammed) {
                if (c.progress > c.lastProgress + 1e-4) {
                    c.lastProgress = c.progress;
                    c.stallSeconds = 0;
                } else if (++c.stallSeconds > 60 && c.progress <= 0.6) {
                    // stuck on the road (a cliff, water, a wall in the way): they come on further along
                    Garrisons.discardLive(level, c.live, true);
                    c.progress = Math.min(0.6, c.progress + 12 / c.length());
                    c.lastProgress = c.progress;
                    c.stallSeconds = 0;
                } else if (c.stallSeconds > 45 && c.progress > 0.6) {
                    c.rammed = true;
                    if (Gates.breach(level, h)) {
                        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The gate of " + h.name + " is broken! They're inside the walls.")
                                .withStyle(ChatFormatting.RED));
                    }
                }
            }
            return;
        }
        c.engaged = false;
        clearBar(c);
        if (!c.live.isEmpty()) return;
        if (c.progress < 1) {
            c.progress += MARCH_SPEED / c.length();
            return;
        }
        if (waitForLord(level, h, c, HoldingEvents.ownerName(level, mgr, c.side) + "'s " + c.total() + " men")) return;
        if (!Config.ABSTRACT_RAIDS.get()) return;
        // fought out while the lord is away
        double def = h.strength() * (h.mode == Holding.Mode.DEFEND ? 1.6 : 1.2) * (0.8 + level.random.nextDouble() * 0.4)
                * (Council.has(h.ownerPlayer(), Council.Seat.MARSHAL) ? 1.2 : 1.0) * kit(Frontier.level(level, h));
        double att = c.strength() * (0.8 + level.random.nextDouble() * 0.4) * kit(c.renown);
        if (att > def) {
            HoldingEvents.transfer(level, mgr, h, c.side);
        } else {
            int losses = (int) Math.ceil(att / 4);
            for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) {
                int l = Math.min(losses, h.count(r));
                h.add(r, -l);
                losses -= l;
            }
            int spoils = Rewards.raidSpoils(c, false);
            h.addStock(Rewards.EMERALD, spoils);
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Your garrison at " + h.name
                    + " threw back a raid by " + HoldingEvents.ownerName(level, mgr, c.side) + ". " + h.troops() + " soldiers remain, and "
                    + spoils + " emeralds of spoils go to the stores.").withStyle(ChatFormatting.GREEN));
            Chronicle.log(level, mgr, Chronicle.WAR, h.name + " threw back " + HoldingEvents.ownerName(level, mgr, c.side)
                    + "; " + h.troops() + " of the garrison remain.", h.ownerPlayer());
        }
        c.stage = Campaign.Stage.DONE;
        mgr.setDirty();
    }

    /** A band of brigands sitting on a spot until someone kills them. */
    private static void tickCamp(ServerLevel level, HoldingManager mgr, Campaign c) {
        if (c.total() <= 0) {
            City.campCleared(level, mgr, c);
            c.stage = Campaign.Stage.DONE;
            return;
        }
        if (playerNear(level, c.to, 110)) {
            if (c.live.size() < c.total()) {
                boolean first = c.live.isEmpty();
                materialize(level, mgr, c, null);
                for (UUID id : c.live.keySet()) {
                    if (level.getEntity(id) instanceof GarrisonEntity g && g.post() == null) g.setPost(c.to);
                }
                if (first && !c.live.isEmpty() && level.isLoaded(c.to)) campSite(level, c.to);
            }
        }
    }

    /** Tents, a fire and a loot chest where the brigands sit. */
    private static void campSite(ServerLevel level, BlockPos at) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos c = new BlockPos(at.getX(), y, at.getZ());
        if (!level.getBlockState(c).isAir()) return;
        level.setBlock(c, net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState(), 3);
        for (int k = 0; k < 3; k++) {
            double a = k * Math.PI * 2 / 3;
            int tx = c.getX() + (int) Math.round(Math.cos(a) * 5), tz = c.getZ() + (int) Math.round(Math.sin(a) * 5);
            int ty = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(tx + dx, ty + (Math.abs(dx) + Math.abs(dz) == 0 ? 2 : 1), tz + dz);
                    if (level.getBlockState(p).isAir())
                        level.setBlock(p, net.minecraft.world.level.block.Blocks.BROWN_WOOL.defaultBlockState(), 3);
                }
            }
            BlockPos pole = new BlockPos(tx, ty, tz);
            if (level.getBlockState(pole).isAir()) level.setBlock(pole, net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);
        }
        BlockPos chest = c.east(2);
        if (level.getBlockState(chest).isAir()) {
            level.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity cbe)
                cbe.setLootTable(new net.minecraft.resources.ResourceLocation("stormhold", "chests/castle_barracks"), level.random.nextLong());
        }
    }

    private static void tickParty(ServerLevel level, HoldingManager mgr, Campaign c, long now) {
        Holding origin = c.origin != null ? mgr.holdings.get(c.origin) : null;
        if (origin == null || !origin.owner.equals(c.side)) {
            Garrisons.discardLive(level, c.live, false);
            c.stage = Campaign.Stage.DONE;
            return;
        }
        if (c.total() <= 0) {
            HoldingEvents.tell(level, origin.ownerPlayer(), Component.literal("The war party from " + origin.name
                    + " was cut down to the last man.").withStyle(ChatFormatting.RED));
            c.stage = Campaign.Stage.DONE;
            return;
        }
        BlockPos dest = c.stage == Campaign.Stage.RETURN ? c.from : c.to;
        boolean near = playerNear(level, c.position(), 128) || playerNear(level, dest, 96);
        if (near) {
            if (c.live.size() < c.total()) materialize(level, mgr, c, dest);
            syncProgress(level, c);
            // a watched party that can't find its way (walls, water, a cliff) stops being a set of stuck
            // entities and marches on as a count; it shows up again further down the road
            if (c.stage != Campaign.Stage.SIEGE && !c.live.isEmpty()) {
                if (c.progress > c.lastProgress + 1e-4) {
                    c.lastProgress = c.progress;
                    c.stallSeconds = 0;
                } else if (++c.stallSeconds > 60) {
                    c.stallSeconds = 0;
                    Garrisons.discardLive(level, c.live, true);
                    c.progress = Math.min(1, c.progress + 12 / c.length());
                    c.lastProgress = c.progress;
                }
            }
        } else if (c.live.isEmpty() && c.progress < 1) {
            c.progress += MARCH_SPEED / c.length();
        }
        Holding enemy = c.target != null ? mgr.holdings.get(c.target) : null;
        if (c.stage == Campaign.Stage.MARCH && c.progress >= 0.98) {
            c.stage = Campaign.Stage.SIEGE;
            c.siegeUntil = now + 6000;
            // the first of ours at these walls sets the hour: those who come after go in with them
            for (Campaign o : mgr.campaigns)
                if (o != c && together(o, c) && o.stage == Campaign.Stage.SIEGE) c.siegeUntil = Math.min(c.siegeUntil, o.siegeUntil);
            for (UUID id : c.live.keySet()) {
                if (level.getEntity(id) instanceof GarrisonEntity g) {
                    g.setMarchTarget(null);
                    g.setPost(c.to);
                }
            }
            if (enemy != null && c.live.isEmpty() && othersMarching(mgr, c))
                HoldingEvents.tell(level, origin.ownerPlayer(), Component.literal("The war party from " + origin.name + " stands before the walls of "
                        + enemy.name + " and waits for the others before it goes in (" + Math.max(1, (c.siegeUntil - now) / 1200) + " minutes at most).")
                        .withStyle(ChatFormatting.GOLD));
        }
        // fought out while nobody watches: every party of ours at these walls goes in together, once the last
        // of them is there or the first has waited its five minutes
        if (c.stage == Campaign.Stage.SIEGE && c.live.isEmpty() && enemy != null && (now >= c.siegeUntil || !othersMarching(mgr, c))) {
            if (assault(level, mgr, c, enemy, now)) return;
        }
        if (c.stage == Campaign.Stage.SIEGE && now >= c.siegeUntil) {
            c.stage = Campaign.Stage.RETURN;
            BlockPos a = c.to;
            c.to = c.from;
            c.from = a;
            c.progress = 0;
            for (UUID id : c.live.keySet()) {
                if (level.getEntity(id) instanceof GarrisonEntity g) {
                    g.setPost(null);
                    g.setMarchTarget(c.to);
                }
            }
        }
        if (c.stage == Campaign.Stage.RETURN && c.progress >= 0.97) {
            for (var e : c.counts.entrySet()) origin.add(e.getKey(), e.getValue());
            Garrisons.discardLive(level, c.live, false);
            HoldingEvents.tell(level, origin.ownerPlayer(), HoldingEvents.gold("The war party returns to " + origin.name + " with "
                    + c.total() + " men."));
            c.stage = Campaign.Stage.DONE;
            mgr.setDirty();
        }
    }

    private Campaigns() {
    }
}
