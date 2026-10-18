package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.worldgen.Finder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Scouting: two riders from a castle's garrison ride to a spot on the map, look over the country for
 * {@link #RADIUS} blocks round it, and ride home to tell you what's there: every castle, town and city,
 * who holds it, how many men, how they stand with you; and where the castles nobody has seen yet will be
 * found. What they report goes on the Realm page. Country held by houses that hate you, and the frontier,
 * cost riders: one may not come back, and if neither does, there's no report.
 */
public final class Scouts {
    public static final int PARTY = 2, RADIUS = 500, MIN_RANGE = 64, MAX_RANGE = 5000;
    /** Blocks a second, out and back: riders on good horses. */
    public static final double SPEED = 8.0;
    /** How long the survey of unseen castle sites may take, once the riders are home. */
    private static final long SURVEY_MS = 250;

    public static boolean out(HoldingManager mgr, Holding h) {
        for (Campaign c : mgr.campaigns)
            if (c.kind == Campaign.Kind.SCOUT && c.stage != Campaign.Stage.DONE && h.id.equals(c.origin)) return true;
        return false;
    }

    /** How long the ride takes there and back, in seconds. */
    public static int seconds(double dist) {
        return (int) Math.ceil(2 * dist / SPEED);
    }

    public static String time(int seconds) {
        return seconds >= 90 ? (seconds + 30) / 60 + " minutes" : seconds + " seconds";
    }

    @Nullable
    public static Campaign send(ServerLevel level, HoldingManager mgr, Holding h, Player player, BlockPos target) {
        if (!h.isPlayerOwned() || !player.getUUID().equals(h.ownerPlayer())) return null;
        if (out(mgr, h)) {
            player.sendSystemMessage(Component.literal(h.name + "'s scouts are already out.").withStyle(ChatFormatting.RED));
            return null;
        }
        BlockPos from = Campaigns.gateOutside(h);
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < MIN_RANGE) {
            player.sendSystemMessage(Component.literal("That's within sight of the walls. Put a spot farther off in the x and z boxes.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (d > MAX_RANGE) {
            player.sendSystemMessage(Component.literal("That's " + (int) d + " blocks off; scouts ride up to " + MAX_RANGE + ".")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (h.troops() < PARTY + 2) {
            player.sendSystemMessage(Component.literal(h.name + " can't spare two riders: it holds only " + h.troops() + " men.")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.SCOUT;
        c.side = h.owner;
        c.origin = h.id;
        c.player = player.getUUID();
        c.from = from;
        c.to = new BlockPos(target.getX(), from.getY(), target.getZ());
        for (int i = 0; i < PARTY; i++) {
            Role r = h.count(Role.KNIGHT) > 1 ? Role.KNIGHT : h.count(Role.ARCHER) > 1 ? Role.ARCHER : h.count(Role.CROSSBOWMAN) > 0
                    ? Role.CROSSBOWMAN : Role.KNIGHT;
            h.add(r, -1);
            c.add(r, 1);
        }
        mgr.campaigns.add(c);
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold("Two riders leave " + h.name + " to scout " + Guide.way(from, c.to) + " (" + c.to.getX()
                + ", " + c.to.getZ() + "). They'll be back in about " + time(seconds(d)) + "."));
        return c;
    }

    /** Once a second. */
    public static void tick(ServerLevel level, HoldingManager mgr, Campaign c) {
        Holding h = c.origin != null ? mgr.holdings.get(c.origin) : null;
        if (h == null || !h.isPlayerOwned()) {
            c.stage = Campaign.Stage.DONE;
            return;
        }
        c.progress += SPEED / c.length();
        if (c.progress < 1) return;
        if (c.stage == Campaign.Stage.MARCH) {
            danger(level, mgr, h, c);
            if (c.total() <= 0) {
                HoldingEvents.tell(level, c.player, Component.literal("The scouts from " + h.name + " never came back from "
                        + c.to.getX() + ", " + c.to.getZ() + ".").withStyle(ChatFormatting.RED));
                Chronicle.log(level, mgr, Chronicle.WAR, "Scouts from " + h.name + " are lost in the country near "
                        + c.to.getX() + ", " + c.to.getZ() + ".", c.player);
                c.stage = Campaign.Stage.DONE;
                return;
            }
            c.stage = Campaign.Stage.RETURN;
            c.progress = 0;
            mgr.setDirty();
            return;
        }
        home(level, mgr, h, c);
    }

    /** Home: the men back on the walls, and the report. */
    public static void home(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        for (var e : c.counts.entrySet()) h.add(e.getKey(), e.getValue());
        List<Component> lines = report(level, mgr, h, c);
        for (Component line : lines) HoldingEvents.tell(level, c.player, line);
        Chronicle.log(level, mgr, Chronicle.NEWS, "Scouts from " + h.name + " report " + (lines.size() - 1) + " places near "
                + c.to.getX() + ", " + c.to.getZ() + ".", c.player);
        c.stage = Campaign.Stage.DONE;
        mgr.setDirty();
    }

    /** At the far end: riders lost to the houses that hate you and to the frontier's beasts. */
    static void danger(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        double risk = risk(level, mgr, h, c);
        int lost = 0;
        for (Role r : Role.TROOPS) {
            int n = c.counts.getOrDefault(r, 0);
            for (int i = 0; i < n; i++) {
                if (level.random.nextDouble() < risk) {
                    c.add(r, -1);
                    lost++;
                }
            }
        }
        c.spoils = lost;
    }

    /** The chance each rider doesn't come back: the frontier zone, and hostile castles near the spot. */
    public static double risk(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        double risk = 0.04 * (Frontier.zone(level, c.to) - 1);
        for (Holding o : mgr.holdings.values()) {
            if (o == h || !near(o, c.to, 320)) continue;
            if (o.isHouseOwned() && c.player != null && Standing.hostile(mgr, o.houseId, c.player)) risk += 0.12;
            else if (net.aiagent256.stormhold.Sides.OUTLAWS.equals(o.owner)) risk += 0.08;
        }
        return Math.min(0.6, risk);
    }

    private static boolean near(Holding o, BlockPos p, int r) {
        BlockPos q = o.center();
        double dx = q.getX() - p.getX(), dz = q.getZ() - p.getZ();
        return dx * dx + dz * dz <= (double) r * r;
    }

    /** What the riders saw, nearest the spot first. */
    public static List<Component> report(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        List<Component> out = new ArrayList<>();
        BlockPos home = Campaigns.gateOutside(h);
        record Seen(double d, Component line) {
        }
        List<Seen> seen = new ArrayList<>();
        List<BlockPos> places = new ArrayList<>();
        for (Holding o : mgr.holdings.values()) {
            if (o == h || !near(o, c.to, RADIUS)) continue;
            if (o.isNether() != h.isNether()) continue;
            places.add(o.center());
            if (c.player != null) o.greeted.add(c.player);
            String owner = HoldingEvents.ownerName(level, mgr, o.owner);
            String stand = !o.isHouseOwned() || c.player == null ? "" : Standing.hostile(mgr, o.houseId, c.player) ? ", who hate you"
                    : Standing.friendly(mgr, o.houseId, c.player) ? ", friends of yours" : "";
            boolean yours = c.player != null && c.player.equals(o.ownerPlayer());
            String men = o.troops() <= 0 ? "no garrison to speak of" : "about " + round(o.troops()) + " men";
            BlockPos p = o.center();
            seen.add(new Seen(dist(p, c.to), Component.literal("  " + o.name + ", " + o.kindName().toLowerCase(java.util.Locale.ROOT) + ", "
                    + (yours ? "yours" : "held by " + owner + stand) + ": " + men + ". " + Guide.way(home, p) + " of " + h.name
                    + " (" + p.getX() + ", " + p.getZ() + ").").withStyle(yours ? ChatFormatting.GREEN : stand.contains("hate")
                    ? ChatFormatting.RED : ChatFormatting.WHITE)));
        }
        if (!h.isNether()) {
            for (Finder.Found f : Finder.within(level, c.to, RADIUS, SURVEY_MS)) {
                BlockPos p = new BlockPos(f.x(), 0, f.z());
                boolean known = false;
                for (BlockPos q : places) if (dist(q, p) < 128) known = true;
                if (known) continue;
                seen.add(new Seen(dist(p, c.to), Component.literal("  Unseen: the ground for " + article(f.what()) + " " + f.what()
                        + " near " + f.x() + ", " + f.z() + ", " + Guide.way(home, p) + " of " + h.name + ".").withStyle(ChatFormatting.GRAY)));
            }
        }
        seen.sort(Comparator.comparingDouble(Seen::d));
        int lost = c.spoils;
        out.add(HoldingEvents.gold("The scouts are back at " + h.name + " from " + c.to.getX() + ", " + c.to.getZ()
                + (lost > 0 ? ", one rider short" : "") + ". " + (seen.isEmpty() ? "Open country: nothing held and nothing built within "
                + RADIUS + " blocks." : "Within " + RADIUS + " blocks they found:")));
        for (int i = 0; i < Math.min(10, seen.size()); i++) out.add(seen.get(i).line());
        if (seen.size() > 10) out.add(Component.literal("  ...and " + (seen.size() - 10) + " more.").withStyle(ChatFormatting.GRAY));
        return out;
    }

    private static String article(String what) {
        return "aeiou".indexOf(Character.toLowerCase(what.charAt(0))) >= 0 ? "an" : "a";
    }

    private static int round(int n) {
        return n < 20 ? n : (n + 2) / 5 * 5;
    }

    private static double dist(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Scouts() {
    }
}
