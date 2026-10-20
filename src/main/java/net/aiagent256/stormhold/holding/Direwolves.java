package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.entity.DirewolfEntity;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.item.DirewolfBardingItem;
import net.aiagent256.stormhold.registry.ModEntities;
import net.aiagent256.stormhold.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * A lord's direwolf. A Dire Hound Leader of the Born in Chaos packs sometimes leaves a white pup by its body (one time
 * in three; a Dread Hound one in forty): feed it raw meat and it's yours, if you've none already, with a whistle. It
 * follows you, fights what you fight, and learns from every kill it makes, ten levels of it: each one bigger, harder
 * and faster, its bite slowing at the fourth and tearing at the seventh, and at the tenth every kill a howl that puts
 * heart into you and your men. Right-click it with barding to arm it; sneak and right-click it bare-handed to take it
 * off. The whistle calls it from wherever it is, or sends it off into the hills when it's at your side. Brought down
 * in a fight it limps home and can't be called for ten minutes; it keeps all it has learned.
 *
 * <p>What it is (name, level, learning, barding, wounds) is kept in the realm's record, by lord, so a whistle lost or a
 * wolf left in some far country is never a wolf lost: whistle again and it comes.
 */
public final class Direwolves {
    public static final long WOUNDED = 12000;
    public static final int CALL_RANGE = 48;
    private static final String[] NAMES = {"Frost", "Ash", "Winter", "Wraith", "Rime", "Sleet", "Hoarfrost", "Shade", "Pale", "Cinder",
            "Moonfang", "Snowmane", "Thane", "Vigil", "Bane"};
    private static final ResourceLocation BIC_LEADER = new ResourceLocation("born_in_chaos_v1", "dire_hound_leader");

    public static final class Record {
        public String name = "Frost";
        public int level = 1, xp, barding;
        public float health = -1;
        public long woundedUntil;
        @Nullable public UUID entity;
        public boolean out;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("Name", name);
            t.putInt("Level", level);
            t.putInt("Xp", xp);
            t.putInt("Barding", barding);
            t.putFloat("Health", health);
            t.putLong("Wounded", woundedUntil);
            if (entity != null) t.putUUID("Entity", entity);
            t.putBoolean("Out", out);
            return t;
        }

