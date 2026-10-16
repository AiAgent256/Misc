package net.aiagent256.stormhold.holding;

import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

/**
 * What the realm makes of you. Knight with one castle, Lord with three, Warden with a great castle,
 * King with a city. Each rank lets you call more men out at once, and a King takes tribute from the
 * houses that count him a friend.
 */
public final class Titles {
    public static final int NONE = 0, KNIGHT = 1, LORD = 2, WARDEN = 3, KING = 4;

    public static int rank(List<Holding> mine) {
        if (mine.isEmpty()) return NONE;
        int rank = KNIGHT;
        if (mine.size() >= 3) rank = LORD;
        for (Holding h : mine) {
            if (h.tier >= 3 && !h.isTown()) rank = Math.max(rank, WARDEN);
            if (h.tier >= 4) rank = Math.max(rank, KING);
        }
        return rank;
    }

    public static int rank(HoldingManager mgr, UUID player) {
        return rank(mgr.ownedBy(player));
    }

    public static String name(int rank) {
        return switch (rank) {
            case KNIGHT -> "Knight";
            case LORD -> "Lord";
            case WARDEN -> "Warden";
            case KING -> "King";
            default -> "";
        };
    }

    /** How many men a muster may call out at once. */
    public static int musterCap(int rank) {
        return switch (rank) {
            case KNIGHT -> 10;
            case LORD -> 20;
            case WARDEN -> 40;
            case KING -> 80;
            default -> 5;
        };
    }

    public static String describe(int rank) {
        return switch (rank) {
            case KNIGHT -> "Knight: musters of ten.";
            case LORD -> "Lord: musters of twenty.";
            case WARDEN -> "Warden: musters of forty.";
            case KING -> "King: musters of eighty, and tribute from friendly houses.";
            default -> "No title yet. Take a castle.";
        };
    }

    /** Called when a title changes hands: tell the player. */
    public static void check(ServerLevel level, HoldingManager mgr, UUID player, int before) {
        int after = rank(mgr, player);
        if (after > before) {
            HoldingEvents.tell(level, player, HoldingEvents.gold("You are now a " + name(after) + " of the realm. " + describe(after)));
            Chronicle.log(level, mgr, Chronicle.NEWS, "You are named " + name(after) + ".", player);
        }
    }

    private Titles() {
    }
}
