package net.aiagent256.stormhold.entity;

import net.aiagent256.stormhold.holding.Direwolves;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A direwolf: a great white wolf, sworn to one lord. It comes as a pup the size of a dog and grows with every level,
 * to two-thirds again a wolf's size at the tenth. It wears barding (iron, diamond, netherite), learns from every kill,
 * and comes and goes at its lord's whistle. Brought down in a fight, it isn't killed: it limps home to heal.
 * Its standing (name, level, learning, barding, wounds) lives in the realm's record ({@link Direwolves}), so a lost
 * whistle or a wolf left in some far country is never a wolf lost.
 */
public class DirewolfEntity extends Wolf {
    private static final EntityDataAccessor<Integer> LEVEL = SynchedEntityData.defineId(DirewolfEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BARDING = SynchedEntityData.defineId(DirewolfEntity.class, EntityDataSerializers.INT);
    public static final int MAX_LEVEL = 10;

    public DirewolfEntity(EntityType<? extends Wolf> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(LEVEL, 1);
        this.entityData.define(BARDING, 0);
    }

    public int wolfLevel() {
        return this.entityData.get(LEVEL);
    }

    /** 0 none, 1 iron, 2 diamond, 3 netherite. */
    public int barding() {
        return this.entityData.get(BARDING);
    }

    public void setWolfLevel(int level) {
        this.entityData.set(LEVEL, Math.max(1, Math.min(MAX_LEVEL, level)));
        applyLevel();
    }

    public void setBarding(int tier) {
        this.entityData.set(BARDING, Math.max(0, Math.min(3, tier)));
        applyLevel();
    }

    /** Its size against a wolf's: a pup at the first level, two-thirds again a wolf at the tenth. */
    public static float scale(int level) {
        return 0.75F + 0.1F * (Math.max(1, Math.min(MAX_LEVEL, level)) - 1);
    }

    public static double health(int level) {
        return 40.0 + 8.0 * (level - 1);
    }

    public static double damage(int level) {
        return 6.0 + (level - 1);
    }

    /** Barding: armour points and toughness by tier. */
    public static final int[] ARMOR = {0, 8, 12, 15}, TOUGHNESS = {0, 0, 2, 3};

    /** Its strength, speed, hide and barding by its level. */
    public void applyLevel() {
        int l = wolfLevel();
        set(Attributes.MAX_HEALTH, health(l));
        set(Attributes.ATTACK_DAMAGE, damage(l));
        set(Attributes.MOVEMENT_SPEED, 0.32 + 0.01 * (l - 1));
        set(Attributes.KNOCKBACK_RESISTANCE, Math.min(0.65, 0.2 + 0.05 * (l - 1)) + (barding() == 3 ? 0.1 : 0.0));
        set(Attributes.ARMOR, ARMOR[barding()]);
        set(Attributes.ARMOR_TOUGHNESS, TOUGHNESS[barding()]);
        if (this.getHealth() > this.getMaxHealth()) this.setHealth(this.getMaxHealth());
        this.refreshDimensions();
    }

    private void set(net.minecraft.world.entity.ai.attributes.Attribute a, double v) {
        AttributeInstance i = this.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    @Override
    public void setTame(boolean tame) {
        super.setTame(tame);
        applyLevel();                       // the wolf's own taming sets a dog's health; a direwolf keeps its own
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return super.getDimensions(pose).scale(scale(wolfLevel()));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (LEVEL.equals(key)) this.refreshDimensions();
    }

    // ------------------------------------------------------------ its lord's hand

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!this.level().isClientSide && this.level() instanceof ServerLevel sl) {
            InteractionResult r = Direwolves.interact(sl, this, player, hand, held);
            if (r != InteractionResult.PASS) return r;
        }
        if (!this.isTame()) {
            // a direwolf pup takes meat, not a bone, and only from the hand of one who has none yet
            return this.isFood(held) ? InteractionResult.sidedSuccess(this.level().isClientSide) : InteractionResult.PASS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean canMate(Animal other) {
        return false;
    }

    @Nullable
    @Override
    public Wolf getBreedOffspring(ServerLevel level, AgeableMob other) {
        return null;
    }

    // ------------------------------------------------------------ the fight

    /** Fourth level: its bite slows. Seventh: it tears, and the wound bleeds. */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity le) {
            int l = wolfLevel();
            if (l >= 4) le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1), this);
            if (l >= 7) le.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0), this);
        }
        return hit;
    }

    /** Brought down, a sworn direwolf isn't killed: it limps home to heal (see {@link Direwolves#wounded}). */
    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide && this.isTame() && this.level() instanceof ServerLevel sl && Direwolves.wounded(sl, this, source)) return;
        super.die(source);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // a name tag gives it a new name: the record takes it, and the label its level
        if (this.tickCount % 40 == 0 && this.level() instanceof ServerLevel sl) Direwolves.renamed(sl, this);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("WolfLevel", wolfLevel());
        tag.putInt("Barding", barding());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("WolfLevel")) this.entityData.set(LEVEL, Math.max(1, Math.min(MAX_LEVEL, tag.getInt("WolfLevel"))));
        if (tag.contains("Barding")) this.entityData.set(BARDING, Math.max(0, Math.min(3, tag.getInt("Barding"))));
        applyLevel();
        // the wolf's own taming on load heals it to a dog's twenty: it keeps the health it had
        if (tag.contains("Health")) this.setHealth(Math.min(tag.getFloat("Health"), this.getMaxHealth()));
    }
}
