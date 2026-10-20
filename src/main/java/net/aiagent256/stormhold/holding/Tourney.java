package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Loadouts;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A tournament at a castle you hold. Heralds cry it for 24 emeralds, and three knights of the houses ride in to
 * fight you in the yard, one at a time, in iron, then diamond, then netherite. Nobody dies in the lists: a
 * challenger brought down to a quarter of his strength yields, and so do you when you're down to two hearts.
 * Beat all three and the castle's renown grows, and the last of them, the best, swears to you and rides in your
 * company in his own kit. Your men stand and watch; nobody else can strike or be struck by a challenger. Leave
 * the castle and you forfeit. Once every two days at each castle.
 */
public final class Tourney {
    public static final String TAG = "StormholdTourney";
    public static final int COST = 24, ROUNDS = 3, LISTS = 8;
    public static final long COOLDOWN = 48000;
    /** The kit level each round's challenger comes in, and the word for it. */
    static final int[] KIT = {3, 5, 8};
    static final String[] KIT_NAME = {"iron", "diamond", "netherite"};
    /** A challenger yields at this share of his strength; you yield at this much health. */
    public static final float YIELD_AT = 0.25F, YOU_YIELD = 4.0F;

    static final class Bout {
        UUID holding, player;
        ResourceKey<Level> dim;
        int round;
        final String[] names = new String[ROUNDS], houses = new String[ROUNDS];
        @Nullable UUID challenger;
        /** Seconds till the next rides in. */
        int wait = 5;
        /** Seconds since this round's challenger yielded; 0 while they fight. */
        int yielded;
        /** Set from the damage hook: the player yielded. */
        boolean lost;
    }

    /** By player. Not saved: a tournament running when the world closes is over (its challenger goes when the world loads). */
    private static final Map<UUID, Bout> BOUTS = new HashMap<>();

    public static boolean running(UUID player) {
        return BOUTS.containsKey(player);
    }

