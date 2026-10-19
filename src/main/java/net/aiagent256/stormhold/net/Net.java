package net.aiagent256.stormhold.net;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.Company;
import net.aiagent256.stormhold.holding.Economy;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class Net {
    private static final String VERSION = "4";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Stormhold.id("main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static final int DEFEND = 0, PRODUCE = 1, ATTACK = 2, SET_ATTACK = 3, SET_SUPPLY = 4, CLEAR_SUPPLY = 5,
            MUSTER = 6, REFRESH = 7, MARCH_NOW = 8, MUSTER_KNIGHTS = 9, MUSTER_ARCHERS = 10, MUSTER_CROSSBOWMEN = 11, DISMISS = 12, ARM = 13, SEND_COLUMN = 14, KEEP_WITH_ME = 15, EXPAND_FIEF = 16, REPAIR = 17, FORTIFY = 18, OPEN_REALM = 19,
            SET_MINERS = 20, SET_SMITHS = 21, PROJECT = 22, SCOUT = 23, TREASURY = 24, VASSAL = 25, REVOKE = 26, COUNCIL = 27;

    public static void register() {
        CHANNEL.registerMessage(0, WarTableData.class, WarTableData::write, WarTableData::read, WarTableData::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, WarTableAction.class, WarTableAction::write, WarTableAction::read, WarTableAction::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, RealmData.class, RealmData::write, RealmData::read, Net::handleRealm,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handleRealm(RealmData data, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.aiagent256.stormhold.client.ClientHooks.showRealm(data)));
        ctx.get().setPacketHandled(true);
    }

    /** The Realm page: the map of what the player knows, his houses and his record. */
    public static void openRealm(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), RealmData.build(player));
    }

    // ------------------------------------------------------------ server -> client

    public record WarTableData(List<HoldingInfo> holdings, boolean recruits) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(holdings.size());
            for (HoldingInfo h : holdings) h.write(b);
            b.writeBoolean(recruits);
        }

        static WarTableData read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            List<HoldingInfo> l = new ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(HoldingInfo.read(b));
            return new WarTableData(l, b.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> net.aiagent256.stormhold.client.ClientHooks.showWarTable(this)));
            ctx.get().setPacketHandled(true);
        }
    }

    public static void openWarTable(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), build(player));
    }

    private static WarTableData build(ServerPlayer player) {
        List<HoldingInfo> out = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager mgr = HoldingManager.get(level);
            for (Holding h : mgr.ownedBy(player.getUUID())) {
                int[] counts = {h.count(Role.CAPTAIN), h.count(Role.KNIGHT), h.count(Role.ARCHER), h.count(Role.CROSSBOWMAN)};
                int[] cap = {h.capacity(Role.CAPTAIN), h.capacity(Role.KNIGHT), h.capacity(Role.ARCHER), h.capacity(Role.CROSSBOWMAN)};
                StringBuilder status = new StringBuilder();
                for (Campaign c : mgr.campaigns) {
                    if (c.stage == Campaign.Stage.DONE) continue;
                    if (c.kind == Campaign.Kind.RAID && h.id.equals(c.target)) status.append("Under attack by ").append(c.total()).append(" men. ");
                    if (c.kind == Campaign.Kind.ILLAGERS && h.id.equals(c.target)) status.append("Illager warband at the walls: ").append(c.total()).append(". ");
                    if (c.kind == Campaign.Kind.PARTY && h.id.equals(c.origin)) status.append("War party out: ").append(c.total()).append(" men, ")
                            .append(c.stage.name().toLowerCase()).append(". ");
                    if (c.kind == Campaign.Kind.SCOUT && h.id.equals(c.origin)) status.append("Scouts out, back in about ")
                            .append(net.aiagent256.stormhold.holding.Scouts.time((int) Math.ceil(((c.stage == Campaign.Stage.MARCH ? 2 : 1) - c.progress)
                                    * c.length() / net.aiagent256.stormhold.holding.Scouts.SPEED))).append(". ");
                    if (c.kind == Campaign.Kind.COLUMN && h.id.equals(c.origin)) status.append("Column of ").append(c.total())
                            .append(" riding to you, ").append(net.aiagent256.stormhold.holding.Columns.eta(c)).append(" out. ");
                }
                if (!h.work.equals(net.aiagent256.stormhold.holding.Builder.NONE)) {
                    int left = h.workQueue == null ? -1 : h.workQueue.size();
                    status.append("Builder ").append(net.aiagent256.stormhold.holding.Builder.doing(h))
                            .append(left < 0 ? ", surveying. " : ": " + left + " blocks to go. ");
                } else if (h.fortify > 0) {
                    status.append("Fortified, stage ").append(h.fortify).append(". ");
                }
                if (net.aiagent256.stormhold.holding.Vassals.is(h)) status.append("Vassal: ").append(h.lordName).append(", loyalty ")
                        .append(h.loyalty).append(", sends ").append(Math.round(net.aiagent256.stormhold.holding.Vassals.share(h) * 100)).append("% of rents. ");
                if (h.reinforceKeep > 0) status.append("Supply line: keeps ").append(h.reinforceKeep).append(" men with you. ");
                if (h.nextRaid > 0) {
                    long days = Math.max(0, (h.nextRaid - level.getGameTime()) / 24000);
                    status.append(h.raidWarned ? "Raid expected within a day." : "Next raid in about " + days + " days.");
                }
                String fief = h.fiefReport.isEmpty()
                        ? (h.fiefPlan != null && !h.fiefPlan.isEmpty() ? "The fief is being laid out: " + h.fiefPlan.size() + " buildings to go."
                        : h.fief.isEmpty() ? "No fief." : h.fiefName() + " of " + h.fief.size() + " buildings; census at dawn.")
                        : h.fiefReport;
                if (h.fiefPlan != null && !h.fiefPlan.isEmpty() && !h.fiefReport.isEmpty()) fief += " Building: " + h.fiefPlan.get(0).replace('_', ' ') + ".";
                int kit = net.aiagent256.stormhold.holding.Frontier.level(level, h);
                String labour = "Kit level " + kit + " (" + net.aiagent256.stormhold.holding.Labour.kitName(kit) + "). "
                        + (h.labourReport.isEmpty() ? "" : h.labourReport + " ")
                        + "Working: " + h.minersAt + " of " + h.miners + " miners, " + h.smithsAt + " of " + h.smiths + " smiths.";
                out.add(new HoldingInfo(h.id, h.name, h.isTown() ? 0 : h.tier, h.mode.name(), counts, cap, Economy.stockSummary(h), h.center(),
                        h.attackTarget, h.supplyTarget, status.toString(), fief, Economy.realmSummary(mgr, h, player.getUUID()),
                        h.miners, h.smiths, labour));
            }
        }
        return new WarTableData(out, RecruitsCompat.loaded());
    }

    // ------------------------------------------------------------ client -> server

    public record WarTableAction(UUID holding, int action, BlockPos pos, int amount) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(holding);
            b.writeVarInt(action);
            b.writeBlockPos(pos);
            b.writeVarInt(amount);
        }

        static WarTableAction read(FriendlyByteBuf b) {
            return new WarTableAction(b.readUUID(), b.readVarInt(), b.readBlockPos(), b.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer player = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (player != null) apply(player, this);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public static void sendAction(UUID holding, int action, BlockPos pos, int amount) {
        CHANNEL.sendToServer(new WarTableAction(holding, action, pos, amount));
    }

    /** A Knight musters ten at a time, a Lord twenty, a Warden forty, a King eighty. */
    private static int musterCap(HoldingManager mgr, ServerPlayer player) {
        int cap = net.aiagent256.stormhold.holding.Titles.musterCap(net.aiagent256.stormhold.holding.Titles.rank(mgr, player.getUUID()));
        if (cap > 40 && player.server.getPlayerList().getPlayer(player.getUUID()) == null) return 40;
        return cap;
    }

    private static void apply(ServerPlayer player, WarTableAction a) {
        if (a.action() == OPEN_REALM) {
            openRealm(player);
            return;
        }
        if (a.action() == REFRESH && a.holding().equals(new UUID(0, 0))) {
            openWarTable(player);
            return;
        }
        String me = Sides.player(player.getUUID());
        for (ServerLevel level : player.server.getAllLevels()) {
            HoldingManager mgr = HoldingManager.get(level);
            Holding h = mgr.holdings.get(a.holding());
            if (h == null) continue;
            if (!me.equals(h.owner)) return;
            switch (a.action()) {
                case DEFEND -> h.mode = Holding.Mode.DEFEND;
                case PRODUCE -> h.mode = Holding.Mode.PRODUCE;
                case ATTACK -> {
                    h.mode = Holding.Mode.ATTACK;
                    if (h.attackTarget == null) player.sendSystemMessage(Component.literal("Set a target to march on."));
                }
                case SET_ATTACK -> h.attackTarget = a.pos();
                case SET_SUPPLY -> h.supplyTarget = a.pos();
                case CLEAR_SUPPLY -> h.supplyTarget = null;
                case MARCH_NOW -> {
                    if (h.attackTarget == null) {
                        player.sendSystemMessage(Component.literal("Set a target first."));
                    } else if (Campaigns.launchParty(level, mgr, h, h.attackTarget) == null) {
                        player.sendSystemMessage(Component.literal("Not enough men to spare, or a war party is already out."));
                    }
                }
                case MUSTER, MUSTER_KNIGHTS -> Company.muster(level, mgr, player, h, Role.KNIGHT, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case MUSTER_ARCHERS -> Company.muster(level, mgr, player, h, Role.ARCHER, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case MUSTER_CROSSBOWMEN -> Company.muster(level, mgr, player, h, Role.CROSSBOWMAN, Math.max(1, Math.min(musterCap(mgr, player), a.amount())));
                case DISMISS -> Company.dismiss(level, mgr, player, h);
                case SEND_COLUMN -> {
                    if (net.aiagent256.stormhold.holding.Columns.active(mgr, h)) {
                        player.sendSystemMessage(Component.literal("A column from " + h.name + " is already on the road."));
                    } else {
                        var want = new java.util.EnumMap<Role, Integer>(Role.class);
                        want.put(Role.KNIGHT, Math.max(0, Math.min(60, a.pos().getX())));
                        want.put(Role.ARCHER, Math.max(0, Math.min(60, a.pos().getY())));
                        want.put(Role.CROSSBOWMAN, Math.max(0, Math.min(60, a.pos().getZ())));
                        net.aiagent256.stormhold.holding.Columns.send(level, mgr, player, h, want);
                    }
                }
                case EXPAND_FIEF -> {
                    var bread = new net.minecraft.resources.ResourceLocation("minecraft", "bread");
                    var iron = new net.minecraft.resources.ResourceLocation("minecraft", "iron_ingot");
                    if (h.fiefPlan == null) net.aiagent256.stormhold.holding.Fief.plan(level, h);
                    if (h.fiefPlan.size() >= 3) {
                        player.sendSystemMessage(Component.literal("The builders at " + h.name + " already have their hands full."));
                    } else if (net.aiagent256.stormhold.holding.Stores.realmStock(mgr, h, player.getUUID(), bread) < 32
                            || net.aiagent256.stormhold.holding.Stores.realmStock(mgr, h, player.getUUID(), iron) < 8) {
                        player.sendSystemMessage(Component.literal("Raising a building takes 32 bread and 8 iron from the realm's stores.")
                                .withStyle(net.minecraft.ChatFormatting.RED));
                    } else {
                        net.aiagent256.stormhold.holding.Stores.take(mgr, h, player.getUUID(), bread, 32);
                        net.aiagent256.stormhold.holding.Stores.take(mgr, h, player.getUUID(), iron, 8);
                        String next = net.aiagent256.stormhold.holding.Fief.nextExtra(h);
                        h.fiefPlan.add(next);
                        player.sendSystemMessage(HoldingEvents.gold("The people of " + h.name + " will raise a " + next.replace('_', ' ') + "."));
                    }
                }
                case SET_MINERS -> player.sendSystemMessage(net.aiagent256.stormhold.holding.Labour.set(h,
                        net.aiagent256.stormhold.holding.Labour.MINER, a.amount()));
                case SET_SMITHS -> player.sendSystemMessage(net.aiagent256.stormhold.holding.Labour.set(h,
                        net.aiagent256.stormhold.holding.Labour.SMITH, a.amount()));
                case REPAIR -> net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.REPAIR, player);
                case FORTIFY -> net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, net.aiagent256.stormhold.holding.Builder.FORTIFY, player);
                case SCOUT -> net.aiagent256.stormhold.holding.Scouts.send(level, mgr, h, player, a.pos());
                case VASSAL -> {
                    if (net.aiagent256.stormhold.holding.Vassals.is(h)) net.aiagent256.stormhold.holding.Vassals.gift(level, mgr, h, player);
                    else net.aiagent256.stormhold.holding.Vassals.grant(level, mgr, h, player);
                }
                case REVOKE -> net.aiagent256.stormhold.holding.Vassals.revoke(level, mgr, h, player);
                case COUNCIL -> {
                    net.aiagent256.stormhold.holding.Council.show(level, player);
                    return;
                }
                case TREASURY -> {
                    var em = new net.minecraft.resources.ResourceLocation("minecraft", "emerald");
                    int have = net.aiagent256.stormhold.holding.Stores.realmStock(mgr, h, player.getUUID(), em);
                    int n = Math.min(64, have);
                    if (n <= 0) {
                        player.sendSystemMessage(Component.literal("The realm's treasury is empty.").withStyle(net.minecraft.ChatFormatting.RED));
                    } else {
                        net.aiagent256.stormhold.holding.Stores.take(mgr, h, player.getUUID(), em, n);
                        net.minecraft.world.item.ItemStack purse = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, n);
                        if (!player.getInventory().add(purse)) player.drop(purse, false);
                        player.sendSystemMessage(HoldingEvents.gold("You take " + n + " emeralds from the treasury; " + (have - n)
                                + " left in the realm's stores."));
                    }
                }
                case PROJECT -> {
                    String[] ps = net.aiagent256.stormhold.holding.Builder.PROJECTS;
                    if (a.amount() >= 0 && a.amount() < ps.length) net.aiagent256.stormhold.holding.Builder.order(level, mgr, h, ps[a.amount()], player);
                }
                case KEEP_WITH_ME -> {
                    h.reinforceKeep = Math.max(0, Math.min(80, a.amount()));
                    h.nextColumn = 0;
                    player.sendSystemMessage(h.reinforceKeep > 0
                            ? HoldingEvents.gold(h.name + " will keep " + h.reinforceKeep + " men with you, sending a column whenever you run short.")
                            : Component.literal("Supply line from " + h.name + " cancelled."));
                }
                case ARM -> {
                    var kit = net.aiagent256.stormhold.holding.Armoury.Kit.byTier(a.amount());
                    if (kit != null) net.aiagent256.stormhold.holding.Armoury.arm(level, mgr, player, h, kit, a.pos().getX() != 0);
                }
                default -> {
                }
            }
            mgr.setDirty();
            break;
        }
        openWarTable(player);
    }

    private Net() {
    }
}
