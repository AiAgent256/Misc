package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.house.NameGen;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lord's council: four great offices, each held by someone you name and pay every morning from the
 * realm's stores. The Marshal makes every garrison bigger and harder to beat; the Spymaster hears of
 * raids and warbands days sooner and makes the scouts faster and safer; the Treasurer gets more out of
 * every rent and better prices in the shops; the Master of Works doubles the builders' pace and cuts what
 * fortifications and roads cost. Miss a morning's wages and the officer goes home.
 */
public final class Council {
    public enum Seat {
        MARSHAL("Marshal", 6, "every garrison holds 15% more men, and raids and warbands fought out while you're away meet 20% more resistance"),
        SPYMASTER("Spymaster", 5, "raids and warbands are reported three days ahead instead of one, and scouts ride half again as fast at half the risk"),
        TREASURER("Treasurer", 4, "rents come in 20% higher, and every shop knocks another 10% off for you"),
        WORKS("Master of Works", 4, "the Master Builders work twice as fast, fortifying costs a quarter less and roads half");

        public final String title;
        public final int wage;
        public final String does;

        Seat(String title, int wage, String does) {
            this.title = title;
            this.wage = wage;
            this.does = does;
        }

        @Nullable
        public static Seat byName(String s) {
            String k = s.toLowerCase(Locale.ROOT).replace(' ', '_');
            for (Seat seat : values()) if (seat.name().toLowerCase(Locale.ROOT).equals(k) || seat.title.toLowerCase(Locale.ROOT).replace(' ', '_').equals(k)) return seat;
            return k.equals("works") || k.equals("master") ? WORKS : null;
        }
    }

    /** Who sits on each lord's council, by player: kept here for the places that can't reach the save (garrison sizes). */
    private static final Map<UUID, EnumMap<Seat, String>> ACTIVE = new ConcurrentHashMap<>();

    public static boolean has(@Nullable UUID player, Seat seat) {
        if (player == null) return false;
        EnumMap<Seat, String> c = ACTIVE.get(player);
        return c != null && c.containsKey(seat);
    }

    @Nullable
    public static String holder(@Nullable UUID player, Seat seat) {
        if (player == null) return null;
        EnumMap<Seat, String> c = ACTIVE.get(player);
        return c == null ? null : c.get(seat);
    }

    /** The council is kept in the overworld's record. */
    private static HoldingManager record(ServerLevel level) {
        return HoldingManager.get(level.getServer().overworld());
    }

