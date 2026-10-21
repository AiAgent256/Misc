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
            "watchtower_c2", "town_walled", "town_cross", "town_green", "great_city", "canal_city",
            // second revisions: new castles use these, the old ones keep their plans for repairs and half-built chunks
            "town_walled_r2", "town_cross_r2", "town_green_r2", "great_city_r2", "canal_city_r2",
            // moated castles and the chateau
            "holdfast_b_moat", "holdfast_c_moat", "great_castle_a_moat", "great_castle_c_moat", "great_castle_d", "great_castle_d2",
            // the lake castles and the timber-framed towns
            "lake_castle", "lake_castle2", "town_walled_r3", "town_cross_r3", "town_green_r3",
            // the defence rebuild: sills, roofs on the merlons, loops, three-wide walks
            "great_castle_a_r2", "great_castle_b_r2", "great_castle_c_r2", "great_castle_a2_r2", "great_castle_b2_r2",
            "great_castle_c2_r2", "holdfast_a_r2", "holdfast_b_r2", "holdfast_c_r2", "holdfast_a2_r2", "holdfast_b2_r2",
            "holdfast_c2_r2", "watchtower_a_r2", "watchtower_b_r2", "watchtower_c_r2", "watchtower_a2_r2", "watchtower_b2_r2",
            "watchtower_c2_r2", "great_city_r3", "canal_city_r3", "town_walled_r4", "town_cross_r4", "town_green_r4",
            "great_castle_d_r2", "great_castle_d2_r2", "holdfast_b_moat_r2", "holdfast_c_moat_r2", "great_castle_a_moat_r2",
            "great_castle_c_moat_r2", "lake_castle_r2", "lake_castle2_r2",
            // built to the audit from the start
            "fortified_manor", "fortified_manor2", "gatehouse_castle", "gatehouse_castle2", "hill_town"};

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
        // every town and city has its shops, its notice board and homes for sale
        for (String n : new String[]{"town_walled", "town_cross", "town_green", "great_city", "canal_city",
                "town_walled_r2", "town_cross_r2", "town_green_r2", "great_city_r2", "canal_city_r2",
                "town_walled_r3", "town_cross_r3", "town_green_r3", "town_walled_r4", "town_cross_r4", "town_green_r4",
                "great_city_r3", "canal_city_r3", "hill_town"}) {
            var lay = DataRegistries.layout(n);
            int shops = 0;
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (var e : lay.extras.entrySet()) {
                if (!e.getKey().startsWith("shop_")) continue;
                shops += e.getValue().size();
                kinds.add(e.getKey());
                if (net.aiagent256.stormhold.holding.Shops.type(e.getKey().substring(5)) == null)
                    throw new IllegalStateException(n + " has a shop of an unknown kind: " + e.getKey());
            }
            if (kinds.size() < 6) throw new IllegalStateException(n + " has only " + kinds.size() + " kinds of shop: " + kinds);
            var houses = lay.extras.get("house");
            if (houses == null || houses.size() < 3) throw new IllegalStateException(n + " sells " + (houses == null ? 0 : houses.size()) + " houses");
            if (lay.extras.get("notice_board") == null) throw new IllegalStateException(n + " has no notice board");
        }
        // every keep has its crypt: the marker the generator leaves at the founder's tomb
        for (String n : new String[]{"holdfast_a", "holdfast_b", "holdfast_c", "great_castle_a", "great_castle_b", "great_castle_c", "great_city", "canal_city",
                "great_city_r2", "canal_city_r2", "holdfast_b_moat", "great_castle_a_moat", "great_castle_d", "great_castle_d2", "lake_castle",
                "holdfast_a_r2", "holdfast_b_r2", "holdfast_c_r2", "great_castle_a_r2", "great_castle_b_r2", "great_castle_c_r2",
                "great_city_r3", "canal_city_r3", "lake_castle_r2", "gatehouse_castle"}) {
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
        // ruins are the old plans placed ruined; the temple is a landmark with its own tiles and no holding
        if (!(reg.get(Stormhold.id("ruin_north")) instanceof net.aiagent256.stormhold.worldgen.CastleStructure ruin) || !ruin.ruined())
            throw new IllegalStateException("the ruins are not registered as ruined");
        if (reg.get(Stormhold.id("holdfast_north")) instanceof net.aiagent256.stormhold.worldgen.CastleStructure hf && hf.ruined())
            throw new IllegalStateException("a whole holdfast is flagged as a ruin");
        var temple = DataRegistries.layout("temple_ruin");
        if (temple == null || !"landmark".equals(temple.kind) || stm.get(CastleTilePiece.tileId("temple_ruin", 0, 0)).isEmpty())
            throw new IllegalStateException("the temple ruin is missing or not a landmark");
        if (!(reg.get(Stormhold.id("temple_ruin_north")) instanceof net.aiagent256.stormhold.worldgen.CastleStructure))
            throw new IllegalStateException("the temple ruin structure is not registered");
        if (!(reg.get(Stormhold.id("cliff_castle_north")) instanceof net.aiagent256.stormhold.worldgen.CastleStructure cl) || !cl.cliff())
            throw new IllegalStateException("the cliff castles are not registered for cliffs");
        g.succeed();
    }

    /** Every kind of shop puts a keeper behind his counter who sells real goods, stays put, and restocks. */
    @GameTest(template = "arena")
    public static void shopsTradeAndRestock(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        Holding h = sample(g);
        List<Entity> spawned = new ArrayList<>();
        try {
            int i = 0;
            for (String kind : net.aiagent256.stormhold.holding.Shops.types().keySet()) {
                BlockPos post = g.absolutePos(new BlockPos(2 + (i % 6) * 2, 2, 2 + (i / 6) * 3));
                level.setBlock(post, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(post.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                var v = net.aiagent256.stormhold.holding.Shops.spawnKeeper(level, h, kind, post);
                if (v == null) throw new IllegalStateException("no keeper for " + kind);
                spawned.add(v);
                if (!v.isNoAi()) throw new IllegalStateException(kind + " keeper wanders");
                var offers = v.getOffers();
                if (offers.size() < 5) throw new IllegalStateException(kind + " sells only " + offers.size() + " things");
                int sells = 0, buys = 0;
                for (var o : offers) {
                    if (o.getResult().isEmpty() || o.getBaseCostA().isEmpty()) throw new IllegalStateException(kind + " has an empty offer");
                    if (o.getBaseCostA().is(net.minecraft.world.item.Items.EMERALD)) sells++;
                    if (o.getResult().is(net.minecraft.world.item.Items.EMERALD)) buys++;
                }
                if (sells == 0 || buys == 0) throw new IllegalStateException(kind + " sells " + sells + " and buys " + buys);
                if (v.getVillagerData().getLevel() != 5) throw new IllegalStateException(kind + " keeper is not a master");
                // sell him out of his first line, then a new morning
                var first = offers.get(0);
                while (!first.isOutOfStock()) first.increaseUses();
                net.aiagent256.stormhold.holding.Shops.restock(v);
                if (first.isOutOfStock()) throw new IllegalStateException(kind + " did not restock");
                if (!v.hasCustomName() || !v.getCustomName().getString().contains(net.aiagent256.stormhold.holding.Shops.type(kind).keeper()))
                    throw new IllegalStateException(kind + " keeper is called " + v.getCustomName());
                i++;
            }
            if (i < 10) throw new IllegalStateException("only " + i + " kinds of shop");
            // a town an older plan built: the post lands on the counter, so the keeper stands beside it
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int x = 2; x <= 12; x++) {
                for (int z = 9; z <= 13; z++) {
                    level.setBlock(g.absolutePos(new BlockPos(x, 1, z)), stone, 3);
                    level.setBlock(g.absolutePos(new BlockPos(x, 2, z)), air, 3);
                    level.setBlock(g.absolutePos(new BlockPos(x, 3, z)), air, 3);
                }
            }
            BlockPos onCounter = g.absolutePos(new BlockPos(4, 2, 11));
            level.setBlock(onCounter, net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);
            BlockPos at = net.aiagent256.stormhold.holding.Shops.standAt(level, onCounter);
            if (at == null || at.equals(onCounter) || at.distManhattan(onCounter) != 1)
                throw new IllegalStateException("a post on its counter stands at " + at);
            // a post in a wall with no counter near: no keeper rather than one in the street
            BlockPos inWall = g.absolutePos(new BlockPos(10, 2, 11));
            level.setBlock(inWall, stone, 3);
            if (net.aiagent256.stormhold.holding.Shops.standAt(level, inWall) != null)
                throw new IllegalStateException("a post with no counter near found a place to stand");
            BlockPos clear = g.absolutePos(new BlockPos(8, 2, 11));
            if (!clear.equals(net.aiagent256.stormhold.holding.Shops.standAt(level, clear)))
                throw new IllegalStateException("a clear post moved");
        } finally {
            for (Entity e : spawned) e.discard();
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void petsAndFriendlySoldiersSpareEachOther(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        String house = "allytest" + Math.abs(me.hashCode() % 100000);
        List<Entity> spawned = new ArrayList<>();
        try {
            var wolf = net.minecraft.world.entity.EntityType.WOLF.create(level);
            if (wolf == null) throw new IllegalStateException("no wolf");
            BlockPos wp = g.absolutePos(new BlockPos(4, 2, 4));
            wolf.moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, 0, 0);
            wolf.setTame(true);
            wolf.setOwnerUUID(me);
            level.addFreshEntity(wolf);
            spawned.add(wolf);
            GarrisonEntity theirs = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(7, 2, 4)), Sides.house(house), true);
            GarrisonEntity mine = Garrisons.spawn(level, mgr, null, Role.ARCHER, g.absolutePos(new BlockPos(4, 2, 7)), Sides.player(me), true);
            if (theirs == null || mine == null) throw new IllegalStateException("no soldiers");
            spawned.add(theirs);
            spawned.add(mine);
            net.aiagent256.stormhold.house.Standing.change(level, mgr, house, me, 200, "test");
            if (!net.aiagent256.stormhold.holding.Allies.spared(level, wolf, theirs) || !net.aiagent256.stormhold.holding.Allies.spared(level, theirs, wolf))
                throw new IllegalStateException("a friendly house's knight and my wolf fight");
            if (!net.aiagent256.stormhold.holding.Allies.spared(level, wolf, mine) || !net.aiagent256.stormhold.holding.Allies.spared(level, mine, wolf))
                throw new IllegalStateException("my own archer and my wolf fight");
            float hp = theirs.getHealth();
            theirs.hurt(level.damageSources().mobAttack(wolf), 4.0F);
            if (theirs.getHealth() < hp) throw new IllegalStateException("my wolf's bite landed on a friendly knight");
            if (theirs.getLastHurtByMob() == wolf) throw new IllegalStateException("the knight holds the bite against my wolf");
            // a house that holds me hostile is fair game
            net.aiagent256.stormhold.house.Standing.change(level, mgr, house, me, -400, "test");
            if (net.aiagent256.stormhold.holding.Allies.spared(level, wolf, theirs))
                throw new IllegalStateException("a hostile house's knight is spared my wolf");
            // a wild animal is nobody's pet
            var stray = net.minecraft.world.entity.EntityType.WOLF.create(level);
            if (stray != null && net.aiagent256.stormhold.holding.Allies.spared(level, stray, mine))
                throw new IllegalStateException("a stray is treated as a pet");
            if (stray != null) stray.discard();
        } finally {
            for (Entity e : spawned) e.discard();
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void labourMinersAndSmiths(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        UUID me = UUID.randomUUID();
        h.owner = Sides.player(me);
        List<Entity> spawned = new ArrayList<>();
        try {
            List<net.minecraft.world.entity.npc.Villager> folk = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                var v = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
                if (v == null) throw new IllegalStateException("no villager");
                BlockPos at = g.absolutePos(new BlockPos(3 + i * 2, 2, 7));
                v.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
                v.setNoAi(i == 4);                                      // a shopkeeper stands at his counter
                if (i == 4) v.getPersistentData().putString(net.aiagent256.stormhold.holding.Shops.TAG, "general");
                level.addFreshEntity(v);
                folk.add(v);
                spawned.add(v);
            }
            h.miners = 2;
            h.smiths = 1;
            net.aiagent256.stormhold.holding.Labour.tick(level, mgr, h);
            int miners = 0, smiths = 0;
            for (var v : folk) {
                String job = net.aiagent256.stormhold.holding.Labour.jobOf(v);
                if (job == null) continue;
                if (v.isNoAi()) throw new IllegalStateException("the shopkeeper was set to " + job);
                String name = v.getCustomName() == null ? "" : v.getCustomName().getString();
                if (job.equals("miner")) {
                    miners++;
                    if (v.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH || !name.endsWith("the Miner"))
                        throw new IllegalStateException("a miner is " + v.getVillagerData().getProfession() + " called " + name);
                } else {
                    smiths++;
                    if (v.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.ARMORER || !name.endsWith("the Smith"))
                        throw new IllegalStateException("a smith is " + v.getVillagerData().getProfession() + " called " + name);
                }
            }
            if (miners != 2 || smiths != 1 || h.minersAt != 2 || h.smithsAt != 1)
                throw new IllegalStateException(miners + " miners and " + smiths + " smiths set to work (" + h.minersAt + ", " + h.smithsAt + ")");
            h.miners = 0;
            net.aiagent256.stormhold.holding.Labour.tick(level, mgr, h);
            for (var v : folk)
                if ("miner".equals(net.aiagent256.stormhold.holding.Labour.jobOf(v))) throw new IllegalStateException("a miner kept on");

            // a day in the mine
            var coal = new net.minecraft.resources.ResourceLocation("minecraft", "coal");
            var iron = new net.minecraft.resources.ResourceLocation("minecraft", "iron_ingot");
            int c0 = h.stock(coal), i0 = h.stock(iron);
            net.aiagent256.stormhold.holding.Labour.mine(level, h, 2);
            if (h.stock(coal) - c0 != 12 || h.stock(iron) - i0 != 6)
                throw new IllegalStateException("two miners brought up " + (h.stock(coal) - c0) + " coal, " + (h.stock(iron) - i0) + " iron");

            // the forge: nothing without the metal, then the next level after six smith-days
            int before = net.aiagent256.stormhold.holding.Frontier.level(level, h);
            if (before < net.aiagent256.stormhold.holding.Frontier.MAX) {
                int[] cost = net.aiagent256.stormhold.holding.Labour.cost(before + 1);
                for (var k : List.of(iron, new net.minecraft.resources.ResourceLocation("minecraft", "diamond"),
                        new net.minecraft.resources.ResourceLocation("minecraft", "netherite_ingot"))) h.addStock(k, -h.stock(k));
                net.aiagent256.stormhold.holding.Labour.forge(level, mgr, h, 3);
                if (h.forgePaid || h.forgeWork != 0) throw new IllegalStateException("the smiths worked without metal");
                h.addStock(iron, cost[0]);
                h.addStock(new net.minecraft.resources.ResourceLocation("minecraft", "diamond"), cost[1]);
                h.addStock(new net.minecraft.resources.ResourceLocation("minecraft", "netherite_ingot"), cost[2]);
                net.aiagent256.stormhold.holding.Labour.forge(level, mgr, h, 3);
                if (!h.forgePaid || h.forgeWork != 3 || h.stock(iron) != 0) throw new IllegalStateException("the forge took the metal? paid "
                        + h.forgePaid + ", work " + h.forgeWork + ", iron left " + h.stock(iron));
                net.aiagent256.stormhold.holding.Labour.forge(level, mgr, h, 3);
                int after = net.aiagent256.stormhold.holding.Frontier.level(level, h);
                if (after != before + 1 || h.forged != before + 1) throw new IllegalStateException("kit level " + before + " forged to " + after);
            }
        } finally {
            for (Entity e : spawned) e.discard();
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void frontierZonesKitAndStock(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        int[][] want = {{0, 1}, {2499, 1}, {2500, 2}, {10000, 5}, {17500, 8}, {19999, 8}, {20000, 9}, {1000000, 9}};
        for (int[] w : want) {
            int z = net.aiagent256.stormhold.holding.Frontier.zoneAt(w[0]);
            if (z != w[1]) throw new IllegalStateException(w[0] + " blocks out is zone " + z + ", not " + w[1]);
        }
        double d = net.aiagent256.stormhold.holding.Frontier.distance(level, 3000, 4000, BlockPos.ZERO);
        if (Math.abs(d - 5000) > 0.01) throw new IllegalStateException("3000, 4000 is " + d + " blocks out");

        // knights: diamond at 5, netherite from 7, netherite Protection IV at the world's edge
        HoldingManager mgr = HoldingManager.get(level);
        BlockPos p = g.absolutePos(new BlockPos(4, 2, 4));
        GarrisonEntity k = Garrisons.spawn(level, mgr, null, Role.KNIGHT, p, Sides.house("testhouse"), true);
        if (k == null) throw new IllegalStateException("no knight");
        try {
            int[][] kit = {{5, 0, 4}, {7, 1, 2}, {8, 1, 3}, {9, 1, 4}};
            for (int[] c : kit) {
                net.aiagent256.stormhold.entity.Loadouts.equip(k, Role.KNIGHT, 3, c[0], net.minecraft.world.item.DyeColor.RED, null);
                var chest = k.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
                boolean nether = chest.is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE);
                int prot = net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                        net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION, chest);
                if (nether != (c[1] == 1) || prot != c[2])
                    throw new IllegalStateException("a level " + c[0] + " knight wears " + chest + " at Protection " + prot);
            }
        } finally {
            k.discard();
        }

        // wild mobs: three times the health and eight more armour at the edge, once however often it's asked
        var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        if (zombie == null) throw new IllegalStateException("no zombie");
        double base = zombie.getMaxHealth(), armour = zombie.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        net.aiagent256.stormhold.holding.Frontier.scale(zombie, 9);
        net.aiagent256.stormhold.holding.Frontier.scale(zombie, 9);
        if (Math.abs(zombie.getMaxHealth() - base * 3) > 0.01 || zombie.getHealth() < zombie.getMaxHealth() - 0.01)
            throw new IllegalStateException("an edge zombie has " + zombie.getHealth() + "/" + zombie.getMaxHealth() + " health from " + base);
        if (zombie.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) < armour + 8)
            throw new IllegalStateException("an edge zombie has armour " + zombie.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR));
        var creeper = net.minecraft.world.entity.EntityType.CREEPER.create(level);
        if (creeper == null) throw new IllegalStateException("no creeper");
        net.aiagent256.stormhold.holding.Frontier.scale(creeper, 9);
        var ct = new net.minecraft.nbt.CompoundTag();
        creeper.addAdditionalSaveData(ct);
        if (ct.getByte("ExplosionRadius") <= 3) throw new IllegalStateException("an edge creeper blows " + ct.getByte("ExplosionRadius"));
        zombie.discard();
        creeper.discard();

        // markets: the armoury sells netherite plate only out on the frontier
        var armoury = net.aiagent256.stormhold.holding.Shops.type("armoury");
        boolean home = false, edge = false;
        for (var o : net.aiagent256.stormhold.holding.Shops.offers(armoury, 1))
            if (o.getResult().is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE)) home = true;
        for (var o : net.aiagent256.stormhold.holding.Shops.offers(armoury, 9))
            if (o.getResult().is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE)) edge = true;
        if (home || !edge) throw new IllegalStateException("netherite plate in the heartland " + home + ", at the edge " + edge);
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void ruinsCrumbleAndAreRestored(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        // a tower of stone bricks, glass and a door, through the ruin processor without placing it
        var proc = new net.aiagent256.stormhold.worldgen.RuinProcessor(12345L, 101, 24);
        var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings();
        List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> in = new ArrayList<>();
        var bricks = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
        var glass = net.minecraft.world.level.block.Blocks.GLASS_PANE.defaultBlockState();
        var door = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState();
        for (int x = 0; x < 24; x++)
            for (int z = 0; z < 24; z++) {
                in.add(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(new BlockPos(x, 100, z), bricks, null));
                boolean wall = x == 0 || z == 0 || x == 23 || z == 23;
                if (!wall) continue;
                for (int y = 101; y <= 124; y++)
                    in.add(new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(new BlockPos(x, y, z),
                            y % 5 == 0 && x % 4 == 2 ? glass : x == 12 && z == 0 && y < 103 ? door : bricks, null));
            }
        List<net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> out = new ArrayList<>();
        for (var b : in) {
            var r = proc.processBlock(level, BlockPos.ZERO, BlockPos.ZERO, b, b, settings);
            if (r != null) out.add(r);
        }
        out = proc.finalizeProcessing(level, BlockPos.ZERO, BlockPos.ZERO, in, out, settings);
        int walls = 0, high = 0, moss = 0, vines = 0, plants = 0;
        for (var b : out) {
            var st = b.state();
            if (st.is(net.minecraft.world.level.block.Blocks.GLASS_PANE) || st.is(net.minecraft.tags.BlockTags.DOORS))
                throw new IllegalStateException("a ruin with " + st + " in it");
            if (st.is(net.minecraft.world.level.block.Blocks.VINE)) vines++;
            else if (st.is(net.minecraft.world.level.block.Blocks.GRASS) || st.is(net.minecraft.world.level.block.Blocks.FERN)
                    || st.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET) || st.is(net.minecraft.world.level.block.Blocks.DANDELION)
                    || st.is(net.minecraft.world.level.block.Blocks.OXEYE_DAISY)) plants++;
            else if (b.pos().getY() > 100) {
                walls++;
                if (b.pos().getY() > 118) high++;
                if (st.is(net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS)) moss++;
            }
        }
        int whole = 0;
        for (var b : in) if (b.pos().getY() > 100) whole++;
        if (walls >= whole * 0.8 || walls < whole * 0.15 || high >= walls / 4 || moss == 0 || vines == 0 || plants == 0)
            throw new IllegalStateException("the ruin: " + walls + " of " + whole + " wall blocks stand, " + high + " high, " + moss + " mossy, "
                    + vines + " vines, " + plants + " plants");
        // a holding found in ruins: brigands on the ground, nobody on the walls; restored, a full castle
        Holding h = sample(g);
        var lay = DataRegistries.layout("watchtower_a");
        BlockPos o = g.absolutePos(BlockPos.ZERO);
        net.aiagent256.stormhold.holding.Detector.applyLayout(h, lay, o);
        h.layout = lay.name;
        net.aiagent256.stormhold.holding.Ruins.found(h, lay);
        if (!h.ruined || !h.owner.equals(Sides.OUTLAWS) || h.lordAlive || h.capacity(Role.ARCHER) != 0
                || !h.posts.getOrDefault(Role.ARCHER, List.of()).isEmpty() || h.count(Role.KNIGHT) < 2)
            throw new IllegalStateException("found: owner " + h.owner + " archers " + h.capacity(Role.ARCHER) + " knights " + h.count(Role.KNIGHT));
        if (!h.kindName().startsWith("Ruined")) throw new IllegalStateException("called " + h.kindName());
        HoldingManager mgr = HoldingManager.get(level);
        h.owner = Sides.player(UUID.randomUUID());
        net.aiagent256.stormhold.holding.Ruins.restore(level, mgr, h);
        if (h.ruined || h.posts.getOrDefault(Role.ARCHER, List.of()).isEmpty() || h.capacity(Role.ARCHER) == 0 || h.surroundingsDone)
            throw new IllegalStateException("restored: archer posts " + h.posts.getOrDefault(Role.ARCHER, List.of()).size());
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void trebuchetAimsAndStands(GameTestHelper g) {
        // the throw: a falling block's own motion, tick by tick, should come down at the wall it was aimed at
        for (double[] t : new double[][]{{40, 6}, {80, -4}, {120, 12}}) {
            var from = new net.minecraft.world.phys.Vec3(0.5, 70.5, 0.5);
            var to = new net.minecraft.world.phys.Vec3(0.5 + t[0], 70.5 + t[1], 0.5 + t[0] * 0.3);
            var v = net.aiagent256.stormhold.holding.Engines.velocity(from, to);
            double x = from.x, y = from.y, z = from.z, vx = v.x, vy = v.y, vz = v.z, best = Double.MAX_VALUE;
            for (int k = 0; k < 200; k++) {
                vy -= 0.04;
                x += vx;
                y += vy;
                z += vz;
                vx *= 0.98;
                vy *= 0.98;
                vz *= 0.98;
                best = Math.min(best, Math.sqrt((x - to.x) * (x - to.x) + (y - to.y) * (y - to.y) + (z - to.z) * (z - to.z)));
            }
            if (best > 2.5) throw new IllegalStateException("a throw at " + t[0] + " blocks misses by " + best);
        }
        // the frame goes up where it's put
        ServerLevel level = g.getLevel();
        BlockPos a = g.absolutePos(BlockPos.ZERO);
        BlockPos base = new BlockPos(a.getX() + 8, 301, a.getZ() + 8);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                level.setBlock(new BlockPos(a.getX() + x, 300, a.getZ() + z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                for (int y = 301; y < 312; y++)
                    level.setBlock(new BlockPos(a.getX() + x, y, a.getZ() + z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        net.aiagent256.stormhold.holding.Engines.build(level, base, net.minecraft.core.Direction.EAST);
        int logs = 0, weight = 0;
        for (int x = -5; x <= 5; x++)
            for (int z = -3; z <= 3; z++)
                for (int y = 0; y <= 8; y++) {
                    var st = level.getBlockState(base.offset(x, y, z));
                    if (st.is(net.minecraft.tags.BlockTags.LOGS)) logs++;
                    if (st.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)) weight++;
                }
        if (logs < 25 || weight != 12) throw new IllegalStateException("the frame: " + logs + " logs, " + weight + " counterweight");
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void veteransEarnTheirRanks(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        GarrisonEntity man = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(4, 3, 4)), Sides.player(me), false);
        if (man == null) throw new IllegalStateException("no man");
        try {
            man.setOwner(me);
            man.setBaseName("Wat Tester");
            double hp = man.getMaxHealth();
            double dmg = man.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
            for (int i = 0; i < 4; i++) man.scored();
            if (!man.baseName().equals("Wat Tester")) throw new IllegalStateException("ranked after 4: " + man.baseName());
            man.scored();
            if (!man.baseName().equals("Veteran Wat Tester") || man.getMaxHealth() != hp + 4
                    || man.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != dmg + 1)
                throw new IllegalStateException("veteran: " + man.baseName() + " hp " + man.getMaxHealth() + " from " + hp);
            for (int i = 0; i < 10; i++) man.scored();
            if (!man.baseName().startsWith("Champion Wat Tester ") || man.getMaxHealth() != hp + 8 || man.kills() != 15)
                throw new IllegalStateException("champion: " + man.baseName() + " hp " + man.getMaxHealth());
            // the rank survives the save
            var tag = new net.minecraft.nbt.CompoundTag();
            man.saveWithoutId(tag);
            if (tag.getInt("Kills") != 15 || !tag.getString("PlainName").equals("Wat Tester")) throw new IllegalStateException("not saved: " + tag);
        } finally {
            man.discard();
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void roadTollsAndBrigands(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        Holding h = sample(g);
        h.owner = Sides.player(me);
        Holding o = new Holding();
        o.name = "Tollhold";
        o.owner = h.owner;
        BlockPos c = h.center();
        o.box = new BoundingBox(c.getX() + 595, c.getY() - 5, c.getZ() - 5, c.getX() + 605, c.getY() + 5, c.getZ() + 5);
        h.gatePos = c;
        h.roadsTo.add(o.id);
        mgr.holdings.put(h.id, h);
        mgr.holdings.put(o.id, o);
        var em = net.aiagent256.stormhold.holding.Rewards.EMERALD;
        try {
            int toll = net.aiagent256.stormhold.holding.Tolls.toll(c, o.center());
            if (toll != 4) throw new IllegalStateException("a 600-block road tolls " + toll);
            int was = h.stock(em);
            var line = net.aiagent256.stormhold.holding.Tolls.collect(level, mgr, me, List.of(h, o));
            if (line == null || h.stock(em) != was + toll) throw new IllegalStateException("tolls: " + (line == null ? "none" : line.getString()));
            // brigands on the road: no tolls while they sit there, a bonus when they're gone
            var camp = new net.aiagent256.stormhold.holding.Campaign();
            camp.kind = net.aiagent256.stormhold.holding.Campaign.Kind.CAMP;
            camp.origin = h.id;
            mgr.campaigns.add(camp);
            h.roadCamps.put(o.id, camp.id);
            was = h.stock(em);
            net.aiagent256.stormhold.holding.Tolls.collect(level, mgr, me, List.of(h, o));
            if (h.stock(em) != was) throw new IllegalStateException("tolls paid past a brigand camp");
            camp.stage = net.aiagent256.stormhold.holding.Campaign.Stage.DONE;
            was = h.stock(em);
            net.aiagent256.stormhold.holding.Tolls.collect(level, mgr, me, List.of(h, o));
            if (h.stock(em) != was + toll + net.aiagent256.stormhold.holding.Tolls.CLEARED_BONUS)
                throw new IllegalStateException("the road cleared: " + (h.stock(em) - was));
        } finally {
            mgr.holdings.remove(h.id);
            mgr.holdings.remove(o.id);
            mgr.campaigns.removeIf(x -> h.id.equals(x.origin));
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void councilSeatsPayAndHelp(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var mock = g.makeMockPlayer();
        UUID me = mock.getUUID();
        Holding h = sample(g);
        h.owner = Sides.player(me);
        h.mode = Holding.Mode.DEFEND;
        mgr.holdings.put(h.id, h);
        try {
            int cap = h.capacity(Role.KNIGHT), rent = net.aiagent256.stormhold.holding.Rewards.rent(level, h);
            var council = mgr.councils.computeIfAbsent(me, k -> new java.util.EnumMap<>(net.aiagent256.stormhold.holding.Council.Seat.class));
            council.put(net.aiagent256.stormhold.holding.Council.Seat.MARSHAL, "Ser Test");
            council.put(net.aiagent256.stormhold.holding.Council.Seat.TREASURER, "Master Coin");
            // the record and the live view agree once the record is read back
            net.aiagent256.stormhold.holding.Council.load(net.aiagent256.stormhold.holding.Council.save(mgr.councils), mgr.councils);
            if (!net.aiagent256.stormhold.holding.Council.has(me, net.aiagent256.stormhold.holding.Council.Seat.MARSHAL))
                throw new IllegalStateException("the marshal isn't seated");
            if (h.capacity(Role.KNIGHT) != (int) Math.round(cap * 1.15) && h.capacity(Role.KNIGHT) <= cap)
                throw new IllegalStateException("marshal: knights " + cap + " then " + h.capacity(Role.KNIGHT));
            if (net.aiagent256.stormhold.holding.Rewards.rent(level, h) != (int) Math.round(rent * 1.2))
                throw new IllegalStateException("treasurer: rent " + rent + " then " + net.aiagent256.stormhold.holding.Rewards.rent(level, h));
            // wages: 6 + 4 from the stores; short of them, the officers go home
            var em = net.aiagent256.stormhold.holding.Rewards.EMERALD;
            h.addStock(em, 12 - h.stock(em));
            var line = net.aiagent256.stormhold.holding.Council.wages(level, me, List.of(h));
            if (line == null || h.stock(em) != 2 || mgr.councils.get(me).size() != 2) throw new IllegalStateException("wages: " + h.stock(em) + " left");
            net.aiagent256.stormhold.holding.Council.wages(level, me, List.of(h));
            if (!mgr.councils.get(me).isEmpty() || net.aiagent256.stormhold.holding.Council.has(me, net.aiagent256.stormhold.holding.Council.Seat.MARSHAL))
                throw new IllegalStateException("unpaid officers stayed: " + mgr.councils.get(me));
        } finally {
            mgr.holdings.remove(h.id);
            mgr.councils.remove(me);
            net.aiagent256.stormhold.holding.Council.forget(me);
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void vassalsHoldPayHelpAndRevolt(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        UUID me = player.getUUID();
        Holding h = sample(g);
        h.owner = Sides.player(me);
        h.lordAlive = false;
        h.mode = Holding.Mode.DEFEND;
        h.add(Role.KNIGHT, 6);
        Holding other = sample(g);
        other.id = UUID.randomUUID();
        other.key = h.key + "/other";
        other.owner = h.owner;
        other.name = "Otherhold";
        mgr.holdings.put(h.id, h);
        mgr.holdings.put(other.id, other);
        try {
            int rentBefore = net.aiagent256.stormhold.holding.Rewards.rent(level, h);
            int knights = h.count(Role.KNIGHT);
            if (!net.aiagent256.stormhold.holding.Vassals.grant(level, mgr, h, player)) throw new IllegalStateException("no grant");
            if (!net.aiagent256.stormhold.holding.Vassals.is(h) || !h.lordAlive || h.count(Role.KNIGHT) != knights - 1 || h.loyalty != 70)
                throw new IllegalStateException("the grant: " + h.vassal + " alive " + h.lordAlive + " knights " + h.count(Role.KNIGHT) + " loyalty " + h.loyalty);
            int rentAfter = net.aiagent256.stormhold.holding.Rewards.rent(level, h);
            if (rentAfter != (int) Math.round(rentBefore * net.aiagent256.stormhold.holding.Vassals.share(h)) || rentAfter >= rentBefore)
                throw new IllegalStateException("rents " + rentBefore + " then " + rentAfter);
            // a gift: from the pack first
            player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 40));
            net.aiagent256.stormhold.holding.Vassals.gift(level, mgr, h, player);
            if (h.loyalty != 70 + net.aiagent256.stormhold.holding.Vassals.GIFT_LOYALTY
                    || player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) != 40 - net.aiagent256.stormhold.holding.Vassals.GIFT)
                throw new IllegalStateException("the gift: loyalty " + h.loyalty);
            // his knights ride to the lord's other place when it's attacked
            int there = other.count(Role.KNIGHT), here = h.count(Role.KNIGHT);
            net.aiagent256.stormhold.holding.Vassals.help(level, mgr, other);
            int sent = Math.min(2 + h.tier, here - 2);
            if (other.count(Role.KNIGHT) != there + sent || h.count(Role.KNIGHT) != here - sent)
                throw new IllegalStateException("help: " + other.count(Role.KNIGHT) + " from " + there + ", sent " + sent);
            // pushed too far, he breaks faith
            net.aiagent256.stormhold.holding.Vassals.slight(level, mgr, h, 200, "a test");
            if (net.aiagent256.stormhold.holding.Vassals.is(h) || !h.isHouseOwned() || !h.lordAlive || !h.houseId.startsWith("rebel_"))
                throw new IllegalStateException("no revolt: owner " + h.owner + " vassal " + h.vassal);
        } finally {
            mgr.holdings.remove(h.id);
            mgr.holdings.remove(other.id);
            mgr.houses.keySet().removeIf(k -> k.startsWith("rebel_"));
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void spoilsRentsAndPurses(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        UUID lord = UUID.randomUUID();
        h.owner = Sides.player(lord);
        h.warTablePos = g.absolutePos(new BlockPos(5, 3, 5));   // the arena's floor is the second layer: y 2 is still stone
        level.setBlock(h.warTablePos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        // the takings: plain goods and a sword with an enchantment both go in the chest the garrison sets up
        BlockPos at = h.warTablePos.above();
        var flesh = new net.minecraft.world.entity.item.ItemEntity(level, at.getX(), at.getY(), at.getZ(),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ROTTEN_FLESH, 3));
        var blade = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        blade.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 1);
        var sword = new net.minecraft.world.entity.item.ItemEntity(level, at.getX(), at.getY(), at.getZ(), blade);
        List<net.minecraft.world.entity.item.ItemEntity> drops = new ArrayList<>(List.of(flesh, sword));
        BlockPos set = net.aiagent256.stormhold.holding.Spoils.setUp(level, h);
        if (set == null || net.aiagent256.stormhold.holding.Steward.container(level, set) == null)
            throw new IllegalStateException("no spoils chest beside the table: set " + set + ", around it "
                    + level.getBlockState(h.warTablePos.offset(-1, 0, -1)) + " over " + level.getBlockState(h.warTablePos.offset(-1, -1, -1)));
        int moved = net.aiagent256.stormhold.holding.Spoils.take(level, mgr, h, drops.iterator());
        if (moved != 4 || !drops.isEmpty()) throw new IllegalStateException(moved + " carried, " + drops.size() + " left on the ground");
        BlockPos chestAt = net.aiagent256.stormhold.holding.Steward.spoilsChest(level, h);
        if (chestAt == null) throw new IllegalStateException("no spoils chest set up");
        var chest = net.aiagent256.stormhold.holding.Steward.container(level, chestAt);
        if (count(chest, net.minecraft.world.item.Items.ROTTEN_FLESH) != 3 || count(chest, net.minecraft.world.item.Items.IRON_SWORD) != 1)
            throw new IllegalStateException("the spoils chest holds the wrong things");
        // the rents: every place pays its lord at dawn; away from the game it goes to the stores
        int rent = net.aiagent256.stormhold.holding.Rewards.rent(level, h);
        if (rent < 3) throw new IllegalStateException("a rent of " + rent);
        var em = net.aiagent256.stormhold.holding.Rewards.EMERALD;
        int was = h.stock(em);
        var line = net.aiagent256.stormhold.holding.Rewards.rents(level, mgr, lord, List.of(h));
        if (line == null || !line.getString().startsWith("Rents") || h.stock(em) != was + rent)
            throw new IllegalStateException("rents: " + (line == null ? "none" : line.getString()) + ", stores " + h.stock(em) + " from " + was);
        // what winning pays
        int tower = h.tier;
        h.tier = 3;
        int great = net.aiagent256.stormhold.holding.Rewards.treasury(level, h);
        h.tier = 1;
        int small = net.aiagent256.stormhold.holding.Rewards.treasury(level, h);
        h.tier = tower;
        if (great <= small || small < 15) throw new IllegalStateException("treasuries: " + small + " and " + great);
        var r = net.minecraft.util.RandomSource.create(7);
        if (net.aiagent256.stormhold.holding.Rewards.purse(r, Role.LORD, Sides.house("testhouse"), 1) < 12
                || net.aiagent256.stormhold.holding.Rewards.purse(r, Role.KNIGHT, Sides.OUTLAWS, 9) > 1)
            throw new IllegalStateException("purses");
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void scoutsRideOutAndReport(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        Holding h = sample(g);
        h.owner = Sides.player(player.getUUID());
        int men = h.troops();
        if (men < 4) throw new IllegalStateException("only " + men + " men to scout with");
        BlockPos home = h.center();
        Holding other = new Holding();
        other.name = "Scoutedhold";
        other.owner = Sides.house("testhouse");
        other.houseId = "testhouse";
        other.tier = 2;
        other.box = new BoundingBox(home.getX() + 590, home.getY() - 5, home.getZ() + 190, home.getX() + 610, home.getY() + 5, home.getZ() + 210);
        other.add(Role.KNIGHT, 12);
        mgr.holdings.put(h.id, h);
        mgr.holdings.put(other.id, other);
        net.aiagent256.stormhold.holding.Campaign c = null;
        try {
            // too close, then a real ride
            if (net.aiagent256.stormhold.holding.Scouts.send(level, mgr, h, player, home.offset(20, 0, 0)) != null)
                throw new IllegalStateException("scouts sent 20 blocks");
            c = net.aiagent256.stormhold.holding.Scouts.send(level, mgr, h, player, home.offset(600, 0, 0));
            if (c == null || c.total() != 2 || h.troops() != men - 2) throw new IllegalStateException("the riders: " + (c == null ? "none" : c.total()));
            if (net.aiagent256.stormhold.holding.Scouts.send(level, mgr, h, player, home.offset(0, 0, 600)) != null)
                throw new IllegalStateException("a second party while the first is out");
            double risk = net.aiagent256.stormhold.holding.Scouts.risk(level, mgr, h, c);
            if (risk < 0 || risk > 0.6) throw new IllegalStateException("risk " + risk);
            // the ride out takes its time
            net.aiagent256.stormhold.holding.Scouts.tick(level, mgr, c);
            if (c.stage != net.aiagent256.stormhold.holding.Campaign.Stage.MARCH || c.progress <= 0 || c.progress >= 0.1)
                throw new IllegalStateException("after a second: " + c.stage + " " + c.progress);
            // home, with the report
            c.stage = net.aiagent256.stormhold.holding.Campaign.Stage.RETURN;
            var lines = net.aiagent256.stormhold.holding.Scouts.report(level, mgr, h, c);
            boolean named = false;
            for (var l : lines) if (l.getString().contains("Scoutedhold") && l.getString().contains("12 men")) named = true;
            if (!named) throw new IllegalStateException("the report: " + lines.stream().map(net.minecraft.network.chat.Component::getString).toList());
            if (!other.greeted.contains(player.getUUID())) throw new IllegalStateException("the scouted castle isn't on the realm page");
            net.aiagent256.stormhold.holding.Scouts.home(level, mgr, h, c);
            if (h.troops() != men || c.stage != net.aiagent256.stormhold.holding.Campaign.Stage.DONE)
                throw new IllegalStateException("back home: " + h.troops() + " of " + men + ", " + c.stage);
            // the map: drawn, and up on a wall of the hall (or waiting for one)
            var frames = level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, net.minecraft.world.phys.AABB.of(h.box).inflate(2),
                    f -> f.getItem().is(net.minecraft.world.item.Items.FILLED_MAP));
            if (frames.isEmpty() && h.pendingMaps.isEmpty()) throw new IllegalStateException("no map");
            var drawn = frames.isEmpty() ? h.pendingMaps.get(0) : frames.get(0).getItem();
            if (!drawn.getHoverName().getString().startsWith("Scouts' map") || !drawn.getOrCreateTag().contains("Decorations"))
                throw new IllegalStateException("the map: " + drawn.getHoverName().getString() + " " + drawn.getTag());
            for (var f : frames) f.discard();
        } finally {
            if (c != null) mgr.campaigns.remove(c);
            mgr.holdings.remove(h.id);
            mgr.holdings.remove(other.id);
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void scoutsMapRoomHangsMapsInPlace(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        BlockPos o = g.absolutePos(BlockPos.ZERO);
        h.box = new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + 15, o.getY() + 12, o.getZ() + 15);
        h.warTablePos = g.absolutePos(new BlockPos(7, 2, 4));
        // a bare wall along the north side of the room, eleven across and seven high, the floor in front of it
        for (int x = 2; x <= 12; x++) for (int y = 2; y <= 8; y++) g.setBlock(new BlockPos(x, y, 1), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
        mgr.holdings.put(h.id, h);
        try {
            BlockPos c = h.center();
            var east = net.minecraft.world.item.MapItem.create(level, c.getX() + net.aiagent256.stormhold.holding.MapRoom.CELL, c.getZ(), (byte) 3, true, true);
            h.pendingMaps.add(east);
            net.aiagent256.stormhold.holding.Scouts.hangPending(level, mgr, h);
            if (h.mapRoom == null) throw new IllegalStateException("no map room on an eleven-by-seven bare wall");
            if (h.mapRoom[4] != 9 || h.mapRoom[5] != 5 || h.mapRoom[3] != net.minecraft.core.Direction.SOUTH.get3DDataValue())
                throw new IllegalStateException("map room " + java.util.Arrays.toString(h.mapRoom));
            if (!h.pendingMaps.isEmpty()) throw new IllegalStateException("the map is still waiting");
            BlockPos home = net.aiagent256.stormhold.holding.MapRoom.frameAt(h, 4, 2), next = net.aiagent256.stormhold.holding.MapRoom.frameAt(h, 5, 2);
            if (!next.equals(home.east())) throw new IllegalStateException("east of home is " + next + ", home " + home);
            if (!level.getBlockState(net.aiagent256.stormhold.holding.MapRoom.frameAt(h, 4, 4).below()).is(net.minecraft.world.level.block.Blocks.CARTOGRAPHY_TABLE))
                throw new IllegalStateException("no cartography table at the foot of the wall");
            java.util.function.Function<BlockPos, List<net.minecraft.world.entity.decoration.ItemFrame>> at = p -> level.getEntitiesOfClass(
                    net.minecraft.world.entity.decoration.ItemFrame.class, new net.minecraft.world.phys.AABB(p));
            var homeFrames = at.apply(home);
            var eastFrames = at.apply(next);
            if (homeFrames.size() != 1 || !homeFrames.get(0).getItem().getHoverName().getString().contains("Testhold"))
                throw new IllegalStateException("home square: " + homeFrames.stream().map(f -> f.getItem().getHoverName().getString()).toList());
            var eastData = eastFrames.isEmpty() ? null : net.minecraft.world.item.MapItem.getSavedData(eastFrames.get(0).getItem(), level);
            if (eastFrames.size() != 1 || eastData == null
                    || net.aiagent256.stormhold.holding.MapRoom.cell(eastData.centerX) != net.aiagent256.stormhold.holding.MapRoom.cell(c.getX()) + 1)
                throw new IllegalStateException("east square: " + eastFrames.size() + " frames");
            // ridden again: the newer map takes the same frame
            h.pendingMaps.add(net.minecraft.world.item.MapItem.create(level, c.getX() + net.aiagent256.stormhold.holding.MapRoom.CELL, c.getZ(), (byte) 3, true, true));
            net.aiagent256.stormhold.holding.Scouts.hangPending(level, mgr, h);
            if (at.apply(next).size() != 1 || !h.pendingMaps.isEmpty()) throw new IllegalStateException("a second map of the east square made "
                    + at.apply(next).size() + " frames");
            // a square past the wall's edge goes on another bare wall, never into the room's empty frames
            h.pendingMaps.add(net.minecraft.world.item.MapItem.create(level, c.getX() + 9 * net.aiagent256.stormhold.holding.MapRoom.CELL, c.getZ(), (byte) 3, true, true));
            net.aiagent256.stormhold.holding.Scouts.hangPending(level, mgr, h);
            for (var f : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, net.minecraft.world.phys.AABB.of(h.box).inflate(2)))
                if (net.aiagent256.stormhold.holding.MapRoom.onWall(h, f.blockPosition()) && !f.blockPosition().equals(home) && !f.blockPosition().equals(next))
                    throw new IllegalStateException("a far map hung in the room's frame at " + f.blockPosition());
        } finally {
            for (var f : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, net.minecraft.world.phys.AABB.of(h.box).inflate(2))) f.discard();
            mgr.holdings.remove(h.id);
        }
        g.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 200, batch = "dupes")
    public static void aSecondLordStandsDown(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        h.lordAlive = true;
        mgr.holdings.put(h.id, h);
        var first = net.aiagent256.stormhold.holding.Garrisons.spawn(level, mgr, h, Role.LORD, h.lordPos, h.owner, true);
        var second = net.aiagent256.stormhold.holding.Garrisons.spawn(level, mgr, h, Role.LORD, h.lordPos.east(3), h.owner, true);
        if (first == null || second == null) throw new IllegalStateException("no lords");
        h.live.remove(second.getUUID());          // a lord nobody counts: the double a castle used to raise as you rode by
        // and the counted one out of sight is not gone: nobody raises a third for him
        if (net.aiagent256.stormhold.holding.Garrisons.gone(level, first.getUUID())) throw new IllegalStateException("a live lord taken for gone");
        g.succeedWhen(() -> {
            if (!second.isRemoved()) throw new net.minecraft.gametest.framework.GameTestAssertException("the uncounted lord still stands");
            if (first.isRemoved()) throw new net.minecraft.gametest.framework.GameTestAssertException("the counted lord stood down too");
            first.discard();
            mgr.holdings.remove(h.id);
        });
    }

    /** A chest at {@code at} with a standing sign on it reading {@code lines}. */
    private static net.minecraft.world.Container signedChest(GameTestHelper g, BlockPos at, String... lines) {
        ServerLevel level = g.getLevel();
        BlockPos p = g.absolutePos(at);
        level.setBlock(p, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        level.setBlock(p.above(), net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (!(level.getBlockEntity(p.above()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign))
            throw new IllegalStateException("no sign at " + p.above());
        var text = new net.minecraft.world.level.block.entity.SignText();
        for (int i = 0; i < lines.length && i < 4; i++) text = text.setMessage(i, net.minecraft.network.chat.Component.literal(lines[i]));
        sign.setText(text, true);
        return (net.minecraft.world.Container) level.getBlockEntity(p);
    }

    @GameTest(template = "arena")
    public static void stewardKeepsTheChestsInOrder(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        Holding h = sample(g);
        h.owner = Sides.player(player.getUUID());
        h.tidy = true;
        mgr.holdings.put(h.id, h);
        var IRON = net.minecraft.world.item.Items.IRON_INGOT;
        var BREAD = net.minecraft.world.item.Items.BREAD;
        try {
            g.setBlock(new BlockPos(3, 2, 3), net.minecraft.world.level.block.Blocks.CHEST);
            g.setBlock(new BlockPos(6, 2, 3), net.minecraft.world.level.block.Blocks.CHEST);
            var a = (net.minecraft.world.Container) level.getBlockEntity(g.absolutePos(new BlockPos(3, 2, 3)));
            var b = (net.minecraft.world.Container) level.getBlockEntity(g.absolutePos(new BlockPos(6, 2, 3)));
            a.setItem(5, new net.minecraft.world.item.ItemStack(IRON, 10));
            a.setItem(9, new net.minecraft.world.item.ItemStack(BREAD, 5));
            b.setItem(2, new net.minecraft.world.item.ItemStack(IRON, 12));
            b.setItem(20, new net.minecraft.world.item.ItemStack(IRON, 8));
            var spoils = signedChest(g, new BlockPos(9, 2, 3), "Spoils");
            spoils.setItem(0, new net.minecraft.world.item.ItemStack(BREAD, 3));
            spoils.setItem(1, new net.minecraft.world.item.ItemStack(IRON, 2));
            var mine = signedChest(g, new BlockPos(12, 2, 3), "mine");
            mine.setItem(4, new net.minecraft.world.item.ItemStack(IRON, 7));
            int moved = net.aiagent256.stormhold.holding.Tidy.tick(level, mgr, h);
            // iron home is B (20 there), bread's is A (5 there); the spoils go home too; the signed chest isn't touched
            if (count(b, IRON) != 32 || count(a, IRON) != 0) throw new IllegalStateException("iron: A " + count(a, IRON) + ", B " + count(b, IRON));
            if (count(a, BREAD) != 8 || count(b, BREAD) != 0) throw new IllegalStateException("bread: A " + count(a, BREAD) + ", B " + count(b, BREAD));
            if (count(spoils, IRON) + count(spoils, BREAD) != 0) throw new IllegalStateException("the spoils chest still holds things");
            if (count(mine, IRON) != 7 || !mine.getItem(4).is(IRON)) throw new IllegalStateException("the signed chest was touched");
            // stacked and sorted: B's iron in one stack in the first slot
            if (!b.getItem(0).is(IRON) || b.getItem(0).getCount() != 32 || !b.getItem(1).isEmpty())
                throw new IllegalStateException("B not stacked: " + b.getItem(0) + ", " + b.getItem(1));
            if (moved != 15) throw new IllegalStateException("moved " + moved + ", not 15");
            if (net.aiagent256.stormhold.holding.Tidy.tick(level, mgr, h) != 0) throw new IllegalStateException("a second pass moved things");
        } finally {
            mgr.holdings.remove(h.id);
        }
        g.succeed();
    }

    private static int count(net.minecraft.world.Container c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    @GameTest(template = "arena")
    public static void stewardSellsStoresKeepsAndBuys(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        // the signs
        var sell = net.aiagent256.stormhold.holding.Steward.parse(new String[]{"sell", "when full", "", ""});
        if (sell == null || !sell.kind().equals("sell") || !sell.whenFull()) throw new IllegalStateException("sell when full read as " + sell);
        var keep = net.aiagent256.stormhold.holding.Steward.parse(new String[]{"keep", "64 bread", "arrows", "iron ingots 12"});
        if (keep == null || keep.want().getOrDefault(net.minecraft.world.item.Items.BREAD, 0) != 64
                || keep.want().getOrDefault(net.minecraft.world.item.Items.ARROW, 0) != 64
                || keep.want().getOrDefault(net.minecraft.world.item.Items.IRON_INGOT, 0) != 12)
            throw new IllegalStateException("keep read as " + keep);
        var send = net.aiagent256.stormhold.holding.Steward.parse(new String[]{"send to wenton", "over 32", "", ""});
        if (send == null || !send.place().equals("wenton") || send.over() != 32) throw new IllegalStateException("send read as " + send);
        if (net.aiagent256.stormhold.holding.Steward.parse(new String[]{"general", "store", "", ""}) != null)
            throw new IllegalStateException("a shop's sign read as an order");
        // the chests
        Holding h = sample(g);
        h.owner = Sides.player(UUID.randomUUID());
        var prices = net.aiagent256.stormhold.holding.Steward.sellPrices();
        var buys = net.aiagent256.stormhold.holding.Steward.buyPrices();
        if (prices.size() < 10 || buys.size() < 20) throw new IllegalStateException(prices.size() + " things sell, " + buys.size() + " buy");
        var wheat = prices.containsKey(net.minecraft.world.item.Items.WHEAT) ? net.minecraft.world.item.Items.WHEAT : prices.keySet().iterator().next();
        int[] wp = prices.get(wheat);
        var c1 = signedChest(g, new BlockPos(3, 2, 3), "Sell");
        c1.setItem(0, new net.minecraft.world.item.ItemStack(wheat, Math.min(64, wp[0] * 2 + 1)));
        int lots = Math.min(64, wp[0] * 2 + 1) / wp[0];
        var c2 = signedChest(g, new BlockPos(7, 2, 3), "Stores");
        c2.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
        var sword = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        sword.enchant(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS, 2);
        c2.setItem(1, sword);
        var c3 = signedChest(g, new BlockPos(11, 2, 3), "Keep", "16 bread");
        var bread = new net.minecraft.resources.ResourceLocation("minecraft", "bread");
        h.addStock(bread, 40 - h.stock(bread));
        var torch = buys.containsKey(net.minecraft.world.item.Items.TORCH) ? net.minecraft.world.item.Items.TORCH : buys.keySet().iterator().next();
        int[] tp = buys.get(torch);
        String torchName = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(torch).getPath().replace('_', ' ');
        var c4 = signedChest(g, new BlockPos(3, 2, 9), "Buy", (tp[0] * 2) + " " + torchName);
        var em = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
        h.addStock(em, tp[1] * 2 + 5 - h.stock(em));
        var cobble = new net.minecraft.resources.ResourceLocation("minecraft", "cobblestone");
        int cobbleWas = h.stock(cobble);
        net.aiagent256.stormhold.holding.Steward.tick(level, mgr, h);
        if (count(c1, wheat) != Math.min(64, wp[0] * 2 + 1) - lots * wp[0] || count(c1, net.minecraft.world.item.Items.EMERALD) != lots * wp[1])
            throw new IllegalStateException("sold: " + count(c1, wheat) + " " + wheat + " left, " + count(c1, net.minecraft.world.item.Items.EMERALD) + " emeralds");
        if (h.stock(cobble) != cobbleWas + 10 || count(c2, net.minecraft.world.item.Items.IRON_SWORD) != 1)
            throw new IllegalStateException("stored: " + h.stock(cobble) + " cobble, sword " + count(c2, net.minecraft.world.item.Items.IRON_SWORD));
        if (count(c3, net.minecraft.world.item.Items.BREAD) != 16 || h.stock(bread) != 24)
            throw new IllegalStateException("kept: " + count(c3, net.minecraft.world.item.Items.BREAD) + " bread in the chest, " + h.stock(bread) + " in the stores");
        if (count(c4, torch) != tp[0] * 2 || h.stock(em) != 5)
            throw new IllegalStateException("bought: " + count(c4, torch) + " " + torch + ", " + h.stock(em) + " emeralds left");
        var report = net.aiagent256.stormhold.holding.Steward.report(h);
        if (report == null || !report.getString().contains("sold")) throw new IllegalStateException("the steward's account: " + report);
        // a second look changes nothing: the keep chest is full up, the sell chest has only its odd lot
        net.aiagent256.stormhold.holding.Steward.tick(level, mgr, h);
        if (count(c3, net.minecraft.world.item.Items.BREAD) != 16 || h.stock(bread) != 24) throw new IllegalStateException("kept twice");
        g.succeed();
    }

    /** A clean stretch of sky over the arena's own footprint: the arenas lie underground and the builder reads the heightmap. */
    private static BlockPos sky(GameTestHelper g, net.minecraft.world.level.block.state.BlockState floor) {
        ServerLevel level = g.getLevel();
        BlockPos a = g.absolutePos(BlockPos.ZERO);
        BlockPos o = new BlockPos(a.getX(), 300, a.getZ());
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                level.setBlock(o.offset(x, 1, z), floor, 3);
                for (int y = 2; y < 16; y++) level.setBlock(o.offset(x, y, z), air, 3);
            }
        return o;
    }

    @GameTest(template = "arena", timeoutTicks = 300)
    public static void builderLightsTheCastle(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        BlockPos o = sky(g, stone);
        // a shut stone room in one corner: only a torch inside lights it
        for (int x = 10; x <= 15; x++)
            for (int z = 10; z <= 15; z++)
                for (int y = 2; y <= 5; y++) {
                    boolean shell = y == 5 || x == 10 || x == 15 || z == 10 || z == 15;
                    if (shell) level.setBlock(o.offset(x, y, z), stone, 3);
                }
        Holding h = sample(g);
        h.box = new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + 15, o.getY() + 15, o.getZ() + 15);
        h.owner = Sides.player(UUID.randomUUID());
        var coal = new net.minecraft.resources.ResourceLocation("minecraft", "coal");
        h.addStock(coal, 8);
        if (!net.aiagent256.stormhold.holding.Builder.dark(level, o.offset(3, 2, 3)))
            throw new IllegalStateException("an open floor at night isn't counted dark");
        net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.LIGHT, g.makeMockPlayer());
        for (int k = 0; k < 60 && !h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE); k++)
            net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
        if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) throw new IllegalStateException("still lighting: " + h.work);
        int torches = h.workDone, used = 8 - h.stock(coal);
        if (torches < 2 || torches > 14) throw new IllegalStateException(torches + " torches for one yard and a room");
        if (used != (torches + 3) / 4) throw new IllegalStateException(torches + " torches took " + used + " coal");
        boolean inside = false;
        for (int x = 11; x <= 14; x++)
            for (int z = 11; z <= 14; z++)
                for (int y = 2; y <= 4; y++) {
                    var st = level.getBlockState(o.offset(x, y, z));
                    if (st.is(net.minecraft.world.level.block.Blocks.TORCH) || st.is(net.minecraft.world.level.block.Blocks.WALL_TORCH)) inside = true;
                }
        if (!inside) throw new IllegalStateException("the shut room got no torch");
        g.succeedWhen(() -> {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    boolean room = x >= 11 && x <= 14 && z >= 11 && z <= 14;
                    boolean shell = x >= 10 && x <= 15 && z >= 10 && z <= 15 && !room;
                    if (shell) continue;
                    BlockPos p = o.offset(x, 2, z);
                    if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p) <= 0)
                        throw new net.minecraft.gametest.framework.GameTestAssertException("still dark at " + x + ", " + z);
                }
        });
    }

    @GameTest(template = "arena")
    public static void builderClearsTheFieldAndLaysRoads(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        BlockPos o = sky(g, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
        var log = net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        var leaf = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1);
        // a wild tree and an orchard tree, five apart; and a barn's log beam with no leaves
        for (int tx : new int[]{2, 7}) {
            for (int y = 2; y <= 6; y++) level.setBlock(o.offset(tx, y, 3), log, 3);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int y = 5; y <= 6; y++)
                        if (dx != 0 || dz != 0) level.setBlock(o.offset(tx + dx, y, 3 + dz), leaf, 3);
            level.setBlock(o.offset(tx, 7, 3), leaf, 3);
        }
        var beam = log.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X);
        for (int x = 2; x <= 4; x++) level.setBlock(o.offset(x, 2, 10), beam, 3);
        Holding h = sample(g);
        h.box = new BoundingBox(o.getX() + 11, o.getY(), o.getZ() + 11, o.getX() + 15, o.getY() + 12, o.getZ() + 15);
        h.gatePos = h.box.getCenter();
        UUID me = UUID.randomUUID();
        h.owner = Sides.player(me);
        h.fief.add(new Holding.FiefBuilding("orchard", new BoundingBox(o.getX() + 6, o.getY(), o.getZ() + 2, o.getX() + 8, o.getY() + 9, o.getZ() + 4)));
        net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.CLEAR, g.makeMockPlayer());
        // the field reaches out past the arena into whatever wood grows on the surface round it: give it time
        for (int k = 0; k < 3000 && !h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE); k++)
            net.aiagent256.stormhold.holding.Builder.tick(level, mgr, h);
        if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE))
            throw new IllegalStateException("still clearing, " + (h.workQueue == null ? "no" : h.workQueue.size()) + " blocks to go");
        var oak = new net.minecraft.resources.ResourceLocation("minecraft", "oak_log");
        for (int y = 2; y <= 7; y++)
            if (!level.getBlockState(o.offset(2, y, 3)).isAir()) throw new IllegalStateException("the wild tree stands at y+" + y);
        if (!level.getBlockState(o.offset(1, 5, 2)).isAir()) throw new IllegalStateException("its leaves are left");
        if (!level.getBlockState(o.offset(7, 4, 3)).is(net.minecraft.world.level.block.Blocks.OAK_LOG)
                || !level.getBlockState(o.offset(6, 5, 3)).is(net.minecraft.world.level.block.Blocks.OAK_LEAVES))
            throw new IllegalStateException("the orchard tree was felled");
        for (int x = 2; x <= 4; x++)
            if (!level.getBlockState(o.offset(x, 2, 10)).is(net.minecraft.world.level.block.Blocks.OAK_LOG))
                throw new IllegalStateException("the barn beam was cut");
        if (h.stock(oak) < 5) throw new IllegalStateException(h.stock(oak) + " logs in the stores, not 5");
        // the road: to the nearest other place the same lord holds, paid in stone
        Holding far = new Holding();
        far.name = "Farhold";
        far.owner = h.owner;
        BlockPos c = h.box.getCenter();
        far.box = new BoundingBox(c.getX() + 395, c.getY() - 5, c.getZ() - 5, c.getX() + 405, c.getY() + 5, c.getZ() + 5);
        var cobble = new net.minecraft.resources.ResourceLocation("minecraft", "cobblestone");
        h.addStock(cobble, 64);
        int roads = mgr.roads.size();
        mgr.holdings.put(h.id, h);
        mgr.holdings.put(far.id, far);
        try {
            net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.ROAD, g.makeMockPlayer());
            if (!h.roadsTo.contains(far.id) || mgr.roads.size() != roads + 1)
                throw new IllegalStateException("no road planned to " + far.name);
            var plan = mgr.roads.get(roads);
            if (!plan.toName.equals("Farhold") || !plan.fromName.equals(h.name))
                throw new IllegalStateException("the road's signposts would read " + plan.fromName + " / " + plan.toName);
            var lines = net.aiagent256.stormhold.holding.Roads.signLines("Hollowwatch Tower", "812 blocks", "north-east");
            if (!lines[0].equals("Hollowwatch") || !lines[1].equals("Tower") || !lines[2].equals("812 blocks"))
                throw new IllegalStateException("signpost lines " + String.join("|", lines));
            if (!net.aiagent256.stormhold.holding.Roads.compass(10, -10).equals("north-east") || !net.aiagent256.stormhold.holding.Roads.compass(0, 5).equals("south")
                    || !net.aiagent256.stormhold.holding.Roads.compass(-3, 0).equals("west"))
                throw new IllegalStateException("the signposts point the wrong way");
            if (h.stock(cobble) != 64 - 40) throw new IllegalStateException("a 400-block road took " + (64 - h.stock(cobble)) + " stone");
            net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.ROAD, g.makeMockPlayer());
            if (mgr.roads.size() != roads + 1) throw new IllegalStateException("a second road to the same place");
            // and the projects button on a busy builder: he says so and starts nothing
            h.work = net.aiagent256.stormhold.holding.Builder.REPAIR;
            net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.CLEAR, g.makeMockPlayer());
            if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.REPAIR)) throw new IllegalStateException("a busy builder dropped his work");
        } finally {
            mgr.holdings.remove(h.id);
            mgr.holdings.remove(far.id);
            mgr.roads.subList(roads, mgr.roads.size()).clear();
            h.work = net.aiagent256.stormhold.holding.Builder.NONE;
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void illagerWarbandMustersClimbsAndSacks(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        // a yard and a curtain wall high over the arena (the arenas lie underground, the warband reads the heightmap)
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        BlockPos a = g.absolutePos(BlockPos.ZERO);
        BlockPos o = new BlockPos(a.getX(), 300, a.getZ());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(o.offset(x, 1, z), stone, 3);
                for (int y = 2; y < 12; y++) level.setBlock(o.offset(x, y, z), x == 4 && z >= 2 && z <= 13 && y <= 8 ? stone : air, 3);
            }
        }
        Holding h = sample(g);
        h.box = new net.minecraft.world.level.levelgen.structure.BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + 15, o.getY() + 15, o.getZ() + 15);
        h.owner = Sides.player(UUID.randomUUID());
        h.tier = 3;
        // the muster: a captain, a ravager for the gate, more than a dozen men for a great castle
        var band = net.aiagent256.stormhold.holding.Warbands.muster(level, h);
        int n = band.values().stream().mapToInt(Integer::intValue).sum();
        if (band.getOrDefault("captain", 0) != 1 || band.getOrDefault("minecraft:ravager", 0) < 1 || n < 14)
            throw new IllegalStateException("a great castle's warband is " + band);
        // the wall: found from the field side, and a ladder up its face
        var face = net.aiagent256.stormhold.holding.Warbands.wallFace(level, h, new net.minecraft.world.phys.Vec3(o.getX() + 1.5, o.getY() + 2, o.getZ() + 8.5));
        if (face == null || face.foot().getX() != o.getX() + 3 || face.in() != net.minecraft.core.Direction.EAST)
            throw new IllegalStateException("the wall face is " + face);
        int rungs = net.aiagent256.stormhold.holding.Warbands.raise(level, face);
        if (rungs < 6 || !level.getBlockState(face.foot().above(3)).is(net.minecraft.world.level.block.Blocks.LADDER))
            throw new IllegalStateException("a ladder of " + rungs + " rungs");
        // a man of the warband: the captain with the banner, and his death comes off the count
        var c = new net.aiagent256.stormhold.holding.Campaign();
        c.kind = net.aiagent256.stormhold.holding.Campaign.Kind.ILLAGERS;
        c.side = net.aiagent256.stormhold.holding.Warbands.SIDE;
        c.target = h.id;
        c.band.put("captain", 1);
        c.band.put("minecraft:vindicator", 2);
        mgr.campaigns.add(c);
        try {
            var cap = net.aiagent256.stormhold.holding.Warbands.spawn(level, c, "captain", o.offset(9, 2, 6));
            if (!(cap instanceof net.minecraft.world.entity.raid.Raider r) || !r.isPatrolLeader()
                    || !r.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.WHITE_BANNER))
                throw new IllegalStateException("the captain is " + cap);
            if (c.total() != 3) throw new IllegalStateException("the band counts " + c.total());
            cap.kill();
            if (c.total() != 2 || c.bandLive.containsKey(cap.getUUID())) throw new IllegalStateException("the captain's death left " + c.total());
        } finally {
            mgr.campaigns.remove(c);
        }
        // the sack: a quarter of the stores and a third of the garrison
        var em = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
        h.addStock(em, 100 - h.stock(em));
        int knights = h.count(Role.KNIGHT);
        if (knights < 1) throw new IllegalStateException("no knights to lose");
        var c2 = new net.aiagent256.stormhold.holding.Campaign();
        c2.kind = net.aiagent256.stormhold.holding.Campaign.Kind.ILLAGERS;
        net.aiagent256.stormhold.holding.Warbands.sack(level, mgr, h, c2);
        if (h.stock(em) != 75 || h.count(Role.KNIGHT) != knights - (int) Math.ceil(knights / 3.0))
            throw new IllegalStateException("the sack left " + h.stock(em) + " emeralds and " + h.count(Role.KNIGHT) + " of " + knights + " knights");
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void batonSplitsTheCompany(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        player.moveTo(g.absolutePos(new BlockPos(3, 2, 3)).getCenter());
        UUID me = player.getUUID();
        List<Entity> spawned = new ArrayList<>();
        try {
            GarrisonEntity a = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(5, 2, 5)), Sides.player(me), false);
            GarrisonEntity b = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(7, 2, 5)), Sides.player(me), false);
            if (a == null || b == null) throw new IllegalStateException("no men");
            spawned.add(a);
            spawned.add(b);
            a.setOwner(me);
            b.setOwner(me);
            net.aiagent256.stormhold.holding.Orders.toggle(level, player, a);
            if (net.aiagent256.stormhold.holding.Orders.modeOf(a) != net.aiagent256.stormhold.holding.Orders.Mode.HOLD
                    || net.aiagent256.stormhold.holding.Orders.modeOf(b) != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW
                    || !a.blockPosition().equals(net.aiagent256.stormhold.holding.Orders.posOf(a)))
                throw new IllegalStateException("told apart, he " + net.aiagent256.stormhold.holding.Orders.modeOf(a));
            net.aiagent256.stormhold.holding.Orders.toggle(level, player, a);
            if (net.aiagent256.stormhold.holding.Orders.modeOf(a) != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW)
                throw new IllegalStateException("back in, he " + net.aiagent256.stormhold.holding.Orders.modeOf(a));
            // the company holds the cave mouth; one comes along
            net.aiagent256.stormhold.holding.Orders.hold(level, player, g.absolutePos(new BlockPos(8, 2, 8)));
            net.aiagent256.stormhold.holding.Orders.toggle(level, player, b);
            if (net.aiagent256.stormhold.holding.Orders.modeOf(a) != net.aiagent256.stormhold.holding.Orders.Mode.HOLD
                    || net.aiagent256.stormhold.holding.Orders.modeOf(b) != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW)
                throw new IllegalStateException("holding with one along: " + net.aiagent256.stormhold.holding.Orders.modeOf(a) + ", "
                        + net.aiagent256.stormhold.holding.Orders.modeOf(b));
            net.aiagent256.stormhold.holding.Orders.fallIn(level, player);
            if (net.aiagent256.stormhold.holding.Orders.modeOf(a) != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW
                    || net.aiagent256.stormhold.holding.Orders.modeOf(b) != net.aiagent256.stormhold.holding.Orders.Mode.FOLLOW)
                throw new IllegalStateException("fall in left someone apart");
        } finally {
            for (Entity e : spawned) e.discard();
            net.aiagent256.stormhold.holding.Orders.forget(me);
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void shopsFoldAndRegularsPayLess(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        String[][] folded = {{"baker", "provisioner"}, {"butcher", "provisioner"}, {"fish", "provisioner"}, {"cloth", "general"},
                {"farmer", "general"}, {"chandler", "enchanter"}, {"potter", "mason"}, {"tanner", "stables"}, {"apothecary", "alchemist"}};
        for (String[] f : folded) {
            var t = net.aiagent256.stormhold.holding.Shops.type(f[0]);
            if (t == null || !t.id().equals(f[1])) throw new IllegalStateException(f[0] + " folds into " + (t == null ? null : t.id()));
        }
        Holding h = sample(g);
        List<Entity> spawned = new ArrayList<>();
        try {
            var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            BlockPos a = g.absolutePos(new BlockPos(3, 2, 3)), b = g.absolutePos(new BlockPos(6, 2, 3));
            for (BlockPos q : List.of(a, a.above(), b, b.above())) level.setBlock(q, air, 3);
            // a baker from an old plan becomes the provisioner
            var old = net.aiagent256.stormhold.holding.Shops.spawnKeeper(level, h, "general", a);
            if (old == null) throw new IllegalStateException("no keeper");
            spawned.add(old);
            old.getPersistentData().putString(net.aiagent256.stormhold.holding.Shops.TAG, "baker");
            net.aiagent256.stormhold.holding.Shops.convert(level, old, net.aiagent256.stormhold.holding.Shops.type("baker"), a);
            boolean fish = false;
            for (var o : old.getOffers()) if (o.getResult().is(net.minecraft.world.item.Items.COOKED_COD)) fish = true;
            if (!"provisioner".equals(old.getPersistentData().getString(net.aiagent256.stormhold.holding.Shops.TAG)) || !fish
                    || old.getVillagerData().getProfession() != net.minecraft.world.entity.npc.VillagerProfession.BUTCHER
                    || !old.getCustomName().getString().endsWith("the Provisioner"))
                throw new IllegalStateException("the old baker is " + old.getCustomName().getString() + ", " + old.getVillagerData().getProfession());
            // a regular's discount
            UUID me = UUID.randomUUID();
            h.custom.put(me, net.aiagent256.stormhold.holding.Shops.TRADES_PER_PERCENT * 10);
            if (net.aiagent256.stormhold.holding.Shops.discount(h, me) != 10) throw new IllegalStateException("60 trades is not 10% off");
            h.custom.put(me, 100000);
            if (net.aiagent256.stormhold.holding.Shops.discount(h, me) != net.aiagent256.stormhold.holding.Shops.MAX_DISCOUNT)
                throw new IllegalStateException("the discount runs past its cap");
            var arm = net.aiagent256.stormhold.holding.Shops.spawnKeeper(level, h, "armoury", b);
            if (arm == null) throw new IllegalStateException("no armourer");
            spawned.add(arm);
            net.aiagent256.stormhold.holding.Shops.applyDiscount(arm, 25);
            int price = -1, list = -1;
            for (var o : arm.getOffers()) {
                if (o.getResult().is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)) {
                    price = o.getCostA().getCount();                // the plain one comes first; the frontier's follow
                    list = o.getBaseCostA().getCount();
                    break;
                }
            }
            if (list != (int) Math.round(21 * net.aiagent256.stormhold.holding.Shops.PRICE))
                throw new IllegalStateException("the diamond chestplate lists at " + list);
            if (price != list - list / 4) throw new IllegalStateException("a " + list + "-emerald chestplate at 25% off costs " + price);
            // and every shop buys something dear
            boolean diamonds = false;
            for (var o : arm.getOffers()) if (o.getBaseCostA().is(net.minecraft.world.item.Items.DIAMOND) && o.getResult().is(net.minecraft.world.item.Items.EMERALD)) diamonds = true;
            if (!diamonds) throw new IllegalStateException("the armoury won't buy diamonds");
        } finally {
            for (Entity e : spawned) e.discard();
        }
        g.succeed();
    }

    @GameTest(template = "arena")
    public static void castleMarketGoesUp(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        // the test arenas lie underground, and a market wants sky over it: a yard high over this arena instead
        BlockPos a = g.absolutePos(BlockPos.ZERO);
        BlockPos o = new BlockPos(a.getX(), 300, a.getZ());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                level.setBlock(o.offset(x, 1, z), stone, 3);
                for (int y = 2; y < 10; y++) level.setBlock(o.offset(x, y, z), air, 3);
            }
        }
        Holding h = new Holding();
        h.key = "gametest-market@" + o.asLong();
        h.name = "Testmarket";
        h.tier = 3;
        h.layout = "great_castle_a";
        h.box = new BoundingBox(o.getX(), o.getY(), o.getZ(), o.getX() + 15, o.getY() + 9, o.getZ() + 15);
        h.rallyPos = o.offset(2, 2, 2);
        h.gatePos = o.offset(-40, 2, -40);
        if (!net.aiagent256.stormhold.holding.Market.wants(h) || !net.aiagent256.stormhold.holding.Shops.hasShops(h))
            throw new IllegalStateException("a great castle wants no market");
        List<Entity> spawned = new ArrayList<>();
        try {
            int n = net.aiagent256.stormhold.holding.Market.raise(level, h);
            if (n < 2) throw new IllegalStateException("only " + n + " stalls went up in an open yard: "
                    + net.aiagent256.stormhold.holding.Market.lastReport);
            for (int i = 0; i < n; i++) {
                String kind = net.aiagent256.stormhold.holding.Market.KINDS.get(i);
                List<BlockPos> ps = h.extras.get("shop_" + kind);
                if (ps == null || ps.size() != 1) throw new IllegalStateException(kind + " has posts " + ps);
                BlockPos post = ps.get(0);
                if (Math.max(Math.abs(post.getX() - h.rallyPos.getX()), Math.abs(post.getZ() - h.rallyPos.getZ())) < 8)
                    throw new IllegalStateException(kind + " stall stands on the muster ground at " + post);
                int counter = 0;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                        if (level.getBlockState(post.offset(dx, 0, dz)).getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) counter++;
                if (counter != 3) throw new IllegalStateException(kind + " stall has " + counter + " counter trapdoors");
                if (!post.equals(net.aiagent256.stormhold.holding.Shops.standAt(level, post)))
                    throw new IllegalStateException(kind + " keeper can't stand in his stall");
                boolean signed = false;
                for (BlockPos q : BlockPos.betweenClosed(post.offset(-1, 2, -1), post.offset(1, 2, 1)))
                    if (level.getBlockEntity(q) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sb
                            && !sb.getFrontText().getMessage(1, false).getString().isBlank()) signed = true;
                if (!signed) throw new IllegalStateException(kind + " stall has no sign");
                var v = net.aiagent256.stormhold.holding.Shops.spawnKeeper(level, h, kind, post);
                if (v == null) throw new IllegalStateException("no keeper for the " + kind + " stall");
                spawned.add(v);
            }
            if (h.extras.get("market") == null) throw new IllegalStateException("the guide has no market to point to");
        } finally {
            for (Entity e : spawned) e.discard();
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

    /** Castle stone shrugs off powder but falls to an engine; the chests come through. */
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void enginesBreachWallsPowderDoesnt(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        mgr.add(h);
        BlockPos p = g.absolutePos(new BlockPos(8, 5, 4));
        net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(p);
        try {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
            level.explode(null, at.x, at.y, at.z, 3.0F, net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
            if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS))
                throw new IllegalStateException("powder broke a castle's stone");
            net.aiagent256.stormhold.holding.Engines.blast(level, at);
            if (!level.getBlockState(p).isAir()) throw new IllegalStateException("a trebuchet stone didn't break the wall: " + level.getBlockState(p));
            level.setBlock(p, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            net.aiagent256.stormhold.holding.Engines.blast(level, at);
            if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CHEST)) throw new IllegalStateException("an engine broke a chest open");
        } finally {
            level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(p).inflate(6))) e.discard();
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** A treasury far out pays the land's own kit: a set from a great castle, netherite and the template at level 7. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void treasuriesPayTheZonesKit(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        h.tier = 3;
        h.forged = 7;
        mgr.add(h);
        BlockPos at = g.absolutePos(new BlockPos(4, 3, 4));
        try {
            var r = net.minecraft.util.RandomSource.create(7);
            java.util.Set<net.minecraft.world.item.Item> pieces = new java.util.HashSet<>();
            boolean template = false;
            int calls = 0;
            for (List<net.minecraft.world.item.ItemStack> got; !(got = net.aiagent256.stormhold.holding.Treasure.zoneKit(level, at, r)).isEmpty(); calls++) {
                for (var st : got) {
                    if (st.is(net.minecraft.world.item.Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)) template = true;
                    else if (st.isDamageableItem()) {
                        if (!net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem()).getPath().startsWith("netherite_"))
                            throw new IllegalStateException("level 7 kit that isn't netherite: " + st);
                        if (st.getEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.ALL_DAMAGE_PROTECTION) < 3
                                && st.getEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS) < 3)
                            throw new IllegalStateException("level 7 kit with a light enchant: " + st);
                        pieces.add(st.getItem());
                    }
                }
                if (calls > 10) throw new IllegalStateException("the treasury never runs dry");
            }
            if (pieces.size() != 5) throw new IllegalStateException("a great castle should give a set and a sword, gave " + pieces);
            if (!template) throw new IllegalStateException("no upgrade template at level 7");
            // and through the loot table itself, the way a chest opens in play
            Holding w = sample(g);
            w.key = w.key + "#2";
            w.forged = 4;
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            mgr.add(w);
            level.setBlock(at, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
            if (!(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest))
                throw new IllegalStateException("no chest");
            chest.setLootTable(new net.minecraft.resources.ResourceLocation(Stormhold.MODID, "chests/watchtower_treasury"), 11L);
            chest.unpackLootTable(null);
            int diamondKit = 0;
            for (int i = 0; i < chest.getContainerSize(); i++)
                if (net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(chest.getItem(i).getItem()).getPath().startsWith("diamond_")
                        && chest.getItem(i).isDamageableItem()) diamondKit++;
            mgr.holdings.remove(w.id);
            mgr.byKey.remove(w.key);
            if (diamondKit != 2) throw new IllegalStateException("a level 4 watchtower's treasury should hold 2 pieces of diamond kit, held " + diamondKit);
        } finally {
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
            level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        g.succeed();
    }

    /** Your men strip the enemy dead too, and what they take far from home rides back to the spoils chest. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void campaignKitRidesHome(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        GarrisonEntity foe = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(4, 3, 4)), Sides.house("testhouse"), false);
        if (foe == null) throw new IllegalStateException("no knight");
        for (var s : net.minecraft.world.entity.EquipmentSlot.values()) foe.setDropChance(s, 1.0F);
        List<net.minecraft.world.entity.item.ItemEntity> dropped = new ArrayList<>();
        foe.captureDrops(dropped);
        int n = foe.stripKit();
        foe.captureDrops(null);
        foe.discard();
        if (n < 4 || dropped.size() != n) throw new IllegalStateException("a knight in full kit at certain chance gave " + n + " pieces, " + dropped.size() + " drops");
        for (var ie : dropped)
            if (ie.getItem().isDamageableItem() && ie.getItem().getDamageValue() <= 0) throw new IllegalStateException("stripped kit came off unworn");
        Holding h = sample(g);
        UUID me = UUID.randomUUID();
        h.owner = Sides.player(me);
        h.houseId = "";
        h.warTablePos = BlockPos.ZERO;                       // nowhere to put it yet: it rides with the carts
        mgr.add(h);
        try {
            int moved = net.aiagent256.stormhold.holding.Spoils.take(level, mgr, h, new ArrayList<>(dropped).iterator(), s -> true, true);
            if (moved != n || h.spoilsCarried.size() != n) throw new IllegalStateException("carried " + h.spoilsCarried.size() + " of " + n);
            h.warTablePos = g.absolutePos(new BlockPos(8, 3, 8));
            net.aiagent256.stormhold.holding.Spoils.unload(level, mgr, h);
            if (!h.spoilsCarried.isEmpty()) throw new IllegalStateException("the carts didn't unload");
            CompoundTag t = h.save();
            if (!t.contains("GearLeft")) throw new IllegalStateException("the treasury's kit count isn't saved");
        } finally {
            for (BlockPos p : BlockPos.betweenClosed(g.absolutePos(new BlockPos(3, 3, 3)), g.absolutePos(new BlockPos(13, 4, 13))))
                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            dropped.forEach(Entity::discard);
            mgr.holdings.remove(h.id);
            mgr.byKey.remove(h.key);
        }
        g.succeed();
    }

    /** The odds before the fight: a third of their number at the same kit, half as many again per step behind. */
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void readinessCountsKitAndNumbers(GameTestHelper g) {
        // a great castle's 98, in diamond, against men in iron and a lord in diamond: (98/3 x 2.25 - 3 x 2.25) / 1.5 = 44.5
        int need = net.aiagent256.stormhold.holding.Readiness.needed(98, 2, 1, 2);
        if (need != 45) throw new IllegalStateException("needed " + need + ", want 45");
        if (net.aiagent256.stormhold.holding.Readiness.needed(98, 2, 2, 2) != 30) throw new IllegalStateException("in diamond it should be 30");
        if (net.aiagent256.stormhold.holding.Readiness.tier(9) != 4 || net.aiagent256.stormhold.holding.Readiness.tier(4) != 2
                || net.aiagent256.stormhold.holding.Readiness.tier(1) != 0) throw new IllegalStateException("kit steps are off");
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding enemy = sample(g);
        int want = 0;
        for (Role r : Role.TROOPS) want += enemy.count(r);
        want += 1 + 2 + enemy.tier;                                             // the lord and his guard
        int k = HoldingEvents.counterattack(enemy);
        want += k + Math.max(1, k / 3);                                           // the rally at the stand; a watchtower has no waves
        if (HoldingEvents.defendersInAll(enemy) != want)
            throw new IllegalStateException("defenders " + HoldingEvents.defendersInAll(enemy) + ", want " + want);
        Holding mine = sample(g);
        mine.key = mine.key + "#mine";
        mine.owner = Sides.player(UUID.randomUUID());
        mine.houseId = "";
        mine.tier = 3;
        mine.fillGarrison(1.0);
        mgr.add(enemy);
        try {
            String line = net.aiagent256.stormhold.holding.Readiness.tableLine(level, mgr, mine, enemy.bannerPos);
            if (line == null || !line.contains(enemy.name) || !line.contains("Sent alone"))
                throw new IllegalStateException("no odds on the War Table: " + line);
            int before = mine.count(Role.KNIGHT);
            if (Campaigns.sends(mine, Role.KNIGHT) != Math.max(0, Math.min(before - 3, (int) Math.ceil(before * 0.6))))
                throw new IllegalStateException("a war party's size changed");
        } finally {
            mgr.holdings.remove(enemy.id);
            mgr.byKey.remove(enemy.key);
        }
        g.succeed();
    }

    /** A man coming back from disk takes his place only if it's free: one lord, never more than the count. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void savedSoldiersAreNotDoubled(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        mgr.add(h);
        List<GarrisonEntity> made = new ArrayList<>();
        try {
            for (int k = 0; k < 6; k++) Garrisons.materialize(level, mgr, h);
            if (Garrisons.liveCount(h.live, Role.LORD) != 1) throw new IllegalStateException("lord did not appear once");
            java.util.function.Function<Role, GarrisonEntity> saved = r -> {
                GarrisonEntity e = net.aiagent256.stormhold.registry.ModEntities.typeFor(r, true).create(level);
                if (e == null) throw new IllegalStateException("no entity for " + r);
                e.setup(h.owner, h.id, h.tier, net.minecraft.world.item.DyeColor.RED, h.lordPos, true);
                made.add(e);
                return e;
            };
            GarrisonEntity second = saved.apply(Role.LORD);
            if (Garrisons.belongs(level, second)) throw new IllegalStateException("a second lord was let in");
            // the lord's chunk unloaded, the castle forgot him: the saved one takes his place
            for (var e : new ArrayList<>(h.live.entrySet()))
                if (e.getValue() == Role.LORD) {
                    Entity lord = level.getEntity(e.getKey());
                    if (lord != null) lord.discard();
                }
            if (!Garrisons.belongs(level, second) || !h.live.containsKey(second.getUUID()))
                throw new IllegalStateException("the saved lord was not taken back");
            int knights = Garrisons.liveCount(h.live, Role.KNIGHT);
            h.add(Role.KNIGHT, knights - h.count(Role.KNIGHT));
            if (Garrisons.belongs(level, saved.apply(Role.KNIGHT))) throw new IllegalStateException("a knight over the count was let in");
            h.add(Role.KNIGHT, 1);
            if (!Garrisons.belongs(level, saved.apply(Role.KNIGHT))) throw new IllegalStateException("a knight under the count was dropped");
            GarrisonEntity theirs = saved.apply(Role.KNIGHT);
            h.add(Role.KNIGHT, 5);
            h.owner = Sides.player(UUID.randomUUID());
            if (Garrisons.belongs(level, theirs)) throw new IllegalStateException("a man of the house that lost the castle was let in");
        } finally {
            made.forEach(Entity::discard);
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
            // and the inner gates: every plane the plans record
            BlockPos lo = new BlockPos(h.box.minX(), h.box.minY(), h.box.minZ());
            for (int[] pl : lay.gatePlanes) level.getChunk((lo.getX() + pl[0]) >> 4, (lo.getZ() + pl[2]) >> 4);
            net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (h.gates == null) throw new IllegalStateException(id + " gate scan found its chunks unloaded");
            for (int[] pl : lay.gatePlanes) {
                BlockPos pa = lo.offset(pl[0], pl[1], pl[2]), pb = lo.offset(pl[3], pl[4], pl[5]);
                boolean found = false;
                for (var plane : h.gates) for (BlockPos q : plane.blocks)
                    if (q.getX() >= pa.getX() && q.getX() <= pb.getX() && q.getY() >= pa.getY() && q.getY() <= pb.getY()
                            && q.getZ() >= pa.getZ() && q.getZ() <= pb.getZ()) found = true;
                if (!found) throw new IllegalStateException(id + " never found its gate at " + pa + " (" + h.gates.size() + " planes found of "
                        + lay.gatePlanes.size() + " in the plans)");
            }
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
            // a house's ram fits on the road before the outer gate, swings at it, and is taken away again
            var site = net.aiagent256.stormhold.holding.SiegeWorks.site(level, h);
            if (site == null) throw new IllegalStateException(id + " has no place for a ram before its gate");
            var rc = new net.aiagent256.stormhold.holding.Campaign();
            if (!net.aiagent256.stormhold.holding.SiegeWorks.raise(level, rc, site))
                throw new IllegalStateException(id + " ram won't fit at " + site.head() + " facing " + site.in() + ": beam cells "
                        + level.getBlockState(site.head().above().relative(site.in().getOpposite())) + ", "
                        + level.getBlockState(site.head().above().relative(site.in().getOpposite(), 2)));
            if (!level.getBlockState(site.head().relative(site.in())).is(net.aiagent256.stormhold.registry.ModBlocks.PORTCULLIS.get()))
                throw new IllegalStateException(id + " ram's head isn't at the portcullis: " + level.getBlockState(site.head().relative(site.in())));
            int beam = net.aiagent256.stormhold.holding.SiegeWorks.beam(level, site);
            if (beam < 3) throw new IllegalStateException(id + " ram has a beam of " + beam);
            net.aiagent256.stormhold.holding.SiegeWorks.swing(level, site, true);
            if (!level.getBlockState(site.head().above()).is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG))
                throw new IllegalStateException(id + " ram didn't swing into the gate");
            net.aiagent256.stormhold.holding.SiegeWorks.clear(level, rc);
            if (net.aiagent256.stormhold.holding.SiegeWorks.beam(level, site) != 0) throw new IllegalStateException(id + " ram left behind");
            if (!net.aiagent256.stormhold.holding.Gates.breach(level, h)) throw new IllegalStateException(id + " gate would not break");
            // a broken gate stays down ten minutes, then goes back up
            if (!net.aiagent256.stormhold.holding.Gates.down(level, h)) throw new IllegalStateException(id + " broken gate not marked down");
            if (net.aiagent256.stormhold.holding.Gates.restore(level, mgr, h) != 0) throw new IllegalStateException(id + " gate rebuilt inside its ten minutes");
            h.gateBrokenAt = level.getGameTime() - net.aiagent256.stormhold.holding.Gates.DOWN_TICKS - 1;
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
            // a wary house lets a stranger walk its yard; a hostile one attacks on sight
            if (knight.isHostileTo(player)) throw new IllegalStateException("wary house's knight attacked a stranger it let in");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), -30, "test");
            knight = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(5, 2, 4)), Sides.house(houseId), false);
            if (!knight.isHostileTo(player)) throw new IllegalStateException("hostile house's knight ignored an enemy");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), 70, "test");
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
            // war with a hostile house, peace with any other
            String houseId = "companypeace";
            mgr.house(houseId).disposition = 0;
            mgr.standing.remove(houseId);
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), -30, "test");
            guard = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(9, 2, 9)), Sides.house(houseId), false);
            if (!man.isHostileTo(guard) || !guard.isHostileTo(man)) throw new IllegalStateException("a hostile house's guard and your man ignored each other");
            net.aiagent256.stormhold.house.Standing.change(level, mgr, houseId, player.getUUID(), 40, "test");
            guard.discard();
            guard = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(9, 2, 10)), Sides.house(houseId), false);
            man.discard();
            man = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(6, 2, 6)), Sides.player(player.getUUID()), false);
            net.aiagent256.stormhold.holding.Company.enlist(level, mgr, man, player, null);
            if (man.isHostileTo(guard) || guard.isHostileTo(man)) throw new IllegalStateException("your man and a wary house's guard fought");
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
                // and everything above it, mountains included: the test's spot moves as tests are added, and a peak left
                // standing over the field is where the fief would put its mine
                int top = Math.max(y + 24, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z));
                for (int yy = y; yy < top; yy++) {
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
            // a traveller's own sworn man is no enemy at a house that doesn't hate his lord: he doesn't hold the gate shut
            knight.setMarchTarget(null);
            var sworn = Garrisons.spawn(level, mgr, null, Role.KNIGHT, gate.offset(0, 0, 3), Sides.player(UUID.randomUUID()), false);
            for (int i = 0; i < 8; i++) net.aiagent256.stormhold.holding.Gates.tick(level, mgr, h);
            if (!level.getBlockState(gate).getValue(net.aiagent256.stormhold.block.PortcullisBlock.OPEN)) throw new IllegalStateException("a wary traveller's soldier kept the gate shut");
            if (sworn != null) sworn.discard();
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
        int treasury = net.aiagent256.stormhold.holding.Rewards.treasury(level, b);
        if (emeralds != 20 + treasury) throw new IllegalStateException("expected 20 emeralds and a treasury of " + treasury + ", got " + emeralds);
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

    /**
     * A wall six high with a corbelled ledge and a machicolation cut in it. From the middle of his block an archer
     * can't see a man at the wall's foot (the wall's own top is in the way); at the hole's lip he can, and shoots.
     */
    // a batch of its own: the company tests clear every soldier within 64 blocks of their player when they finish
    @GameTest(template = "arena", timeoutTicks = 300, batch = "lip_archers")
    public static void lipArchersShootDownTheWall(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        int fy = 1;
        while (!level.getBlockState(g.absolutePos(new BlockPos(5, fy, 5))).isAir() && fy < 6) fy++;
        int top = fy + 6;                                // the walk: men stand at y = top
        for (int z = 3; z <= 11; z++) {
            for (int y = fy; y < top; y++) for (int x = 8; x <= 9; x++)
                g.setBlock(new BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
            if (z == 7) continue;                         // the hole
            g.setBlock(new BlockPos(7, top - 2, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);   // the ledge
            g.setBlock(new BlockPos(7, top - 1, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
            if (z % 4 < 2) {                              // merlons
                g.setBlock(new BlockPos(7, top, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
                g.setBlock(new BlockPos(7, top + 1, z), net.minecraft.world.level.block.Blocks.STONE_BRICKS);
            }
        }
        BlockPos post = g.absolutePos(new BlockPos(8, top, 7));
        BlockPos foot = g.absolutePos(new BlockPos(7, fy, 7));          // hard against the wall's face, under the hole
        Holding h = sample(g);
        h.lips.put(post, new int[]{-1, 0});
        GarrisonEntity archer = Garrisons.spawn(level, mgr, h, Role.ARCHER, post, h.owner, true);
        if (archer == null) throw new IllegalStateException("no archer");
        archer.setInvulnerable(true);            // the tests next door have raiders in them: this one is about his eye
        var foe = net.minecraft.world.entity.EntityType.VINDICATOR.create(level);
        if (foe == null) throw new IllegalStateException("no vindicator");
        foe.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 0, 0);
        foe.setNoAi(true);
        foe.setPersistenceRequired();
        level.addFreshEntity(foe);
        // the dead ground: from the middle of his block the wall's top hides the man below
        net.minecraft.world.phys.Vec3 mid = new net.minecraft.world.phys.Vec3(post.getX() + 0.5, post.getY() + archer.getEyeHeight(), post.getZ() + 0.5);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(mid, foe.getEyePosition(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, archer));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
            archer.discard(); foe.discard();
            throw new IllegalStateException("the wall's foot was in sight from the middle of the walk: the test wall is wrong");
        }
        float full = foe.getHealth();
        g.succeedWhen(() -> {
            if (!archer.isAlive()) throw new net.minecraft.gametest.framework.GameTestAssertException("archer gone ("
                    + (archer.isRemoved() ? archer.getRemovalReason() : "dead") + ", last hurt by " + archer.getLastDamageSource() + ")");
            if (archer.getY() < post.getY() - 0.01) throw new net.minecraft.gametest.framework.GameTestAssertException("archer fell off the lip: y " + archer.getY());
            net.minecraft.world.phys.Vec3 at = archer.lipSpot();
            if (at == null || Math.abs(archer.getX() - at.x) > 0.06 || Math.abs(archer.getZ() - at.z) > 0.06)
                throw new net.minecraft.gametest.framework.GameTestAssertException("not at the lip: " + archer.position() + " want " + at);
            if (foe.isAlive() && foe.getHealth() >= full)
                throw new net.minecraft.gametest.framework.GameTestAssertException("no hit on the man at the foot yet; target " + archer.getTarget()
                        + ", sees him " + archer.getSensing().hasLineOfSight(foe));
            archer.discard();
            foe.discard();
        });
    }

    /** The seat is where rents go; the forge can be rushed for emeralds, and the arithmetic holds. */
    @GameTest(template = "arena")
    public static void seatTakesTheRentsAndTheForgeCanBeRushed(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        Holding a = sample(g), b = sample(g);
        b.key = a.key + "/b";
        b.name = "Secondhold";
        a.owner = Sides.player(player.getUUID());
        b.owner = a.owner;
        mgr.holdings.put(a.id, a);
        mgr.holdings.put(b.id, b);
        try {
            if (!net.aiagent256.stormhold.holding.Seats.set(level, mgr, b, player)) throw new IllegalStateException("the seat wasn't set");
            if (mgr.ownedBy(player.getUUID()).get(0) != b) throw new IllegalStateException("the seat isn't first: the rents would go elsewhere");
            if (net.aiagent256.stormhold.holding.Seats.set(level, mgr, b, player)) throw new IllegalStateException("set twice");
            a.vassal = "Tom";
            if (net.aiagent256.stormhold.holding.Seats.set(level, mgr, a, player)) throw new IllegalStateException("a vassal's castle made the seat");
            a.vassal = null;
            // the forge: the next level now, for its metal and level x smith-days left x 2 emeralds
            int target = net.aiagent256.stormhold.holding.Labour.target(level, b);
            if (target <= net.aiagent256.stormhold.holding.Frontier.MAX) {
                int fee = net.aiagent256.stormhold.holding.Labour.rushCost(level, b);
                if (fee != target * net.aiagent256.stormhold.holding.Labour.SMITH_DAYS * net.aiagent256.stormhold.holding.Labour.RUSH_PER_LEVEL_DAY)
                    throw new IllegalStateException("rush fee " + fee + " for level " + target);
                // half the work done already: half the fee
                b.forgePaid = true;
                b.forgeTarget = target;
                b.forgeWork = net.aiagent256.stormhold.holding.Labour.SMITH_DAYS / 2;
                int half = net.aiagent256.stormhold.holding.Labour.rushCost(level, b);
                if (half != target * (net.aiagent256.stormhold.holding.Labour.SMITH_DAYS - b.forgeWork) * net.aiagent256.stormhold.holding.Labour.RUSH_PER_LEVEL_DAY)
                    throw new IllegalStateException("rush fee " + half + " with half the work done");
                b.forgePaid = false;
                b.forgeTarget = 0;
                b.forgeWork = 0;
                if (net.aiagent256.stormhold.holding.Labour.rush(level, mgr, b, player)) throw new IllegalStateException("rushed with no metal or emeralds");
                int[] cost = net.aiagent256.stormhold.holding.Labour.cost(target);
                var mc = (java.util.function.Function<String, net.minecraft.resources.ResourceLocation>) n -> new net.minecraft.resources.ResourceLocation("minecraft", n);
                b.addStock(mc.apply("iron_ingot"), cost[0]);
                b.addStock(mc.apply("diamond"), cost[1]);
                b.addStock(mc.apply("netherite_ingot"), cost[2]);
                b.addStock(mc.apply("emerald"), fee + 5);
                if (!net.aiagent256.stormhold.holding.Labour.rush(level, mgr, b, player)) throw new IllegalStateException("rush refused with everything there");
                if (b.forged != target) throw new IllegalStateException("forged " + b.forged + ", not " + target);
                int em = net.aiagent256.stormhold.holding.Stores.realmStock(mgr, b, player.getUUID(), mc.apply("emerald"));
                if (em != 5) throw new IllegalStateException(em + " emeralds left, not 5");
                if (net.aiagent256.stormhold.holding.Stores.realmStock(mgr, b, player.getUUID(), mc.apply("iron_ingot")) != 0)
                    throw new IllegalStateException("the metal wasn't taken");
            }
        } finally {
            mgr.holdings.remove(a.id);
            mgr.holdings.remove(b.id);
            mgr.seats.remove(player.getUUID());
        }
        g.succeed();
    }

    /** Taking a castle by force makes an heir; a truce turns his raid back and holds off the next; a hostage comes home. */
    @GameTest(template = "arena")
    public static void trucesStopRaidsAndHeirsWantTheirCastlesBack(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        UUID me = player.getUUID();
        Holding h = sample(g);
        mgr.holdings.put(h.id, h);
        var emerald = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
        try {
            HoldingEvents.transfer(level, mgr, h, Sides.player(me));
            if (h.heir.isEmpty() || !h.heirFather.contains("Lord Test")) throw new IllegalStateException("no heir: '" + h.heir + "', son of '" + h.heirFather + "'");
            Campaign c = Campaigns.launchRaid(level, mgr, h);
            if (!c.heir.equals(h.heir)) throw new IllegalStateException("the heir doesn't lead the raid");
            if (c.counts.getOrDefault(Role.KNIGHT, 0) < 4 + net.aiagent256.stormhold.holding.Heirs.MORE_KNIGHTS)
                throw new IllegalStateException("the heir brought " + c.counts.get(Role.KNIGHT) + " knights");
            // a truce bought: the raid turns back, the house isn't hostile, no raid while it holds
            h.addStock(emerald, 500);
            int had = h.stock(emerald) + player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD);
            if (!net.aiagent256.stormhold.holding.Truces.ask(level, mgr, player, "testhouse", false))
                throw new IllegalStateException("truce refused: " + net.aiagent256.stormhold.holding.Truces.refusal(mgr, "testhouse", me));
            int price = net.aiagent256.stormhold.holding.Truces.price(mgr, "testhouse", me);
            int paid = had - h.stock(emerald) - player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD);
            if (paid != price) throw new IllegalStateException("the truce cost " + paid + ", not " + price);
            if (c.stage != Campaign.Stage.DONE) throw new IllegalStateException("the raid didn't turn back");
            if (net.aiagent256.stormhold.house.Standing.hostile(mgr, "testhouse", me)) throw new IllegalStateException("hostile in a truce");
            mgr.campaigns.remove(c);
            h.nextRaid = level.getGameTime();
            Campaigns.scheduleRaids(level, mgr, h, level.getGameTime());
            if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID)) throw new IllegalStateException("a raid in the truce");
            if (h.nextRaid < net.aiagent256.stormhold.holding.Truces.get(mgr, "testhouse", me).until) throw new IllegalStateException("next raid inside the truce");
            net.aiagent256.stormhold.holding.Truces.broken(level, mgr, "testhouse", me, "a test");
            if (net.aiagent256.stormhold.holding.Truces.active(mgr, "testhouse", me)) throw new IllegalStateException("the broken truce holds");
            // a hostage's truce: one knight goes, and comes home when it's over
            int knights = h.count(Role.KNIGHT);
            if (knights < 2) h.add(Role.KNIGHT, 2 - knights);
            knights = h.count(Role.KNIGHT);
            if (!net.aiagent256.stormhold.holding.Truces.ask(level, mgr, player, "testhouse", true)) throw new IllegalStateException("hostage truce refused");
            if (h.count(Role.KNIGHT) != knights - 1) throw new IllegalStateException("no hostage went");
            net.aiagent256.stormhold.holding.Truces.get(mgr, "testhouse", me).until = level.getGameTime() - 1;
            net.aiagent256.stormhold.holding.Truces.tick(level, mgr);
            if (net.aiagent256.stormhold.holding.Truces.active(mgr, "testhouse", me) || h.count(Role.KNIGHT) != knights)
                throw new IllegalStateException("the hostage didn't come home");
            // thrown back three times, the heir gives up
            for (int i = 0; i < net.aiagent256.stormhold.holding.Heirs.GIVE_UP; i++) net.aiagent256.stormhold.holding.Heirs.beaten(level, mgr, h, c);
            if (!h.heir.isEmpty()) throw new IllegalStateException("the heir kept his claim after " + net.aiagent256.stormhold.holding.Heirs.GIVE_UP + " defeats");
            // a claim bought out
            h.heir = "Ser Testson";
            h.heirFather = "the late Lord Test";
            h.addStock(emerald, 500);
            if (!net.aiagent256.stormhold.holding.Heirs.buyOut(level, mgr, h, player) || !h.heir.isEmpty())
                throw new IllegalStateException("couldn't buy out the claim");
        } finally {
            mgr.holdings.remove(h.id);
            mgr.truces.remove("testhouse");
            mgr.campaigns.removeIf(x -> h.id.equals(x.target));
        }
        g.succeed();
    }

    /** A house's ram before a shut gate: ten blows and the gate's down; break its beam and it's done. */
    @GameTest(template = "arena")
    public static void aHouseRamBreaksTheGateUnlessItsBeamIsBroken(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        h.owner = Sides.player(UUID.randomUUID());
        var pc = net.aiagent256.stormhold.registry.ModBlocks.PORTCULLIS.get().defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int x = 4; x <= 10; x++) for (int y = 2; y <= 5; y++) for (int z = 9; z <= 14; z++) g.setBlock(new BlockPos(x, y, z), air);
        Runnable gate = () -> {
            for (int x = 6; x <= 8; x++) for (int y = 2; y <= 4; y++) g.setBlock(new BlockPos(x, y, 8), pc);
            h.gates = new ArrayList<>(List.of(net.aiagent256.stormhold.holding.Gates.planeAt(level, g.absolutePos(new BlockPos(7, 2, 8)))));
        };
        gate.run();
        var site = net.aiagent256.stormhold.holding.SiegeWorks.site(level, h);
        if (site == null || !site.head().equals(g.absolutePos(new BlockPos(7, 2, 9))) || site.in() != net.minecraft.core.Direction.NORTH)
            throw new IllegalStateException("ram site " + (site == null ? "none" : site.head() + " " + site.in()) + ", not " + g.absolutePos(new BlockPos(7, 2, 9)) + " north");
        String side = Sides.house("testhouse");
        List<GarrisonEntity> men = new ArrayList<>();
        try {
            Campaign c = new Campaign();
            c.kind = Campaign.Kind.RAID;
            c.side = side;
            c.target = h.id;
            for (int i = 0; i < 2; i++) {
                GarrisonEntity m = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(4 + i * 6, 2, 11)), side, false);
                if (m == null) throw new IllegalStateException("no raider");
                men.add(m);
                c.live.put(m.getUUID(), Role.KNIGHT);
                c.add(Role.KNIGHT, 1);
            }
            c.siegeSeconds = net.aiagent256.stormhold.holding.SiegeWorks.RAM_AFTER - 1;
            net.aiagent256.stormhold.holding.SiegeWorks.tick(level, mgr, h, c);
            if (c.ramHead == null) throw new IllegalStateException("no ram brought up");
            for (int i = 0; i < 40 && !c.rammed; i++) net.aiagent256.stormhold.holding.SiegeWorks.tick(level, mgr, h, c);
            if (!c.rammed) throw new IllegalStateException("the gate stands after " + c.ramHits + " blows");
            if (c.ramHits != net.aiagent256.stormhold.holding.SiegeWorks.RAM_HITS) throw new IllegalStateException(c.ramHits + " blows broke it");
            if (level.getBlockState(g.absolutePos(new BlockPos(7, 2, 8))).is(pc.getBlock())) throw new IllegalStateException("the portcullis is still there");
            if (c.ramHead != null || net.aiagent256.stormhold.holding.SiegeWorks.beam(level, site) != 0) throw new IllegalStateException("the ram wasn't taken away");
            // a new gate, and a ram whose beam is broken: wrecked, and none comes after it
            gate.run();
            Campaign c2 = new Campaign();
            c2.kind = Campaign.Kind.RAID;
            c2.side = side;
            c2.target = h.id;
            c2.live.putAll(c.live);
            c2.counts.putAll(c.counts);
            c2.siegeSeconds = net.aiagent256.stormhold.holding.SiegeWorks.RAM_AFTER - 1;
            net.aiagent256.stormhold.holding.SiegeWorks.tick(level, mgr, h, c2);
            if (c2.ramHead == null) throw new IllegalStateException("no second ram");
            for (int k = 1; k <= 3; k++) level.setBlock(site.head().relative(net.minecraft.core.Direction.SOUTH, k).above(), air, 3);
            net.aiagent256.stormhold.holding.SiegeWorks.tick(level, mgr, h, c2);
            if (c2.ramHead != null || !c2.ramDone) throw new IllegalStateException("the broken ram wasn't wrecked");
            for (int i = 0; i < 10; i++) net.aiagent256.stormhold.holding.SiegeWorks.tick(level, mgr, h, c2);
            if (c2.ramHead != null || c2.rammed) throw new IllegalStateException("another ram came, or the gate fell anyway");
            if (!level.getBlockState(g.absolutePos(new BlockPos(7, 2, 8))).is(pc.getBlock())) throw new IllegalStateException("the gate fell to a wrecked ram");
        } finally {
            for (GarrisonEntity m : men) m.discard();
        }
        g.succeed();
    }

    /** In the lists nobody but the two strikes the other, a beaten challenger yields, and so does the player at two hearts. */
    @GameTest(template = "arena")
    public static void inTheListsOnlyTheTwoFightAndTheBeatenYield(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        Holding h = sample(g);
        h.owner = Sides.player(player.getUUID());
        GarrisonEntity ch = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(5, 2, 5)), Sides.house("testhouse"), false);
        GarrisonEntity mine = Garrisons.spawn(level, mgr, null, Role.KNIGHT, g.absolutePos(new BlockPos(10, 2, 10)), Sides.player(player.getUUID()), false);
        try {
            if (ch == null || mine == null) throw new IllegalStateException("couldn't raise the men");
            // a challenger whose bout is over (the world closed mid-tournament) doesn't belong
            ch.getPersistentData().putUUID(net.aiagent256.stormhold.holding.Tourney.TAG, player.getUUID());
            if (Garrisons.belongs(level, ch)) throw new IllegalStateException("a challenger with no bout stays");
            net.aiagent256.stormhold.holding.Tourney.forTest(player.getUUID(), h, level.dimension(), ch);
            if (!Garrisons.belongs(level, ch)) throw new IllegalStateException("the challenger in the lists was sent away");
            if (!ch.isHostileTo(player)) throw new IllegalStateException("he won't fight the player");
            if (ch.isHostileTo(mine) || mine.isHostileTo(ch)) throw new IllegalStateException("the player's man and the challenger go for each other");
            if (!net.aiagent256.stormhold.holding.Tourney.apart(mine, ch) || !net.aiagent256.stormhold.holding.Tourney.apart(ch, mine))
                throw new IllegalStateException("blows between the challenger and the player's man aren't stopped");
            if (net.aiagent256.stormhold.holding.Tourney.apart(player, ch) || net.aiagent256.stormhold.holding.Tourney.apart(ch, player))
                throw new IllegalStateException("the two in the lists can't strike each other");
            var blow = new net.minecraftforge.event.entity.living.LivingDamageEvent(ch, level.damageSources().playerAttack(player), ch.getMaxHealth());
            net.aiagent256.stormhold.holding.Tourney.onDamage(blow);
            if (!blow.isCanceled() || !net.aiagent256.stormhold.holding.Tourney.yielded(player.getUUID()) || ch.getHealth() < 1)
                throw new IllegalStateException("a beaten man didn't yield");
            net.aiagent256.stormhold.holding.Tourney.forget(player.getUUID());
            net.aiagent256.stormhold.holding.Tourney.forTest(player.getUUID(), h, level.dimension(), ch);
            player.setHealth(6.0F);
            var back = new net.minecraftforge.event.entity.living.LivingDamageEvent(player, level.damageSources().mobAttack(ch), 3.0F);
            net.aiagent256.stormhold.holding.Tourney.onDamage(back);
            if (!back.isCanceled() || !net.aiagent256.stormhold.holding.Tourney.lost(player.getUUID()))
                throw new IllegalStateException("the player didn't yield at two hearts");
        } finally {
            net.aiagent256.stormhold.holding.Tourney.forget(player.getUUID());
            if (ch != null) ch.discard();
            if (mine != null) mine.discard();
        }
        g.succeed();
    }

    /** Doubles stacked on a stall's post, under today's title or an old one, are sent away; one keeper stays. */
    @GameTest(template = "arena")
    public static void stackedShopkeepersAreSentAway(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        Holding h = sample(g);
        BlockPos bake = g.absolutePos(new BlockPos(4, 2, 4)), store = g.absolutePos(new BlockPos(10, 2, 10));
        h.extras.put("shop_baker", new ArrayList<>(List.of(bake)));
        h.extras.put("shop_general", new ArrayList<>(List.of(store)));
        List<net.minecraft.world.entity.npc.Villager> made = new ArrayList<>();
        String[][] names = {{"Mabel the Baker", "Rob the Baker", "Osric the Provisioner"}, {"Tilda the Shopkeeper", "Maud the Shopkeeper"}};
        BlockPos[] posts = {bake, store};
        try {
            for (int i = 0; i < 2; i++) {
                for (String n : names[i]) {
                    var v = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
                    if (v == null) throw new IllegalStateException("no villager");
                    v.setCustomName(net.minecraft.network.chat.Component.literal(n));
                    v.moveTo(posts[i].getX() + 0.5, posts[i].getY(), posts[i].getZ() + 0.5, 0, 0);
                    v.setNoAi(true);
                    level.addFreshEntity(v);
                    made.add(v);
                }
            }
            // a villager who isn't a keeper stands by the stall and stays
            var passer = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            passer.setCustomName(net.minecraft.network.chat.Component.literal("Maud Glover"));
            passer.moveTo(bake.getX() + 0.5, bake.getY(), bake.getZ() + 1.0, 0, 0);
            passer.setNoAi(true);
            level.addFreshEntity(passer);
            made.add(passer);
            int sent = net.aiagent256.stormhold.holding.Shops.sweep(level, h);
            if (sent != 3) throw new IllegalStateException(sent + " sent away, not 3");
            for (BlockPos p : posts) {
                int left = level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, new net.minecraft.world.phys.AABB(p).inflate(1.5),
                        x -> x.isAlive() && x != passer).size();
                if (left != 1) throw new IllegalStateException(left + " keepers left at " + p);
            }
            if (!passer.isAlive()) throw new IllegalStateException("a passer-by was sent away");
            if (net.aiagent256.stormhold.holding.Shops.sweep(level, h) != 0) throw new IllegalStateException("a second sweep sent more away");
        } finally {
            for (var v : made) v.discard();
        }
        g.succeed();
    }

    /** A raid that waited at the walls comes up outside the gate when you arrive, not by the banner. */
    @GameTest(template = "arena")
    public static void aWaitingRaidComesUpOutsideTheGate(GameTestHelper g) {
        HoldingManager mgr = HoldingManager.get(g.getLevel());
        Holding h = sample(g);
        h.owner = Sides.player(UUID.randomUUID());
        mgr.holdings.put(h.id, h);
        try {
            Campaign c = new Campaign();
            c.kind = Campaign.Kind.RAID;
            c.target = h.id;
            c.to = h.bannerPos;
            c.from = h.bannerPos.offset(110, 0, 0);
            c.progress = 1.0;
            BlockPos at = Campaigns.raidArrival(mgr, c);
            var box = h.box;
            if (at.getX() >= box.minX() && at.getX() <= box.maxX() && at.getZ() >= box.minZ() && at.getZ() <= box.maxZ())
                throw new IllegalStateException("the raid comes up inside the walls at " + at);
            c.rammed = true;
            if (!Campaigns.raidArrival(mgr, c).equals(c.position())) throw new IllegalStateException("through a broken gate they should come on");
            c.rammed = false;
            c.progress = 0.3;
            if (!Campaigns.raidArrival(mgr, c).equals(c.position())) throw new IllegalStateException("a raid on the road was moved");
        } finally {
            mgr.holdings.remove(h.id);
        }
        g.succeed();
    }

    /** Your other castles send men to one under attack, and the ones still standing go home after. */
    @GameTest(template = "arena")
    public static void castlesSendMenToOneUnderAttack(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        Holding target = sample(g), near = sample(g), far = sample(g);
        near.key = target.key + "/near";
        far.key = target.key + "/far";
        near.name = "Nearhold";
        far.name = "Farhold";
        // the arena's garrisons stand where the test does: the others are put far off on the map by their boxes
        BlockPos c = target.center();
        near.box = new BoundingBox(c.getX() + 600, c.getY(), c.getZ(), c.getX() + 615, c.getY() + 7, c.getZ() + 15);
        far.box = new BoundingBox(c.getX() + 5000, c.getY(), c.getZ(), c.getX() + 5015, c.getY() + 7, c.getZ() + 15);
        for (Holding x : List.of(target, near, far)) {
            x.owner = Sides.player(me);
            x.live.clear();
            mgr.holdings.put(x.id, x);
        }
        near.garrison.put(Role.KNIGHT, 6);
        near.garrison.put(Role.ARCHER, 4);
        far.garrison.put(Role.KNIGHT, 6);
        int k0 = target.count(Role.KNIGHT), a0 = target.count(Role.ARCHER);
        int k = Math.min(1 + near.tier, 6 - net.aiagent256.stormhold.holding.Relief.KEEP), a = Math.min((1 + near.tier) / 2, 4 - net.aiagent256.stormhold.holding.Relief.KEEP);
        try {
            net.aiagent256.stormhold.holding.Relief.send(level, mgr, target);
            if (near.count(Role.KNIGHT) != 6 - k || near.count(Role.ARCHER) != 4 - a)
                throw new IllegalStateException("Nearhold sent " + (6 - near.count(Role.KNIGHT)) + " knights and " + (4 - near.count(Role.ARCHER)) + " archers, not " + k + " and " + a);
            if (far.count(Role.KNIGHT) != 6) throw new IllegalStateException("a castle 5000 blocks off sent men");
            if (target.count(Role.KNIGHT) != k0 + k || target.count(Role.ARCHER) != a0 + a) throw new IllegalStateException("the men didn't arrive");
            // the attack's over; the garrison lost half its knights: half of those sent go home
            int after = target.count(Role.KNIGHT);
            target.add(Role.KNIGHT, -(after - after / 2));
            net.aiagent256.stormhold.holding.Relief.over(level, mgr, target);
            int back = (int) Math.floor(k * ((after / 2) / (double) after));
            if (near.count(Role.KNIGHT) != 6 - k + back) throw new IllegalStateException(near.count(Role.KNIGHT) - (6 - k) + " knights came home, not " + back);
            if (near.count(Role.ARCHER) != 4) throw new IllegalStateException("the archers didn't come home");
            if (!target.relief.isEmpty()) throw new IllegalStateException("the relief is still on the roll");
        } finally {
            for (Holding x : List.of(target, near, far)) {
                for (UUID id : x.live.keySet()) if (level.getEntity(id) instanceof GarrisonEntity ge) ge.discard();
                mgr.holdings.remove(x.id);
            }
        }
        g.succeed();
    }

    /** Men sent home ride to their castle on the clock and join its garrison; a lost home sends them to another of yours. */
    @GameTest(template = "arena")
    public static void theCompanyRidesHome(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var player = g.makeMockPlayer();
        Holding home = sample(g), other = sample(g);
        other.key = home.key + "/other";
        other.name = "Otherhold";
        home.owner = Sides.player(player.getUUID());
        other.owner = home.owner;
        mgr.holdings.put(home.id, home);
        mgr.holdings.put(other.id, other);
        try {
            int k0 = home.count(Role.KNIGHT);
            var men = new java.util.EnumMap<Role, Integer>(Role.class);
            men.put(Role.KNIGHT, 3);
            men.put(Role.ARCHER, 1);
            BlockPos at = home.center().offset(200, 0, 0);
            Campaign c = net.aiagent256.stormhold.holding.Columns.home(mgr, player, home, at, men);
            int secs = (int) Math.ceil(c.length() / net.aiagent256.stormhold.holding.Columns.SPEED);
            for (int i = 0; i < secs - 1; i++) Campaigns.tickOne(level, mgr, c);
            if (c.stage == Campaign.Stage.DONE || home.count(Role.KNIGHT) != k0) throw new IllegalStateException("home before " + secs + " seconds");
            Campaigns.tickOne(level, mgr, c);
            Campaigns.tickOne(level, mgr, c);
            if (c.stage != Campaign.Stage.DONE || home.count(Role.KNIGHT) != k0 + 3) throw new IllegalStateException("not home after " + secs + " seconds");
            // home falls while they ride: they go to the other castle
            int o0 = other.count(Role.KNIGHT);
            Campaign d = net.aiagent256.stormhold.holding.Columns.home(mgr, player, home, at, men);
            home.owner = Sides.house("testhouse");
            for (int i = 0; i < 400 && d.stage != Campaign.Stage.DONE; i++) Campaigns.tickOne(level, mgr, d);
            if (other.count(Role.KNIGHT) != o0 + 3) throw new IllegalStateException("with home lost they didn't go to the other castle");
        } finally {
            mgr.holdings.remove(home.id);
            mgr.holdings.remove(other.id);
            mgr.campaigns.removeIf(x -> home.id.equals(x.target) || other.id.equals(x.target));
        }
        g.succeed();
    }

    /** The Attack page's plan: each castle's war party, its kit, and the chance; the castles known near yours, nearest first. */
    @GameTest(template = "arena")
    public static void theAttackPageShowsWhoWouldGoAndTheChance(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        Holding a = sample(g), b = sample(g), enemy = sample(g), far = sample(g);
        b.key = a.key + "/b";
        enemy.key = a.key + "/e";
        far.key = a.key + "/f";
        enemy.name = "Foehold";
        far.name = "Farfoe";
        BlockPos c = a.center();
        enemy.box = new BoundingBox(c.getX() + 300, c.getY(), c.getZ(), c.getX() + 315, c.getY() + 7, c.getZ() + 15);
        enemy.bannerPos = enemy.box.getCenter();
        far.box = new BoundingBox(c.getX() + 9000, c.getY(), c.getZ(), c.getX() + 9015, c.getY() + 7, c.getZ() + 15);
        a.owner = Sides.player(me);
        b.owner = a.owner;
        enemy.greeted.add(me);
        far.greeted.add(me);
        for (Holding x : List.of(a, b, enemy, far)) mgr.holdings.put(x.id, x);
        try {
            var t = net.aiagent256.stormhold.holding.WarPlan.plan(level, mgr, me, enemy);
            if (t.parts().size() != 2) throw new IllegalStateException(t.parts().size() + " war parties, not 2");
            int knights = Campaigns.sends(a, Role.KNIGHT) + Campaigns.sends(b, Role.KNIGHT);
            int got = 0;
            for (var p : t.parts()) got += p.men()[1];
            if (got != knights) throw new IllegalStateException(got + " knights in the plan, not " + knights);
            if (t.pct() < 0 || t.pct() > 100) throw new IllegalStateException("chance " + t.pct());
            if (t.parts().get(0).seconds() < 300 / 3 - 10) throw new IllegalStateException("a 300-block march in " + t.parts().get(0).seconds() + "s");
            if (net.aiagent256.stormhold.holding.WarPlan.chance(2, 1) != 100 || net.aiagent256.stormhold.holding.WarPlan.chance(1, 2) != 0)
                throw new IllegalStateException("the odds sum is off");
            int even = net.aiagent256.stormhold.holding.WarPlan.chance(1, 1);
            if (even < 45 || even > 55) throw new IllegalStateException("an even fight is " + even + "%");
            var list = net.aiagent256.stormhold.holding.WarPlan.targets(level, mgr, me);
            boolean foe = false, farOne = false;
            for (var x : list) {
                if (x.id().equals(enemy.id)) foe = true;
                if (x.id().equals(far.id)) farOne = true;
            }
            if (!foe) throw new IllegalStateException("the castle next door isn't listed");
            if (farOne) throw new IllegalStateException("a castle 9000 blocks off is listed");
            if (!net.aiagent256.stormhold.holding.WarPlan.men(new int[]{1, 5, 2, 0}).equals("1 captain, 5 knights, 2 archers"))
                throw new IllegalStateException("men read " + net.aiagent256.stormhold.holding.WarPlan.men(new int[]{1, 5, 2, 0}));
        } finally {
            for (Holding x : List.of(a, b, enemy, far)) mgr.holdings.remove(x.id);
        }
        g.succeed();
    }

    /** A white pup: meat tames it (one to a lord), kills raise it, barding arms it, a fall wounds it, the whistle calls it and sends it off. */
    @GameTest(template = "arena", timeoutTicks = 200)
    public static void aDirewolfGrowsWearsBardingAndComesAtTheWhistle(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager book = HoldingManager.get(level.getServer().overworld());
        var player = g.makeMockPlayer();
        var at = g.absoluteVec(new net.minecraft.world.phys.Vec3(8, 2, 8));
        player.moveTo(at.x, at.y, at.z);
        List<Entity> spawned = new ArrayList<>();
        try {
            var pup = net.aiagent256.stormhold.holding.Direwolves.pup(level, g.absolutePos(new BlockPos(4, 2, 4)));
            if (pup == null) throw new IllegalStateException("no pup");
            spawned.add(pup);
            if (pup.isTame() || pup.wolfLevel() != 1 || pup.getMaxHealth() != 40) throw new IllegalStateException("pup: tame " + pup.isTame()
                    + ", level " + pup.wolfLevel() + ", health " + pup.getMaxHealth());
            var r = net.aiagent256.stormhold.holding.Direwolves.tame(level, pup, player);
            if (!pup.isTame() || !player.getUUID().equals(pup.getOwnerUUID())) throw new IllegalStateException("not tamed");
            if (pup.getMaxHealth() != 40) throw new IllegalStateException("taming set its health to " + pup.getMaxHealth());
            if (!player.getInventory().contains(new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.DIREWOLF_WHISTLE.get())))
                throw new IllegalStateException("no whistle");
            // one to a lord
            var other = net.aiagent256.stormhold.holding.Direwolves.pup(level, g.absolutePos(new BlockPos(10, 2, 10)));
            spawned.add(other);
            var beef = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BEEF, 16);
            for (int i = 0; i < 8; i++)
                net.aiagent256.stormhold.holding.Direwolves.interact(level, other, player, net.minecraft.world.InteractionHand.MAIN_HAND, beef);
            if (other.isTame()) throw new IllegalStateException("a second pup came to a lord with one already");
            // kills: a zombie is five; twenty to the second level
            var zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
            for (int i = 0; i < 4; i++) net.aiagent256.stormhold.holding.Direwolves.kill(level, pup, zombie);
            if (r.level != 2 || r.xp != 0) throw new IllegalStateException("after four zombies level " + r.level + ", " + r.xp + " learned");
            if (pup.getMaxHealth() != 48 || pup.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != 7)
                throw new IllegalStateException("level 2: health " + pup.getMaxHealth() + ", bite "
                        + pup.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
            if (Math.abs(pup.getBbWidth() - 0.6F * net.aiagent256.stormhold.entity.DirewolfEntity.scale(2)) > 0.01)
                throw new IllegalStateException("level 2 width " + pup.getBbWidth());
            for (int i = 0; i < 400 && r.level < 10; i++) net.aiagent256.stormhold.holding.Direwolves.kill(level, pup, zombie);
            if (r.level != 10 || pup.wolfLevel() != 10) throw new IllegalStateException("never full grown: " + r.level);
            // barding on, swapped, off
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.BARDING_IRON.get()));
            net.aiagent256.stormhold.holding.Direwolves.interact(level, pup, player, net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem());
            if (pup.barding() != 1 || r.barding != 1 || pup.getArmorValue() != 8) throw new IllegalStateException("iron: " + pup.barding() + ", armour " + pup.getArmorValue());
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.BARDING_DIAMOND.get()));
            net.aiagent256.stormhold.holding.Direwolves.interact(level, pup, player, net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem());
            if (pup.barding() != 2 || pup.getArmorValue() != 12) throw new IllegalStateException("diamond: armour " + pup.getArmorValue());
            if (!player.getInventory().contains(new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.BARDING_IRON.get())))
                throw new IllegalStateException("the iron barding wasn't given back");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            player.setShiftKeyDown(true);
            net.aiagent256.stormhold.holding.Direwolves.interact(level, pup, player, net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem());
            player.setShiftKeyDown(false);
            if (pup.barding() != 0 || pup.getArmorValue() != 0) throw new IllegalStateException("barding not off");
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.aiagent256.stormhold.registry.ModItems.BARDING_DIAMOND.get()));
            net.aiagent256.stormhold.holding.Direwolves.interact(level, pup, player, net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem());
            // saved and loaded, it keeps its level, barding and wounds
            pup.setHealth(30);
            CompoundTag saved = new CompoundTag();
            pup.saveWithoutId(saved);
            var copy = net.aiagent256.stormhold.registry.ModEntities.DIREWOLF.get().create(level);
            copy.load(saved);
            if (copy.wolfLevel() != 10 || copy.barding() != 2 || Math.abs(copy.getHealth() - 30) > 0.01 || copy.getMaxHealth() != 112)
                throw new IllegalStateException("loaded: level " + copy.wolfLevel() + ", barding " + copy.barding() + ", health " + copy.getHealth()
                        + " of " + copy.getMaxHealth());
            if (net.aiagent256.stormhold.holding.Direwolves.stale(level, copy)) throw new IllegalStateException("its own record calls it stale");
            // brought down: wounded, not dead, and the whistle won't call it yet
            pup.hurt(level.damageSources().generic(), 10000);
            if (!pup.isRemoved()) throw new IllegalStateException("still standing");
            if (r.entity != null || r.out || r.woundedUntil <= level.getGameTime()) throw new IllegalStateException("not wounded: " + r.woundedUntil);
            if (r.level != 10 || r.barding != 2) throw new IllegalStateException("the fall cost it its learning");
            net.aiagent256.stormhold.holding.Direwolves.whistle(level, player);
            if (r.entity != null) throw new IllegalStateException("came while wounded");
            r.woundedUntil = 0;
            net.aiagent256.stormhold.holding.Direwolves.whistle(level, player);
            if (r.entity == null || !r.out || !(level.getEntity(r.entity) instanceof net.aiagent256.stormhold.entity.DirewolfEntity back))
                throw new IllegalStateException("the whistle didn't call it");
            spawned.add(back);
            if (back.wolfLevel() != 10 || back.barding() != 2 || !back.isTame()) throw new IllegalStateException("came back as level " + back.wolfLevel());
            if (back.distanceToSqr(player) > 9 * 9) throw new IllegalStateException("came to " + back.blockPosition() + ", not the lord");
            net.aiagent256.stormhold.holding.Direwolves.whistle(level, player);
            if (!back.isRemoved() || r.out) throw new IllegalStateException("the whistle at its side didn't send it off");
        } finally {
            book.direwolves.remove(player.getUUID());
            for (Entity e : spawned) if (!e.isRemoved()) e.discard();
        }
        g.succeed();
    }

    /** Vassals answer the call: their war parties count on the Attack page, the banners ride to you, a disloyal one stays home. */
    @GameTest(template = "arena")
    public static void vassalsAnswerTheCallAndTheBannersRideToYou(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        var me = net.minecraftforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "banners_test"));
        Holding a = sample(g), b = sample(g), c = sample(g), enemy = sample(g);
        b.key = a.key + "/b";
        c.key = a.key + "/c";
        enemy.key = a.key + "/e";
        b.name = "Vassalhold";
        c.name = "Faithless";
        enemy.name = "Foehold";
        BlockPos o = a.center();
        enemy.box = new BoundingBox(o.getX() + 300, o.getY(), o.getZ(), o.getX() + 315, o.getY() + 7, o.getZ() + 15);
        enemy.bannerPos = enemy.box.getCenter();
        for (Holding x : List.of(a, b, c)) x.owner = Sides.player(me.getUUID());
        b.vassal = "Hugh";
        b.lordName = "Castellan Hugh";
        b.loyalty = 90;
        c.vassal = "Wat";
        c.lordName = "Castellan Wat";
        c.loyalty = 10;
        for (Holding x : List.of(a, b, c, enemy)) mgr.holdings.put(x.id, x);
        try {
            var t = net.aiagent256.stormhold.holding.WarPlan.plan(level, mgr, me.getUUID(), enemy);
            if (t.parts().size() != 2) throw new IllegalStateException(t.parts().size() + " war parties, not 2 (the loyal vassal marches)");
            if (t.parts().stream().noneMatch(p -> p.castle().contains("(Hugh)"))) throw new IllegalStateException("the vassal's party isn't named");
            if (!t.note().contains("Castellan Wat")) throw new IllegalStateException("the disloyal vassal isn't named: " + t.note());
            // the banners: a and b ride to you, c stays home
            int ak = a.count(Role.KNIGHT), bk = b.count(Role.KNIGHT), ck = c.count(Role.KNIGHT);
            int sendA = Campaigns.sends(a, Role.KNIGHT), sendB = Campaigns.sends(b, Role.KNIGHT);
            int n = net.aiagent256.stormhold.holding.Banners.call(me);
            if (n <= 0) throw new IllegalStateException("nobody rode");
            if (a.count(Role.KNIGHT) != ak - sendA || b.count(Role.KNIGHT) != bk - sendB) throw new IllegalStateException("knights left: "
                    + a.count(Role.KNIGHT) + " of " + ak + " (sends " + sendA + "), " + b.count(Role.KNIGHT) + " of " + bk + " (sends " + sendB + ")");
            if (c.count(Role.KNIGHT) != ck) throw new IllegalStateException("the faithless vassal sent men");
            int columns = 0;
            for (Campaign x : mgr.campaigns) {
                if (x.kind != Campaign.Kind.COLUMN || !me.getUUID().equals(x.player)) continue;
                if (!x.muster) throw new IllegalStateException("a banner column isn't marked as a host");
                columns++;
            }
            if (columns != 2) throw new IllegalStateException(columns + " columns, not 2");
        } finally {
            for (Holding x : List.of(a, b, c, enemy)) mgr.holdings.remove(x.id);
            mgr.campaigns.removeIf(x -> me.getUUID().equals(x.player));
        }
        g.succeed();
    }

    /** The realm's diamonds go to the worst-armed castle first; a lord with no seat gets his largest; the market sells the surplus. */
    @GameTest(template = "arena")
    public static void theForgeServesTheWorstArmedFirstAndTheMarketSells(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        Holding low = sample(g), high = sample(g), vassal = sample(g);
        high.key = low.key + "/high";
        vassal.key = low.key + "/vassal";
        low.name = "Lowkit";
        high.name = "Highkit";
        for (Holding x : List.of(low, high, vassal)) {
            x.owner = Sides.player(me);
            x.smiths = x.smithsAt = 8;
        }
        vassal.vassal = "Hugh";
        vassal.add(Role.KNIGHT, 40);                 // the biggest, but not his to sit in
        low.renown = 3;
        high.forged = 5;
        high.add(Role.KNIGHT, 5);
        var diamond = new net.minecraft.resources.ResourceLocation("minecraft", "diamond");
        var iron = new net.minecraft.resources.ResourceLocation("minecraft", "iron_ingot");
        var wheat = new net.minecraft.resources.ResourceLocation("minecraft", "wheat");
        var bread = new net.minecraft.resources.ResourceLocation("minecraft", "bread");
        high.addStock(diamond, 12);
        high.addStock(iron, 16);
        for (Holding x : List.of(low, high, vassal)) mgr.holdings.put(x.id, x);
        try {
            // the seat
            if (!net.aiagent256.stormhold.holding.Seats.ensure(level, mgr, me)) throw new IllegalStateException("no seat named");
            if (mgr.seatOf(me) != high) throw new IllegalStateException("seat is " + (mgr.seatOf(me) == null ? "none" : mgr.seatOf(me).name));
            if (net.aiagent256.stormhold.holding.Seats.ensure(level, mgr, me)) throw new IllegalStateException("named a second seat");
            // the forge
            int lowLevel = net.aiagent256.stormhold.holding.Frontier.level(level, low);
            if (lowLevel == 3) {
                net.aiagent256.stormhold.holding.Labour.forge(level, mgr, high, 8);
                if (high.forgePaid || high.stock(diamond) != 12) throw new IllegalStateException("the better-armed castle took the diamonds");
                net.aiagent256.stormhold.holding.Labour.forge(level, mgr, low, 1);   // one smith-day: paid, not yet done
                if (!low.forgePaid || low.forgeTarget != 4) throw new IllegalStateException("the worst-armed castle didn't get them");
                if (high.stock(diamond) != 0) throw new IllegalStateException(high.stock(diamond) + " diamonds left");
            } else {
                Stormhold.LOGGER.info("Forge order test skipped: the test castle stands in zone level {}", lowLevel);
            }
            // market day: wheat past the last 64 sells at 20 a emerald, bread and iron never
            high.addStock(wheat, 3999);
            high.addStock(bread, 1103);
            int ironBefore = high.stock(iron), em = high.stock(net.aiagent256.stormhold.holding.Rewards.EMERALD);
            var sold = new java.util.LinkedHashMap<String, Integer>();
            int got = net.aiagent256.stormhold.holding.Surplus.sell(high, sold);
            if (got != (3999 - 64) / 20) throw new IllegalStateException("sold for " + got + ", not " + (3999 - 64) / 20);
            if (high.stock(wheat) != 3999 - got * 20 || high.stock(wheat) < 64) throw new IllegalStateException("wheat left " + high.stock(wheat));
            if (high.stock(bread) != 1103 || high.stock(iron) != ironBefore) throw new IllegalStateException("sold bread or iron");
            if (high.stock(net.aiagent256.stormhold.holding.Rewards.EMERALD) != em + got) throw new IllegalStateException("the emeralds didn't reach the stores");
        } finally {
            for (Holding x : List.of(low, high, vassal)) mgr.holdings.remove(x.id);
            mgr.seats.remove(me);
        }
        g.succeed();
    }

    /** An order left from a castle's old lord never sends its men against your own; Defend turns a party back; taking a castle wipes its orders. */
    @GameTest(template = "arena")
    public static void oldOrdersDontMarchOnYourOwnCastles(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        UUID me = UUID.randomUUID();
        Holding a = sample(g), b = sample(g), enemy = sample(g);
        b.key = a.key + "/b";
        enemy.key = a.key + "/e";
        BlockPos o = a.center();
        b.box = new BoundingBox(o.getX() + 300, o.getY(), o.getZ(), o.getX() + 315, o.getY() + 7, o.getZ() + 15);
        enemy.box = new BoundingBox(o.getX() + 600, o.getY(), o.getZ(), o.getX() + 615, o.getY() + 7, o.getZ() + 15);
        enemy.bannerPos = enemy.box.getCenter();
        a.owner = Sides.player(me);
        b.owner = a.owner;
        for (Holding x : List.of(a, b, enemy)) mgr.holdings.put(x.id, x);
        try {
            a.attackTarget = b.center();
            int k = a.count(Role.KNIGHT);
            if (Campaigns.launchParty(level, mgr, a, b.center()) != null || a.count(Role.KNIGHT) != k)
                throw new IllegalStateException("a war party marched on its own side's castle");
            if (a.attackTarget != null) throw new IllegalStateException("the old order still stands");
            Campaign c = Campaigns.launchParty(level, mgr, a, enemy.center());
            if (c == null) throw new IllegalStateException("no war party against the enemy");
            if (Campaigns.recall(level, mgr, a) != c.total() || c.stage != Campaign.Stage.RETURN)
                throw new IllegalStateException("Defend didn't turn the party back: " + c.stage);
            enemy.attackTarget = a.center();
            enemy.mode = Holding.Mode.ATTACK;
            HoldingEvents.transfer(level, mgr, enemy, Sides.player(me), true);
            if (enemy.attackTarget != null || enemy.mode != Holding.Mode.DEFEND) throw new IllegalStateException("the taken castle kept its old orders");
        } finally {
            for (Holding x : List.of(a, b, enemy)) mgr.holdings.remove(x.id);
            mgr.campaigns.removeIf(x -> a.id.equals(x.origin));
            mgr.seats.remove(me);
        }
        g.succeed();
    }

    /** Ten men don't all go for the same two: each takes the nearest foe with room on him, a third man finds his own, friends give elbow room. */
    @GameTest(template = "arena", timeoutTicks = 100)
    public static void aFightSpreadsIntoPairs(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        List<GarrisonEntity> a = new ArrayList<>(), b = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                for (int s = 0; s < 2; s++) {
                    GarrisonEntity e = net.aiagent256.stormhold.registry.ModEntities.typeFor(Role.KNIGHT, true).create(level);
                    if (e == null) throw new IllegalStateException("no knight");
                    var at = g.absoluteVec(new net.minecraft.world.phys.Vec3(4.5 + i * 2, 2, s == 0 ? 3.5 : 12.5));
                    e.moveTo(at.x, at.y, at.z, s == 0 ? 0 : 180, 0);
                    e.setup(s == 0 ? "house:duel_a" : "house:duel_b", null, 1, net.minecraft.world.item.DyeColor.RED, null, false);
                    level.addFreshEntity(e);
                    (s == 0 ? a : b).add(e);
                }
            }
            if (!a.get(0).isHostileTo(b.get(0))) throw new IllegalStateException("the two sides aren't enemies");
            for (GarrisonEntity e : a) e.setTarget(net.aiagent256.stormhold.entity.ai.Engagement.pick(e, b));
            java.util.Map<GarrisonEntity, Integer> on = new java.util.HashMap<>();
            for (GarrisonEntity e : a) if (e.getTarget() instanceof GarrisonEntity t) on.merge(t, 1, Integer::sum);
            int most = on.values().stream().max(Integer::compare).orElse(0);
            if (most > net.aiagent256.stormhold.entity.ai.Engagement.MOST) throw new IllegalStateException(most + " men on one foe");
            if (on.size() < 3) throw new IllegalStateException("four men took only " + on.size() + " foes");
            // three on one, a foe standing free: the third goes for the free one
            for (int i = 0; i < 3; i++) a.get(i).setTarget(b.get(0));
            a.get(3).setTarget(b.get(1));
            net.aiagent256.stormhold.entity.ai.Engagement.rebalance(a.get(2));
            if (a.get(2).getTarget() == b.get(0)) throw new IllegalStateException("the third man stayed on the crowded foe");
            // two friends on one spot step apart
            a.get(1).setPos(a.get(0).getX() + 0.2, a.get(0).getY(), a.get(0).getZ());
            if (net.aiagent256.stormhold.entity.ai.Engagement.elbowRoom(a.get(0)).lengthSqr() <= 0) throw new IllegalStateException("no elbow room");
        } finally {
            for (GarrisonEntity e : a) e.discard();
            for (GarrisonEntity e : b) e.discard();
        }
        g.succeed();
    }

    /** The Master Builder sets the work within reach of where he stands, tool in hand, and walks to the rest; the smiths find the anvil. */
    @GameTest(template = "arena")
    public static void theBuilderWorksWhereHeStandsAndTheSmithsFindTheAnvil(GameTestHelper g) {
        ServerLevel level = g.getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = sample(g);
        h.owner = Sides.player(UUID.randomUUID());
        h.builderName = "Testwright";
        h.warTablePos = g.absolutePos(new BlockPos(3, 2, 3));
        var m = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
        if (m == null) throw new IllegalStateException("no villager");
        var at = g.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2, 3.5));
        m.moveTo(at.x, at.y, at.z);
        m.setCustomName(net.minecraft.network.chat.Component.literal("Master Builder Testwright"));
        level.addFreshEntity(m);
        try {
            if (net.aiagent256.stormhold.holding.Builder.master(level, h) != m) throw new IllegalStateException("the builder isn't known by his name");
            BlockPos near = g.absolutePos(new BlockPos(6, 2, 6));
            BlockPos far = near.offset(60, 0, 0);
            h.work = net.aiagent256.stormhold.holding.Builder.REPAIR;
            h.workQueue = new java.util.ArrayDeque<>();
            h.workQueue.add(new net.aiagent256.stormhold.holding.Builder.Job(far, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), true));
            h.workQueue.add(new net.aiagent256.stormhold.holding.Builder.Job(near, net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), true));
            int placed = net.aiagent256.stormhold.holding.Builder.beside(level, mgr, h, m, 8);
            if (placed != 1 || !level.getBlockState(near).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS))
                throw new IllegalStateException("the block beside him wasn't set (" + placed + ")");
            if (level.getBlockState(far).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) throw new IllegalStateException("a block 60 off was set");
            if (!m.getMainHandItem().is(net.minecraft.world.item.Items.STONE_BRICKS)) throw new IllegalStateException("no stone in his hand");
            if (h.workQueue.size() != 1) throw new IllegalStateException(h.workQueue.size() + " jobs left, not 1");
            net.aiagent256.stormhold.holding.Builder.beside(level, mgr, h, m, 8);
            if (m.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET).isEmpty())
                throw new IllegalStateException("he didn't set off for the far work");
            // the smiths' anvil
            BlockPos anvil = g.absolutePos(new BlockPos(9, 2, 9));
            level.setBlock(anvil, net.minecraft.world.level.block.Blocks.ANVIL.defaultBlockState(), 3);
            BlockPos found = net.aiagent256.stormhold.holding.Work.forge(level, h, level.getGameTime());
            if (!anvil.equals(found)) throw new IllegalStateException("the smiths looked for the anvil and found " + found);
        } finally {
            m.discard();
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
