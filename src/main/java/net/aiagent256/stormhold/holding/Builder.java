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
    public static final String NONE = "NONE", REPAIR = "REPAIR", FORTIFY = "FORTIFY", LIGHT = "LIGHT", CLEAR = "CLEAR";
    /** The War Table's projects, in the order its project button cycles through them. */
    public static final String ROAD = "ROAD";
    public static final String[] PROJECTS = {LIGHT, CLEAR, ROAD};
    /** How far out from the walls the field is cleared, and the longest road the builder will lay. */
    public static final int CLEAR_REACH = 24, ROAD_MAX = 3000;
    /** Torches a coal makes, and road blocks a stone pays for. */
    public static final int TORCHES_PER_COAL = 4, ROAD_PER_STONE = 10;
    private static final ResourceLocation COAL = new ResourceLocation("minecraft", "coal"), CHARCOAL = new ResourceLocation("minecraft", "charcoal");
    public static final int MAX_FORTIFY = 3;
    /** Blocks placed per second. */
    private static final int RATE = 24, SLOW_RATE = 5, CLEAR_RATE = 80;
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
        if (h.builder != null && !Garrisons.gone(level, h.builder)) return;      // out of sight isn't gone
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
        Garrisons.seen(v);
        h.builder = v.getUUID();
    }

    // ------------------------------------------------------------ orders

    public static void order(ServerLevel level, HoldingManager mgr, Holding h, String work, net.minecraft.world.entity.player.Player player) {
        if (work.equals(ROAD)) {
            road(level, mgr, h, player);
            return;
        }
        if (!h.work.equals(NONE)) {
            player.sendSystemMessage(Component.literal("Master Builder " + h.builderName + " is " + doing(h) + " at " + h.name
                    + " already.").withStyle(ChatFormatting.RED));
            return;
        }
        SURVEYS.remove(h.id);
        if (work.equals(LIGHT) && coal(mgr, h) <= 0) {
            player.sendSystemMessage(Component.literal("Lighting " + h.name + " takes coal or charcoal in the realm's stores (one makes "
                    + TORCHES_PER_COAL + " torches). Set miners to work, or bring some.").withStyle(ChatFormatting.RED));
            return;
        }
        if (work.equals(FORTIFY)) {
            if (h.fortify >= MAX_FORTIFY) {
                player.sendSystemMessage(Component.literal(h.name + " is as strong as he can make it."));
                return;
            }
            int[] cost = fortifyCost(h.fortify + 1);
            if (Council.has(h.ownerPlayer(), Council.Seat.WORKS)) cost = new int[]{cost[0] * 3 / 4, cost[1] * 3 / 4};
            java.util.UUID owner = h.ownerPlayer();
            int haveStone = stone(mgr, h), haveIron = owner == null ? Stores.stockWithChests(mgr, h, IRON) : Stores.stockWithChests(mgr, h, owner, IRON);
            if (haveStone < cost[0] || haveIron < cost[1]) {
                player.sendSystemMessage(Component.literal("Stage " + (h.fortify + 1) + " needs " + cost[0] + " stone and " + cost[1]
                        + " iron in the realm's stores or this castle's chests; you have " + haveStone + " and " + haveIron + ".").withStyle(ChatFormatting.RED));
                return;
            }
            takeStone(mgr, h, cost[0]);
            if (owner != null) Stores.takeWithChests(mgr, h, owner, IRON, cost[1]);
            else Stores.takeWithChests(mgr, h, IRON, cost[1]);
        }
        if (work.equals(REPAIR)) h.gateBrokenAt = 0;              // your own builder's repair puts your gate back now
        h.work = work;
        h.workQueue = null;
        h.workDone = 0;
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold("Master Builder " + h.builderName + switch (work) {
            case REPAIR -> " surveys the damage at " + h.name + " and sets his men to the walls.";
            case LIGHT -> " walks " + h.name + " end to end with a lantern, marking every dark corner where something could crawl out.";
            case CLEAR -> " sends woodcutters out to fell every tree within " + CLEAR_REACH + " blocks of the walls of " + h.name + ".";
            default -> " begins " + stageName(h.fortify + 1) + " at " + h.name + ".";
        }));
    }

    /** What the builder's at, for the War Table and the realm page. */
    public static String doing(Holding h) {
        return switch (h.work) {
            case REPAIR -> "repairing";
            case LIGHT -> "lighting the castle";
            case CLEAR -> "clearing the field";
            case NONE -> "idle";
            default -> "building " + stageName(h.fortify + 1);
        };
    }

    /** Coal and charcoal across the realm. */
    public static int coal(HoldingManager mgr, Holding h) {
        java.util.UUID owner = h.ownerPlayer();
        int n = 0;
        for (ResourceLocation c : new ResourceLocation[]{COAL, CHARCOAL}) n += owner == null ? Stores.stockWithChests(mgr, h, c) : Stores.stockWithChests(mgr, h, owner, c);
        return n;
    }

    private static boolean takeCoal(HoldingManager mgr, Holding h) {
        java.util.UUID owner = h.ownerPlayer();
        for (ResourceLocation c : new ResourceLocation[]{COAL, CHARCOAL}) {
            int have = owner == null ? Stores.stockWithChests(mgr, h, c) : Stores.stockWithChests(mgr, h, owner, c);
            if (have <= 0) continue;
            if (owner == null) Stores.takeWithChests(mgr, h, c, 1);
            else Stores.takeWithChests(mgr, h, owner, c, 1);
            return true;
        }
        return false;
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
        for (ResourceLocation s : STONE) n += owner == null ? Stores.stockWithChests(mgr, h, s) : Stores.stockWithChests(mgr, h, owner, s);
        return n;
    }

    private static void takeStone(HoldingManager mgr, Holding h, int n) {
        java.util.UUID owner = h.ownerPlayer();
        for (ResourceLocation s : STONE) {
            int have = owner == null ? Stores.stockWithChests(mgr, h, s) : Stores.stockWithChests(mgr, h, owner, s);
            int take = Math.min(n, have);
            if (take > 0) {
                if (owner == null) Stores.takeWithChests(mgr, h, s, take);
                else Stores.takeWithChests(mgr, h, owner, s, take);
            }
            n -= take;
            if (n <= 0) return;
        }
    }

    // ------------------------------------------------------------ the work, once a second

    /** How long a house's castle must be quiet after damage, and after the last blow struck at its men, before its masons start. */
    public static final int QUIET = 1200;

    /** The house's masons start on the walls: the survey runs on the next tick. */
    public static void summonMasons(HoldingManager mgr, Holding h) {
        h.damagedAt = 0;
        h.work = REPAIR;
        h.workQueue = null;
        h.workDone = 0;
        mgr.setDirty();
    }

    public static void tick(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.isPlayerOwned()) {
            if (h.ruined) return;   // nobody mends a ruin but a lord's builder
            // a house's own masons: once the castle has been quiet for a minute after taking damage, they put it back
            if (h.work.equals(NONE)) {
                long now = level.getGameTime();
                if (h.damagedAt <= 0 || now - h.damagedAt < QUIET || now - h.foughtAt < QUIET || h.dragonAt != 0 || h.plans() == null) return;
                summonMasons(mgr, h);
            } else if (!h.work.equals(REPAIR)) {
                h.work = NONE;
                return;
            }
        }
        if (h.work.equals(NONE)) return;
        ensureBuilder(level, h);
        if (h.workQueue == null && h.work.equals(LIGHT)) {
            // the survey walks the castle a slice at a time, so a city doesn't stall the server
            LightSurvey sv = SURVEYS.computeIfAbsent(h.id, k -> new LightSurvey(h.box.minX()));
            if (!surveyDark(level, h, sv)) return;
            SURVEYS.remove(h.id);
            h.workQueue = new ArrayDeque<>(planLights(level, h, sv.dark));
            Stormhold.LOGGER.info("Stormhold: {} has {} dark spots; {} lights planned", h.name, sv.dark.size(), h.workQueue.size());
            if (h.workQueue.isEmpty()) {
                tell(level, h, "Master Builder " + h.builderName + " finds " + h.name + " lit already: nothing can spawn inside the walls.");
                h.work = NONE;
                h.workQueue = null;
                mgr.setDirty();
                return;
            }
            h.workDone = 0;
        }
        if (h.workQueue == null && h.work.equals(CLEAR)) {
            h.workQueue = new ArrayDeque<>(planClearing(level, mgr, h));
            h.workDone = 0;
            if (h.workQueue.isEmpty()) {
                tell(level, h, "There are no trees within " + CLEAR_REACH + " blocks of the walls of " + h.name + " to fell.");
                h.work = NONE;
                h.workQueue = null;
                mgr.setDirty();
                return;
            }
        }
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
        if (h.work.equals(CLEAR)) rate = CLEAR_RATE;
        if (Council.has(h.ownerPlayer(), Council.Seat.WORKS)) rate *= 2;
        int placed = 0;
        while (placed < rate && !h.workQueue.isEmpty()) {
            Job j = h.workQueue.poll();
            if (!level.isLoaded(j.pos)) {
                h.workQueue.addLast(j);   // come back when it's loaded
                if (++placed > 1) break;
                continue;
            }
            BlockState now = level.getBlockState(j.pos);
            if (j.expect != null && !now.is(j.expect)) continue;   // someone's been at it since the survey
            if (j.onlyIfAir && !(now.isAir() || now.is(CommonEvents.SIEGE_PLACEABLE) || !now.getFluidState().isEmpty()
                    || now.is(Blocks.FIRE) || now.canBeReplaced() || (h.work.equals(REPAIR) && now.is(BlockTags.LEAVES)))) continue;
            if (h.work.equals(LIGHT) && h.workDone % TORCHES_PER_COAL == 0 && !takeCoal(mgr, h)) {
                tell(level, h, "Master Builder " + h.builderName + " has run out of coal at " + h.name + " with " + (h.workQueue.size() + 1)
                        + " dark spots left. Bring more and order it again.");
                h.work = NONE;
                h.workQueue = null;
                mgr.setDirty();
                return;
            }
            if (j.expect != null && now.is(BlockTags.LOGS)) {
                ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(now.getBlock());
                if (id != null) h.addStock(id, 1);
            }
            level.setBlock(j.pos, j.state, 3);
            placed++;
            h.workDone++;
        }
        if (placed > 0) level.playSound(null, h.warTablePos, h.work.equals(CLEAR) ? SoundEvents.WOOD_BREAK : SoundEvents.STONE_PLACE,
                SoundSource.BLOCKS, 0.6F, 0.8F);
        if (h.workQueue.isEmpty()) finish(level, mgr, h);
        mgr.setDirty();
    }

    private static void finish(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.work.equals(REPAIR) && h.ruined && h.isPlayerOwned()) Ruins.restore(level, mgr, h);
        if (h.work.equals(LIGHT)) {
            tell(level, h, "Master Builder " + h.builderName + " has lit " + h.name + ": " + h.workDone
                    + " torches, and nothing spawns inside the walls now. Crypts and cellars stay dark.");
        } else if (h.work.equals(CLEAR)) {
            tell(level, h, "The field round " + h.name + " is clear to " + CLEAR_REACH + " blocks: " + h.workDone
                    + " blocks of timber and leaf cut, the logs stacked in the stores.");
        } else if (h.work.equals(REPAIR)) {
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

    /** A block to set. {@code expect}: only if that block is still there (felling), else nothing. */
    public record Job(BlockPos pos, BlockState state, boolean onlyIfAir, @Nullable Block expect) {
        public Job(BlockPos pos, BlockState state, boolean onlyIfAir) {
            this(pos, state, onlyIfAir, null);
        }
    }

    // ------------------------------------------------------------ repair: compare the castle to its plans

    /** Everything the layout says should be there and isn't. False if this castle has no plans (a founded holding). */
    private static boolean survey(ServerLevel level, Holding h) {
        Layout l = h.plans();
        if (l == null) return false;
        Map<Block, DataRegistries.Weighted> theme = DataRegistries.theme(h.theme == null ? "" : h.theme);
        var lookup = level.holderLookup(Registries.BLOCK);
        BlockPos origin = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
        int nx = (l.sizeX + 31) / 32, nz = (l.sizeZ + 31) / 32;
        List<Job> jobs = new ArrayList<>();
        boolean gateDown = Gates.down(level, h);
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
                            || have.canBeReplaced() || have.is(BlockTags.LEAVES))) continue;
                    if (gateDown && want.is(net.aiagent256.stormhold.registry.ModBlocks.PORTCULLIS.get())) continue;   // broken lately: it stays down
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

    // ------------------------------------------------------------ light: torches wherever a mob could spawn

    private static final Map<java.util.UUID, LightSurvey> SURVEYS = new java.util.HashMap<>();
    /** Slices of the castle walked per second. */
    private static final int SURVEY_SLICES = 12;

    static final class LightSurvey {
        int x;
        final List<BlockPos> dark = new ArrayList<>();

        LightSurvey(int x) {
            this.x = x;
        }
    }

    /** Where a cellar or crypt starts: below this the survey leaves the dark alone. */
    private static int lightFloor(Holding h) {
        Layout l = h.plans();
        return l != null ? h.box.minY() + l.ground - 1 : h.box.minY();
    }

    /** A spot a monster could spawn on tonight: room to stand, a floor that takes a spawn, and no lamp near. */
    public static boolean dark(ServerLevel level, BlockPos p) {
        BlockState at = level.getBlockState(p), up = level.getBlockState(p.above());
        if (!net.minecraft.world.level.NaturalSpawner.isValidEmptySpawnBlock(level, p, at, at.getFluidState(), EntityType.ZOMBIE)) return false;
        if (!net.minecraft.world.level.NaturalSpawner.isValidEmptySpawnBlock(level, p.above(), up, up.getFluidState(), EntityType.ZOMBIE)) return false;
        BlockPos below = p.below();
        if (!level.getBlockState(below).isValidSpawn(level, below, EntityType.ZOMBIE)) return false;
        return level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) == 0;
    }

    /** One second's walk of the survey. True once the whole castle's been walked. */
    static boolean surveyDark(ServerLevel level, Holding h, LightSurvey sv) {
        int y0 = Math.max(lightFloor(h), level.getMinBuildHeight() + 1), y1 = Math.min(h.box.maxY(), level.getMaxBuildHeight() - 2);
        for (int n = 0; n < SURVEY_SLICES && sv.x <= h.box.maxX(); n++, sv.x++) {
            for (int z = h.box.minZ(); z <= h.box.maxZ(); z++) {
                if (!level.isLoaded(new BlockPos(sv.x, y0, z))) continue;
                int top = Math.min(y1, level.getHeight(Heightmap.Types.MOTION_BLOCKING, sv.x, z));
                for (int y = y0; y <= top; y++) {
                    BlockPos p = new BlockPos(sv.x, y, z);
                    if (dark(level, p)) sv.dark.add(p);
                }
            }
        }
        return sv.x > h.box.maxX();
    }

    /**
     * Torches for the dark spots, as few as will do: one at the first dark spot left, then everything its
     * light reaches (light runs out a level a block through anything it passes, so a flood through open
     * cells is the real thing) is struck off, and so on. A wall torch where there's a wall to hang it on.
     */
    static List<Job> planLights(ServerLevel level, Holding h, List<BlockPos> dark) {
        java.util.Set<BlockPos> left = new java.util.LinkedHashSet<>(dark);
        List<Job> jobs = new ArrayList<>();
        while (!left.isEmpty()) {
            BlockPos p = left.iterator().next();
            left.remove(p);
            BlockState torch = torchAt(level, p);
            if (torch == null) continue;
            jobs.add(new Job(p, torch, true));
            // a torch gives 14; count a spot lit while it'd get 3 or more
            java.util.Map<BlockPos, Integer> seen = new java.util.HashMap<>();
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            seen.put(p, 0);
            q.add(p);
            while (!q.isEmpty()) {
                BlockPos c = q.poll();
                int d = seen.get(c);
                left.remove(c);
                if (d >= 11) continue;
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos nb = c.relative(dir);
                    if (seen.containsKey(nb) || !h.box.inflatedBy(12).isInside(nb)) continue;
                    BlockState s = level.getBlockState(nb);
                    if (s.canOcclude() && s.isSolidRender(level, nb)) continue;
                    int cost = 1 + s.getLightBlock(level, nb);
                    if (d + cost > 11) continue;
                    seen.put(nb, d + cost);
                    q.add(nb);
                }
            }
        }
        return jobs;
    }

    /** A wall torch facing out from a wall beside {@code p}, else a torch on the floor; null if neither will stand. */
    @Nullable
    private static BlockState torchAt(ServerLevel level, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockState w = Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, d);
            if (w.canSurvive(level, p)) return w;
        }
        BlockState t = Blocks.TORCH.defaultBlockState();
        return t.canSurvive(level, p) ? t : null;
    }

    // ------------------------------------------------------------ clear: fell the trees round the walls

    /** Every wild tree with a trunk within {@link #CLEAR_REACH} of the walls: leaves first, then logs, top down. */
    static List<Job> planClearing(ServerLevel level, HoldingManager mgr, Holding h) {
        var ring = h.box.inflatedBy(CLEAR_REACH);
        // what's somebody's: every castle and town near, and the buildings of the fiefs
        var near = ring.inflatedBy(40);
        List<net.minecraft.world.level.levelgen.structure.BoundingBox> keep = new ArrayList<>();
        List<Holding> all = new ArrayList<>(mgr.holdings.values());
        if (!all.contains(h)) all.add(h);
        for (Holding o : all) {
            if (o.box.intersects(near)) keep.add(o.box);
            for (Holding.FiefBuilding b : o.fief) if (b.box().intersects(near)) keep.add(b.box().inflatedBy(1));
        }
        java.util.Set<BlockPos> logs = new java.util.HashSet<>(), leaves = new java.util.HashSet<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = ring.minX(); x <= ring.maxX(); x++) {
            for (int z = ring.minZ(); z <= ring.maxZ(); z++) {
                if (x >= h.box.minX() && x <= h.box.maxX() && z >= h.box.minZ() && z <= h.box.maxZ()) continue;
                if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
                BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
                if (!wildLog(level.getBlockState(top)) || logs.contains(top) || spoken(keep, top)) continue;
                fell(level, keep, top, logs, leaves);
            }
        }
        List<Job> jobs = new ArrayList<>();
        java.util.Comparator<BlockPos> down = (a, b) -> Integer.compare(b.getY(), a.getY());
        List<BlockPos> l = new ArrayList<>(leaves);
        l.sort(down);
        for (BlockPos p : l) jobs.add(new Job(p, air, false, level.getBlockState(p).getBlock()));
        List<BlockPos> g = new ArrayList<>(logs);
        g.sort(down);
        for (BlockPos p : g) jobs.add(new Job(p, air, false, level.getBlockState(p).getBlock()));
        return jobs;
    }

    private static boolean wildLog(BlockState s) {
        if (!s.is(BlockTags.LOGS)) return false;
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        return id == null || !id.getPath().contains("stripped");
    }

    private static boolean wildLeaf(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                && s.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                && !s.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
    }

    /** Somewhere that's somebody's: inside any castle or town, or a building of a fief. */
    private static boolean spoken(List<net.minecraft.world.level.levelgen.structure.BoundingBox> keep, BlockPos p) {
        for (var b : keep) if (b.isInside(p)) return true;
        return false;
    }

    /**
     * One tree from a log at its crown: the trunk and branches (logs touching, corners too), then the
     * leaves that grew on it. Only if it has wild leaves: a log beam of somebody's barn is left standing.
     */
    private static void fell(ServerLevel level, List<net.minecraft.world.level.levelgen.structure.BoundingBox> keep, BlockPos start,
                             java.util.Set<BlockPos> logs, java.util.Set<BlockPos> leaves) {
        java.util.Set<BlockPos> trunk = new java.util.HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        trunk.add(start);
        q.add(start);
        while (!q.isEmpty() && trunk.size() < 600) {
            BlockPos c = q.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.offset(dx, dy, dz);
                        if (trunk.contains(n) || logs.contains(n) || !level.isLoaded(n)) continue;
                        if (!wildLog(level.getBlockState(n)) || spoken(keep, n)) continue;
                        trunk.add(n);
                        q.add(n);
                    }
        }
        // the crown: wild leaves within six of the wood, as a leaf counts its distance
        java.util.Map<BlockPos, Integer> crown = new java.util.HashMap<>();
        for (BlockPos t : trunk) q.add(t);
        java.util.Map<BlockPos, Integer> dist = new java.util.HashMap<>();
        for (BlockPos t : trunk) dist.put(t, 0);
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            int d = dist.get(c);
            if (d >= 6) continue;
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos n = c.relative(dir);
                if (dist.containsKey(n) || leaves.contains(n) || !level.isLoaded(n)) continue;
                if (!wildLeaf(level.getBlockState(n)) || spoken(keep, n)) continue;
                dist.put(n, d + 1);
                crown.put(n, d + 1);
                q.add(n);
            }
        }
        if (crown.size() < 3) return;
        logs.addAll(trunk);
        leaves.addAll(crown.keySet());
    }

    // ------------------------------------------------------------ roads: every place you hold joined to the seat

    /** A road still to lay between two of a lord's places, and its length. */
    public record Link(Holding a, Holding b, double length) {
    }

    /**
     * The roads still to lay so that every place the lord holds here is joined to every other, the shortest
     * first: the roads already laid count, and no road runs longer than ROAD_MAX. The seat's group comes first,
     * so a road from it is laid before any other of the same length.
     */
    public static List<Link> missing(HoldingManager mgr, java.util.UUID owner) {
        List<Holding> mine = mgr.ownedBy(owner);
        java.util.Map<java.util.UUID, java.util.UUID> parent = new java.util.HashMap<>();
        for (Holding h : mine) parent.put(h.id, h.id);
        for (Holding h : mine)
            for (java.util.UUID o : h.roadsTo) if (parent.containsKey(o)) union(parent, h.id, o);
        // the groups the roads laid already make
        java.util.Map<java.util.UUID, java.util.UUID> group = new java.util.HashMap<>();
        for (Holding h : mine) group.put(h.id, find(parent, h.id));
        List<Link> all = new ArrayList<>();
        for (int i = 0; i < mine.size(); i++) {
            for (int j = i + 1; j < mine.size(); j++) {
                BlockPos a = Campaigns.gateOutside(mine.get(i)), b = Campaigns.gateOutside(mine.get(j));
                double d = Math.sqrt((double) (a.getX() - b.getX()) * (a.getX() - b.getX()) + (double) (a.getZ() - b.getZ()) * (a.getZ() - b.getZ()));
                if (d <= ROAD_MAX) all.add(new Link(mine.get(i), mine.get(j), d));
            }
        }
        all.sort(java.util.Comparator.comparingDouble(Link::length));
        List<Link> out = new ArrayList<>();
        for (Link l : all) {
            if (find(parent, l.a().id).equals(find(parent, l.b().id))) continue;
            union(parent, l.a().id, l.b().id);
            out.add(l);
        }
        Holding seat = mgr.seatOf(owner);
        if (seat == null || out.isEmpty()) return out;
        // from the seat out: each next road the shortest that reaches a place not yet joined to it
        java.util.Set<java.util.UUID> reached = new java.util.HashSet<>();
        java.util.UUID home = group.get(seat.id);
        for (var e : group.entrySet()) if (e.getValue().equals(home)) reached.add(e.getKey());
        List<Link> left = new ArrayList<>(out), ordered = new ArrayList<>();
        while (!left.isEmpty()) {
            Link next = null;
            for (Link l : left) {
                if (reached.contains(l.a().id) || reached.contains(l.b().id)) {
                    next = l;
                    break;                                   // left is shortest first
                }
            }
            if (next == null) {
                ordered.addAll(left);
                break;
            }
            left.remove(next);
            boolean fromA = reached.contains(next.a().id);
            Holding far = fromA ? next.b() : next.a();
            ordered.add(fromA ? next : new Link(next.b(), next.a(), next.length()));
            java.util.UUID g = group.get(far.id);
            for (var e : group.entrySet()) if (e.getValue().equals(g)) reached.add(e.getKey());
        }
        return ordered;
    }

    private static java.util.UUID find(java.util.Map<java.util.UUID, java.util.UUID> parent, java.util.UUID x) {
        java.util.UUID p = parent.get(x);
        while (!p.equals(x)) {
            java.util.UUID gp = parent.get(p);
            parent.put(x, gp);
            x = p;
            p = gp;
        }
        return x;
    }

    private static void union(java.util.Map<java.util.UUID, java.util.UUID> parent, java.util.UUID a, java.util.UUID b) {
        java.util.UUID ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /** Stone a road costs: one per ROAD_PER_STONE blocks, at least 16; the Master of Works halves it. */
    public static int roadCost(java.util.UUID owner, double length) {
        int stone = Math.max(16, (int) Math.ceil(length / ROAD_PER_STONE));
        if (Council.has(owner, Council.Seat.WORKS)) stone = Math.max(8, stone / 2);
        return stone;
    }

    /** Set a road between two places going down: the plan, the land it crosses that's loaded now, the record. */
    static RoadPlan lay(ServerLevel level, HoldingManager mgr, Holding a, Holding b) {
        RoadPlan r = new RoadPlan();
        r.from = Campaigns.gateOutside(a);
        r.to = Campaigns.gateOutside(b);
        r.seed = a.id.getLeastSignificantBits() ^ b.id.getMostSignificantBits();
        r.theme = a.theme == null ? "north" : a.theme;
        r.fromName = a.name;
        r.toName = b.name;
        mgr.roads.add(r);
        a.roadsTo.add(b.id);
        int minX = Math.min(r.from.getX(), r.to.getX()) >> 4, maxX = Math.max(r.from.getX(), r.to.getX()) >> 4;
        int minZ = Math.min(r.from.getZ(), r.to.getZ()) >> 4, maxZ = Math.max(r.from.getZ(), r.to.getZ()) >> 4;
        for (int cx = minX - 2; cx <= maxX + 2; cx++)
            for (int cz = minZ - 2; cz <= maxZ + 2; cz++)
                if (level.getChunkSource().getChunkNow(cx, cz) != null) Roads.enqueue(level, new net.minecraft.world.level.ChunkPos(cx, cz));
        mgr.setDirty();
        Chronicle.log(level, mgr, Chronicle.GROWTH, "A road is laid from " + a.name + " to " + b.name + ".", a.ownerPlayer());
        return r;
    }

    /** The War Table's "Lay roads": every road the realm still needs that the stone will pay for, shortest first. */
    public static void road(ServerLevel level, HoldingManager mgr, Holding h, net.minecraft.world.entity.player.Player player) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            player.sendSystemMessage(Component.literal("Roads are laid in the overworld only.").withStyle(ChatFormatting.RED));
            return;
        }
        java.util.UUID owner = h.ownerPlayer();
        if (owner == null) return;
        List<Link> links = missing(mgr, owner);
        if (links.isEmpty()) {
            player.sendSystemMessage(Component.literal(mgr.ownedBy(owner).size() < 2 ? "Take another castle or town and there'll be somewhere "
                    + "to build a road to." : "Every place you hold is joined by road (none lies more than " + ROAD_MAX + " blocks from the next).")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }
        int have = stone(mgr, h), spent = 0;
        List<String> laid = new ArrayList<>();
        Link waiting = null;
        for (Link l : links) {
            int cost = roadCost(owner, l.length());
            if (have - spent < cost) {
                waiting = l;
                break;
            }
            takeStone(mgr, h, cost);
            spent += cost;
            if (l.b() == h) l = new Link(l.b(), l.a(), l.length());   // from this castle, when it's one end
            lay(level, mgr, l.a(), l.b());
            laid.add(l.a().name + " to " + l.b().name + " (" + (int) l.length() + " blocks)");
        }
        if (laid.isEmpty()) {
            player.sendSystemMessage(Component.literal("The road from " + waiting.a().name + " to " + waiting.b().name + " (" + (int) waiting.length()
                    + " blocks) takes " + roadCost(owner, waiting.length()) + " stone from the realm's stores or " + h.name + "'s chests; there's "
                    + have + ".").withStyle(ChatFormatting.RED));
            return;
        }
        player.sendSystemMessage(HoldingEvents.gold("Master Builder " + h.builderName + "'s road gangs set out: " + String.join("; ", laid)
                + ". " + spent + " stone. The roads go down as the country they cross is seen: ride them and they're there, with a signpost "
                + "at each end." + (waiting == null ? " Every place you hold is joined now." : " Still to join: " + waiting.a().name + " to "
                + waiting.b().name + ", " + roadCost(owner, waiting.length()) + " stone.")));
    }

    /** At dawn: the next road the realm needs, if the stores have the stone for it. The account's line, or null. */
    @org.jetbrains.annotations.Nullable
    public static Component dawnRoads(ServerLevel level, HoldingManager mgr, java.util.UUID owner, List<Holding> mine) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || mine.size() < 2) return null;
        List<Link> links = missing(mgr, owner);
        if (links.isEmpty()) return null;
        Link l = links.get(0);
        Holding pay = mine.get(0);
        int cost = roadCost(owner, l.length()), have = stone(mgr, pay);
        if (have < cost) {
            return Component.literal("Roads: joining " + l.a().name + " to " + l.b().name + " (" + (int) l.length() + " blocks) waits on stone: "
                    + cost + " needed, " + have + " in the stores.").withStyle(ChatFormatting.GRAY);
        }
        takeStone(mgr, pay, cost);
        lay(level, mgr, l.a(), l.b());
        return Component.literal("Roads: the builders set out to join " + l.a().name + " to " + l.b().name + ", " + (int) l.length() + " blocks "
                + Guide.way(Campaigns.gateOutside(l.a()), Campaigns.gateOutside(l.b())).replaceAll("^\\d+ blocks ", "") + " (" + cost + " stone).")
                .withStyle(ChatFormatting.GREEN);
    }

    private Builder() {
    }
}
