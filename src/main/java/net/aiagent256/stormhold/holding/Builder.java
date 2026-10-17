package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.event.CommonEvents;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.worldgen.CastleTilePiece;
import net.aiagent256.stormhold.worldgen.ThemeProcessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Master Builder: once a castle is yours he's at the War Table, and you set him to work. Repair puts
 * back what was broken getting in (walls, the portcullis, the gate) block by block, in the castle's own
 * style. Fortify adds defences in three stages: a palisade of stakes, a moat, hoardings over the archers'
 * posts; each stage lets the castle hold more men. Both eat stone and iron from the stores.
 */
public final class Builder {
    public static final String NONE = "NONE", REPAIR = "REPAIR", FORTIFY = "FORTIFY";
    public static final int MAX_FORTIFY = 3;
    /** Blocks placed per second. */
    private static final int RATE = 24, SLOW_RATE = 5;
    private static final ResourceLocation[] STONE = {
            new ResourceLocation("minecraft", "cobblestone"), new ResourceLocation("minecraft", "stone_bricks"),
            new ResourceLocation("minecraft", "stone"), new ResourceLocation("minecraft", "andesite")};
    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "iron_ingot");

    /** Stone and iron each stage of fortification costs. */
    public static int[] fortifyCost(int stage) {
        return new int[]{48 * stage, 6 * stage};
    }

    // ------------------------------------------------------------ the man himself

    public static void ensureBuilder(ServerLevel level, Holding h) {
        if (!h.isPlayerOwned() || h.warTablePos.equals(BlockPos.ZERO) || !level.isLoaded(h.warTablePos)) return;
        if (h.builder != null && level.getEntity(h.builder) != null) return;
        if (h.builderName.isEmpty()) h.builderName = NameGen.first(level.random);
        BlockPos at = Garrisons.standable(level, h.warTablePos.east());
        if (at == null) at = Garrisons.standable(level, h.warTablePos);
        if (at == null) return;
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return;
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null, null);
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.MASON).setLevel(5));
        v.setVillagerXp(250);
        v.setCustomName(Component.literal("Master Builder " + h.builderName));
        v.setCustomNameVisible(true);
        v.setPersistenceRequired();
        level.addFreshEntity(v);
        h.builder = v.getUUID();
    }

    // ------------------------------------------------------------ orders

    public static void order(ServerLevel level, HoldingManager mgr, Holding h, String work, net.minecraft.world.entity.player.Player player) {
        if (work.equals(FORTIFY)) {
            if (h.fortify >= MAX_FORTIFY) {
                player.sendSystemMessage(Component.literal(h.name + " is as strong as he can make it."));
                return;
            }
            int[] cost = fortifyCost(h.fortify + 1);
            java.util.UUID owner = h.ownerPlayer();
            int haveStone = stone(mgr, h), haveIron = owner == null ? h.stock(IRON) : Stores.realmStock(mgr, h, owner, IRON);
            if (haveStone < cost[0] || haveIron < cost[1]) {
                player.sendSystemMessage(Component.literal("Stage " + (h.fortify + 1) + " needs " + cost[0] + " stone and " + cost[1]
                        + " iron in the realm's stores; you have " + haveStone + " and " + haveIron + ".").withStyle(ChatFormatting.RED));
                return;
            }
            takeStone(mgr, h, cost[0]);
            if (owner != null) Stores.take(mgr, h, owner, IRON, cost[1]);
            else h.addStock(IRON, -cost[1]);
        }
        h.work = work;
        h.workQueue = null;
        h.workDone = 0;
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold("Master Builder " + h.builderName + (work.equals(REPAIR)
                ? " surveys the damage at " + h.name + " and sets his men to the walls."
                : " begins " + stageName(h.fortify + 1) + " at " + h.name + ".")));
    }

    public static String stageName(int stage) {
        return switch (stage) {
            case 1 -> "a palisade of stakes";
            case 2 -> "the moat";
            case 3 -> "hoardings over the walls";
            default -> "nothing";
        };
    }

    /** Stone of any kind across the realm, this castle first. */
    public static int stone(HoldingManager mgr, Holding h) {
        java.util.UUID owner = h.ownerPlayer();
        int n = 0;
        for (ResourceLocation s : STONE) n += owner == null ? h.stock(s) : Stores.realmStock(mgr, h, owner, s);
        return n;
    }

    private static void takeStone(HoldingManager mgr, Holding h, int n) {
        java.util.UUID owner = h.ownerPlayer();
        for (ResourceLocation s : STONE) {
            int have = owner == null ? h.stock(s) : Stores.realmStock(mgr, h, owner, s);
            int take = Math.min(n, have);
            if (take > 0) {
                if (owner == null) h.addStock(s, -take);
                else Stores.take(mgr, h, owner, s, take);
            }
            n -= take;
            if (n <= 0) return;
        }
    }

    // ------------------------------------------------------------ the work, once a second

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.isPlayerOwned()) {
            // a house's own masons: once the castle has been quiet for a minute after taking damage, they put it back
            if (h.work.equals(NONE)) {
                long now = level.getGameTime();
                if (h.damagedAt <= 0 || now - h.damagedAt < 1200 || h.dragonAt != 0 || h.layout == null) return;
                h.damagedAt = 0;
                h.work = REPAIR;
                h.workQueue = null;
                h.workDone = 0;
                mgr.setDirty();
            } else if (!h.work.equals(REPAIR)) {
                h.work = NONE;
                return;
            }
        }
        if (h.work.equals(NONE)) return;
        ensureBuilder(level, h);
        if (h.workQueue == null) {
            h.workQueue = new ArrayDeque<>();
            if (h.work.equals(REPAIR)) {
                if (!survey(level, h)) {
                    h.work = NONE;
                    tell(level, h, "Master Builder " + h.builderName + " finds nothing to repair at " + h.name + ".");
                    return;
                }
                if (!h.isPlayerOwned() && !h.workQueue.isEmpty())
                    HoldingEvents.announceNear(level, h.center(), 160, HoldingEvents.gold("The masons of " + h.name + " are at work on the walls."));
            } else {
                plan(level, h, h.fortify + 1);
            }
            if (h.workQueue.isEmpty()) {
                finish(level, mgr, h);
                return;
            }
        }
        // stone keeps the pace up; without it the men scavenge and go slowly
        int rate = RATE;
        if (h.work.equals(REPAIR) && h.isPlayerOwned()) {
            if (stone(mgr, h) > 0) takeStone(mgr, h, 1);
            else rate = SLOW_RATE;
        }
        int placed = 0;
        while (placed < rate && !h.workQueue.isEmpty()) {
            Job j = h.workQueue.poll();
            if (!level.isLoaded(j.pos)) {
                h.workQueue.addLast(j);   // come back when it's loaded
                if (++placed > 1) break;
                continue;
            }
            BlockState now = level.getBlockState(j.pos);
            if (j.onlyIfAir && !(now.isAir() || now.is(CommonEvents.SIEGE_PLACEABLE) || !now.getFluidState().isEmpty()
                    || now.is(Blocks.FIRE) || now.canBeReplaced())) continue;
            level.setBlock(j.pos, j.state, 3);
            placed++;
            h.workDone++;
        }
        if (placed > 0) level.playSound(null, h.warTablePos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.6F, 0.8F);
        if (h.workQueue.isEmpty()) finish(level, mgr, h);
        mgr.setDirty();
    }

    private static void finish(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.work.equals(REPAIR)) {
            h.gates = null;
            if (h.isPlayerOwned()) tell(level, h, "The repairs at " + h.name + " are done: " + h.workDone + " blocks set back in place.");
            else if (h.workDone > 0) HoldingEvents.announceNear(level, h.center(), 160, HoldingEvents.gold("The walls of " + h.name + " stand whole again."));
        } else {
            h.fortify = Math.min(MAX_FORTIFY, h.fortify + 1);
            tell(level, h, "Master Builder " + h.builderName + " has finished " + stageName(h.fortify) + " at " + h.name
                    + ". The castle can hold more men now.");
        }
        h.work = NONE;
        h.workQueue = null;
        mgr.setDirty();
    }

    private static void tell(ServerLevel level, Holding h, String msg) {
        HoldingEvents.tell(level, h.ownerPlayer(), HoldingEvents.gold(msg));
    }

    public record Job(BlockPos pos, BlockState state, boolean onlyIfAir) {
    }

    // ------------------------------------------------------------ repair: compare the castle to its plans

    /** Everything the layout says should be there and isn't. False if this castle has no plans (a founded holding). */
    private static boolean survey(ServerLevel level, Holding h) {
        Layout l = h.layout != null ? DataRegistries.layout(h.layout) : null;
        if (l == null) return false;
        Map<Block, DataRegistries.Weighted> theme = DataRegistries.theme(h.theme == null ? "" : h.theme);
        var lookup = level.holderLookup(Registries.BLOCK);
        BlockPos origin = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
        int nx = (l.sizeX + 31) / 32, nz = (l.sizeZ + 31) / 32;
        List<Job> jobs = new ArrayList<>();
        int tiles = 0, scanned = 0, differ = 0;
        for (int tx = 0; tx < nx; tx++) {
            for (int tz = 0; tz < nz; tz++) {
                var t = level.getStructureManager().get(CastleTilePiece.tileId(l.name, tx, tz));
                if (t.isEmpty()) continue;
                tiles++;
                CompoundTag tag = t.get().save(new CompoundTag());
                ListTag palette = tag.getList("palette", 10);
                BlockState[] states = new BlockState[palette.size()];
                for (int i = 0; i < states.length; i++) states[i] = NbtUtils.readBlockState(lookup, palette.getCompound(i));
                ListTag blocks = tag.getList("blocks", 10);
                BlockPos tileOrigin = origin.offset(tx * 32, 0, tz * 32);
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag b = blocks.getCompound(i);
                    BlockState want = states[b.getInt("state")];
                    if (want.isAir() || skip(want)) continue;
                    ListTag p = b.getList("pos", 3);
                    BlockPos pos = tileOrigin.offset(p.getInt(0), p.getInt(1), p.getInt(2));
                    if (!level.isLoaded(pos)) continue;
                    DataRegistries.Weighted w = theme.get(want.getBlock());
                    if (w != null) want = ThemeProcessor.copy(want, w.pick(Mth.getSeed(pos)));
                    BlockState have = level.getBlockState(pos);
                    scanned++;
                    if (have.getBlock() == want.getBlock()) continue;
                    differ++;
                    if (!(have.isAir() || have.is(CommonEvents.SIEGE_PLACEABLE) || !have.getFluidState().isEmpty() || have.is(Blocks.FIRE)
                            || have.canBeReplaced())) continue;
                    jobs.add(new Job(pos, want, true));
                }
            }
        }
        // bottom up, so walls grow rather than hang
        jobs.sort((a, b) -> a.pos.getY() != b.pos.getY() ? Integer.compare(a.pos.getY(), b.pos.getY())
                : Integer.compare(a.pos.getX() + a.pos.getZ(), b.pos.getX() + b.pos.getZ()));
        h.workQueue.addAll(jobs);
        Stormhold.LOGGER.info("Stormhold: {} needs {} blocks of repair ({} tiles, {} blocks checked, {} differ, origin {})", h.name,
                jobs.size(), tiles, scanned, differ, origin);
        return true;
    }

    /** Things that change in play and shouldn't be "repaired": banners, containers, beds, doors, crops, torches. */
    public static boolean skip(BlockState s) {
        return s.is(BlockTags.BANNERS) || s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS)
                || s.is(Blocks.CHEST) || s.is(Blocks.BARREL) || s.is(BlockTags.CROPS) || s.is(Blocks.FARMLAND)
                || s.is(BlockTags.CANDLES) || s.is(Blocks.LANTERN) || s.is(BlockTags.LEAVES) || s.is(Blocks.WATER)
                || s.is(Blocks.CAMPFIRE) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.HAY_BLOCK) || s.is(BlockTags.FLOWER_POTS) || s.is(Blocks.COBWEB);
    }

    // ------------------------------------------------------------ fortify: the three stages

    private static void plan(ServerLevel level, Holding h, int stage) {
        switch (stage) {
            case 1 -> palisade(level, h);
            case 2 -> moat(level, h);
            case 3 -> hoardings(level, h);
            default -> {
            }
        }
    }

    /** Where the road leaves the gate; the fortifications leave a causeway there. */
    private static boolean onCauseway(Holding h, int x, int z, int width) {
        BlockPos out = Campaigns.gateOutside(h);
        BlockPos c = h.center();
        double dx = out.getX() - c.getX(), dz = out.getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return false;
        // distance from the line through the centre and the gate, on the gate's side
        double px = x - c.getX(), pz = z - c.getZ();
        double along = (px * dx + pz * dz) / len;
        double across = Math.abs(px * dz - pz * dx) / len;
        return along > 0 && across <= width / 2.0;
    }

    private static int surface(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public static boolean natural(BlockState s) {
        if (s.is(Blocks.SNOW) || s.is(Blocks.GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN)) return true;
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.PODZOL);
    }

    /** Stage 1: a ring of sharpened stakes seven blocks out from the walls. */
    private static void palisade(ServerLevel level, Holding h) {
        int d = 7;
        int x0 = h.box.minX() - d, x1 = h.box.maxX() + d, z0 = h.box.minZ() - d, z1 = h.box.maxZ() + d;
        int causeway = 0, unloaded = 0, wet = 0, odd = 0, ok = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean ring = x == x0 || x == x1 || z == z0 || z == z1;
                if (!ring) continue;
                if (onCauseway(h, x, z, 9)) {
                    causeway++;
                    continue;
                }
                if (!level.isLoaded(new BlockPos(x, 64, z))) {
                    unloaded++;
                    continue;
                }
                int y = surface(level, x, z);
                BlockPos ground = new BlockPos(x, y - 1, z);
                BlockState gs = level.getBlockState(ground);
                if (!gs.getFluidState().isEmpty()) {
                    wet++;
                    continue;
                }
                if (!natural(gs)) {
                    odd++;
                    if (odd <= 3) Stormhold.LOGGER.info("Stormhold: palisade skips {} at {}", gs, ground);
                    continue;
                }
                ok++;
                // posts every third block, sharpened stakes between them (dripstone needs solid ground under it)
                boolean post = (x + z) % 3 == 0;
                if (post) {
                    h.workQueue.add(new Job(new BlockPos(x, y, z), Blocks.SPRUCE_FENCE.defaultBlockState(), true));
                    h.workQueue.add(new Job(new BlockPos(x, y + 1, z), Blocks.SPRUCE_FENCE.defaultBlockState(), true));
                } else {
                    h.workQueue.add(new Job(new BlockPos(x, y, z), Blocks.POINTED_DRIPSTONE.defaultBlockState(), true));
                }
            }
        }
        Stormhold.LOGGER.info("Stormhold: palisade at {}: {} cells, {} on the causeway, {} unloaded, {} wet, {} not natural ground",
                h.name, ok, causeway, unloaded, wet, odd);
    }

    /** Stage 2: a water-filled ditch two wide outside the palisade. */
    private static void moat(ServerLevel level, Holding h) {
        int d0 = 10, d1 = 11;
        int x0 = h.box.minX() - d1, x1 = h.box.maxX() + d1, z0 = h.box.minZ() - d1, z1 = h.box.maxZ() + d1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int dx = Math.max(h.box.minX() - x, x - h.box.maxX()), dz = Math.max(h.box.minZ() - z, z - h.box.maxZ());
                int dist = Math.max(dx, dz);
                if (dist < d0 || dist > d1 || onCauseway(h, x, z, 9) || !level.isLoaded(new BlockPos(x, 64, z))) continue;
                int y = surface(level, x, z);
                BlockPos ground = new BlockPos(x, y - 1, z);
                if (!level.getBlockState(ground).getFluidState().isEmpty() || !natural(level.getBlockState(ground))) continue;
                for (int k = 1; k <= 3; k++) {
                    BlockPos p = new BlockPos(x, y - k, z);
                    if (!natural(level.getBlockState(p))) break;
                    h.workQueue.add(new Job(p, k == 3 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState(), false));
                }
            }
        }
    }

    /** Stage 3: a timber roof over every archer's post on the walls, with a lantern under it. */
    private static void hoardings(ServerLevel level, Holding h) {
        List<BlockPos> posts = new ArrayList<>();
        for (Role r : new Role[]{Role.ARCHER, Role.CROSSBOWMAN}) {
            List<BlockPos> l = h.posts.get(r);
            if (l != null) posts.addAll(l);
        }
        int groundY = h.box.minY() + 8;
        for (BlockPos p : posts) {
            if (p.getY() < groundY || !level.isLoaded(p)) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos roof = p.offset(dx, 3, dz);
                    if (!level.getBlockState(roof).isAir()) continue;
                    h.workQueue.add(new Job(roof, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,
                            net.minecraft.world.level.block.state.properties.SlabType.TOP), true));
                }
            }
            BlockPos lamp = p.above(2);
            if (level.getBlockState(lamp).isAir()) {
                h.workQueue.add(new Job(lamp, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true), true));
            }
        }
    }

    private Builder() {
    }
}
