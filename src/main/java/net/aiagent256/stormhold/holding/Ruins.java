package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Ruined castles: the old plans, left to the weather (see the ruin processor), squatted by brigands. No
 * lord, no people, no market, no fields; a handful of men on the ground, none on the broken walls. Clear
 * them out and raise your banner, and it's yours as it stands: a poor place, paying a quarter of the
 * rent. Set the Master Builder to Repair and he puts back everything the plans say is missing; when he's
 * done the castle is whole again, and from then on it's a castle like any other.
 */
public final class Ruins {
    /** A fresh ruin, as the detector finds it. */
    public static void found(Holding h, Layout l) {
        h.ruined = true;
        h.houseId = "outlaws";
        h.owner = Sides.OUTLAWS;
        h.lordAlive = false;
        h.renown = 1;
        h.bonusLeft = 0;
        h.villagersSpawned = true;
        h.surroundingsDone = true;
        h.fiefPlan = new ArrayList<>();
        groundPosts(h, l);
        h.fillGarrison(1.0);
    }

    /** Only the posts on the ground: the walls they'd have stood on are down. */
    static void groundPosts(Holding h, Layout l) {
        int floor = h.box.minY() + l.ground;
        for (Role r : Role.values()) {
            List<BlockPos> list = h.posts.get(r);
            if (list == null) continue;
            if (r == Role.ARCHER || r == Role.CROSSBOWMAN) {
                list.clear();
                continue;
            }
            list.removeIf(p -> p.getY() > floor + 1);
        }
    }

    /** The Master Builder has put back all the plans call for: the castle stands whole again. */
    public static void restore(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.ruined) return;
        Layout l = h.plans();
        h.ruined = false;
        if (l != null) Detector.applyLayout(h, l, new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ()));
        h.surroundingsDone = false;
        h.fiefPlan = null;
        h.villagersSpawned = false;
        h.bannersDirty = true;
        mgr.setDirty();
        HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold(h.name + " stands whole again. Its walls can hold a full garrison, "
                + "people will come to its lands, and it pays a castle's rent."));
        Chronicle.log(level, mgr, Chronicle.GROWTH, h.name + " is raised from its ruins.", h.ownerPlayer());
    }

    /** What a ruin pays of a whole castle's rent. */
    public static final double RENT = 0.25;

    /** A ruin's garrison: a few men on the ground, no one on the walls. */
    public static int capacity(int base, Role role) {
        if (role == Role.ARCHER || role == Role.CROSSBOWMAN) return 0;
        if (role == Role.CAPTAIN) return Math.min(1, base);
        return Math.max(2, base * 2 / 5);
    }

    public static Component describe(Holding h) {
        return Component.literal("The ruins of " + h.name + ": brigands hold what's left of it.").withStyle(ChatFormatting.GRAY);
    }

    private Ruins() {
    }
}
