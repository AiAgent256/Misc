package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Vassals: a castle, town or city you hold, granted to one of its knights to rule in your name. He sits
 * in the hall as its lord and fights for it, keeps its garrison up out of his own purse, rides to your
 * other places with his knights when they're attacked, and pays you a share of its rents that grows with
 * his loyalty. Loyalty creeps up in peace, rises with gifts and when you fight beside him, and falls when
 * his place is sacked. Let it run out and he breaks faith: the castle is his, under a house of his own,
 * and an enemy.
 */
public final class Vassals {
    public static final int START = 70, GIFT = 32, GIFT_LOYALTY = 12, DAILY = 2, LOW = 25, HELP_RANGE = 2000;

    public static boolean is(Holding h) {
        return h.isPlayerOwned() && h.vassal != null && !h.vassal.isEmpty();
    }

    /** Whether the place answers its lord's call to arms: a castle of his own always; a vassal while his loyalty holds (LOW or more). */
    public static boolean answers(Holding h) {
        return !is(h) || h.loyalty >= LOW;
    }

    /** Why a vassal keeps his men at home, for a line on the War Table. */
    public static String refusal(Holding h) {
        return h.lordName + " keeps the men of " + h.name + " at home: his loyalty is " + h.loyalty + ", and he answers at " + LOW + ".";
    }

    /** The share of the rents he sends you: a third at the lowest, four fifths at the most loyal. */
    public static double share(Holding h) {
        return 0.3 + 0.5 * Math.max(0, Math.min(100, h.loyalty)) / 100.0;
    }

    public static String title(Holding h) {
        return h.isTown() ? "Reeve " : h.tier >= 3 ? "Lord " : Holding.lordTitle(h.tier);
    }

    /** Raise one of the garrison's knights to hold the place in your name. */
    public static boolean grant(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        if (!player.getUUID().equals(h.ownerPlayer())) return false;
        if (is(h)) {
            player.sendSystemMessage(Component.literal(h.lordName + " already holds " + h.name + " for you.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (h.count(Role.KNIGHT) < 1) {
            player.sendSystemMessage(Component.literal(h.name + " has no knight to raise. Send it one first.").withStyle(ChatFormatting.RED));
            return false;
        }
        h.add(Role.KNIGHT, -1);
        h.vassal = NameGen.first(level.random);
        h.loyalty = START;
        h.lordAlive = true;
        h.lordName = title(h) + h.vassal + " of " + h.name;
        h.mode = Holding.Mode.DEFEND;
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(h.lordName + " kneels and swears to hold " + h.name + " in your name. He defends it, keeps "
                + "its garrison up from his own purse, rides to your other places with his knights when they're attacked, and sends you "
                + Math.round(share(h) * 100) + "% of its rents while his loyalty stays where it is (" + h.loyalty + ")."));
        Chronicle.log(level, mgr, Chronicle.GROWTH, h.lordName + " is made lord of " + h.name + ".", player.getUUID());
        return true;
    }

    /** Take it back into your own hands: he returns to the ranks. */
    public static boolean revoke(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        if (!is(h) || !player.getUUID().equals(h.ownerPlayer())) return false;
        String was = h.lordName;
        dismissLord(level, h);
        h.vassal = null;
        h.lordAlive = false;
        h.add(Role.KNIGHT, 1);
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(was + " gives back the keys of " + h.name + " and takes his place in the ranks again."));
        return true;
    }

    /** A purse for him: loyalty up. From your pack first, then the realm's stores. */
    public static boolean gift(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        if (!is(h) || !player.getUUID().equals(h.ownerPlayer())) return false;
        int have = player.getInventory().countItem(Items.EMERALD) + Stores.realmStock(mgr, h, player.getUUID(), Rewards.EMERALD);
        if (have < GIFT) {
            player.sendSystemMessage(Component.literal("A gift fit for a lord is " + GIFT + " emeralds; you and the realm have " + have + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        Stores.takeFromPackThenRealm(mgr, h, player, Items.EMERALD, GIFT);
        h.loyalty = Math.min(100, h.loyalty + GIFT_LOYALTY);
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(h.lordName + " takes your gift of " + GIFT + " emeralds. Loyalty " + h.loyalty + "."));
        return true;
    }

    /** Once a day: loyalty creeps up in peace. */
    public static void daily(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!is(h)) return;
        h.loyalty = Math.min(100, h.loyalty + DAILY);
        mgr.setDirty();
    }

    /** Something that costs his loyalty. At nothing, he breaks faith. */
    public static void slight(ServerLevel level, HoldingManager mgr, Holding h, int by, String why) {
        if (!is(h)) return;
        h.loyalty -= by;
        mgr.setDirty();
        if (h.loyalty <= 0) {
            revolt(level, mgr, h);
            return;
        }
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(h.lordName + " is ill-pleased: " + why + ". Loyalty " + h.loyalty
                + (h.loyalty < LOW ? ". He sends no men and little rent until it's mended." : ".")).withStyle(ChatFormatting.GOLD));
    }

