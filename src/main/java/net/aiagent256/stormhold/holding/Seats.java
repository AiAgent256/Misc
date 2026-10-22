package net.aiagent256.stormhold.holding;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * A lord's seat: the castle his realm is run from. Rents, dues, tolls and purses go into the treasury chest beside its
 * War Table (see {@link Rewards#pay}); what won't fit, or comes while it isn't loaded, lands in its stores,
 * and the council's wages come out of them first. The realm's roads start from its gate; the Realm map crowns it.
 * His first castle is his seat until he names another on the War Table.
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
                + ". Tribute, envoys' purses and anything paid to you while you're away go into its stores, and wages come out of them "
                + "first. Rents, tolls and purses go into the treasury chest beside its War Table. Your roads start from its gate, and the Realm map crowns it."));
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

    /** A lord with castles and no seat (his castles were his before there were seats): his largest castle of his own is it. */
    public static boolean ensure(ServerLevel level, HoldingManager mgr, java.util.UUID player) {
        if (mgr.seatOf(player) != null) return false;
        Holding best = null;
        for (Holding h : mgr.ownedBy(player)) if (!Vassals.is(h) && (best == null || h.troops() > best.troops())) best = h;
        if (best == null) return false;
        mgr.seats.put(player, best.id);
        mgr.setDirty();
        HoldingEvents.tell(level, player, HoldingEvents.gold(best.name + " is your seat, the largest castle you hold in your own hands. "
                + "Tribute and anything paid to you while you're away go into its stores, and wages come out of them first. "
                + "Name another on the Overview (Make this my seat)."));
        return true;
    }

    public static boolean is(HoldingManager mgr, Holding h) {
        java.util.UUID p = h.ownerPlayer();
        return p != null && h.id.equals(mgr.seats.get(p));
    }

    private Seats() {
    }
}
