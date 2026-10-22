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
 * Market day. At dawn each castle's stores sell what nothing in the realm uses (carrots and potatoes, wool, hides,
 * meat, fish, paper, copper) past the last {@link #KEEP} of each, at the price the shops pay, and the emeralds go
 * into that castle's stores. Bread, wheat (the ovens bake it), iron, gold, diamonds, coal, stone, arrows and logs are
 * never sold: the garrison, the forge and the builders live on them. And when the realm's bread runs short the market
 * buys it in: up to {@link #BUY_MOST} emeralds' worth a day at the shops' six loaves to the emerald, from the stores'
 * emeralds. A lord can keep out of the market instead (War Table, Works).
 */
public final class Surplus {
    /** Of each thing, this many stay in each castle's stores. */
    public static final int KEEP = 64;
    /** Below this much bread in the whole realm the market buys more, up to the second figure; at most so many emeralds a day. */
    public static final int BREAD_LOW = 160, BREAD_TO = 320, BUY_MOST = 64, LOAVES_PER_EMERALD = 6;

    /** So many of a thing for one emerald, as the shops buy it. */
    static final Map<String, Integer> PER_EMERALD = new LinkedHashMap<>();

    static {
        String[] goods = {"carrot:22", "potato:26", "pumpkin:6", "melon:4", "sugar_cane:16", "sweet_berries:16", "cocoa_beans:12",
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
        int[] bought = buyBread(mgr, player, mine);
        if (total <= 0 && bought[0] <= 0) return null;
        mgr.setDirty();
        if (total <= 0) return Component.literal("Market day: bread was short, so the stores bought " + bought[0] + " loaves for " + bought[1]
                + " emeralds.").withStyle(ChatFormatting.GREEN);
        List<Map.Entry<String, Integer>> top = new ArrayList<>(sold.entrySet());
        top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(4, top.size()); i++) parts.add(top.get(i).getValue() + " " + top.get(i).getKey().replace('_', ' '));
        if (top.size() > 4) parts.add("and " + (top.size() - 4) + " more kinds");
        return Component.literal("Market day: the stores sold " + String.join(", ", parts) + " for " + total + " emeralds."
                + (bought[0] > 0 ? " Bread was short: they bought " + bought[0] + " loaves for " + bought[1] + " emeralds." : ""))
                .withStyle(ChatFormatting.GREEN);
    }

    /** Bread bought in when the realm's is short: {loaves, emeralds}. Into the seat's stores (or the first castle's). */
    public static int[] buyBread(HoldingManager mgr, UUID player, List<Holding> mine) {
        if (mine.isEmpty()) return new int[]{0, 0};
        ResourceLocation bread = new ResourceLocation("minecraft", "bread");
        Holding first = mgr.seatOf(player);
        if (first == null) first = mine.get(0);
        int have = Stores.realmStock(mgr, first, player, bread);
        if (have >= BREAD_LOW) return new int[]{0, 0};
        int emeralds = Math.min(BUY_MOST, (BREAD_TO - have + LOAVES_PER_EMERALD - 1) / LOAVES_PER_EMERALD);
        emeralds = Math.min(emeralds, Stores.realmStock(mgr, first, player, Rewards.EMERALD));
        if (emeralds <= 0) return new int[]{0, 0};
        Stores.take(mgr, first, player, Rewards.EMERALD, emeralds);
        first.addStock(bread, emeralds * LOAVES_PER_EMERALD);
        return new int[]{emeralds * LOAVES_PER_EMERALD, emeralds};
    }

    private Surplus() {
    }
}
