package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Spymaster's work, on the Realm map's Houses page. With one on your council you can, once every two days:
 * <ul>
 * <li><b>Kill their captain</b> (60 emeralds, 70%): for three days the house's raids come without a captain and a
 * quarter fewer knights.</li>
 * <li><b>Turn a castle</b> (60 + 40 a tier; 60% at a watchtower, 15 less a tier): its castellan opens the gates to you.
 * Half the garrison rides off to its house; the rest are yours. Never a house's seat.</li>
 * <li><b>Set them on a neighbour</b> (40 emeralds, 80%): forged letters set the house against the house nearest it, and
 * it marches soon.</li>
 * </ul>
 * A spy caught hangs, and the house holds it against you: standing -20, and a truce with it is broken.
 */
public final class Spies {
    public static final int KILL = 0, TURN = 1, WAR = 2;
    public static final int KILL_PRICE = 60, WAR_PRICE = 40, CAUGHT = -20;
    public static final long COOLDOWN = 48000, LEADERLESS = 72000;

    /** One thing the Spymaster offers against a house: what, the chip's words, what it does, whether he's free for it. */
    public record Plot(int kind, String label, String tip, boolean ready) {
    }

    // ------------------------------------------------------------ what he offers

    /** The plots against a house, for its row on the Houses page. Empty without a Spymaster. */
    public static List<Plot> plots(ServerLevel level, HoldingManager mgr, String houseId, UUID me) {
        List<Plot> out = new ArrayList<>();
        if (!Council.has(me, Council.Seat.SPYMASTER) || houseId.equals("outlaws")) return out;
        long now = level.getGameTime();
        long next = mgr.lastPlot.getOrDefault(me, -COOLDOWN) + COOLDOWN;
        boolean ready = now >= next;
        String busy = ready ? "" : " Your Spymaster is busy for " + Math.max(1, (next - now + 1199) / 1200) + " minutes more.";
        House house = mgr.house(houseId);
        out.add(new Plot(KILL, "[Kill their captain: " + KILL_PRICE + "]", "70%: for three days " + house.title() + "'s raids come without a captain "
                + "and a quarter fewer knights. A spy caught hangs: standing " + CAUGHT + "." + busy, ready));
        Holding turn = turnable(mgr, houseId, me);
        if (turn != null) {
            out.add(new Plot(TURN, "[Turn " + turn.name + ": " + turnPrice(turn) + "]", turnChance(turn) + "%: the castellan of " + turn.name
                    + " opens the gates to you. Half the garrison rides off to " + house.title() + "; the rest are yours, and so is the castle. "
                    + "They learn of it either way: standing -30 if it works, " + CAUGHT + " if he's caught." + busy, ready));
        }
        String rival = neighbour(mgr, houseId);
        if (rival != null) {
            out.add(new Plot(WAR, "[Set them on " + mgr.house(rival).name + ": " + WAR_PRICE + "]", "80%: forged letters set " + house.title()
                    + " against " + mgr.house(rival).title() + ", its nearest neighbour; it marches on them soon. Caught: standing " + CAUGHT + "."
                    + busy, ready));
        }
        return out;
    }

