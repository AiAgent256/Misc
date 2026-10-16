package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.house.Standing;
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

import java.util.List;

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
        // the garrison turns on it: the men on the walls first, since they can reach it
        List<GarrisonEntity> men = level.getEntitiesOfClass(GarrisonEntity.class, area, g -> g.isAlive() && !g.side.isEmpty());
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
        mgr.setDirty();
    }

    private Dragons() {
    }
}