    /** Something that wins it. */
    public static void favour(ServerLevel level, HoldingManager mgr, Holding h, int by, String why) {
        if (!is(h)) return;
        h.loyalty = Math.min(100, h.loyalty + by);
        mgr.setDirty();
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(h.lordName + " won't forget it: " + why + ". Loyalty " + h.loyalty + ".")
                .withStyle(ChatFormatting.GREEN));
    }

    /** He breaks faith: the place becomes his, under a house of his own, and an enemy of yours. */
    public static void revolt(ServerLevel level, HoldingManager mgr, Holding h) {
        UUID lord = h.ownerPlayer();
        String name = h.vassal;
        String id = "rebel_" + h.id.toString().substring(0, 8);
        House house = mgr.house(id);
        house.disposition = -60;
        h.vassal = null;
        HoldingEvents.transfer(level, mgr, h, Sides.house(id), true);
        h.lordAlive = true;
        h.lordName = "Lord " + name + " " + house.name;
        mgr.setDirty();
        level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(name + " has broken faith and holds " + h.name
                + " against his lord as " + h.lordName + "!").withStyle(ChatFormatting.DARK_RED), false);
        Chronicle.log(level, mgr, Chronicle.WAR, name + " breaks faith and holds " + h.name + " against you.", lord);
    }

    /** One of your places is attacked: every loyal vassal within reach sends knights to it. */
    public static void help(ServerLevel level, HoldingManager mgr, Holding target) {
        UUID owner = target.ownerPlayer();
        if (owner == null) return;
        BlockPos at = target.rallyPos.equals(BlockPos.ZERO) ? target.center() : target.rallyPos;
        for (Holding v : mgr.holdings.values()) {
            if (v == target || !is(v) || !owner.equals(v.ownerPlayer()) || v.loyalty < LOW) continue;
            double dx = v.center().getX() - target.center().getX(), dz = v.center().getZ() - target.center().getZ();
            if (dx * dx + dz * dz > (double) HELP_RANGE * HELP_RANGE) continue;
            int n = Math.min(2 + v.tier, v.count(Role.KNIGHT) - 2);
            if (n <= 0) continue;
            v.add(Role.KNIGHT, -n);
            if (level.isLoaded(at)) Garrisons.reinforce(level, mgr, target, Role.KNIGHT, n, at, null);
            else target.add(Role.KNIGHT, n);
            HoldingEvents.tell(level, owner, HoldingEvents.gold(v.lordName + " sends " + n + " knights from " + v.name + " to hold "
                    + target.name + " with you."));
            mgr.setDirty();
        }
    }

    /** His own lord dies in the fighting: the place comes back into your hands. */
    public static void fallen(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!is(h)) return;
        HoldingEvents.tell(level, h.ownerPlayer(), Component.literal(h.lordName + ", your vassal at " + h.name + ", has fallen. " + h.name
                + " is in your own hands again.").withStyle(ChatFormatting.RED));
        Chronicle.log(level, mgr, Chronicle.WAR, h.lordName + " falls defending " + h.name + ".", h.ownerPlayer());
        h.vassal = null;
        h.lordAlive = false;
        mgr.setDirty();
    }

    /** The account's line on a vassal whose loyalty is slipping, or null. */
    @Nullable
    public static Component warning(Holding h) {
        if (!is(h) || h.loyalty >= 35) return null;
        return Component.literal(h.lordName + " at " + h.name + " grows restless (loyalty " + h.loyalty + "). A gift at the War Table mends it.")
                .withStyle(ChatFormatting.GOLD);
    }

    private static void dismissLord(ServerLevel level, Holding h) {
        for (var e : new java.util.ArrayList<>(h.live.entrySet())) {
            if (e.getValue() != Role.LORD) continue;
            Entity ent = level.getEntity(e.getKey());
            if (ent instanceof GarrisonEntity) ent.discard();
            h.live.remove(e.getKey());
        }
    }

    private Vassals() {
    }
}
