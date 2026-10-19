package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Where things are. A city is a quarter of a mile across and nothing in it is signposted, so when you first
 * come to a castle, a town or a city you're told what's here and which way it lies, and {@code /stormhold guide}
 * says it again. Directions are from where you stand.
 */
public final class Guide {
    private static final String[] WINDS = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};

    /** "140 blocks north-west", or "right here". */
    public static String way(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int d = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (d < 8) return "right here";
        // Minecraft: north is -z, east is +x
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        int i = (int) Math.floor(((ang + 360 + 22.5) % 360) / 45);
        return d + " blocks " + WINDS[i];
    }

    private static String at(BlockPos p) {
        return "(" + p.getX() + ", " + p.getZ() + ")";
    }

    private static Component line(String what, BlockPos from, BlockPos to, String note) {
        return Component.literal("  " + what + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(way(from, to) + " " + at(to)).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(note.isEmpty() ? "" : ". " + note).withStyle(ChatFormatting.GRAY));
    }

    public static List<Component> lines(ServerLevel level, HoldingManager mgr, Holding h, ServerPlayer p) {
        List<Component> out = new ArrayList<>();
        BlockPos from = p.blockPosition();
        String owner = HoldingEvents.ownerName(level, mgr, h.owner);
        out.add(Component.literal(h.name + ", " + h.kindName().toLowerCase() + " of " + owner + ".").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        boolean mine = h.isPlayerOwned() && p.getUUID().equals(h.ownerPlayer());
        if (h.isHouseOwned()) {
            int st = Standing.get(mgr, h.houseId, p.getUUID());
            String word = Standing.word(st);
            String means = st >= Standing.FRIENDLY ? "You may trade, hire, buy and take their work."
                    : st > Standing.HOSTILE ? "The gate opens for you, the " + (h.isTown() ? "reeve" : h.isCity() ? "king" : "lord")
                    + " trades and houses are for sale; hiring soldiers wants friendly standing (do their work, kill monsters at their gate, run them goods)."
                    : "The gate stays down for you.";
            out.add(Component.literal("  " + owner + " holds you " + word + " (" + st + "). ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(means).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
        }
        if (Frontier.enabled()) {
            int z = Frontier.zone(level, h.center()), lvl = Frontier.level(level, h);
            String kit = lvl >= 9 ? "netherite, Protection IV" : lvl >= 7 ? "netherite" : lvl >= 4 ? "diamond" : lvl >= 2 ? "iron" : "chain";
            out.add(Component.literal("  " + Frontier.name(z).substring(0, 1).toUpperCase() + Frontier.name(z).substring(1)
                    + ", zone " + z + " of " + Frontier.MAX + ": its knights wear " + kit
                    + (z >= 4 ? ", and its markets carry the frontier's goods" : "") + ".").withStyle(ChatFormatting.GRAY));
        }
        if (!h.gatePos.equals(BlockPos.ZERO)) out.add(line("Gate", from, h.gatePos, ""));
        if (!h.isCity() && !h.isTown() && !h.rallyPos.equals(BlockPos.ZERO)) out.add(line("Yard", from, h.rallyPos, "Where the garrison musters"));
        if (!h.lordPos.equals(BlockPos.ZERO) && h.lordAlive) {
            String who = (h.isTown() ? "The reeve" : h.isCity() ? "The king" : "The lord") + (h.lordName.isEmpty() ? "" : ", " + h.lordName);
            out.add(line(who, from, h.lordPos, h.isTown() ? "Sits at the War Table in the market hall"
                    : "Emerald for bread, sneak + emerald for iron" + (h.isCity() ? ". Kill him, raise your banner and hold the donjon to take the city" : "")));
        }
        if (!mine && !h.bannerPos.equals(BlockPos.ZERO)) {
            String where = h.isCity() ? "On the donjon roof, by the lantern" : h.isTown() ? "On the church tower roof" : "Up top";
            out.add(line("Banner stand", from, h.bannerPos, where + ", height " + h.bannerPos.getY() + ". "
                    + (h.lordAlive ? "Kill the " + (h.isTown() ? "reeve" : h.isCity() ? "king" : "lord") + " first, then raise" : "Raise")
                    + " a War Banner on it and keep their men 12 blocks off for " + net.aiagent256.stormhold.Config.CAPTURE_SECONDS.get() + "s"));
        }
        if (mine && !h.warTablePos.equals(BlockPos.ZERO)) out.add(line("War Table", from, h.warTablePos, "Stores, the garrison, repairs and fortifying"));
        var barracks = h.extras.get("barracks");
        if (h.isCity() && barracks != null && !barracks.isEmpty())
            out.add(line("Muster Post, at the barracks", from, barracks.get(0), "Right-click with emeralds: 6 a knight, sneak 5 an archer, sneak + empty off-hand 5 a crossbowman"));
        var board = h.extras.get("notice_board");
        if ((h.isCity() || h.isTown()) && board != null && !board.isEmpty())
            out.add(line("Notice Board, in the market", from, board.get(0), "Right-click it: every shop and house for sale with the way there, and work"));
        var shops = Shops.posts(h);
        var market = h.extras.get("market");
        if (!shops.isEmpty() && market != null && !market.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String k : shops.keySet()) names.add(Shops.type(k).title());
            out.add(line("Market, in the yard", from, market.get(0), String.join(", ", names) + ", each behind his counter"));
        } else if (!shops.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String k : shops.keySet()) names.add(Shops.type(k).title());
            out.add(Component.literal("  Shops: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.join(", ", names)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(". Keepers stand behind their counters, named over their heads").withStyle(ChatFormatting.GRAY)));
        }
        var houses = h.extras.get("house");
        if ((h.isCity() || h.isTown()) && houses != null && !houses.isEmpty()) {
            BlockPos nearest = null;
            int sold = 0;
            for (BlockPos d : houses) {
                if (h.ownedHouses.containsKey(d)) {
                    sold++;
                    continue;
                }
                if (nearest == null || d.distSqr(from) < nearest.distSqr(from)) nearest = d;
            }
            if (nearest != null) out.add(line("Houses for sale (" + (houses.size() - sold) + ")", from, nearest,
                    "Nearest door. Use it holding emeralds (" + City.housePrice(h) + "); yours to live and build in"));
        }
        var inn = h.extras.get("inn");
        if (inn != null && !inn.isEmpty()) out.add(line("Inn", from, inn.get(0), ""));
        var smithy = h.extras.get("smithy");
        if (smithy != null && !smithy.isEmpty()) out.add(line("Smithy", from, smithy.get(0), ""));
        if (h.isCity()) out.add(Component.literal("  The townsfolk trade like villagers: right-click a smith, a cleric, a fletcher.").withStyle(ChatFormatting.GRAY));
        out.add(Component.literal("  /stormhold guide says this again.").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    /** The holding the player stands in or nearest within reach, or null. */
    public static Holding here(HoldingManager mgr, ServerPlayer p) {
        Holding h = mgr.at(p.blockPosition(), 48);
        if (h != null) return h;
        Holding best = null;
        double bd = 400.0 * 400.0;
        for (Holding o : mgr.holdings.values()) {
            double d = o.center().distSqr(p.blockPosition());
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    private Guide() {
    }
}
