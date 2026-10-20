package net.aiagent256.stormhold.house;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * How a house feels about a player, -100 to 100. A house's base disposition is rolled when it is
 * created (about a third start friendly); everything you do to that house moves your own number.
 * Above {@link #HOSTILE} its gate opens, its guards leave you alone unless you strike first, and its
 * lord trades; above {@link #FRIENDLY} it also hires you soldiers and a fallen castle can be taken by
 * bending the knee. At {@link #HOSTILE} or below its gate stays down and its men attack on sight.
 */
public final class Standing {
    public static final int FRIENDLY = 25;
    public static final int HOSTILE = -10;
    public static final int MAX = 100, MIN = -100;

    public static final int HIT_GUARD = -40;
    public static final int KILLED_GUARD = -25;
    public static final int TOOK_CASTLE = -80;
    public static final int RAIDED_THEM = -30;
    public static final int KILLED_ENEMY_NEAR_CASTLE = 2;
    public static final int SUPPLIED_CASTLE = 10;
    public static final int DAILY_DRIFT = 1;

    public static int get(HoldingManager mgr, String houseId, UUID player) {
        House h = mgr.house(houseId);
        Map<UUID, Integer> m = mgr.standing.get(houseId);
        int personal = m == null ? 0 : m.getOrDefault(player, 0);
        return clamp(h.disposition + personal);
    }

    public static boolean friendly(HoldingManager mgr, String houseId, UUID player) {
        return get(mgr, houseId, player) >= FRIENDLY;
    }

    /** Hostile: its gate stays shut to you and its men attack on sight. Never while a truce holds. */
    public static boolean hostile(HoldingManager mgr, String houseId, UUID player) {
        if (net.aiagent256.stormhold.holding.Truces.active(mgr, houseId, player)) return false;
        return get(mgr, houseId, player) <= HOSTILE;
    }

    /** Friendly to this side? Only players have standing; houses and brigands never do. */
    public static boolean friendlyTo(HoldingManager mgr, String houseId, String side) {
        UUID p = Sides.playerId(side);
        return p != null && friendly(mgr, houseId, p);
    }

    public static String word(int v) {
        if (v >= 70) return "sworn friend";
        if (v >= FRIENDLY) return "friendly";
        if (v > HOSTILE) return "wary";
        if (v > -60) return "hostile";
        return "sworn enemy";
    }

    /** Move a player's standing with a house and tell them if it crossed a line. */
    public static void change(ServerLevel level, HoldingManager mgr, String houseId, @Nullable UUID player, int delta,
                              String reason) {
        if (player == null || delta == 0 || houseId.equals("outlaws")) return;
        int before = get(mgr, houseId, player);
        Map<UUID, Integer> m = mgr.standing.computeIfAbsent(houseId, k -> new java.util.HashMap<>());
        House h = mgr.house(houseId);
        int personal = m.getOrDefault(player, 0);
        personal = clamp(h.disposition + personal + delta) - h.disposition;
        m.put(player, personal);
        mgr.setDirty();
        int after = get(mgr, houseId, player);
        if (before == after) return;
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(player);
        if (sp == null) return;
        boolean crossed = (before >= FRIENDLY) != (after >= FRIENDLY) || (before <= HOSTILE) != (after <= HOSTILE);
        if (crossed) {
            ChatFormatting c = after > before ? ChatFormatting.GREEN : ChatFormatting.RED;
            sp.sendSystemMessage(Component.literal(h.title() + " now counts you a " + word(after) + " (" + reason + ").").withStyle(c));
        } else if (Math.abs(delta) >= 10) {
            sp.displayClientMessage(Component.literal(h.title() + ": " + (delta > 0 ? "+" : "") + delta + ", " + word(after))
                    .withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** Standing slowly returns toward the house's base disposition, one point a day. */
    public static void drift(HoldingManager mgr) {
        boolean dirty = false;
        for (Map<UUID, Integer> m : mgr.standing.values()) {
            for (Map.Entry<UUID, Integer> e : m.entrySet()) {
                int v = e.getValue();
                if (v == 0) continue;
                e.setValue(v > 0 ? v - DAILY_DRIFT : v + DAILY_DRIFT);
                dirty = true;
            }
        }
        if (dirty) mgr.setDirty();
    }

    private static int clamp(int v) {
        return Math.max(MIN, Math.min(MAX, v));
    }

    private Standing() {
    }
}
