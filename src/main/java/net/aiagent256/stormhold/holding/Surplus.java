package net.aiagent256.stormhold.holding;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Market day. At dawn each castle's stores sell what nothing in the realm uses (the fields' crops, wool, hides,
 * meat, fish, paper, copper) past the last {@link #KEEP} of each, at the price the shops pay, and the emeralds go
 * into that castle's stores. Bread, iron, gold, diamonds, coal, stone, arrows and logs are never sold: the garrison,
 * the forge and the builders live on them. A lord can keep everything instead (War Table, Works).
 */
public final class Surplus {
    /** Of each thing, this many stay in each castle's stores. */
    public static final int KEEP = 64;

    /** So many of a thing for one emerald, as the shops buy it. */
    static final Map<String, Integer> PER_EMERALD = new LinkedHashMap<>();

    static {
        String[] goods = {"wheat:20", "carrot:22", "potato:26", "pumpkin:6", "melon:4", "sugar_cane:16", "sweet_berries:16", "cocoa_beans:12",
                "egg:16", "white_wool:16", "leather:6", "rabbit_hide:6", "string:12", "feather:20", "paper:24", "beef:10", "porkchop:7",
                "mutton:7", "chicken:14", "rabbit:4", "cod:15", "salmon:13", "tropical_fish:6", "rotten_flesh:32", "bone:16", "spider_eye:6",
                "gunpowder:6", "slime_ball:4", "honeycomb:6", "ink_sac:8", "clay_ball:10", "flint:20", "copper_ingot:10", "raw_copper:12"};
        for (String g : goods) {
            String[] kv = g.split(":");
            PER_EMERALD.put(kv[0], Integer.parseInt(kv[1]));
        }
    }

    private static HoldingManager book(MinecraftServer server) {
        return HoldingManager.get(server.overworld());
    }

    public static boolean selling(MinecraftServer server, UUID player) {
        return !book(server).keepSurplus.contains(player);
    }

    public static void toggle(ServerPlayer player) {
        HoldingManager b = book(player.server);
        boolean was = selling(player.server, player.getUUID());
        if (was) b.keepSurplus.add(player.getUUID());
        else b.keepSurplus.remove(player.getUUID());
        b.setDirty();
        player.sendSystemMessage(was ? Component.literal("Your stores keep everything from now on.").withStyle(ChatFormatting.GOLD)
                : HoldingEvents.gold("At dawn your stores sell what nothing in the realm uses, past the last " + KEEP + " of each, and the "
                + "emeralds go into the stores."));
    }

    /** One castle's sale: how many emeralds, and what went into {@code sold}. */
    public static int sell(Holding h, Map<String, Integer> sold) {
        int got = 0;
        for (var e : PER_EMERALD.entrySet()) {
            ResourceLocation id = new ResourceLocation("minecraft", e.getKey());
            int lots = (h.stock(id) - KEEP) / e.getValue();
            if (lots <= 0) continue;
            h.addStock(id, -lots * e.getValue());
            sold.merge(e.getKey(), lots * e.getValue(), Integer::sum);
            got += lots;
        }
        if (got > 0) h.addStock(Rewards.EMERALD, got);
        return got;
    }

    /** At dawn, for a lord: market day in every castle he holds. The line for his account, or null if nothing sold. */
    @Nullable
    public static Component dawn(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine) {
        if (!selling(level.getServer(), player)) return null;
        Map<String, Integer> sold = new LinkedHashMap<>();
        int total = 0;
        for (Holding h : mine) total += sell(h, sold);
        if (total <= 0) return null;
        mgr.setDirty();
        List<Map.Entry<String, Integer>> top = new ArrayList<>(sold.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(4, top.size()); i++) parts.add(top.get(i).getValue() + " " + top.get(i).getKey().replace('_', ' '));
        if (top.size() > 4) parts.add("and " + (top.size() - 4) + " more kinds");
        return Component.literal("Market day: the stores sold " + String.join(", ", parts) + " for " + total + " emeralds.")
                .withStyle(ChatFormatting.GREEN);
    }

    private Surplus() {
    }
}