    public static boolean appoint(ServerLevel level, ServerPlayer player, Seat seat) {
        HoldingManager mgr = record(level);
        UUID me = player.getUUID();
        if (HoldingManager.get(level).ownedBy(me).isEmpty() && mgr.ownedBy(me).isEmpty()) {
            player.sendSystemMessage(Component.literal("A council is for a lord with a castle to his name.").withStyle(ChatFormatting.RED));
            return false;
        }
        EnumMap<Seat, String> c = mgr.councils.computeIfAbsent(me, k -> new EnumMap<>(Seat.class));
        if (c.containsKey(seat)) {
            player.sendSystemMessage(Component.literal(c.get(seat) + " is your " + seat.title + " already.").withStyle(ChatFormatting.RED));
            return false;
        }
        String name = NameGen.first(level.random) + (level.random.nextBoolean() ? " of " + NameGen.place(level.random, 2) : "");
        c.put(seat, name);
        ACTIVE.put(me, c.clone());
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(name + " takes the seat of " + seat.title + " on your council: " + seat.does + ". Wages "
                + seat.wage + " emeralds a day, from the realm's stores each morning."));
        return true;
    }

    public static boolean dismiss(ServerLevel level, ServerPlayer player, Seat seat) {
        HoldingManager mgr = record(level);
        EnumMap<Seat, String> c = mgr.councils.get(player.getUUID());
        if (c == null || !c.containsKey(seat)) return false;
        String name = c.remove(seat);
        ACTIVE.put(player.getUUID(), c.clone());
        mgr.setDirty();
        player.sendSystemMessage(HoldingEvents.gold(name + " gives up the seat of " + seat.title + "."));
        return true;
    }

    /** The council in chat: who holds what, and buttons to appoint and dismiss. */
    public static void show(ServerLevel level, ServerPlayer player) {
        HoldingManager mgr = record(level);
        EnumMap<Seat, String> c = mgr.councils.getOrDefault(player.getUUID(), new EnumMap<>(Seat.class));
        int wages = 0;
        for (var e : c.entrySet()) wages += e.getKey().wage;
        player.sendSystemMessage(Component.literal("Your council" + (c.isEmpty() ? ": every seat is empty." : ", " + wages + " emeralds a day in wages:"))
                .withStyle(ChatFormatting.GOLD));
        for (Seat seat : Seat.values()) {
            String who = c.get(seat);
            MutableComponent line = Component.literal("  " + seat.title + ": ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(who == null ? "empty. " : who + ". ").withStyle(who == null ? ChatFormatting.GRAY : ChatFormatting.WHITE));
            String cmd = "/stormhold council " + (who == null ? "appoint " : "dismiss ") + seat.name().toLowerCase(Locale.ROOT);
            line.append(Component.literal(who == null ? "[Appoint, " + seat.wage + "/day]" : "[Dismiss]")
                    .setStyle(Style.EMPTY.withColor(who == null ? ChatFormatting.GREEN : ChatFormatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(seat.title + ": " + seat.does + ".")))));
            player.sendSystemMessage(line);
        }
    }

    /** At dawn: the council's wages out of the realm's stores; whoever can't be paid goes home. The account's line, or null. */
    @Nullable
    public static Component wages(ServerLevel level, UUID player, List<Holding> mine) {
        HoldingManager mgr = record(level);
        EnumMap<Seat, String> c = mgr.councils.get(player);
        if (c == null || c.isEmpty() || mine.isEmpty()) return null;
        HoldingManager here = HoldingManager.get(level);
        int paid = 0;
        StringBuilder left = new StringBuilder();
        for (Seat seat : Seat.values()) {
            if (!c.containsKey(seat)) continue;
            if (Stores.realmStock(here, mine.get(0), player, Rewards.EMERALD) >= seat.wage) {
                Stores.take(here, mine.get(0), player, Rewards.EMERALD, seat.wage);
                paid += seat.wage;
            } else {
                left.append(c.remove(seat)).append(" (").append(seat.title).append(") ");
            }
        }
        ACTIVE.put(player, c.clone());
        mgr.setDirty();
        if (left.length() > 0)
            return Component.literal("Council wages: " + paid + " emeralds paid. Unpaid, " + left.toString().trim() + " went home.")
                    .withStyle(ChatFormatting.RED);
        return Component.literal("Council wages: " + paid + " emeralds from the stores.").withStyle(ChatFormatting.GRAY);
    }

    // ------------------------------------------------------------ the record

    public static CompoundTag save(Map<UUID, EnumMap<Seat, String>> councils) {
        CompoundTag t = new CompoundTag();
        councils.forEach((p, c) -> {
            CompoundTag one = new CompoundTag();
            c.forEach((seat, name) -> one.putString(seat.name(), name));
            t.put(p.toString(), one);
        });
        return t;
    }

    public static void load(CompoundTag t, Map<UUID, EnumMap<Seat, String>> into) {
        for (String k : t.getAllKeys()) {
            EnumMap<Seat, String> c = new EnumMap<>(Seat.class);
            CompoundTag one = t.getCompound(k);
            for (String s : one.getAllKeys()) {
                try {
                    c.put(Seat.valueOf(s), one.getString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
            UUID id = UUID.fromString(k);
            into.put(id, c);
            ACTIVE.put(id, c.clone());
        }
    }

    /** For tests and a fresh world: forget who sat where. */
    public static void forget(UUID player) {
        ACTIVE.remove(player);
    }

    private Council() {
    }
}
