package net.aiagent256.stormhold.holding;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.house.House;
import net.aiagent256.stormhold.house.Politics;
import net.aiagent256.stormhold.house.Standing;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The event deck. Some mornings the steward brings a lord a matter that needs deciding: a bad harvest, a
 * smith at the gate, an envoy, rats in the cellar. Each has two answers, clickable in chat, and both
 * cost something. An unanswered matter settles itself after two days, the way unattended things do.
 */
public final class Events {
    /** Days a matter waits for an answer. */
    public static final int WAIT_DAYS = 2;
    /** Chance per lord per dawn that something comes up, in percent. */
    public static final int CHANCE = 35;

    public static final String HARVEST = "harvest", SMITH = "smith", DESERTERS = "deserters", ENVOY = "envoy", TOURNEY = "tourney",
            RATS = "rats", BRIGANDS = "brigands", MASON = "mason", WEDDING = "wedding", PETITION = "petition", SPY = "spy", RELIC = "relic";
    public static final String[] KINDS = {HARVEST, SMITH, DESERTERS, ENVOY, TOURNEY, RATS, BRIGANDS, MASON, WEDDING, PETITION, SPY, RELIC};

    private static final ResourceLocation BREAD = new ResourceLocation("minecraft", "bread"), IRON = new ResourceLocation("minecraft", "iron_ingot"),
            STONE = new ResourceLocation("minecraft", "cobblestone"), GOLD = new ResourceLocation("minecraft", "gold_ingot"),
            EMERALD = new ResourceLocation("minecraft", "emerald");

    /** A matter waiting on a lord's word. */
    public static class Matter {
        public UUID id = UUID.randomUUID();
        public UUID player;
        public UUID holding;
        public String kind = "";
        /** A house the matter concerns, or "". */
        public String houseId = "";
        /** A number rolled when the matter was drawn, so the outcome is fixed before the choice. */
        public int roll;
        public long day;
        public long expiresAt;
        public boolean done;

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", id);
            t.putUUID("Player", player);
            t.putUUID("Holding", holding);
            t.putString("Kind", kind);
            t.putString("House", houseId);
            t.putInt("Roll", roll);
            t.putLong("Day", day);
            t.putLong("Expires", expiresAt);
            t.putBoolean("Done", done);
            return t;
        }

