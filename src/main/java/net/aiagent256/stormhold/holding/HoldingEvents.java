package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.NameGen;
import net.aiagent256.stormhold.house.Standing;
import net.aiagent256.stormhold.item.WarBannerItem;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.aiagent256.stormhold.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Fighting, dying, claiming and changing hands. */
public final class HoldingEvents {

    // ------------------------------------------------------------ messages

    public static void tell(ServerLevel level, @Nullable UUID player, Component msg) {
        if (player == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(msg);
    }

    public static void announceNear(ServerLevel level, BlockPos at, int radius, Component msg) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(at) < (double) radius * radius) p.sendSystemMessage(msg);
        }
    }

    public static Component gold(String s) {
        return Component.literal(s).withStyle(ChatFormatting.GOLD);
    }

    public static String ownerName(ServerLevel level, HoldingManager mgr, String side) {
        UUID p = Sides.playerId(side);
        if (p != null) {
            ServerPlayer sp = level.getServer().getPlayerList().getPlayer(p);
            return sp != null ? sp.getGameProfile().getName() : "a rival lord";
        }
        House h = mgr.houseOf(side);
        return h != null ? h.title() : "brigands";
    }

    // ------------------------------------------------------------ combat hooks

    public static void onGarrisonHurt(GarrisonEntity g, LivingEntity attacker) {
        if (!(g.level() instanceof ServerLevel level) || g.holdingId() == null) return;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.holdings.get(g.holdingId());
        if (h == null) return;
        for (UUID id : h.live.keySet()) {
            Entity e = level.getEntity(id);
            if (e instanceof GarrisonEntity other && other != g && other.distanceToSqr(g) < 48 * 48) other.alarm(attacker);
        }
    }

    public static void onLordBloodied(GarrisonEntity lord) {
        if (!(lord.level() instanceof ServerLevel level) || lord.holdingId() == null) return;
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = mgr.holdings.get(lord.holdingId());
        if (h == null) return;
        Garrisons.reinforce(level, mgr, h, Role.KNIGHT, 2 + h.tier, lord.blockPosition(), lord.getTarget());
        announceNear(level, lord.blockPosition(), 64, gold(h.lordName + " calls his household guard!"));
        level.playSound(null, lord.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 4.0F, 1.0F);
    }

    public static void onDeath(GarrisonEntity g) {
        if (!(g.level() instanceof ServerLevel level)) return;
        HoldingManager mgr = HoldingManager.get(level);
        Role role = g.role();
        if (g.owner() != null) {
            tell(level, g.owner(), Component.literal(g.baseName() + " has fallen" + (g.kills() > 0 ? " after " + g.kills()
                    + (g.kills() == 1 ? " kill" : " kills") : "") + ". His kit lies where he died.").withStyle(net.minecraft.ChatFormatting.RED));
            if (GarrisonEntity.rankFor(g.kills()) >= 2)
                Chronicle.log(level, mgr, Chronicle.WAR, g.baseName() + " falls after " + g.kills() + " kills.", g.owner());
        }
        if (g.holdingId() != null) {
            Holding h = mgr.holdings.get(g.holdingId());
            if (h != null && h.live.remove(g.getUUID()) != null) {
                if (h.isHouseOwned() && g.getLastAttacker() instanceof Player killer) {
                    Standing.change(level, mgr, h.houseId, killer.getUUID(),
                            role == Role.LORD ? Standing.TOOK_CASTLE / 2 : Standing.KILLED_GUARD, "you killed their " + role.name().toLowerCase());
                }
                if (role == Role.LORD && Vassals.is(h)) {
                    Vassals.fallen(level, mgr, h);
                } else if (role == Role.LORD) {
                    h.lordAlive = false;
                    level.getServer().getPlayerList().broadcastSystemMessage(gold(h.lordName + " has fallen at " + h.name
                            + ". Raise a War Banner on the keep's banner stand to claim it."), false);
                } else {
                    h.add(role, -1);
                }
                mgr.setDirty();
            }
        }
        if (g.campaignId() != null) {
            for (Campaign c : mgr.campaigns) {
                if (c.id.equals(g.campaignId()) && c.live.remove(g.getUUID()) != null) {
                    c.add(role, -1);
                    mgr.setDirty();
                }
            }
        }
        if (g.shipmentId() != null) {
            Shipment s = null;
            for (Shipment x : mgr.shipments) if (x.id.equals(g.shipmentId())) s = x;
            if (s != null) {
                for (ItemStack st : s.cargo) level.addFreshEntity(new ItemEntity(level, g.getX(), g.getY() + 0.5, g.getZ(), st.copy()));
                mgr.shipments.remove(s);
                Holding from = mgr.holdings.get(s.origin);
                if (from != null) tell(level, from.ownerPlayer(), gold("A courier from " + from.name + " was killed on the road. The cargo lies where he fell."));
                mgr.setDirty();
            }
        }
    }

    // ------------------------------------------------------------ claiming

    @Nullable
    public static Holding byStand(HoldingManager mgr, BlockPos pos) {
        for (Holding h : mgr.holdings.values()) if (h.bannerPos.equals(pos)) return h;
        return null;
    }

    public static void useBannerStand(ServerLevel level, ServerPlayer player, BlockPos pos, ItemStack stack) {
        HoldingManager mgr = HoldingManager.get(level);
        Holding h = byStand(mgr, pos);
        if (h == null) {
            player.sendSystemMessage(Component.literal("This banner stand belongs to no castle."));
            return;
        }
        String me = Sides.player(player.getUUID());
        if (me.equals(h.owner)) {
            player.sendSystemMessage(gold(h.name + ": " + h.troops() + " soldiers. Use a War Table to command them."));
            return;
        }
        if (!stack.is(ModItems.WAR_BANNER.get())) {
            player.sendSystemMessage(Component.literal("Raise a War Banner here to claim " + h.name + "."));
            return;
        }
        if (h.isHouseOwned() && h.lordAlive) {
            player.sendSystemMessage(Component.literal(h.lordName + " still lives. Defeat him before you claim " + h.name + ".")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        mgr.playerColors.put(player.getUUID(), WarBannerItem.color(stack));
        if (h.isHouseOwned() && Standing.friendly(mgr, h.houseId, player.getUUID())) {
            // the house is friendly and its lord is dead: the garrison bends the knee
            level.getServer().getPlayerList().broadcastSystemMessage(gold("The garrison of " + h.name
                    + " bends the knee to " + player.getGameProfile().getName() + "."), false);
            transfer(level, mgr, h, me, true);
            return;
        }
        h.claimant = player.getUUID();
        if (!me.equals(h.contestSide)) {
            h.contestSide = me;
            h.contestProgress = 0;
        }
        level.playSound(null, pos, SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 6.0F, 1.2F);
        int near = 0;
        for (Entity e : level.getEntities((Entity) null, new AABB(pos).inflate(12, 8, 12), x -> x instanceof GarrisonEntity ge && ge.isAlive() && ge.side().equals(h.owner))) near++;
        player.sendSystemMessage(gold("You raise your banner over " + h.name + ". Hold the stand for "
                + Config.CAPTURE_SECONDS.get() + " seconds with no defenders within 12 blocks."
                + (near > 0 ? " " + near + " still stand near it, archers on the roof count." : "")));
        if (h.isHouseOwned() && !h.counterattackSpawned) {
            h.counterattackSpawned = true;
            // brigands squatting in a ruin have no reserve to speak of: two of them, no crossbows
            int n = h.ruined ? 2 : Math.max(3, (int) Math.ceil(h.capacity(Role.KNIGHT) * 0.35));
            BlockPos at = h.rallyPos.equals(BlockPos.ZERO) ? h.gatePos : h.rallyPos;
            Garrisons.reinforce(level, mgr, h, Role.KNIGHT, n, at, player);
            if (!h.ruined) Garrisons.reinforce(level, mgr, h, Role.CROSSBOWMAN, Math.max(1, n / 3), at, player);
            announceNear(level, pos, 96, Component.literal("The garrison of " + h.name + " rallies to retake the keep!")
                    .withStyle(ChatFormatting.RED));
        }
        mgr.setDirty();
    }

    // ------------------------------------------------------------ the contest at the banner stand

    @Nullable
    private static String sideOf(Entity e, Holding h) {
        if (e instanceof GarrisonEntity g) return g.role() == Role.COURIER || g.side().isEmpty() ? null : g.side();
        if (e instanceof Player p) {
            if (p.isSpectator()) return null;
            String s = Sides.player(p.getUUID());
            if (s.equals(h.owner)) return s;
            return h.claimant != null && h.claimant.equals(p.getUUID()) ? s : null;
        }
        return RecruitsCompat.sideOf(e);
    }

    public static void contest(ServerLevel level, HoldingManager mgr, Holding h) {
        if (h.bannerPos.equals(BlockPos.ZERO) || !level.isLoaded(h.bannerPos)) return;
        if (h.isHouseOwned() && h.lordAlive) {
            clearContest(h);
            return;
        }
        AABB inner = new AABB(h.bannerPos).inflate(7, 5, 7);
        AABB outer = new AABB(h.bannerPos).inflate(12, 8, 12);
        Set<String> attackers = new HashSet<>();
        int defenders = 0;
        for (Entity e : level.getEntities((Entity) null, outer, x -> x instanceof LivingEntity le && le.isAlive())) {
            String s = sideOf(e, h);
            if (s == null) continue;
            if (s.equals(h.owner)) defenders++;
            else if (inner.contains(e.position())) attackers.add(s);
        }
        h.contestDefenders = defenders;
        if (defenders > 0) {
            h.contestProgress = Math.max(0, h.contestProgress - 2);
        } else if (attackers.size() == 1) {
            String s = attackers.iterator().next();
            if (!s.equals(h.contestSide)) {
                h.contestSide = s;
                h.contestProgress = 0;
            }
            h.contestProgress++;
            waves(level, mgr, h);
            if (h.contestProgress >= Config.CAPTURE_SECONDS.get()) {
                transfer(level, mgr, h, s);
                return;
            }
        } else {
            h.contestProgress = Math.max(0, h.contestProgress - 1);
        }
        updateBar(level, mgr, h);
    }

    /**
     * The bigger the castle, the harder it is to hold the stand alone. A watchtower has only its counterattack;
     * a holdfast sends one more wave, a great castle three, the city five and the king's guard with the last.
     */
    private static void waves(ServerLevel level, HoldingManager mgr, Holding h) {
        if (!h.isHouseOwned() || h.isTown() || h.ruined) return;
        int total = h.tier >= 4 ? 5 : h.tier == 3 ? 3 : h.tier == 2 ? 1 : 0;
        if (h.wavesSent >= total) return;
        int cap = Config.CAPTURE_SECONDS.get();
        int at = cap * (h.wavesSent + 1) / (total + 1);
        if (h.contestProgress < at) return;
        h.wavesSent++;
        int n = h.tier >= 4 ? 16 : h.tier == 3 ? 12 : 8;
        BlockPos spot = h.rallyPos.equals(BlockPos.ZERO) ? h.gatePos : h.rallyPos;
        ServerPlayer claimant = h.claimant != null ? level.getServer().getPlayerList().getPlayer(h.claimant) : null;
        Garrisons.reinforce(level, mgr, h, Role.KNIGHT, (int) Math.ceil(n * 0.7), spot, claimant);
        Garrisons.reinforce(level, mgr, h, Role.CROSSBOWMAN, (int) Math.floor(n * 0.3), spot, claimant);
        boolean last = h.wavesSent == total;
        if (last && h.tier >= 4) Garrisons.reinforce(level, mgr, h, Role.CAPTAIN, 3, spot, claimant);
        String what = last && h.tier >= 4 ? "The king's guard comes for the stand!" : "Another " + n + " of the garrison rally to the stand!";
        announceNear(level, h.bannerPos, 96, Component.literal(what).withStyle(ChatFormatting.RED));
    }

    private static void clearContest(Holding h) {
        h.contestProgress = 0;
        h.wavesSent = 0;
        if (h.bar != null) {
            h.bar.removeAllPlayers();
            h.bar = null;
        }
    }

    private static void updateBar(ServerLevel level, HoldingManager mgr, Holding h) {
        boolean claimed = h.claimant != null && level.getServer().getPlayerList().getPlayer(h.claimant) != null;
        if ((h.contestProgress <= 0 && !claimed) || h.contestSide == null) {
            clearContest(h);
            return;
        }
        if (h.bar == null) {
            h.bar = new ServerBossEvent(Component.literal("Taking " + h.name), BossEvent.BossBarColor.YELLOW,
                    BossEvent.BossBarOverlay.PROGRESS);
        }
        if (h.contestDefenders > 0) {
            h.bar.setName(Component.literal(h.name + ": " + h.contestDefenders + (h.contestDefenders == 1 ? " defender" : " defenders")
                    + " within 12 blocks of the stand. Kill them."));
            h.bar.setColor(BossEvent.BossBarColor.RED);
        } else {
            int left = Config.CAPTURE_SECONDS.get() - h.contestProgress;
            h.bar.setName(Component.literal(ownerName(level, mgr, h.contestSide) + " is taking " + h.name + " (" + left + "s)"));
            h.bar.setColor(BossEvent.BossBarColor.YELLOW);
        }
        h.bar.setProgress(Math.min(1F, h.contestProgress / (float) Config.CAPTURE_SECONDS.get()));
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(h.bannerPos) < 64 * 64) h.bar.addPlayer(p);
            else h.bar.removePlayer(p);
        }
    }

    /** The castle changes hands. */
    public static void transfer(ServerLevel level, HoldingManager mgr, Holding h, String newSide) {
        transfer(level, mgr, h, newSide, false);
    }

    public static void transfer(ServerLevel level, HoldingManager mgr, Holding h, String newSide, boolean peaceful) {
        String oldSide = h.owner;
        if (Sides.isHouse(oldSide) && Sides.isPlayer(newSide) && !peaceful) {
            Standing.change(level, mgr, h.houseId, Sides.playerId(newSide), Standing.TOOK_CASTLE, "you took " + h.name);
        }
        Garrisons.discardLive(level, h.live, true);
        h.usedPosts.clear();
        clearContest(h);
        h.vassal = null;
        h.owner = newSide;
        h.contestSide = null;
        h.claimant = null;
        h.counterattackSpawned = false;
        h.wavesSent = 0;
        long now = level.getGameTime();
        UUID newPlayer = Sides.playerId(newSide);
        int rankBefore = newPlayer != null ? Titles.rank(mgr, newPlayer) : 0;
        // armies fighting over this castle go home
        for (Campaign c : mgr.campaigns) {
            if (h.id.equals(c.target) && c.stage != Campaign.Stage.DONE && c.side.equals(newSide)) {
                Garrisons.discardLive(level, c.live, true);
                c.stage = Campaign.Stage.DONE;
            }
        }
        if (Sides.isPlayer(newSide)) {
            h.lordAlive = false;
            h.work = Builder.NONE;
            h.fillGarrison(peaceful ? 0.8 : 0.5);
            h.mode = Holding.Mode.DEFEND;
            h.nextRaid = now + raidDelay(level);
            h.raidWarned = false;
            h.nextProduce = now + 24000;
            h.nextReplenish = now + 1200;
            h.addStock(new ResourceLocation("minecraft", "bread"), 32);
            h.addStock(new ResourceLocation("minecraft", "iron_ingot"), 16);
            placeWarTable(level, h, Sides.playerId(newSide));
        } else {
            h.lordAlive = true;
            h.lordName = h.lordTitle() + NameGen.first(level.random)
                    + " " + ownerName(level, mgr, newSide).replace("House ", "");
            h.fillGarrison(0.6);
        }
        net.aiagent256.stormhold.house.Politics.onTransfer(level, mgr, h, oldSide, newSide);
        h.bannersDirty = true;
        Garrisons.recolorBanners(level, mgr, h);
        level.playSound(null, h.bannerPos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 8.0F, 0.8F);
        level.getServer().getPlayerList().broadcastSystemMessage(gold(ownerName(level, mgr, newSide) + " has taken "
                + h.name + " from " + ownerName(level, mgr, oldSide) + "!"), false);
        UUID p = Sides.playerId(newSide);
        if (p != null) {
            tell(level, p, Component.literal("The garrison swears to you. Use the War Table in the hall to give orders.")
                    .withStyle(ChatFormatting.GREEN));
            if (Sides.isHouse(oldSide)) Rewards.seized(level, mgr, h, p, peaceful);
            Titles.check(level, mgr, p, rankBefore);
            House house = mgr.houseOf(oldSide);
            if (house != null && h.id.equals(house.capital)) {
                // the crown has fallen: every leaderless castle of the house bends the knee
                int bent = 0;
                for (Holding other : new java.util.ArrayList<>(mgr.holdings.values())) {
                    if (other != h && other.isHouseOwned() && other.houseId.equals(house.id) && !other.lordAlive) {
                        transfer(level, mgr, other, newSide, true);
                        bent++;
                    }
                }
                level.getServer().getPlayerList().broadcastSystemMessage(gold(house.title() + " has lost its seat. "
                        + (bent > 0 ? bent + " of its castles bend the knee." : "Its remaining lords hold out.")), false);
            }
        }
        mgr.setDirty();
    }

    /** The War Table goes on its marked spot, or the nearest clear floor, or into the new lord's hands. */
    static void placeWarTable(ServerLevel level, Holding h, @Nullable UUID player) {
        BlockPos want = h.warTablePos.equals(BlockPos.ZERO) ? h.lordPos : h.warTablePos;
        if (want.equals(BlockPos.ZERO) || !level.isLoaded(want)) return;
        for (BlockPos p : new BlockPos[]{want, want.above(), want.below()}) {
            if (level.getBlockState(p).is(ModBlocks.WAR_TABLE.get())) return;
        }
        BlockPos spot = level.getBlockState(want).canBeReplaced() && !level.getBlockState(want.below()).canBeReplaced() ? want
                : Garrisons.standable(level, want);
        if (spot == null) spot = Garrisons.standable(level, h.lordPos);
        if (spot != null && level.getBlockState(spot).canBeReplaced()) {
            level.setBlock(spot, ModBlocks.WAR_TABLE.get().defaultBlockState(), 3);
            h.warTablePos = spot;
            return;
        }
        ServerPlayer p = player != null ? level.getServer().getPlayerList().getPlayer(player) : null;
        if (p != null) {
            ItemStack table = new ItemStack(ModBlocks.WAR_TABLE.get());
            if (!p.getInventory().add(table)) p.drop(table, false);
            p.sendSystemMessage(Component.literal("No room in the keep for a War Table, so the steward hands you one. Place it anywhere inside the walls.")
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    public static long raidDelay(ServerLevel level) {
        int lo = Config.RAID_MIN_DAYS.get(), hi = Math.max(lo, Config.RAID_MAX_DAYS.get());
        return (lo + level.random.nextInt(hi - lo + 1)) * 24000L;
    }

    private HoldingEvents() {
    }
}
