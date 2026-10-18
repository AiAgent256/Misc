package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Sides;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What winning pays, and what a lord's places pay him. Raids broken, warbands broken and castles taken
 * pay a purse; a house's soldiers carry coin; and every morning each place you hold sends its rents
 * and dues to you, wherever you are.
 */
public final class Rewards {
    public static final ResourceLocation EMERALD = new ResourceLocation("minecraft", "emerald");

    /** Emeralds into the lord's pack if he's on, else into a place's stores. How many reached the pack. */
    public static int pay(ServerLevel level, HoldingManager mgr, @Nullable UUID player, int emeralds, @Nullable Holding fallback) {
        if (emeralds <= 0) return 0;
        ServerPlayer sp = player == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (sp != null) {
            int left = emeralds;
            while (left > 0) {
                int n = Math.min(64, left);
                ItemStack s = new ItemStack(Items.EMERALD, n);
                if (!sp.getInventory().add(s)) sp.drop(s, false);
                left -= n;
            }
            return emeralds;
        }
        Holding into = fallback;
        if (into == null && player != null) {
            List<Holding> mine = mgr.ownedBy(player);
            if (!mine.isEmpty()) into = mine.get(0);
        }
        if (into != null) {
            into.addStock(EMERALD, emeralds);
            mgr.setDirty();
        }
        return 0;
    }

    // ------------------------------------------------------------ rents

    /** A day's rents and dues from one place: by size, renown, people and how far out it stands. */
    public static int rent(ServerLevel level, Holding h) {
        int base = h.isCity() ? 18 : h.isTown() ? 8 : h.isNether() ? 8 : switch (h.tier) {
            case 1 -> 3;
            case 2 -> 6;
            default -> 10;
        };
        int n = base + Math.max(1, h.renown) + Math.min(8, h.fiefPop / 3) + 2 * (Frontier.zone(level, h.center()) - 1);
        if (h.mode == Holding.Mode.PRODUCE) n += n / 4;
        return n;
    }

    /** At dawn: every place the lord holds that isn't under attack pays its rent to him. The account's line, or null. */
    @Nullable
    public static Component rents(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine) {
        List<String> parts = new ArrayList<>();
        int total = 0;
        List<String> besieged = new ArrayList<>();
        for (Holding h : mine) {
            if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID) || Warbands.active(mgr, h.id)) {
                besieged.add(h.name);
                continue;
            }
            int r = rent(level, h);
            total += r;
            parts.add(h.name + " " + r);
        }
        if (total <= 0 && besieged.isEmpty()) return null;
        int inPack = pay(level, mgr, player, total, mine.get(0));
        String where = inPack > 0 ? "to your purse" : "to the stores of " + mine.get(0).name;
        return Component.literal("Rents and dues: " + String.join(", ", parts) + (parts.isEmpty() ? "nothing" : "") + ". " + total
                + " emeralds " + where + "." + (besieged.isEmpty() ? "" : " " + String.join(", ", besieged) + " paid nothing: under attack."))
                .withStyle(ChatFormatting.GREEN);
    }

    // ------------------------------------------------------------ winning

    /** What a castle's treasury holds when you take it. */
    public static int treasury(ServerLevel level, Holding h) {
        int base = h.isCity() ? 120 : h.isTown() ? 25 : h.isNether() ? 45 : switch (h.tier) {
            case 1 -> 15;
            case 2 -> 30;
            default -> 60;
        };
        double zone = 1 + 0.25 * (Frontier.zone(level, h.center()) - 1);
        return (int) Math.round((base + 5 * Math.max(1, h.renown)) * zone);
    }

    /** The castle is yours: its treasury with it. */
    public static void seized(ServerLevel level, HoldingManager mgr, Holding h, UUID player, boolean peaceful) {
        int n = peaceful ? treasury(level, h) / 2 : treasury(level, h);
        pay(level, mgr, player, n, h);
        HoldingEvents.tell(level, player, Component.literal("The treasury of " + h.name + " is yours: " + n + " emeralds.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** A raid broken at your walls: the spoils of the field, three emeralds a man they brought. */
    public static int raidSpoils(Campaign c, boolean watched) {
        int men = c.spoils > 0 ? c.spoils : 10;
        return watched ? 3 * men : (3 * men) / 2;
    }

    /** The coin a soldier of a house carries, for whoever cuts him down: more for officers and far out. */
    public static int purse(RandomSource r, Role role, String side, int zone) {
        if (Sides.OUTLAWS.equals(side)) return r.nextInt(2);
        int z = Math.max(0, zone - 1);
        return switch (role) {
            case LORD -> 12 + r.nextInt(9) + 2 * z;
            case CAPTAIN -> 3 + r.nextInt(4) + z;
            case KNIGHT, ARCHER, CROSSBOWMAN -> r.nextInt(3) + z / 2;
            default -> 0;
        };
    }

    private Rewards() {
    }
}