    /** Why a tournament can't be held here now, or null if it can. */
    @Nullable
    public static String refusal(ServerLevel level, HoldingManager mgr, Holding h, @Nullable Player player) {
        if (h.isTown()) return "Tournaments are held at castles.";
        if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID) || Warbands.active(mgr, h.id)) return "Not while " + h.name + " is under attack.";
        long since = level.getGameTime() - h.lastTourney;
        if (h.lastTourney != 0 && since >= 0 && since < COOLDOWN) {
            long left = COOLDOWN - since;
            return h.name + " held one lately: the next in about " + (left >= 24000 ? (left + 23999) / 24000 + " day" + (left > 24000 ? "s" : "")
                    : (left + 1199) / 1200 + " minutes") + ".";
        }
        if (player != null && running(player.getUUID())) return "You're in the lists already.";
        return null;
    }

    public static boolean start(ServerLevel level, HoldingManager mgr, Holding h, ServerPlayer player) {
        if (!player.getUUID().equals(h.ownerPlayer())) return false;
        String no = refusal(level, mgr, h, player);
        if (no == null && !inLists(h, player)) no = "Be at " + h.name + " to hold its tournament: the lists are its yard.";
        if (no != null) {
            player.sendSystemMessage(Component.literal(no).withStyle(ChatFormatting.RED));
            return false;
        }
        int have = Stores.packPlusRealm(mgr, h, player, Items.EMERALD);
        if (have < COST && !player.isCreative()) {
            player.sendSystemMessage(Component.literal("Heralds, a purse and a feast: a tournament costs " + COST + " emeralds; you have " + have + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!player.isCreative()) Stores.takeFromPackThenRealm(mgr, h, player, Items.EMERALD, COST);
        Bout b = new Bout();
        b.holding = h.id;
        b.player = player.getUUID();
        b.dim = level.dimension();
        List<String> houses = new ArrayList<>();
        for (House x : mgr.houses.values()) if (!x.id.equals("outlaws") && !x.id.startsWith("rebel_") && !net.aiagent256.stormhold.house.Politics.castlesOf(mgr, x.id).isEmpty()) houses.add(x.id);
        Collections.shuffle(houses, new java.util.Random(level.random.nextLong()));
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < ROUNDS; i++) {
            b.houses[i] = houses.isEmpty() ? h.houseId : houses.get(i % houses.size());
            b.names[i] = "Ser " + NameGen.firstUnlike(level.random, taken, "Ser ");
            taken.add(b.names[i]);
        }
        h.lastTourney = Math.max(1, level.getGameTime());
        mgr.setDirty();
        BOUTS.put(b.player, b);
        level.playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 2.0F, 1.0F);
        StringBuilder who = new StringBuilder();
        for (int i = 0; i < ROUNDS; i++) {
            if (i > 0) who.append(i == ROUNDS - 1 ? " and " : ", ");
            who.append(title(mgr, b, i)).append(" in ").append(KIT_NAME[i]);
        }
        HoldingEvents.announceNear(level, player.blockPosition(), 96, HoldingEvents.gold("Heralds cry a tournament at " + h.name + "! "
                + "Three challengers ride for the prize: " + who + ". You meet them one at a time in the yard. Nobody dies in the lists: "
                + "a man brought low yields, and so do you at two hearts. Beat all three and the last swears to you."));
        return true;
    }

    private static String title(HoldingManager mgr, Bout b, int i) {
        return b.names[i] + " of " + mgr.house(b.houses[i]).title();
    }

    static boolean inLists(Holding h, Player p) {
        var box = h.box.inflatedBy(LISTS);
        return p.getX() >= box.minX() && p.getX() <= box.maxX() + 1 && p.getZ() >= box.minZ() && p.getZ() <= box.maxZ() + 1
                && p.getY() >= box.minY() - 16 && p.getY() <= box.maxY() + 16;
    }

    /** Once a second. */
    public static void tick(ServerLevel level, HoldingManager mgr) {
        if (BOUTS.isEmpty()) return;
        for (Iterator<Bout> it = BOUTS.values().iterator(); it.hasNext(); ) {
            Bout b = it.next();
            if (!b.dim.equals(level.dimension())) continue;
            Holding h = mgr.holdings.get(b.holding);
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(b.player);
            GarrisonEntity ch = b.challenger != null && level.getEntity(b.challenger) instanceof GarrisonEntity g ? g : null;
            if (b.lost) {
                lose(level, mgr, h, p, b, ch, "You yield to " + title(mgr, b, b.round) + ". The tournament" + (h != null ? " at " + h.name : "")
                        + " is his.");
                it.remove();
                continue;
            }
            if (h == null || p == null || p.level() != level || !p.isAlive() || !inLists(h, p) || !p.getUUID().equals(h.ownerPlayer())) {
                lose(level, mgr, h, p, b, ch, p != null && !p.isAlive() ? "You fell in the lists" + (h != null ? " at " + h.name : "") + "."
                        : "You left the lists" + (h != null ? " at " + h.name : "") + ": the challengers have the day.");
                it.remove();
                continue;
            }
            if (b.yielded > 0) {
                if (++b.yielded <= 3) continue;
                if (b.round >= ROUNDS - 1) {
                    win(level, mgr, h, p, b, ch);
                    it.remove();
                    continue;
                }
                leave(level, ch);
                b.challenger = null;
                b.yielded = 0;
                b.round++;
                b.wait = 5;
                continue;
            }
            if (b.challenger == null) {
                if (--b.wait > 0) continue;
                ch = spawn(level, mgr, h, p, b);
                if (ch == null) {
                    lose(level, mgr, h, p, b, null, "No challenger could find a place to stand in the yard of " + h.name + ".");
                    it.remove();
                    continue;
                }
                b.challenger = ch.getUUID();
                level.playSound(null, ch.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2.0F, 1.4F);
                p.sendSystemMessage(Component.literal("Bout " + (b.round + 1) + " of " + ROUNDS + ": " + title(mgr, b, b.round) + " rides in, in "
                        + KIT_NAME[b.round] + ". Have at him!").withStyle(ChatFormatting.GOLD));
                continue;
            }
            if (ch == null || !ch.isAlive()) {
                // killed outright, or lost where the land isn't loaded: counted beaten
                p.sendSystemMessage(Component.literal(title(mgr, b, b.round) + " is out of the lists.").withStyle(ChatFormatting.GOLD));
                b.challenger = null;
                b.yielded = 1;
                continue;
            }
            if (ch.getTarget() != p) ch.setTarget(p);
        }
    }

    @Nullable
    private static GarrisonEntity spawn(ServerLevel level, HoldingManager mgr, Holding h, ServerPlayer p, Bout b) {
        String side = Sides.house(b.houses[b.round]);
        BlockPos yard = Campaigns.gateInside(h);
        BlockPos at = Garrisons.standable(level, yard);
        if (at == null || at.distSqr(p.blockPosition()) > 40 * 40) {
            // beside the player if the yard spot won't do
            BlockPos near = p.blockPosition().relative(p.getDirection(), 6);
            at = Garrisons.standable(level, near);
        }
        if (at == null) return null;
        GarrisonEntity g = Garrisons.spawn(level, mgr, null, Role.KNIGHT, at, side, false);
        if (g == null) return null;
        int lvl = KIT[b.round];
        House house = mgr.houseOf(side);
        Loadouts.equip(g, Role.KNIGHT, 3, lvl, g.color(), house);
        double hp = Garrisons.health(Role.KNIGHT, 3) * (1.0 + 0.12 * (lvl - 1));
        AttributeInstance mh = g.getAttribute(Attributes.MAX_HEALTH);
        if (mh != null) mh.setBaseValue(hp);
        g.setHealth((float) hp);
        g.getPersistentData().putUUID(TAG, b.player);
        g.setBaseName(title(mgr, b, b.round));
        g.setCustomName(Component.literal(g.baseName()));
        g.setTarget(p);
        return g;
    }

    private static void leave(ServerLevel level, @Nullable GarrisonEntity ch) {
        if (ch == null) return;
        level.sendParticles(ParticleTypes.POOF, ch.getX(), ch.getY() + 1, ch.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        ch.discard();
    }

    private static void lose(ServerLevel level, HoldingManager mgr, @Nullable Holding h, @Nullable ServerPlayer p, Bout b,
                             @Nullable GarrisonEntity ch, String says) {
        leave(level, ch);
        if (p != null) p.sendSystemMessage(Component.literal(says).withStyle(ChatFormatting.RED));
        if (h != null) Chronicle.log(level, mgr, Chronicle.NEWS, title(mgr, b, b.round) + " has the day at the tournament of " + h.name + ".", b.player);
    }

    private static void win(ServerLevel level, HoldingManager mgr, Holding h, ServerPlayer p, Bout b, @Nullable GarrisonEntity ch) {
        int was = h.renown;
        h.renown = Math.min(5, h.renown + 1);
        mgr.setDirty();
        String name = b.names[ROUNDS - 1], full = title(mgr, b, ROUNDS - 1);
        String renown = h.renown > was ? " " + h.name + " is " + Holding.renownName(h.renown) + " seat now." : "";
        if (ch != null && ch.isAlive()) {
            ch.getPersistentData().remove(TAG);
            ch.setNoAi(false);
            ch.setTarget(null);
            ch.setHealth(ch.getMaxHealth());
            ch.setCustomName(Component.literal(name));
            Company.enlist(level, mgr, ch, p, h);
            level.playSound(null, ch.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 0.8F);
            p.sendSystemMessage(HoldingEvents.gold("You've beaten all three! " + full + " lays his sword at your feet: " + ch.baseName()
                    + " rides in your company now, in his own " + KIT_NAME[ROUNDS - 1] + "." + renown));
        } else {
            p.sendSystemMessage(HoldingEvents.gold("You've beaten all three, though " + full + " is in no state to serve anyone." + renown));
        }
        Chronicle.log(level, mgr, Chronicle.NEWS, "You win the tournament at " + h.name + (ch != null ? "; " + name + " swears to you." : "."), b.player);
    }

    // ------------------------------------------------------------ the rules of the lists

    public static boolean is(@Nullable Entity e) {
        return e instanceof GarrisonEntity && e.getPersistentData().hasUUID(TAG);
    }

    /** Whether this challenger's bout is still on and he's the one in it. */
    public static boolean current(GarrisonEntity g) {
        Bout b = BOUTS.get(g.getPersistentData().getUUID(TAG));
        return b != null && g.getUUID().equals(b.challenger);
    }

    /** The one man a challenger fights. */
    public static boolean foeOf(GarrisonEntity g, LivingEntity e) {
        return e instanceof Player p && is(g) && p.getUUID().equals(g.getPersistentData().getUUID(TAG));
    }

    /** No one but the two in the lists strikes the other: true to stop the blow. */
    public static boolean apart(@Nullable Entity attacker, Entity target) {
        boolean a = is(attacker), t = is(target);
        if (!a && !t) return false;
        if (a && t) return true;
        if (a) return !(target instanceof LivingEntity le && foeOf((GarrisonEntity) attacker, le));
        return !(attacker instanceof LivingEntity le && foeOf((GarrisonEntity) target, le));
    }

    /** A man brought low yields; so does the player. */
    public static void onDamage(LivingDamageEvent e) {
        LivingEntity t = e.getEntity();
        if (t instanceof GarrisonEntity g && is(g)) {
            Bout b = BOUTS.get(g.getPersistentData().getUUID(TAG));
            if (b == null || !g.getUUID().equals(b.challenger)) return;
            if (b.yielded > 0) {
                e.setCanceled(true);
                return;
            }
            if (g.getHealth() - e.getAmount() <= g.getMaxHealth() * YIELD_AT) {
                e.setCanceled(true);
                g.setHealth(Math.max(1.0F, g.getMaxHealth() * YIELD_AT));
                g.setTarget(null);
                g.getNavigation().stop();
                g.setNoAi(true);
                b.yielded = 1;
                g.level().playSound(null, g.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.HOSTILE, 1.5F, 0.6F);
                ServerPlayer p = g.level() instanceof ServerLevel sl ? sl.getServer().getPlayerList().getPlayer(b.player) : null;
                if (p != null)
                    p.sendSystemMessage(Component.literal(g.baseName() + " yields! (bout " + (b.round + 1) + " of " + ROUNDS + ")").withStyle(ChatFormatting.GREEN));
            }
            return;
        }
        if (t instanceof Player p) {
            Bout b = BOUTS.get(p.getUUID());
            if (b == null || b.yielded > 0 || b.challenger == null) return;
            Entity src = e.getSource().getEntity();
            if (src != null && src.getUUID().equals(b.challenger) && p.getHealth() - e.getAmount() <= YOU_YIELD) {
                e.setCanceled(true);
                b.lost = true;
            }
        }
    }

    /** For the tests: a bout on, this man in the lists against this player. */
    public static void forTest(UUID player, Holding h, ResourceKey<Level> dim, GarrisonEntity challenger) {
        Bout b = new Bout();
        b.player = player;
        b.holding = h.id;
        b.dim = dim;
        for (int i = 0; i < ROUNDS; i++) {
            b.names[i] = "Ser Test" + i;
            b.houses[i] = h.houseId;
        }
        b.challenger = challenger.getUUID();
        challenger.getPersistentData().putUUID(TAG, player);
        BOUTS.put(player, b);
    }

    /** For the tests: whether the bout's challenger has yielded, and whether the player has. */
    public static boolean yielded(UUID player) {
        Bout b = BOUTS.get(player);
        return b != null && b.yielded > 0;
    }

    public static boolean lost(UUID player) {
        Bout b = BOUTS.get(player);
        return b != null && b.lost;
    }

    public static void forget(UUID player) {
        BOUTS.remove(player);
    }

    @Nullable
    static UUID challenger(UUID player) {
        Bout b = BOUTS.get(player);
        return b == null ? null : b.challenger;
    }

    static int round(UUID player) {
        Bout b = BOUTS.get(player);
        return b == null ? -1 : b.round;
    }

    private Tourney() {
    }
}
