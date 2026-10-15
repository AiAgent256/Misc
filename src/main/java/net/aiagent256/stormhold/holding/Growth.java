package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * A realm that is fed and at peace grows on its own, a little each day: the fief raises a new building
 * every few days up to the castle's size, the Master Builder adds a stage of fortification every twelve,
 * the garrison trains up to capacity from the stores (see Economy). A raid sets the clock back.
 */
public final class Growth {
    private static final ResourceLocation BREAD = new ResourceLocation("minecraft", "bread");
    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "iron_ingot");
    public static final int BUILDING_EVERY = 3, FORTIFY_EVERY = 12;

    /** How many buildings (fields aside) a fief of this tier grows to on its own. */
    public static int fiefCap(int tier) {
        return tier >= 3 ? 15 : tier == 2 ? 9 : 3;
    }

    static void daily(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!h.isPlayerOwned()) return;
        java.util.UUID owner = h.ownerPlayer();
        if (owner == null) return;
        boolean besieged = Campaigns.active(mgr, h.id, Campaign.Kind.RAID) || now - h.lastRaidAt < 24000;
        boolean fed = Stores.realmStock(mgr, h, owner, BREAD) >= 16;
        if (besieged || !fed) {
            h.growth = Math.max(0, h.growth - 1);
            return;
        }
        h.growth++;
        int buildings = 0;
        for (Holding.FiefBuilding b : h.fief) if (!b.piece().equals("field") && !b.piece().equals("orchard")) buildings++;
        boolean planned = h.fiefPlan != null && !h.fiefPlan.isEmpty();
        if (h.growth % BUILDING_EVERY == 0 && !planned && buildings < fiefCap(h.tier) && h.fiefPlan != null
                && Stores.realmStock(mgr, h, owner, BREAD) >= 32 && Stores.realmStock(mgr, h, owner, IRON) >= 8) {
            Stores.take(mgr, h, owner, BREAD, 32);
            Stores.take(mgr, h, owner, IRON, 8);
            String next = Fief.nextExtra(h);
            h.fiefPlan.add(next);
            HoldingEvents.tell(level, owner, HoldingEvents.gold("The people of " + h.name + " are raising a " + next.replace('_', ' ')
                    + "; the " + h.fiefName().toLowerCase() + " grows."));
        }
        if (h.growth % FORTIFY_EVERY == 0 && h.fortify < Builder.MAX_FORTIFY && h.work.equals(Builder.NONE)) {
            int[] cost = Builder.fortifyCost(h.fortify + 1);
            if (Builder.stone(mgr, h) >= cost[0] && Stores.realmStock(mgr, h, owner, IRON) >= cost[1]) {
                var player = level.getServer().getPlayerList().getPlayer(owner);
                if (player != null) Builder.order(level, mgr, h, Builder.FORTIFY, player);
            }
        }
        mgr.setDirty();
    }

    private Growth() {
    }
}
