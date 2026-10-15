package net.aiagent256.stormhold.entity;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.ai.BackOffGoal;
import net.aiagent256.stormhold.entity.ai.ClimbGoal;
import net.aiagent256.stormhold.entity.ai.FollowOwnerGoal;
import net.aiagent256.stormhold.entity.ai.GarrisonRangedGoal;
import net.aiagent256.stormhold.entity.ai.KnightMeleeGoal;
import net.aiagent256.stormhold.entity.ai.GuardStrollGoal;
import net.aiagent256.stormhold.entity.ai.MarchGoal;
import net.aiagent256.stormhold.entity.ai.ReturnToPostGoal;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A soldier bound to a holding (or a campaign). The holding is the source of truth for how many
 * soldiers exist; entities are only a view while players are near, so they are never saved to disk.
 */
public abstract class GarrisonEntity extends PathfinderMob implements RangedAttackMob {
    private String side = "";
    @Nullable private UUID holdingId;
    @Nullable private UUID campaignId;
    @Nullable private UUID shipmentId;
    @Nullable private BlockPos post;
    @Nullable private BlockPos marchTarget;
    /** Somewhere to pass through on the way to the march target: the gate, when leaving a castle. */
    @Nullable private BlockPos marchVia;
    /** The waypoint after {@link #marchVia}: outside the gate, once we're through it. */
    @Nullable private BlockPos marchVia2;
    /** The player this soldier rides with, if mustered into a company. Synced so the client knows whose man he is. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<UUID>> DATA_OWNER =
            net.minecraft.network.syncher.SynchedEntityData.defineId(GarrisonEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.OPTIONAL_UUID);
    /** A company man's food pouch: he eats from it when he's hurt. */
    private final net.minecraft.core.NonNullList<ItemStack> pouch = net.minecraft.core.NonNullList.withSize(3, ItemStack.EMPTY);
    /** His name without the health readout. */
    private String baseName = "";
    private int shownHealth = -1;
    private int eatCooldown;
    private int lastDamagedTick = -1000;
    /** A wall archer that has come down to fight; counts down back to holding the wall. */
    public int sortieTicks;
    private boolean holdPosition;
    private int tier = 1;
    private int renown = 2;
    private DyeColor color = DyeColor.RED;
    private boolean reinforced;
    private int alarmTicks;
    /** Ticks spent next to a target we can't reach; past a limit the knight backs off. */
    private int unreachableTicks;
    public boolean climbing;
    public int backOffTicks;
    @Nullable public Vec3 backOffFrom;
    @Nullable private UUID ignoring;
    private long ignoreUntil;
    @Nullable private final ServerBossEvent bossEvent;

    protected GarrisonEntity(EntityType<? extends GarrisonEntity> type, Level level) {
        super(type, level);
        Role r = role();
        this.xpReward = r.xp;
        this.bossEvent = r == Role.LORD ? new ServerBossEvent(this.getDisplayName(), BossEvent.BossBarColor.RED,
                BossEvent.BossBarOverlay.NOTCHED_10) : null;
        if (this.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        for (EquipmentSlot s : EquipmentSlot.values()) this.setDropChance(s, 0.02F);
    }

    public static AttributeSupplier.Builder attributes(Role r) {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, r.health)
                .add(Attributes.MOVEMENT_SPEED, r.speed)
                .add(Attributes.ATTACK_DAMAGE, r.damage)
                .add(Attributes.FOLLOW_RANGE, r.ranged ? 44.0 : 32.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, r.knockbackResist)
                .add(Attributes.ARMOR, r == Role.LORD ? 6.0 : 0.0)
                .add(Attributes.ATTACK_KNOCKBACK, r == Role.LORD ? 1.0 : 0.0);
    }

    public Role role() {
        return ModEntities.roleOf(this.getType());
    }

    public abstract boolean isHouse();

