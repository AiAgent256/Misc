package net.aiagent256.stormhold.gametest;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.DataRegistries;
import net.aiagent256.stormhold.holding.Garrisons;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.aiagent256.stormhold.worldgen.CastleTilePiece;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(Stormhold.MODID)
@PrefixGameTestTemplate(false)
public class StormholdGameTests {
    private static final String[] LAYOUTS = {"great_castle_a", "great_castle_b", "great_castle_c", "holdfast_a",
            "holdfast_b", "watchtower_a", "watchtower_b", "great_castle_a2", "great_castle_b2", "great_castle_c2",
            "holdfast_a2", "holdfast_b2", "watchtower_a2", "watchtower_b2", "holdfast_c", "holdfast_c2", "watchtower_c",
            "watchtower_c2", "town_walled", "town_cross", "town_green", "great_city", "canal_city"};

    @GameTest(template = "arena")
    public static void castleDataLoads(GameTestHelper g) {
        StructureTemplateManager stm = g.getLevel().getStructureManager();
        for (String n : LAYOUTS) {
            if (DataRegistries.layout(n) == null) throw new IllegalStateException("missing layout " + n);
            if (stm.get(CastleTilePiece.tileId(n, 0, 0)).isEmpty()) throw new IllegalStateException("missing template " + n);
            if (DataRegistries.layout(n).posts.get(Role.KNIGHT) == null) throw new IllegalStateException("no knight posts in " + n);
            // the footprint the land is cleared over: the gate stands on it, the corner outside a round tower's box may not
            var lay = DataRegistries.layout(n);
            if (!lay.inside(lay.gate.getX(), lay.gate.getZ())) throw new IllegalStateException("no footprint under the gate of " + n);
        }
        for (String t : new String[]{"riverlands", "reach", "dorne", "stormlands", "nether"}) {
            if (DataRegistries.theme(t).isEmpty()) throw new IllegalStateException("missing theme " + t);
        }
        // every keep has its crypt: the marker the generator leaves at the founder's tomb
        for (String n : new String[]{"holdfast_a", "holdfast_b", "holdfast_c", "great_castle_a", "great_castle_b", "great_castle_c", "great_city", "canal_city"}) {
            var crypt = DataRegistries.layout(n).extras.get("crypt");
            if (crypt == null || crypt.isEmpty()) throw new IllegalStateException("no crypt under " + n);
        }
        // the Nether holdfast is registered and flagged for the Nether
        var reg = g.getLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var nether = reg.get(Stormhold.id("nether_holdfast_nether"));
        if (!(nether instanceof net.aiagent256.stormhold.worldgen.CastleStructure cs) || !cs.nether())
            throw new IllegalStateException("the Nether holdfast is not registered for the Nether");
        if (reg.get(Stormhold.id("holdfast_north")) instanceof net.aiagent256.stormhold.worldgen.CastleStructure o && o.nether())
            throw new IllegalStateException("a surface holdfast is flagged for the Nether");
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void everyRoleSpawnsArmed(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        int i = 0;
        List<Entity> spawned = new ArrayList<>();
        for (Role r : Role.values()) {
            for (String side : new String[]{Sides.house("testhouse"), Sides.player(UUID.randomUUID())}) {
                if (r == Role.LORD && Sides.isPlayer(side)) continue;
                BlockPos p = g.absolutePos(new BlockPos(2 + (i % 6) * 2, 2, 2 + (i / 6) * 2));
                GarrisonEntity e = Garrisons.spawn(level, mgr, null, r, p, side, false);
                if (e == null || !e.isAlive()) throw new IllegalStateException("could not spawn " + r);
                if (r != Role.COURIER && e.getMainHandItem().isEmpty()) throw new IllegalStateException(r + " has no weapon");
                spawned.add(e);
                i++;
            }
        }
        spawned.forEach(Entity::discard);
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void holdingSavesAndLoads(GameTestHelper g) {
        Holding h = sample(g);
        h.addStock(new net.minecraft.resources.ResourceLocation("minecraft", "bread"), 12);
        CompoundTag t = h.save();
        Holding b = Holding.load(t);
        if (!b.name.equals(h.name) || !b.box.equals(h.box) || b.count(Role.KNIGHT) != h.count(Role.KNIGHT)
                || b.posts.get(Role.ARCHER).size() != h.posts.get(Role.ARCHER).size() || b.stock.get(new net.minecraft.resources.ResourceLocation("minecraft", "bread")) != 12) {
            throw new IllegalStateException("holding did not round-trip");
        }
        g.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 300)
    public static void killLordThenCapture(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        mgr.add(h);
        try {
            for (int k = 0; k < 6; k++) Garrisons.materialize(level, mgr, h);
            if (h.live.isEmpty()) throw new IllegalStateException("garrison did not appear");
            Entity lord = null;
            for (var e : h.live.entrySet()) if (e.getValue() == Role.LORD) lord = level.getEntity(e.getKey());
            if (lord == null) throw new IllegalStateException("no lord");
            lord.kill();
            if (h.lordAlive) throw new IllegalStateException("lord's death was not recorded");
            Garrisons.discardLive(level, h.live, false);
            h.garrison.clear();
            String side = Sides.player(UUID.randomUUID());
            GarrisonEntity r = Garrisons.spawn(level, mgr, null, Role.KNIGHT, h.bannerPos.east(), side, true);
            if (r == null) throw new IllegalStateException("no retainer");
            for (int i = 0; i <= Config.CAPTURE_SECONDS.get() + 1 && !h.owner.equals(side); i++) {
                HoldingEvents.contest(level, mgr, h);
            }
            r.discard();
            if (!h.owner.equals(side)) throw new IllegalStateException("castle was not captured, progress " + h.contestProgress);
            if (h.count(Role.KNIGHT) <= 0) throw new IllegalStateException("captured castle has no garrison");
        } finally {
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** Runs the same path as /place structure, then turns the result into a holding. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void castlesGenerateAndAttach(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        var reg = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var gen = level.getChunkSource().getGenerator();
        HoldingManager mgr = HoldingManager.get(level);
        int i = 0;
        for (String id : new String[]{"watchtower_riverlands", "watchtower_north", "holdfast_dorne", "holdfast_north", "great_castle_reach"}) {
            var structure = reg.get(Stormhold.id(id));
            if (structure == null) throw new IllegalStateException("structure not registered: " + id);
            BlockPos at = g.absolutePos(BlockPos.ZERO).offset(3000 + i * 400, 0, 3000);
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(at);
            // dry, flat ground to build on, whatever the world put here (an ocean, once)
            for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) level.getChunk(cp.x + dx, cp.z + dz);
            int gy = gen.getFirstOccupiedHeight(at.getX(), at.getZ(), net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, level,
                    level.getChunkSource().randomState());   // where the generator will put the castle's floor
            for (int x = at.getX() - 100; x <= at.getX() + 100; x++) {
                for (int z = at.getZ() - 100; z <= at.getZ() + 100; z++) {
                    for (int yy = gy - 6; yy < gy - 1; yy++) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(x, gy - 1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    for (int yy = gy; yy < gy + 40; yy++) {
                        if (!level.getBlockState(new BlockPos(x, yy, z)).isAir()) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            net.aiagent256.stormhold.worldgen.CastleStructure.ignoreTerrain = false;
            var probe = structure.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                    level.getStructureManager(), level.getSeed(), cp, 0, level, b -> true);
            Stormhold.LOGGER.info("Siting probe for {}: valid={} last rejection: {}", id, probe.isValid(),
                    net.aiagent256.stormhold.worldgen.CastleStructure.lastRejection);
            net.aiagent256.stormhold.worldgen.CastleStructure.ignoreTerrain = true;
            var start = structure.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                    level.getStructureManager(), level.getSeed(), cp, 0, level, b -> true);
            net.aiagent256.stormhold.worldgen.CastleStructure.ignoreTerrain = false;
            if (!start.isValid()) throw new IllegalStateException(id + " generated nothing even with terrain checks off");
            BoundingBox bb = start.getBoundingBox();
            net.minecraft.world.level.ChunkPos.rangeClosed(new net.minecraft.world.level.ChunkPos(bb.minX() >> 4, bb.minZ() >> 4),
                    new net.minecraft.world.level.ChunkPos(bb.maxX() >> 4, bb.maxZ() >> 4)).forEach(c ->
                    start.placeInChunk(level, level.structureManager(), gen, level.getRandom(),
                            new BoundingBox(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX(),
                                    level.getMaxBuildHeight(), c.getMaxBlockZ()), c));
            int stone = 0;
            BlockPos center = bb.getCenter();
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                if (!level.getBlockState(new BlockPos(center.getX(), y, center.getZ())).isAir()) stone++;
            }
            for (int x = bb.minX(); x <= bb.maxX(); x += 3) {
                if (!level.getBlockState(new BlockPos(x, bb.minY() + 2, center.getZ())).isAir()) stone++;
            }
            if (stone < 5) throw new IllegalStateException(id + " placed almost nothing");
            if (id.contains("dorne")) {
                boolean sand = false;
                for (int x = bb.minX(); x <= bb.maxX() && !sand; x++) {
                    for (int y = bb.minY(); y < bb.maxY() && !sand; y += 2) {
                        var s = level.getBlockState(new BlockPos(x, y, center.getZ()));
                        if (s.is(net.minecraft.world.level.block.Blocks.CUT_SANDSTONE) || s.is(net.minecraft.world.level.block.Blocks.SMOOTH_SANDSTONE)) sand = true;
                    }
                }
                if (!sand) throw new IllegalStateException("dorne theme did not apply");
            }
            Holding h = net.aiagent256.stormhold.holding.Detector.createForTest(level, mgr, "test:" + id + "@" + cp.toLong(),
                    Stormhold.id(id), start);
            if (h == null || h.posts.isEmpty() || h.bannerPos.equals(BlockPos.ZERO)) throw new IllegalStateException(id + " did not attach");
            // the terrain smoother must see ground level at the floor, not at the bottom of the foundation
            var lay = net.aiagent256.stormhold.holding.DataRegistries.layout(h.layout);
            for (var p : start.getPieces()) {
                if (p.getBoundingBox().minY() != h.box.minY() + lay.ground)
                    throw new IllegalStateException(id + " piece box bottom " + p.getBoundingBox().minY() + " is not ground level "
                            + (h.box.minY() + lay.ground));
            }
            if (!level.getBlockState(h.bannerPos.below()).isSolidRender(level, h.bannerPos.below()))
                throw new IllegalStateException(id + " banner stand floats at " + h.bannerPos);
            if (!level.getBlockState(h.bannerPos).canBeReplaced()) throw new IllegalStateException(id + " banner stand is inside a block at " + h.bannerPos);
            if (!level.getBlockState(h.warTablePos).canBeReplaced() || level.getBlockState(h.warTablePos.below()).canBeReplaced())
                throw new IllegalStateException(id + " war table spot is blocked or floating at " + h.warTablePos);
            if (!level.getBlockState(h.lordPos).canBeReplaced() || level.getBlockState(h.lordPos.below()).canBeReplaced())
                throw new IllegalStateException(id + " lord's spot is blocked or floating at " + h.lordPos);
            // every castle has a gate, and a lost gate goes back up
            List<BlockPos> gateMarks = new ArrayList<>(List.of(h.gatePos));
            for (var ex : h.extras.entrySet()) if (ex.getKey().startsWith("gate")) gateMarks.addAll(ex.getValue());
            for (BlockPos gm : gateMarks)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) level.getChunk((gm.getX() >> 4) + dx, (gm.getZ() >> 4) + dz);
            net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (h.gates == null) throw new IllegalStateException(id + " gate scan found its chunks unloaded");
            if (h.gates.isEmpty()) {
                int pc = 0;
                for (BlockPos q : BlockPos.betweenClosed(h.box.minX(), h.box.minY(), h.box.minZ(), h.box.maxX(), h.box.maxY(), h.box.maxZ()))
                    if (level.getBlockState(q).is(net.aiagent256.stormhold.registry.ModBlocks.PORTCULLIS.get())) pc++;
                throw new IllegalStateException(id + " has no gate near " + h.gatePos + "; portcullis blocks in the castle: " + pc
                        + ", layout planes: " + lay.gatePlanes.size() + ", restore would place " + net.aiagent256.stormhold.holding.Gates.restore(level, mgr, h));
            }
            for (var plane : h.gates) {
                if (plane.blocks.isEmpty()) throw new IllegalStateException(id + " has an empty gate plane");
            }
            if (!net.aiagent256.stormhold.holding.Gates.breach(level, h)) throw new IllegalStateException(id + " gate would not break");
            h.gates = null;
            net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (h.gates == null || h.gates.isEmpty()) throw new IllegalStateException(id + " gate was not rebuilt");
            if (i == 0) {
                // knock holes in the walls, then have the Master Builder put them back
                List<BlockPos> holes = new ArrayList<>();
                var lay0 = net.aiagent256.stormhold.holding.DataRegistries.layout(h.layout);
                int wallY = h.box.minY() + lay0.ground + 1;
                int midZ = (h.box.minZ() + h.box.maxZ()) / 2;
                for (int x = h.box.minX(); x <= h.box.maxX() && holes.size() < 12; x++) {
                    BlockPos p = new BlockPos(x, wallY, midZ);
                    // only the castle's own masonry: not terrain at the template's edge, not the furnishings the
                    // builder leaves alone (beds, carpets, candles, containers)
                    var st = level.getBlockState(p);
                    if (!st.isAir() && !st.is(net.minecraft.world.level.block.Blocks.CHEST) && st.getFluidState().isEmpty()
                            && !net.aiagent256.stormhold.holding.Builder.skip(st) && !net.aiagent256.stormhold.holding.Builder.natural(st)
                            && st.isSolidRender(level, p)) {
                        holes.add(p);
                        level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                if (holes.size() < 6) throw new IllegalStateException("could not knock holes in " + id);
                // first the house's own masons: damage noted, a minute of quiet, and the walls come back unasked
                // (the world is too young here for the quiet period to pass on its own, so they're summoned)
                h.damagedAt = 1;
                net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
                if (h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) net.aiagent256.stormhold.holding.Builder.summonMasons(mgr, h);
                for (int k = 0; k < 40 && !h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE); k++)
                    net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
                if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) throw new IllegalStateException("the masons never finished");
                for (BlockPos p : holes) if (level.getBlockState(p).isAir()) throw new IllegalStateException("the masons left a hole at " + p);
                if (h.damagedAt != 0) throw new IllegalStateException("damage not cleared after the masons");
                // knock them out again for the player's Master Builder
                for (BlockPos p : holes) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                h.owner = Sides.player(g.makeMockPlayer().getUUID());
                h.work = net.aiagent256.stormhold.holding.Builder.REPAIR;
                h.addStock(new net.minecraft.resources.ResourceLocation("minecraft:cobblestone"), 64);
                for (int k = 0; k < 40 && !h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE); k++)
                    net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
                if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) throw new IllegalStateException("repair never finished");
                for (BlockPos p : holes) if (level.getBlockState(p).isAir()) throw new IllegalStateException("hole not repaired at " + p);
                if (h.stock(new net.minecraft.resources.ResourceLocation("minecraft:cobblestone")) >= 64) throw new IllegalStateException("repair cost no stone");
                // and fortify: the palisade goes up and the garrison cap rises
                int cap = h.capacity(Role.ARCHER);
                h.addStock(new net.minecraft.resources.ResourceLocation("minecraft:cobblestone"), 100);
                h.addStock(new net.minecraft.resources.ResourceLocation("minecraft:iron_ingot"), 20);
                net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.FORTIFY, g.makeMockPlayer());
                for (int k = 0; k < 400 && !h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE); k++)
                    net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
                if (h.fortify != 1) throw new IllegalStateException("fortify stage " + h.fortify + ", work " + h.work);
                if (h.capacity(Role.ARCHER) <= cap) throw new IllegalStateException("fortifying did not raise capacity");
                int stakes = 0;
                for (int x = h.box.minX() - 7; x <= h.box.maxX() + 7; x++) {
                    int z = h.box.minZ() - 7;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    var st = level.getBlockState(new BlockPos(x, y, z));
                    if (st.is(net.minecraft.world.level.block.Blocks.SPRUCE_FENCE) || st.is(net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE)) stakes++;
                }
                if (stakes < 4 && h.workDone < 10) throw new IllegalStateException("palisade has " + stakes + " stakes along the north side and "
                        + h.workDone + " blocks were placed");
            }
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            i++;
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void spawnsAvoidWalls(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        int fy = 1;
        while (!level.getBlockState(g.absolutePos(new BlockPos(5, fy, 5))).isAir() && fy < 6) fy++;
        BlockPos post = g.absolutePos(new BlockPos(5, fy, 5));
        if (!post.equals(Garrisons.standable(level, post)))
            throw new IllegalStateException("open floor post was moved: " + post + " -> " + Garrisons.standable(level, post));
        g.setBlock(new BlockPos(5, fy, 5), net.minecraft.world.level.block.Blocks.STONE);
        g.setBlock(new BlockPos(5, fy + 1, 5), net.minecraft.world.level.block.Blocks.STONE);
        BlockPos moved = Garrisons.standable(level, post);
        if (moved == null || !level.getBlockState(moved).isAir() || !level.getBlockState(moved.above()).isAir())
            throw new IllegalStateException("buried post gave " + moved);
        for (int x = 8; x <= 12; x++) for (int y = fy; y <= fy + 5; y++) for (int z = 8; z <= 12; z++)
            g.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.STONE);
        BlockPos sealed = Garrisons.standable(level, g.absolutePos(new BlockPos(10, fy, 10)));
        if (sealed != null) throw new IllegalStateException("found room inside solid stone at " + sealed);
        g.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 200)
    public static void castlesKeepClearOfVillages(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        var watches = net.aiagent256.stormhold.worldgen.Avoidance.watches(level.registryAccess());
        if (watches.stream().noneMatch(w -> w.name().equals("minecraft:villages")))
            throw new IllegalStateException("villages not watched; sets: " + watches.stream().map(w -> w.name()).toList());
        if (watches.stream().noneMatch(w -> w.name().equals("stormhold:great_castles") && w.castleSize() > 0))
            throw new IllegalStateException("great castles not watched");
        var t = List.of(new net.aiagent256.stormhold.worldgen.Avoidance.Threat("v", 100, 100, 72, 0));
        if (net.aiagent256.stormhold.worldgen.Avoidance.crowds(t, 100, 160, 32) == null)
            throw new IllegalStateException("holdfast 60 blocks from a village start was allowed");
        if (net.aiagent256.stormhold.worldgen.Avoidance.crowds(t, 100, 320, 32) != null)
            throw new IllegalStateException("holdfast 220 blocks from a village start was refused");
        var gen = level.getChunkSource().getGenerator();
        var ctx = new net.minecraft.world.level.levelgen.structure.Structure.GenerationContext(level.registryAccess(), gen,
                gen.getBiomeSource(), level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(),
                new net.minecraft.world.level.ChunkPos(0, 0), level, b -> true);
        var near = net.aiagent256.stormhold.worldgen.Avoidance.near(ctx, 0, 0, 3000, 18);
        Stormhold.LOGGER.info("Avoidance: {} watched sets, {} possible starts within 3000 blocks", watches.size(), near.size());
        if (near.isEmpty()) throw new IllegalStateException("no possible villages or castles within 3000 blocks");
        // and the other way round: a village that would start where a castle could stand gives way (mixin)
        var sets = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET);
        var castles = sets.get(Stormhold.id("great_castles"));
        if (castles == null || !(castles.placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement rsp))
            throw new IllegalStateException("great_castles set missing");
        var village = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                .get(new net.minecraft.resources.ResourceLocation("minecraft:village_plains"));
        if (village == null) throw new IllegalStateException("no plains village structure");
        net.minecraft.world.level.ChunkPos claimed = null;
        for (int rx = 0; rx < 40 && claimed == null; rx++) {
            for (int rz = 0; rz < 40 && claimed == null; rz++) {
                var c = rsp.getPotentialStructureChunk(level.getSeed(), rx * rsp.spacing(), rz * rsp.spacing());
                if (net.aiagent256.stormhold.worldgen.Avoidance.onCastleGround(level.registryAccess(), level.getSeed(), gen,
                        gen.getBiomeSource(), level.getChunkSource().randomState(), level, c, 80)) claimed = c;
            }
        }
        if (claimed == null) throw new IllegalStateException("no castle site found in 40x40 cells");
        var start = village.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), claimed, 0, level, b -> true);
        if (start.isValid()) throw new IllegalStateException("a village was allowed to start on castle ground at " + claimed);
        Stormhold.LOGGER.info("Avoidance: village refused at castle site {}", claimed);
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void houseStandingGatesGuards(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        // the framework's mock player is creative, which guards always ignore; use a survival one
        var player = new net.minecraft.world.entity.player.Player(level, BlockPos.ZERO, 0.0F,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "stormhold-test")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        String houseId = "standingtest";
        mgr.house(houseId).disposition = 0;
        mgr.standing.remove(houseId);
        if (net.aiagent256.stormhold.house.Standing.friendly(mgr, houseId, player.getUUID())) throw new IllegalStateException("wary house counted friendly");
        GarrisonEntity knight = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(4, 2, 4)), Sides.house(houseId), false);
        if (knight == null) throw new IllegalStateException("no knight");
        try {
            if (!knight.isHostileTo(player)) throw new IllegalStateException("wary house's knight ignored a stranger");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), 40, "test");
            if (!net.aiagent256.stormhold.house.Standing.friendly(mgr, houseId, player.getUUID())) throw new IllegalStateException("+40 did not make friends");
            knight = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(6, 2, 4)), Sides.house(houseId), false);
            if (knight.isHostileTo(player)) throw new IllegalStateException("friendly house's knight attacked a friend");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), net.aiagent256.stormhold.house.Standing.HIT_GUARD, "test");
            if (net.aiagent256.stormhold.house.Standing.friendly(mgr, houseId, player.getUUID())) throw new IllegalStateException("hitting a guard kept you friendly");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), -500, "test");
            if (net.aiagent256.stormhold.house.Standing.get(mgr, houseId, player.getUUID()) != net.aiagent256.stormhold.house.Standing.MIN) throw new IllegalStateException("standing not clamped");
            // bend the knee: a friendly house with a dead lord hands over peacefully with a bigger garrison
            Holding h = sample(g);
            h.houseId = houseId;
            h.owner = Sides.house(houseId);
            h.lordAlive = false;
            mgr.add(h);
            try {
                HoldingEvents.transfer(level, mgr, h, Sides.player(player.getUUID()), true);
                if (!h.owner.equals(Sides.player(player.getUUID()))) throw new IllegalStateException("peaceful transfer failed");
                if (h.count(Role.KNIGHT) < (int) Math.ceil(h.capacity(Role.KNIGHT) * 0.8)) throw new IllegalStateException("bent knee gave a small garrison");
                if (net.aiagent256.stormhold.house.Standing.get(mgr, houseId, player.getUUID()) != net.aiagent256.stormhold.house.Standing.MIN)
                    throw new IllegalStateException("peaceful transfer changed standing");
            } finally {
                Garrisons.discardLive(level, h.live, false);
                mgr.holdings.remove(h.id);
                mgr.byKey.remove(h.key);
            }
        } finally {
            for (var e : level.getEntitiesOfClass(GarrisonEntity.class, new net.minecraft.world.phys.AABB(g.absolutePos(BlockPos.ZERO)).inflate(20))) e.discard();
            mgr.standing.remove(houseId);
            mgr.houses.remove(houseId);
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void foundHoldingAndSupplyIt(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        var banner = new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.WAR_BANNER.get());
        banner.getOrCreateTag().put("Corner1", net.minecraft.nbt.NbtUtils.writeBlockPos(g.absolutePos(new BlockPos(1, 2, 1))));
        banner.getOrCreateTag().put("Corner2", net.minecraft.nbt.NbtUtils.writeBlockPos(g.absolutePos(new BlockPos(14, 2, 14))));
        player.getInventory().setItem(0, banner);
        BlockPos table = g.absolutePos(new BlockPos(7, 2, 7));
        g.setBlock(new BlockPos(7, 2, 7), net.aiagent256.stormhold.registry.ModBlocks.WAR_TABLE.get());
        Holding h = net.aiagent256.stormhold.holding.Founding.tryFound(level, player, table);
        if (h == null) throw new IllegalStateException("founding failed");
        try {
            if (!h.owner.equals(Sides.player(player.getUUID())) || h.tier != 1 || !h.scanned || h.posts.isEmpty())
                throw new IllegalStateException("founded holding is wrong: tier " + h.tier + " scanned " + h.scanned);
            if (mgr.at(table, 0) != h) throw new IllegalStateException("table is not inside its own holding");
            if (net.aiagent256.stormhold.item.WarBannerItem.corner(banner, 1) != null) throw new IllegalStateException("corners not cleared");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 20));
            var state = level.getBlockState(table);
            state.getBlock().use(state, level, table, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(table), net.minecraft.core.Direction.UP, table, false));
            if (h.stock(new net.minecraft.resources.ResourceLocation("minecraft:bread")) != 20) throw new IllegalStateException("bread not stored: " + h.stock);
            if (!player.getMainHandItem().isEmpty()) throw new IllegalStateException("bread still in hand");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 5));
            state.getBlock().use(state, level, table, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(table), net.minecraft.core.Direction.UP, table, false));
            if (player.getMainHandItem().getCount() != 5) throw new IllegalStateException("dirt was taken");
        } finally {
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** The capital: 256 blocks across, tier 4, a King on the throne, and friendly by default. */
    @GameTest(template = "arena", timeoutTicks = 2400)
    public static void cityGeneratesAndAttaches(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        var reg = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var gen = level.getChunkSource().getGenerator();
        HoldingManager mgr = HoldingManager.get(level);
        var structure = reg.get(Stormhold.id("great_city_north"));
        if (structure == null) throw new IllegalStateException("city structure not registered");
        BlockPos at = g.absolutePos(BlockPos.ZERO).offset(6000, 0, 6000);
        net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(at);
        net.aiagent256.stormhold.worldgen.CastleStructure.ignoreTerrain = true;
        var start = structure.generate(level.registryAccess(), gen, gen.getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), cp, 0, level, b -> true);
        net.aiagent256.stormhold.worldgen.CastleStructure.ignoreTerrain = false;
        if (!start.isValid()) throw new IllegalStateException("city generated nothing");
        BoundingBox bb = start.getBoundingBox();
        if (bb.getXSpan() < 250 || bb.getZSpan() < 250) throw new IllegalStateException("city box too small: " + bb);
        // every chunk of the city must be within 8 chunks of the start chunk, or vanilla never places it
        for (var piece : start.getPieces()) {
            BoundingBox pb = piece.getBoundingBox();
            for (int cx = pb.minX() >> 4; cx <= pb.maxX() >> 4; cx++)
                for (int cz = pb.minZ() >> 4; cz <= pb.maxZ() >> 4; cz++)
                    if (Math.abs(cx - cp.x) > 8 || Math.abs(cz - cp.z) > 8)
                        throw new IllegalStateException("city tile chunk " + cx + "," + cz + " is out of reference range of " + cp);
        }
        long t0 = System.currentTimeMillis();
        net.minecraft.world.level.ChunkPos.rangeClosed(new net.minecraft.world.level.ChunkPos(bb.minX() >> 4, bb.minZ() >> 4),
                new net.minecraft.world.level.ChunkPos(bb.maxX() >> 4, bb.maxZ() >> 4)).forEach(c ->
                start.placeInChunk(level, level.structureManager(), gen, level.getRandom(),
                        new BoundingBox(c.getMinBlockX(), level.getMinBuildHeight(), c.getMinBlockZ(), c.getMaxBlockX(),
                                level.getMaxBuildHeight(), c.getMaxBlockZ()), c));
        Stormhold.LOGGER.info("City placed in {} ms", System.currentTimeMillis() - t0);
        Holding h = net.aiagent256.stormhold.holding.Detector.createForTest(level, mgr, "test:city@" + cp.toLong(),
                Stormhold.id("great_city_north"), start);
        if (h == null) throw new IllegalStateException("city did not attach");
        try {
            if (h.tier != 4) throw new IllegalStateException("city tier " + h.tier);
            if (!h.lordName.startsWith("King ")) throw new IllegalStateException("city lord is " + h.lordName);
            if (h.capacity(Role.KNIGHT) < 30) throw new IllegalStateException("city garrison too small");
            var house = mgr.house(h.houseId);
            if (!h.id.equals(house.capital)) throw new IllegalStateException("city is not its house's capital");
            if (house.disposition < 40) throw new IllegalStateException("capital house not friendly: " + house.disposition);
            if (!level.getBlockState(h.bannerPos.below()).isSolidRender(level, h.bannerPos.below()))
                throw new IllegalStateException("banner stand floats at " + h.bannerPos);
            if (!level.getBlockState(h.lordPos).isAir()) throw new IllegalStateException("throne is inside a block at " + h.lordPos);
            if (!level.getBlockState(h.bannerPos).canBeReplaced()) throw new IllegalStateException("city banner stand is inside a block at " + h.bannerPos);
            if (!level.getBlockState(h.warTablePos).canBeReplaced()) throw new IllegalStateException("city war table spot is blocked at " + h.warTablePos);
            if (!h.extras.containsKey("barracks") || !h.extras.containsKey("notice_board") || h.extras.getOrDefault("house", List.of()).size() < 6)
                throw new IllegalStateException("city features missing: " + h.extras.keySet());
            net.aiagent256.stormhold.holding.City.placeBlocks(level, h);
            if (!level.getBlockState(h.extras.get("barracks").get(0)).is(net.aiagent256.stormhold.registry.ModBlocks.MUSTER_POST.get()))
                throw new IllegalStateException("muster post not placed");
            if (!level.getBlockState(h.extras.get("notice_board").get(0)).is(net.aiagent256.stormhold.registry.ModBlocks.NOTICE_BOARD.get()))
                throw new IllegalStateException("notice board not placed");
            // a posting appears, is accepted, becomes a camp, and clearing the camp pays out
            net.aiagent256.stormhold.holding.City.tickPostings(level, mgr, h, level.getGameTime() + 1);
            if (h.postings.isEmpty()) throw new IllegalStateException("no posting generated");
            var player = g.makeMockPlayer();
            player.moveTo(h.center().getX(), h.center().getY(), h.center().getZ());
            var post = h.postings.get(0);
            if (!(player instanceof net.minecraft.server.level.ServerPlayer)) {
                post.takenBy = player.getUUID();
                var c = new net.aiagent256.stormhold.holding.Campaign();
                c.kind = net.aiagent256.stormhold.holding.Campaign.Kind.CAMP;
                c.origin = h.id;
                c.to = post.at;
                c.from = post.at;
                post.campaign = c.id;
                mgr.campaigns.add(c);
                int before = mgr.campaigns.size();
                net.aiagent256.stormhold.holding.Campaigns.tick(level, mgr, level.getGameTime());
                if (mgr.campaigns.size() != before - 1) throw new IllegalStateException("empty camp was not cleared");
                if (h.postings.contains(post)) throw new IllegalStateException("cleared posting still on the board");
                if (h.stock(new net.minecraft.resources.ResourceLocation("minecraft", "emerald")) != post.reward)
                    throw new IllegalStateException("reward not banked for an absent player: " + h.stock);
            }
        } finally {
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            mgr.houses.remove(h.houseId);
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void renownScalesGearAndTreasure(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding poor = sample(g);
        poor.renown = 1;
        Holding famed = sample(g);
        famed.renown = 5;
        GarrisonEntity a = Garrisons.spawn(level, mgr, poor, Role.KNIGHT, g.absolutePos(new BlockPos(3, 2, 3)), Sides.house("x"), false);
        GarrisonEntity b = Garrisons.spawn(level, mgr, famed, Role.KNIGHT, g.absolutePos(new BlockPos(7, 2, 3)), Sides.house("x"), false);
        try {
            var aChest = a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            var bChest = b.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            if (bChest.getItem() == aChest.getItem()) throw new IllegalStateException("renown 1 and 5 knights wear the same armour: " + aChest);
            int prot = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION, bChest);
            if (prot != 4) throw new IllegalStateException("famed knight has Protection " + prot);
            if (net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION, aChest) != 0)
                throw new IllegalStateException("poor knight has enchanted armour");
            if (b.getMaxHealth() <= a.getMaxHealth()) throw new IllegalStateException("renown did not raise health");
            var loot = net.aiagent256.stormhold.holding.Treasure.roll(level.random, 5);
            boolean netherite = loot.stream().anyMatch(s -> s.is(net.minecraft.world.item.Items.NETHERITE_INGOT));
            boolean prot4 = loot.stream().anyMatch(s -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION, s) == 4);
            if (!netherite || !prot4) throw new IllegalStateException("famed treasure is thin: " + loot);
            if (net.aiagent256.stormhold.holding.Treasure.roll(level.random, 1).size() != 0) throw new IllegalStateException("poor castle paid a bonus");
        } finally {
            a.discard();
            b.discard();
        }
        g.succeed();
    }

    /** Calling men out of a castle to ride with you, and sending them back in. Also: a war party heads for the gate. */
    @GameTest(template = "arena")
    public static void companyMustersAndDismisses(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        player.moveTo(g.absolutePos(new BlockPos(8, 2, 8)).getX() + 0.5, g.absolutePos(new BlockPos(8, 2, 8)).getY(),
                g.absolutePos(new BlockPos(8, 2, 8)).getZ() + 0.5, 0, 0);
        Holding h = sample(g);
        h.owner = Sides.player(player.getUUID());
        h.houseId = "testhouse";
        mgr.add(h);
        try {
            int knights = h.count(Role.KNIGHT), archers = h.count(Role.ARCHER);
            for (int i = 0; i < 4; i++) Garrisons.materialize(level, mgr, h);
            int standing = h.live.size();
            if (standing < 6) throw new IllegalStateException("only " + standing + " men standing");
            int got = net.aiagent256.stormhold.holding.Company.muster(level, mgr, player, h, Role.KNIGHT, 2);
            got += net.aiagent256.stormhold.holding.Company.muster(level, mgr, player, h, Role.ARCHER, 1);
            if (got != 3) throw new IllegalStateException("mustered " + got);
            if (h.count(Role.KNIGHT) != knights - 2 || h.count(Role.ARCHER) != archers - 1) throw new IllegalStateException("garrison not debited");
            if (h.live.size() != standing - 3) throw new IllegalStateException("standing men did not leave their posts: " + h.live.size() + " of " + standing);
            var mine = level.getEntitiesOfClass(net.aiagent256.stormhold.entity.GarrisonEntity.class, player.getBoundingBox().inflate(12),
                    e -> player.getUUID().equals(e.owner()));
            if (mine.size() != 3) throw new IllegalStateException("company has " + mine.size() + " men");
            for (var m : mine) {
                if (m.holdsPosition() || !m.shouldBeSaved() || m.holdingId() != null) throw new IllegalStateException("company man is still a garrison soldier");
                if (!m.isAlly(player)) throw new IllegalStateException("company man is not the player's ally");
            }
            // the armoury: diamond kit for 2 of the 3 from the pack, the third from the castle's stores
            player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 60));
            var diamondKey = new net.minecraft.resources.ResourceLocation("minecraft:diamond");
            int armed = net.aiagent256.stormhold.holding.Armoury.arm(level, mgr, player, h, net.aiagent256.stormhold.holding.Armoury.Kit.DIAMOND, false);
            if (armed != 2) throw new IllegalStateException("armed " + armed + " from a pack of 60 diamonds");
            if (player.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) != 8) throw new IllegalStateException("pack not charged");
            h.addStock(diamondKey, 30);
            armed = net.aiagent256.stormhold.holding.Armoury.arm(level, mgr, player, h, net.aiagent256.stormhold.holding.Armoury.Kit.DIAMOND, true);
            // the two already in diamond only need the enchantments (the mock player is creative, so no levels are charged)
            if (armed != 3) throw new IllegalStateException("armed " + armed + " with stores");
            if (h.stock(diamondKey) != 12) throw new IllegalStateException("stores charged " + (30 - h.stock(diamondKey)));
            int diamondChests = 0, enchanted = 0;
            for (var m : mine) {
                if (m.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)) diamondChests++;
                if (m.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEnchanted()) enchanted++;
                if (m.role() == Role.ARCHER && !m.getMainHandItem().is(net.minecraft.world.item.Items.BOW)) throw new IllegalStateException("archer lost his bow");
                if (m.role() == Role.KNIGHT && !m.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SWORD)) throw new IllegalStateException("knight has no diamond sword");
            }
            if (diamondChests != 3 || enchanted != 3) throw new IllegalStateException(diamondChests + " in diamond, " + enchanted + " enchanted");
            if (player.getInventory().countItem(net.minecraft.world.item.Items.IRON_CHESTPLATE)
                    + player.getInventory().countItem(net.minecraft.world.item.Items.CHAINMAIL_CHESTPLATE)
                    + player.getInventory().countItem(net.minecraft.world.item.Items.LEATHER_CHESTPLATE) < 1)
                throw new IllegalStateException("old kit did not come back");
            // far away: can't call or dismiss
            player.moveTo(player.getX() + 400, player.getY(), player.getZ(), 0, 0);
            if (net.aiagent256.stormhold.holding.Company.muster(level, mgr, player, h, Role.KNIGHT, 1) != 0) throw new IllegalStateException("mustered from afar");
            player.moveTo(player.getX() - 400, player.getY(), player.getZ(), 0, 0);
            int back = net.aiagent256.stormhold.holding.Company.dismiss(level, mgr, player, h);
            if (back != 3 || h.count(Role.KNIGHT) != knights || h.count(Role.ARCHER) != archers) throw new IllegalStateException("dismiss returned " + back);
            // a war party raised here heads for the gate first
            h.attackTarget = g.absolutePos(new BlockPos(8, 2, 8)).offset(300, 0, 0);
            var party = net.aiagent256.stormhold.holding.Campaigns.launchParty(level, mgr, h, h.attackTarget);
            if (party == null) throw new IllegalStateException("no party");
            boolean via = false;
            for (var id : party.live.keySet()) {
                if (level.getEntity(id) instanceof net.aiagent256.stormhold.entity.GarrisonEntity ge && ge.marchVia() != null) via = true;
            }
            if (!party.live.isEmpty() && !via) throw new IllegalStateException("party does not route through the gate");
            Garrisons.discardLive(level, party.live, false);
            mgr.campaigns.remove(party);
        } finally {
            for (var e : level.getEntitiesOfClass(net.aiagent256.stormhold.entity.GarrisonEntity.class, player.getBoundingBox().inflate(64))) e.discard();
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** Your men take orders, wear what you give them, heal, and keep the peace with a friendly house. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void companyOrdersKitAndCare(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = new net.minecraft.world.entity.player.Player(level, g.absolutePos(new BlockPos(8, 2, 8)), 0.0F,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "stormhold-company")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        BlockPos at = g.absolutePos(new BlockPos(8, 2, 8));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        GarrisonEntity man = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(6, 2, 6)), Sides.player(player.getUUID()), false);
        GarrisonEntity guard = null;
        net.minecraft.world.entity.monster.Zombie near = null, far = null;
        if (man == null) throw new IllegalStateException("no man");
        try {
            net.aiagent256.stormhold.holding.Company.enlist(level, mgr, man, player, null);
            if (!player.getUUID().equals(man.owner()) || man.baseName().isEmpty()) throw new IllegalStateException("not enlisted");
            // kit
            var kit = new net.aiagent256.stormhold.menu.SoldierKit(man);
            var chest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
            if (net.aiagent256.stormhold.menu.SoldierKit.allowed(0, chest) || !net.aiagent256.stormhold.menu.SoldierKit.allowed(1, chest))
                throw new IllegalStateException("kit slot rules wrong for a chestplate");
            if (net.aiagent256.stormhold.menu.SoldierKit.allowed(6, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT)))
                throw new IllegalStateException("dirt allowed in the pouch");
            kit.setItem(1, chest);
            if (!man.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE))
                throw new IllegalStateException("kit did not dress him");
            // healing from the pouch
            man.setHealth(6);
            man.pouch().set(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD, 3));
            if (!man.consume(man.pouch().get(0)) || man.getHealth() <= 6 || man.pouch().get(0).getCount() != 2)
                throw new IllegalStateException("eating did not heal: " + man.getHealth() + " pouch " + man.pouch().get(0));
            // peace with a friendly house, war with a wary one
            String houseId = "companypeace";
            mgr.house(houseId).disposition = 0;
            mgr.standing.remove(houseId);
            guard = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(9, 2, 9)), Sides.house(houseId), false);
            if (!man.isHostileTo(guard) || !guard.isHostileTo(man)) throw new IllegalStateException("a wary house's guard and your man ignored each other");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), 60, "test");
            guard.discard();
            guard = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(9, 2, 10)), Sides.house(houseId), false);
            man.discard();
            man = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(6, 2, 6)), Sides.player(player.getUUID()), false);
            net.aiagent256.stormhold.holding.Company.enlist(level, mgr, man, player, null);
            if (man.isHostileTo(guard) || guard.isHostileTo(man)) throw new IllegalStateException("your man and a friendly house's guard fought");
            // clearing a building: only foes inside count, and it reports clear once they're gone
            BlockPos room = g.absolutePos(new BlockPos(3, 2, 3));
            near = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            near.moveTo(room.getX() + 0.5, room.getY(), room.getZ() + 0.5, 0, 0);
            level.addFreshEntity(near);
            far = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            far.moveTo(room.getX() + 40.5, room.getY(), room.getZ() + 0.5, 0, 0);
            level.addFreshEntity(far);
            var order = net.aiagent256.stormhold.holding.Orders.of(player.getUUID());
            order.mode = net.aiagent256.stormhold.holding.Orders.Mode.CLEAR;
            order.pos = room;
            order.since = level.getGameTime();
            if (!man.isHostileTo(near)) throw new IllegalStateException("zombie in the room not a target");
            if (man.isHostileTo(far)) throw new IllegalStateException("zombie 40 blocks off counted as in the room");
            net.aiagent256.stormhold.holding.Orders.tickClear(level, player, order, level.getGameTime());
            if (man.getTarget() != near) throw new IllegalStateException("man not sent at the zombie in the room");
            near.discard();
            man.moveTo(room.getX() + 0.5, room.getY(), room.getZ() + 0.5, 0, 0);
            man.setTarget(null);
            for (int i = 0; i < 3; i++) net.aiagent256.stormhold.holding.Orders.tickClear(level, player, order, level.getGameTime());
            if (order.mode != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW) throw new IllegalStateException("room never reported clear");
            // following: only what's close to the lord
            if (man.isHostileTo(far)) throw new IllegalStateException("following man would chase a zombie 40 blocks off");
            net.aiagent256.stormhold.holding.Orders.attack(level, player, far);
            if (man.getTarget() != far) throw new IllegalStateException("attack order ignored");
        } finally {
            net.aiagent256.stormhold.holding.Orders.forget(player.getUUID());
            if (man != null) man.discard();
            if (guard != null) guard.discard();
            if (near != null) near.discard();
            if (far != null) far.discard();
        }
        g.succeed();
    }

    /** The mine digs a real tunnel and pays in what it digs; the pen breeds and the butcher culls; ripe crops are cut. */
    @GameTest(template = "arena", timeoutTicks = 600)
    public static void fiefWorkIsReal(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        int cx = -6000 - (int) (Math.floorMod(g.absolutePos(BlockPos.ZERO).asLong(), 7L)) * 400, cz = 6000;
        int cc = cx >> 4, ccz = cz >> 4;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setChunkForced(cc + dx, ccz + dz, true);
                level.getChunk(cc + dx, ccz + dz);
            }
        }
        int y = 80;
        // wide enough that a mine at the far edge of the fief still has rock to tunnel into
        for (int x = cx - 175; x <= cx + 175; x++) {
            for (int z = cz - 175; z <= cz + 175; z++) {
                for (int yy = y - 24; yy < y - 3; yy++) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
                for (int yy = y - 3; yy < y - 1; yy++) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int yy = y; yy < y + 24; yy++) {
                    if (!level.getBlockState(new BlockPos(x, yy, z)).isAir()) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // a seam of iron for the miners to find
        for (int x = cx - 175; x <= cx + 175; x++) {
            for (int z = cz - 175; z <= cz + 175; z++) {
                if ((x + z) % 3 != 0) continue;
                for (int yy = y - 14; yy <= y - 10; yy++) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState(), 2);
            }
        }
        Holding h = sample(g);
        h.box = new BoundingBox(cx - 8, y, cz - 8, cx + 7, y + 7, cz + 7);
        h.gatePos = new BlockPos(cx, y, cz + 8);
        h.owner = Sides.player(UUID.randomUUID());
        mgr.add(h);
        var area = new net.minecraft.world.phys.AABB(new BlockPos(cx, y, cz)).inflate(180, 60, 180);
        try {
            var minePiece = net.aiagent256.stormhold.holding.DataRegistries.fiefPiece("mine");
            var penPiece = net.aiagent256.stormhold.holding.DataRegistries.fiefPiece("pasture");
            boolean mineOk = false, penOk = false;
            for (int i = 0; i < 40 && !mineOk; i++) mineOk = net.aiagent256.stormhold.holding.Fief.place(level, mgr, h, minePiece);
            for (int i = 0; i < 40 && !penOk; i++) penOk = net.aiagent256.stormhold.holding.Fief.place(level, mgr, h, penPiece);
            if (!mineOk || !penOk) throw new IllegalStateException("could not place mine " + mineOk + " pasture " + penOk);
            Holding.FiefBuilding mine = h.fief.stream().filter(b -> b.piece().equals("mine")).findFirst().orElseThrow();
            Holding.FiefBuilding pen = h.fief.stream().filter(b -> b.piece().equals("pasture")).findFirst().orElseThrow();
            // the mine
            var d = net.aiagent256.stormhold.holding.Produce.dig(level, h, mine);
            if (d == null) throw new IllegalStateException("no working face found in the mine at " + mine.box());
            BlockPos first = d.next;
            java.util.Map<String, Integer> made = new java.util.HashMap<>();
            for (int i = 0; i < 40; i++) net.aiagent256.stormhold.holding.Produce.slice(level, d, made);
            Stormhold.LOGGER.info("Mine test: dug {} slices, {} down, {} turns, made {}", d.dug, d.down, d.turns, made);
            if (d.dug < 30) {
                StringBuilder sb = new StringBuilder("mine dug only " + d.dug + " slices (turns " + d.turns + ", done " + d.done + ", heading "
                        + d.dx + "," + d.dz + " side " + d.sx + "," + d.sz + ", next " + d.next + ", last " + d.last + "); at the face:");
                for (int dy = -1; dy <= 3; dy++) {
                    for (int k = 0; k <= 1; k++) {
                        BlockPos q = d.next.offset(d.sx * k, dy, d.sz * k);
                        sb.append(' ').append(dy).append('/').append(k).append('=').append(level.getBlockState(q).getBlock().getDescriptionId().replace("block.minecraft.", ""));
                    }
                }
                throw new IllegalStateException(sb.toString());
            }
            if (!level.getBlockState(first.above()).isAir()) throw new IllegalStateException("first slice not cut at " + first);
            if (made.getOrDefault("cobblestone", 0) < 100) throw new IllegalStateException("mine yielded " + made);
            if (made.getOrDefault("iron_ingot", 0) < 1) throw new IllegalStateException("mine never reached the iron: " + made + ", " + d.down + " down");
            // the herd
            var herd0 = level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, net.minecraft.world.phys.AABB.of(pen.box()).inflate(1, 4, 1));
            if (herd0.size() < 6) throw new IllegalStateException("pen has " + herd0.size() + " beasts");
            int born = net.aiagent256.stormhold.holding.Produce.breed(level, pen, 2);
            if (born < 1) throw new IllegalStateException("no young in a pen of " + herd0.size());
            BlockPos inPen = pen.box().getCenter();
            for (int i = 0; i < 10; i++) {
                var cow = net.minecraft.world.entity.EntityType.COW.create(level);
                cow.moveTo(inPen.getX() + 0.5, pen.box().minY() + 4, inPen.getZ() + 0.5, 0, 0);
                level.addFreshEntity(cow);
            }
            java.util.Map<String, Integer> meat = new java.util.HashMap<>();
            int culled = net.aiagent256.stormhold.holding.Produce.cull(level, pen, meat);
            if (culled < 1 || meat.getOrDefault("cooked_beef", 0) < 3) throw new IllegalStateException("butcher took " + culled + ": " + meat);
            // the field
            BlockPos f0 = new BlockPos(cx + 30, y, cz - 40);
            for (int i = 0; i < 5; i++) {
                level.setBlock(f0.offset(i, -1, 0), net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 2);
                level.setBlock(f0.offset(i, 0, 0), ((net.minecraft.world.level.block.CropBlock) net.minecraft.world.level.block.Blocks.WHEAT).getStateForAge(7), 2);
            }
            java.util.Map<String, Integer> crop = new java.util.HashMap<>();
            int cut = net.aiagent256.stormhold.holding.Produce.harvest(level, new BoundingBox(f0.getX() - 1, y - 2, f0.getZ() - 1, f0.getX() + 6, y + 2, f0.getZ() + 1), crop);
            if (cut != 5 || crop.getOrDefault("wheat", 0) != 5) throw new IllegalStateException("harvest cut " + cut + ": " + crop);
            if (level.getBlockState(f0).getValue(net.minecraft.world.level.block.CropBlock.AGE) != 0)
                throw new IllegalStateException("wheat not replanted");
        } finally {
            for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area, e -> !(e instanceof net.minecraft.world.entity.player.Player))) e.discard();
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) level.setChunkForced(cc + dx, ccz + dz, false);
        }
        g.succeed();
    }

    /** A hamlet of commoners grows outside a watchtower's walls and its people fill the stores. */
    @GameTest(template = "arena", timeoutTicks = 600)
    public static void fiefGrowsAndProduces(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        // somewhere of our own, so the buildings can't land on another test's arena
        int cx = 4000 + (int) (g.absolutePos(BlockPos.ZERO).asLong() % 7) * 300, cz = 4000;
        int cc = cx >> 4, ccz = cz >> 4;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                level.setChunkForced(cc + dx, ccz + dz, true);
                level.getChunk(cc + dx, ccz + dz);   // blocks until it's really there
            }
        }
        // a flat meadow to build on, whatever the world is
        int y = 70;
        for (int x = cx - 60; x <= cx + 60; x++) {
            for (int z = cz - 60; z <= cz + 60; z++) {
                for (int yy = y - 3; yy < y; yy++) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int yy = y; yy < y + 24; yy++) {
                    if (!level.getBlockState(new BlockPos(x, yy, z)).isAir()) level.setBlock(new BlockPos(x, yy, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        Holding h = sample(g);
        h.box = new BoundingBox(cx - 8, y, cz - 8, cx + 7, y + 7, cz + 7);
        h.gatePos = new BlockPos(cx, y, cz + 8);
        h.owner = Sides.player(g.makeMockPlayer().getUUID());
        h.surroundingsDone = true;
        mgr.add(h);
        try {
            net.aiagent256.stormhold.holding.Fief.plan(level, h);
            if (h.fiefPlan == null || h.fiefPlan.size() != 5) throw new IllegalStateException("hamlet plan " + h.fiefPlan);
            for (int i = 0; i < 120 && !h.fiefPlan.isEmpty(); i++) net.aiagent256.stormhold.holding.Fief.buildNext(level, mgr, h);
            if (h.fief.size() < 4) throw new IllegalStateException("built " + h.fief.size() + " of 5: " + h.fief + " plan left " + h.fiefPlan);
            int beds = 0;
            for (var b : h.fief) {
                if (!b.piece().startsWith("cottage")) continue;
                for (int x = b.box().minX(); x <= b.box().maxX(); x++)
                    for (int yy = b.box().minY(); yy <= b.box().maxY(); yy++)
                        for (int z = b.box().minZ(); z <= b.box().maxZ(); z++)
                            if (level.getBlockState(new BlockPos(x, yy, z)).getBlock() instanceof net.minecraft.world.level.block.BedBlock) beds++;
            }
            if (beds < 4) throw new IllegalStateException("cottages have " + beds + " bed blocks");
            var folk = level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class,
                    new net.minecraft.world.phys.AABB(new BlockPos(cx, y, cz)).inflate(120, 60, 120));
            if (folk.size() < 8) throw new IllegalStateException("only " + folk.size() + " villagers");
            if (!h.posts.get(Role.KNIGHT).stream().anyMatch(p -> !h.box.isInside(p))) throw new IllegalStateException("no patrol post outside the walls");
            net.aiagent256.stormhold.holding.Fief.census(level, mgr, h, level.getGameTime());
            if (h.fiefPop < 4 || !h.fiefJobs.containsKey("farmer")) throw new IllegalStateException("census: " + h.fiefReport);
            if (h.stock(new net.minecraft.resources.ResourceLocation("minecraft:bread")) <= 0) throw new IllegalStateException("farmers made no bread: " + h.fiefReport);
            Stormhold.LOGGER.info("Fief test: {}", h.fiefReport);
            for (var v : folk) v.discard();
            for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class,
                    new net.minecraft.world.phys.AABB(new BlockPos(cx, y, cz)).inflate(120, 60, 120))) a.discard();
        } finally {
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) level.setChunkForced(cc + dx, ccz + dz, false);
        }
        g.succeed();
    }

    /** A column rides to the player on the clock and the men fall in on arrival. */
    @GameTest(template = "arena")
    public static void columnRidesToPlayer(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        // the mock player isn't on the player list; a column needs a real ServerPlayer to find
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().isEmpty() ? null : level.getServer().getPlayerList().getPlayers().get(0);
        if (player == null) {
            // headless test server: exercise the maths only
            var c = new net.aiagent256.stormhold.holding.Campaign();
            c.from = BlockPos.ZERO;
            c.to = new BlockPos(500, 0, 0);
            c.add(Role.KNIGHT, 4);
            if (!net.aiagent256.stormhold.holding.Columns.eta(c).startsWith("1m 40s")) throw new IllegalStateException("eta " + net.aiagent256.stormhold.holding.Columns.eta(c));
            g.succeed();
            return;
        }
        h.owner = Sides.player(player.getUUID());
        mgr.add(h);
        try {
            int knights = h.count(Role.KNIGHT);
            var want = new java.util.EnumMap<Role, Integer>(Role.class);
            want.put(Role.KNIGHT, 2);
            want.put(Role.ARCHER, 1);
            var c = net.aiagent256.stormhold.holding.Columns.send(level, mgr, player, h, want);
            if (c == null || c.total() != 3 || h.count(Role.KNIGHT) != knights - 2) throw new IllegalStateException("column not raised");
            mgr.campaigns.remove(c);
        } finally {
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** The portcullis raises for the castle's own men on the move and stays down with an enemy at it. */
    @GameTest(template = "arena")
    public static void portcullisRaisesForGarrison(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        mgr.add(h);
        BlockPos gate = h.gatePos;
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy < 3; dy++) level.setBlock(gate.offset(dx, dy, 0),
                net.aiagent256.stormhold.registry.ModBlocks.PORTCULLIS.get().defaultBlockState(), 3);
        net.aiagent256.stormhold.entity.GarrisonEntity knight = null;
        try {
            h.gates = null;
            net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (h.gates == null || h.gates.size() != 1) throw new IllegalStateException("gate planes found: " + (h.gates == null ? "none" : h.gates.size()));
            if (level.getBlockState(gate).getValue(net.aiagent256.stormhold.block.PortcullisBlock.OPEN)) throw new IllegalStateException("gate starts open");
            // a posted guard beside it doesn't raise it
            knight = Garrisons.spawn(level, mgr, h, Role.KNIGHT, gate.offset(0, 0, -3), h.owner, false);
            if (knight == null) throw new IllegalStateException("no knight");
            for (int i = 0; i < 6; i++) net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (level.getBlockState(gate).getValue(net.aiagent256.stormhold.block.PortcullisBlock.OPEN)) throw new IllegalStateException("a posted guard opened the gate");
            // one marching out does
            knight.setMarchTarget(gate.offset(0, 0, 40));
            for (int i = 0; i < 6; i++) net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (!level.getBlockState(gate).getValue(net.aiagent256.stormhold.block.PortcullisBlock.OPEN)) throw new IllegalStateException("gate did not raise for a marching knight");
            if (!level.getBlockState(gate).getCollisionShape(level, gate).isEmpty()) throw new IllegalStateException("raised gate still blocks");
            // an enemy at the gate keeps it down
            var foe = Garrisons.spawn(level, mgr, null, Role.KNIGHT, gate.offset(0, 0, 3), Sides.house("otherhouse"), false);
            for (int i = 0; i < 8; i++) net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (level.getBlockState(gate).getValue(net.aiagent256.stormhold.block.PortcullisBlock.OPEN)) throw new IllegalStateException("gate stayed up with an enemy at it");
            if (foe != null) foe.discard();
            // the ram
            if (!net.aiagent256.stormhold.holding.Gates.breach(level, h)) throw new IllegalStateException("breach did nothing");
            if (!level.getBlockState(gate).isAir()) throw new IllegalStateException("gate survived the ram");
        } finally {
            if (knight != null) knight.discard();
            Garrisons.discardLive(level, h.live, false);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** Two rival houses: one marches on the other, the castle changes hands by numbers, the record says so, and an envoy's
     *  offer pays out when a player takes a castle. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void housesActAndTheRecordKeeps(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding a = sample(g);
        a.name = "Ravenhold";
        a.houseId = "housea";
        a.owner = Sides.house("housea");
        a.key = "gametest-a@" + a.box.minX() + "," + a.box.minZ();
        Holding b = sample(g);
        b.name = "Weakwall";
        b.houseId = "houseb";
        b.owner = Sides.house("houseb");
        b.key = "gametest-b@" + b.box.minX() + "," + b.box.minZ();
        b.garrison.clear();
        b.add(Role.KNIGHT, 1);
        b.lordAlive = false;
        // far from any player so the war is settled by numbers
        BlockPos far = new BlockPos(a.box.minX() + 4000, a.box.minY(), a.box.minZ() + 4000);
        b.box = new BoundingBox(far.getX(), far.getY(), far.getZ(), far.getX() + 15, far.getY() + 7, far.getZ() + 15);
        b.bannerPos = far.offset(8, 2, 8);
        b.lordPos = far.offset(3, 2, 3);
        a.box = new BoundingBox(far.getX() + 300, far.getY(), far.getZ(), far.getX() + 315, far.getY() + 7, far.getZ() + 15);
        a.bannerPos = a.box.getCenter();
        a.gatePos = a.box.getCenter();
        mgr.add(a);
        mgr.add(b);
        try {
            housesActBody(g, level, mgr, a, b);
        } finally {
            mgr.holdings.remove(a.id);
            mgr.holdings.remove(b.id);
            mgr.campaigns.removeIf(c -> a.id.equals(c.origin) || b.id.equals(c.target));
            mgr.contracts.removeIf(c -> b.id.equals(c.target));
        }
        g.succeed();
    }

    private static void housesActBody(GameTestHelper g, ServerLevel level, HoldingManager mgr, Holding a, Holding b) {
        var ha = mgr.house("housea");
        var hb = mgr.house("houseb");
        net.aiagent256.stormhold.house.Politics.shift(mgr, "housea", "houseb", -80);
        if (!net.aiagent256.stormhold.house.Politics.rivals(mgr, "housea", "houseb")) throw new IllegalStateException("not rivals");
        var c = net.aiagent256.stormhold.house.Politics.war(level, mgr, ha, java.util.List.of(a), b, level.getGameTime());
        if (c.total() < 5) throw new IllegalStateException("war party too small: " + c.total());
        if (mgr.chronicle.isEmpty() || !mgr.chronicle.get(mgr.chronicle.size() - 1).text().contains("marches on Weakwall"))
            throw new IllegalStateException("the record did not note the march");
        // march it to the gate and let the numbers decide
        c.progress = 1.0;
        int before = mgr.chronicle.size();
        for (int i = 0; i < 5 && c.stage != Campaign.Stage.DONE; i++) Campaigns.tickOne(level, mgr, c);
        if (c.stage != Campaign.Stage.DONE) throw new IllegalStateException("the war never resolved");
        if (!b.owner.equals(Sides.house("housea")) || !b.houseId.equals("housea"))
            throw new IllegalStateException("Weakwall should have fallen to house A: owner " + b.owner + " house " + b.houseId);
        if (!b.lordAlive || b.lordName.isEmpty()) throw new IllegalStateException("the new lord was not installed");
        if (c.total() < 10) throw new IllegalStateException("a house should march with a real army, not " + c.total());
        if (mgr.chronicle.size() <= before) throw new IllegalStateException("the record did not note the fall");
        if (net.aiagent256.stormhold.house.Politics.relation(mgr, "housea", "houseb") > -80) throw new IllegalStateException("relations did not sour");
        // an envoy's offer pays when a player takes the castle
        var player = g.makeMockPlayer();
        var contract = new net.aiagent256.stormhold.house.Politics.Contract();
        contract.houseId = "houseb";
        contract.target = b.id;
        contract.reward = 20;
        contract.expiresAt = level.getGameTime() + 24000;
        mgr.contracts.add(contract);
        HoldingEvents.transfer(level, mgr, b, Sides.player(player.getUUID()));
        if (!contract.done) throw new IllegalStateException("the contract was not honoured");
        // a mock player isn't on the player list, so the fee goes to the realm's stores
        int emeralds = b.stock(new net.minecraft.resources.ResourceLocation("minecraft", "emerald"));
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            var st = player.getInventory().getItem(i);
            if (st.is(net.minecraft.world.item.Items.EMERALD)) emeralds += st.getCount();
        }
        if (emeralds != 20) throw new IllegalStateException("expected 20 emeralds, got " + emeralds);
        if (net.aiagent256.stormhold.house.Standing.get(mgr, "houseb", player.getUUID()) < hb.disposition + 15)
            throw new IllegalStateException("standing with the paying house did not rise");
        // the dawn account reads
        var lines = net.aiagent256.stormhold.holding.Chronicle.account(level, mgr, player.getUUID(), mgr.ownedBy(player.getUUID()),
                net.aiagent256.stormhold.holding.Chronicle.day(level));
        if (lines.size() < 3 || !lines.get(0).getString().startsWith("Day ")) throw new IllegalStateException("no dawn account");
        // and the realm page builds
        var data = net.aiagent256.stormhold.net.RealmData.build(level, player.getUUID(), player.blockPosition());
        if (data.own().size() != 1 || data.notes().isEmpty()) throw new IllegalStateException("realm page empty: own " + data.own().size());
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        data.write(buf);
        var back = net.aiagent256.stormhold.net.RealmData.read(buf);
        if (back.castles().size() != data.castles().size() || back.notes().size() != data.notes().size())
            throw new IllegalStateException("realm page did not round-trip");
    }

    /** Every card in the event deck can be dealt to a lord and answered either way, and each answer leaves a mark. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void everyMatterResolvesBothWays(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        UUID me = player.getUUID();
        Holding h = sample(g);
        h.name = "Dealhold";
        h.tier = 2;
        h.houseId = "housea";
        h.owner = Sides.player(me);
        h.key = "gametest-deal@" + h.box.minX() + "," + h.box.minZ();
        h.fiefPop = 10;
        Holding town = sample(g);
        town.name = "Dealmarket";
        town.layout = "town_walled";
        town.owner = Sides.player(me);
        town.key = "gametest-dealtown@" + town.box.minX() + "," + town.box.minZ();
        town.box = new BoundingBox(h.box.minX() + 600, h.box.minY(), h.box.minZ(), h.box.minX() + 615, h.box.maxY(), h.box.maxZ());
        town.fiefPop = 10;
        // a house with a castle, so envoys, tourneys and spies have somewhere to come from
        Holding theirs = sample(g);
        theirs.name = "Theirhold";
        theirs.houseId = "housea";
        theirs.owner = Sides.house("housea");
        theirs.key = "gametest-theirs@" + theirs.box.minX() + "," + theirs.box.minZ();
        theirs.box = new BoundingBox(h.box.minX() + 1200, h.box.minY(), h.box.minZ(), h.box.minX() + 1215, h.box.maxY(), h.box.maxZ());
        mgr.add(h);
        mgr.add(town);
        mgr.add(theirs);
        var emerald = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
        var iron = new net.minecraft.resources.ResourceLocation("minecraft", "iron_ingot");
        try {
            for (String kind : net.aiagent256.stormhold.holding.Events.KINDS) {
                for (int way = 1; way <= 2; way++) {
                    // the stores are full, so the first answer can always be paid for
                    for (var item : new String[]{"bread", "iron_ingot", "cobblestone", "gold_ingot", "emerald"}) {
                        h.stock.put(new net.minecraft.resources.ResourceLocation("minecraft", item), 100);
                        town.stock.put(new net.minecraft.resources.ResourceLocation("minecraft", item), 100);
                    }
                    h.fillGarrison(1.0);
                    h.fortify = 0;
                    h.work = net.aiagent256.stormhold.holding.Builder.NONE;
                    h.renown = 2;
                    // tourney wants a friend, spy and envoy want someone cooler: set the mood per card
                    mgr.standing.computeIfAbsent("housea", k -> new java.util.HashMap<>()).put(me,
                            kind.equals(net.aiagent256.stormhold.holding.Events.TOURNEY) ? 60 - mgr.house("housea").disposition : -mgr.house("housea").disposition);
                    Holding at = kind.equals(net.aiagent256.stormhold.holding.Events.PETITION) ? town : h;
                    var m = net.aiagent256.stormhold.holding.Events.draw(level, mgr, me, at, kind);
                    if (m == null) throw new IllegalStateException(kind + " would not deal");
                    if (net.aiagent256.stormhold.holding.Events.open(mgr, me) != m) throw new IllegalStateException(kind + " is not the open matter");
                    var card = net.aiagent256.stormhold.holding.Events.card(mgr, m);
                    if (card.text().isEmpty() || card.first().isEmpty() || card.second().isEmpty()) throw new IllegalStateException(kind + " has a blank card");
                    if (net.aiagent256.stormhold.holding.Events.present(mgr, m).size() != 2) throw new IllegalStateException(kind + " did not present");
                    int lines = mgr.chronicle.size(), men = h.troops(), emeralds = h.stock(emerald), ironBefore = h.stock(iron), pop = at.fiefPop;
                    if (!net.aiagent256.stormhold.holding.Events.choose(level, mgr, me, m.id, way)) throw new IllegalStateException(kind + " could not be chosen");
                    if (!m.done || net.aiagent256.stormhold.holding.Events.open(mgr, me) != null) throw new IllegalStateException(kind + " did not settle");
                    if (mgr.chronicle.size() != lines + 1) throw new IllegalStateException(kind + " left no line in the record");
                    if (net.aiagent256.stormhold.holding.Events.choose(level, mgr, me, m.id, way)) throw new IllegalStateException(kind + " settled twice");
                    switch (kind) {
                        case net.aiagent256.stormhold.holding.Events.SMITH -> {
                            if (way == 1 && (h.stock(iron) != ironBefore + 24 || h.stock(emerald) != emeralds - 8)) throw new IllegalStateException("the smith did not trade");
                            if (way == 2 && h.stock(iron) != ironBefore) throw new IllegalStateException("the smith traded anyway");
                        }
                        case net.aiagent256.stormhold.holding.Events.DESERTERS -> {
                            if (way == 1 && h.troops() != men) throw new IllegalStateException("the riders lost men");
                            if (way == 2 && h.troops() != men - m.roll) throw new IllegalStateException("deserters stayed: " + h.troops() + " of " + men);
                        }
                        case net.aiagent256.stormhold.holding.Events.BRIGANDS -> {
                            if (way == 2 && (h.troops() != men - 2 || h.stock(emerald) != emeralds + 15)) throw new IllegalStateException("the road was not cleared");
                        }
                        case net.aiagent256.stormhold.holding.Events.MASON -> {
                            if (way == 1 && h.fortify != 1) throw new IllegalStateException("the mason did not build");
                        }
                        case net.aiagent256.stormhold.holding.Events.WEDDING -> {
                            if (way == 1 && at.fiefPop != pop + 4) throw new IllegalStateException("no one came to the feast");
                            if (way == 2 && at.fiefPop != pop - 1) throw new IllegalStateException("no one left without a feast");
                        }
                        case net.aiagent256.stormhold.holding.Events.PETITION -> {
                            if (way == 1 && at.fiefPop != pop + 3) throw new IllegalStateException("the tolls did not draw trade");
                        }
                        case net.aiagent256.stormhold.holding.Events.RELIC -> {
                            if (way == 1 && h.renown != 3) throw new IllegalStateException("the relic brought no renown");
                            if (way == 2 && h.stock(emerald) != emeralds + 20) throw new IllegalStateException("the relic did not sell");
                        }
                        case net.aiagent256.stormhold.holding.Events.ENVOY -> {
                            if (way == 1 && h.nextRaid < level.getGameTime() + 3 * 24000L) throw new IllegalStateException("the envoy bought no peace");
                        }
                        default -> {
                        }
                    }
                }
            }
            // a matter nobody answers settles itself after two days
            var m = net.aiagent256.stormhold.holding.Events.draw(level, mgr, me, h, net.aiagent256.stormhold.holding.Events.RATS);
            int men = h.troops();
            net.aiagent256.stormhold.holding.Events.tick(level, mgr, level.getGameTime() + 3 * 24000L);
            if (!m.done || h.troops() != men - m.roll) throw new IllegalStateException("an unanswered matter did not settle the second way");
            if (!mgr.chronicle.get(mgr.chronicle.size() - 1).text().startsWith("Left unanswered")) throw new IllegalStateException("the record did not say it lapsed");
            // and the page shows an open one
            net.aiagent256.stormhold.holding.Events.draw(level, mgr, me, h, net.aiagent256.stormhold.holding.Events.SMITH);
            var data = net.aiagent256.stormhold.net.RealmData.build(level, me, player.blockPosition());
            if (data.notes().isEmpty() || data.notes().get(data.notes().size() - 1).day() != -2) throw new IllegalStateException("the page does not show the open matter");
            // saved and loaded
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.put("Matters", net.aiagent256.stormhold.holding.Events.save(mgr.matters));
            java.util.List<net.aiagent256.stormhold.holding.Events.Matter> back = new java.util.ArrayList<>();
            net.aiagent256.stormhold.holding.Events.load(tag.getList("Matters", net.minecraft.nbt.Tag.TAG_COMPOUND), back);
            if (back.size() != mgr.matters.size()) throw new IllegalStateException("matters did not round-trip");
        } finally {
            mgr.holdings.remove(h.id);
            mgr.holdings.remove(town.id);
            mgr.holdings.remove(theirs.id);
            mgr.byKey.remove(h.key);
            mgr.byKey.remove(town.key);
            mgr.byKey.remove(theirs.key);
            mgr.matters.removeIf(x -> me.equals(x.player));
            mgr.standing.getOrDefault("housea", new java.util.HashMap<>()).remove(me);
        }
        g.succeed();
    }

    /** A beast seen from a house's walls gets a price on its head; the record and the map know it; the slayer is paid. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void dragonBountiesPay(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        UUID me = player.getUUID();
        Holding mine = sample(g);
        mine.name = "Slayerhold";
        mine.owner = Sides.player(me);
        mine.key = "gametest-slayer@" + mine.box.minX() + "," + mine.box.minZ();
        Holding theirs = sample(g);
        theirs.name = "Wyrmwatch";
        theirs.houseId = "housed";
        theirs.owner = Sides.house("housed");
        theirs.renown = 3;
        theirs.key = "gametest-wyrm@" + theirs.box.minX() + "," + theirs.box.minZ();
        theirs.box = new BoundingBox(mine.box.minX() + 2000, mine.box.minY(), mine.box.minZ(), mine.box.minX() + 2015, mine.box.maxY(), mine.box.maxZ());
        theirs.greeted.add(me);
        mgr.add(mine);
        mgr.add(theirs);
        // a stand-in for the dragon: any beast the walls can see
        var beast = net.minecraft.world.entity.EntityType.COW.create(level);
        BlockPos seenAt = theirs.box.getCenter().offset(300, 0, 0);
        beast.moveTo(seenAt.getX(), seenAt.getY(), seenAt.getZ(), 0, 0);
        long now = level.getGameTime();
        var emerald = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
        try {
            var s = net.aiagent256.stormhold.holding.Dragons.note(mgr, beast, theirs, now);
            if (net.aiagent256.stormhold.holding.Dragons.sighting(mgr, beast.getUUID()) != s) throw new IllegalStateException("the sighting was not kept");
            if (net.aiagent256.stormhold.holding.Dragons.note(mgr, beast, theirs, now + 10) != s) throw new IllegalStateException("one beast, two entries");
            if (net.aiagent256.stormhold.holding.Dragons.seenNear(mgr, theirs.center(), net.aiagent256.stormhold.house.Politics.BOUNTY_REACH, now, 24000L).size() != 1)
                throw new IllegalStateException("the sighting is not near the castle");
            var house = mgr.house("housed");
            int lines = mgr.chronicle.size();
            if (!net.aiagent256.stormhold.house.Politics.bounty(level, mgr, house, java.util.List.of(theirs), now, java.util.List.of(me)))
                throw new IllegalStateException("no bounty was posted");
            if (net.aiagent256.stormhold.house.Politics.bounty(level, mgr, house, java.util.List.of(theirs), now, java.util.List.of(me)))
                throw new IllegalStateException("the same beast was posted twice");
            var c = mgr.contracts.stream().filter(x -> x.isBounty() && beast.getUUID().equals(x.target)).findFirst().orElseThrow();
            if (c.reward != 60 + 15 * 3 + 10 * theirs.tier) throw new IllegalStateException("odd reward " + c.reward);
            if (mgr.chronicle.size() != lines + 1) throw new IllegalStateException("the record did not note the bounty");
            // the page shows the beast with its price
            var data = net.aiagent256.stormhold.net.RealmData.build(level, me, player.blockPosition());
            if (data.beasts().size() != 1 || data.beasts().get(0).bounty() != c.reward || data.beasts().get(0).dead())
                throw new IllegalStateException("the page does not show the beast: " + data.beasts());
            var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            data.write(buf);
            if (net.aiagent256.stormhold.net.RealmData.read(buf).beasts().size() != 1) throw new IllegalStateException("beasts did not round-trip");
            // the slayer is paid (a mock player isn't on the list, so into the stores) and the house is grateful
            int before = mine.stock(emerald), standing = net.aiagent256.stormhold.house.Standing.get(mgr, "housed", me);
            net.aiagent256.stormhold.house.Politics.dragonSlain(level, mgr, me, beast.getUUID(), seenAt, "cow");
            if (!c.done) throw new IllegalStateException("the bounty was not honoured");
            if (mine.stock(emerald) != before + c.reward) throw new IllegalStateException("the slayer was not paid: " + mine.stock(emerald));
            if (net.aiagent256.stormhold.house.Standing.get(mgr, "housed", me) < standing + 20) throw new IllegalStateException("the house was not grateful");
            s.dead = true;
            data = net.aiagent256.stormhold.net.RealmData.build(level, me, player.blockPosition());
            if (!data.beasts().get(0).dead()) throw new IllegalStateException("the page still shows the beast alive");
            // nobody pays for a dead beast
            if (net.aiagent256.stormhold.house.Politics.bounty(level, mgr, house, java.util.List.of(theirs), now, java.util.List.of(me)))
                throw new IllegalStateException("a bounty was posted on a dead beast");
        } finally {
            mgr.holdings.remove(mine.id);
            mgr.holdings.remove(theirs.id);
            mgr.byKey.remove(mine.key);
            mgr.byKey.remove(theirs.key);
            mgr.contracts.removeIf(x -> x.isBounty() && beast.getUUID().equals(x.target));
            mgr.dragons.removeIf(x -> x.dragon.equals(beast.getUUID()));
            mgr.standing.getOrDefault("housed", new java.util.HashMap<>()).remove(me);
        }
        g.succeed();
    }

    /** The Nether holdfast finds a floor under the roof: somewhere in forty chunks it sites itself above the lava. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void netherHoldfastSitesOnTheFloor(GameTestHelper g) {
        ServerLevel nether = g.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        if (nether == null) throw new IllegalStateException("no Nether on this server");
        var reg = nether.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var structure = reg.get(Stormhold.id("nether_holdfast_nether"));
        if (structure == null) throw new IllegalStateException("nether_holdfast_nether not registered");
        var gen = nether.getChunkSource().getGenerator();
        int found = 0, tried = 0;
        for (int i = 0; i < 80 && found == 0; i++) {
            tried++;
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(200 + i * 7, 200 + (i % 5) * 11);
            var start = structure.generate(nether.registryAccess(), gen, gen.getBiomeSource(), nether.getChunkSource().randomState(),
                    nether.getStructureManager(), nether.getSeed(), cp, 0, nether, b -> true);
            if (!start.isValid()) continue;
            // the start's box is inflated by the terrain smoother's margin; the pieces' boxes start at the floor
            BoundingBox bb = start.getPieces().get(0).getBoundingBox();
            if (bb.minY() < 30 || bb.minY() > 110) throw new IllegalStateException("a Nether holdfast sited at y " + bb.minY());
            found++;
        }
        if (found == 0) throw new IllegalStateException("no site for a Nether holdfast in " + tried + " chunks; last: "
                + net.aiagent256.stormhold.worldgen.CastleStructure.lastRejection);
        g.succeed();
    }

    /** The finder names where the next watchtower will stand from placement maths alone, without loading a chunk. */
    @GameTest(template = "arena", timeoutTicks = 400)
    public static void finderPlansWithoutChunks(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        BlockPos from = g.absolutePos(BlockPos.ZERO).offset(9000, 0, -9000);
        long t = System.currentTimeMillis();
        var found = net.aiagent256.stormhold.worldgen.Finder.planned(level, from, "watchtower", 16, 20000);
        long took = System.currentTimeMillis() - t;
        if (found == null) throw new IllegalStateException("no watchtower within sixteen cells; last: " + net.aiagent256.stormhold.worldgen.CastleStructure.lastRejection);
        if (found.known()) throw new IllegalStateException("planned, not known");
        if (found.dist(from) > 16 * 18 * 16 * 1.5) throw new IllegalStateException("too far: " + found.dist(from));
        if (level.getChunkSource().getChunkNow(found.x() >> 4, found.z() >> 4) != null) throw new IllegalStateException("the finder loaded a chunk");
        Stormhold.LOGGER.info("Finder: {} at {}, {} in {} ms", found.what(), found.x(), found.z(), took);
        if (net.aiagent256.stormhold.worldgen.Finder.planned(level, from, "nonsense", 2, 1000) != null) throw new IllegalStateException("found nonsense");
        g.succeed();
    }

    private static Holding sample(GameTestHelper g) {
        BlockPos o = g.absolutePos(BlockPos.ZERO);
        Holding h = new Holding();
        h.key = "gametest@" + o.asLong();
        h.name = "Testhold";
        h.tier = 1;
        h.houseId = "testhouse";
        h.owner = Sides.house("testhouse");
        h.lordName = "Lord Test";
        h.box = new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + 15, o.getY() + 7, o.getZ() + 15);
        h.lordPos = g.absolutePos(new BlockPos(3, 2, 3));
        h.bannerPos = g.absolutePos(new BlockPos(8, 2, 8));
        h.rallyPos = g.absolutePos(new BlockPos(3, 2, 12));
        h.gatePos = h.rallyPos;
        List<BlockPos> posts = new ArrayList<>();
        for (int x = 2; x < 14; x += 3) for (int z = 2; z < 14; z += 4) posts.add(g.absolutePos(new BlockPos(x, 2, z)));
        for (Role r : Role.TROOPS) h.posts.put(r, new ArrayList<>(posts));
        h.scanned = true;
        h.villagersSpawned = true;
        h.surroundingsDone = true;
        h.fillGarrison(1.0);
        return h;
    }
}
