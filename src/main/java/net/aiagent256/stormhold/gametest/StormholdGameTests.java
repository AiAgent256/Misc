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
