package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.worldgen.ThemeProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The commoners who live off the land around a castle. A hamlet, village or town of small buildings is
 * laid out on the real terrain outside the walls as the chunks load (cottages near the gate, the
 * pasture, mine and quarry further out), each with villagers for its trade and herds in its pens.
 * Once a day the castle takes a census and the stores fill from what the living workers produce.
 */
public final class Fief {
    /** How far from the walls the fief reaches. */
    public static final int REACH = 110;
    /** Give up on a building after this many failed sitings; the land is just too rough. */
    private static final int MAX_TRIES = 90;

    private static final List<String> HAMLET = List.of("cottage_a", "well", "cottage_c", "cottage_b", "pasture");
    private static final List<String> VILLAGE = List.of("cottage_a", "well", "cottage_b", "pasture", "cottage_c", "mine", "cottage_d",
            "mill", "chapel", "cottage_a", "cottage_c");
    private static final List<String> TOWN = List.of("cottage_a", "well", "cottage_b", "pasture", "cottage_c", "mine", "cottage_d",
            "mill", "chapel", "cottage_a", "lumber", "cottage_b", "quarry", "pasture", "dock", "cottage_c", "cottage_d", "cottage_a");

    public static void plan(ServerLevel level, Holding h) {
        if (h.fiefPlan != null) return;
        // nobody farms the Nether: a holdfast there has its garrison and nothing outside the walls
        boolean none = h.tier >= 4 || h.isTown() || level.dimension() != net.minecraft.world.level.Level.OVERWORLD;
        h.fiefPlan = new ArrayList<>(none ? List.of() : h.tier == 3 ? TOWN : h.tier == 2 ? VILLAGE : HAMLET);
    }

    /** What a growing fief would add next, past its plan. */
    public static String nextExtra(Holding h) {
        int cottages = 0;
        for (Holding.FiefBuilding b : h.fief) if (b.piece().startsWith("cottage")) cottages++;
        boolean hasMine = h.fief.stream().anyMatch(b -> b.piece().equals("mine"));
        boolean hasPasture = h.fief.stream().anyMatch(b -> b.piece().equals("pasture"));
        boolean hasMill = h.fief.stream().anyMatch(b -> b.piece().equals("mill"));
        if (!hasPasture) return "pasture";
        if (!hasMine) return "mine";
        if (!hasMill) return "mill";
        return switch (cottages % 4) {
            case 0 -> "cottage_a";
            case 1 -> "cottage_b";
            case 2 -> "cottage_c";
            default -> "cottage_d";
        };
    }

    // ------------------------------------------------------------ building