        static Record load(CompoundTag t) {
            Record r = new Record();
            r.name = t.getString("Name");
            r.level = Math.max(1, Math.min(DirewolfEntity.MAX_LEVEL, t.getInt("Level")));
            r.xp = t.getInt("Xp");
            r.barding = t.getInt("Barding");
            r.health = t.getFloat("Health");
            r.woundedUntil = t.getLong("Wounded");
            if (t.hasUUID("Entity")) r.entity = t.getUUID("Entity");
            r.out = t.getBoolean("Out");
            return r;
        }
    }

    /** The record, kept with the overworld's. */
    private static HoldingManager book(MinecraftServer server) {
        return HoldingManager.get(server.overworld());
    }

    @Nullable
    public static Record of(MinecraftServer server, UUID player) {
        return book(server).direwolves.get(player);
    }

    private static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
    }

    /** Learning to the next level from this one: twenty a level. */
    public static int need(int level) {
        return 20 * level;
    }

    public static String label(Record r) {
        return r.name + " (" + r.level + ")";
    }

    public static String stats(Record r) {
        String[] bard = {"no barding", "iron barding", "diamond barding", "netherite barding"};
        return r.name + ", level " + r.level + (r.level < DirewolfEntity.MAX_LEVEL ? " (" + r.xp + " of " + need(r.level) + " to the next)" : ", full grown")
                + ", " + bard[Math.max(0, Math.min(3, r.barding))] + ".";
    }

    // ------------------------------------------------------------ a pup

    /** A hound pack's leader is dead: sometimes a white pup is left by it. */
    public static void onDeath(ServerLevel level, LivingEntity dead, DamageSource src) {
        Entity killer = src.getEntity();
        if (killer instanceof DirewolfEntity w && w.isTame()) kill(level, w, dead);
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(dead.getType());
        if (id == null || !id.getNamespace().equals(BIC_LEADER.getNamespace())) return;
        double chance = id.equals(BIC_LEADER) ? 1.0 / 3 : id.getPath().startsWith("dread_hound") ? 1.0 / 40 : 0;
        if (chance <= 0 || level.random.nextDouble() >= chance) return;
        UUID who = killer instanceof Player p ? p.getUUID() : killer instanceof DirewolfEntity w ? w.getOwnerUUID()
                : killer instanceof GarrisonEntity g ? g.owner() : null;
        if (who == null || of(level.getServer(), who) != null) return;
        DirewolfEntity pup = pup(level, dead.blockPosition());
        if (pup != null) HoldingEvents.tell(level, who, HoldingEvents.gold("By the dead hound a white pup whimpers, too young to run. "
                + "Feed it raw meat and it's yours."));
    }

    @Nullable
    public static DirewolfEntity pup(ServerLevel level, BlockPos at) {
        DirewolfEntity w = ModEntities.DIREWOLF.get().create(level);
        if (w == null) return null;
        BlockPos p = Garrisons.standable(level, at);
        if (p == null) p = at;
        w.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
        w.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.EVENT, null, null);
        w.setWolfLevel(1);
        w.setHealth(w.getMaxHealth());
        w.setPersistenceRequired();
        level.addFreshEntity(w);
        return w;
    }

    private static boolean meat(ItemStack s) {
        return s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT)
                || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN)
                || s.is(Items.COOKED_RABBIT) || s.is(Items.ROTTEN_FLESH);
    }

    // ------------------------------------------------------------ by hand

    /** Taming a pup, arming a direwolf, unarming it; PASS for the wolf's own (sitting, feeding). */
    public static InteractionResult interact(ServerLevel level, DirewolfEntity w, Player p, InteractionHand hand, ItemStack held) {
        if (!w.isTame()) {
            if (!meat(held)) return InteractionResult.PASS;
            Record mine = of(level.getServer(), p.getUUID());
            if (mine != null) {
                p.displayClientMessage(Component.literal("The pup won't come to you: " + mine.name + " is yours already, and it knows.")
                        .withStyle(ChatFormatting.GRAY), true);
                return InteractionResult.SUCCESS;
            }
            if (!p.getAbilities().instabuild) held.shrink(1);
            if (level.random.nextInt(2) == 0) {
                tame(level, w, p);
            } else {
                level.sendParticles(ParticleTypes.SMOKE, w.getX(), w.getY() + 0.6, w.getZ(), 6, 0.2, 0.2, 0.2, 0.01);
                p.displayClientMessage(Component.literal("The pup takes the meat, but backs away. Try again.").withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (!p.getUUID().equals(w.getOwnerUUID())) return InteractionResult.PASS;
        Record r = of(level.getServer(), p.getUUID());
        if (r == null) return InteractionResult.PASS;
        if (held.getItem() instanceof DirewolfBardingItem b) {
            int was = w.barding();
            if (was == b.tier()) return InteractionResult.PASS;
            String worn = held.getHoverName().getString().toLowerCase();
            w.setBarding(b.tier());
            r.barding = b.tier();
            if (!p.getAbilities().instabuild) held.shrink(1);
            if (was > 0) give(p, bardingItem(was));
            level.playSound(null, w.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON, SoundSource.NEUTRAL, 1.0F, 0.8F);
            p.displayClientMessage(Component.literal(r.name + " wears " + worn + ": armour "
                    + DirewolfEntity.ARMOR[b.tier()] + ".").withStyle(ChatFormatting.GOLD), true);
            book(level.getServer()).setDirty();
            return InteractionResult.SUCCESS;
        }
        if (held.isEmpty() && p.isShiftKeyDown() && w.barding() > 0) {
            give(p, bardingItem(w.barding()));
            w.setBarding(0);
            r.barding = 0;
            level.playSound(null, w.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.NEUTRAL, 1.0F, 0.8F);
            book(level.getServer()).setDirty();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    private static ItemStack bardingItem(int tier) {
        return new ItemStack(tier == 3 ? ModItems.BARDING_NETHERITE.get() : tier == 2 ? ModItems.BARDING_DIAMOND.get() : ModItems.BARDING_IRON.get());
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** The pup is the lord's: a name, the record, and a whistle. */
    public static Record tame(ServerLevel level, DirewolfEntity w, Player p) {
        w.tame(p);
        w.setOrderedToSit(false);
        w.getNavigation().stop();
        w.setTarget(null);
        Record r = new Record();
        r.name = NAMES[level.random.nextInt(NAMES.length)];
        r.entity = w.getUUID();
        r.out = true;
        book(level.getServer()).direwolves.put(p.getUUID(), r);
        book(level.getServer()).setDirty();
        w.setWolfLevel(1);
        w.setHealth(w.getMaxHealth());
        w.setCustomName(Component.literal(label(r)));
        level.broadcastEntityEvent(w, (byte) 7);            // hearts
        give(p, new ItemStack(ModItems.DIREWOLF_WHISTLE.get()));
        p.sendSystemMessage(HoldingEvents.gold("The pup takes the meat from your hand and won't leave your side. " + r.name
                + " is yours: it follows you, fights what you fight, and grows with every kill. The whistle calls it from wherever "
                + "it is, or sends it home when it's with you. Right-click it with barding to arm it."));
        Chronicle.log(level, HoldingManager.get(level), Chronicle.NEWS, "A white pup comes to you: you name it " + r.name + ".", p.getUUID());
        return r;
    }

    // ------------------------------------------------------------ learning

    /** It made a kill: learning, a level when it's learned enough, and at the tenth a howl. */
    public static void kill(ServerLevel level, DirewolfEntity w, LivingEntity dead) {
        UUID owner = w.getOwnerUUID();
        Record r = owner == null ? null : of(level.getServer(), owner);
        if (r == null || !w.getUUID().equals(r.entity)) return;
        if (r.level >= DirewolfEntity.MAX_LEVEL) {
            howl(level, w, owner);
            return;
        }
        r.xp += Math.max(1, Math.round(dead.getMaxHealth() / 4.0F));
        boolean up = false;
        while (r.level < DirewolfEntity.MAX_LEVEL && r.xp >= need(r.level)) {
            r.xp -= need(r.level);
            r.level++;
            up = true;
        }
        if (r.level >= DirewolfEntity.MAX_LEVEL) r.xp = 0;
        book(level.getServer()).setDirty();
        if (!up) return;
        w.setWolfLevel(r.level);
        w.setHealth(w.getMaxHealth());
        w.setCustomName(Component.literal(label(r)));
        level.playSound(null, w.blockPosition(), SoundEvents.WOLF_HOWL, SoundSource.NEUTRAL, 2.0F, 0.9F);
        String perk = switch (r.level) {
            case 4 -> " Its bite slows what it holds now.";
            case 7 -> " Its bite tears now: the wound bleeds.";
            case DirewolfEntity.MAX_LEVEL -> " Full grown. Every kill now ends in a howl that puts heart into you and your men.";
            default -> "";
        };
        HoldingEvents.tell(level, owner, HoldingEvents.gold(r.name + " grows: level " + r.level + ", " + (int) DirewolfEntity.health(r.level)
                + " health, " + (int) DirewolfEntity.damage(r.level) + " bite." + perk));
    }

    /** Strength for the lord and his men within 24 blocks, ten seconds. */
    private static void howl(ServerLevel level, DirewolfEntity w, UUID owner) {
        level.playSound(null, w.blockPosition(), SoundEvents.WOLF_HOWL, SoundSource.NEUTRAL, 3.0F, 0.7F);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, w.getBoundingBox().inflate(24),
                e -> e.getUUID().equals(owner) || e instanceof GarrisonEntity g && owner.equals(g.owner()))) {
            e.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 200, 0), w);
        }
    }

    // ------------------------------------------------------------ hurt

    /** Brought down: it limps home to lick its wounds. True if it was a sworn direwolf (and it's gone, not dead). */
    public static boolean wounded(ServerLevel level, DirewolfEntity w, DamageSource src) {
        UUID owner = w.getOwnerUUID();
        Record r = owner == null ? null : of(level.getServer(), owner);
        if (r == null || !w.getUUID().equals(r.entity)) return false;
        r.out = false;
        r.entity = null;
        r.health = -1;
        r.woundedUntil = now(level.getServer()) + WOUNDED;
        book(level.getServer()).setDirty();
        level.sendParticles(ParticleTypes.POOF, w.getX(), w.getY() + 0.6, w.getZ(), 16, 0.4, 0.4, 0.4, 0.02);
        level.playSound(null, w.blockPosition(), SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1.5F, 0.8F);
        HoldingEvents.tell(level, owner, Component.literal(r.name + " is brought down and limps off to lick its wounds. Whistle for it again in "
                + WOUNDED / 1200 + " minutes.").withStyle(ChatFormatting.RED));
        w.discard();
        return true;
    }

    // ------------------------------------------------------------ the whistle

    /** Call it, or send it home. */
    public static void whistle(ServerLevel level, Player p) {
        MinecraftServer server = level.getServer();
        Record r = of(server, p.getUUID());
        if (r == null) {
            p.sendSystemMessage(Component.literal("You have no direwolf. A Dire Hound Leader's pack sometimes leaves a white pup behind: "
                    + "feed it raw meat.").withStyle(ChatFormatting.GRAY));
            return;
        }
        long left = r.woundedUntil - now(server);
        if (left > 0) {
            p.sendSystemMessage(Component.literal(r.name + " is still licking its wounds: " + Math.max(1, (left + 1199) / 1200) + " minutes more.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        DirewolfEntity w = find(server, r);
        if (w != null && w.level() == level && w.distanceToSqr(p) <= (double) CALL_RANGE * CALL_RANGE) {
            r.health = w.getHealth();
            r.out = false;
            r.entity = null;
            book(server).setDirty();
            level.sendParticles(ParticleTypes.POOF, w.getX(), w.getY() + 0.6, w.getZ(), 12, 0.4, 0.4, 0.4, 0.02);
            w.discard();
            p.sendSystemMessage(Component.literal(r.name + " lopes off into the hills. Whistle and it comes.").withStyle(ChatFormatting.GOLD));
            return;
        }
        if (w != null && w.level() == level) {
            BlockPos at = Garrisons.standable(level, p.blockPosition().relative(p.getDirection().getOpposite(), 2));
            if (at == null) at = p.blockPosition();
            w.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            w.setOrderedToSit(false);
            w.getNavigation().stop();
            p.sendSystemMessage(Component.literal(r.name + " comes bounding back to you. " + stats(r)).withStyle(ChatFormatting.GOLD));
            return;
        }
        if (w != null) w.discard();                          // in another world: this one comes instead
        if (summon(level, p, r) != null)
            p.sendSystemMessage(Component.literal(r.name + " comes at your whistle. " + stats(r)).withStyle(ChatFormatting.GOLD));
    }

    @Nullable
    private static DirewolfEntity find(MinecraftServer server, Record r) {
        if (r.entity == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(r.entity) instanceof DirewolfEntity w && w.isAlive()) return w;
        }
        return null;
    }

    @Nullable
    public static DirewolfEntity summon(ServerLevel level, Player p, Record r) {
        DirewolfEntity w = ModEntities.DIREWOLF.get().create(level);
        if (w == null) return null;
        BlockPos at = Garrisons.standable(level, p.blockPosition().relative(p.getDirection().getOpposite(), 2));
        if (at == null) at = p.blockPosition();
        w.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, p.getYRot(), 0);
        w.tame(p);
        w.setWolfLevel(r.level);
        w.setBarding(r.barding);
        w.setHealth(r.health > 0 ? Math.min(r.health, w.getMaxHealth()) : w.getMaxHealth());
        w.setCustomName(Component.literal(label(r)));
        w.setPersistenceRequired();
        level.addFreshEntity(w);
        level.sendParticles(ParticleTypes.CLOUD, w.getX(), w.getY() + 0.5, w.getZ(), 10, 0.4, 0.3, 0.4, 0.02);
        level.playSound(null, at, SoundEvents.WOLF_HOWL, SoundSource.NEUTRAL, 1.5F, 1.1F);
        r.entity = w.getUUID();
        r.out = true;
        book(level.getServer()).setDirty();
        return w;
    }

    /** A direwolf loaded from the world that the record no longer answers for (one called away elsewhere since). */
    public static boolean stale(ServerLevel level, DirewolfEntity w) {
        if (!w.isTame() || w.getOwnerUUID() == null) return false;
        Record r = of(level.getServer(), w.getOwnerUUID());
        return r == null || !w.getUUID().equals(r.entity) || !r.out;
    }

    /** Renamed with a name tag: the record takes the new name, and the label its level. */
    public static void renamed(ServerLevel level, DirewolfEntity w) {
        if (!w.isTame() || w.getOwnerUUID() == null || !w.hasCustomName()) return;
        Record r = of(level.getServer(), w.getOwnerUUID());
        if (r == null || !w.getUUID().equals(r.entity)) return;
        String shown = w.getCustomName().getString();
        if (shown.equals(label(r))) return;
        String name = shown.replaceAll("\\s*\\(\\d+\\)$", "").trim();
        if (name.isEmpty()) return;
        r.name = name;
        w.setCustomName(Component.literal(label(r)));
        book(level.getServer()).setDirty();
    }

    static CompoundTag save(Map<UUID, Record> m) {
        CompoundTag t = new CompoundTag();
        m.forEach((p, r) -> t.put(p.toString(), r.save()));
        return t;
    }

    static void load(CompoundTag t, Map<UUID, Record> into) {
        for (String k : t.getAllKeys()) {
            try {
                into.put(UUID.fromString(k), Record.load(t.getCompound(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private Direwolves() {
    }
}
