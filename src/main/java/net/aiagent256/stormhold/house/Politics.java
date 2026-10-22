package net.aiagent256.stormhold.house;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.Chronicle;
import net.aiagent256.stormhold.holding.Dragons;
import net.aiagent256.stormhold.holding.Fief;
import net.aiagent256.stormhold.holding.Growth;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The houses have lives of their own. Every few days each one does one thing: marches on a rival's
 * castle (fought out by numbers unless you're there to watch), raises a building at one of its
 * fiefs, or sends an envoy to a lord it likes with an offer: so many emeralds for a rival's castle.
 * Castles change hands without you. Relations between houses sour with every war and mend slowly
 * with time, and the enemy of your enemy thinks a little better of you when you take his castle.
 */
public final class Politics {
    /** Below this, two houses are rivals and raid each other. */
    public static final int RIVAL = -20;
    public static final int MOVE_MIN_DAYS = 4, MOVE_SPREAD_DAYS = 4;
    public static final int REACH = 2600;
    private static final ResourceLocation EMERALD = new ResourceLocation("minecraft", "emerald");

    /** An envoy's offer: emeralds for a castle, to whoever takes it. */
    public static class Contract {
        public UUID id = UUID.randomUUID();
        public String houseId = "";
        public UUID target;
        public int reward;
        public long expiresAt;
        public boolean done;
        /** "castle": take the target castle. "dragon": slay the dragon, whose sighting is the target. */
        public String kind = "castle";
        /** For a dragon bounty: what it is and where it was seen. */
        public String beast = "";
        public int x, z;

        public boolean isBounty() {
            return kind.equals("dragon");
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putString("House", houseId);
            if (target != null) t.putUUID("Target", target);
            t.putString("Kind", kind);
            t.putString("Beast", beast);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putInt("Reward", reward);
            t.putLong("Expires", expiresAt);
            t.putBoolean("Done", done);
            return t;
        }

        public static Contract load(CompoundTag t) {
            Contract c = new Contract();
            if (t.hasUUID("Id")) c.id = t.getUUID("Id");
            c.houseId = t.getString("House");
            c.target = t.hasUUID("Target") ? t.getUUID("Target") : null;
            c.kind = t.contains("Kind") ? t.getString("Kind") : "castle";
            c.beast = t.getString("Beast");
            c.x = t.getInt("X");
            c.z = t.getInt("Z");
            c.reward = t.getInt("Reward");
            c.expiresAt = t.getLong("Expires");
            c.done = t.getBoolean("Done");
            return c;
        }
    }

    // ------------------------------------------------------------ relations between houses

    private static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** -100..100. Pairs start somewhere between old grudges and old friendships, rolled from their names. */
    public static int relation(HoldingManager mgr, String a, String b) {
        if (a.equals(b)) return 100;
        Integer v = mgr.relations.get(key(a, b));
        if (v != null) return v;
        int h = key(a, b).hashCode();
        return (Math.floorMod(h, 71)) - 40;          // -40..30: about a third of all pairs are rivals
    }

    public static void shift(HoldingManager mgr, String a, String b, int delta) {
        if (a.equals(b) || a.equals("outlaws") || b.equals("outlaws")) return;
        int v = Math.max(-100, Math.min(100, relation(mgr, a, b) + delta));
        mgr.relations.put(key(a, b), v);
        mgr.setDirty();
    }

    public static boolean rivals(HoldingManager mgr, String a, String b) {
        return !a.equals(b) && relation(mgr, a, b) <= RIVAL;
    }

    /** Who this house is at war with: rivals it has marched on, or that marched on it, lately. */
    public static List<String> atWarWith(HoldingManager mgr, String houseId) {
        List<String> out = new ArrayList<>();
        for (Campaign c : mgr.campaigns) {
            if (c.kind != Campaign.Kind.RAID || c.stage == Campaign.Stage.DONE) continue;
            String att = Sides.houseId(c.side);
            Holding t = c.target != null ? mgr.holdings.get(c.target) : null;
            if (att == null || t == null || !t.isHouseOwned()) continue;
            if (att.equals(houseId) && !out.contains(t.houseId)) out.add(t.houseId);
            if (t.houseId.equals(houseId) && !out.contains(att)) out.add(att);
        }
        return out;
    }

    // ------------------------------------------------------------ the daily turn

    public static void tick(ServerLevel level, HoldingManager mgr, long now) {
        if (now - mgr.lastPolitics < 600) return;
        mgr.lastPolitics = now;
        Chronicle.dawn(level, mgr);
        net.aiagent256.stormhold.holding.Events.tick(level, mgr, now);
        // offers that have lapsed
        for (Contract c : mgr.contracts) {
            if (!c.done && c.expiresAt < now) {
                c.done = true;
                if (c.isBounty()) {
                    Chronicle.log(level, mgr, Chronicle.HOUSES, mgr.house(c.houseId).title() + " withdraws its bounty on the " + c.beast + ".");
                    continue;
                }
                Holding t = mgr.holdings.get(c.target);
                if (t != null) Chronicle.log(level, mgr, Chronicle.HOUSES, mgr.house(c.houseId).title() + " withdraws its offer for " + t.name + ".");
            }
        }
        mgr.contracts.removeIf(c -> c.done && c.expiresAt < now - 24000 * 3);
        // every few days, the houses heal a little
        if (now - mgr.lastMend >= 24000) {
            mgr.lastMend = now;
            for (var e : mgr.relations.entrySet()) if (e.getValue() < 30) e.setValue(e.getValue() + 1);
        }
        if (!Config.HOUSES_ACT.get()) return;
        for (House house : new ArrayList<>(mgr.houses.values())) {
            if (house.id.equals("outlaws")) continue;
            if (house.nextMove <= 0) {
                house.nextMove = now + 24000L * (2 + level.random.nextInt(MOVE_SPREAD_DAYS));
                mgr.setDirty();
                continue;
            }
            if (now < house.nextMove) continue;
            house.nextMove = now + 24000L * (MOVE_MIN_DAYS + level.random.nextInt(MOVE_SPREAD_DAYS));
            mgr.setDirty();
            act(level, mgr, house, now);
        }
    }

    public static List<Holding> castlesOf(HoldingManager mgr, String houseId) {
        List<Holding> out = new ArrayList<>();
        for (Holding h : mgr.holdings.values()) if (h.isHouseOwned() && h.houseId.equals(houseId) && h.scanned) out.add(h);
        return out;
    }

    /** A rival's castle within reach of one of ours: the nearest, never the rival's seat while it has others. */
    @Nullable
    private static Holding warTarget(HoldingManager mgr, House house, List<Holding> ours) {
        Holding best = null;
        double bestD = Double.MAX_VALUE;
        for (Holding t : mgr.holdings.values()) {
            if (!t.isHouseOwned() || t.houseId.equals(house.id) || !t.scanned || t.houseId.equals("outlaws")) continue;
            if (!rivals(mgr, house.id, t.houseId)) continue;
            if (Campaigns.active(mgr, t.id, Campaign.Kind.RAID)) continue;
            House rival = mgr.house(t.houseId);
            if (t.id.equals(rival.capital) && castlesOf(mgr, rival.id).size() > 1) continue;
            for (Holding o : ours) {
                double d = o.center().distSqr(t.center());
                if (d < bestD && d <= (double) REACH * REACH) {
                    bestD = d;
                    best = t;
                }
            }
        }
        return best;
    }

    private static void act(ServerLevel level, HoldingManager mgr, House house, long now) {
        List<Holding> ours = castlesOf(mgr, house.id);
        if (ours.isEmpty()) return;
        Holding target = warTarget(mgr, house, ours);
        double roll = level.random.nextDouble();
        if (target != null && roll < 0.45) {
            war(level, mgr, house, ours, target, now);
        } else if (target != null && roll < 0.65 && offer(level, mgr, house, target, now)) {
            return;
        } else if (roll < 0.85 && bounty(level, mgr, house, ours, now)) {
            return;
        } else {
            grow(level, mgr, house, ours);
        }
    }

    /** March on a rival's castle. If you're there it's fought out at the walls; if not, by numbers. */
    public static Campaign war(ServerLevel level, HoldingManager mgr, House house, List<Holding> ours, Holding target, long now) {
        Holding from = ours.get(0);
        for (Holding o : ours) if (o.strength() > from.strength()) from = o;
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.RAID;
        c.side = Sides.house(house.id);
        c.origin = from.id;
        c.target = target.id;
        c.tier = Math.max(from.tier, target.tier);
        c.renown = net.aiagent256.stormhold.holding.Frontier.baseLevel(level, from);
        c.from = from.gatePos.equals(BlockPos.ZERO) ? from.center() : from.gatePos;
        c.to = target.bannerPos.equals(BlockPos.ZERO) ? target.center() : target.bannerPos;
        // an army that can take the place: about twice the defenders' strength, lord and walls counted
        double want = target.strength() * 2.2;
        int knights = (int) Math.max(6, Math.min(40, Math.ceil(want * 0.7 / Role.KNIGHT.strength())));
        c.add(Role.CAPTAIN, 1);
        c.add(Role.KNIGHT, knights);
        c.add(Role.CROSSBOWMAN, Math.max(2, (int) Math.ceil(want * 0.15 / Role.CROSSBOWMAN.strength())));
        c.add(Role.ARCHER, Math.max(2, (int) Math.ceil(want * 0.15 / Role.ARCHER.strength())));
        net.aiagent256.stormhold.holding.Spies.behead(mgr, c, house.id, now);
        mgr.campaigns.add(c);
        target.lastRaidAt = now;
        shift(mgr, house.id, target.houseId, -15);
        House rival = mgr.house(target.houseId);
        Chronicle.log(level, mgr, Chronicle.WAR, house.title() + " marches on " + target.name + ", held by " + rival.title()
                + ", with " + c.total() + " men.");
        near(level, target.center(), 1500, Component.literal(house.title() + " marches on " + target.name + " ("
                + rival.title() + ").").withStyle(ChatFormatting.RED));
        mgr.setDirty();
        return c;
    }

    /** The people of one of the house's castles raise a building. */
    private static void grow(ServerLevel level, HoldingManager mgr, House house, List<Holding> ours) {
        List<Holding> can = new ArrayList<>();
        for (Holding h : ours) {
            if (h.fiefPlan == null || !h.fiefPlan.isEmpty()) continue;
            int buildings = 0;
            for (Holding.FiefBuilding b : h.fief) if (!b.piece().equals("field") && !b.piece().equals("orchard")) buildings++;
            boolean works = !Fief.nextExtra(h).startsWith("cottage") && buildings < Growth.fiefCap(h.tier);
            if (works || net.aiagent256.stormhold.holding.Town.grows(level, h) && net.aiagent256.stormhold.holding.Town.room(h)) can.add(h);
        }
        if (can.isEmpty()) return;
        Holding h = can.get(level.random.nextInt(can.size()));
        String next = Fief.nextExtra(h);
        int buildings = 0;
        for (Holding.FiefBuilding b : h.fief) if (!b.piece().equals("field") && !b.piece().equals("orchard")) buildings++;
        if (!next.startsWith("cottage") && buildings < Growth.fiefCap(h.tier)) {
            h.fiefPlan.add(next);
            Chronicle.log(level, mgr, Chronicle.GROWTH, "The people of " + h.name + " (" + house.title() + ") are raising a "
                    + next.replace('_', ' ') + ".");
        } else if (net.aiagent256.stormhold.holding.Town.ask(h)) {
            Chronicle.log(level, mgr, Chronicle.GROWTH, "The town outside " + h.name + " (" + house.title() + ") grows.");
        }
        mgr.setDirty();
    }

    /** An envoy to every lord this house likes: emeralds for a rival's castle. */
    private static boolean offer(ServerLevel level, HoldingManager mgr, House house, Holding target, long now) {
        for (Contract c : mgr.contracts) if (!c.done && !c.isBounty() && c.houseId.equals(house.id)) return false;
        List<UUID> lords = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!mgr.ownedBy(p.getUUID()).isEmpty() && Standing.friendly(mgr, house.id, p.getUUID())) lords.add(p.getUUID());
        }
        if (lords.isEmpty()) return false;
        Contract c = new Contract();
        c.houseId = house.id;
        c.target = target.id;
        c.reward = 15 * target.tier * target.tier + 5 * target.renown;
        c.expiresAt = now + 24000L * 8;
        mgr.contracts.add(c);
        House rival = mgr.house(target.houseId);
        String text = "An envoy of " + house.title() + " offers " + c.reward + " emeralds to whoever takes " + target.name
                + " from " + rival.title() + " within eight days.";
        Chronicle.log(level, mgr, Chronicle.HOUSES, text);
        for (UUID p : lords) HoldingEvents.tell(level, p, Component.literal(text).withStyle(ChatFormatting.AQUA));
        mgr.setDirty();
        return true;
    }

    /** How far from a house's castle a dragon is the house's problem. */
    public static final int BOUNTY_REACH = 800;

    /**
     * A house with a dragon loose near its lands puts a price on its head: so many emeralds to the lord who
     * slays it within ten days. Paid when it dies, to whoever killed it.
     */
    public static boolean bounty(ServerLevel level, HoldingManager mgr, House house, List<Holding> ours, long now) {
        List<UUID> lords = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!mgr.ownedBy(p.getUUID()).isEmpty() && !Standing.hostile(mgr, house.id, p.getUUID())) lords.add(p.getUUID());
        }
        return bounty(level, mgr, house, ours, now, lords);
    }

    public static boolean bounty(ServerLevel level, HoldingManager mgr, House house, List<Holding> ours, long now, List<UUID> lords) {
        if (lords.isEmpty()) return false;
        for (Holding h : ours) {
            for (Dragons.Sighting s : Dragons.seenNear(mgr, h.center(), BOUNTY_REACH, now, 24000L * 6)) {
                boolean posted = false;
                for (Contract c : mgr.contracts) if (!c.done && c.isBounty() && s.dragon.equals(c.target)) posted = true;
                if (posted) continue;
                Contract c = new Contract();
                c.kind = "dragon";
                c.houseId = house.id;
                c.target = s.dragon;
                c.beast = s.kind;
                c.x = s.x;
                c.z = s.z;
                c.reward = 60 + 15 * h.renown + 10 * h.tier;
                c.expiresAt = now + 24000L * 10;
                mgr.contracts.add(c);
                String text = house.title() + " puts " + c.reward + " emeralds on the head of the " + s.kind + " seen near " + h.name + ".";
                Chronicle.log(level, mgr, Chronicle.HOUSES, text);
                for (UUID p : lords) HoldingEvents.tell(level, p, Component.literal(text).withStyle(ChatFormatting.AQUA));
                mgr.setDirty();
                return true;
            }
        }
        return false;
    }

    /** A player killed a dragon: every open bounty on it (or on a dragon seen close by) pays out. */
    public static void dragonSlain(ServerLevel level, HoldingManager mgr, UUID player, UUID dragon, BlockPos at, String kind) {
        long now = level.getGameTime();
        for (Contract c : mgr.contracts) {
            if (c.done || !c.isBounty() || c.expiresAt < now) continue;
            long dx = c.x - at.getX(), dz = c.z - at.getZ();
            boolean same = dragon.equals(c.target) || (c.beast.equals(kind) && dx * dx + dz * dz <= 400L * 400L);
            if (!same) continue;
            c.done = true;
            pay(level, mgr, player, c.reward);
            Standing.change(level, mgr, c.houseId, player, 20, "you slew the " + kind);
            String text = mgr.house(c.houseId).title() + " pays " + c.reward + " emeralds for the head of the " + kind + ".";
            Chronicle.log(level, mgr, Chronicle.HOUSES, text, player);
            HoldingEvents.tell(level, player, Component.literal(text).withStyle(ChatFormatting.AQUA));
        }
        mgr.setDirty();
    }

    // ------------------------------------------------------------ what a change of hands means

    /** Called by {@link HoldingEvents#transfer}. */
    public static void onTransfer(ServerLevel level, HoldingManager mgr, Holding h, String oldSide, String newSide) {
        String oldHouse = Sides.houseId(oldSide);
        String newHouse = Sides.houseId(newSide);
        UUID player = Sides.playerId(newSide);
        if (newHouse != null) {
            // a house took it: the castle is theirs now, in name as well
            h.houseId = newHouse;
            if (oldHouse != null) shift(mgr, newHouse, oldHouse, -10);
            Chronicle.log(level, mgr, Chronicle.WAR, mgr.house(newHouse).title() + " has taken " + h.name + " from "
                    + HoldingEvents.ownerName(level, mgr, oldSide) + ".");
        }
        if (player != null) {
            Chronicle.log(level, mgr, Chronicle.WAR, "You took " + h.name + " from " + HoldingEvents.ownerName(level, mgr, oldSide) + ".", player);
            if (oldHouse != null) {
                // the enemy of my enemy
                for (House other : mgr.houses.values()) {
                    if (other.id.equals(oldHouse) || other.id.equals("outlaws")) continue;
                    if (rivals(mgr, other.id, oldHouse)) Standing.change(level, mgr, other.id, player, 6, "you took " + h.name + " from their rival");
                }
            }
            for (Contract c : mgr.contracts) {
                if (c.done || c.isBounty() || c.expiresAt < level.getGameTime() || !h.id.equals(c.target)) continue;
                c.done = true;
                pay(level, mgr, player, c.reward);
                Standing.change(level, mgr, c.houseId, player, 15, "you took " + h.name + " for them");
                Chronicle.log(level, mgr, Chronicle.HOUSES, mgr.house(c.houseId).title() + " pays you " + c.reward + " emeralds for " + h.name + ".", player);
                HoldingEvents.tell(level, player, Component.literal(mgr.house(c.houseId).title() + " pays " + c.reward
                        + " emeralds for " + h.name + ", as promised.").withStyle(ChatFormatting.AQUA));
            }
        }
    }

    private static void pay(ServerLevel level, HoldingManager mgr, UUID player, int emeralds) {
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player);
        int left = emeralds;
        if (sp != null) {
            while (left > 0) {
                int n = Math.min(64, left);
                ItemStack s = new ItemStack(Items.EMERALD, n);
                if (!sp.getInventory().add(s)) sp.drop(s, false);
                left -= n;
            }
            return;
        }
        List<Holding> mine = mgr.ownedBy(player);
        if (!mine.isEmpty()) mine.get(0).addStock(EMERALD, left);
    }

    private static void near(ServerLevel level, BlockPos at, int radius, Component msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(at) <= (double) radius * radius) p.sendSystemMessage(msg);
        }
    }

    private Politics() {
    }
}
