package net.aiagent256.stormhold.event;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.command.StormholdCommand;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.holding.DataRegistries;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.aiagent256.stormhold.holding.HoldingTicker;
import net.aiagent256.stormhold.holding.Roads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Stormhold.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {
    public static final TagKey<Block> SIEGE_BREAKABLE = BlockTags.create(Stormhold.id("siege_breakable"));
    public static final TagKey<Block> SIEGE_PLACEABLE = BlockTags.create(Stormhold.id("siege_placeable"));
    private static final Map<UUID, Long> LAST_WARNING = new HashMap<>();

    @SubscribeEvent
    public static void levelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        try {
            HoldingTicker.tick(level);
        } catch (Exception ex) {
            Stormhold.LOGGER.error("Stormhold tick failed", ex);
        }
    }

    /** Remember where a player stood before a portal, so their men can be found and brought through. */
    private static final Map<UUID, BlockPos> LAST_POS = new HashMap<>();

    @SubscribeEvent
    public static void playerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent e) {
        if (e.phase == net.minecraftforge.event.TickEvent.Phase.END && e.player instanceof ServerPlayer sp && sp.tickCount % 10 == 0) {
            LAST_POS.put(sp.getUUID(), sp.blockPosition());
            if (sp.tickCount % 40 == 0) net.aiagent256.stormhold.holding.Frontier.watch(sp);
        }
    }

    /** A player's pets and soldiers who aren't his enemies can't hurt each other: accidents start no fights. */
    @SubscribeEvent
    public static void accident(net.minecraftforge.event.entity.living.LivingAttackEvent e) {
        if (e.getEntity().level() instanceof ServerLevel level
                && net.aiagent256.stormhold.holding.Allies.spared(level, e.getSource().getEntity(), e.getEntity()))
            e.setCanceled(true);
    }

    /** Every hostile mob, bosses too, is made as hard as the frontier zone it joins the world in. */
    @SubscribeEvent
    public static void joined(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof net.minecraft.world.entity.Mob mob)) return;
        if (net.aiagent256.stormhold.holding.Warbands.stray(level, mob)) {
            e.setCanceled(true);                        // a warband man saved with the world: his warband brings him up itself
            return;
        }
        net.aiagent256.stormhold.holding.Frontier.onJoin(level, mob);
    }

    /** A warband man sent up a ladder climbs it. */
    @SubscribeEvent
    public static void livingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent e) {
        if (e.getEntity() instanceof net.minecraft.world.entity.raid.Raider r && !r.level().isClientSide)
            net.aiagent256.stormhold.holding.Warbands.climb(r);
    }

    @SubscribeEvent
    public static void changedDimension(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        ServerLevel from = sp.getServer().getLevel(e.getFrom()), to = sp.getServer().getLevel(e.getTo());
        BlockPos left = LAST_POS.get(sp.getUUID());
        if (from == null || to == null || left == null) return;
        // a tick later, so the player is actually standing in the new world
        to.getServer().execute(() -> net.aiagent256.stormhold.holding.Company.followThrough(from, to, sp, left));
    }

    /** A child born in a fief gets a name and a line in the record. */
    @SubscribeEvent
    public static void born(net.minecraftforge.event.entity.living.BabyEntitySpawnEvent e) {
        if (!(e.getChild() instanceof net.minecraft.world.entity.npc.Villager child) || !(e.getParentA().level() instanceof ServerLevel level)) return;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = net.aiagent256.stormhold.holding.Fief.peopleOf(mgr, e.getParentA().blockPosition());
        if (h == null) return;
        net.aiagent256.stormhold.holding.Fief.name(level, child);
        String parent = e.getParentA() instanceof net.minecraft.world.entity.npc.Villager pv ? net.aiagent256.stormhold.holding.Fief.nameOf(pv) : "one of the people";
        net.aiagent256.stormhold.holding.Chronicle.log(level, mgr, net.aiagent256.stormhold.holding.Chronicle.GROWTH,
                "A child is born at " + h.name + " to " + parent + ": " + net.aiagent256.stormhold.holding.Fief.nameOf(child) + ".", h.ownerPlayer());
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOW)
    public static void drops(net.minecraftforge.event.entity.living.LivingDropsEvent e) {
        net.aiagent256.stormhold.holding.Spoils.onDrops(e);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent e) {
        if (e.getSource().getEntity() instanceof GarrisonEntity killer && killer.owner() != null && !killer.level().isClientSide
                && !(e.getEntity() instanceof net.minecraft.world.entity.player.Player)) killer.scored();
        if (e.getEntity() instanceof GarrisonEntity g && !g.level().isClientSide) HoldingEvents.onDeath(g);
        if (e.getEntity().level() instanceof ServerLevel wl && e.getEntity().getPersistentData().hasUUID(net.aiagent256.stormhold.holding.Warbands.TAG))
            net.aiagent256.stormhold.holding.Warbands.onDeath(wl, e.getEntity());
        if (e.getEntity().level() instanceof ServerLevel dl && net.aiagent256.stormhold.holding.Dragons.isDragon(e.getEntity()))
            net.aiagent256.stormhold.holding.Dragons.onDeath(dl, e.getEntity(), e.getSource().getEntity());
        if (e.getEntity() instanceof net.minecraft.world.entity.npc.Villager v && v.level() instanceof ServerLevel level) {
            HoldingManager mgr = HoldingManager.get(level);
            Holding h = net.aiagent256.stormhold.holding.Fief.peopleOf(mgr, v.blockPosition());
            if (h != null) {
                if (h.isHouseOwned() && e.getSource().getEntity() instanceof Player killer && !killer.isCreative()) {
                    net.aiagent256.stormhold.house.Standing.change(level, mgr, h.houseId, killer.getUUID(), -6, "you killed one of their people");
                }
                String by = e.getSource().getEntity() instanceof Player p ? p.getName().getString()
                        : e.getSource().getEntity() != null ? "a " + e.getSource().getEntity().getName().getString().toLowerCase() : e.getSource().getMsgId();
                net.aiagent256.stormhold.holding.Chronicle.log(level, mgr, net.aiagent256.stormhold.holding.Chronicle.NEWS,
                        net.aiagent256.stormhold.holding.Fief.nameOf(v) + " of " + h.name + " is dead, killed by " + by + ".", h.ownerPlayer());
            }
        }
        // killing monsters at a house's gates earns a little goodwill
        if (!(e.getEntity() instanceof GarrisonEntity) && e.getEntity() instanceof net.minecraft.world.entity.monster.Enemy
                && e.getEntity().level() instanceof ServerLevel level && e.getSource().getEntity() instanceof Player killer) {
            HoldingManager mgr = HoldingManager.get(level);
            if (mgr.holdings.isEmpty()) return;
            Holding h = mgr.at(e.getEntity().blockPosition(), 40);
            if (h != null && h.isHouseOwned() && h.lordAlive) {
                net.aiagent256.stormhold.house.Standing.change(level, mgr, h.houseId, killer.getUUID(),
                        net.aiagent256.stormhold.house.Standing.KILLED_ENEMY_NEAR_CASTLE, "you fought at " + h.name);
            }
        }
    }

    @SubscribeEvent
    public static void reload(AddReloadListenerEvent e) {
        e.addListener(new DataRegistries.LayoutListener());
        e.addListener(new DataRegistries.ThemeListener());
        e.addListener(new DataRegistries.FiefListener());
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent e) {
        StormholdCommand.register(e.getDispatcher());
    }

    @SubscribeEvent
    public static void chunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk c) {
            HoldingManager mgr = HoldingManager.get(level);
            if (!mgr.roads.isEmpty()) Roads.enqueue(level, c.getPos());
        }
    }

    // ------------------------------------------------------------ siege rules

    private static Holding houseHoldingAt(ServerLevel level, BlockPos pos) {
        return houseHoldingAt(level, pos, null);
    }

    private static Holding houseHoldingAt(ServerLevel level, BlockPos pos, @org.jetbrains.annotations.Nullable Player p) {
        if (!Config.PROTECT_HOUSE_CASTLES.get()) return null;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.at(pos, 1);
        if (h == null || !h.isHouseOwned()) return null;
        if (p != null && net.aiagent256.stormhold.holding.City.inOwnHouse(mgr, h, p, pos)) return null;
        return h;
    }

    private static void warn(Player p, String msg) {
        long now = p.level().getGameTime();
        Long last = LAST_WARNING.get(p.getUUID());
        if (last == null || now - last > 60) {
            LAST_WARNING.put(p.getUUID(), now);
            p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true);
        }
    }

    @SubscribeEvent
    public static void breakBlock(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getPlayer().isCreative()) return;
        Holding h = houseHoldingAt(level, e.getPos(), e.getPlayer());
        if (h == null) return;
        if (e.getState().is(SIEGE_BREAKABLE)) {
            h.damagedAt = level.getGameTime();
            return;
        }
        e.setCanceled(true);
        warn(e.getPlayer(), "The walls of " + h.name + " hold. Break the gate, climb, or blast the portcullis.");
    }

    @SubscribeEvent
    public static void placeBlock(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof Player p) || p.isCreative()) return;
        Holding h = houseHoldingAt(level, e.getPos(), p);
        if (h == null || e.getPlacedBlock().is(SIEGE_PLACEABLE)) return;
        e.setCanceled(true);
        warn(p, "Only ladders, scaffolding, torches and TNT can be placed while " + h.name + " holds out.");
    }

    @SubscribeEvent
    public static void explosion(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        HoldingManager mgr = HoldingManager.get(level);
        if (mgr.holdings.isEmpty()) return;
        e.getAffectedBlocks().removeIf(pos -> {
            Holding h = mgr.at(pos, 1);
            if (h == null) return false;
            h.damagedAt = level.getGameTime();
            BlockState s = level.getBlockState(pos);
            return !s.is(SIEGE_BREAKABLE);
        });
    }

    /** A shopkeeper of a house that holds you hostile won't open his stall to you. */
    @SubscribeEvent
    public static void shop(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!(e.getTarget() instanceof net.minecraft.world.entity.npc.Villager v)) return;
        if (net.aiagent256.stormhold.holding.Shops.refuses(level, sp, v)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        } else if (e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            net.aiagent256.stormhold.holding.Shops.welcome(level, sp, v);
        }
    }

    /** Every trade at a place's shops counts toward being a regular there. */
    @SubscribeEvent
    public static void traded(net.minecraftforge.event.entity.player.TradeWithVillagerEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp && sp.level() instanceof ServerLevel level
                && e.getAbstractVillager() instanceof net.minecraft.world.entity.npc.Villager v)
            net.aiagent256.stormhold.holding.Shops.traded(level, sp, v);
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        // a sign on a town's or a city's notice board reads the board
        if (e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND && e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp0
                && level.getBlockState(e.getPos()).getBlock() instanceof net.minecraft.world.level.block.SignBlock
                && net.aiagent256.stormhold.holding.City.readBoardSign(level, sp0, e.getPos())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (e.getEntity().isCreative()) return;
        if (e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND && e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                && level.getBlockState(e.getPos()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                && net.aiagent256.stormhold.holding.City.useDoor(level, sp, e.getPos())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (!Config.SEAL_TREASURY.get()) return;
        BlockEntity be = level.getBlockEntity(e.getPos());
        if (be == null) return;
        boolean container = be instanceof Container || be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent();
        if (!container) return;
        Holding h = HoldingManager.get(level).at(e.getPos(), 0);
        if (h == null) return;
        if (net.aiagent256.stormhold.holding.City.inOwnHouse(HoldingManager.get(level), h, e.getEntity(), e.getPos())) return;
        if (h.isHouseOwned() && h.lordAlive) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
            warn(e.getEntity(), "The stores of " + h.name + " are sealed while " + h.lordName + " lives.");
        } else if (h.bonusLeft > 0 && be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest
                && e.getPos().distSqr(h.lordPos) < 28 * 28 && e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            net.aiagent256.stormhold.holding.Treasure.fill(level, HoldingManager.get(level), h, chest, sp);
        } else if (h.isPlayerOwned() && !Sides.player(e.getEntity().getUUID()).equals(h.owner)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SuppressWarnings("unused")
    private static boolean isPlayer(Entity e) {
        return e instanceof ServerPlayer;
    }

    private CommonEvents() {
    }
}