    /** Try to site and build the next planned building. Called every couple of seconds while a player is near. */
    public static void buildNext(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.fiefPlan == null || h.fiefPlan.isEmpty()) return;
        String name = h.fiefPlan.get(0);
        FiefPiece piece = DataRegistries.fiefPiece(name);
        if (piece == null) {
            h.fiefPlan.remove(0);
            return;
        }
        if (place(level, mgr, h, piece)) {
            h.fiefPlan.remove(0);
            h.fiefTries = 0;
            mgr.setDirty();
        } else if (++h.fiefTries > MAX_TRIES) {
            Stormhold.LOGGER.info("Stormhold: no room for a {} near {}", name, h.name);
            h.fiefPlan.remove(0);
            h.fiefTries = 0;
            mgr.setDirty();
        }
    }

    private record Site(int x, int z, int y, Rotation rot) {
    }

    public static boolean place(ServerLevel level, HoldingManager mgr, Holding h, FiefPiece piece) {
        var tmpl = level.getStructureManager().get(new ResourceLocation(Stormhold.MODID, piece.template));
        if (tmpl.isEmpty()) return false;
        StructureTemplate template = tmpl.get();
        RandomSource rnd = level.random;
        BlockPos c = h.center();
        BlockPos gate = h.gatePos.equals(BlockPos.ZERO) ? c : h.gatePos;
        double gateAng = Math.atan2(gate.getZ() - c.getZ(), gate.getX() - c.getX());
        int half = Math.max(h.box.getXSpan(), h.box.getZSpan()) / 2;
        boolean near = piece.kind.equals("cottage") || piece.kind.equals("well") || piece.kind.equals("chapel") || piece.kind.equals("mill");
        for (int attempt = 0; attempt < 6; attempt++) {
            double ang = near ? gateAng + (rnd.nextDouble() - 0.5) * Math.PI * 1.1 : rnd.nextDouble() * Math.PI * 2;
            int dist = half + (near ? 10 + rnd.nextInt(36) : 22 + rnd.nextInt(60));
            int cx = c.getX() + (int) (Math.cos(ang) * dist), cz = c.getZ() + (int) (Math.sin(ang) * dist);
            Site s = site(level, h, piece, template, cx, cz);
            if (s == null) continue;
            build(level, mgr, h, piece, template, s);
            return true;
        }
        return false;
    }

    /** Check a spot for the piece: loaded, flat enough, dry, clear of the castle and the other buildings. */
    @Nullable
    private static Site site(ServerLevel level, Holding h, FiefPiece piece, StructureTemplate template, int cx, int cz) {
        Rotation rot;
        BlockPos c = h.center();
        if (piece.water) {
            rot = toWater(level, cx, cz);
            if (rot == null) return null;
        } else {
            rot = facing(cx - c.getX(), cz - c.getZ());
        }
        StructurePlaceSettings settings = settings(rot, h.theme);
        BoundingBox bb0 = template.getBoundingBox(settings, BlockPos.ZERO);
        int w = bb0.getXSpan(), d = bb0.getZSpan();
        int x0 = cx - w / 2, z0 = cz - d / 2;
        BoundingBox foot = new BoundingBox(x0 - 1, 0, z0 - 1, x0 + w, 255, z0 + d);
        if (!level.isLoaded(new BlockPos(x0 - 1, 64, z0 - 1)) || !level.isLoaded(new BlockPos(x0 + w, 64, z0 + d))
                || !level.isLoaded(new BlockPos(x0 - 1, 64, z0 + d)) || !level.isLoaded(new BlockPos(x0 + w, 64, z0 - 1))) return null;
        if (h.box.inflatedBy(8).intersects(foot)) return null;
        for (Holding.FiefBuilding b : h.fief) if (b.box().inflatedBy(3).intersects(foot)) return null;
        // heights
        int[] hs = new int[9];
        int k = 0, water = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                int x = x0 + i * (w - 1) / 2, z = z0 + j * (d - 1) / 2;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
                if (!below.getFluidState().isEmpty()) water++;
                hs[k++] = y;
            }
        }
        int[] sorted = hs.clone();
        java.util.Arrays.sort(sorted);
        int slopeMax = piece.kind.equals("cottage") || piece.kind.equals("well") ? 5 : 6;
        if (!piece.water) {
            if (water > 0 || sorted[8] - sorted[0] > slopeMax) return null;
        } else if (sorted[8] - sorted[0] > 5) {
            return null;
        }
        int y = piece.water ? landHeight(level, x0, z0, w, d, rot) : sorted[4];
        if (y <= 0) return null;
        // don't build on someone's road, field or castle: anything man-made in the footprint
        for (int x = x0; x < x0 + w; x += 2) {
            for (int z = z0; z < z0 + d; z += 2) {
                BlockState s = level.getBlockState(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z));
                if (s.is(BlockTags.PLANKS) || s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.FARMLAND) || s.is(Blocks.SMOOTH_STONE)
                        || s.is(Blocks.POLISHED_ANDESITE) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.WOOL)) return null;
            }
        }
        return new Site(x0, z0, y, rot);
    }

    /** For a dock: the rotation that points the pier (+z) at water within 12 blocks, or null. */
    @Nullable
    private static Rotation toWater(ServerLevel level, int cx, int cz) {
        int yHere = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
        if (!level.getBlockState(new BlockPos(cx, yHere - 1, cz)).getFluidState().isEmpty()) return null;
        Rotation[] rots = {Rotation.NONE, Rotation.CLOCKWISE_180, Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90};
        int[][] dirs = {{0, 1}, {0, -1}, {-1, 0}, {1, 0}};
        for (int i = 0; i < 4; i++) {
            int wx = cx + dirs[i][0] * 10, wz = cz + dirs[i][1] * 10;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            BlockState s = level.getBlockState(new BlockPos(wx, y - 1, wz));
            int yEdge = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dirs[i][0] * 5, cz + dirs[i][1] * 5);
            if (!s.getFluidState().isEmpty() && Math.abs(yEdge - yHere) <= 2) return rots[i];
        }
        return null;
    }

    private static int landHeight(ServerLevel level, int x0, int z0, int w, int d, Rotation rot) {
        // the back of the dock sits on land; sample its middle
        int bx = x0 + w / 2, bz = z0 + d / 2;
        switch (rot) {
            case NONE -> bz = z0 + 2;
            case CLOCKWISE_180 -> bz = z0 + d - 3;
            case CLOCKWISE_90 -> bx = x0 + w - 3;
            default -> bx = x0 + 2;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    /** The piece's front (+z) toward the castle. */
    private static Rotation facing(int dx, int dz) {
        // the building sits at (dx, dz) from the castle, so the castle is at (-dx, -dz) from it
        int tx = -dx, tz = -dz;
        if (Math.abs(tz) >= Math.abs(tx)) return tz >= 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
        return tx < 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
    }

    static StructurePlaceSettings settings(Rotation rot, @Nullable String theme) {
        StructurePlaceSettings s = new StructurePlaceSettings().setRotation(rot).setMirror(Mirror.NONE).setIgnoreEntities(true);
        if (theme != null && !theme.isEmpty() && !theme.equals("north")) s.addProcessor(new ThemeProcessor(theme));
        return s;
    }

    private static void build(ServerLevel level, HoldingManager mgr, Holding h, FiefPiece piece, StructureTemplate template, Site s) {
        StructurePlaceSettings settings = settings(s.rot(), h.theme);
        BoundingBox bb0 = template.getBoundingBox(settings, BlockPos.ZERO);
        int w = bb0.getXSpan(), d = bb0.getZSpan();
        int originY = s.y() - piece.ground;
        // the rotated template's min corner lands on (x0, z0)
        BlockPos origin = new BlockPos(s.x() - bb0.minX(), originY, s.z() - bb0.minZ());
        flatten(level, s.x(), s.z(), w, d, s.y(), piece.sizeY, piece.water);
        template.placeInWorld(level, origin, origin, settings, level.random, 2);
        BoundingBox box = new BoundingBox(s.x(), originY, s.z(), s.x() + w - 1, originY + piece.sizeY - 1, s.z() + d - 1);
        h.fief.add(new Holding.FiefBuilding(piece.name, box));
        // the people
        VillagerProfession prof = piece.profession == null ? null
                : ForgeRegistries.VILLAGER_PROFESSIONS.getValue(new ResourceLocation("minecraft", piece.profession));
        // a family to a cottage, a crew to a workshop: one soul a bed and a couple more, so the place looks lived in
        int souls = piece.kind.equals("well") ? 0 : Math.max(2, piece.beds.size() + 1 + level.random.nextInt(2));
        for (int i = 0; i < souls; i++) {
            BlockPos at = i < piece.beds.size() ? world(origin, settings, piece.beds.get(i)) : world(origin, settings, piece.door);
            BlockPos free = Garrisons.standable(level, at.offset(i < piece.beds.size() ? 0 : level.random.nextInt(5) - 2, 0, 0));
            // the extra souls take whatever trade the village lacks: vanilla picks from the job blocks about
            spawnVillager(level, free != null ? free : at, i < piece.beds.size() ? prof : null);
        }
        if (piece.animalSpot != null) {
            BlockPos at = world(origin, settings, piece.animalSpot);
            for (String a : piece.animals) {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("minecraft", a));
                if (type == null) continue;
                Entity e = type.create(level);
                if (!(e instanceof Animal an)) continue;
                BlockPos p = Garrisons.standable(level, at.offset(level.random.nextInt(9) - 4, 0, level.random.nextInt(7) - 3));
                if (p == null) p = at;
                an.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
                an.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.STRUCTURE, null, null);
                an.setPersistenceRequired();
                level.addFreshEntity(an);
            }
        }
        // a knight's beat outside the front door
        if (!piece.kind.equals("well")) {
            BlockPos door = world(origin, settings, piece.door);
            BlockPos post = Garrisons.standable(level, door);
            if (post != null) h.posts.computeIfAbsent(Role.KNIGHT, r -> new ArrayList<>()).add(post);
        }
        mgr.setDirty();
    }

    private static BlockPos world(BlockPos origin, StructurePlaceSettings settings, BlockPos local) {
        return origin.offset(StructureTemplate.calculateRelativePosition(settings, local));
    }

    /** One of the fief's people, with a name and a trade. They keep vanilla hours: work at their job block by day, bed at night. */
    public static Villager spawnVillager(ServerLevel level, BlockPos at, @Nullable VillagerProfession prof) {
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return null;
        v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360F, 0);
        v.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.STRUCTURE, null, null);
        if (prof != null) {
            v.setVillagerData(v.getVillagerData().setProfession(prof).setLevel(1 + level.random.nextInt(2)));
            v.setVillagerXp(10);
        }
        name(level, v);
        v.setPersistenceRequired();
        level.addFreshEntity(v);
        return v;
    }

    /** Give one of the people a name if they have none. */
    public static void name(ServerLevel level, Villager v) {
        if (v.hasCustomName()) return;
        ResourceLocation id = ForgeRegistries.VILLAGER_PROFESSIONS.getKey(v.getVillagerData().getProfession());
        String job = id == null ? "none" : id.getPath();
        v.setCustomName(net.minecraft.network.chat.Component.literal(net.aiagent256.stormhold.house.NameGen.commoner(level.random, job)));
        v.setCustomNameVisible(false);
    }

    public static String nameOf(Villager v) {
        return v.hasCustomName() ? v.getCustomName().getString() : "one of the people";
    }

    /** Level a pad for the building: fill under it, clear trees and grass above it. The dock's water end is left alone. */
    static void flatten(ServerLevel level, int x0, int z0, int w, int d, int y, int height, boolean dock) {
        for (int x = x0 - 1; x <= x0 + w; x++) {
            for (int z = z0 - 1; z <= z0 + d; z++) {
                boolean edge = x < x0 || z < z0 || x >= x0 + w || z >= z0 + d;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos g = new BlockPos(x, top - 1, z);
                if (dock && !level.getBlockState(g).getFluidState().isEmpty()) continue;
                // clear what's above the pad: plants, trees, snow
                for (int yy = y - (edge ? 0 : 1); yy < Math.max(top, y) + height; yy++) {
                    BlockPos p = new BlockPos(x, yy, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir()) continue;
                    if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS) || st.is(BlockTags.REPLACEABLE) || st.is(BlockTags.FLOWERS)
                            || st.is(BlockTags.SAPLINGS) || st.is(Blocks.SNOW) || st.is(BlockTags.CROPS) || st.is(Blocks.BAMBOO)
                            || st.is(Blocks.SUGAR_CANE) || st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD)
                            || st.is(Blocks.GRAVEL) || st.is(Blocks.MOSS_BLOCK) || st.is(BlockTags.TERRACOTTA) || st.is(Blocks.PODZOL)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                // fill up to the pad
                for (int yy = Math.min(top, y) - 1; yy < y - 1; yy++) {
                    BlockPos p = new BlockPos(x, yy, z);
                    if (level.getBlockState(p).isAir() || !level.getBlockState(p).getFluidState().isEmpty()) {
                        level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
                    }
                }
                if (edge && level.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.DIRT)) {
                    level.setBlock(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
            }
        }
    }

    // ------------------------------------------------------------ census and produce

    private static final String[][] YIELD = {
            // profession, item:count...
            {"farmer", "wheat:6", "bread:4", "carrot:3", "potato:3"},
            {"shepherd", "white_wool:3", "mutton:2", "cooked_mutton:1"},
            {"butcher", "cooked_beef:3", "cooked_porkchop:2", "leather:2"},
            {"mason", "stone_bricks:8", "cobblestone:8", "andesite:4"},
            {"toolsmith", "iron_ingot:3", "coal:5", "raw_copper:2"},
            {"armorer", "iron_ingot:2", "coal:3"},
            {"fletcher", "arrow:16", "string:2", "feather:2"},
            {"fisherman", "cod:4", "salmon:2", "string:1"},
            {"cleric", "emerald:1", "paper:2", "glass_bottle:2"},
            {"leatherworker", "leather:3", "saddle:0"},
            {"librarian", "paper:3", "book:1"},
    };

    /** Count the people and the herds, and (for a player's castle) fill the stores from their work. */
    public static void census(ServerLevel level, HoldingManager mgr, Holding h, long now) {
        if (h.fief.isEmpty()) return;
        BlockPos c = h.center();
        if (!level.isLoaded(c) || !level.isLoaded(c.offset(REACH, 0, 0)) || !level.isLoaded(c.offset(-REACH, 0, 0))) return;
        AABB area = new AABB(c).inflate(REACH, 80, REACH);
        Map<String, Integer> jobs = new java.util.LinkedHashMap<>();
        int pop = 0;
        for (Villager v : level.getEntitiesOfClass(Villager.class, area, Entity::isAlive)) {
            pop++;
            ResourceLocation id = ForgeRegistries.VILLAGER_PROFESSIONS.getKey(v.getVillagerData().getProfession());
            String job = id == null ? "none" : id.getPath();
            jobs.merge(job, 1, Integer::sum);
        }
        int herd = 0;
        for (Holding.FiefBuilding b : h.fief) {
            if (!b.piece().equals("pasture")) continue;
            AABB pen = AABB.of(b.box()).inflate(2, 4, 2);
            herd += level.getEntitiesOfClass(Animal.class, pen, Entity::isAlive).size();
        }
        h.fiefPop = pop;
        h.fiefHerd = herd;
        h.fiefJobs.clear();
        h.fiefJobs.putAll(jobs);
        // what they made
        Map<String, Integer> made = new java.util.LinkedHashMap<>();
        double mult = !h.isPlayerOwned() ? 0 : h.mode == Holding.Mode.PRODUCE ? 1.5 : h.mode == Holding.Mode.ATTACK ? 0.75 : 1.0;
        if (mult > 0) {
            for (String[] row : YIELD) {
                int n = jobs.getOrDefault(row[0], 0);
                if (n == 0) continue;
                for (int i = 1; i < row.length; i++) {
                    String[] kv = row[i].split(":");
                    int q = (int) Math.round(Integer.parseInt(kv[1]) * n * mult);
                    if (q > 0) made.merge(kv[0], q, Integer::sum);
                }
            }
            for (var e : made.entrySet()) h.addStock(new ResourceLocation("minecraft", e.getKey()), e.getValue());
        }
        // the real work: what the mine dug, the butcher and shearer took, the fields gave up (already in the stores)
        Map<String, Integer> real = Produce.daily(level, mgr, h);
        real.forEach((k, v) -> made.merge(k, v, Integer::sum));
        int dug = 0;
        for (Produce.Dig d : h.digs.values()) dug += d.dug;
        StringBuilder sb = new StringBuilder(h.fiefName()).append(" of ").append(pop).append(pop == 1 ? " soul" : " souls");
        if (!jobs.isEmpty()) {
            sb.append(" (");
            int i = 0;
            for (var e : jobs.entrySet()) {
                if (e.getKey().equals("none") || e.getKey().equals("nitwit")) continue;
                if (i++ > 0) sb.append(", ");
                sb.append(e.getValue()).append(" ").append(e.getKey().replace('_', ' ')).append(e.getValue() > 1 ? "s" : "");
            }
            sb.append(")");
        }
        if (herd > 0) sb.append(", herd of ").append(herd);
        if (dug > 0) sb.append(", mine ").append(dug).append(" blocks deep");
        sb.append(".");
        if (!made.isEmpty()) {
            sb.append(" Yesterday:");
            int i = 0;
            List<Map.Entry<String, Integer>> top = new ArrayList<>(made.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            for (var e : top) {
                if (i++ >= 7) break;
                sb.append(i > 1 ? ", " : " ").append(e.getValue()).append(" ").append(e.getKey().replace('_', ' '));
            }
            sb.append(".");
        }
        h.fiefReport = sb.toString();
        mgr.setDirty();
    }

    /** The holding whose people live here, whoever holds it. */
    @Nullable
    public static Holding peopleOf(HoldingManager mgr, BlockPos pos) {
        for (Holding h : mgr.holdings.values()) {
            if (h.box.inflatedBy(REACH).isInside(pos)) return h;
        }
        return null;
    }

    /** A house holding whose fief this spot lies in, if any. */
    @Nullable
    public static Holding fiefAt(HoldingManager mgr, BlockPos pos) {
        for (Holding h : mgr.holdings.values()) {
            if (h.fief.isEmpty() || !h.isHouseOwned()) continue;
            if (h.box.inflatedBy(REACH).isInside(pos)) return h;
        }
        return null;
    }

    private Fief() {
    }
}
