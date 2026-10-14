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

import java.util.UUID;

/** Raids against player castles and war parties sent out by them. */
public final class Campaigns {
    private static final double MARCH_SPEED = 3.0; // blocks per second while unseen

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
        if (!h.raidWarned && now >= h.nextRaid - 24000) {
            h.raidWarned = true;
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Scouts report " + mgr.house(h.houseId).title()
                    + " gathering to retake " + h.name + ". They march within a day.").withStyle(ChatFormatting.RED));
            mgr.setDirty();
        }
        if (now >= h.nextRaid) {
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
        c.renown = h.renown;
        c.to = h.bannerPos;
        double ang = level.random.nextDouble() * Math.PI * 2;
        BlockPos center = h.center();
        int x = center.getX() + (int) (Math.cos(ang) * 110), z = center.getZ() + (int) (Math.sin(ang) * 110);
        c.from = new BlockPos(x, center.getY(), z);
        c.add(Role.CAPTAIN, 1);
        c.add(Role.KNIGHT, Math.max(4, (int) Math.ceil(h.count(Role.KNIGHT) * 0.8) + 2));
        c.add(Role.CROSSBOWMAN, Math.max(1, (int) Math.ceil(h.count(Role.CROSSBOWMAN) * 0.7)));
        c.add(Role.ARCHER, (int) Math.ceil(h.count(Role.ARCHER) * 0.4));
        mgr.campaigns.add(c);
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(mgr.house(h.houseId).title() + " marches on "
                + h.name + " with " + c.total() + " men!").withStyle(ChatFormatting.RED));
        alliedHelp(level, mgr, h);
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
    public static Campaign launchParty(ServerLevel level, HoldingManager mgr, Holding h, BlockPos target) {
        if (active(mgr, h.id, Campaign.Kind.PARTY)) return null;
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.PARTY;
        c.side = h.owner;
        c.origin = h.id;
        c.tier = h.tier;
        Holding enemy = mgr.at(target, 16);
        c.target = enemy != null && !enemy.owner.equals(h.owner) ? enemy.id : null;
        if (enemy != null && enemy.isHouseOwned() && c.target != null) {
            net.aiagent256.stormhold.house.Standing.change(level, mgr, enemy.houseId, h.ownerPlayer(),
                    net.aiagent256.stormhold.house.Standing.RAIDED_THEM, "you marched on " + enemy.name);
        }
        c.to = enemy != null ? enemy.bannerPos : target;
        c.from = h.gatePos.equals(BlockPos.ZERO) ? h.center() : h.gatePos;
        for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) {
            int keep = r == Role.KNIGHT ? 3 : 1;
            int send = Math.max(0, Math.min(h.count(r) - keep, (int) Math.ceil(h.count(r) * 0.6)));
            if (send > 0) {
                c.add(r, send);
                h.add(r, -send);
            }
        }
        if (h.count(Role.CAPTAIN) > 0) {
            c.add(Role.CAPTAIN, 1);
            h.add(Role.CAPTAIN, -1);
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
                g.setup(c.side, null, c.tier, g.color(), null, false);
                g.setCampaignId(c.id);
                g.setMarchTarget(c.to);
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

    // ------------------------------------------------------------ ticking (once a second)

    public static void tick(ServerLevel level, HoldingManager mgr, long now) {
        for (Campaign c : mgr.campaigns) {
            if (c.stage == Campaign.Stage.DONE) continue;
            Garrisons.pruneLive(level, c.live);
            if (c.kind == Campaign.Kind.RAID) tickRaid(level, mgr, c);
            else if (c.kind == Campaign.Kind.CAMP) tickCamp(level, mgr, c);
            else tickParty(level, mgr, c, now);
        }
        if (mgr.campaigns.removeIf(c -> c.stage == Campaign.Stage.DONE)) mgr.setDirty();
    }

    private static boolean playerNear(ServerLevel level, BlockPos p, int radius) {
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
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("The raid on " + h.name + " is broken!")
                    .withStyle(ChatFormatting.GREEN));
            c.stage = Campaign.Stage.DONE;
            return;
        }
        boolean near = playerNear(level, h.center(), 160);
        if (near) {
            if (c.live.size() < c.total()) {
                boolean first = c.live.isEmpty();
                materialize(level, mgr, c, c.to);
                if (first && !c.live.isEmpty()) {
                    level.playSound(null, h.center(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 16.0F, 0.9F);
                }
            }
            syncProgress(level, c);
            return;
        }
        if (!c.live.isEmpty()) return;
        if (c.progress < 1) {
            c.progress += MARCH_SPEED / c.length();
            return;
        }
        if (!Config.ABSTRACT_RAIDS.get()) return;
        // fought out while the lord is away
        double def = h.strength() * (h.mode == Holding.Mode.DEFEND ? 1.6 : 1.2) * (0.8 + level.random.nextDouble() * 0.4);
        double att = c.strength() * (0.8 + level.random.nextDouble() * 0.4);
        if (att > def) {
            HoldingEvents.transfer(level, mgr, h, c.side);
        } else {
            int losses = (int) Math.ceil(att / 4);
            for (Role r : new Role[]{Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) {
                int l = Math.min(losses, h.count(r));
                h.add(r, -l);
                losses -= l;
            }
            HoldingEvents.tell(level, h.ownerPlayer(), Component.literal("Your garrison at " + h.name
                    + " threw back a raid by " + mgr.house(h.houseId).title() + ". " + h.troops() + " soldiers remain.")
                    .withStyle(ChatFormatting.GREEN));
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
        } else if (c.live.isEmpty() && c.progress < 1) {
            c.progress += MARCH_SPEED / c.length();
        }
        if (c.stage == Campaign.Stage.MARCH && c.progress >= 0.98) {
            c.stage = Campaign.Stage.SIEGE;
            c.siegeUntil = now + 6000;
            for (UUID id : c.live.keySet()) {
                if (level.getEntity(id) instanceof GarrisonEntity g) {
                    g.setMarchTarget(null);
                    g.setPost(c.to);
                }
            }
            Holding enemy = c.target != null ? mgr.holdings.get(c.target) : null;
            if (enemy != null && c.live.isEmpty()) {
                double def = enemy.strength() * 1.3 * (0.8 + level.random.nextDouble() * 0.4);
                double att = c.strength() * (0.8 + level.random.nextDouble() * 0.4);
                if (att > def && !enemy.owner.equals(c.side)) {
                    enemy.lordAlive = false;
                    HoldingEvents.transfer(level, mgr, enemy, c.side);
                    c.stage = Campaign.Stage.DONE;
                    return;
                }
                c.counts.replaceAll((r, n) -> n / 3);
                HoldingEvents.tell(level, origin.ownerPlayer(), Component.literal("Your war party broke against the walls of "
                        + enemy.name + ". The survivors fall back.").withStyle(ChatFormatting.RED));
                c.siegeUntil = now;
            }
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
