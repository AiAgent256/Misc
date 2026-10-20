package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * When one of your places is attacked, your other castles within 2000 blocks send men to hold it: each keeps two
 * knights and two archers at home and sends up to one knight more than its tier and half as many archers. They
 * stand with the garrison until the attack is over (fought out by numbers they count in its strength), then the
 * ones still standing ride home. Castles under attack themselves, and vassals (who send their own), don't.
 */
public final class Relief {
    public static final int RANGE = 2000, KEEP = 2;

    /** How many of a role a castle sends: up to {@code most}, keeping KEEP at home. */
    static int spare(Holding o, Role r, int most) {
        return Math.max(0, Math.min(most, o.count(r) - KEEP));
    }

    public static void send(ServerLevel level, HoldingManager mgr, Holding target) {
        UUID owner = target.ownerPlayer();
        if (owner == null) return;
        BlockPos at = target.rallyPos.equals(BlockPos.ZERO) ? target.center() : target.rallyPos;
        List<String> who = new ArrayList<>();
        for (Holding o : mgr.ownedBy(owner)) {
            if (o == target || Vassals.is(o) || target.relief.containsKey(o.id)) continue;
            if (Campaigns.active(mgr, o.id, Campaign.Kind.RAID) || Warbands.active(mgr, o.id)) continue;
            double dx = o.center().getX() - target.center().getX(), dz = o.center().getZ() - target.center().getZ();
            if (dx * dx + dz * dz > (double) RANGE * RANGE) continue;
            int k = spare(o, Role.KNIGHT, 1 + o.tier), a = spare(o, Role.ARCHER, (1 + o.tier) / 2);
            if (k + a == 0) continue;
            leave(level, o, Role.KNIGHT, k);
            leave(level, o, Role.ARCHER, a);
            arrive(level, mgr, target, Role.KNIGHT, k, at);
            arrive(level, mgr, target, Role.ARCHER, a, at);
            target.relief.put(o.id, new int[]{k, a});
            who.add(o.name + " " + k + (k == 1 ? " knight" : " knights") + (a > 0 ? " and " + a + (a == 1 ? " archer" : " archers") : ""));
        }
        if (who.isEmpty()) return;
        target.reliefAfter = new int[]{target.count(Role.KNIGHT), target.count(Role.ARCHER)};
        mgr.setDirty();
        HoldingEvents.tell(level, owner, HoldingEvents.gold("Your castles ride to the aid of " + target.name + ": " + String.join(", ", who)
                + ". They stand with its garrison till the attack's over, then go home."));
    }

    /** The attack's over: the men sent who are still standing ride home, in the share the garrison came through. */
    public static void over(ServerLevel level, HoldingManager mgr, Holding target) {
        if (target.relief.isEmpty()) return;
        UUID owner = target.ownerPlayer();
        double fk = target.reliefAfter[0] <= 0 ? 0 : Math.min(1.0, target.count(Role.KNIGHT) / (double) target.reliefAfter[0]);
        double fa = target.reliefAfter[1] <= 0 ? 0 : Math.min(1.0, target.count(Role.ARCHER) / (double) target.reliefAfter[1]);
        List<String> home = new ArrayList<>();
        for (Map.Entry<UUID, int[]> e : target.relief.entrySet()) {
            Holding o = mgr.holdings.get(e.getKey());
            if (o == null || owner == null || !owner.equals(o.ownerPlayer())) continue;      // home's gone: they stay
            int k = Math.min((int) Math.floor(e.getValue()[0] * fk), Math.max(0, target.count(Role.KNIGHT) - 1));
            int a = Math.min((int) Math.floor(e.getValue()[1] * fa), Math.max(0, target.count(Role.ARCHER) - 1));
            if (k + a == 0) continue;
            leave(level, target, Role.KNIGHT, k);
            leave(level, target, Role.ARCHER, a);
            o.add(Role.KNIGHT, k);
            o.add(Role.ARCHER, a);
            home.add((k + a) + " to " + o.name);
        }
        target.relief.clear();
        target.reliefAfter = new int[]{0, 0};
        mgr.setDirty();
        if (!home.isEmpty()) HoldingEvents.tell(level, owner, HoldingEvents.gold("The men who held " + target.name + " ride home: "
                + String.join(", ", home) + "."));
    }

    /** n of a role leave the castle: off the roll, and the men standing there if they're about. */
    static void leave(ServerLevel level, Holding h, Role r, int n) {
        if (n <= 0) return;
        int gone = 0;
        for (var e : new ArrayList<>(h.live.entrySet())) {
            if (gone >= n) break;
            if (e.getValue() != r) continue;
            h.live.remove(e.getKey());
            Entity ent = level.getEntity(e.getKey());
            if (ent instanceof GarrisonEntity g) {
                if (g.post() != null) h.usedPosts.remove(g.post());
                g.discard();
            }
            gone++;
        }
        h.add(r, -n);
    }

    private static void arrive(ServerLevel level, HoldingManager mgr, Holding h, Role r, int n, BlockPos at) {
        if (n <= 0) return;
        if (level.isLoaded(at)) Garrisons.reinforce(level, mgr, h, r, n, at, null);
        else h.add(r, n);
    }

    static CompoundTag save(Holding h) {
        CompoundTag t = new CompoundTag();
        h.relief.forEach((id, v) -> t.putIntArray(id.toString(), v));
        t.putIntArray("_after", h.reliefAfter);
        return t;
    }

    static void load(Holding h, CompoundTag t) {
        for (String k : t.getAllKeys()) {
            if (k.equals("_after")) {
                int[] a = t.getIntArray(k);
                if (a.length == 2) h.reliefAfter = a;
                continue;
            }
            try {
                int[] v = t.getIntArray(k);
                if (v.length == 2) h.relief.put(UUID.fromString(k), v);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private Relief() {
    }
}
