package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Truces. Pay a house, or send it one of your knights as a hostage, and for three days it sends no raids on what
 * you hold, its gates open to you and its men leave yours alone. Strike its men, march on its castles or set an
 * engine against its walls and the truce is broken: your standing with it falls hard, and the hostage pays for
 * it. When the three days are out, the hostage comes home to the castle he left.
 */
public final class Truces {
    public static final int DAYS = 3;
    public static final long LENGTH = 24000L * DAYS;
    /** The price: a base, and so much for every castle the house holds; double for a sworn enemy. */
    public static final int BASE = 24, PER_CASTLE = 12, MAX_PRICE = 160;

    public static final class Truce {
        public long until;
        /** The knight sent as a hostage, and the castle he came from; empty when the truce was bought. */
        public String hostage = "";
        @Nullable public UUID hostageFrom;

        CompoundTag save(String house, UUID player) {
            CompoundTag t = new CompoundTag();
            t.putString("House", house);
            t.putUUID("Player", player);
            t.putLong("Until", until);
            t.putString("Hostage", hostage);
            if (hostageFrom != null) t.putUUID("From", hostageFrom);
            return t;
        }
    }

    public static boolean active(HoldingManager mgr, @Nullable String houseId, @Nullable UUID player) {
        if (houseId == null || player == null) return false;
        Map<UUID, Truce> m = mgr.truces.get(houseId);
        return m != null && m.containsKey(player);
    }

    @Nullable
    public static Truce get(HoldingManager mgr, String houseId, UUID player) {
        Map<UUID, Truce> m = mgr.truces.get(houseId);
        return m == null ? null : m.get(player);
    }

    /** Emeralds the house asks: 24 and 12 a castle (up to 160), twice that from a sworn enemy. */
    public static int price(HoldingManager mgr, String houseId, UUID player) {
        int castles = Politics.castlesOf(mgr, houseId).size();
        int p = Math.min(MAX_PRICE, BASE + PER_CASTLE * castles);
        if (Standing.get(mgr, houseId, player) <= -60) p *= 2;
        return p;
    }

    /** Why the house won't treat right now, or null if it will. */
    @Nullable
    public static String refusal(HoldingManager mgr, String houseId, UUID player) {
        if (houseId.equals("outlaws")) return "Brigands keep no truces.";
        House house = mgr.house(houseId);
        if (Standing.friendly(mgr, houseId, player)) return house.title() + " is friendly to you already: it sends no raids.";
        if (active(mgr, houseId, player)) return "You have a truce with " + house.title() + " already.";
        return null;
    }

    /** Ask a house for a truce, paying emeralds or giving a hostage. */
    public static boolean ask(ServerLevel level, HoldingManager mgr, Player player, String houseId, boolean hostage) {
        String no = refusal(mgr, houseId, player.getUUID());
        if (no != null) {
            player.sendSystemMessage(Component.literal(no).withStyle(ChatFormatting.RED));
            return false;
        }
        House house = mgr.house(houseId);
        Truce t = new Truce();
        long now = level.getGameTime();
        t.until = now + LENGTH;
        String paid;
        if (hostage) {
            Holding from = hostageFrom(mgr, player.getUUID());
            if (from == null) {
                player.sendSystemMessage(Component.literal("A hostage must be a knight from one of your castles, and none of them has two "
                        + "to spare one.").withStyle(ChatFormatting.RED));
                return false;
            }
            sendAway(level, from);
            t.hostage = "Ser " + NameGen.first(level.random);
            t.hostageFrom = from.id;
            paid = t.hostage + " of " + from.name + " rides to them as a hostage";
        } else {
            int price = price(mgr, houseId, player.getUUID());
            int have = Stores.packPlusRealm(mgr, null, player, Items.EMERALD);
            if (have < price) {
                player.sendSystemMessage(Component.literal(house.title() + " asks " + price + " emeralds for a truce (" + BASE + " and "
                        + PER_CASTLE + " for each of its " + Politics.castlesOf(mgr, houseId).size() + " castles"
                        + (Standing.get(mgr, houseId, player.getUUID()) <= -60 ? ", doubled: you're its sworn enemy" : "")
                        + "); you have " + have + ".").withStyle(ChatFormatting.RED));
                return false;
            }
            Stores.takeFromPackThenRealm(mgr, null, player, Items.EMERALD, price);
            paid = price + " emeralds paid";
        }
        mgr.truces.computeIfAbsent(houseId, k -> new HashMap<>()).put(player.getUUID(), t);
        mgr.setDirty();
        // its men in the field turn for home
        int recalled = 0;
        for (Campaign c : mgr.campaigns) {
            if (c.kind != Campaign.Kind.RAID || c.stage == Campaign.Stage.DONE || !c.side.equals(Sides.house(houseId))) continue;
            Holding target = c.target == null ? null : mgr.holdings.get(c.target);
            if (target == null || !player.getUUID().equals(target.ownerPlayer())) continue;
            SiegeWorks.clear(level, c);
            Garrisons.discardLive(level, c.live, true);
            Campaigns.clearBar(c);
            c.stage = Campaign.Stage.DONE;
            recalled++;
        }
        player.sendSystemMessage(HoldingEvents.gold(house.title() + " agrees a truce for " + DAYS + " days (" + paid + "). No raids from "
                + "them, their gates open to you and their men leave yours alone." + (recalled > 0 ? " Its army before your walls turns for home." : "")
                + " Strike their men or march on their castles and it's broken" + (hostage ? ", and the hostage pays." : ".")));
        Chronicle.log(level, mgr, Chronicle.HOUSES, "A truce with " + house.title() + " for " + DAYS + " days.", player.getUUID());
        return true;
    }

