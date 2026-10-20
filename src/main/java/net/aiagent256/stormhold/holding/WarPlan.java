package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who'd march, in what kit, and the chance: every castle the lord holds, his vassals' too (they're sworn to him), sends
 * its war party against a castle, save one with a party out already or a vassal whose loyalty has run low. They wait
 * for each other and go in together, fought out of sight. The War Table's Attack page lists the castles you know near
 * your own this way, nearest first.
 */
public final class WarPlan {
    /** How far from your nearest castle a castle is still worth listing. */
    public static final int REACH = 3000;
    /** At most this many listed. */
    public static final int MOST = 15;

    /** One castle's war party: its men by role (captains, knights, archers, crossbowmen), their kit level, and its march. */
    public record Part(String castle, int[] men, int kit, int seconds) {
        public int total() {
            int n = 0;
            for (int m : men) n += m;
            return n;
        }
    }

    /**
     * A castle you could march on: who holds it and how they stand with you (attitude as on the Realm map: 1 friendly,
     * 2 wary, 3 hostile, 4 brigands), how far it is from your nearest castle, its defenders and their kit, what you'd
     * send, and the chance. {@code note} is anything that costs you for attacking it (a friend, a truce).
     */
    public record Target(UUID id, String name, String owner, int attitude, int x, int z, int distance, int defenders, int theirKit,
                         List<Part> parts, int pct, String note) {
        public int men() {
            int n = 0;
            for (Part p : parts) n += p.total();
            return n;
        }

        public void write(FriendlyByteBuf b) {
            b.writeUUID(id);
            b.writeUtf(name);
            b.writeUtf(owner);
            b.writeVarInt(attitude);
            b.writeVarInt(x);
            b.writeVarInt(z);
            b.writeVarInt(distance);
            b.writeVarInt(defenders);
            b.writeVarInt(theirKit);
            b.writeVarInt(parts.size());
            for (Part p : parts) {
                b.writeUtf(p.castle());
                b.writeVarIntArray(p.men());
                b.writeVarInt(p.kit());
                b.writeVarInt(p.seconds());
            }
            b.writeVarInt(pct);
            b.writeUtf(note);
        }

        public static Target read(FriendlyByteBuf b) {
            UUID id = b.readUUID();
            String name = b.readUtf(), owner = b.readUtf();
            int att = b.readVarInt(), x = b.readVarInt(), z = b.readVarInt(), dist = b.readVarInt(), def = b.readVarInt(), kit = b.readVarInt();
            int n = b.readVarInt();
            List<Part> parts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) parts.add(new Part(b.readUtf(), b.readVarIntArray(), b.readVarInt(), b.readVarInt()));
            return new Target(id, name, owner, att, x, z, dist, def, kit, parts, b.readVarInt(), b.readUtf());
        }
    }

    static final Role[] ROLES = {Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN};

    /** What {@code from} would send against {@code enemy}, or null if it can't send a war party now. */
    public static Part part(ServerLevel level, HoldingManager mgr, Holding from, Holding enemy) {
        if (from == enemy || !Vassals.answers(from) || Campaigns.active(mgr, from.id, Campaign.Kind.PARTY)) return null;
        int[] men = new int[4];
        int total = 0;
        for (int i = 0; i < 4; i++) {
            men[i] = Campaigns.sends(from, ROLES[i]);
            total += men[i];
        }
        if (total < 3) return null;
        BlockPos a = Campaigns.gateOutside(from), b = enemy.bannerPos.equals(BlockPos.ZERO) ? enemy.center() : enemy.bannerPos;
        double d = Math.sqrt((double) (a.getX() - b.getX()) * (a.getX() - b.getX()) + (double) (a.getZ() - b.getZ()) * (a.getZ() - b.getZ()));
        return new Part(from.name + (Vassals.is(from) ? " (" + from.vassal + ")" : ""), men, Frontier.level(level, from),
                (int) Math.ceil(d / Campaigns.MARCH_SPEED));
    }

    /** The whole plan against one castle. */
    public static Target plan(ServerLevel level, HoldingManager mgr, UUID lord, Holding enemy) {
        List<Part> parts = new ArrayList<>();
        double att = 0;
        int nearest = Integer.MAX_VALUE;
        List<String> home = new ArrayList<>();
        for (Holding h : mgr.ownedBy(lord)) {
            if (h == enemy) continue;
            int d = (int) Math.sqrt(h.center().distSqr(enemy.center().atY(h.center().getY())));
            nearest = Math.min(nearest, d);
            if (!Vassals.answers(h)) home.add(h.lordName + " (loyalty " + h.loyalty + ")");
            Part p = part(level, mgr, h, enemy);
            if (p == null) continue;
            parts.add(p);
            double s = 0;
            for (int i = 0; i < 4; i++) s += p.men()[i] * ROLES[i].strength();
            att += s * Campaigns.kit(p.kit());
        }
        parts.sort((x, y) -> Integer.compare(x.seconds(), y.seconds()));
        int theirKit = Frontier.level(level, enemy);
        double def = enemy.strength() * 1.3 * Campaigns.kit(theirKit);
        int attitude;
        String note = "";
        if (enemy.isHouseOwned() && !enemy.houseId.equals("outlaws")) {
            int s = Standing.get(mgr, enemy.houseId, lord);
            attitude = s >= Standing.FRIENDLY ? 1 : s > Standing.HOSTILE ? 2 : 3;
            if (Truces.active(mgr, enemy.houseId, lord)) note = "You have a truce with " + mgr.house(enemy.houseId).title() + ": marching breaks it.";
            else if (attitude == 1) note = mgr.house(enemy.houseId).title() + " is friendly to you: marching on it costs you that.";
        } else {
            attitude = 4;
        }
        if (!home.isEmpty()) note = (note.isEmpty() ? "" : note + " ") + "Staying home, loyalty under " + Vassals.LOW + ": "
                + String.join(", ", home) + ".";
        return new Target(enemy.id, enemy.name, HoldingEvents.ownerName(level, mgr, enemy.owner), attitude, enemy.center().getX(),
                enemy.center().getZ(), nearest == Integer.MAX_VALUE ? 0 : nearest, HoldingEvents.defendersInAll(enemy), theirKit, parts,
                parts.isEmpty() ? 0 : chance(att, def), note);
    }

    /** The castles you know near your own, nearest first, with the plan against each. */
    public static List<Target> targets(ServerLevel level, HoldingManager mgr, UUID lord) {
        List<Holding> mine = mgr.ownedBy(lord);
        List<Target> out = new ArrayList<>();
        if (mine.isEmpty()) return out;
        List<Holding> known = new ArrayList<>();
        for (Holding h : mgr.holdings.values()) {
            if (h.isPlayerOwned() && lord.equals(h.ownerPlayer())) continue;
            if (!h.greeted.contains(lord) || !h.scanned) continue;
            if (nearest(mine, h) > REACH) continue;
            known.add(h);
        }
        known.sort((a, b) -> Double.compare(nearest(mine, a), nearest(mine, b)));
        for (Holding h : known) {
            if (out.size() >= MOST) break;
            out.add(plan(level, mgr, lord, h));
        }
        return out;
    }

    private static double nearest(List<Holding> mine, Holding h) {
        double best = Double.MAX_VALUE;
        for (Holding m : mine) {
            double dx = m.center().getX() - h.center().getX(), dz = m.center().getZ() - h.center().getZ();
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    /** The chance the attackers' worth beats the defenders' when each side's luck on the day is 0.8 to 1.2 of it. */
    public static int chance(double att, double def) {
        if (att <= 0) return 0;
        int win = 0, n = 40;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double a = att * (0.8 + 0.4 * (i + 0.5) / n), d = def * (0.8 + 0.4 * (j + 0.5) / n);
            if (a > d) win++;
        }
        return (int) Math.round(100.0 * win / (n * n));
    }

    public static String verdict(int pct) {
        return pct >= 95 ? "they'll take it" : pct >= 60 ? "likely to take it" : pct >= 30 ? "a gamble" : pct > 0 ? "likely to break"
                : "they'd break against the walls";
    }

    /** "1 captain, 5 knights, 2 archers" from a party's counts. */
    public static String men(int[] m) {
        String[] one = {"captain", "knight", "archer", "crossbowman"}, many = {"captains", "knights", "archers", "crossbowmen"};
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (m[i] > 0) out.add(m[i] + " " + (m[i] == 1 ? one[i] : many[i]));
        return out.isEmpty() ? "no one" : String.join(", ", out);
    }

    /** The kit a level arms men with, and the level. */
    public static String kit(int level) {
        return Readiness.kitName(Readiness.tier(level)) + " (level " + level + ")";
    }

    public static String time(int seconds) {
        return seconds >= 60 ? (seconds / 60) + "m " + String.format("%02d", seconds % 60) + "s" : seconds + "s";
    }

    private WarPlan() {
    }
}
