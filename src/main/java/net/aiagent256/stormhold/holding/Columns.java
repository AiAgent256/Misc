package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Men sent from a castle to wherever the player is, on the clock rather than on foot. A column leaves the
 * garrison at once, rides at {@link #SPEED} blocks a second, and when the timer runs out the men appear at
 * the player's side and join the company. On the way, a castle of a house that hates the player, close to
 * the road, takes a bite out of it. A supply line is a standing order: keep N men with me, send a column
 * whenever the company falls short.
 */
public final class Columns {
    /** Blocks per second. Mounted men on a road. */
    public static final double SPEED = 5.0;
    /** A hostile castle this close to the column's route falls on it. */
    public static final int AMBUSH_RANGE = 100;
    /** How often a supply line can send a column. */
    public static final long SUPPLY_INTERVAL = 6000;
    /** How far from the rendezvous the player may be for the column to find them on arrival. */
    private static final int FIND_RANGE = 200;

    private static final Set<String> AMBUSHED = new HashSet<>();   // campaign id + holding id, this session

    /** Send up to the asked numbers from {@code h} to the player. Returns the column, or null if nothing could go. */
    @Nullable
    public static Campaign send(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding h, EnumMap<Role, Integer> want) {
        return send(level, mgr, player, h, want, false);
    }

    /** The same; {@code quiet} for one of several sent at once (the banners), which say so together. */
    @Nullable
    public static Campaign send(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding h, EnumMap<Role, Integer> want, boolean quiet) {
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.COLUMN;
        c.side = h.owner;
        c.origin = h.id;
        c.player = player.getUUID();
        c.tier = h.tier;
        c.renown = Frontier.level(level, h);
        c.from = Campaigns.gateOutside(h);
        c.to = player.blockPosition();
        for (var e : want.entrySet()) {
            int n = Math.min(e.getValue(), Math.max(0, h.count(e.getKey()) - 1));
            if (n > 0) {
                c.add(e.getKey(), n);
                h.add(e.getKey(), -n);
            }
        }
        if (c.total() <= 0) {
            if (!quiet) player.sendSystemMessage(Component.literal(h.name + " has no one to spare.").withStyle(ChatFormatting.RED));
            return null;
        }
        // men standing in the castle leave with it
        int toRemove = c.total();
        for (UUID id : new java.util.ArrayList<>(h.live.keySet())) {
            if (toRemove <= 0) break;
            Role r = h.live.get(id);
            if (r == Role.LORD || h.count(r) >= Garrisons.liveCount(h.live, r)) continue;
            Entity e = level.getEntity(id);
            h.live.remove(id);
            if (e instanceof GarrisonEntity g) {
                if (g.post() != null) h.usedPosts.remove(g.post());
                g.discard();
            }
            toRemove--;
        }
        mgr.campaigns.add(c);
        mgr.setDirty();
        if (quiet) return c;
        level.playSound(null, player.blockPosition(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), SoundSource.PLAYERS, 0.8F, 0.9F);
        player.sendSystemMessage(HoldingEvents.gold("A column of " + c.total() + " rides out from " + h.name + ". "
                + eta(c) + " to reach you."));
        return c;
    }

    /** Men riding home from the player's side to their castle: {@code target} is the castle, no origin. */
    public static Campaign home(HoldingManager mgr, Player player, Holding home, BlockPos at, EnumMap<Role, Integer> men) {
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.COLUMN;
        c.side = home.owner;
        c.target = home.id;
        c.player = player.getUUID();
        c.tier = home.tier;
        c.from = at;
        c.to = Campaigns.gateOutside(home);
        men.forEach(c::add);
        mgr.campaigns.add(c);
        mgr.setDirty();
        return c;
    }

    /** Once a second for a column riding home: on the clock, then into the garrison. */
    private static void tickHome(ServerLevel level, HoldingManager mgr, Campaign c) {
        Holding home = mgr.holdings.get(c.target);
        ServerPlayer player = c.player != null ? level.getServer().getPlayerList().getPlayer(c.player) : null;
        if (home == null || !home.owner.equals(c.side)) {
            // home's lost on the way: the seat, else any castle still his, else they're gone
            Holding other = c.player == null ? null : mgr.seatOf(c.player);
            if (other == null && c.player != null)
                for (Holding h : mgr.ownedBy(c.player)) if (!Vassals.is(h)) other = h;
            if (other == null) {
                c.stage = Campaign.Stage.DONE;
                return;
            }
            c.target = other.id;
            c.side = other.owner;
            c.from = c.position();
            c.to = Campaigns.gateOutside(other);
            c.progress = 0;
            return;
        }
        if (c.total() <= 0) {
            c.stage = Campaign.Stage.DONE;
            return;
        }
        c.progress = Math.min(1, c.progress + SPEED / c.length());
        if (c.progress < 1) return;
        int n = 0;
        for (var e : c.counts.entrySet()) {
            home.add(e.getKey(), e.getValue());
            n += e.getValue();
        }
        c.counts.clear();
        c.stage = Campaign.Stage.DONE;
        mgr.setDirty();
        if (player != null) player.sendSystemMessage(HoldingEvents.gold(n + " of your men are home at " + home.name + "."));
    }

    public static String eta(Campaign c) {
        double left = Math.max(0, (1 - c.progress) * c.length() / SPEED);
        int m = (int) (left / 60), s = (int) (left % 60);
        return m > 0 ? m + "m " + String.format("%02d", s) + "s" : s + "s";
    }

    /** Once a second. */
    static void tick(ServerLevel level, HoldingManager mgr, Campaign c) {
        if (c.target != null && c.origin == null) {
            tickHome(level, mgr, c);
            return;
        }
        Holding origin = c.origin != null ? mgr.holdings.get(c.origin) : null;
        ServerPlayer player = c.player != null ? level.getServer().getPlayerList().getPlayer(c.player) : null;
        if (origin == null || !origin.owner.equals(c.side) || c.total() <= 0) {
            c.stage = Campaign.Stage.DONE;
            return;
        }
        if (c.progress < 1) {
            c.progress = Math.min(1, c.progress + SPEED / c.length());
            ambush(level, mgr, c, player);
            if (c.total() <= 0) {
                c.stage = Campaign.Stage.DONE;
                return;
            }
            if (c.progress < 1) return;
        }
        // arrived. If the player has moved on, ride after them; if they're away, wait at the rendezvous.
        // In another world (the Nether, the End) they take the portal the player took and find them there.
        if (player == null) return;
        ServerLevel where = player.serverLevel();
        BlockPos here = player.blockPosition();
        if (where == level && here.distSqr(c.to) > (double) FIND_RANGE * FIND_RANGE) {
            c.from = c.to;
            c.to = here;
            c.progress = 0;
            return;
        }
        int n = 0;
        for (var e : c.counts.entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                if (Company.arrive(where, mgr, player, origin, e.getKey())) n++;
            }
        }
        c.counts.clear();
        c.stage = Campaign.Stage.DONE;
        where.playSound(null, here, SoundEvents.HORSE_GALLOP, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.sendSystemMessage(HoldingEvents.gold(n + " men from " + origin.name + " have found you and fall in."));
        mgr.setDirty();
    }

    /** A castle of a house that hates the player, close to the column's route, hits it once. */
    private static void ambush(ServerLevel level, HoldingManager mgr, Campaign c, @Nullable ServerPlayer player) {
        if (c.player == null || c.muster) return;
        BlockPos at = c.position();
        for (Holding h : mgr.holdings.values()) {
            if (!h.isHouseOwned() || !h.lordAlive) continue;
            if (h.center().distSqr(new BlockPos(at.getX(), h.center().getY(), at.getZ())) > (double) AMBUSH_RANGE * AMBUSH_RANGE) continue;
            String key = c.id + ":" + h.id;
            if (AMBUSHED.contains(key)) continue;
            if (!Standing.hostile(mgr, h.houseId, c.player)) continue;
            AMBUSHED.add(key);
            int lost = 1 + level.random.nextInt(h.tier + 1);
            int killed = 0;
            for (Role r : new Role[]{Role.ARCHER, Role.CROSSBOWMAN, Role.KNIGHT, Role.CAPTAIN}) {
                while (lost > 0 && c.counts.getOrDefault(r, 0) > 0) {
                    c.add(r, -1);
                    lost--;
                    killed++;
                }
            }
            if (player != null && killed > 0) {
                player.sendSystemMessage(Component.literal("Riders of " + mgr.house(h.houseId).title() + " fell on your column near "
                        + h.name + ": " + killed + " men lost" + (c.total() > 0 ? ", the rest ride on." : ". None got through."))
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    // ------------------------------------------------------------ supply line

    /** The supply line is cancelled: a column it sent that hasn't reached you turns back. How many men. */
    public static int recallSupply(HoldingManager mgr, Holding h) {
        int n = 0;
        for (Campaign c : mgr.campaigns) {
            if (c.kind != Campaign.Kind.COLUMN || c.stage == Campaign.Stage.DONE || !c.supply || !h.id.equals(c.origin)) continue;
            for (var e : c.counts.entrySet()) {
                h.add(e.getKey(), e.getValue());
                n += e.getValue();
            }
            c.counts.clear();
            c.stage = Campaign.Stage.DONE;
        }
        if (n > 0) mgr.setDirty();
        return n;
    }

    public static boolean active(HoldingManager mgr, Holding h) {
        for (Campaign c : mgr.campaigns) {
            if (c.kind == Campaign.Kind.COLUMN && c.stage != Campaign.Stage.DONE && h.id.equals(c.origin)) return true;
        }
        return false;
    }

    /** The standing order: when the company runs short, send a column. Checked every second per holding. */
    static void tickSupply(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (h.reinforceKeep <= 0 || !h.isPlayerOwned() || now < h.nextColumn || active(mgr, h)) return;
        UUID owner = h.ownerPlayer();
        ServerPlayer player = owner != null ? level.getServer().getPlayerList().getPlayer(owner) : null;
        if (player == null) return;
        h.nextColumn = now + SUPPLY_INTERVAL;
        int have = companySize(player.serverLevel(), player);
        int short_ = h.reinforceKeep - have;
        if (short_ <= 0) return;
        EnumMap<Role, Integer> want = new EnumMap<>(Role.class);
        int knights = (int) Math.ceil(short_ * 0.6), archers = (int) Math.ceil(short_ * 0.25);
        want.put(Role.KNIGHT, knights);
        want.put(Role.ARCHER, archers);
        want.put(Role.CROSSBOWMAN, Math.max(0, short_ - knights - archers));
        Campaign c = send(level, mgr, player, h, want);
        if (c != null) c.supply = true;
        if (c == null) {
            player.sendSystemMessage(Component.literal("The supply line from " + h.name + " has run dry; its garrison can't spare anyone.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /** The player's men: Stormhold soldiers sworn to them and Recruits units they own, anywhere in the level. */
    public static int companySize(ServerLevel level, Player player) {
        String me = Sides.player(player.getUUID());
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof GarrisonEntity g) {
                if (player.getUUID().equals(g.owner())) n++;
            } else if (me.equals(RecruitsCompat.sideOf(e))) {
                n++;
            }
        }
        return n;
    }

    private Columns() {
    }
}
