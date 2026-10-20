package net.aiagent256.stormhold.command;

import net.aiagent256.stormhold.Stormhold;

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
                .then(Commands.literal("version").executes(c -> {
                    c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Stormhold build " + Stormhold.buildId()), false);
                    return 1;
                }))
                .then(Commands.literal("direwolf").executes(c -> {
                            var p = c.getSource().getPlayerOrException();
                            var r = net.aiagent256.stormhold.holding.Direwolves.of(p.server, p.getUUID());
                            p.sendSystemMessage(Component.literal(r == null ? "You have no direwolf. A Dire Hound Leader sometimes leaves a white pup "
                                    + "by its body (one in three; a Dread Hound one in forty): feed it raw meat."
                                    : net.aiagent256.stormhold.holding.Direwolves.stats(r)));
                            return 1;
                        })
                        .then(Commands.literal("pup").requires(s -> s.hasPermission(2)).executes(c -> {
                            var p = c.getSource().getPlayerOrException();
                            var w = net.aiagent256.stormhold.holding.Direwolves.pup(p.serverLevel(), p.blockPosition().relative(p.getDirection(), 2));
                            c.getSource().sendSuccess(() -> Component.literal(w == null ? "No pup." : "A white pup. Feed it raw meat."), false);
                            return w == null ? 0 : 1;
                        })))
                .then(Commands.literal("list").executes(StormholdCommand::list))
                .then(Commands.literal("council").executes(c -> {
                            var p = c.getSource().getPlayerOrException();
                            net.aiagent256.stormhold.holding.Council.show(p.serverLevel(), p);
                            return 1;
                        })
                        .then(Commands.literal("appoint").then(Commands.argument("seat", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.Arrays.stream(
                                        net.aiagent256.stormhold.holding.Council.Seat.values()).map(s -> s.name().toLowerCase(java.util.Locale.ROOT)), b))
                                .executes(c -> council(c, true))))
                        .then(Commands.literal("dismiss").then(Commands.argument("seat", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.Arrays.stream(
                                        net.aiagent256.stormhold.holding.Council.Seat.values()).map(s -> s.name().toLowerCase(java.util.Locale.ROOT)), b))
                                .executes(c -> council(c, false)))))
                .then(Commands.literal("nearest").executes(StormholdCommand::nearest))
                .then(Commands.literal("info").executes(StormholdCommand::info))
                .then(Commands.literal("houses").executes(StormholdCommand::houses))
                .then(Commands.literal("realm").executes(c -> {
                    net.aiagent256.stormhold.net.Net.openRealm(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("chronicle").executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(p.serverLevel());
                    var mine = mgr.ownedBy(p.getUUID());
                    if (mine.isEmpty()) {
                        for (var e : net.aiagent256.stormhold.holding.Chronicle.forPlayer(mgr, p.getUUID(), 12))
                            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Day " + e.day() + ": " + e.text()));
                        return 1;
                    }
                    for (var line : net.aiagent256.stormhold.holding.Chronicle.account(p.serverLevel(), mgr, p.getUUID(), mine,
                            net.aiagent256.stormhold.holding.Chronicle.day(p.serverLevel()))) p.sendSystemMessage(line);
                    return 1;
                }))
                .then(Commands.literal("accept").then(Commands.argument("holding", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("posting", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(StormholdCommand::accept))))
                .then(Commands.literal("find").then(Commands.argument("kind", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(net.aiagent256.stormhold.worldgen.Finder.KINDS.keySet(), b))
                        .executes(StormholdCommand::find)))
                .then(Commands.literal("choose").then(Commands.argument("matter", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .then(Commands.argument("which", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 2)).executes(StormholdCommand::choose))))
                .then(Commands.literal("matters").executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(p.serverLevel());
                    var m = net.aiagent256.stormhold.holding.Events.open(mgr, p.getUUID());
                    if (m == null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Nothing waits on your word."));
                    else for (var line : net.aiagent256.stormhold.holding.Events.present(mgr, m)) p.sendSystemMessage(line);
                    return 1;
                }))
                .then(Commands.literal("guide").executes(c -> {
                    var p = c.getSource().getPlayerOrException();
                    var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(p.serverLevel());
                    var h = net.aiagent256.stormhold.holding.Guide.here(mgr, p);
                    if (h == null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("No castle or town within 400 blocks. Try /stormhold find city."));
                    else for (var line : net.aiagent256.stormhold.holding.Guide.lines(p.serverLevel(), mgr, h, p)) p.sendSystemMessage(line);
                    return 1;
                }))
                .then(Commands.literal("frontier").executes(StormholdCommand::frontier)
                        .then(Commands.literal("here").requires(s -> s.hasPermission(2) || !frontierSet(s))
                                .executes(StormholdCommand::frontierHere)))
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
                + Holding.renownName(h.renown).substring(0, 1).toUpperCase() + Holding.renownName(h.renown).substring(1) + " " + h.kindName().toLowerCase() + " (renown " + h.renown + "/5)\n"
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

    private static int accept(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return 0;
        try {
            java.util.UUID h = java.util.UUID.fromString(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "holding"));
            java.util.UUID p = java.util.UUID.fromString(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "posting"));
            return net.aiagent256.stormhold.holding.City.accept(c.getSource().getLevel(), sp, h, p) ? 1 : 0;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** The nearest castle of a kind: one already in the world, and where the next one will stand. Never loads a chunk. */
    private static int find(CommandContext<CommandSourceStack> c) {
        String kind = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "kind").toLowerCase(java.util.Locale.ROOT);
        if (!net.aiagent256.stormhold.worldgen.Finder.KINDS.containsKey(kind)) {
            c.getSource().sendFailure(Component.literal("Kinds: " + String.join(", ", new java.util.TreeSet<>(net.aiagent256.stormhold.worldgen.Finder.KINDS.keySet()))));
            return 0;
        }
        var level = c.getSource().getLevel();
        BlockPos from = BlockPos.containing(c.getSource().getPosition());
        boolean nether = level.dimension() == net.minecraft.world.level.Level.NETHER;
        if (kind.equals("nether") != nether) {
            c.getSource().sendFailure(Component.literal(nether ? "Only Nether holdfasts are down here: /stormhold find nether" : "Nether holdfasts are in the Nether."));
            return 0;
        }
        var known = net.aiagent256.stormhold.worldgen.Finder.known(level, from, kind);
        if (known != null) {
            c.getSource().sendSuccess(() -> Component.literal(known.what() + " at " + known.x() + ", " + known.z() + " (" + (int) known.dist(from)
                    + " blocks " + heading(from, known.x(), known.z()) + "), already standing.").withStyle(net.minecraft.ChatFormatting.GOLD), false);
        }
        long t = System.currentTimeMillis();
        int rings = kind.equals("city") ? 10 : 16;
        var planned = net.aiagent256.stormhold.worldgen.Finder.planned(level, from, kind, rings, 4000);
        long took = System.currentTimeMillis() - t;
        if (planned == null) {
            c.getSource().sendSuccess(() -> Component.literal(known == null ? "No " + kind + " found within reach (" + took + " ms)."
                    : "No other " + kind + " planned within reach.").withStyle(net.minecraft.ChatFormatting.GRAY), false);
            return known == null ? 0 : 1;
        }
        c.getSource().sendSuccess(() -> Component.literal("Next " + planned.what() + " will stand at " + planned.x() + ", " + planned.z() + " ("
                + (int) planned.dist(from) + " blocks " + heading(from, planned.x(), planned.z()) + "). " + took + " ms.")
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);
        return 1;
    }

    private static String heading(BlockPos from, int x, int z) {
        int dx = x - from.getX(), dz = z - from.getZ();
        String ns = dz < 0 ? "north" : "south", ew = dx < 0 ? "west" : "east";
        if (Math.abs(dx) < Math.abs(dz) / 2) return ns;
        if (Math.abs(dz) < Math.abs(dx) / 2) return ew;
        return ns + "-" + ew;
    }

    private static int choose(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return 0;
        try {
            java.util.UUID id = java.util.UUID.fromString(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "matter"));
            int which = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "which");
            HoldingManager mgr = HoldingManager.get(c.getSource().getLevel());
            if (!net.aiagent256.stormhold.holding.Events.choose(c.getSource().getLevel(), mgr, sp.getUUID(), id, which)) {
                sp.sendSystemMessage(Component.literal("That matter is settled.").withStyle(net.minecraft.ChatFormatting.GRAY));
                return 0;
            }
            return 1;
        } catch (IllegalArgumentException e) {
            return 0;
        }
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

    private static boolean frontierSet(net.minecraft.commands.CommandSourceStack s) {
        return HoldingManager.get(s.getServer().overworld()).frontierSet;
    }

    /** Where the frontier counts from, how far out you are and what zone you're in. */
    private static int frontier(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c) {
        var src = c.getSource();
        BlockPos centre = net.aiagent256.stormhold.holding.Frontier.centre(src.getServer());
        boolean set = frontierSet(src);
        net.minecraft.server.level.ServerLevel level = src.getLevel();
        var at = src.getPosition();
        double d = net.aiagent256.stormhold.holding.Frontier.distance(level, at.x, at.z);
        int z = net.aiagent256.stormhold.holding.Frontier.zone(level, at.x, at.z);
        int band = net.aiagent256.stormhold.Config.FRONTIER_BAND.get();
        String next = z >= net.aiagent256.stormhold.holding.Frontier.MAX ? "This is the last zone."
                : "Zone " + (z + 1) + " starts " + (int) Math.max(0, z * band - d) + " blocks farther out.";
        src.sendSuccess(() -> Component.literal("Frontier centre: " + centre.getX() + ", " + centre.getZ()
                + (set ? "" : " (world spawn; /stormhold frontier here to move it, once)") + ".\n"
                + "You are " + (int) d + " blocks out, in " + net.aiagent256.stormhold.holding.Frontier.name(z) + ", zone " + z + " of "
                + net.aiagent256.stormhold.holding.Frontier.MAX + ". " + next), false);
        return 1;
    }

    /** Count the zones out from where you stand. Free the first time; after that it takes an operator. */
    private static int frontierHere(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c) {
        var src = c.getSource();
        var at = src.getPosition();
        net.aiagent256.stormhold.holding.Frontier.setCentre(src.getServer(), src.getLevel(), at.x, at.z);
        BlockPos centre = net.aiagent256.stormhold.holding.Frontier.centre(src.getServer());
        src.sendSuccess(() -> Component.literal("The frontier now counts out from " + centre.getX() + ", " + centre.getZ()
                + ". Every " + net.aiagent256.stormhold.Config.FRONTIER_BAND.get() + " blocks out is a zone harder, to zone 9 at "
                + 8 * net.aiagent256.stormhold.Config.FRONTIER_BAND.get() + ".").withStyle(net.minecraft.ChatFormatting.GOLD), false);
        return 1;
    }

    private static int council(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, boolean appoint)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        var seat = net.aiagent256.stormhold.holding.Council.Seat.byName(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "seat"));
        if (seat == null) {
            c.getSource().sendFailure(Component.literal("Seats: marshal, spymaster, treasurer, works."));
            return 0;
        }
        boolean ok = appoint ? net.aiagent256.stormhold.holding.Council.appoint(p.serverLevel(), p, seat)
                : net.aiagent256.stormhold.holding.Council.dismiss(p.serverLevel(), p, seat);
        if (ok) net.aiagent256.stormhold.holding.Council.show(p.serverLevel(), p);
        return ok ? 1 : 0;
    }

}
