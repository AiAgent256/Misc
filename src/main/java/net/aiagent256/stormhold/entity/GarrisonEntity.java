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
    /** The side of his post a bowman leans out over a machicolation from; both 0 for a post with no hole beside it. */
    private int lipX, lipZ;
    /** How far off his block's centre a man at a lip stands: his body a quarter of a block out over the hole,
     * his eye over its edge. The generator's audit stands him at the same spot. */
    public static final double LIP = 0.45;
    private boolean holdPosition;
    private int tier = 1;
    private int renown = 2;
    private DyeColor color = DyeColor.RED;
    private boolean reinforced;
    private int alarmTicks;
    /** Whose side the alarm is against: a player's side string. Null means a monster, which is nobody's fault. */
    @Nullable private String alarmSide;
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
                .add(Attributes.FOLLOW_RANGE, r.ranged ? 56.0 : 32.0)
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
            // the game looks for targets only four blocks up or down: a bowman on a wall never spotted the man at
            // its foot until somebody was hit. A bowman looks as far down as his bow carries
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::isHostileTo) {
                @Override
                protected net.minecraft.world.phys.AABB getTargetSearchArea(double d) {
                    return this.mob.getBoundingBox().inflate(d, r.ranged ? Math.min(d, 32.0) : 4.0, d);
                }

                // not simply the nearest: the nearest with room on him (see Engagement), so a fight spreads into pairs
                @Override
                protected void findTarget() {
                    var mob = GarrisonEntity.this;
                    var foes = mob.level().getEntitiesOfClass(LivingEntity.class, this.getTargetSearchArea(this.getFollowDistance()),
                            e -> this.targetConditions.test(mob, e));
                    this.target = net.aiagent256.stormhold.entity.ai.Engagement.pick(mob, foes);
                }
            });
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
        this.renown = Math.max(1, Math.min(9, renown));
        this.color = color;
        this.post = post;
        this.lipX = 0;
        this.lipZ = 0;
        this.holdPosition = hold;
    }

    public String side() { return side; }
    @Nullable public UUID holdingId() { return holdingId; }
    @Nullable public UUID campaignId() { return campaignId; }
    public void setCampaignId(@Nullable UUID id) { this.campaignId = id; }
    @Nullable public UUID shipmentId() { return shipmentId; }
    public void setShipmentId(@Nullable UUID id) { this.shipmentId = id; }
    @Nullable public BlockPos post() { return post; }
    public void setLip(int dx, int dz) { this.lipX = Integer.signum(dx); this.lipZ = Integer.signum(dz); }
    public boolean hasLip() { return lipX != 0 || lipZ != 0; }

    /** Where a man at a lip post stands: LIP off the post's centre toward the hole. Null when the post has none. */
    @Nullable
    public Vec3 lipSpot() {
        if (post == null || !hasLip()) return null;
        double len = Math.sqrt(lipX * lipX + lipZ * lipZ);
        return new Vec3(post.getX() + 0.5 + LIP * lipX / len, post.getY(), post.getZ() + 0.5 + LIP * lipZ / len);
    }

    /**
     * A bowman at a post beside a machicolation stands at the hole's lip, leaning over it, so he sees straight
     * down the wall's face where a man at the middle of his block sees only the ledge. He's set there once he's
     * on his post and still: walking there could carry him over the edge.
     */
    private void holdLip() {
        if (!hasLip() || !role().ranged || !holdsPosition() || sortieTicks > 0) return;
        if (!this.blockPosition().equals(post) || !this.onGround() || !this.getNavigation().isDone()) return;
        Vec3 at = lipSpot();
        double dx = at.x - this.getX(), dz = at.z - this.getZ();
        if (dx * dx + dz * dz < 0.0004) return;
        this.setPos(at.x, this.getY(), at.z);
        this.setDeltaMovement(0, this.getDeltaMovement().y, 0);
    }
    public void setPost(@Nullable BlockPos p) {
        if (!java.util.Objects.equals(p, this.post)) { lipX = 0; lipZ = 0; }      // a lip belongs to its post
        this.post = p;
    }
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

    // ------------------------------------------------------------ veterans: a company man's kills, and the rank they earn him

    private int kills;
    /** His name before any rank was put in front of it. */
    private String plainName = "";
    private static final java.util.UUID VET_HEALTH = java.util.UUID.fromString("6f6e1c1a-5b3e-4a27-9d1e-0a4f6b7c8d01"),
            VET_DAMAGE = java.util.UUID.fromString("6f6e1c1a-5b3e-4a27-9d1e-0a4f6b7c8d02"),
            VET_KNOCKBACK = java.util.UUID.fromString("6f6e1c1a-5b3e-4a27-9d1e-0a4f6b7c8d03");
    private static final String[] EPITHETS = {"the Bold", "the Grim", "Ironhand", "the Red", "Oakheart", "the Steady", "Wolfsbane",
            "the Lion", "Stoneface", "the Unbroken", "Longarm", "the Black"};

    public int kills() { return kills; }

    /** 0 none, 1 Veteran (5 kills), 2 Champion (15), 3 Hero (40). */
    public static int rankFor(int kills) {
        return kills >= 40 ? 3 : kills >= 15 ? 2 : kills >= 5 ? 1 : 0;
    }

    public static String rankName(int rank) {
        return switch (rank) {
            case 1 -> "Veteran";
            case 2 -> "Champion";
            case 3 -> "Hero";
            default -> "";
        };
    }

    /** A kill for one of a lord's company: count it, and raise him a rank when he's earned one. */
    public void scored() {
        if (owner() == null) return;
        int before = rankFor(kills);
        kills++;
        int rank = rankFor(kills);
        if (rank == before) return;
        if (plainName.isEmpty()) plainName = baseName();
        String epithet = rank >= 2 ? " " + EPITHETS[Math.floorMod(this.getUUID().hashCode(), EPITHETS.length)] : "";
        setBaseName(rankName(rank) + " " + plainName + epithet);
        applyRank(rank);
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            net.aiagent256.stormhold.holding.HoldingEvents.tell(sl, owner(), net.minecraft.network.chat.Component.literal(plainName
                    + " has proven himself: " + baseName() + ", " + kills + " kills. " + switch (rank) {
                        case 1 -> "Tougher and harder-hitting than a fresh man.";
                        case 2 -> "Men steady when he's beside them.";
                        default -> "They'll sing of him.";
                    }).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    private void applyRank(int rank) {
        double hp = rank == 1 ? 4 : rank == 2 ? 8 : rank >= 3 ? 14 : 0, dmg = rank == 1 ? 1 : rank == 2 ? 2.5 : rank >= 3 ? 4 : 0;
        var health = this.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.removeModifier(VET_HEALTH);
            if (hp > 0) health.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(VET_HEALTH, "Stormhold veteran", hp,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
            this.heal((float) hp);
        }
        var attack = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.removeModifier(VET_DAMAGE);
            if (dmg > 0) attack.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(VET_DAMAGE, "Stormhold veteran", dmg,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        }
        var knock = this.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knock != null) {
            knock.removeModifier(VET_KNOCKBACK);
            if (rank >= 2) knock.addPermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(VET_KNOCKBACK, "Stormhold veteran",
                    0.1 * rank, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        }
    }

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
        // in the lists a challenger fights the one man he rode in for, and nobody fights him
        if (net.aiagent256.stormhold.holding.Tourney.is(this)) return net.aiagent256.stormhold.holding.Tourney.foeOf(this, e);
        if (net.aiagent256.stormhold.holding.Tourney.is(e)) return false;
        // a company man only picks fights his orders allow (close to his lord, inside the building he's clearing)
        if (owner() != null && !net.aiagent256.stormhold.holding.Orders.inScope(this, e)) return false;
        if (e instanceof GarrisonEntity g) {
            if (g.side.isEmpty() || g.side.equals(side)) return false;
            return !atPeaceWith(g);
        }
        if (e instanceof Player p) {
            if (p.isCreative() || p.isSpectator()) return false;
            if (!isHouse() || side.equals(Sides.player(p.getUUID()))) return false;
            // a house leaves you alone unless it holds you hostile or you've just drawn blood here: the same
            // rule its gate keeps, so a wary traveller let in isn't cut down in the yard
            return alarmedAt(Sides.player(p.getUUID())) || !toleratesPlayer(p);
        }
        String rs = RecruitsCompat.sideOf(e);
        if (rs != null) return !rs.equals(side) && (isHouse() || Sides.isPlayer(rs));
        // a wild dragon is everyone's problem
        if (net.aiagent256.stormhold.holding.Dragons.isWild(e)) return true;
        // monsters inside or near the walls are everyone's problem; knights leave creepers to the archers
        if (e instanceof Enemy) return role().ranged || !(e instanceof Creeper);
        return false;
    }

    /** The house this soldier serves counts the player a friend. Checked at most every half second. */
    /** Not a friend, but not an enemy either: the house's standing with the player is above hostile. */
    public boolean toleratesPlayer(Player p) {
        if (!isHouse() || !(this.level() instanceof net.minecraft.server.level.ServerLevel sl)) return false;
        String hid = Sides.houseId(side);
        if (hid == null) return false;
        return !net.aiagent256.stormhold.house.Standing.hostile(net.aiagent256.stormhold.holding.HoldingManager.get(sl), hid, p.getUUID());
    }

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
     * A house's soldier and a player's soldier leave each other alone unless the house holds the player
     * hostile, one of them has just been hurt, or one is already fighting the other's side.
     */
    private boolean atPeaceWith(GarrisonEntity g) {
        String house = isHouse() ? side : g.isHouse() ? g.side : null;
        String player = !isHouse() ? side : !g.isHouse() ? g.side : null;
        if (house == null || player == null) return false;
        GarrisonEntity houseMan = isHouse() ? this : g, playerMan = isHouse() ? g : this;
        if (houseMan.alarmedAt(player) || playerMan.alarmedAt(house)) return false;
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
        peaceCached = !net.aiagent256.stormhold.house.Standing.hostile(net.aiagent256.stormhold.holding.HoldingManager.get(sl), hid, pid);
        return peaceCached;
    }

    @Override
    public boolean isAlliedTo(Entity other) {
        return isAlly(other) || super.isAlliedTo(other);
    }

    /**
     * The whole garrison turns on whoever struck one of them, for half a minute. A monster hitting a guard
     * raises the alarm against nobody in particular: the guards fight the monster, and a friendly visitor
     * standing nearby is left alone.
     */
    public void alarm(LivingEntity attacker) {
        this.alarmTicks = 600;
        this.alarmSide = blame(attacker);
        if (this.getTarget() == null || !this.getTarget().isAlive()) {
            if (isHostileTo(attacker)) this.setTarget(attacker);
        }
    }

    /** Is the alarm up against this side right now? */
    public boolean alarmedAt(@Nullable String side) {
        return alarmTicks > 0 && side != null && side.equals(alarmSide);
    }

    /** The side to hold responsible for a blow: the player, or the player whose man struck it. */
    @Nullable
    private static String blame(LivingEntity attacker) {
        if (attacker instanceof Player p) return Sides.player(p.getUUID());
        if (attacker instanceof GarrisonEntity g) return g.owner() != null ? Sides.player(g.owner()) : (g.side.isEmpty() ? null : g.side);
        String rs = RecruitsCompat.sideOf(attacker);
        return rs;
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
            if (isHouse() && blamed != null && !net.aiagent256.stormhold.holding.Tourney.is(this)
                    && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                Player p = blamed;
                String hid = Sides.houseId(side);
                if (hid != null) {
                    var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(sl);
                    if (net.aiagent256.stormhold.holding.Truces.active(mgr, hid, p.getUUID()))
                        net.aiagent256.stormhold.holding.Truces.broken(sl, mgr, hid, p.getUUID(), "you attacked their men");
                    int delta = net.aiagent256.stormhold.house.Standing.friendly(mgr, hid, p.getUUID())
                            ? net.aiagent256.stormhold.house.Standing.HIT_GUARD : -3;
                    net.aiagent256.stormhold.house.Standing.change(sl, mgr, hid, p.getUUID(), delta, "you attacked their soldier");
                    alarmTicks = 600;
                    alarmSide = Sides.player(p.getUUID());
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

    /** A lord sells supplies to anyone his house doesn't hate: an emerald buys 8 bread, or 4 iron ingots when sneaking. */
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
        // a lord trades with anyone his house doesn't hold hostile; only a true enemy is turned away
        if (!isFriendlyTo(player) && !toleratesPlayer(player)) {
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

    /** A bowman at his embrasure braces against the parapet: no knocking him out through the crenel. */
    @Override
    public void knockback(double strength, double x, double z) {
        if (role().ranged && holdsPosition()) return;
        super.knockback(strength, x, z);
    }

    /** Nor does a comrade shouldering past on the walk push him off his sill. */
    @Override
    public boolean isPushable() {
        return !(role().ranged && holdsPosition()) && super.isPushable();
    }

    private static final EquipmentSlot[] KIT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

    /** Each piece of kit at its drop chance, worn the way the game wears dropped kit. Public for tests. */
    public int stripKit() {
        int n = 0;
        for (EquipmentSlot s : KIT) {
            ItemStack st = this.getItemBySlot(s);
            float chance = this.getEquipmentDropChance(s);
            if (st.isEmpty() || chance <= 0 || chance > 1 || net.minecraft.world.item.enchantment.EnchantmentHelper.hasVanishingCurse(st)
                    || this.random.nextFloat() >= chance) continue;
            ItemStack drop = st.copy();
            if (drop.isDamageableItem())
                drop.setDamageValue(drop.getMaxDamage() - this.random.nextInt(1 + this.random.nextInt(Math.max(drop.getMaxDamage() - 3, 1))));
            this.spawnAtLocation(drop);
            this.setItemSlot(s, ItemStack.EMPTY);
            n++;
        }
        return n;
    }

    /** A company man drops everything he carries: it's the lord's kit. */
    @Override
    protected void dropCustomDeathLoot(DamageSource src, int looting, boolean recentlyHit) {
        if (owner() == null) {
            super.dropCustomDeathLoot(src, looting, recentlyHit);
            // a house's man carries coin, for the lord who cuts him down or whose men do
            if (this.level() instanceof net.minecraft.server.level.ServerLevel sl && Sides.playerId(side) == null) {
                Entity k = src.getEntity();
                // the game only strips kit off men the player hit himself; his own men strip it too, at the same chance
                if (!recentlyHit && k instanceof GarrisonEntity g && Sides.playerId(g.side()) != null) stripKit();
                boolean lord = k instanceof Player || (k instanceof GarrisonEntity g && Sides.playerId(g.side()) != null);
                if (lord) {
                    int n = net.aiagent256.stormhold.holding.Rewards.purse(this.random, role(), side,
                            net.aiagent256.stormhold.holding.Frontier.zone(sl, this.blockPosition()));
                    if (n > 0) this.spawnAtLocation(new ItemStack(Items.EMERALD, n));
                }
            }
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
        // one of a castle's men who finds his place taken (a second lord, a man over the count) stands down
        if (this.tickCount % 40 == 7 && this.level() instanceof net.minecraft.server.level.ServerLevel sl
                && !net.aiagent256.stormhold.holding.Garrisons.belongs(sl, this)) {
            this.discard();
            return;
        }
        // a dragon's carcass is still "alive" to the game; the men stop hacking at it once it's down
        if (this.tickCount % 20 == 0 && net.aiagent256.stormhold.holding.Dragons.isCarcass(this.getTarget())) this.setTarget(null);
        if (alarmTicks > 0) alarmTicks--;
        if (backOffTicks > 0) backOffTicks--;
        if (sortieTicks > 0) sortieTicks--;
        holdLip();
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
                if (foe != null && foe != this && !isAlly(foe) && !net.aiagent256.stormhold.holding.Tourney.is(foe)
                        && !(foe instanceof Player fp && (fp.isCreative() || fp.isSpectator()))) {
                    // two of his own on the man fighting his lord is enough: the rest take the foes around him
                    var friends = net.aiagent256.stormhold.entity.ai.Engagement.friends(this);
                    if (net.aiagent256.stormhold.entity.ai.Engagement.on(friends, foe) < net.aiagent256.stormhold.entity.ai.Engagement.most(this)) {
                        this.setTarget(foe);
                    } else {
                        LivingEntity taken = foe;
                        var around = this.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16, 6, 16),
                                e -> e != taken && e.isAlive() && this.isHostileTo(e));
                        LivingEntity other = net.aiagent256.stormhold.entity.ai.Engagement.pick(this, around);
                        this.setTarget(other != null ? other : foe);
                    }
                }
            }
        }
        // in a fight: one too many on his foe and he finds his own; and room at his elbow
        if (role() != Role.COURIER && role() != Role.LORD && this.getTarget() != null) {
            var order = owner() != null ? net.aiagent256.stormhold.holding.Orders.peek(owner()) : null;
            if ((this.tickCount + this.getId()) % 25 == 0 && (order == null || order.target == null))
                net.aiagent256.stormhold.entity.ai.Engagement.rebalance(this);
            if (!role().ranged && this.onGround() && !climbing && !this.onClimbable() && !this.isInWater() && !holdsPosition()) {
                var push = net.aiagent256.stormhold.entity.ai.Engagement.elbowRoom(this);
                if (push != Vec3.ZERO) this.setDeltaMovement(this.getDeltaMovement().add(push));
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
        if (hasLip()) { tag.putInt("LipX", lipX); tag.putInt("LipZ", lipZ); }
        tag.putInt("Tier", tier);
        tag.putInt("Renown", renown);
        tag.putInt("Color", color.getId());
        if (owner() != null) tag.putUUID("Owner", owner());
        if (!baseName.isEmpty()) tag.putString("BaseName", baseName);
        if (kills > 0) tag.putInt("Kills", kills);
        if (!plainName.isEmpty()) tag.putString("PlainName", plainName);
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
        setLip(tag.getInt("LipX"), tag.getInt("LipZ"));
        tier = Math.max(1, tag.getInt("Tier"));
        if (tag.contains("Renown")) renown = Math.max(1, Math.min(9, tag.getInt("Renown")));
        color = DyeColor.byId(tag.getInt("Color"));
        if (tag.hasUUID("Owner")) setOwner(tag.getUUID("Owner"));
        baseName = tag.getString("BaseName");
        kills = tag.getInt("Kills");
        plainName = tag.getString("PlainName");
        var pt = tag.getList("Pouch", 10);
        for (int i = 0; i < pouch.size() && i < pt.size(); i++) pouch.set(i, ItemStack.of(pt.getCompound(i)));
    }
}
