package net.aiagent256.stormhold.net;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.compat.RecruitsCompat;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Campaign;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.Economy;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.ChatFormatting;
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
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Stormhold.id("main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static final int DEFEND = 0, PRODUCE = 1, ATTACK = 2, SET_ATTACK = 3, SET_SUPPLY = 4, CLEAR_SUPPLY = 5,
            MUSTER = 6, REFRESH = 7, MARCH_NOW = 8;

    public static void register() {
        CHANNEL.registerMessage(0, WarTableData.class, WarTableData::write, WarTableData::read, WarTableData::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, WarTableAction.class, WarTableAction::write, WarTableAction::read, WarTableAction::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
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
                    if (c.kind == Campaign.Kind.PARTY && h.id.equals(c.origin)) status.append("War party out: ").append(c.total()).append(" men, ")
                            .append(c.stage.name().toLowerCase()).append(". ");
                }
                if (h.nextRaid > 0) {
                    long days = Math.max(0, (h.nextRaid - level.getGameTime()) / 24000);
                    status.append(h.raidWarned ? "Raid expected within a day." : "Next raid in about " + days + " days.");
                }
                out.add(new HoldingInfo(h.id, h.name, h.tier, h.mode.name(), counts, cap, Economy.stockSummary(h), h.center(),
                        h.attackTarget, h.supplyTarget, status.toString()));
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

    private static void apply(ServerPlayer player, WarTableAction a) {
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
                case MUSTER -> muster(level, mgr, player, h, Math.max(1, Math.min(10, a.amount())));
                default -> {
                }
            }
            mgr.setDirty();
            break;
        }
        openWarTable(player);
    }

    private static void muster(ServerLevel level, HoldingManager mgr, ServerPlayer player, Holding h, int n) {
        if (!RecruitsCompat.loaded()) {
            player.sendSystemMessage(Component.literal("Villager Recruits is not installed."));
            return;
        }
        int done = 0;
        for (int i = 0; i < n; i++) {
            Role r = h.count(Role.KNIGHT) > 2 ? Role.KNIGHT : h.count(Role.CROSSBOWMAN) > 0 ? Role.CROSSBOWMAN
                    : h.count(Role.ARCHER) > 1 ? Role.ARCHER : null;
            if (r == null) break;
            if (!RecruitsCompat.muster((ServerLevel) player.level(), player, r)) break;
            h.add(r, -1);
            done++;
        }
        player.sendSystemMessage(done > 0
                ? HoldingEvents.gold(done + " soldiers of " + h.name + " join your company.")
                : Component.literal("No one could be mustered (Recruits may have reached its unit limit).").withStyle(ChatFormatting.RED));
        mgr.setDirty();
    }

    private Net() {
    }
}
