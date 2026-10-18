package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * The men who ride with the player. Mustering takes soldiers out of a castle's garrison and puts them at the
 * player's side; dismissing sends the ones nearby back into a garrison. The company is made of Stormhold's own
 * soldiers, who take orders from the Marshal's Baton; the config can hand them to Villager Recruits instead.
 */
public final class Company {
    /** How close to a castle you must be to call its men to you, or send yours into it. */
    public static final int CALL_RANGE = 128;
    /** How far around the player "dismiss" reaches. */
    public static final int DISMISS_RANGE = 24;
    /** Each castle keeps at least this many of a role when you muster. */
    private static final int KEEP = 1;

    public static boolean inRange(Player player, Holding h) {
        BlockPos c = h.center();
        double dx = player.getX() - c.getX(), dz = player.getZ() - c.getZ();
        return dx * dx + dz * dz <= (double) CALL_RANGE * CALL_RANGE;
    }

    /** Call {@code n} soldiers of {@code role} from {@code h} to the player's side. Returns how many came. */
    public static int muster(ServerLevel level, HoldingManager mgr, Player player, Holding h, Role role, int n) {
        if (!inRange(player, h)) {
            player.sendSystemMessage(Component.literal("Your men at " + h.name + " are too far away to hear you. Ride within "
                    + CALL_RANGE + " blocks to gather them, or use Send to have a column ride to you.").withStyle(ChatFormatting.RED));
            return 0;
        }
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (h.count(role) <= KEEP) break;
            // one of the men standing in the castle leaves his post
            UUID leaving = null;
            for (var e : h.live.entrySet()) {
                if (e.getValue() == role) {
                    leaving = e.getKey();
                    break;
                }
            }
            GarrisonEntity standing = leaving != null && level.getEntity(leaving) instanceof GarrisonEntity g ? g : null;
            if (!arrive(level, mgr, player, h, role)) break;
            if (leaving != null) {
                h.live.remove(leaving);
                if (standing != null) {
                    if (standing.post() != null) h.usedPosts.remove(standing.post());
                    standing.discard();
                }
            }
            h.add(role, -1);
            done++;
        }
        if (done > 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 1.0F, 1.0F);
            player.sendSystemMessage(HoldingEvents.gold(done + " " + plural(role, done) + " of " + h.name + " ride with you."));
        } else {
            player.sendSystemMessage(Component.literal(h.count(role) <= KEEP ? h.name + " can't spare any more " + plural(role, 2) + "."
                    : "No one could be mustered" + (useRecruits() ? " (Recruits may have reached its unit limit)." : "."))
                    .withStyle(ChatFormatting.RED));
        }
        mgr.setDirty();
        return done;
    }

    /** A man from {@code h} joins the player's company at their side (a column arriving, say). */
    public static boolean arrive(ServerLevel level, HoldingManager mgr, Player player, Holding h, Role role) {
        if (useRecruits()) return RecruitsCompat.muster(level, player, role);
        return spawnFollower(level, mgr, player, h, role) != null;
    }

    /** Hand the company to Villager Recruits instead of Stormhold's own soldiers (config, off by default). */
    public static boolean useRecruits() {
        return RecruitsCompat.loaded() && net.aiagent256.stormhold.Config.COMPANY_RECRUITS.get();
    }

    /** Bread each man takes from the realm's stores for his pouch when he joins. */
    public static final int RATIONS = 3;

    /**
     * Make {@code e} one of the player's own men: sworn to them, following them, named, with a few days'
     * bread in his pouch from the realm's stores if there is any.
     */
    public static void enlist(ServerLevel level, HoldingManager mgr, GarrisonEntity e, Player player, @org.jetbrains.annotations.Nullable Holding from) {
        e.setup(Sides.player(player.getUUID()), null, from != null ? from.tier : e.tier(), from != null ? Frontier.level(level, from) : e.renown(),
                e.color(), null, false);
        e.setOwner(player.getUUID());
        e.setPost(null);
        String name = e.hasCustomName() ? e.getCustomName().getString() : "Ser " + NameGen.first(level.random);
        e.setBaseName(name);
        var bread = new net.minecraft.resources.ResourceLocation("minecraft", "bread");
        int have = Stores.realmStock(mgr, from, player.getUUID(), bread);
        int take = Math.min(RATIONS, have);
        if (take > 0) {
            Stores.take(mgr, from, player.getUUID(), bread, take);
            e.pouch().set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, take));
        }
    }

    private static GarrisonEntity spawnFollower(ServerLevel level, HoldingManager mgr, Player player, Holding h, Role role) {
        BlockPos at = null;
        for (int i = 0; i < 8 && at == null; i++) {
            BlockPos q = player.blockPosition().offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
            at = Garrisons.standable(level, q);
        }
        if (at == null) at = player.blockPosition();
        GarrisonEntity e = Garrisons.spawn(level, mgr, h, role, at, Sides.player(player.getUUID()), false);
        if (e == null) return null;
        // spawn() files the soldier under the holding; he belongs to the player now
        h.live.remove(e.getUUID());
        enlist(level, mgr, e, player, h);
        return e;
    }

    /** Send every soldier of the player's within DISMISS_RANGE into {@code h}'s garrison. Returns how many went. */
    public static int dismiss(ServerLevel level, HoldingManager mgr, Player player, Holding h) {
        if (!inRange(player, h)) {
            player.sendSystemMessage(Component.literal("Ride within " + CALL_RANGE + " blocks of " + h.name
                    + " to send your men into its garrison.").withStyle(ChatFormatting.RED));
            return 0;
        }
        String me = Sides.player(player.getUUID());
        int sent = 0;
        AABB area = player.getBoundingBox().inflate(DISMISS_RANGE);
        for (Entity e : level.getEntities(player, area)) {
            Role r = null;
            if (e instanceof GarrisonEntity g) {
                if (g.owner() == null || !g.owner().equals(player.getUUID())) continue;
                r = g.role();
            } else {
                String side = RecruitsCompat.sideOf(e);
                if (side == null || !side.equals(me)) continue;
                r = RecruitsCompat.roleOf(e);
            }
            if (r == null || r == Role.LORD || r == Role.COURIER) continue;
            e.discard();
            h.add(r, 1);
            sent++;
        }
        if (sent > 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 1.0F, 0.9F);
            player.sendSystemMessage(HoldingEvents.gold(sent + " of your men join the garrison of " + h.name + "."));
        } else {
            player.sendSystemMessage(Component.literal("None of your men are within " + DISMISS_RANGE + " blocks.").withStyle(ChatFormatting.RED));
        }
        mgr.setDirty();
        return sent;
    }

    /** How close a man must be when the player goes through a portal to follow them through. */
    public static final int PORTAL_RANGE = 48;

    /**
     * The player has changed worlds: every man of theirs who was close behind goes through after them
     * and comes out at their side. Men holding a spot or clearing a building stay where they were told.
     */
    public static void followThrough(ServerLevel from, ServerLevel to, net.minecraft.server.level.ServerPlayer player, BlockPos left) {
        var mode = Orders.mode(player.getUUID());
        if (mode == Orders.Mode.HOLD || mode == Orders.Mode.CLEAR) return;
        var men = from.getEntitiesOfClass(GarrisonEntity.class, new AABB(left).inflate(PORTAL_RANGE, 24, PORTAL_RANGE),
                g -> g.isAlive() && player.getUUID().equals(g.owner()));
        int n = 0;
        for (GarrisonEntity g : men) {
            BlockPos at = null;
            for (int i = 0; i < 8 && at == null; i++) {
                at = Garrisons.standable(to, player.blockPosition().offset(to.random.nextInt(5) - 2, 0, to.random.nextInt(5) - 2));
            }
            if (at == null) at = player.blockPosition();
            final BlockPos spot = at;
            Entity moved = g.changeDimension(to, new net.minecraftforge.common.util.ITeleporter() {
                @Override
                public Entity placeEntity(Entity e, ServerLevel cur, ServerLevel dest, float yaw,
                                          java.util.function.Function<Boolean, Entity> repositionEntity) {
                    Entity r = repositionEntity.apply(false);
                    r.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, yaw, 0);
                    return r;
                }

                @Override
                public boolean playTeleportSound(net.minecraft.server.level.ServerPlayer p, ServerLevel cur, ServerLevel dest) {
                    return false;
                }
            });
            if (moved instanceof GarrisonEntity mg) {
                mg.setOwner(player.getUUID());
                mg.setTarget(null);
                n++;
            }
        }
        if (n > 0) {
            to.playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.4F, 1.4F);
            player.sendSystemMessage(HoldingEvents.gold(n + (n == 1 ? " man follows" : " men follow") + " you through."));
        }
    }

    private static String plural(Role r, int n) {
        return switch (r) {
            case KNIGHT -> n == 1 ? "knight" : "knights";
            case ARCHER -> n == 1 ? "archer" : "archers";
            case CROSSBOWMAN -> n == 1 ? "crossbowman" : "crossbowmen";
            case CAPTAIN -> n == 1 ? "captain" : "captains";
            default -> "men";
        };
    }

    private Company() {
    }
}