    /** The castle a hostage comes from: the seat if it has two knights, else whichever castle has the most. */
    @Nullable
    static Holding hostageFrom(HoldingManager mgr, UUID player) {
        Holding best = null;
        for (Holding h : mgr.ownedBy(player)) {
            if (Vassals.is(h) || h.count(Role.KNIGHT) < 2) continue;
            if (best == null || Seats.is(mgr, h) && !Seats.is(mgr, best) || !Seats.is(mgr, best) && h.count(Role.KNIGHT) > best.count(Role.KNIGHT)) best = h;
        }
        return best;
    }

    /** One knight leaves the castle: off the roll, and the man himself if he's standing there. */
    private static void sendAway(ServerLevel level, Holding h) {
        UUID leaving = null;
        for (var e : h.live.entrySet()) {
            if (e.getValue() == Role.KNIGHT) {
                leaving = e.getKey();
                break;
            }
        }
        if (leaving != null) {
            h.live.remove(leaving);
            Entity e = level.getEntity(leaving);
            if (e instanceof GarrisonEntity g) {
                if (g.post() != null) h.usedPosts.remove(g.post());
                g.discard();
            }
        }
        h.add(Role.KNIGHT, -1);
    }

    /** Something the player did to the house: the truce is off. */
    public static void broken(ServerLevel level, HoldingManager mgr, String houseId, UUID player, String what) {
        Map<UUID, Truce> m = mgr.truces.get(houseId);
        Truce t = m == null ? null : m.remove(player);
        if (t == null) return;
        mgr.setDirty();
        House house = mgr.house(houseId);
        Standing.change(level, mgr, houseId, player, -30, "you broke the truce");
        HoldingEvents.tell(level, player, Component.literal("You've broken the truce with " + house.title() + ": " + what + "."
                + (t.hostage.isEmpty() ? "" : " They hang " + t.hostage + ", your hostage.")).withStyle(ChatFormatting.RED));
        Chronicle.log(level, mgr, Chronicle.HOUSES, "The truce with " + house.title() + " is broken" + (t.hostage.isEmpty() ? "." : "; "
                + t.hostage + " is hanged."), player);
    }

    /** Once a second: truces whose days are out end, and hostages come home. */
    public static void tick(ServerLevel level, HoldingManager mgr) {
        if (mgr.truces.isEmpty()) return;
        long now = level.getGameTime();
        for (var he : mgr.truces.entrySet()) {
            for (Iterator<Map.Entry<UUID, Truce>> it = he.getValue().entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                Truce t = e.getValue();
                if (now < t.until) continue;
                it.remove();
                mgr.setDirty();
                House house = mgr.house(he.getKey());
                String home = "";
                if (!t.hostage.isEmpty() && t.hostageFrom != null) {
                    Holding from = mgr.holdings.get(t.hostageFrom);
                    if (from != null && e.getKey().equals(from.ownerPlayer())) {
                        from.add(Role.KNIGHT, 1);
                        home = " " + t.hostage + " comes home to " + from.name + ".";
                    } else {
                        home = " " + t.hostage + " is sent back to you.";
                    }
                }
                HoldingEvents.tell(level, e.getKey(), Component.literal("The truce with " + house.title() + " is over: their raids may come again."
                        + home).withStyle(ChatFormatting.GOLD));
            }
        }
        mgr.truces.values().removeIf(Map::isEmpty);
    }

    /** Days left, rounded up. */
    public static int daysLeft(ServerLevel level, Truce t) {
        return (int) Math.max(1, (t.until - level.getGameTime() + 23999) / 24000);
    }

    static ListTag save(Map<String, Map<UUID, Truce>> truces) {
        ListTag l = new ListTag();
        truces.forEach((house, m) -> m.forEach((p, t) -> l.add(t.save(house, p))));
        return l;
    }

    static void load(ListTag l, Map<String, Map<UUID, Truce>> into) {
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            if (!c.hasUUID("Player")) continue;
            Truce t = new Truce();
            t.until = c.getLong("Until");
            t.hostage = c.getString("Hostage");
            if (c.hasUUID("From")) t.hostageFrom = c.getUUID("From");
            into.computeIfAbsent(c.getString("House"), k -> new HashMap<>()).put(c.getUUID("Player"), t);
        }
    }

    private Truces() {
    }
}
