package net.aiagent256.stormhold.net;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Builder;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Chronicle;
import net.aiagent256.stormhold.holding.Economy;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Everything the Realm page shows: the map of known castles, the houses, your holdings, the record. */
public record RealmData(long day, int px, int pz, List<Castle> castles, List<HouseMark> houses, List<Own> own,
                        List<Line> lines, List<Note> notes, List<Beast> beasts) {

    /** A castle on the map. attitude: 0 yours, 1 friendly house, 2 wary, 3 hostile, 4 brigands/other. */
    public record Castle(UUID id, String name, int tier, int x, int z, int color, int attitude, boolean lordAlive, int renown,
                         String owner, boolean underAttack, boolean seat) {
    }

    public record HouseMark(String id, String name, int color, int standing, int castles, String atWar, boolean seatKnown) {
    }

    public record Own(UUID id, String name, int tier, int troops, int capacity, int fiefPop, String builder, int raidDays,
                      String mode, String stock, boolean underAttack) {
    }

    /** An army on the march: from (x1,z1) to (x2,z2), in a house's colour. */
    public record Line(int x1, int z1, int x2, int z2, int color, boolean raid) {
    }

    /** A dragon someone's garrison has seen: where, how long ago (in days), and whether a house pays for its head. */
    public record Beast(String kind, int x, int z, int daysAgo, boolean dead, int bounty) {
    }

    /** A line of the record or an open offer; kind as in {@link Chronicle}. */
    public record Note(long day, int kind, String text) {
    }

    public void write(FriendlyByteBuf b) {
        b.writeVarLong(day);
        b.writeVarInt(px);
        b.writeVarInt(pz);
        b.writeVarInt(castles.size());
        for (Castle c : castles) {
            b.writeUUID(c.id);
            b.writeUtf(c.name);
            b.writeVarInt(c.tier);
            b.writeVarInt(c.x);
            b.writeVarInt(c.z);
            b.writeVarInt(c.color);
            b.writeVarInt(c.attitude);
            b.writeBoolean(c.lordAlive);
            b.writeVarInt(c.renown);
            b.writeUtf(c.owner);
            b.writeBoolean(c.underAttack);
            b.writeBoolean(c.seat);
        }
        b.writeVarInt(houses.size());
        for (HouseMark h : houses) {
            b.writeUtf(h.id);
            b.writeUtf(h.name);
            b.writeVarInt(h.color);
            b.writeVarInt(h.standing);
            b.writeVarInt(h.castles);
            b.writeUtf(h.atWar);
            b.writeBoolean(h.seatKnown);
        }
        b.writeVarInt(own.size());
        for (Own o : own) {
            b.writeUUID(o.id);
            b.writeUtf(o.name);
            b.writeVarInt(o.tier);
            b.writeVarInt(o.troops);
            b.writeVarInt(o.capacity);
            b.writeVarInt(o.fiefPop);
            b.writeUtf(o.builder);
            b.writeVarInt(o.raidDays);
            b.writeUtf(o.mode);
            b.writeUtf(o.stock);
            b.writeBoolean(o.underAttack);
        }
        b.writeVarInt(lines.size());
        for (Line l : lines) {
            b.writeVarInt(l.x1);
            b.writeVarInt(l.z1);
            b.writeVarInt(l.x2);
            b.writeVarInt(l.z2);
            b.writeVarInt(l.color);
            b.writeBoolean(l.raid);
        }
        b.writeVarInt(notes.size());
        for (Note n : notes) {
            b.writeVarLong(n.day);
            b.writeVarInt(n.kind);
            b.writeUtf(n.text);
        }
        b.writeVarInt(beasts.size());
        for (Beast d : beasts) {
            b.writeUtf(d.kind);
            b.writeVarInt(d.x);
            b.writeVarInt(d.z);
            b.writeVarInt(d.daysAgo);
            b.writeBoolean(d.dead);
            b.writeVarInt(d.bounty);
        }
    }

    public static RealmData read(FriendlyByteBuf b) {
        long day = b.readVarLong();
        int px = b.readVarInt(), pz = b.readVarInt();
        int n = b.readVarInt();
        List<Castle> castles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            castles.add(new Castle(b.readUUID(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                    b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readUtf(), b.readBoolean(), b.readBoolean()));
        }
        n = b.readVarInt();
        List<HouseMark> houses = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            houses.add(new HouseMark(b.readUtf(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(), b.readBoolean()));
        }
        n = b.readVarInt();
        List<Own> own = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            own.add(new Own(b.readUUID(), b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(),
                    b.readVarInt(), b.readUtf(), b.readUtf(), b.readBoolean()));
        }
        n = b.readVarInt();
        List<Line> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(new Line(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean()));
        }
        n = b.readVarInt();
        List<Note> notes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) notes.add(new Note(b.readVarLong(), b.readVarInt(), b.readUtf()));
        n = b.readVarInt();
        List<Beast> beasts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) beasts.add(new Beast(b.readUtf(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarInt()));
        return new RealmData(day, px, pz, castles, houses, own, lines, notes, beasts);
    }

    /** What this player knows: every castle he's come within sight of, every house he's heard of, his own holdings. */
    public static RealmData build(ServerPlayer player) {
        return build(player.serverLevel(), player.getUUID(), player.blockPosition());
    }

    public static RealmData build(ServerLevel level, UUID me, net.minecraft.core.BlockPos at) {
        HoldingManager mgr = HoldingManager.get(level);
        long now = level.getGameTime();
        List<Castle> castles = new ArrayList<>();
        List<Own> own = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        java.util.Set<String> knownHouses = new java.util.HashSet<>();
        java.util.Set<UUID> known = new java.util.HashSet<>();
        for (Holding h : mgr.holdings.values()) {
            boolean mine = h.isPlayerOwned() && me.equals(h.ownerPlayer());
            if (!mine && !h.greeted.contains(me)) continue;
            known.add(h.id);
            boolean attacked = net.aiagent256.stormhold.holding.Campaigns.active(mgr, h.id, Campaign.Kind.RAID);
            int attitude;
            int color;
            if (mine) {
                attitude = 0;
                color = mgr.playerColor(me).getId();
            } else if (h.isHouseOwned() && !h.houseId.equals("outlaws")) {
                knownHouses.add(h.houseId);
                House house = mgr.house(h.houseId);
                color = house.base.getId();
                int s = Standing.get(mgr, h.houseId, me);
                attitude = s >= Standing.FRIENDLY ? 1 : s > Standing.HOSTILE ? 2 : 3;
            } else {
                attitude = 4;
                color = mgr.colorOf(h.owner).getId();
            }
            House house = h.isHouseOwned() ? mgr.house(h.houseId) : null;
            castles.add(new Castle(h.id, h.name, h.isTown() ? 0 : h.tier, h.center().getX(), h.center().getZ(), color, attitude, h.lordAlive, h.renown,
                    HoldingEvents.ownerName(level, mgr, h.owner), attacked, house != null && h.id.equals(house.capital)));
            if (mine) {
                int troops = h.troops(), cap = 0;
                for (Role r : new Role[]{Role.CAPTAIN, Role.KNIGHT, Role.ARCHER, Role.CROSSBOWMAN}) cap += h.capacity(r);
                String builder = h.work.equals(Builder.NONE) ? (h.fortify > 0 ? "fortified " + h.fortify : "idle")
                        : h.work.equals(Builder.REPAIR) ? "repairing" : "building " + Builder.stageName(h.fortify + 1);
                int raidDays = h.nextRaid > 0 && !Standing.friendly(mgr, h.houseId, me) ? (int) Math.max(0, (h.nextRaid - now + 23999) / 24000) : -1;
                own.add(new Own(h.id, h.name, h.isTown() ? 0 : h.tier, troops, cap, h.fiefPop, builder, raidDays, h.mode.name().toLowerCase(),
                        Economy.stockSummary(h), attacked));
            }
        }
        for (Campaign c : mgr.campaigns) {
            if (c.stage == Campaign.Stage.DONE || c.stage == Campaign.Stage.RETURN) continue;
            if (c.kind != Campaign.Kind.RAID && c.kind != Campaign.Kind.PARTY) continue;
            boolean show = (c.target != null && known.contains(c.target)) || (c.origin != null && known.contains(c.origin))
                    || Sides.player(me).equals(c.side);
            if (!show) continue;
            lines.add(new Line(c.from.getX(), c.from.getZ(), c.to.getX(), c.to.getZ(), mgr.colorOf(c.side).getId(), c.kind == Campaign.Kind.RAID));
        }
        List<HouseMark> houses = new ArrayList<>();
        for (String id : knownHouses) {
            House house = mgr.house(id);
            StringBuilder war = new StringBuilder();
            for (String other : Politics.atWarWith(mgr, id)) {
                if (war.length() > 0) war.append(", ");
                war.append(mgr.house(other).name);
            }
            houses.add(new HouseMark(id, house.name, house.base.getId(), Standing.get(mgr, id, me), Politics.castlesOf(mgr, id).size(),
                    war.toString(), house.capital != null && known.contains(house.capital)));
        }
        houses.sort((a, b) -> b.standing - a.standing);
        List<Note> notes = new ArrayList<>();
        for (Politics.Contract c : mgr.contracts) {
            if (c.done || c.expiresAt < now) continue;
            if (c.isBounty()) {
                notes.add(new Note(-1, Chronicle.HOUSES, mgr.house(c.houseId).title() + " pays " + c.reward + " emeralds for the head of the "
                        + c.beast + " seen near " + c.x + ", " + c.z + ", " + Math.max(1, (c.expiresAt - now) / 24000) + " days left."));
                continue;
            }
            Holding t = mgr.holdings.get(c.target);
            if (t == null) continue;
            notes.add(new Note(-1, Chronicle.HOUSES, mgr.house(c.houseId).title() + " pays " + c.reward + " emeralds for " + t.name
                    + ", " + Math.max(1, (c.expiresAt - now) / 24000) + " days left."));
        }
        // dragons seen from castles the player knows, the last ten days
        List<Beast> beasts = new ArrayList<>();
        for (net.aiagent256.stormhold.holding.Dragons.Sighting s : mgr.dragons) {
            if (now - s.when > 24000L * 10) continue;
            if (s.holding == null || !known.contains(s.holding)) continue;
            int bounty = 0;
            for (Politics.Contract c : mgr.contracts) if (!c.done && c.isBounty() && c.expiresAt >= now && s.dragon.equals(c.target)) bounty = c.reward;
            beasts.add(new Beast(s.kind, s.x, s.z, (int) ((now - s.when) / 24000L), s.dead, bounty));
        }
        for (Chronicle.Entry e : Chronicle.forPlayer(mgr, me, 40)) notes.add(new Note(e.day(), e.kind(), e.text()));
        // a matter waiting on the player's word goes last, so the page shows it first
        net.aiagent256.stormhold.holding.Events.Matter open = net.aiagent256.stormhold.holding.Events.open(mgr, me);
        if (open != null) {
            var card = net.aiagent256.stormhold.holding.Events.card(mgr, open);
            notes.add(new Note(-2, Chronicle.NEWS, card.text() + " Answer in chat: /stormhold matters"));
        }
        return new RealmData(Chronicle.day(level), at.getX(), at.getZ(), castles, houses, own, lines, notes, beasts);
    }

    public static int rgb(int dye) {
        return DyeColor.byId(dye).getTextColor();
    }
}
