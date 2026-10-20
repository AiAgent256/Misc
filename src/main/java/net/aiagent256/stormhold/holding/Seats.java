package net.aiagent256.stormhold.holding;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * A lord's seat: the castle his realm is run from. While he's away, rents, dues, tolls and offers paid to him land
 * in its stores (while he's on they come to his purse, as ever); the realm's roads start from its gate; the Realm
 * map crowns it. His first castle is his seat until he names another on the War Table.
 */
public final class Seats {
    public static boolean set(ServerLevel level, HoldingManager mgr, Holding h, Player player) {
        if (!player.getUUID().equals(h.ownerPlayer())) return false;
        if (Vassals.is(h)) {
            player.sendSystemMessage(Component.literal(h.lordName + " holds " + h.name + " for you. Take it back into your own hands "
                    + "(Revoke) before you sit there.").withStyle(ChatFormatting.RED));
            return false;
        }
        Holding was = mgr.seatOf(player.getUUID());
        if (was == h) {
            player.sendSystemMessage(Component.literal(h.name + " is your seat already.").withStyle(ChatFormatting.GOLD));
            return false;
        }
        mgr.seats.put(player.getUUID(), h.id);
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(h.name + " is your seat" + (was != null ? " (it was " + was.name + ")" : "")
                + ". While you're away, rents, dues and tolls are paid into its stores; while you're on they come to your purse. "
                + "Your roads start from its gate, and the Realm map crowns it."));
        Chronicle.log(level, mgr, Chronicle.GROWTH, "The lord's seat is now " + h.name + ".", player.getUUID());
        return true;
    }

    /** A lord's first castle is his seat. */
    static void firstCastle(HoldingManager mgr, Holding h) {
        java.util.UUID p = h.ownerPlayer();
        if (p == null || mgr.seatOf(p) != null) return;
        if (mgr.ownedBy(p).size() == 1) {
            mgr.seats.put(p, h.id);
            mgr.setDirty();
        }
    }

    public static boolean is(HoldingManager mgr, Holding h) {
        java.util.UUID p = h.ownerPlayer();
        return p != null && h.id.equals(mgr.seats.get(p));
    }

    private Seats() {
    }
}