        public static Matter load(CompoundTag t) {
            Matter m = new Matter();
            if (t.hasUUID("Id")) m.id = t.getUUID("Id");
            m.player = t.hasUUID("Player") ? t.getUUID("Player") : null;
            m.holding = t.hasUUID("Holding") ? t.getUUID("Holding") : null;
            m.kind = t.getString("Kind");
            m.houseId = t.getString("House");
            m.roll = t.getInt("Roll");
            m.day = t.getLong("Day");
            m.expiresAt = t.getLong("Expires");
            m.done = t.getBoolean("Done");
            return m;
        }
    }

    // ------------------------------------------------------------ drawing

    /** The matter a player is still to answer, if any. */
    @Nullable
    public static Matter open(HoldingManager mgr, UUID player) {
        for (Matter m : mgr.matters) if (!m.done && player.equals(m.player)) return m;
        return null;
    }

    /** At dawn: perhaps bring a lord a matter, and tell him. */
    public static void dawn(ServerLevel level, HoldingManager mgr, ServerPlayer p, List<Holding> mine) {
        if (!Config.EVENTS.get() || mine.isEmpty() || open(mgr, p.getUUID()) != null) return;
        RandomSource r = level.random;
        if (r.nextInt(100) >= CHANCE) return;
        Holding h = mine.get(r.nextInt(mine.size()));
        List<String> fit = new ArrayList<>();
        for (String k : KINDS) if (fits(mgr, h, p.getUUID(), k)) fit.add(k);
        if (fit.isEmpty()) return;
        Matter m = draw(level, mgr, p.getUUID(), h, fit.get(r.nextInt(fit.size())));
        if (m != null) for (Component c : present(mgr, m)) p.sendSystemMessage(c);
    }

    /** Whether a matter of this kind can come up at this holding for this player. */
    public static boolean fits(HoldingManager mgr, Holding h, UUID player, String kind) {
        return switch (kind) {
            case HARVEST, WEDDING -> h.fiefPop > 0 || h.isTown();
            case DESERTERS, RATS -> h.troops() >= 6;
            case ENVOY -> h.houseId != null && !h.houseId.equals("outlaws")
                    && !Standing.friendly(mgr, h.houseId, player) && !Politics.castlesOf(mgr, h.houseId).isEmpty();
            case TOURNEY -> friendlyHouse(mgr, player) != null;
            case SPY -> rivalHouse(mgr, player) != null;
            case MASON -> !h.isTown() && h.fortify < 3 && h.work.equals(Builder.NONE);
            case PETITION -> h.isTown();
            case RELIC -> !h.isTown() && h.tier >= 2;
            default -> true;
        };
    }

    @Nullable
    private static String friendlyHouse(HoldingManager mgr, UUID player) {
        for (House house : mgr.houses.values()) {
            if (house.id.equals("outlaws") || Politics.castlesOf(mgr, house.id).isEmpty()) continue;
            if (Standing.friendly(mgr, house.id, player)) return house.id;
        }
        return null;
    }

    @Nullable
    private static String rivalHouse(HoldingManager mgr, UUID player) {
        for (House house : mgr.houses.values()) {
            if (house.id.equals("outlaws") || Politics.castlesOf(mgr, house.id).isEmpty()) continue;
            if (Standing.get(mgr, house.id, player) < Standing.FRIENDLY) return house.id;
        }
        return null;
    }

    /** Draw a matter of a kind for a holding. Null if it doesn't fit. */
    @Nullable
    public static Matter draw(ServerLevel level, HoldingManager mgr, UUID player, Holding h, String kind) {
        if (!fits(mgr, h, player, kind)) return null;
        Matter m = new Matter();
        m.player = player;
        m.holding = h.id;
        m.kind = kind;
        m.roll = 2 + level.random.nextInt(4);
        m.day = Chronicle.day(level);
        m.expiresAt = level.getGameTime() + WAIT_DAYS * 24000L;
        m.houseId = switch (kind) {
            case ENVOY -> h.houseId;
            case TOURNEY -> friendlyHouse(mgr, player);
            case SPY -> rivalHouse(mgr, player);
            default -> "";
        };
        if (m.houseId == null) m.houseId = "";
        mgr.matters.add(m);
        mgr.setDirty();
        return m;
    }

    // ------------------------------------------------------------ the deck

    /** The story and the two answers. */
    public record Card(String text, String first, String second) {
    }

    public static Card card(HoldingManager mgr, Matter m) {
        Holding h = mgr.holdings.get(m.holding);
        String name = h == null ? "your castle" : h.name;
        String house = m.houseId.isEmpty() ? "a house" : mgr.house(m.houseId).title();
        int n = m.roll;
        return switch (m.kind) {
            case HARVEST -> new Card("Blight has taken the fields of " + name + ". The steward asks to open the granary to the people.",
                    "Open the granary (20 bread)", "Let them manage (" + n + " souls leave)");
            case SMITH -> new Card("A wandering smith is at the gate of " + name + " with a cart of iron and an eye for a forge.",
                    "Hire him (8 emeralds for 24 iron)", "Send him on");
            case DESERTERS -> new Card(n + " men of " + name + " slipped away in the night with their arms.",
                    "Send riders after them (10 bread)", "Let them go (lose " + n + " men)");
            case ENVOY -> new Card("An envoy of " + house + " rides to " + name + " under a white banner. For gold, they would hold their swords a while.",
                    "Pay 12 gold (standing +15, no raid for 3 days)", "Turn him away (standing -5)");
            case TOURNEY -> new Card(house + " holds a tourney and invites the lord of " + name + ".",
                    "Ride to it (6 emeralds, renown +1, standing +8)", "Send regrets (standing -3)");
            case RATS -> new Card("Rats in the cellars of " + name + ". The cook says half the bread is spoiled already.",
                    "Burn the spoiled stores (half the bread)", "Risk it (" + n + " men fall sick)");
            case BRIGANDS -> new Card("Brigands hold the road below " + name + " and take a toll from every cart.",
                    "Pay them off (10 emeralds)", "Clear the road (lose 2 men, take 15 emeralds)");
            case MASON -> new Card("A master mason offers to raise the walls of " + name + ", for stone.",
                    "Set him to work (40 stone, fortified +1)", "Not now");
            case WEDDING -> new Card("The miller's daughter weds at " + name + " and the people look to their lord for a feast.",
                    "Hold a feast (30 bread, 4 souls come)", "No feast (1 soul leaves)");
            case PETITION -> new Card("The guilds of " + name + " petition against the tolls at the gate.",
                    "Lower the tolls (6 emeralds, 3 souls come)", "Keep them (6 emeralds, 2 souls leave)");
            case SPY -> new Card("A spy of " + house + " is caught in the kitchens of " + name + ".",
                    "Hang him (standing -10, renown +1)", "Ransom him (10 emeralds, standing +3)");
            case RELIC -> new Card("The men digging a cellar under " + name + " found a saint's bones in a lead box.",
                    "Give them to the chapel (renown +1)", "Sell them (20 emeralds)");
            default -> new Card("Nothing of note at " + name + ".", "So be it", "So be it");
        };
    }

    /** The matter as chat: the story, then two clickable answers. */
    public static List<Component> present(HoldingManager mgr, Matter m) {
        Card c = card(mgr, m);
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(c.text).withStyle(ChatFormatting.YELLOW));
        out.add(Component.literal("  ").append(choice(m, 1, c.first)).append(Component.literal("   ")).append(choice(m, 2, c.second)));
        return out;
    }

    private static MutableComponent choice(Matter m, int which, String label) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/stormhold choose " + m.id + " " + which))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to decide"))));
    }

    // ------------------------------------------------------------ resolving

    /** A player's answer. False if the matter isn't his or is settled. */
    public static boolean choose(ServerLevel level, HoldingManager mgr, UUID player, UUID matterId, int which) {
        for (Matter m : mgr.matters) {
            if (!m.id.equals(matterId)) continue;
            if (m.done || !player.equals(m.player)) return false;
            resolve(level, mgr, m, which == 1, false);
            return true;
        }
        return false;
    }

    /** Settle a matter one way or the other, and write what came of it. */
    public static String resolve(ServerLevel level, HoldingManager mgr, Matter m, boolean first, boolean lapsed) {
        m.done = true;
        Holding h = mgr.holdings.get(m.holding);
        UUID p = m.player;
        String line;
        if (h == null) {
            line = "The matter came to nothing; the castle is gone.";
        } else {
            int n = m.roll;
            String name = h.name;
            String house = m.houseId.isEmpty() ? "" : mgr.house(m.houseId).title();
            switch (m.kind) {
                case HARVEST -> {
                    if (first) {
                        int took = 20 - Stores.take(mgr, h, p, BREAD, 20);
                        line = "The granary of " + name + " fed the people through the blight (" + took + " bread).";
                    } else {
                        h.fiefPop = Math.max(0, h.fiefPop - n);
                        line = n + " souls left the fields of " + name + " after the blight.";
                    }
                }
                case SMITH -> {
                    if (first && pay(mgr, h, p, EMERALD, 8)) {
                        h.addStock(IRON, 24);
                        line = "A wandering smith sold " + name + " 24 iron.";
                    } else line = first ? "The smith left " + name + ": the treasury could not pay him." : "The smith passed " + name + " by.";
                }
                case DESERTERS -> {
                    if (first && pay(mgr, h, p, BREAD, 10)) {
                        line = "Riders brought the deserters back to " + name + ".";
                    } else {
                        lose(h, n);
                        line = n + " men deserted " + name + ".";
                    }
                }
                case ENVOY -> {
                    if (first && !house.isEmpty() && pay(mgr, h, p, GOLD, 12)) {
                        Standing.change(level, mgr, m.houseId, p, 15, "gold for peace");
                        long now = level.getGameTime();
                        h.nextRaid = Math.max(h.nextRaid, now + 3 * 24000L);
                        h.raidWarned = false;
                        line = house + " took 12 gold from " + name + " and sheathed its swords for a time.";
                    } else {
                        if (!house.isEmpty()) Standing.change(level, mgr, m.houseId, p, -5, "envoy turned away");
                        line = "The envoy of " + house + " rode from " + name + " empty-handed.";
                    }
                }
                case TOURNEY -> {
                    if (first && !house.isEmpty() && pay(mgr, h, p, EMERALD, 6)) {
                        h.renown = Math.min(5, h.renown + 1);
                        Standing.change(level, mgr, m.houseId, p, 8, "rode in the tourney");
                        line = "The lord of " + name + " rode in the tourney of " + house + " and was cheered.";
                    } else {
                        if (!house.isEmpty()) Standing.change(level, mgr, m.houseId, p, -3, "declined the tourney");
                        line = "The lord of " + name + " sent regrets to the tourney of " + house + ".";
                    }
                }
                case RATS -> {
                    if (first) {
                        int bread = h.stock(BREAD);
                        h.addStock(BREAD, -(bread / 2));
                        line = "Half the bread of " + name + " went on the fire; the rats went with it.";
                    } else {
                        lose(h, n);
                        line = n + " men of " + name + " sickened from the spoiled stores.";
                    }
                }
                case BRIGANDS -> {
                    if (first && pay(mgr, h, p, EMERALD, 10)) {
                        line = name + " paid the brigands 10 emeralds to leave the road.";
                    } else {
                        lose(h, 2);
                        h.addStock(EMERALD, 15);
                        line = "Men of " + name + " cleared the brigands from the road, at a cost of two, and took 15 emeralds.";
                    }
                }
                case MASON -> {
                    if (first && pay(mgr, h, p, STONE, 40)) {
                        h.fortify++;
                        line = "The mason raised the walls of " + name + ".";
                    } else line = first ? "The mason left " + name + ": not enough stone." : "The mason left " + name + " for other work.";
                }
                case WEDDING -> {
                    if (first && pay(mgr, h, p, BREAD, 30)) {
                        h.fiefPop += 4;
                        line = "A wedding feast at " + name + "; four more souls came to stay.";
                    } else {
                        h.fiefPop = Math.max(0, h.fiefPop - 1);
                        line = "No feast at " + name + ". The miller's family went to the next valley.";
                    }
                }
                case PETITION -> {
                    if (first && pay(mgr, h, p, EMERALD, 6)) {
                        h.fiefPop += 3;
                        line = name + " lowered its tolls; three more families came to trade.";
                    } else {
                        h.addStock(EMERALD, 6);
                        h.fiefPop = Math.max(0, h.fiefPop - 2);
                        line = name + " kept its tolls and two families left.";
                    }
                }
                case SPY -> {
                    if (first) {
                        if (!house.isEmpty()) Standing.change(level, mgr, m.houseId, p, -10, "hanged a spy");
                        h.renown = Math.min(5, h.renown + 1);
                        line = "A spy of " + house + " hangs from the walls of " + name + ".";
                    } else {
                        h.addStock(EMERALD, 10);
                        if (!house.isEmpty()) Standing.change(level, mgr, m.houseId, p, 3, "ransomed a spy");
                        line = house + " paid 10 emeralds for its spy, caught at " + name + ".";
                    }
                }
                case RELIC -> {
                    if (first) {
                        h.renown = Math.min(5, h.renown + 1);
                        line = "The relic found under " + name + " lies in its chapel; pilgrims come to see it.";
                    } else {
                        h.addStock(EMERALD, 20);
                        line = "The relic found under " + name + " was sold for 20 emeralds.";
                    }
                }
                default -> line = "Nothing came of it at " + name + ".";
            }
        }
        if (lapsed) line = "Left unanswered: " + line;
        Chronicle.log(level, mgr, Chronicle.NEWS, line, p);
        HoldingEvents.tell(level, p, Component.literal(line).withStyle(lapsed ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
        mgr.setDirty();
        return line;
    }

    /** Take n of an item from the realm's stores; false (and nothing taken) if they haven't got it. */
    private static boolean pay(HoldingManager mgr, Holding h, UUID p, ResourceLocation item, int n) {
        if (Stores.realmStock(mgr, h, p, item) < n) return false;
        Stores.take(mgr, h, p, item, n);
        return true;
    }

    /** Lose n men from the garrison, the common ranks first. */
    private static void lose(Holding h, int n) {
        for (Role r : new Role[]{Role.ARCHER, Role.CROSSBOWMAN, Role.KNIGHT, Role.CAPTAIN}) {
            while (n > 0 && h.count(r) > 0) {
                h.add(r, -1);
                n--;
            }
        }
    }

    // ------------------------------------------------------------ time

    /** Matters nobody answered settle themselves the second way. */
    public static void tick(ServerLevel level, HoldingManager mgr, long now) {
        for (Matter m : mgr.matters) {
            if (!m.done && m.expiresAt < now) resolve(level, mgr, m, false, true);
        }
        mgr.matters.removeIf(m -> m.done && m.expiresAt < now - 24000L * 3);
    }

    public static ListTag save(List<Matter> matters) {
        ListTag l = new ListTag();
        for (Matter m : matters) l.add(m.save());
        return l;
    }

    public static void load(ListTag l, List<Matter> into) {
        for (int i = 0; i < l.size(); i++) {
            Matter m = Matter.load(l.getCompound(i));
            if (m.player != null && m.holding != null) into.add(m);
        }
    }

    private Events() {
    }
}