    @Override
    protected void registerGoals() {
        Role r = role();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        if (r == Role.COURIER) {
            this.goalSelector.addGoal(1, new PanicGoal(this, 1.4));
        } else if (r.ranged) {
            this.goalSelector.addGoal(2, new GarrisonRangedGoal(this));
        } else {
            this.goalSelector.addGoal(1, new BackOffGoal(this));
            this.goalSelector.addGoal(1, new ClimbGoal(this));
            this.goalSelector.addGoal(2, new KnightMeleeGoal(this, 1.2));
        }
        this.goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this));
        this.goalSelector.addGoal(3, new net.aiagent256.stormhold.entity.ai.CompanyPostGoal(this));
        this.goalSelector.addGoal(4, new MarchGoal(this));
        this.goalSelector.addGoal(5, new ReturnToPostGoal(this));
        if (r != Role.LORD && !r.ranged) this.goalSelector.addGoal(7, new GuardStrollGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        if (r != Role.COURIER) {
            this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::isHostileTo));
        }
    }

    // ---------------------------------------------------------------- allegiance

    public void setup(String side, @Nullable UUID holdingId, int tier, DyeColor color, @Nullable BlockPos post, boolean hold) {
        setup(side, holdingId, tier, this.renown, color, post, hold);
    }

    public void setup(String side, @Nullable UUID holdingId, int tier, int renown, DyeColor color, @Nullable BlockPos post, boolean hold) {
        this.side = side;
        this.holdingId = holdingId;
        this.tier = tier;
        this.renown = Math.max(1, Math.min(5, renown));
        this.color = color;
        this.post = post;
        this.holdPosition = hold;
    }

    public String side() { return side; }
    @Nullable public UUID holdingId() { return holdingId; }
    @Nullable public UUID campaignId() { return campaignId; }
    public void setCampaignId(@Nullable UUID id) { this.campaignId = id; }
    @Nullable public UUID shipmentId() { return shipmentId; }
    public void setShipmentId(@Nullable UUID id) { this.shipmentId = id; }
    @Nullable public BlockPos post() { return post; }
    public void setPost(@Nullable BlockPos p) { this.post = p; }
    @Nullable public BlockPos marchTarget() { return marchTarget; }
    public void setMarchTarget(@Nullable BlockPos p) { this.marchTarget = p; }
    @Nullable public BlockPos marchVia() { return marchVia; }
    public void setMarchVia(@Nullable BlockPos p) { this.marchVia = p; }
    @Nullable public BlockPos marchVia2() { return marchVia2; }
    public void setMarchVia2(@Nullable BlockPos p) { this.marchVia2 = p; }
    @Nullable public UUID owner() { return this.entityData.get(DATA_OWNER).orElse(null); }
    public void setOwner(@Nullable UUID id) { this.entityData.set(DATA_OWNER, java.util.Optional.ofNullable(id)); }
    public boolean holdsPosition() { return holdPosition && marchTarget == null && owner() == null; }
    public net.minecraft.core.NonNullList<ItemStack> pouch() { return pouch; }
    public void setBaseName(String name) {
        this.baseName = name;
        this.shownHealth = -1;
        this.setCustomName(net.minecraft.network.chat.Component.literal(name));
    }
    public String baseName() { return baseName.isEmpty() ? this.getName().getString() : baseName; }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_OWNER, java.util.Optional.empty());
    }

    /** The player this soldier follows, if they're online and in this level. */
    @Nullable
    public Player ownerPlayer() {
        UUID o = owner();
        return o == null ? null : this.level().getPlayerByUUID(o);
    }
    public int tier() { return tier; }
    public int renown() { return renown; }
    public DyeColor color() { return color; }

    public boolean isAlly(Entity e) {
        if (e instanceof GarrisonEntity g) return !side.isEmpty() && side.equals(g.side);
        if (e instanceof Player p) return side.equals(Sides.player(p.getUUID()));
        String rs = RecruitsCompat.sideOf(e);
        return rs != null && rs.equals(side);
    }

    public boolean isHostileTo(LivingEntity e) {
        if (e == this || !e.isAlive() || side.isEmpty()) return false;
        // a company man only picks fights his orders allow (close to his lord, inside the building he's clearing)
        if (owner() != null && !net.aiagent256.stormhold.holding.Orders.inScope(this, e)) return false;
        if (e instanceof GarrisonEntity g) {
            if (g.side.isEmpty() || g.side.equals(side)) return false;
            return !atPeaceWith(g);
        }
        if (e instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (!isHouse() || side.equals(Sides.player(p.getUUID()))) return false;
            // a friendly house leaves you alone unless you've just drawn blood here
            return alarmTicks > 0 || !isFriendlyTo(p);
        }
        String rs = RecruitsCompat.sideOf(e);
        if (rs != null) return !rs.equals(side) && (isHouse() || Sides.isPlayer(rs));
        // monsters inside or near the walls are everyone's problem; knights leave creepers to the archers
        if (e instanceof Enemy) return role().ranged || !(e instanceof Creeper);
        return false;
    }

    /** The house this soldier serves counts the player a friend. Checked at most every half second. */
    public boolean isFriendlyTo(Player p) {
        if (!isHouse() || !(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        String hid = Sides.houseId(side);
        if (hid == null) return false;
        long now = sl.getGameTime();
        if (friendCache != null && friendCache.equals(p.getUUID()) && now - friendCacheTime < 10) return friendCached;
        friendCache = p.getUUID();
        friendCacheTime = now;
        friendCached = net.aiagent256.stormhold.house.Standing.friendly(
                net.aiagent256.stormhold.holding.HoldingManager.get(sl), hid, p.getUUID());
        return friendCached;
    }

    @Nullable private UUID friendCache;
    private long friendCacheTime;
    private boolean friendCached;
    @Nullable private String peaceKey;
    private long peaceTime;
    private boolean peaceCached;

    /**
     * A house's soldier and a player's soldier leave each other alone when the house counts the player a
     * friend, unless one of them has just been hurt or is already fighting the other's side.
     */
    private boolean atPeaceWith(GarrisonEntity g) {
        String house = isHouse() ? side : g.isHouse() ? g.side : null;
        String player = !isHouse() ? side : !g.isHouse() ? g.side : null;
        if (house == null || player == null) return false;
        if (alarmTicks > 0 || g.alarmTicks > 0) return false;
        LivingEntity gt = g.getTarget();
        if (gt != null && (gt == this || isAlly(gt))) return false;
        LivingEntity mt = this.getTarget();
        if (mt != null && (mt == g || g.isAlly(mt))) return false;
        String hid = Sides.houseId(house);
        UUID pid = Sides.playerId(player);
        if (hid == null || pid == null || !(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        String key = hid + "|" + pid;
        long now = sl.getGameTime();
        if (key.equals(peaceKey) && now - peaceTime < 10) return peaceCached;
        peaceKey = key;
        peaceTime = now;
        peaceCached = net.aiagent256.stormhold.house.Standing.friendly(net.aiagent256.stormhold.holding.HoldingManager.get(sl), hid, pid);
        return peaceCached;
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        return isAlly(other) || super.isAlliedTo(other);
    }

    /** Hostile everywhere in the holding for a while after being attacked. */
    public void alarm(LivingEntity attacker) {
        if (this.getTarget() == null || !this.getTarget().isAlive()) {
            if (isHostileTo(attacker) || attacker instanceof Player) this.setTarget(attacker);
        }
        this.alarmTicks = 600;
    }

    // ---------------------------------------------------------------- combat

    @Override
    public boolean hurt(DamageSource src, float amount) {
        Entity attacker = src.getEntity();
        if (attacker != null && attacker != this && isAlly(attacker)) {
            if (!(attacker instanceof Player p) || !p.isShiftKeyDown()) return false;
        }
        if (!this.level().isClientSide && amount > 0 && attacker != null
                && this.getOffhandItem().getItem() instanceof ShieldItem
                && !src.is(DamageTypeTags.BYPASSES_ARMOR)) {
            Vec3 look = this.getViewVector(1.0F);
            Vec3 to = attacker.position().subtract(this.position());
            double len = Math.sqrt(to.x * to.x + to.z * to.z);
            if (len > 0.01 && (look.x * to.x + look.z * to.z) / len > 0.35 && this.random.nextFloat() < blockChance()) {
                amount *= src.is(DamageTypeTags.IS_PROJECTILE) ? 0.1F : 0.4F;
                this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.9F + this.random.nextFloat() * 0.2F);
            }
        }
        boolean result = super.hurt(src, amount);
        if (result) lastDamagedTick = this.tickCount;
        if (result && !this.level().isClientSide && attacker instanceof LivingEntity le) {
            HoldingEvents.onGarrisonHurt(this, le);
            // a player's man striking a house's soldier counts as the player striking him
            Player blamed = le instanceof Player pl ? pl : le instanceof GarrisonEntity ge && ge.owner() != null ? ge.ownerPlayer() : null;
            if (isHouse() && blamed != null && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                Player p = blamed;
                String hid = Sides.houseId(side);
                if (hid != null) {
                    var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(sl);
                    int delta = net.aiagent256.stormhold.house.Standing.friendly(mgr, hid, p.getUUID())
                            ? net.aiagent256.stormhold.house.Standing.HIT_GUARD : -3;
                    net.aiagent256.stormhold.house.Standing.change(sl, mgr, hid, p.getUUID(), delta, "you attacked their soldier");
                    alarmTicks = 600;
                }
            }
        }
        return result;
    }

    private float blockChance() {
        return switch (role()) {
            case LORD -> 0.45F;
            case CAPTAIN -> 0.55F;
            case KNIGHT -> 0.4F + 0.05F * tier;
            default -> 0.2F;
        };
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && role() == Role.LORD && target instanceof LivingEntity le) {
            le.knockback(0.9, Math.sin(this.getYRot() * ((float) Math.PI / 180F)), -Math.cos(this.getYRot() * ((float) Math.PI / 180F)));
        }
        return hit;
    }

    /** A friendly lord sells supplies: an emerald buys 8 bread, or 4 iron ingots when sneaking. */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        UUID o = owner();
        if (o != null && o.equals(player.getUUID())) {
            if (!this.level().isClientSide && player instanceof ServerPlayer sp) ownerInteract(sp, hand);
            return net.minecraft.world.InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (role() != Role.LORD || !isHouse() || !(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return super.mobInteract(player, hand);
        }
        ItemStack held = player.getItemInHand(hand);
        if (!isFriendlyTo(player)) {
            if (!held.is(Items.EMERALD)) return super.mobInteract(player, hand);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    this.getDisplayName().getString() + " will not trade with you."), true);
            return net.minecraft.world.InteractionResult.sidedSuccess(sl.isClientSide);
        }
        if (!held.is(Items.EMERALD)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    this.getDisplayName().getString() + ": \"An emerald buys bread. Sneak for iron.\""), true);
            return net.minecraft.world.InteractionResult.sidedSuccess(sl.isClientSide);
        }
        held.shrink(1);
        ItemStack goods = player.isShiftKeyDown() ? new ItemStack(Items.IRON_INGOT, 4) : new ItemStack(Items.BREAD, 8);
        if (!player.getInventory().add(goods)) player.drop(goods, false);
        this.playSound(SoundEvents.VILLAGER_YES, 1.0F, 1.0F);
        return net.minecraft.world.InteractionResult.sidedSuccess(sl.isClientSide);
    }

    /**
     * The lord deals with one of his own men: empty hand opens his kit (sneak for a word on how he is),
     * food or a potion heals him (or goes in his pouch if he's whole), armour or a weapon goes straight on.
     */
    private void ownerInteract(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            if (player.isShiftKeyDown()) {
                int food = 0;
                for (ItemStack s : pouch) food += s.getCount();
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(baseName() + ": " + (int) Math.ceil(getHealth())
                        + "/" + (int) getMaxHealth() + " health, " + food + " food in his pouch, orders: "
                        + net.aiagent256.stormhold.holding.Orders.mode(owner()).name().toLowerCase() + "."), true);
                return;
            }
            net.minecraftforge.network.NetworkHooks.openScreen(player, new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> new net.aiagent256.stormhold.menu.SoldierMenu(id, inv, new net.aiagent256.stormhold.menu.SoldierKit(this), this),
                    net.minecraft.network.chat.Component.literal(baseName())), buf -> buf.writeVarInt(this.getId()));
            return;
        }
        if (held.isEdible() || isDrinkable(held)) {
            if (getHealth() < getMaxHealth()) {
                ItemStack use = player.getAbilities().instabuild ? held.copy() : held;
                boolean potion = isDrinkable(use);
                if (consume(use) && potion && !player.getAbilities().instabuild) {
                    ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
                    if (!player.getInventory().add(bottle)) player.drop(bottle, false);
                }
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(baseName() + " is at " + (int) Math.ceil(getHealth())
                        + "/" + (int) getMaxHealth() + "."), true);
            } else {
                ItemStack rest = stow(player.getAbilities().instabuild ? held.copy() : held);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(rest.isEmpty() || rest.getCount() < held.getCount()
                        ? baseName() + " stows it in his pouch." : baseName() + "'s pouch is full."), true);
            }
            return;
        }
        EquipmentSlot slot = gearSlot(held);
        if (slot != null) {
            ItemStack old = this.getItemBySlot(slot).copy();
            this.setItemSlot(slot, player.getAbilities().instabuild ? held.copyWithCount(1) : held.split(1));
            this.setDropChance(slot, 0.0F);
            if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
            this.playSound(SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 1.0F);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(baseName() + " takes the "
                    + this.getItemBySlot(slot).getHoverName().getString() + "."), true);
            return;
        }
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(baseName() + " has no use for that. Empty hand opens his kit."), true);
    }

    /** Where a piece of gear goes on a soldier, or null if it isn't gear. */
    @Nullable
    public static EquipmentSlot gearSlot(ItemStack s) {
        if (s.isEmpty()) return null;
        EquipmentSlot es = Mob.getEquipmentSlotForItem(s);
        if (es.getType() == EquipmentSlot.Type.ARMOR) return es;
        if (s.getItem() instanceof ShieldItem) return EquipmentSlot.OFFHAND;
        var it = s.getItem();
        if (it instanceof net.minecraft.world.item.SwordItem || it instanceof net.minecraft.world.item.AxeItem
                || it instanceof net.minecraft.world.item.ProjectileWeaponItem || it instanceof net.minecraft.world.item.TridentItem
                || it instanceof net.minecraft.world.item.TieredItem) return EquipmentSlot.MAINHAND;
        return null;
    }

    public static boolean isDrinkable(ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.PotionItem
                && !(s.getItem() instanceof net.minecraft.world.item.ThrowablePotionItem);
    }

    /** Put food in the pouch; returns what didn't fit (the same stack, shrunk). */
    public ItemStack stow(ItemStack s) {
        for (int i = 0; i < pouch.size() && !s.isEmpty(); i++) {
            ItemStack p = pouch.get(i);
            if (p.isEmpty()) {
                pouch.set(i, s.split(s.getCount()));
            } else if (ItemStack.isSameItemSameTags(p, s) && p.getCount() < p.getMaxStackSize()) {
                int n = Math.min(s.getCount(), p.getMaxStackSize() - p.getCount());
                p.grow(n);
                s.shrink(n);
            }
        }
        return s;
    }

    /** Eat or drink one of {@code s} (shrinking it) and heal. False if it isn't food or drink. */
    public boolean consume(ItemStack s) {
        if (s.isEmpty()) return false;
        ItemStack shown = s.copyWithCount(1);
        if (s.isEdible()) {
            var food = s.getFoodProperties(this);
            float heal = food == null ? 2 : Math.max(2, food.getNutrition() * 1.5F);
            this.eat(this.level(), s);
            this.heal(heal);
        } else if (isDrinkable(s)) {
            for (var e : net.minecraft.world.item.alchemy.PotionUtils.getMobEffects(s)) {
                if (e.getEffect().isInstantenous()) e.getEffect().applyInstantenousEffect(this, this, this, e.getAmplifier(), 1.0D);
                else this.addEffect(new net.minecraft.world.effect.MobEffectInstance(e));
            }
            s.shrink(1);
            this.playSound(SoundEvents.GENERIC_DRINK, 1.0F, 1.0F);
        } else {
            return false;
        }
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, shown),
                    this.getX(), this.getEyeY() - 0.2, this.getZ(), 8, 0.2, 0.1, 0.2, 0.05);
        }
        shownHealth = -1;
        return true;
    }

    /** A company man looks after himself: slow healing out of a fight, and his pouch when he's badly hurt. */
    private void tickCare() {
        if (eatCooldown > 0) eatCooldown--;
        boolean calm = this.getTarget() == null && this.tickCount - lastDamagedTick > 100;
        if (calm && this.tickCount % 40 == 0 && this.getHealth() < this.getMaxHealth()) this.heal(1.0F);
        if (eatCooldown <= 0 && this.getHealth() < this.getMaxHealth() * 0.5F) {
            for (ItemStack s : pouch) {
                if (consume(s)) {
                    eatCooldown = 80;
                    break;
                }
            }
        }
        if (this.tickCount % 20 == 0) {
            int hp = (int) Math.ceil(this.getHealth());
            if (hp != shownHealth) {
                shownHealth = hp;
                this.setCustomName(net.minecraft.network.chat.Component.literal(baseName() + " ")
                        .append(net.minecraft.network.chat.Component.literal("\u2764" + hp).withStyle(net.minecraft.ChatFormatting.RED)));
            }
        }
    }

    /** A company man drops everything he carries: it's the lord's kit. */
    @Override
    protected void dropCustomDeathLoot(DamageSource src, int looting, boolean recentlyHit) {
        if (owner() == null) {
            super.dropCustomDeathLoot(src, looting, recentlyHit);
            return;
        }
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = this.getItemBySlot(s);
            if (!st.isEmpty()) {
                this.spawnAtLocation(st.copy());
                this.setItemSlot(s, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < pouch.size(); i++) {
            if (!pouch.get(i).isEmpty()) this.spawnAtLocation(pouch.get(i).copy());
            pouch.set(i, ItemStack.EMPTY);
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        boolean xbow = role() == Role.CROSSBOWMAN;
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power);
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        float velocity = xbow ? 2.6F : 1.8F;
        float spread = (float) Math.max(1.0, 10 - this.level().getDifficulty().getId() * 3);
        arrow.shoot(dx, dy + h * (xbow ? 0.08F : 0.18F), dz, velocity, spread);
        arrow.setBaseDamage(arrow.getBaseDamage() + (xbow ? 2.5 : 0.75) + tier * 0.4);
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        this.playSound(xbow ? SoundEvents.CROSSBOW_SHOOT : SoundEvents.SKELETON_SHOOT, 1.0F,
                1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        if (ignoring != null && ignoring.equals(target.getUUID()) && this.level().getGameTime() < ignoreUntil
                && target.getY() - this.getY() > 1.5) return false;
        return super.canAttack(target);
    }

    /** Can't reach the target: it is above sword reach and we're already as close as we can get. */
    private boolean targetOutOfReach(LivingEntity t) {
        double dy = t.getY() - this.getY();
        double dx = t.getX() - this.getX(), dz = t.getZ() - this.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (dy > KnightMeleeGoal.REACH - 0.5 && horiz < 4.5) return true;
        return dy > 1.5 && horiz < 6 && this.getNavigation().isDone();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (alarmTicks > 0) alarmTicks--;
        if (backOffTicks > 0) backOffTicks--;
        if (sortieTicks > 0) sortieTicks--;
        if (owner() != null) {
            tickCare();
            // a marked target overrides whatever he picked for himself
            var order = net.aiagent256.stormhold.holding.Orders.peek(owner());
            if (order != null && order.target != null && this.tickCount % 10 == 0
                    && this.level() instanceof net.minecraft.server.level.ServerLevel sl
                    && sl.getEntity(order.target) instanceof LivingEntity marked && marked.isAlive() && marked != this
                    && this.getTarget() != marked && marked.distanceToSqr(this) < 48 * 48) {
                this.setTarget(marked);
            }
        }
        // in a company: take up whatever is attacking the player, or whatever they just hit
        if (owner() != null && this.tickCount % 10 == 0 && (this.getTarget() == null || !this.getTarget().isAlive())) {
            Player p = ownerPlayer();
            if (p != null) {
                LivingEntity foe = p.getLastHurtByMob();
                if (foe == null || !foe.isAlive() || foe.distanceToSqr(p) > 24 * 24 || p.tickCount - p.getLastHurtByMobTimestamp() > 200) {
                    foe = p.getLastHurtMob();
                    if (foe != null && (!foe.isAlive() || foe.distanceToSqr(p) > 24 * 24 || p.tickCount - p.getLastHurtMobTimestamp() > 200)) foe = null;
                }
                if (foe != null && foe != this && !isAlly(foe) && !(foe instanceof Player fp && (fp.isCreative() || fp.isSpectator()))) {
                    this.setTarget(foe);
                }
            }
        }
        if (!role().ranged && role() != Role.COURIER) {
            LivingEntity t = this.getTarget();
            if (t != null && targetOutOfReach(t) && !climbing) {
                if (++unreachableTicks > 70) {
                    unreachableTicks = 0;
                    backOffTicks = 160;
                    backOffFrom = t.position();
                    ignoring = t.getUUID();
                    ignoreUntil = this.level().getGameTime() + 400;
                    this.setTarget(null);
                }
            } else if (unreachableTicks > 0) {
                unreachableTicks--;
            }
        }
        if (bossEvent != null) {
            if (this.tickCount % 20 == 0) updateBossViewers();
            bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
            if (!reinforced && this.getHealth() < this.getMaxHealth() * 0.5F) {
                reinforced = true;
                HoldingEvents.onLordBloodied(this);
            }
        }
    }

    @Override
    public void setCustomName(@Nullable net.minecraft.network.chat.Component name) {
        super.setCustomName(name);
        if (bossEvent != null) bossEvent.setName(this.getDisplayName());
    }

    /** The lord's bar shows once you're close enough to see him, or while he's fighting you. */
    private void updateBossViewers() {
        if (!(this.level() instanceof net.minecraft.server.level.ServerLevel sl) || bossEvent == null) return;
        for (ServerPlayer p : sl.players()) {
            double d = p.distanceToSqr(this);
            boolean shown = bossEvent.getPlayers().contains(p);
            boolean near = !p.isSpectator() && (d < 40 * 40 || (this.getTarget() == p && d < 96 * 96));
            if (near && !shown) bossEvent.addPlayer(p);
            else if (!near && shown && d > 48 * 48) bossEvent.removePlayer(p);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (bossEvent != null) bossEvent.removePlayer(player);
    }

    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(net.minecraft.world.level.ServerLevelAccessor level,
                                                                   net.minecraft.world.DifficultyInstance difficulty,
                                                                   net.minecraft.world.entity.MobSpawnType reason,
                                                                   @Nullable net.minecraft.world.entity.SpawnGroupData data,
                                                                   @Nullable CompoundTag tag) {
        net.minecraft.world.entity.SpawnGroupData d = super.finalizeSpawn(level, difficulty, reason, data, tag);
        if (side.isEmpty()) {
            // spawned by egg or command: pick a sensible allegiance
            if (isHouse()) {
                side = Sides.OUTLAWS;
            } else {
                Player p = level.getNearestPlayer(this, 16.0);
                if (p != null) side = Sides.player(p.getUUID());
            }
            if (post == null) post = this.blockPosition();
            Loadouts.equip(this, role(), tier, renown, color, null);
        }
        return d;
    }

    // ---------------------------------------------------------------- persistence

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** Garrisons are re-created from holding counts, so never write them to chunk data. A player's company is saved. */
    @Override
    public boolean shouldBeSaved() {
        return owner() != null && !this.isRemoved();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Side", side);
        if (holdingId != null) tag.putUUID("Holding", holdingId);
        if (post != null) tag.put("Post", NbtUtils.writeBlockPos(post));
        tag.putInt("Tier", tier);
        tag.putInt("Renown", renown);
        tag.putInt("Color", color.getId());
        if (owner() != null) tag.putUUID("Owner", owner());
        if (!baseName.isEmpty()) tag.putString("BaseName", baseName);
        net.minecraft.nbt.ListTag pt = new net.minecraft.nbt.ListTag();
        for (ItemStack st : pouch) pt.add(st.save(new CompoundTag()));
        tag.put("Pouch", pt);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        side = tag.getString("Side");
        if (tag.hasUUID("Holding")) holdingId = tag.getUUID("Holding");
        if (tag.contains("Post")) post = NbtUtils.readBlockPos(tag.getCompound("Post"));
        tier = Math.max(1, tag.getInt("Tier"));
        if (tag.contains("Renown")) renown = Math.max(1, Math.min(5, tag.getInt("Renown")));
        color = DyeColor.byId(tag.getInt("Color"));
        if (tag.hasUUID("Owner")) setOwner(tag.getUUID("Owner"));
        baseName = tag.getString("BaseName");
        var pt = tag.getList("Pouch", 10);
        for (int i = 0; i < pouch.size() && i < pt.size(); i++) pouch.set(i, ItemStack.of(pt.getCompound(i)));
    }
}
