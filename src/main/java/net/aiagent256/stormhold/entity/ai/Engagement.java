package net.aiagent256.stormhold.entity.ai;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * How a fight spreads out. Left to itself every man goes for the nearest enemy, and a battle balls up around the first
 * two men to meet. Here a man picks the nearest enemy that hasn't already got {@link #MOST} of his own side on him
 * (bowmen let {@link #MOST_RANGED} share a mark), so ten against ten comes apart into ten fights; a man who finds
 * himself the third on one foe while another stands free goes for the free one; and men in the press step away from a
 * friend at their elbow rather than shoulder through him.
 */
public final class Engagement {
    /** Swordsmen of one side on a single enemy; past this a man looks for a foe of his own. */
    public static final int MOST = 2;
    /** Bowmen of one side on a single mark. */
    public static final int MOST_RANGED = 3;
    /** Blocks of extra distance a man counts for each of his side already on a foe. */
    static final double CROWD = 6.0;
    /** How far a man looks for friends to count, and for a free foe to change to. */
    static final double LOOK = 20.0;
    /** A friend this close (horizontally) is at his elbow. */
    static final double ELBOW = 1.6;

    /** The friends of {@code g} near him: same side, alive, not him. */
    public static List<GarrisonEntity> friends(GarrisonEntity g) {
        return g.level().getEntitiesOfClass(GarrisonEntity.class, g.getBoundingBox().inflate(LOOK, 8, LOOK),
                o -> o != g && o.isAlive() && !g.side().isEmpty() && g.side().equals(o.side()));
    }

    /** How many of {@code friends} are on {@code foe}. */
    public static int on(List<GarrisonEntity> friends, LivingEntity foe) {
        int n = 0;
        for (GarrisonEntity o : friends) if (o.getTarget() == foe) n++;
        return n;
    }

    public static int most(GarrisonEntity g) {
        return g.role().ranged ? MOST_RANGED : MOST;
    }

    /** Of {@code foes}, the one {@code g} should take: nearest, counting each friend already on it as {@link #CROWD} blocks more. */
    @Nullable
    public static LivingEntity pick(GarrisonEntity g, List<? extends LivingEntity> foes) {
        if (foes.isEmpty()) return null;
        List<GarrisonEntity> friends = friends(g);
        int most = most(g);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity f : foes) {
            if (f == null || !f.isAlive() || f == g) continue;
            int n = on(friends, f);
            double score = Math.sqrt(f.distanceToSqr(g)) + CROWD * n + (n >= most ? 1000 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    /**
     * Every second or so in a fight: a man who is one too many on his foe changes to the nearest foe with room, if
     * there's one within reach of a few strides. Not off a man who has just hit him.
     */
    public static void rebalance(GarrisonEntity g) {
        LivingEntity t = g.getTarget();
        if (t == null || !t.isAlive()) return;
        if (g.getLastHurtByMob() == t && g.tickCount - g.getLastHurtByMobTimestamp() < 60) return;
        List<GarrisonEntity> friends = friends(g);
        int most = most(g);
        int here = on(friends, t);
        if (here < most) return;
        List<LivingEntity> foes = g.level().getEntitiesOfClass(LivingEntity.class, g.getBoundingBox().inflate(LOOK * 0.75, 6, LOOK * 0.75),
                e -> e != t && e.isAlive() && g.isHostileTo(e) && g.getSensing().hasLineOfSight(e));
        LivingEntity free = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity f : foes) {
            int n = on(friends, f);
            if (n >= most || n >= here) continue;
            double d = f.distanceToSqr(g) + CROWD * CROWD * n;
            if (d < bd) {
                bd = d;
                free = f;
            }
        }
        if (free != null) g.setTarget(free);
    }

    /** A gentle push away from friends at his elbow, horizontally. Zero when there's room. */
    public static Vec3 elbowRoom(GarrisonEntity g) {
        double px = 0, pz = 0;
        for (GarrisonEntity o : g.level().getEntitiesOfClass(GarrisonEntity.class, g.getBoundingBox().inflate(ELBOW, 1, ELBOW),
                o -> o != g && o.isAlive() && g.side().equals(o.side()))) {
            double dx = g.getX() - o.getX(), dz = g.getZ() - o.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= ELBOW) continue;
            if (d < 1.0E-3) {
                dx = g.getRandom().nextDouble() - 0.5;
                dz = g.getRandom().nextDouble() - 0.5;
                d = Math.max(1.0E-3, Math.sqrt(dx * dx + dz * dz));
            }
            double w = (ELBOW - d) / ELBOW;
            px += dx / d * w;
            pz += dz / d * w;
        }
        double len = Math.sqrt(px * px + pz * pz);
        if (len < 1.0E-4) return Vec3.ZERO;
        double s = Math.min(0.06, 0.04 * len) / len;
        return new Vec3(px * s, 0, pz * s);
    }

    private Engagement() {
    }
}
