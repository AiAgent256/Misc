package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ice and Fire's dragons, when that mod is present. A wild dragon that comes within sight of a castle
 * is the castle's business: the garrison turns on it (archers and crossbowmen first, from the walls),
 * and the dragon is given the garrison to fight rather than the sheep. A dragon slain at a castle
 * earns the castle renown and whoever killed it the house's gratitude. The record keeps all of it.
 */
public final class Dragons {
    private static Boolean loaded;
    /** How far from the walls a dragon is the castle's problem. */
    public static final int WATCH = 96;

    /** A wild dragon someone's garrison has seen: where, when, and whether it's dead yet. */
    public static class Sighting {
        public UUID dragon;
        public String kind = "dragon";
        public int x, z;
        public long when;
        public UUID holding;
        public boolean dead;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Dragon", dragon);
            t.putString("Kind", kind);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putLong("When", when);
            if (holding != null) t.putUUID("Holding", holding);
            t.putBoolean("Dead", dead);
            return t;
        }

        public static Sighting load(CompoundTag t) {
            Sighting s = new Sighting();
            s.dragon = t.hasUUID("Dragon") ? t.getUUID("Dragon") : UUID.randomUUID();
            s.kind = t.getString("Kind");
            s.x = t.getInt("X");
            s.z = t.getInt("Z");
            s.when = t.getLong("When");
            s.holding = t.hasUUID("Holding") ? t.getUUID("Holding") : null;
            s.dead = t.getBoolean("Dead");
            return s;
        }
    }

    /** The record keeps the last two dozen dragons seen. */
    public static final int KEEP = 24;

    public static ListTag save(List<Sighting> l) {
        ListTag out = new ListTag();
        for (Sighting s : l) out.add(s.save());
        return out;
    }

    public static void load(ListTag l, List<Sighting> into) {
        for (int i = 0; i < l.size(); i++) into.add(Sighting.load(l.getCompound(i)));
    }

    /** Write a dragon down, or bring its entry up to date. */
    public static Sighting note(HoldingManager mgr, Entity dragon, @Nullable Holding near, long now) {
        Sighting s = sighting(mgr, dragon.getUUID());
        if (s == null) {
            s = new Sighting();
            s.dragon = dragon.getUUID();
            mgr.dragons.add(s);
            while (mgr.dragons.size() > KEEP) mgr.dragons.remove(0);
        }
        s.kind = name(dragon);
        s.x = dragon.blockPosition().getX();
        s.z = dragon.blockPosition().getZ();
        s.when = now;
        if (near != null) s.holding = near.id;
        mgr.setDirty();
        return s;
    }

    @Nullable
    public static Sighting sighting(HoldingManager mgr, UUID dragon) {
        for (Sighting s : mgr.dragons) if (s.dragon.equals(dragon)) return s;
        return null;
    }

    /** Dragons seen alive within reach of a point, lately. */
    public static List<Sighting> seenNear(HoldingManager mgr, BlockPos at, int reach, long now, long withinTicks) {
        List<Sighting> out = new ArrayList<>();
        for (Sighting s : mgr.dragons) {
            if (s.dead || now - s.when > withinTicks) continue;
            long dx = s.x - at.getX(), dz = s.z - at.getZ();
            if (dx * dx + dz * dz <= (long) reach * reach) out.add(s);
        }
        return out;
    }

    public static boolean loaded() {
        if (loaded == null) loaded = ModList.get().isLoaded("iceandfire");
        return loaded;
    }

    public static boolean isDragon(@Nullable Entity e) {
        if (e == null || !loaded() || !(e instanceof Mob)) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return id != null && id.getNamespace().equals("iceandfire") && id.getPath().endsWith("_dragon");
    }

    /** A dragon nobody has tamed. */
    public static boolean isWild(@Nullable Entity e) {
        return isDragon(e) && !(e instanceof TamableAnimal t && t.isTame());
    }

    public static String name(Entity e) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return id == null ? "dragon" : id.getPath().replace('_', ' ');
    }

    /** Every few seconds for a loaded castle: is there a wild dragon about? */
    public static void watch(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (!loaded() || !Config.DRAGONS_ATTACK.get()) return;
        AABB area = new AABB(h.box.minX() - WATCH, h.box.minY() - 64, h.box.minZ() - WATCH,
                h.box.maxX() + WATCH, h.box.maxY() + 96, h.box.maxZ() + WATCH);
        List<Mob> dragons = level.getEntitiesOfClass(Mob.class, area, Dragons::isWild);
        if (dragons.isEmpty()) {
            if (h.dragonAt > 0 && now - h.dragonAt > 2400) h.dragonAt = 0;
            return;
        }
        Mob dragon = dragons.get(0);
        if (h.dragonAt == 0 || now - h.dragonAt > 24000) {
            String text = "A " + name(dragon) + " fell upon " + h.name + ".";
            Chronicle.log(level, mgr, Chronicle.WAR, text, h.ownerPlayer());
            for (ServerPlayer p : level.players()) {
                if (p.blockPosition().distSqr(h.center()) <= 400.0 * 400.0 || (h.isPlayerOwned() && p.getUUID().equals(h.ownerPlayer())))
                    p.sendSystemMessage(Component.literal("A " + name(dragon) + " is at " + h.name + "!").withStyle(ChatFormatting.RED));
            }
        }
        h.dragonAt = now;
        note(mgr, dragon, h, now);
        // the garrison turns on it: the men on the walls first, since they can reach it
        List<GarrisonEntity> men = level.getEntitiesOfClass(GarrisonEntity.class, area, g -> g.isAlive() && !g.side().isEmpty());
        GarrisonEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (GarrisonEntity g : men) {
            double d = g.distanceToSqr(dragon);
            int reach = g.role().ranged ? 64 : 28;
            if (d <= reach * reach) {
                LivingEntity t = g.getTarget();
                if (t == null || !t.isAlive() || (!isDragon(t) && d < 24 * 24)) g.setTarget(dragon);
            }
            if (d < best) {
                best = d;
                nearest = g;
            }
        }
        // and the dragon gives the sheep a rest and comes for the walls
        LivingEntity dt = dragon.getTarget();
        if (nearest != null && (dt == null || !dt.isAlive() || !(dt instanceof GarrisonEntity || dt instanceof Player))) {
            dragon.setTarget(nearest);
        }
    }

    /** A dragon died: if it was at a castle, the castle and whoever killed it get the credit. */
    public static void onDeath(ServerLevel level, LivingEntity dragon, @Nullable Entity killer) {
        HoldingManager mgr = HoldingManager.get(level);
        Sighting s = sighting(mgr, dragon.getUUID());
        if (s != null) {
            s.dead = true;
            mgr.setDirty();
        }
        if (killer instanceof Player p) Politics.dragonSlain(level, mgr, p.getUUID(), dragon.getUUID(), dragon.blockPosition(), name(dragon));
        if (mgr.holdings.isEmpty()) return;
        Holding h = mgr.at(dragon.blockPosition(), WATCH);
        if (h == null) return;
        h.dragonAt = 0;
        String who = killer instanceof Player p ? p.getName().getString() : "the garrison of " + h.name;
        Chronicle.log(level, mgr, Chronicle.WAR, "The " + name(dragon) + " at " + h.name + " was slain by " + who + ".");
        if (h.renown < 5) {
            h.renown++;
            Chronicle.log(level, mgr, Chronicle.NEWS, h.name + " is spoken of: its renown rises to " + h.renown + ".", h.ownerPlayer());
        }
        if (killer instanceof Player p && h.isHouseOwned()) {
            Standing.change(level, mgr, h.houseId, p.getUUID(), 30, "you slew the dragon at " + h.name);
        }
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(h.center()) <= 400.0 * 400.0)
                p.sendSystemMessage(Component.literal("The " + name(dragon) + " at " + h.name + " is dead.").withStyle(ChatFormatting.GOLD));
        }
        if (trophy(level, h, dragon)) {
            Chronicle.log(level, mgr, Chronicle.NEWS, "The skull of the " + name(dragon) + " is mounted in the hall of " + h.name + ".", h.ownerPlayer());
        }
        mgr.setDirty();
    }

    /**
     * Mount the dragon's skull in the great hall, beside the lord's seat: Ice and Fire's skull entity, sized
     * to the beast. False if there's no room or the mod has no such thing.
     */
    public static boolean trophy(ServerLevel level, Holding h, LivingEntity dragon) {
        var type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("iceandfire", "dragon_skull"));
        if (type == null || h.lordPos.equals(BlockPos.ZERO) || !level.isLoaded(h.lordPos)) return false;
        String kind = name(dragon);
        int dragonType = kind.startsWith("ice") ? 1 : kind.startsWith("lightning") ? 2 : 0;
        int stage = Math.max(1, Math.min(5, (int) (dragon.getBbWidth() / 1.8) + 1));
        BlockPos spot = null;
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) {
            BlockPos p = h.lordPos.offset(o[0], 0, o[1]);
            if (!h.box.isInside(p)) continue;
            if (level.getBlockState(p.below()).isSolid() && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()) {
                spot = p;
                break;
            }
        }
        if (spot == null) return false;
        final BlockPos at = spot;
        // no second skull in the same spot
        if (!level.getEntitiesOfClass(Entity.class, new AABB(at).inflate(0.5), e -> e.getType() == type).isEmpty()) return false;
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "iceandfire:dragon_skull");
        tag.putInt("Type", dragonType);
        tag.putInt("Stage", stage);
        tag.putInt("DragonAge", stage * 25);
        tag.putFloat("DragonYaw", (float) (level.random.nextInt(4) * 90));
        Entity skull = net.minecraft.world.entity.EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            return e;
        });
        if (skull == null) return false;
        level.addFreshEntity(skull);
        return true;
    }

    private Dragons() {
    }
}
