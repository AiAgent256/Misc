package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player's company is doing right now. One order per player, shared by every man in the company:
 * follow and defend, attack anything hostile nearby, hold a spot, or clear a building. On top of that a
 * single named target can be marked, which every man goes for. Orders live in memory only; after a
 * restart the company is back to following.
 */
public final class Orders {
    public enum Mode { FOLLOW, AGGRESSIVE, HOLD, CLEAR }

    public static final class Order {
        public Mode mode = Mode.FOLLOW;
        @Nullable public BlockPos pos;
        @Nullable public UUID target;
        public long since;
        int quiet;
        int lastLeft = -1;
    }

    public static final int CLEAR_XZ = 14, CLEAR_Y = 7;
    public static final double FOLLOW_RANGE = 14, AGGRESSIVE_RANGE = 24, HOLD_RANGE = 12;
    /** How far from the player a man still counts as in the company for orders. */
    public static final int COMPANY_RANGE = 160;
    private static final long CLEAR_TIMEOUT = 20 * 180;

    private static final Map<UUID, Order> ORDERS = new ConcurrentHashMap<>();

    public static Order of(UUID player) {
        return ORDERS.computeIfAbsent(player, k -> new Order());
    }

    @Nullable
    public static Order peek(@Nullable UUID player) {
        return player == null ? null : ORDERS.get(player);
    }

    public static Mode mode(@Nullable UUID player) {
        Order o = peek(player);
        return o == null ? Mode.FOLLOW : o.mode;
    }

    public static AABB clearArea(BlockPos p) {
        return new AABB(p).inflate(CLEAR_XZ, CLEAR_Y, CLEAR_XZ);
    }

    /** The player's own soldiers within reach of an order. */
    public static List<GarrisonEntity> company(ServerLevel level, Player player) {
        return level.getEntitiesOfClass(GarrisonEntity.class, player.getBoundingBox().inflate(COMPANY_RANGE),
                g -> g.isAlive() && player.getUUID().equals(g.owner()));
    }

    /** Where this man stands when holding or sweeping {@code pos}: spread out in a loose square. */
    public static BlockPos spot(GarrisonEntity man, BlockPos pos) {
        int k = Math.floorMod(man.getUUID().hashCode(), 9);
        BlockPos p = pos.offset((k % 3 - 1) * 2, 0, (k / 3 - 1) * 2);
        if (man.level() instanceof ServerLevel sl) {
            BlockPos free = Garrisons.standable(sl, p);
            if (free != null) return free;
        }
        return pos;
    }

    /**
     * May this man pick {@code e} as a target on his own, given his company's order? Keeps a following
     * company close to its lord instead of chasing every zombie in sight.
     */
    public static boolean inScope(GarrisonEntity man, LivingEntity e) {
        UUID owner = man.owner();
        if (owner == null) return true;
        Order o = ORDERS.get(owner);
        if (o != null && o.target != null && o.target.equals(e.getUUID())) return true;
        Mode m = o == null ? Mode.FOLLOW : o.mode;
        Player p = man.ownerPlayer();
        return switch (m) {
            case CLEAR -> o.pos != null && clearArea(o.pos).contains(e.position());
            case HOLD -> o.pos != null && e.distanceToSqr(Vec3.atCenterOf(o.pos)) < HOLD_RANGE * HOLD_RANGE;
            case AGGRESSIVE -> p != null && e.distanceToSqr(p) < AGGRESSIVE_RANGE * AGGRESSIVE_RANGE;
            default -> p != null ? e.distanceToSqr(p) < FOLLOW_RANGE * FOLLOW_RANGE : e.distanceToSqr(man) < 100;
        };
    }

    // ------------------------------------------------------------ commands

    private static void say(Player p, String msg, ChatFormatting color) {
        p.displayClientMessage(Component.literal(msg).withStyle(color), true);
    }

    public static int attack(ServerLevel level, Player player, LivingEntity target) {
        List<GarrisonEntity> men = company(level, player);
        if (men.isEmpty()) {
            say(player, "You have no men with you.", ChatFormatting.RED);
            return 0;
        }
        Order o = of(player.getUUID());
        o.target = target.getUUID();
        int n = 0;
        for (GarrisonEntity m : men) {
            if (m == target) continue;
            m.setTarget(target);
            n++;
        }
        level.playSound(null, player.blockPosition(), SoundEvents.PILLAGER_CELEBRATE, SoundSource.PLAYERS, 0.6F, 0.8F);
        say(player, n + " men go for " + target.getDisplayName().getString() + "!", ChatFormatting.GOLD);
        return n;
    }

