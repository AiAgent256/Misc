package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Heirs. Take a castle from a house by force and the son of the lord you took it from swears to win it back. His
 * attacks come sooner than the house's raids would (six days in ten) and with three more knights, and he leads
 * them himself as their captain. Kill him before your walls and the claim dies with him; throw him back three
 * times and he gives it up; or buy him out on the War Table. If he wins, he's lord of his father's castle again.
 */
public final class Heirs {
    public static final int GIVE_UP = 3, MORE_KNIGHTS = 3;
    public static final double SOONER = 0.6;
    public static final String TAG = "StormholdHeir";

    /** The price of a claim: 40, and 20 for every tier of the castle. */
    public static int price(Holding h) {
        return 40 + 20 * Math.max(1, h.tier);
    }

    public static boolean claimed(Holding h) {
        return !h.heir.isEmpty();
    }

    /** What the War Table says about a claim: empty when there's none. */
    public static String line(HoldingManager mgr, Holding h) {
        if (!claimed(h)) return "";
        return h.heir + ", son of " + h.heirFather + ", claims " + h.name + ". His attacks come sooner and stronger than the house's; "
                + "kill him at your walls, throw him back " + (GIVE_UP - h.heirBeaten) + " more " + (GIVE_UP - h.heirBeaten == 1 ? "time" : "times")
                + ", or buy out his claim for " + price(h) + " emeralds.";
    }

    /** Raids on a claimed castle come sooner. */
    static long delay(ServerLevel level, Holding h) {
        long d = HoldingEvents.raidDelay(level);
        return claimed(h) ? (long) (d * SOONER) : d;
    }

    /** He rides with the raid, with more knights. */
    static void lead(Holding h, Campaign c) {
        if (!claimed(h)) return;
        c.heir = h.heir;
        c.add(Role.KNIGHT, MORE_KNIGHTS);
    }

    /** The raid's captain is the heir himself. */
    static void mark(GarrisonEntity g, Campaign c) {
        g.setBaseName(c.heir);
        g.getPersistentData().putString(TAG, c.heir);
    }

    public static boolean isHeir(GarrisonEntity g) {
        return g.getPersistentData().contains(TAG);
    }

    static void onTransfer(ServerLevel level, HoldingManager mgr, Holding h, String oldSide, String newSide, boolean peaceful) {
        UUID player = Sides.playerId(newSide);
        if (Sides.isHouse(oldSide) && player != null) {
            // taken from a house by force: the lord's son swears to have it back
            String hid = Sides.houseId(oldSide);
            if (peaceful || hid == null || hid.equals("outlaws") || h.isTown()) {
                clear(h);
                return;
            }
            House house = mgr.house(hid);
            h.heir = "Ser " + NameGen.first(level.random) + " " + house.name;
            h.heirFather = h.lordName.isEmpty() ? "its last lord" : "the late " + h.lordName;
            h.heirBeaten = 0;
            h.nextRaid = level.getGameTime() + (long) (HoldingEvents.raidDelay(level) * SOONER);
            mgr.setDirty();
            HoldingEvents.tell(level, player, Component.literal(h.heir + ", son of " + h.heirFather + ", swears to take back " + h.name
                    + ". He'll come sooner than the house would and with more men. Kill him at your walls, throw him back " + GIVE_UP
                    + " times, or buy out his claim on the War Table (" + price(h) + " emeralds).").withStyle(ChatFormatting.GOLD));
            Chronicle.log(level, mgr, Chronicle.HOUSES, h.heir + " swears to win back " + h.name + ".", player);
            return;
        }
        if (Sides.isPlayer(oldSide) && claimed(h) && Sides.isHouse(newSide) && newSide.equals(Sides.house(h.houseId))) {
            // he did it
            String lord = "Lord " + h.heir.replaceFirst("^Ser ", "");
            h.lordName = lord;
            h.lordAlive = true;
            UUID was = Sides.playerId(oldSide);
            HoldingEvents.tell(level, was, Component.literal(h.heir + " has taken back his father's castle: " + lord + " holds "
                    + h.name + ".").withStyle(ChatFormatting.RED));
            Chronicle.log(level, mgr, Chronicle.HOUSES, h.heir + " wins back " + h.name + " and is its lord.", was);
            clear(h);
            mgr.setDirty();
            return;
        }
        if (!Sides.isPlayer(newSide)) clear(h);
    }

    /** An attack he led was thrown back. */
    public static void beaten(ServerLevel level, HoldingManager mgr, Holding h, Campaign c) {
        if (c.heir.isEmpty() || !c.heir.equals(h.heir)) return;
        h.heirBeaten++;
        mgr.setDirty();
        if (h.heirBeaten >= GIVE_UP) {
            HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold(h.heir + " is thrown back from " + h.name + " for the "
                    + GIVE_UP + (GIVE_UP == 3 ? "rd" : "th") + " time and gives up his claim. He takes ship across the sea."));
            Chronicle.log(level, mgr, Chronicle.WAR, h.heir + " gives up his claim on " + h.name + ".", h.ownerPlayer());
            clear(h);
            return;
        }
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(h.heir + " is thrown back from " + h.name + " (" + h.heirBeaten + " of "
                + GIVE_UP + "). He'll be back.").withStyle(ChatFormatting.GOLD));
    }

    /** He fell before the walls. */
    static void slain(ServerLevel level, HoldingManager mgr, Campaign c, GarrisonEntity g) {
        Holding h = c.target == null ? null : mgr.holdings.get(c.target);
        if (h == null || !claimed(h) || !h.heir.equals(c.heir)) return;
        HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold(h.heir + " falls before the walls of " + h.name + ". "
                + mgr.house(h.houseId).title() + "'s claim on it dies with him."));
        Chronicle.log(level, mgr, Chronicle.WAR, h.heir + " falls before " + h.name + "; his claim dies with him.", h.ownerPlayer());
        c.heir = "";
        clear(h);
        mgr.setDirty();
    }

    /** Pay him to give it up. */
    public static boolean buyOut(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        if (!claimed(h)) {
            player.sendSystemMessage(Component.literal("Nobody claims " + h.name + ".").withStyle(ChatFormatting.GOLD));
            return false;
        }
        int price = price(h);
        int have = Stores.packPlusRealm(mgr, h, player, Items.EMERALD);
        if (have < price) {
            player.sendSystemMessage(Component.literal(h.heir + " wants " + price + " emeralds (40 and 20 a tier) to give up " + h.name
                    + "; you have " + have + ".").withStyle(ChatFormatting.RED));
            return false;
        }
        Stores.takeFromPackThenRealm(mgr, h, player, Items.EMERALD, price);
        String who = h.heir;
        Standing.change(level, mgr, h.houseId, player.getUUID(), 10, "you bought out " + who);
        player.sendSystemMessage(HoldingEvents.gold(who + " takes " + price + " emeralds and a manor over the hills, and gives up his claim on "
                + h.name + "."));
        Chronicle.log(level, mgr, Chronicle.HOUSES, who + " sells his claim on " + h.name + ".", player.getUUID());
        clear(h);
        mgr.setDirty();
        return true;
    }

    static void clear(Holding h) {
        h.heir = "";
        h.heirFather = "";
        h.heirBeaten = 0;
    }

    private Heirs() {
    }
}
