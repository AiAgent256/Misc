package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Loadouts;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.aiagent256.stormhold.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Turns holding counts into soldiers on the walls, and back. */
public final class Garrisons {

    public static void pruneLive(ServerLevel level, Map<UUID, Role> live) {
        live.keySet().removeIf(id -> {
            Entity e = level.getEntity(id);
            return e == null || !e.isAlive();
        });
    }

    public static int liveCount(Map<UUID, Role> live, Role r) {
        int n = 0;
        for (Role x : live.values()) if (x == r) n++;
        return n;
    }

    /** Spawn missing soldiers at loaded posts, a few per call. */
    /** Holdings this wide only keep soldiers near players; the rest of the garrison exists as counts. */
    public static final int DISTRICT_SIZE = 150;
    public static final double DISTRICT_RADIUS = 96;

    static boolean nearPlayer(ServerLevel level, BlockPos p, double r) {
        for (ServerPlayer sp : level.players()) {
            if (!sp.isSpectator() && sp.blockPosition().distSqr(p) < r * r) return true;
        }
        return false;
    }

    public static void materialize(ServerLevel level, HoldingManager mgr, Holding h) {
        pruneLive(level, h.live);
        if (h.live.isEmpty()) h.usedPosts.clear();
        boolean city = h.box.getXSpan() > DISTRICT_SIZE || h.box.getZSpan() > DISTRICT_SIZE;
        if (city) {
            // soldiers that wandered out of every player's district fade back into the count
            for (UUID id : new java.util.ArrayList<>(h.live.keySet())) {
                Entity e = level.getEntity(id);
                if (e instanceof GarrisonEntity ge && ge.getTarget() == null && !nearPlayer(level, e.blockPosition(), DISTRICT_RADIUS + 40)) {
                    if (ge.post() != null) h.usedPosts.remove(ge.post());
                    h.live.remove(id);
                    e.discard();
                }
            }
        }
        int budget = 6;
        if (h.isHouseOwned() && h.lordAlive && liveCount(h.live, Role.LORD) == 0 && level.isLoaded(h.lordPos)) {
            spawn(level, mgr, h, Role.LORD, h.lordPos, h.owner, true);
            budget--;
        }
        for (Role r : Role.TROOPS) {
            int have = liveCount(h.live, r);
            int want = h.count(r);
            while (have < want && budget > 0) {
                BlockPos p = pickPost(level, h, r);
                if (p == null) break;
                spawn(level, mgr, h, r, p, h.owner, r.ranged);
                have++;
                budget--;
            }
        }
    }

    @Nullable
    public static BlockPos pickPost(ServerLevel level, Holding h, Role r) {
        List<BlockPos> list = h.posts.get(r);
        if (list == null || list.isEmpty()) {
            Role fallback = switch (r) {
                case CROSSBOWMAN -> Role.ARCHER;
                case CAPTAIN, ARCHER -> Role.KNIGHT;
                default -> null;
            };
            if (fallback == null) return h.rallyPos.equals(BlockPos.ZERO) ? null : h.rallyPos;
            return pickPost(level, h, fallback);
        }
        boolean city = h.box.getXSpan() > DISTRICT_SIZE || h.box.getZSpan() > DISTRICT_SIZE;
        for (int i = 0; i < (city ? 40 : 16); i++) {
            BlockPos p = list.get(level.random.nextInt(list.size()));
            if (city && !nearPlayer(level, p, DISTRICT_RADIUS)) continue;
            if (!h.usedPosts.contains(p) && level.isLoaded(p)) {
                h.usedPosts.add(p);
                BlockPos free = standable(level, p);
                if (free != null) return free;
            }
        }
        if (city) return null;
        BlockPos p = list.get(level.random.nextInt(list.size()));
        return level.isLoaded(p) ? standable(level, p) : null;
    }

