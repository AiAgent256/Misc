package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The roads your builders lay between your places carry trade, and trade pays tolls: every morning, an
 * emerald for every 150 blocks of road (at least two a road), with the rents. Now and then brigands camp
 * on a long road; while they sit there the tolls on it stop. Clear them out and the merchants pay you ten
 * for the road being open again.
 */
public final class Tolls {
    public static final int BLOCKS_PER_EMERALD = 150, MIN_TOLL = 2, CLEARED_BONUS = 10, BRIGAND_ROAD = 300;
    public static final double BRIGAND_CHANCE = 0.08;

    public static int toll(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.max(MIN_TOLL, (int) (Math.sqrt(dx * dx + dz * dz) / BLOCKS_PER_EMERALD));
    }

    private static boolean campStands(HoldingManager mgr, UUID id) {
        for (Campaign c : mgr.campaigns) if (c.id.equals(id) && c.stage != Campaign.Stage.DONE) return true;
        return false;
    }

    /** At dawn: the day's tolls, the roads cleared and the roads newly blocked. The account's line, or null. */
    @Nullable
    public static Component collect(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine) {
        int total = 0, roads = 0;
        List<String> blocked = new ArrayList<>(), cleared = new ArrayList<>(), camped = new ArrayList<>();
        for (Holding h : mine) {
            for (UUID otherId : new ArrayList<>(h.roadsTo)) {
                Holding o = mgr.holdings.get(otherId);
                if (o == null || !player.equals(o.ownerPlayer())) continue;
                String road = h.name + " to " + o.name;
                UUID camp = h.roadCamps.get(otherId);
                if (camp != null) {
                    if (campStands(mgr, camp)) {
                        blocked.add(road);
                        continue;
                    }
                    h.roadCamps.remove(otherId);
                    cleared.add(road);
                    total += CLEARED_BONUS;
                }
                BlockPos a = Campaigns.gateOutside(h), b = Campaigns.gateOutside(o);
                total += toll(a, b);
                roads++;
                double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
                if (Math.sqrt(dx * dx + dz * dz) >= BRIGAND_ROAD && level.random.nextDouble() < BRIGAND_CHANCE) {
                    Campaign c = camp(level, mgr, h, a, b);
                    h.roadCamps.put(otherId, c.id);
                    camped.add(road + " near " + c.to.getX() + ", " + c.to.getZ());
                }
            }
        }
        if (roads == 0 && blocked.isEmpty()) return null;
        mgr.setDirty();
        boolean pack = total > 0 && Rewards.pay(level, mgr, player, total, mine.get(0)) > 0;
        StringBuilder s = new StringBuilder("Road tolls: " + total + " emeralds from " + roads + (roads == 1 ? " road" : " roads")
                + (total > 0 ? (pack ? " to your purse." : " to the stores.") : "."));
        if (!cleared.isEmpty()) s.append(" The merchants thank you for clearing ").append(String.join(", ", cleared)).append(".");
        if (!blocked.isEmpty()) s.append(" No tolls while brigands hold ").append(String.join(", ", blocked)).append(".");
        if (!camped.isEmpty()) s.append(" Brigands have camped on the road from ").append(String.join("; ", camped)).append("!");
        return Component.literal(s.toString()).withStyle(camped.isEmpty() && blocked.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.GOLD);
    }

    /** A band of brigands astride the road, somewhere along its middle. */
    static Campaign camp(ServerLevel level, HoldingManager mgr, Holding h, BlockPos a, BlockPos b) {
        double t = 0.3 + level.random.nextDouble() * 0.4;
        BlockPos at = new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * t), h.center().getY(),
                (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
        Campaign c = new Campaign();
        c.kind = Campaign.Kind.CAMP;
        c.side = Sides.OUTLAWS;
        c.origin = h.id;
        c.from = at;
        c.to = at;
        c.stage = Campaign.Stage.SIEGE;
        c.siegeUntil = Long.MAX_VALUE;
        c.tier = 2;
        c.renown = Frontier.baseLevel(level, h);
        c.add(Role.CAPTAIN, 1);
        c.add(Role.KNIGHT, 4 + level.random.nextInt(3));
        c.add(Role.ARCHER, 1);
        c.add(Role.CROSSBOWMAN, 1);
        mgr.campaigns.add(c);
        mgr.setDirty();
        return c;
    }

    private Tolls() {
    }
}
