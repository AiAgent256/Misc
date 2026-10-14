package net.aiagent256.stormhold.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.holding.Campaigns;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingEvents;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;

public final class StormholdCommand {

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("stormhold")
                .then(Commands.literal("list").executes(StormholdCommand::list))
                .then(Commands.literal("nearest").executes(StormholdCommand::nearest))
                .then(Commands.literal("info").executes(StormholdCommand::info))
                .then(Commands.literal("houses").executes(StormholdCommand::houses))
                .then(Commands.literal("befriend").requires(s -> s.hasPermission(2)).executes(StormholdCommand::befriend))
                .then(Commands.literal("capture").requires(s -> s.hasPermission(2)).executes(StormholdCommand::capture))
                .then(Commands.literal("raid").requires(s -> s.hasPermission(2)).executes(StormholdCommand::raid))
                .then(Commands.literal("killlord").requires(s -> s.hasPermission(2)).executes(StormholdCommand::killLord)));
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        if (mgr.holdings.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("No castles found yet in this dimension. Explore."), false);
            return 0;
        }
        for (Holding h : mgr.holdings.values()) {
            BlockPos p = h.center();
            String line = h.name + " (tier " + h.tier + ") at " + p.getX() + ", " + p.getZ() + ": "
                    + HoldingEvents.ownerName(level, mgr, h.owner) + ", " + h.troops() + " men" + (h.isHouseOwned() && h.lordAlive ? ", lord alive" : "");
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return mgr.holdings.size();
    }

    private static Holding nearestTo(CommandSourceStack s) {
        HoldingManager mgr = HoldingManager.get(s.getLevel());
        BlockPos at = BlockPos.containing(s.getPosition());
        return mgr.holdings.values().stream().min(Comparator.comparingDouble(h -> h.center().distSqr(at))).orElse(null);
    }

    private static int nearest(CommandContext<CommandSourceStack> c) {
        Holding h = nearestTo(c.getSource());
        if (h == null) {
            c.getSource().sendFailure(Component.literal("No castles known yet."));
            return 0;
        }
        BlockPos p = h.center();
        c.getSource().sendSuccess(() -> Component.literal(h.name + " at " + p.getX() + ", " + p.getY() + ", " + p.getZ()), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        Holding h = nearestTo(c.getSource());
        if (h == null) return 0;
        ServerLevel level = c.getSource().getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        String s = h.name + " [" + h.structure + (h.layout != null ? " / " + h.layout + " / " + h.theme : "") + "]\n"
                + "Held by " + HoldingEvents.ownerName(level, mgr, h.owner) + (h.isHouseOwned() ? (h.lordAlive ? ", " + h.lordName + " lives" : ", lord dead") : "") + "\n"
                + "Captain " + h.count(Role.CAPTAIN) + ", knights " + h.count(Role.KNIGHT) + ", archers " + h.count(Role.ARCHER)
                + ", crossbows " + h.count(Role.CROSSBOWMAN) + "\nBanner stand at " + h.bannerPos.toShortString()
                + (h.contestProgress > 0 ? "\nBeing taken: " + h.contestProgress + "s" : "")
                + (h.isHouseOwned() && c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp
                ? "\n" + mgr.house(h.houseId).title() + " counts you a " + net.aiagent256.stormhold.house.Standing.word(
                net.aiagent256.stormhold.house.Standing.get(mgr, h.houseId, sp.getUUID())) + " ("
                + net.aiagent256.stormhold.house.Standing.get(mgr, h.houseId, sp.getUUID()) + ")" : "");
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    /** Every house you've met and how it feels about you. */
    private static int houses(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return 0;
        HoldingManager mgr = HoldingManager.get(c.getSource().getLevel());
        StringBuilder sb = new StringBuilder();
        for (var house : mgr.houses.values()) {
            if (house.id.equals("outlaws")) continue;
            int castles = 0;
            for (Holding h : mgr.holdings.values()) if (h.isHouseOwned() && h.houseId.equals(house.id)) castles++;
            if (castles == 0) continue;
            int v = net.aiagent256.stormhold.house.Standing.get(mgr, house.id, sp.getUUID());
            sb.append(house.title()).append(": ").append(net.aiagent256.stormhold.house.Standing.word(v)).append(" (").append(v)
                    .append("), ").append(castles).append(castles == 1 ? " castle\n" : " castles\n");
        }
        String s = sb.length() == 0 ? "You haven't met any houses yet." : sb.toString().trim();
        c.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    /** Testing: make the nearest castle's house friendly to you. */
    private static int befriend(CommandContext<CommandSourceStack> c) {
        Holding h = nearestTo(c.getSource());
        if (h == null || !(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return 0;
        ServerLevel level = c.getSource().getLevel();
        HoldingManager mgr = HoldingManager.get(level);
        net.aiagent256.stormhold.house.Standing.change(level, mgr, h.houseId, sp.getUUID(), 100, "command");
        return 1;
    }

    private static int capture(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        Holding h = nearestTo(c.getSource());
        if (p == null || h == null) return 0;
        HoldingEvents.transfer(c.getSource().getLevel(), HoldingManager.get(c.getSource().getLevel()), h, Sides.player(p.getUUID()));
        return 1;
    }

    private static int raid(CommandContext<CommandSourceStack> c) {
        Holding h = nearestTo(c.getSource());
        if (h == null || !h.isPlayerOwned()) {
            c.getSource().sendFailure(Component.literal("The nearest castle is not held by a player."));
            return 0;
        }
        Campaigns.launchRaid(c.getSource().getLevel(), HoldingManager.get(c.getSource().getLevel()), h);
        return 1;
    }

    private static int killLord(CommandContext<CommandSourceStack> c) {
        Holding h = nearestTo(c.getSource());
        if (h == null) return 0;
        h.lordAlive = false;
        HoldingManager.get(c.getSource().getLevel()).setDirty();
        c.getSource().sendSuccess(() -> Component.literal(h.lordName + " is dead."), true);
        return 1;
    }

    private StormholdCommand() {
    }
}