    public static int clear(ServerLevel level, Player player, BlockPos pos) {
        List<GarrisonEntity> men = company(level, player);
        if (men.isEmpty()) {
            say(player, "You have no men with you.", ChatFormatting.RED);
            return 0;
        }
        Order o = of(player.getUUID());
        o.mode = Mode.CLEAR;
        o.pos = pos;
        o.target = null;
        o.since = level.getGameTime();
        o.quiet = 0;
        o.lastLeft = -1;
        for (GarrisonEntity m : men) m.setTarget(null);
        level.playSound(null, player.blockPosition(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(1).value(), SoundSource.PLAYERS, 0.7F, 1.0F);
        say(player, men.size() + " men move to clear it.", ChatFormatting.GOLD);
        return men.size();
    }

    public static int hold(ServerLevel level, Player player, BlockPos pos) {
        List<GarrisonEntity> men = company(level, player);
        if (men.isEmpty()) {
            say(player, "You have no men with you.", ChatFormatting.RED);
            return 0;
        }
        Order o = of(player.getUUID());
        o.mode = Mode.HOLD;
        o.pos = pos;
        o.target = null;
        o.since = level.getGameTime();
        for (GarrisonEntity m : men) m.setTarget(null);
        say(player, men.size() + " men hold at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".", ChatFormatting.GOLD);
        return men.size();
    }

    /** Fall in: drop everything and come back to me. */
    public static int fallIn(ServerLevel level, Player player) {
        List<GarrisonEntity> men = company(level, player);
        Order o = of(player.getUUID());
        o.mode = Mode.FOLLOW;
        o.pos = null;
        o.target = null;
        for (GarrisonEntity m : men) m.setTarget(null);
        level.playSound(null, player.blockPosition(), SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0).value(), SoundSource.PLAYERS, 0.6F, 1.2F);
        say(player, men.isEmpty() ? "You have no men with you." : "Fall in! " + men.size() + " men return to you.",
                men.isEmpty() ? ChatFormatting.RED : ChatFormatting.GOLD);
        return men.size();
    }

    /** Switch between defending the lord and attacking anything hostile near him. */
    public static Mode toggleStance(ServerLevel level, Player player) {
        Order o = of(player.getUUID());
        o.mode = o.mode == Mode.AGGRESSIVE ? Mode.FOLLOW : Mode.AGGRESSIVE;
        o.pos = null;
        int n = company(level, player).size();
        say(player, o.mode == Mode.AGGRESSIVE
                ? "Aggressive: your " + n + " men attack anything hostile within " + (int) AGGRESSIVE_RANGE + " blocks of you."
                : "Follow: your " + n + " men stay close and defend you.", ChatFormatting.GOLD);
        return o.mode;
    }

    // ------------------------------------------------------------ once a second

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (var e : ORDERS.entrySet()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null || p.level() != level) continue;
            Order o = e.getValue();
            if (o.target != null) {
                var t = level.getEntity(o.target);
                if (!(t instanceof LivingEntity le) || !le.isAlive() || le.distanceToSqr(p) > 64 * 64) {
                    if (t instanceof LivingEntity le2 && !le2.isAlive()) say(p, le2.getDisplayName().getString() + " is down.", ChatFormatting.GOLD);
                    o.target = null;
                }
            }
            if (o.mode == Mode.CLEAR && o.pos != null) tickClear(level, p, o, now);
        }
    }

    public static void tickClear(ServerLevel level, Player p, Order o, long now) {
        List<GarrisonEntity> men = company(level, p);
        if (men.isEmpty()) {
            o.mode = Mode.FOLLOW;
            return;
        }
        GarrisonEntity rep = men.get(0);
        List<LivingEntity> foes = level.getEntitiesOfClass(LivingEntity.class, clearArea(o.pos), rep::isHostileTo);
        boolean reached = men.stream().anyMatch(m -> m.blockPosition().closerThan(o.pos, 7));
        if (foes.isEmpty()) {
            if (reached && ++o.quiet >= 3) {
                say(p, "The building is clear. Your men fall back in.", ChatFormatting.GREEN);
                level.playSound(null, p.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.PLAYERS, 0.6F, 1.0F);
                o.mode = Mode.FOLLOW;
                o.pos = null;
            } else if (now - o.since > CLEAR_TIMEOUT) {
                say(p, "Your men couldn't get in there. They fall back in.", ChatFormatting.RED);
                o.mode = Mode.FOLLOW;
                o.pos = null;
            }
            return;
        }
        o.quiet = 0;
        for (GarrisonEntity m : men) {
            LivingEntity t = m.getTarget();
            if (t != null && t.isAlive()) continue;
            foes.stream().min(Comparator.comparingDouble(f -> f.distanceToSqr(m))).ifPresent(m::setTarget);
        }
        if (foes.size() != o.lastLeft) {
            o.lastLeft = foes.size();
            say(p, "Clearing: " + foes.size() + " left.", ChatFormatting.GOLD);
        }
        if (now - o.since > CLEAR_TIMEOUT * 2) {
            say(p, "Your men can't finish the job. They fall back in.", ChatFormatting.RED);
            o.mode = Mode.FOLLOW;
            o.pos = null;
        }
    }

    public static void forget(UUID player) {
        ORDERS.remove(player);
    }

    private Orders() {
    }
}
