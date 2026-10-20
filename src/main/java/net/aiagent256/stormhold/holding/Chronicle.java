package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The realm's record. Anything that changes the map or a player's holdings is written here, and at
 * dawn every lord gets a short account of his day: what the stores hold, what grew, what the
 * builder is at, who marches where. The whole record is on the Realm page.
 */
public final class Chronicle {
    public static final int KEEP = 240;
    /** Kinds, for colour on the page: 0 news of the realm (your castles), 1 war, 2 houses and envoys, 3 growth. */
    public static final int NEWS = 0, WAR = 1, HOUSES = 2, GROWTH = 3;

    public record Entry(long day, int kind, String text, @Nullable UUID player) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Day", day);
            t.putInt("Kind", kind);
            t.putString("Text", text);
            if (player != null) t.putUUID("Player", player);
            return t;
        }

        static Entry load(CompoundTag t) {
            return new Entry(t.getLong("Day"), t.getInt("Kind"), t.getString("Text"), t.hasUUID("Player") ? t.getUUID("Player") : null);
        }
    }

    public static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** Write a line everyone can read. */
    public static void log(ServerLevel level, HoldingManager mgr, int kind, String text) {
        log(level, mgr, kind, text, null);
    }

    /** Write a line; with a player, it's about his holdings and only he sees it on his page. */
    public static void log(ServerLevel level, HoldingManager mgr, int kind, String text, @Nullable UUID player) {
        mgr.chronicle.add(new Entry(day(level), kind, text, player));
        while (mgr.chronicle.size() > KEEP) mgr.chronicle.remove(0);
        mgr.setDirty();
    }

    /** The lines a player may read: the realm's news and his own. */
    public static List<Entry> forPlayer(HoldingManager mgr, UUID player, int max) {
        List<Entry> out = new ArrayList<>();
        for (int i = mgr.chronicle.size() - 1; i >= 0 && out.size() < max; i--) {
            Entry e = mgr.chronicle.get(i);
            if (e.player == null || e.player.equals(player)) out.add(0, e);
        }
        return out;
    }

    /** Once a day, at dawn: every lord with a castle gets the day's account in chat. */
    public static void dawn(ServerLevel level, HoldingManager mgr) {
        long day = day(level);
        if (day == mgr.lastDawn) return;
        mgr.lastDawn = day;
        mgr.setDirty();
        for (ServerPlayer p : level.players()) {
            if (Seats.ensure(level, mgr, p.getUUID())) mgr.setDirty();
            List<Holding> mine = mgr.ownedBy(p.getUUID());
            if (mine.isEmpty()) continue;
            tribute(level, mgr, p.getUUID(), mine);
            for (Component c : account(level, mgr, p.getUUID(), mine, day)) p.sendSystemMessage(c);
            Component rents = Rewards.rents(level, mgr, p.getUUID(), mine);
            if (rents != null) p.sendSystemMessage(rents);
            Component market = Surplus.dawn(level, mgr, p.getUUID(), mine);
            if (market != null) p.sendSystemMessage(market);
            Component tolls = Tolls.collect(level, mgr, p.getUUID(), mine);
            if (tolls != null) p.sendSystemMessage(tolls);
            Component wages = Council.wages(level, p.getUUID(), mine);
            if (wages != null) p.sendSystemMessage(wages);
            Events.dawn(level, mgr, p, mine);
            Component roads = Builder.dawnRoads(level, mgr, p.getUUID(), mine);
            if (roads != null) p.sendSystemMessage(roads);
        }
    }

    private static final ResourceLocation BREAD = new ResourceLocation("minecraft", "bread"), IRON = new ResourceLocation("minecraft", "iron_ingot"),
            STONE = new ResourceLocation("minecraft", "cobblestone"), GOLD = new ResourceLocation("minecraft", "gold_ingot"),
            EMERALD = new ResourceLocation("minecraft", "emerald");

    /** The dawn account: five to eight lines, the rest is on the Realm page. */
    public static List<Component> account(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine, long day) {
        List<Component> out = new ArrayList<>();
        String title = Titles.name(Titles.rank(mine));
        out.add(Component.literal("Day " + day + ". " + (title.isEmpty() ? "The realm" : "The realm of the " + title) + ": " + mine.size()
                + (mine.size() == 1 ? " castle, " : " castles, ") + troops(mine) + " men under arms.").withStyle(ChatFormatting.GOLD));
        Holding first = mine.get(0);
        out.add(Component.literal("Stores: bread " + Stores.realmStock(mgr, first, player, BREAD) + ", iron "
                + Stores.realmStock(mgr, first, player, IRON) + ", stone " + Stores.realmStock(mgr, first, player, STONE)
                + ", gold " + Stores.realmStock(mgr, first, player, GOLD) + ", emeralds "
                + Stores.realmStock(mgr, first, player, EMERALD) + ".").withStyle(ChatFormatting.GRAY));
        long now = level.getGameTime();
        // threats first
        for (Holding h : mine) {
            if (Campaigns.active(mgr, h.id, Campaign.Kind.RAID)) {
                out.add(Component.literal(h.name + " is under attack.").withStyle(ChatFormatting.RED));
            } else if (h.nextRaid > 0 && Config.RAIDS_ENABLED.get() && !Standing.friendly(mgr, h.houseId, player)) {
                long days = Math.max(0, (h.nextRaid - now + 23999) / 24000);
                if (days <= 2) {
                    out.add(Component.literal((h.heir.isEmpty() ? mgr.house(h.houseId).title() : h.heir) + " will march on " + h.name
                            + (days == 0 ? " today." : days == 1 ? " tomorrow." : " in " + days + " days.")).withStyle(ChatFormatting.RED));
                }
            }
        }
        // the stewards' tallies, and vassals growing restless
        for (Holding h : mine) {
            Component w = Vassals.warning(h);
            if (w != null) out.add(w);
            Component s = Steward.report(h);
            if (s != null) out.add(s);
        }
        // the work: what the miners brought up, how the forge stands, what the builder is at, and who's really working
        for (Holding h : mine) {
            List<String> w = new ArrayList<>();
            if (h.miners > 0 || h.smiths > 0) {
                if (!h.labourReport.isEmpty()) w.add(h.labourReport);
                boolean short_ = h.minersAt < h.miners || h.smithsAt < h.smiths;
                w.add("Working: " + h.minersAt + " of " + h.miners + " miners, " + h.smithsAt + " of " + h.smiths + " smiths"
                        + (short_ ? " (no more of the people to spare: more cottages, more hands)" : "") + ".");
            }
            if (!h.work.equals(Builder.NONE)) w.add("Master Builder " + h.builderName + " is " + Builder.doing(h)
                    + (h.workQueue != null && !h.workQueue.isEmpty() ? ", " + h.workQueue.size() + " blocks to go" : "") + ".");
            if (!w.isEmpty()) out.add(Component.literal(h.name + ": " + String.join(" ", w)).withStyle(ChatFormatting.GRAY));
        }
        // what the day brought: the chronicle's lines since yesterday
        int shown = 0;
        for (int i = mgr.chronicle.size() - 1; i >= 0 && shown < 5; i--) {
            Entry e = mgr.chronicle.get(i);
            if (e.day < day - 1) break;
            if (e.player != null && !e.player.equals(player)) continue;
            out.add(Component.literal(e.text).withStyle(e.kind == WAR ? ChatFormatting.RED : e.kind == HOUSES ? ChatFormatting.AQUA
                    : e.kind == GROWTH ? ChatFormatting.GREEN : ChatFormatting.WHITE));
            shown++;
        }
        // open contracts
        for (Politics.Contract c : mgr.contracts) {
            if (c.done || c.expiresAt < now) continue;
            if (c.isBounty()) {
                out.add(Component.literal(mgr.house(c.houseId).title() + " still pays " + c.reward + " emeralds for the head of the " + c.beast
                        + " seen near " + c.x + ", " + c.z + " (" + Math.max(1, (c.expiresAt - now) / 24000) + " days left).").withStyle(ChatFormatting.AQUA));
                continue;
            }
            Holding t = mgr.holdings.get(c.target);
            if (t == null) continue;
            out.add(Component.literal(mgr.house(c.houseId).title() + " still pays " + c.reward + " emeralds for " + t.name + " ("
                    + Math.max(1, (c.expiresAt - now) / 24000) + " days left).").withStyle(ChatFormatting.AQUA));
        }
        Events.Matter open = Events.open(mgr, player);
        if (open != null) out.add(Component.literal("A matter at " + (mgr.holdings.containsKey(open.holding) ? mgr.holdings.get(open.holding).name : "your castle")
                + " still waits on your word; see the Realm page.").withStyle(ChatFormatting.YELLOW));
        out.add(Component.literal("The Realm page on the War Table has the rest.").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    /** A King takes two emeralds a day from every house that counts him a friend and still holds a castle. */
    private static void tribute(ServerLevel level, HoldingManager mgr, UUID player, List<Holding> mine) {
        if (Titles.rank(mine) < Titles.KING) return;
        int paid = 0, houses = 0;
        for (House house : mgr.houses.values()) {
            if (house.id.equals("outlaws") || !Standing.friendly(mgr, house.id, player)) continue;
            if (Politics.castlesOf(mgr, house.id).isEmpty()) continue;
            houses++;
            paid += 2;
        }
        if (paid > 0) {
            mine.get(0).addStock(EMERALD, paid);
            log(level, mgr, NEWS, houses + (houses == 1 ? " house pays" : " houses pay") + " the King " + paid + " emeralds in tribute.", player);
        }
    }

    private static int troops(List<Holding> mine) {
        int n = 0;
        for (Holding h : mine) n += h.troops();
        return n;
    }

    public static ListTag save(List<Entry> entries) {
        ListTag l = new ListTag();
        for (Entry e : entries) l.add(e.save());
        return l;
    }

    public static void load(ListTag l, List<Entry> into) {
        for (int i = 0; i < l.size(); i++) into.add(Entry.load(l.getCompound(i)));
    }

    /** A house's name for the record. */
    public static String name(House h) {
        return h.title();
    }

    private Chronicle() {
    }
}
