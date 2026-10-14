package net.aiagent256.stormhold.gametest;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.DataRegistries;
import net.aiagent256.stormhold.holding.Garrisons;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.aiagent256.stormhold.worldgen.CastleTilePiece;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
            "holdfast_b", "watchtower_a", "watchtower_b"};

    @GameTest(template = "arena")
    public static void castleDataLoads(GameTestHelper g) {
        StructureTemplateManager stm = g.getLevel().getStructureManager();
        for (String n : LAYOUTS) {
            if (DataRegistries.layout(n) == null) throw new IllegalStateException("missing layout " + n);
            if (stm.get(CastleTilePiece.tileId(n, 0, 0)).isEmpty()) throw new IllegalStateException("missing template " + n);
            if (DataRegistries.layout(n).posts.get(Role.KNIGHT) == null) throw new IllegalStateException("no knight posts in " + n);
        }
        for (String t : new String[]{"riverlands", "reach", "dorne", "stormlands"}) {
            if (DataRegistries.theme(t).isEmpty()) throw new IllegalStateException("missing theme " + t);
        }
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
        for (String id : new String[]{"watchtower_riverlands", "holdfast_dorne", "great_castle_reach"}) {
            var structure = reg.get(Stormhold.id(id));
            if (structure == null) throw new IllegalStateException("structure not registered: " + id);
            BlockPos at = g.absolutePos(BlockPos.ZERO).offset(3000 + i * 400, 0, 3000);
            net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(at);
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
            if (!level.getBlockState(h.bannerPos.below()).isSolidRender(level, h.bannerPos.below()))
                throw new IllegalStateException(id + " banner stand floats at " + h.bannerPos);
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