    /** The house's castle a bribe could open: one you know, not its seat, nearest your own castles. */
    @Nullable
    static Holding turnable(HoldingManager mgr, String houseId, UUID me) {
        List<Holding> mine = mgr.ownedBy(me);
        House house = mgr.house(houseId);
        Holding best = null;
        double bd = Double.MAX_VALUE;
        for (Holding h : Politics.castlesOf(mgr, houseId)) {
            if (h.id.equals(house.capital) || !h.greeted.contains(me)) continue;
            double d = Double.MAX_VALUE;
            for (Holding m : mine) d = Math.min(d, m.center().distSqr(h.center()));
            if (mine.isEmpty()) d = 0;
            if (d < bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    public static int turnPrice(Holding h) {
        return 60 + 40 * Math.max(1, h.tier);
    }

    public static int turnChance(Holding h) {
        return Math.max(10, 60 - 15 * (Math.max(1, h.tier) - 1));
    }

    /** The house whose castles lie nearest this one's, not already its rival, not the brigands. */
    @Nullable
    static String neighbour(HoldingManager mgr, String houseId) {
        List<Holding> ours = Politics.castlesOf(mgr, houseId);
        String best = null;
        double bd = Double.MAX_VALUE;
        for (Holding t : mgr.holdings.values()) {
            if (!t.isHouseOwned() || t.houseId.equals(houseId) || t.houseId.equals("outlaws") || Politics.rivals(mgr, houseId, t.houseId)) continue;
            for (Holding o : ours) {
                double d = o.center().distSqr(t.center());
                if (d < bd) {
                    bd = d;
                    best = t.houseId;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------ doing it

    public static boolean act(ServerLevel level, HoldingManager mgr, ServerPlayer p, String houseId, int kind) {
        return act(level, mgr, p, houseId, kind, -1);
    }

    /** The same, with the dice given (0 to 99; a negative number rolls them): for the tests. */
    public static boolean act(ServerLevel level, HoldingManager mgr, ServerPlayer p, String houseId, int kind, int roll) {
        UUID me = p.getUUID();
        if (!Council.has(me, Council.Seat.SPYMASTER)) {
            p.sendSystemMessage(Component.literal("You have no Spymaster. Appoint one on the War Table's Council tab.").withStyle(ChatFormatting.RED));
            return false;
        }
        long now = level.getGameTime();
        long next = mgr.lastPlot.getOrDefault(me, -COOLDOWN) + COOLDOWN;
        if (now < next) {
            p.sendSystemMessage(Component.literal("Your Spymaster is busy for " + Math.max(1, (next - now + 1199) / 1200) + " minutes more.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        House house = mgr.house(houseId);
        Holding castle = kind == TURN ? turnable(mgr, houseId, me) : null;
        String rival = kind == WAR ? neighbour(mgr, houseId) : null;
        if (kind == TURN && castle == null || kind == WAR && rival == null) return false;
        int price = kind == KILL ? KILL_PRICE : kind == TURN ? turnPrice(castle) : WAR_PRICE;
        int pct = kind == KILL ? 70 : kind == TURN ? turnChance(castle) : 80;
        int have = Stores.packPlusRealm(mgr, null, p, Items.EMERALD);
        if (!p.isCreative() && have < price) {
            p.sendSystemMessage(Component.literal("That takes " + price + " emeralds; you and the realm have " + have + ".").withStyle(ChatFormatting.RED));
            return false;
        }
        if (!p.isCreative()) Stores.takeFromPackThenRealm(mgr, null, p, Items.EMERALD, price);
        mgr.lastPlot.put(me, now);
        mgr.setDirty();
        if ((roll < 0 ? level.random.nextInt(100) : roll) >= pct) {
            caught(level, mgr, me, houseId);
            return false;
        }
        switch (kind) {
            case KILL -> {
                mgr.leaderless.put(houseId, now + LEADERLESS);
                p.sendSystemMessage(HoldingEvents.gold("Your Spymaster's man did his work: a captain of " + house.title() + " is found dead in his "
                        + "bed. For three days their raids come without a captain and a quarter fewer knights."));
                Chronicle.log(level, mgr, Chronicle.HOUSES, "A captain of " + house.title() + " is found dead in his bed.", me);
            }
            case TURN -> {
                HoldingEvents.transfer(level, mgr, castle, Sides.player(me), true);
                for (Role r : Role.TROOPS) castle.add(r, -castle.count(r) / 2);     // the loyal half ride off to their house
                Standing.change(level, mgr, houseId, me, -30, "you bought the castellan of " + castle.name);
                p.sendSystemMessage(HoldingEvents.gold("The castellan of " + castle.name + " takes your gold and opens the gates to your men. Half "
                        + "the garrison rides off to " + house.title() + "; the rest are yours, and so is " + castle.name + "."));
                Chronicle.log(level, mgr, Chronicle.WAR, "The castellan of " + castle.name + " sells it to you.", me);
            }
            case WAR -> {
                Politics.shift(mgr, houseId, rival, -45);
                if (house.nextMove > now + 2400) house.nextMove = now + 2400;
                p.sendSystemMessage(HoldingEvents.gold("Forged letters do their work: " + house.title() + " and " + mgr.house(rival).title()
                        + " are at each other's throats. " + house.title() + " will march on them soon."));
                Chronicle.log(level, mgr, Chronicle.HOUSES, house.title() + " and " + mgr.house(rival).title() + " fall out over letters "
                        + "nobody will own to.", me);
            }
            default -> {
            }
        }
        mgr.setDirty();
        return true;
    }

    private static void caught(ServerLevel level, HoldingManager mgr, UUID me, String houseId) {
        House house = mgr.house(houseId);
        HoldingEvents.tell(level, me, Component.literal("Your spy is caught and hanged by " + house.title() + ". They know who sent him.")
                .withStyle(ChatFormatting.RED));
        if (Truces.active(mgr, houseId, me)) Truces.broken(level, mgr, houseId, me, "your spy was caught");
        Standing.change(level, mgr, houseId, me, CAUGHT, "your spy was caught");
        Chronicle.log(level, mgr, Chronicle.HOUSES, house.title() + " hangs a spy of yours.", me);
    }

    // ------------------------------------------------------------ their raids without a captain

    public static boolean leaderless(HoldingManager mgr, @Nullable String houseId, long now) {
        if (houseId == null) return false;
        Long until = mgr.leaderless.get(houseId);
        return until != null && now < until;
    }

    /** A raid of a house whose captain is dead: no captain, a quarter fewer knights. */
    public static void behead(HoldingManager mgr, Campaign c, @Nullable String houseId, long now) {
        if (!leaderless(mgr, houseId, now) || !c.heir.isEmpty()) return;
        c.counts.remove(Role.CAPTAIN);
        int k = c.counts.getOrDefault(Role.KNIGHT, 0);
        if (k > 0) c.counts.put(Role.KNIGHT, Math.max(1, k - k / 4));
    }

    static CompoundTag save(Map<UUID, Long> plots, Map<String, Long> leaderless) {
        CompoundTag t = new CompoundTag();
        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        plots.forEach((k, v) -> a.putLong(k.toString(), v));
        leaderless.forEach(b::putLong);
        t.put("Plots", a);
        t.put("Leaderless", b);
        return t;
    }

    static void load(CompoundTag t, Map<UUID, Long> plots, Map<String, Long> leaderless) {
        CompoundTag a = t.getCompound("Plots"), b = t.getCompound("Leaderless");
        for (String k : a.getAllKeys()) {
            try {
                plots.put(UUID.fromString(k), a.getLong(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String k : b.getAllKeys()) leaderless.put(k, b.getLong(k));
    }

    private Spies() {
    }
}