    /**
     * The nearest spot to {@code p} (within two blocks across, three up, one down) where a mob can
     * stand without being inside a wall. Null if there's none, e.g. something else was built over the post.
     */
    @Nullable
    public static BlockPos standable(ServerLevel level, BlockPos p) {
        int[] ys = {0, 1, -1, 2, 3};
        for (int r = 0; r <= 2; r++) {
            for (int dy : ys) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos q = p.offset(dx, dy, dz);
                        if (fits(level, q)) return q;
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, BlockPos q) {
        if (!level.isLoaded(q) || !level.isLoaded(q.above())) return false;
        BlockState feet = level.getBlockState(q), head = level.getBlockState(q.above());
        var fs = feet.getCollisionShape(level, q);
        if (!fs.isEmpty() && fs.max(net.minecraft.core.Direction.Axis.Y) > 0.5) return false;
        if (!head.getCollisionShape(level, q.above()).isEmpty()) return false;
        if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) return false;
        if (!fs.isEmpty()) return true;
        BlockPos below = q.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    @Nullable
    public static GarrisonEntity spawn(ServerLevel level, HoldingManager mgr, @Nullable Holding h, Role r, BlockPos p,
                                       String side, boolean hold) {
        EntityType<? extends GarrisonEntity> type = ModEntities.typeFor(r, Sides.isHouse(side));
        GarrisonEntity e = type.create(level);
        if (e == null) return null;
        BlockPos free = standable(level, p);
        if (free != null) p = free;
        House house = mgr.houseOf(side);
        DyeColor color = mgr.colorOf(side);
        int tier = h != null ? h.tier : 2;
        int renown = h != null ? h.renown : 2;
        e.setup(side, h != null ? h.id : null, tier, renown, color, p, hold);
        e.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.EVENT, null, null);
        Loadouts.equip(e, r, tier, renown, color, house);
        double hp = health(r, tier) * (1.0 + 0.12 * (renown - 1));
        AttributeInstance mh = e.getAttribute(Attributes.MAX_HEALTH);
        if (mh != null) mh.setBaseValue(hp);
        e.setHealth((float) hp);
        if (r == Role.LORD && h != null) e.setCustomName(Component.literal(h.lordName));
        if (r == Role.CAPTAIN) e.setCustomName(Component.literal("Ser " + NameGen.first(level.random)));
        e.setPersistenceRequired();
        level.addFreshEntity(e);
        if (h != null) h.live.put(e.getUUID(), r);
        return e;
    }

    public static double health(Role r, int tier) {
        int t = Math.max(1, Math.min(Holding.MAX_TIER, tier));
        return switch (r) {
            case LORD -> new double[]{120, 170, 230, 320}[t - 1];
            case CAPTAIN -> new double[]{44, 60, 80, 96}[t - 1];
            case KNIGHT -> new double[]{28, 32, 38, 42}[t - 1];
            case ARCHER -> new double[]{20, 24, 26, 28}[t - 1];
            case CROSSBOWMAN -> new double[]{22, 26, 30, 32}[t - 1];
            case COURIER -> 24;
        };
    }

    public static void discardLive(ServerLevel level, Map<UUID, Role> live, boolean poof) {
        for (UUID id : new ArrayList<>(live.keySet())) {
            Entity e = level.getEntity(id);
            if (e != null) {
                if (poof) level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 1, e.getZ(), 8, 0.3, 0.6, 0.3, 0.02);
                e.discard();
            }
        }
        live.clear();
    }

    /** Extra soldiers that appear at a point (lord's guard, counterattack). */
    public static void reinforce(ServerLevel level, HoldingManager mgr, Holding h, Role r, int n, BlockPos at,
                                 @Nullable LivingEntity target) {
        for (int i = 0; i < n; i++) {
            BlockPos p = at.offset(level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) p = at;
            h.add(r, 1);
            GarrisonEntity e = spawn(level, mgr, h, r, p, h.owner, false);
            if (e != null && target != null) e.setTarget(target);
        }
        mgr.setDirty();
    }

    // ------------------------------------------------------------ banners

    public static ItemStack bannerItem(@Nullable House house) {
        ItemStack s = new ItemStack(Items.WHITE_BANNER);
        if (house != null) {
            CompoundTag bet = new CompoundTag();
            bet.put("Patterns", house.patterns());
            s.addTagElement("BlockEntityTag", bet);
        }
        return s;
    }

    @Nullable
    private static Block colored(DyeColor c, boolean wall) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("minecraft", c.getName() + (wall ? "_wall_banner" : "_banner")));
    }

    /** Repaint every banner in the castle in the owner's colours. */
    public static void recolorBanners(ServerLevel level, HoldingManager mgr, Holding h) {
        DyeColor color = mgr.colorOf(h.owner);
        House house = mgr.houseOf(h.owner);
        ItemStack pattern = bannerItem(house);
        boolean all = true;
        for (BlockPos b : h.banners) {
            if (!level.isLoaded(b)) {
                all = false;
                continue;
            }
            BlockState s = level.getBlockState(b);
            if (!(s.getBlock() instanceof WallBannerBlock)) continue;
            Block nb = colored(color, true);
            if (nb == null) continue;
            level.setBlock(b, nb.defaultBlockState().setValue(WallBannerBlock.FACING, s.getValue(WallBannerBlock.FACING)), 3);
            applyPattern(level, b, pattern, color);
        }
        if (all) h.bannersDirty = false;
        placeStand(level, mgr, h);
    }

    private static void applyPattern(ServerLevel level, BlockPos b, ItemStack pattern, DyeColor color) {
        BlockEntity be = level.getBlockEntity(b);
        if (be instanceof BannerBlockEntity bbe) {
            bbe.fromItem(pattern, color);
            bbe.setChanged();
            level.sendBlockUpdated(b, level.getBlockState(b), level.getBlockState(b), 3);
        }
    }

    /** Banner stand block with the owner's standard flying above it. */
    public static void placeStand(ServerLevel level, HoldingManager mgr, Holding h) {
        BlockPos p = h.bannerPos;
        if (p.equals(BlockPos.ZERO) || !level.isLoaded(p)) return;
        if (!level.getBlockState(p).is(ModBlocks.BANNER_STAND.get())) {
            BlockState here = level.getBlockState(p);
            if (!here.isAir() && !here.canBeReplaced()) return;
            level.setBlock(p, ModBlocks.BANNER_STAND.get().defaultBlockState(), 3);
        }
        BlockPos up = p.above();
        BlockState s = level.getBlockState(up);
        if (s.isAir() || s.getBlock() instanceof BannerBlock) {
            DyeColor color = mgr.colorOf(h.owner);
            Block nb = colored(color, false);
            if (nb == null) return;
            int rot = s.getBlock() instanceof BannerBlock ? s.getValue(BannerBlock.ROTATION) : level.random.nextInt(16);
            level.setBlock(up, nb.defaultBlockState().setValue(BannerBlock.ROTATION, rot), 3);
            applyPattern(level, up, bannerItem(mgr.houseOf(h.owner)), color);
        }
    }

    // ------------------------------------------------------------ life in the bailey

    private static final VillagerProfession[] TRADES = {VillagerProfession.ARMORER, VillagerProfession.WEAPONSMITH,
            VillagerProfession.TOOLSMITH, VillagerProfession.CLERIC, VillagerProfession.BUTCHER, VillagerProfession.FARMER,
            VillagerProfession.FLETCHER, VillagerProfession.MASON, VillagerProfession.SHEPHERD,
            VillagerProfession.LEATHERWORKER, VillagerProfession.LIBRARIAN, VillagerProfession.FARMER};

    public static void spawnVillagers(ServerLevel level, Holding h) {
        if (h.villagersSpawned || h.layout == null || !Config.VILLAGERS_ENABLED.get()) {
            h.villagersSpawned = true;
            return;
        }
        Layout l = DataRegistries.layout(h.layout);
        List<BlockPos> knights = h.posts.get(Role.KNIGHT);
        if (l == null || knights == null || knights.isEmpty()) {
            h.villagersSpawned = true;
            return;
        }
        int groundY = h.box.minY() + l.ground;
        List<BlockPos> ground = new ArrayList<>();
        for (BlockPos p : knights) if (p.getY() == groundY && level.isLoaded(p)) ground.add(p);
        if (ground.size() < 3) return;
        int n = h.tier * 3;
        for (int i = 0; i < n; i++) {
            BlockPos p = standable(level, ground.get(level.random.nextInt(ground.size())));
            if (p == null) continue;
            Villager v = EntityType.VILLAGER.create(level);
            if (v == null) continue;
            v.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
            v.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.STRUCTURE, null, null);
            VillagerProfession prof = TRADES[level.random.nextInt(TRADES.length)];
            v.setVillagerData(v.getVillagerData().setProfession(prof).setLevel(2));
            v.setVillagerXp(10);
            v.setPersistenceRequired();
            level.addFreshEntity(v);
        }
        h.villagersSpawned = true;
    }

    private Garrisons() {
    }
}
