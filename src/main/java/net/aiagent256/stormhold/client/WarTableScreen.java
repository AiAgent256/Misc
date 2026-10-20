package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.holding.Builder;
import net.aiagent256.stormhold.holding.Columns;
import net.aiagent256.stormhold.holding.Company;
import net.aiagent256.stormhold.holding.Labour;
import net.aiagent256.stormhold.holding.Scouts;
import net.aiagent256.stormhold.holding.Vassals;
import net.aiagent256.stormhold.net.HoldingInfo;
import net.aiagent256.stormhold.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Command every castle you hold. Your places down the left; for the chosen one, four pages: Overview (what
 * it has and what's happening), Orders (stance, target, war parties, scouts), Company (the men who ride with
 * you and their kit) and Works (builder, the people at the mine and forge, couriers). A fifth page, Council,
 * is your four officers, who serve the whole realm. The frame fits the window down to the smallest GUI, and
 * the last thing the server says while it's open shows along its bottom instead of behind it in the chat.
 */
public class WarTableScreen extends Screen {
    private static final int BG = 0xF0241c14, PANEL = 0xF0302619, CARD = 0xF0423525, LINE = 0xFF6b5a40;
    private static final int GOLD = 0xF0D8A0, TEXT = 0xE0D0B0, DIM = 0x9C8C6C, WARN = 0xE89060, BLUE = 0xA8B8C8, NAME = 0xFFE8B0;
    private static final String[] TABS = {"Overview", "Orders", "Company", "Works", "Council"};
    private static final int OVERVIEW = 0, ORDERS = 1, COMPANY = 2, WORKS = 3, COUNCIL = 4, DECIDE = 5, TARGETS = 6;
    private static final int SIDE = 104, BH = 20, STEP = 22, MIN_BUTTON = 64;

    private Net.WarTableData data;
    private int selected, page, tab, scroll;
    private int W, H;
    private EditBox ax, az, sx, sy, sz;
    /** What the player typed into the target and courier boxes, kept while he clicks about; reset for another place. */
    @Nullable private UUID typedFor;
    @Nullable private String[] typed;
    private Component notice = Component.empty();
    private long noticeAt;
    /** How many of each (knights, archers, crossbowmen) the player wants to take; kept between refreshes. */
    private final int[] want = {4, 2, 0};
    private boolean enchant;
    // where init put things, for render to draw their labels beside them
    private int textBottom, stanceTextY, targetY, oddsY, noteY, armouryY, peopleY, stepY, labourY, courierY;
    private int overviewHeight;

    /** A castle to open on, picked on the Realm map before the table's data came back. */
    @Nullable private static java.util.UUID toSelect;
    private boolean pageToSelected;

    public static void select(java.util.UUID id) {
        toSelect = id;
    }

    /** The page to open on, picked on the Realm map. */
    @Nullable private static Integer toTab;

    public static void openOrders() {
        toTab = ORDERS;
    }

    public WarTableScreen(Net.WarTableData data) {
        super(Component.literal("War Table"));
        this.data = data;
        pick();
    }

    public void update(Net.WarTableData d) {
        this.data = d;
        if (selected >= d.holdings().size()) selected = Math.max(0, d.holdings().size() - 1);
        if (tab == DECIDE && d.decision() == null) tab = OVERVIEW;
        pick();
        this.rebuildWidgets();
    }

    private void pick() {
        if (toTab != null) {
            tab = toTab;
            toTab = null;
        }
        if (toSelect == null) return;
        List<HoldingInfo> l = data.holdings();
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id().equals(toSelect)) {
                selected = i;
                tab = OVERVIEW;
                pageToSelected = true;
            }
        }
        toSelect = null;
    }

    /** Something the server said while the table is open: it shows along the bottom for a while. */
    public void notice(Component c) {
        this.notice = c;
        this.noticeAt = System.currentTimeMillis();
    }

    @Nullable
    private HoldingInfo current() {
        List<HoldingInfo> l = data.holdings();
        return l.isEmpty() ? null : l.get(Math.min(selected, l.size() - 1));
    }

    private int left() {
        return (this.width - W) / 2;
    }

    private int top() {
        return (this.height - H) / 2;
    }

    private int mainX() {
        return left() + SIDE + 8;
    }

    private int mainW() {
        return W - SIDE - 14;
    }

    private int contentTop() {
        return top() + 54;
    }

    private int contentBottom() {
        return top() + H - 18;
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        W = Math.min(480, Math.max(360, this.width - 12));
        H = Math.min(300, Math.max(214, this.height - 12));
        keepTyped();
        int x0 = left(), y0 = top();
        sideList(x0, y0);
        int mx = mainX(), mw = mainW();
        // the pages: the one you're on is lit and can't be pressed
        int tw = (mw + 2) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            Button b = Button.builder(Component.literal(TABS[i]), x -> {
                tab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(mx + i * tw, y0 + 31, tw - 2, 16).build();
            b.active = tab != i;
            this.addRenderableWidget(b);
        }
        HoldingInfo h = current();
        if (tab == DECIDE && data.decision() != null) decisionWidgets(mx, mw);
        else if (tab == TARGETS) targetsWidgets(mx, mw);
        else if (tab == COUNCIL) councilWidgets(mx, mw);
        else if (h != null) {
            switch (tab) {
                case ORDERS -> ordersWidgets(h, mx, mw);
                case COMPANY -> companyWidgets(h, mx, mw);
                case WORKS -> worksWidgets(h, mx, mw);
                default -> overviewWidgets(h, mx, mw);
            }
        }
    }

    /** Your places, a page at a time, then the Realm map. */
    private void sideList(int x0, int y0) {
        List<HoldingInfo> l = data.holdings();
        boolean deciding = data.decision() != null;
        int listTop = y0 + 20, listBottom = y0 + H - 46 - (deciding ? 18 : 0);
        int perPage = Math.max(1, (listBottom - listTop) / 18);
        boolean paged = l.size() > perPage;
        if (paged) {
            listBottom -= 18;
            perPage = Math.max(1, (listBottom - listTop) / 18);
        }
        if (pageToSelected) {
            page = selected / perPage;
            pageToSelected = false;
        }
        page = Math.min(page, Math.max(0, (l.size() - 1) / perPage));
        int start = page * perPage;
        for (int i = start; i < Math.min(l.size(), start + perPage); i++) {
            final int idx = i;
            HoldingInfo h = l.get(i);
            boolean alarm = h.status().contains("Under attack") || h.status().contains("Illager warband");
            Button b = Button.builder(Component.literal((alarm ? "! " : "") + trim(h.name(), alarm ? 13 : 15)), x -> {
                selected = idx;
                scroll = 0;
                if (tab == COUNCIL || tab == DECIDE || tab == TARGETS) tab = OVERVIEW;
                rebuildWidgets();
            }).tooltip(Tooltip.create(Component.literal(h.name() + "\n" + (h.status().isEmpty() ? "All quiet." : h.status()))))
                    .bounds(x0 + 6, listTop + (i - start) * 18, SIDE - 12, 16).build();
            b.active = idx != selected || tab == COUNCIL || tab == DECIDE || tab == TARGETS;
            this.addRenderableWidget(b);
        }
        if (paged) {
            final int pp = perPage;
            Button prev = Button.builder(Component.literal("<"), b -> {
                page = Math.max(0, page - 1);
                rebuildWidgets();
            }).bounds(x0 + 6, listBottom + (deciding ? 20 : 2), 20, 16).build();
            prev.active = page > 0;
            this.addRenderableWidget(prev);
            Button next = Button.builder(Component.literal(">"), b -> {
                page = Math.min((l.size() - 1) / pp, page + 1);
                rebuildWidgets();
            }).bounds(x0 + SIDE - 26, listBottom + (deciding ? 20 : 2), 20, 16).build();
            next.active = page < (l.size() - 1) / perPage;
            this.addRenderableWidget(next);
        }
        if (deciding) {
            Button d = Button.builder(Component.literal("! Your word is needed").withStyle(net.minecraft.ChatFormatting.GOLD), b -> {
                tab = DECIDE;
                rebuildWidgets();
            }).tooltip(Tooltip.create(Component.literal(data.decision().text()))).bounds(x0 + 6, listBottom + 2, SIDE - 12, 16).build();
            d.active = tab != DECIDE;
            this.addRenderableWidget(d);
        }
        Button attack = Button.builder(Component.literal("Attack a castle"), b -> {
            tab = TARGETS;
            rebuildWidgets();
        }).tooltip(Tooltip.create(Component.literal("The castles you know near your own, nearest first: who holds them, how many defend them in "
                + "what kit, what every castle of yours would send, and the chance. One click sends them all.")))
                .bounds(x0 + 6, y0 + H - 42, SIDE - 12, 16).build();
        attack.active = tab != TARGETS;
        this.addRenderableWidget(attack);
        this.addRenderableWidget(Button.builder(Component.literal("Realm map"), b -> Net.sendAction(new UUID(0, 0), Net.OPEN_REALM, BlockPos.ZERO, 0))
                .tooltip(Tooltip.create(Component.literal("Every castle you know on a map, the houses and how they stand with you, and the realm's record.")))
                .bounds(x0 + 6, y0 + H - 24, SIDE - 12, 16).build());
    }

    private void overviewWidgets(HoldingInfo h, int mx, int mw) {
        List<Button> row = new ArrayList<>();
        row.add(button("Take 64 emeralds", "From the realm's stores into your pack (" + data.treasury() + " there now). Rents, the steward's "
                + "sales and spoils collect there while you're away.", () -> Net.sendAction(h.id(), Net.TREASURY, BlockPos.ZERO, 0)));
        boolean vassal = h.status().contains("Vassal: ");
        row.add(button(vassal ? "Gift vassal" : "Grant to a vassal", vassal
                ? "A purse of " + Vassals.GIFT + " emeralds for the lord who holds " + h.name() + " for you, from your pack and then the stores: "
                + "loyalty +" + Vassals.GIFT_LOYALTY + ". Loyal vassals send more rent and ride to your aid."
                : "Raise one of " + h.name() + "'s knights to hold it in your name. He keeps the garrison up from his own purse, rides to your "
                + "other places within " + Vassals.HELP_RANGE + " blocks when they're attacked, and sends you 30% to 80% of the rents by his "
                + "loyalty. Loyalty grows in peace and with gifts and falls when the place is sacked; at nothing he breaks faith.",
                () -> Net.sendAction(h.id(), Net.VASSAL, BlockPos.ZERO, 0)));
        if (vassal) row.add(button("Revoke", "Take " + h.name() + " back into your own hands. He returns to the ranks as a knight.",
                () -> Net.sendAction(h.id(), Net.REVOKE, BlockPos.ZERO, 0)));
        String seatDoes = "tribute, envoys' purses and anything paid to you while you're away go into its stores, and wages come out of them "
                + "first; rents still come to your purse at dawn. Your roads start from its gate, and the Realm map crowns it.";
        Button seat = button(h.seat() ? "Your seat" : "Make this my seat", (h.seat() ? h.name() + " is your seat: " : "Rule from " + h.name() + ": ")
                + seatDoes, () -> Net.sendAction(h.id(), Net.SET_SEAT, BlockPos.ZERO, 0));
        seat.active = !h.seat() && !vassal;
        row.add(seat);
        Button tourney = button("Hold a tournament", h.tourney().isEmpty()
                ? "Heralds, a purse and a feast for " + net.aiagent256.stormhold.holding.Tourney.COST + " emeralds. Three knights of the houses ride "
                + "in to fight you in the yard, one at a time, in iron, diamond and netherite. A man brought low yields, and so do you at two hearts. "
                + "Beat all three and " + h.name() + "'s renown grows, and the last swears to you and rides in your company in his own kit. "
                + "Be at the castle; leave it and you forfeit. Once every two days."
                : h.tourney(), () -> {
            Net.sendAction(h.id(), Net.TOURNEY, BlockPos.ZERO, 0);
            this.onClose();
        });
        tourney.active = h.tourney().isEmpty();
        row.add(tourney);
        if (!h.claim().isEmpty()) row.add(button("Buy out the claim", h.claim() + " " + h.claimPrice() + " emeralds from your pack and then "
                + "the stores; he gives it up and his house thinks a little better of you.", () -> Net.sendAction(h.id(), Net.BUY_CLAIM, BlockPos.ZERO, 0)));
        textBottom = layUp(row, mx, contentBottom() - BH, mw) - 4;
    }

    private void ordersWidgets(HoldingInfo h, int mx, int mw) {
        int y = contentTop();
        String[] modes = {"DEFEND", "PRODUCE", "ATTACK"};
        String[] labels = {"Defend", "Produce", "Attack"};
        int bw = (mw - 48) / 3;
        for (int i = 0; i < 3; i++) {
            final int act = i;
            Button b = Button.builder(Component.literal(labels[i]), x -> Net.sendAction(h.id(), act, BlockPos.ZERO, 0))
                    .tooltip(Tooltip.create(Component.literal(stanceText(modes[i])))).bounds(mx + 48 + i * bw, y, bw - 2, BH).build();
            b.active = !modes[i].equals(h.mode());
            this.addRenderableWidget(b);
        }
        stanceTextY = y + BH + 4;
        targetY = stanceTextY + this.font.split(Component.literal(stanceText(h.mode())), mw).size() * 9 + 6;
        BlockPos me = playerPos();
        ax = box(mx + 48, targetY, 46, typedOr(0, h.attack() != null ? h.attack().getX() : me.getX()));
        az = box(mx + 108, targetY, 46, typedOr(1, h.attack() != null ? h.attack().getZ() : me.getZ()));
        int rest = mw - 158, here = Math.min(40, rest / 3);
        this.addRenderableWidget(Button.builder(Component.literal("Here"), b -> {
            ax.setValue(String.valueOf(playerPos().getX()));
            az.setValue(String.valueOf(playerPos().getZ()));
        }).tooltip(Tooltip.create(Component.literal("Fill in where you're standing."))).bounds(mx + 158, targetY, here, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Set target"), b -> {
            BlockPos p = parse(ax, null, az);
            if (p != null) Net.sendAction(h.id(), Net.SET_ATTACK, p, 0);
        }).tooltip(Tooltip.create(Component.literal("Make this " + h.name() + "'s target. The odds against whatever stands there show below.")))
                .bounds(mx + 160 + here, targetY, rest - here - 2, 18).build());
        oddsY = targetY + 24;
        List<Button> row = new ArrayList<>();
        Button march = button("March now", "Send a war party from " + h.name() + " at the target now, without waiting for the Attack stance's next one.",
                () -> Net.sendAction(h.id(), Net.MARCH_NOW, BlockPos.ZERO, 0));
        march.active = h.attack() != null;
        row.add(march);
        Button scout = button("Scout there", "Two riders go to the x and z above (up to " + Scouts.MAX_RANGE + " blocks), look over "
                + Scouts.RADIUS + " blocks round it and come home to report every castle, town and city: who holds it, how many men, how they "
                + "stand with you. What they find goes on the Realm map. Hostile country and the far frontier cost riders.", () -> {
            BlockPos p = parse(ax, null, az);
            if (p != null) Net.sendAction(h.id(), Net.SCOUT, p, 0);
        });
        scout.active = !h.status().contains("Scouts out");
        row.add(scout);
        Button all = button("All castles march", "A war party from every castle you hold with men to spare, at this target. "
                + "They wait at the walls for each other (up to five minutes from the first) and go in together.",
                () -> Net.sendAction(h.id(), Net.MARCH_ALL, BlockPos.ZERO, 0));
        all.active = h.attack() != null && data.holdings().size() > 1;
        row.add(all);
        textBottom = layUp(row, mx, contentBottom() - BH, mw) - 4;
    }

    private void companyWidgets(HoldingInfo h, int mx, int mw) {
        int y = contentTop();
        int[] c = h.counts();
        int[] max = {Math.max(0, c[1] - 1), Math.max(0, c[2] - 1), Math.max(0, c[3] - 1)};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            want[k] = Math.min(want[k], max[k]);
            int ry = y + i * 18;
            Button minus = Button.builder(Component.literal("-"), b -> {
                want[k] = Math.max(0, want[k] - (hasShiftDown() ? 5 : 1));
                rebuildWidgets();
            }).tooltip(Tooltip.create(Component.literal("One fewer (shift: five)."))).bounds(mx + 66, ry, 16, 16).build();
            minus.active = want[k] > 0;
            this.addRenderableWidget(minus);
            Button plus = Button.builder(Component.literal("+"), b -> {
                want[k] = Math.min(max[k], want[k] + (hasShiftDown() ? 5 : 1));
                rebuildWidgets();
            }).tooltip(Tooltip.create(Component.literal("One more (shift: five). One of each always stays home."))).bounds(mx + 108, ry, 16, 16).build();
            plus.active = want[k] < max[k];
            this.addRenderableWidget(plus);
        }
        int n = want[0] + want[1] + want[2];
        boolean near = inCallRange(h);
        List<Button> row = new ArrayList<>();
        int[] acts = {Net.MUSTER_KNIGHTS, Net.MUSTER_ARCHERS, Net.MUSTER_CROSSBOWMEN};
        Button muster = button("Call here", "The chosen men step out of " + h.name() + "'s garrison and join you where you stand. You must be "
                + "within " + Company.CALL_RANGE + " blocks of it.", () -> {
            for (int i = 0; i < 3; i++) if (want[i] > 0) Net.sendAction(h.id(), acts[i], BlockPos.ZERO, want[i]);
        });
        muster.active = near && n > 0;
        row.add(muster);
        Button send = button("Send to me", "The chosen men ride out as a column, " + (int) Columns.SPEED + " blocks a second (about "
                + etaFrom(h) + " from here), and join you when they arrive. A castle that hates you near the road will take a bite out of them.",
                () -> Net.sendAction(h.id(), Net.SEND_COLUMN, new BlockPos(want[0], want[1], want[2]), 0));
        send.active = n > 0;
        row.add(send);
        boolean supply = h.status().contains("Supply line");
        row.add(button(supply ? "Stop resupply" : "Keep me supplied", supply ? "Cancel the standing order. A column it has on the road turns back."
                : "Standing order: keep " + Math.max(1, n) + " men (the counts above) with you. Whenever you run short, " + h.name()
                + " sends a column, at most one every five minutes; if you're short now, the first rides at once.",
                () -> Net.sendAction(h.id(), Net.KEEP_WITH_ME, BlockPos.ZERO, supply ? 0 : Math.max(1, n))));
        Button dismiss = button("Dismiss here", "Every man of yours within 24 blocks joins " + h.name() + "'s garrison. You must be within "
                + Company.CALL_RANGE + " blocks of it.", () -> Net.sendAction(h.id(), Net.DISMISS, BlockPos.ZERO, 0));
        dismiss.active = near;
        row.add(dismiss);
        row.add(button("Send home", "From anywhere: every man of yours within " + Company.HOME_RANGE + " blocks rides back to the castle he came "
                + "from (" + (int) Columns.SPEED + " blocks a second) and joins its garrison. Men with no home go to your seat. Their names and "
                + "kit stay behind: the castle arms them as it arms the rest.", () -> Net.sendAction(h.id(), Net.SEND_HOME, BlockPos.ZERO, 0)));
        row.add(button("Call the banners", "Every castle you hold, your vassals' too, sends its war party to you as a column ("
                + (int) Columns.SPEED + " blocks a second), keeping 3 knights, an archer and a crossbowman at home. They fall in as your "
                + "company when they reach you: lead them with the baton, and Send home when it's done. A castle under attack keeps its men, "
                + "and so does a vassal whose loyalty is under 25.", () -> Net.sendAction(h.id(), Net.BANNERS, BlockPos.ZERO, 0)));
        noteY = layDown(row, mx, y + 58, mw) + 3;
        // the armoury, along the bottom
        String[] kits = {"Iron kit", "Diamond kit", "Netherite kit"};
        List<Button> arm = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int tier = i + 1;
            arm.add(button(kits[i], "Full " + kits[i].toLowerCase().replace(" kit", "") + " armour and a weapon for every man of yours within "
                    + "24 blocks who wears worse. Paid from your pack, then the stores and " + h.name() + "'s chests when you're within " + Company.CALL_RANGE
                    + " blocks. Old kit comes back to you.", () -> Net.sendAction(h.id(), Net.ARM, new BlockPos(enchant ? 1 : 0, 0, 0), tier)));
        }
        arm.add(button(enchant ? "Enchant: on" : "Enchant: off", "Protection and Sharpness or Power II to IV by metal, Unbreaking III; fire "
                + "and piercing on netherite. " + net.aiagent256.stormhold.holding.Armoury.ENCHANT_LEVELS + " experience levels a man.", () -> {
            enchant = !enchant;
            rebuildWidgets();
        }));
        armouryY = layUp(arm, mx, contentBottom() - BH - 10, mw) - 11;
    }

    private void worksWidgets(HoldingInfo h, int mx, int mw) {
        boolean busy = h.status().contains("Builder ");
        int stage = Math.min(3, fortifyStage(h) + 1);
        int[] cost = Builder.fortifyCost(stage);
        List<Button> row = new ArrayList<>();
        Button repair = button("Repair", "Put back every block of the castle's plans that's missing: walls, gate, portcullis, roofs. A stone "
                + "from the stores (or any chest in the castle) per block keeps the pace; without stone it's slow. Your own additions are left alone.",
                () -> Net.sendAction(h.id(), Net.REPAIR, BlockPos.ZERO, 0));
        repair.active = !busy;
        row.add(repair);
        Button fort = button(fortifyStage(h) >= 3 ? "Fortified" : "Fortify " + "I".repeat(stage), "Stage " + stage + ": " + Builder.stageName(stage)
                + ". " + cost[0] + " stone and " + cost[1] + " iron from the stores or the castle's chests. Each stage lets the castle hold 15% more men.",
                () -> Net.sendAction(h.id(), Net.FORTIFY, BlockPos.ZERO, 0));
        fort.active = !busy && fortifyStage(h) < 3;
        row.add(fort);
        String[] names = {"Light it", "Clear trees", "Lay roads"};
        String[] tips = {
                "The builder walks every floor inside the walls and sets a torch wherever a monster could spawn, on the walls where he can. "
                        + "A coal or charcoal from the stores or the castle's chests makes " + Builder.TORCHES_PER_COAL + ". Crypts and cellars stay dark.",
                "Woodcutters fell every wild tree within " + Builder.CLEAR_REACH + " blocks of the walls, so nothing comes at the gate unseen. "
                        + "The logs go to the stores. Orchards, fief buildings and anything inside a town are left alone.",
                "Roads to join every place you hold, shortest first and from your seat out (none longer than " + Builder.ROAD_MAX + " blocks), "
                        + "with lamps along them and a signpost at each end naming the place at the other. A stone from the stores or the castle's "
                        + "chests per " + Builder.ROAD_PER_STONE + " blocks; as many as the stone pays for go down now, and at dawn the builders "
                        + "lay the next one themselves when there's stone. They go down as the land they cross loads."};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            Button b = button(names[i], tips[i], () -> Net.sendAction(h.id(), Net.PROJECT, BlockPos.ZERO, p));
            b.active = i == 2 || !busy;
            row.add(b);
        }
        row.add(button(h.tidy() ? "Chests: in order" : "Chests: as they are", h.tidy()
                ? "The steward keeps this castle's chests in order every half minute while you're near: the spoils chest emptied into them, "
                + "each kind of thing gathered into the chest with the most of it, every chest stacked and sorted. Click to stop him."
                : "Have the steward keep this castle's chests in order: the spoils chest emptied into your chests, each kind of thing gathered "
                + "into the chest that holds the most of it, every chest stacked and sorted. Put a sign on any chest to keep him out of it.",
                () -> Net.sendAction(h.id(), Net.TIDY, BlockPos.ZERO, 0)));
        row.add(button(data.sellSurplus() ? "Market: selling" : "Market: keeping", data.sellSurplus()
                ? "At dawn every castle's stores sell what nothing in the realm uses (crops, wool, hides, meat, fish, paper, copper) past the "
                + "last " + net.aiagent256.stormhold.holding.Surplus.KEEP + " of each, at the shops' price, into the stores. Bread, iron, gold, "
                + "diamonds, coal, stone, arrows and logs are never sold. Click to keep everything."
                : "Your stores keep everything. Click to sell the surplus at dawn: crops, wool, hides, meat, fish, paper and copper past the last "
                + net.aiagent256.stormhold.holding.Surplus.KEEP + " of each, at the shops' price.",
                () -> Net.sendAction(h.id(), Net.SURPLUS, BlockPos.ZERO, 0)));
        peopleY = layDown(row, mx, contentTop() + 12, mw) + 6;
        this.addRenderableWidget(Button.builder(Component.literal("Expand fief"), b -> Net.sendAction(h.id(), Net.EXPAND_FIEF, BlockPos.ZERO, 0))
                .tooltip(Tooltip.create(Component.literal("The people raise one more building (a pasture, a mine, a mill, then more cottages) for "
                        + "32 bread and 8 iron from the stores or the castle's chests. More folk, more produce."))).bounds(mx + mw - 70, peopleY - 4, 70, 16).build());
        stepY = peopleY + 16;
        stepper(mx + 34, stepY, h.miners(), Labour.MAX, v -> Net.sendAction(h.id(), Net.SET_MINERS, BlockPos.ZERO, v),
                "person at the mine. Each brings up a day's ore, smelted, into the stores: coal, iron, copper, gold, now and then redstone, "
                        + "lapis, emeralds and diamonds; more diamonds on the frontier, ancient debris from zone 7.");
        int next = Math.min(net.aiagent256.stormhold.holding.Frontier.MAX, kitLevel(h) + 1);
        int[] metal = Labour.cost(next);
        stepper(mx + mw / 2 + 36, stepY, h.smiths(), Labour.MAX, v -> Net.sendAction(h.id(), Net.SET_SMITHS, BlockPos.ZERO, v),
                "smith at the forge. Smiths forge the garrison's kit a level at a time: the next, level " + next + " (" + Labour.kitName(next)
                        + "), takes " + (metal[0] > 0 ? metal[0] + " iron " : "") + (metal[1] > 0 ? metal[1] + " diamonds " : "")
                        + (metal[2] > 0 ? metal[2] + " netherite " : "") + "and " + Labour.SMITH_DAYS + " smith-days; then every man is re-armed.");
        labourY = stepY + 20;
        int rush = h.rush();
        Button buy = Button.builder(Component.literal("Buy now"), b -> Net.sendAction(h.id(), Net.RUSH, BlockPos.ZERO, 0))
                .tooltip(Tooltip.create(Component.literal(rush < 0 ? "The garrison's kit is the best there is."
                        : "Hired hands finish level " + next + " (" + Labour.kitName(next) + ") tonight instead of waiting on the smiths: "
                        + rush + " emeralds (level " + next + " x smith-days left x " + Labour.RUSH_PER_LEVEL_DAY + "), from your pack and then the "
                        + "stores, plus the level's metal if the forge hasn't got it yet. Every man on the walls is re-armed at once.")))
                .bounds(mx + mw - 60, labourY - 2, 60, 16).build();
        buy.active = rush >= 0;
        this.addRenderableWidget(buy);
        // couriers, along the bottom
        courierY = contentBottom() - 18;
        BlockPos depot = markedDepot();
        BlockPos sup = h.supply() != null ? h.supply() : depot != null ? depot : playerPos().below();
        sx = box(mx + 50, courierY, 40, typedOr(2, sup.getX()));
        sy = box(mx + 94, courierY, 30, typedOr(3, sup.getY()));
        sz = box(mx + 128, courierY, 40, typedOr(4, sup.getZ()));
        int rest = mw - 172;
        this.addRenderableWidget(Button.builder(Component.literal("Set"), b -> {
            BlockPos p = parse(sx, sy, sz);
            if (p != null) Net.sendAction(h.id(), Net.SET_SUPPLY, p, 0);
        }).tooltip(Tooltip.create(Component.literal("Each day a courier carries " + h.name() + "'s surplus to a chest at this x, y, z. "
                + "A War Banner in your pack fills in its depot."))).bounds(mx + 172, courierY, rest / 2 - 1, 18).build());
        Button stop = Button.builder(Component.literal("Stop"), b -> Net.sendAction(h.id(), Net.CLEAR_SUPPLY, BlockPos.ZERO, 0))
                .tooltip(Tooltip.create(Component.literal("No more couriers from " + h.name() + ".")))
                .bounds(mx + 172 + rest / 2 + 1, courierY, rest - rest / 2 - 1, 18).build();
        stop.active = h.supply() != null;
        this.addRenderableWidget(stop);
        textBottom = courierY - 4;
    }

    // ------------------------------------------------------------------ the Attack page

    private int targetSel, targetScroll, tListY, tRows, tDetailY;
    private static final int ROW = 11, MOST_PARTS = 5;

    private void targetsWidgets(int mx, int mw) {
        List<net.aiagent256.stormhold.holding.WarPlan.Target> ts = data.targets();
        if (ts.isEmpty()) return;
        targetSel = Math.max(0, Math.min(targetSel, ts.size() - 1));
        var t = ts.get(targetSel);
        int detail = 2 + Math.min(MOST_PARTS + 1, Math.max(1, t.parts().size())) + (t.note().isEmpty() ? 0 : 1);
        tDetailY = contentBottom() - BH - 4 - detail * 10;
        tListY = contentTop() + 11;
        tRows = Math.max(2, (tDetailY - 6 - tListY) / ROW);
        targetScroll = Math.max(0, Math.min(targetScroll, Math.max(0, ts.size() - tRows)));
        if (targetSel < targetScroll) targetScroll = targetSel;
        if (targetSel >= targetScroll + tRows) targetScroll = targetSel - tRows + 1;
        int[] all = new int[4];
        for (var p : t.parts()) for (int i = 0; i < 4; i++) all[i] += p.men()[i];
        int half = (mw - 4) / 2;
        Button go = Button.builder(Component.literal(fit("Attack " + t.name() + " with everything", half - 8)), b ->
                        Net.sendAction(new UUID(0, 0), Net.ATTACK_ALL, new BlockPos(t.x(), 64, t.z()), 0))
                .tooltip(Tooltip.create(Component.literal(t.parts().isEmpty() ? "None of your castles has three men to spare, or each has a war party out already."
                        : "Every castle listed above sends its war party at " + t.name() + " (" + net.aiagent256.stormhold.holding.WarPlan.men(all)
                        + "). They wait at the walls for each other, up to five minutes from the first, and go in together. Fought out of "
                        + "sight it's " + t.pct() + "%; be there when they arrive and it's fought in front of you instead."
                        + (t.note().isEmpty() ? "" : " " + t.note()))))
                .bounds(mx, contentBottom() - BH, half, BH).build();
        go.active = !t.parts().isEmpty();
        this.addRenderableWidget(go);
        Button lead = Button.builder(Component.literal(fit("Lead it yourself", half - 8)), b ->
                        Net.sendAction(new UUID(0, 0), Net.BANNERS, BlockPos.ZERO, 0))
                .tooltip(Tooltip.create(Component.literal("Call the banners: the same " + t.men() + " men ride to you instead, as columns ("
                        + (int) Columns.SPEED + " blocks a second), and fall in as your company. Then you take them to " + t.name()
                        + " and lead them in with the baton: the gate, the walls and the waves of defenders (about " + t.defenders()
                        + " in all) are fought in front of you, not by the odds above. Send home (Company tab) when it's done.")))
                .bounds(mx + mw - half, contentBottom() - BH, half, BH).build();
        lead.active = !t.parts().isEmpty();
        this.addRenderableWidget(lead);
    }

    private void drawTargets(GuiGraphics g, int mx, int mw, int mouseX, int mouseY) {
        List<net.aiagent256.stormhold.holding.WarPlan.Target> ts = data.targets();
        if (ts.isEmpty()) {
            lines(g, "You know no castle within " + net.aiagent256.stormhold.holding.WarPlan.REACH + " blocks of your own yet. Ride out, or send "
                    + "scouts from the Orders tab: every castle they find shows here.", mx, contentTop(), mw, contentBottom(), TEXT);
            return;
        }
        int cName = mx, cOwner = mx + mw * 36 / 100, cAway = mx + mw * 62 / 100, cDef = mx + mw * 75 / 100, cPct = mx + mw * 88 / 100;
        g.drawString(this.font, "Castle", cName, contentTop(), DIM, false);
        g.drawString(this.font, "Held by", cOwner, contentTop(), DIM, false);
        g.drawString(this.font, "Away", cAway, contentTop(), DIM, false);
        g.drawString(this.font, "Men", cDef, contentTop(), DIM, false);
        g.drawString(this.font, "Odds", cPct, contentTop(), DIM, false);
        for (int r = 0; r < tRows && targetScroll + r < ts.size(); r++) {
            int i = targetScroll + r;
            var t = ts.get(i);
            int y = tListY + r * ROW;
            boolean sel = i == targetSel, over = mouseX >= mx && mouseX < mx + mw && mouseY >= y - 1 && mouseY < y + ROW - 1;
            if (sel) g.fill(mx - 2, y - 1, mx + mw, y + ROW - 1, CARD);
            else if (over) g.fill(mx - 2, y - 1, mx + mw, y + ROW - 1, 0x40FFFFFF);
            int nameC = t.attitude() == 1 ? 0x80E080 : t.attitude() == 3 ? 0xFF8080 : t.attitude() == 4 ? 0xC0A0A0 : NAME;
            g.drawString(this.font, fit(t.name(), cOwner - cName - 4), cName, y, nameC, false);
            g.drawString(this.font, fit(t.owner().replace("House ", ""), cAway - cOwner - 4), cOwner, y, TEXT, false);
            g.drawString(this.font, String.valueOf(t.distance()), cAway, y, DIM, false);
            g.drawString(this.font, String.valueOf(t.defenders()), cDef, y, TEXT, false);
            g.drawString(this.font, t.parts().isEmpty() ? "-" : t.pct() + "%", cPct, y, pctColor(t.pct(), t.parts().isEmpty()), false);
        }
        if (ts.size() > tRows) {
            int track = tRows * ROW, bar = Math.max(8, track * tRows / ts.size());
            int by = tListY + (track - bar) * targetScroll / Math.max(1, ts.size() - tRows);
            g.fill(mx + mw - 2, tListY, mx + mw, tListY + track, 0x40FFFFFF);
            g.fill(mx + mw - 2, by, mx + mw, by + bar, 0xC0F0D8A0);
        }
        // the chosen castle: who defends it, who'd go, the chance
        var t = ts.get(Math.min(targetSel, ts.size() - 1));
        int y = tDetailY;
        g.fill(mx - 2, y - 4, mx + mw, y - 3, LINE);
        g.drawString(this.font, fit(t.name() + ", " + t.owner() + ": about " + t.defenders() + " defenders in "
                + net.aiagent256.stormhold.holding.WarPlan.kit(t.theirKit()) + ".", mw), mx, y, NAME, false);
        y += 10;
        if (t.parts().isEmpty()) {
            g.drawString(this.font, fit("None of your castles has three men to spare, or each has a war party out already.", mw), mx, y, WARN, false);
            y += 10;
        } else {
            int shown = 0;
            for (var p : t.parts()) {
                if (shown == MOST_PARTS && t.parts().size() > MOST_PARTS + 1) {
                    g.drawString(this.font, "  and " + (t.parts().size() - shown) + " more castles", mx, y, DIM, false);
                    y += 10;
                    break;
                }
                g.drawString(this.font, fit(p.castle() + ": " + net.aiagent256.stormhold.holding.WarPlan.men(p.men()) + ", "
                        + net.aiagent256.stormhold.holding.WarPlan.kit(p.kit()) + ", " + net.aiagent256.stormhold.holding.WarPlan.time(p.seconds()), mw), mx, y, TEXT, false);
                y += 10;
                shown++;
            }
        }
        int[] all = new int[4];
        for (var p : t.parts()) for (int i = 0; i < 4; i++) all[i] += p.men()[i];
        if (!t.parts().isEmpty()) {
            g.drawString(this.font, fit("In all " + t.men() + " men (" + net.aiagent256.stormhold.holding.WarPlan.men(all) + "): " + t.pct() + "%, "
                    + net.aiagent256.stormhold.holding.WarPlan.verdict(t.pct()) + ".", mw), mx, y, pctColor(t.pct(), false), false);
            y += 10;
        }
        if (!t.note().isEmpty()) g.drawString(this.font, fit(t.note(), mw), mx, y, WARN, false);
    }

    private static int pctColor(int pct, boolean none) {
        return none ? DIM : pct >= 60 ? 0x80E080 : pct >= 30 ? 0xE8D080 : WARN;
    }

    private int decisionY;

    /** The matter waiting on your word: its two answers as buttons under the story, and Later. */
    private void decisionWidgets(int mx, int mw) {
        Net.Decision d = data.decision();
        decisionY = contentTop() + this.font.split(Component.literal(d.text()), mw).size() * 10 + 10;
        int y = decisionY;
        for (int i = 1; i <= 2; i++) {
            final int which = i;
            this.addRenderableWidget(Button.builder(Component.literal(i == 1 ? d.first() : d.second()),
                    b -> Net.sendAction(d.id(), Net.MATTER_CHOOSE, BlockPos.ZERO, which)).bounds(mx, y, mw, BH).build());
            y += STEP;
        }
        this.addRenderableWidget(Button.builder(Component.literal("Later"), b -> {
            tab = OVERVIEW;
            rebuildWidgets();
        }).tooltip(Tooltip.create(Component.literal("Leave it for now. Unanswered for two days, it goes the second way.")))
                .bounds(mx + mw - 70, y + 6, 70, 16).build());
    }

    private void councilWidgets(int mx, int mw) {
        List<Net.Seat> seats = data.council();
        int rh = seatHeight();
        for (int i = 0; i < seats.size(); i++) {
            Net.Seat s = seats.get(i);
            int ry = contentTop() + i * rh;
            boolean held = !s.holder().isEmpty();
            Button b = Button.builder(Component.literal(held ? "Dismiss" : "Appoint"),
                    x -> Net.sendAction(new UUID(0, 0), held ? Net.COUNCIL_DISMISS : Net.COUNCIL_APPOINT, BlockPos.ZERO, s.ordinal()))
                    .tooltip(Tooltip.create(Component.literal(s.title() + ": " + s.does() + ".\n\n" + (held ? s.holder() + " gives up the seat and the wage stops."
                            : "Name someone to the seat: " + s.wage() + " emeralds a day from the realm's stores, paid each dawn."))))
                    .bounds(mx + mw - 56, ry + 1, 56, 16).build();
            b.active = held || !data.holdings().isEmpty();
            this.addRenderableWidget(b);
        }
    }

    private int seatHeight() {
        return Math.max(30, (contentBottom() - 12 - contentTop()) / Math.max(1, data.council().size()));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g);
        int x0 = left(), y0 = top();
        g.fill(x0, y0, x0 + W, y0 + H, BG);
        g.fill(x0 + 2, y0 + 2, x0 + SIDE, y0 + H - 2, PANEL);
        g.fill(x0 + SIDE, y0 + 4, x0 + SIDE + 1, y0 + H - 4, LINE);
        g.drawString(this.font, "War Table", x0 + 8, y0 + 7, GOLD, false);
        HoldingInfo h = current();
        int mx = mainX(), mw = mainW();
        if (tab == DECIDE && data.decision() != null) {
            g.drawString(this.font, "Your word is needed", mx, y0 + 7, NAME, false);
            g.drawString(this.font, fit("Choose one. Unanswered for two days, it goes the second way.", mw), mx, y0 + 18, DIM, false);
        } else if (tab == TARGETS) {
            g.drawString(this.font, "Attack a castle", mx, y0 + 7, NAME, false);
            g.drawString(this.font, fit("Nearest first. Odds: every castle of yours marching together, fought out of sight.", mw), mx, y0 + 18, DIM, false);
        } else if (tab == COUNCIL) {
            g.drawString(this.font, "Your council", mx, y0 + 7, NAME, false);
            g.drawString(this.font, fit("Four officers who serve every place you hold. Hover a seat's button for the details.", mw), mx, y0 + 18, DIM, false);
        } else if (h != null) {
            String where = net.aiagent256.stormhold.holding.Holding.tierName(h.tier()) + ", " + h.pos().getX() + " " + h.pos().getZ();
            g.drawString(this.font, fit(h.name(), mw - this.font.width(where) - 8), mx, y0 + 7, NAME, false);
            g.drawString(this.font, where, mx + mw - this.font.width(where), y0 + 7, DIM, false);
            String alert = headline(h);
            g.drawString(this.font, fit(alert, mw), mx, y0 + 18, alert.equals("All quiet.") ? DIM : WARN, false);
        }
        g.fill(mx - 2, y0 + 50, mx + mw, y0 + 51, LINE);
        if (tab == DECIDE && data.decision() != null) g.drawWordWrap(this.font, Component.literal(data.decision().text()), mx, contentTop(), mw, TEXT);
        else if (tab == TARGETS) drawTargets(g, mx, mw, mouseX, mouseY);
        else if (tab == COUNCIL) drawCouncil(g, mx, mw);
        else if (h == null) {
            g.drawWordWrap(this.font, Component.literal("You hold no castles yet. Kill a castle's lord, raise a War Banner on its banner stand and "
                    + "hold it until the garrison yields. Your council can sit once you hold one."), mx, contentTop(), mw, TEXT);
        } else {
            switch (tab) {
                case ORDERS -> drawOrders(g, h, mx, mw);
                case COMPANY -> drawCompany(g, h, mx, mw);
                case WORKS -> drawWorks(g, h, mx, mw);
                default -> drawOverview(g, h, mx, mw);
            }
        }
        // along the bottom: the last word from the server while the table's open, else the build
        boolean fresh = System.currentTimeMillis() - noticeAt < 20000 && !notice.getString().isEmpty();
        List<FormattedCharSequence> nl = this.font.split(fresh ? notice : Component.literal("build " + net.aiagent256.stormhold.Stormhold.buildId()), mw);
        if (!nl.isEmpty()) g.drawString(this.font, nl.get(0), mx, y0 + H - 13, fresh ? 0xF0E0A0 : 0x6C5C3C, false);
        super.render(g, mouseX, mouseY, partial);
    }

    private void drawOverview(GuiGraphics g, HoldingInfo h, int mx, int mw) {
        int top = contentTop(), bottom = textBottom;
        int[] c = h.counts(), cap = h.capacity();
        String[][] sections = {
                {"Garrison", "Knights " + c[1] + " of " + cap[1] + ", archers " + c[2] + " of " + cap[2] + ", crossbowmen " + c[3] + " of " + cap[3]
                        + ", captains " + c[0] + "."},
                {"What's happening", h.status().isEmpty() ? "All quiet." : h.status().replace(". ", ".\n")},
                {"Stores", h.stock()},
                {"The people", h.fief()},
                {"The realm", h.realm()}};
        g.enableScissor(mx - 2, top, mx + mw, bottom);
        int y = top - scroll;
        for (String[] s : sections) {
            g.drawString(this.font, s[0], mx, y, GOLD, false);
            y += 10;
            boolean news = s[0].equals("What's happening") && !h.status().isEmpty();
            for (String para : s[1].split("\n")) {
                for (FormattedCharSequence line : this.font.split(Component.literal(para.trim()), mw - 8)) {
                    g.drawString(this.font, line, mx + 4, y, news ? WARN : TEXT, false);
                    y += 9;
                }
            }
            y += 4;
        }
        g.disableScissor();
        overviewHeight = y + scroll - top;
        int track = bottom - top;
        if (overviewHeight > track) {
            int bar = Math.max(10, track * track / overviewHeight);
            int by = top + (track - bar) * scroll / Math.max(1, overviewHeight - track);
            g.fill(mx + mw - 2, top, mx + mw, bottom, 0x40FFFFFF);
            g.fill(mx + mw - 2, by, mx + mw, by + bar, 0xC0F0D8A0);
        }
    }

    private void drawOrders(GuiGraphics g, HoldingInfo h, int mx, int mw) {
        g.drawString(this.font, "Stance", mx, contentTop() + 6, GOLD, false);
        lines(g, stanceText(h.mode()), mx, stanceTextY, mw, targetY - 4, TEXT);
        g.drawString(this.font, "Target", mx, targetY + 5, GOLD, false);
        g.drawString(this.font, "x", mx + 40, targetY + 5, DIM, false);
        g.drawString(this.font, "z", mx + 100, targetY + 5, DIM, false);
        String odds = h.attack() == null ? "No target. Type an x and z (or stand there and press Here), then Set target." : h.odds();
        lines(g, odds, mx, oddsY, mw, textBottom, h.attack() == null ? DIM : BLUE);
    }

    private void drawCompany(GuiGraphics g, HoldingInfo h, int mx, int mw) {
        int y = contentTop();
        String[] roles = {"Knights", "Archers", "Crossbows"};
        int[] c = h.counts();
        int[] have = {c[1], c[2], c[3]};
        for (int i = 0; i < 3; i++) {
            int ry = y + i * 18;
            g.drawString(this.font, roles[i], mx, ry + 4, TEXT, false);
            g.drawCenteredString(this.font, String.valueOf(want[i]), mx + 95, ry + 4, 0xFFFFFF);
            g.drawString(this.font, "of " + have[i] + " at home", mx + 132, ry + 4, DIM, false);
        }
        boolean near = inCallRange(h);
        g.drawString(this.font, fit(near ? "You're close enough to call them here." + (data.recruits() ? " (Recruits)" : "")
                : "Too far to call them: come within " + Company.CALL_RANGE + " blocks, or send for them.", mw), mx, noteY, near ? DIM : WARN, false);
        g.drawString(this.font, "Armoury", mx, armouryY, GOLD, false);
        g.drawString(this.font, fit("a man: 26 iron | 26 diamond | 26 diamond + 5 netherite", mw - 50), mx + 50, armouryY, DIM, false);
        var p = Minecraft.getInstance().player;
        int iron = p != null ? p.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) : 0;
        int dia = p != null ? p.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) : 0;
        int neth = p != null ? p.getInventory().countItem(net.minecraft.world.item.Items.NETHERITE_INGOT) : 0;
        g.drawString(this.font, fit("In your pack: " + iron + " iron, " + dia + " diamond, " + neth + " netherite, "
                + (p != null ? p.experienceLevel : 0) + " levels", mw), mx, contentBottom() - 8, DIM, false);
    }

    private void drawWorks(GuiGraphics g, HoldingInfo h, int mx, int mw) {
        String builder = "Idle. Pick a job below.";
        for (String s : h.status().split("\\. ")) if (s.startsWith("Builder ") || s.startsWith("Fortified")) builder = s.endsWith(".") ? s : s + ".";
        g.drawString(this.font, "Builder", mx, contentTop(), GOLD, false);
        g.drawString(this.font, fit(builder, mw - 48), mx + 48, contentTop(), TEXT, false);
        g.drawString(this.font, "The people", mx, peopleY, GOLD, false);
        g.drawString(this.font, fit(h.fief(), mw - 140), mx + 62, peopleY, DIM, false);
        g.drawString(this.font, "Mine", mx, stepY + 4, TEXT, false);
        g.drawString(this.font, "Forge", mx + mw / 2, stepY + 4, TEXT, false);
        lines(g, h.labour(), mx, labourY, mw - 64, textBottom, DIM);
        g.drawString(this.font, "Couriers", mx, courierY + 5, GOLD, false);
    }

    private void drawCouncil(GuiGraphics g, int mx, int mw) {
        List<Net.Seat> seats = data.council();
        int rh = seatHeight();
        int wages = 0;
        for (int i = 0; i < seats.size(); i++) {
            Net.Seat s = seats.get(i);
            int ry = contentTop() + i * rh;
            boolean held = !s.holder().isEmpty();
            if (held) wages += s.wage();
            g.fill(mx - 2, ry - 1, mx + mw, ry + rh - 3, CARD);
            g.fill(mx - 2, ry - 1, mx, ry + rh - 3, held ? 0xFFA8D080 : 0xFF6b5a40);
            String head = s.title() + ": ";
            g.drawString(this.font, head, mx + 3, ry + 5, GOLD, false);
            String who = held ? s.holder() : "seat empty (" + s.wage() + " a day)";
            g.drawString(this.font, fit(who, mw - 64 - this.font.width(head)), mx + 3 + this.font.width(head), ry + 5, held ? 0xFFFFFF : DIM, false);
            lines(g, s.brief() + (held ? " (" + s.wage() + " emeralds a day)" : ""), mx + 3, ry + 18, mw - 6, ry + rh - 4, TEXT);
        }
        g.drawString(this.font, fit("Wages " + wages + " a day, paid at dawn from the stores (" + data.treasury() + " emeralds there). "
                + "Unpaid officers leave.", mw), mx, contentBottom() - 8, wages > data.treasury() ? WARN : DIM, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (tab == TARGETS && button == 0 && !data.targets().isEmpty()) {
            int mx = mainX(), mw = mainW();
            if (mouseX >= mx && mouseX < mx + mw && mouseY >= tListY - 1 && mouseY < tListY + tRows * ROW - 1) {
                int i = targetScroll + (int) ((mouseY - tListY + 1) / ROW);
                if (i >= 0 && i < data.targets().size()) {
                    targetSel = i;
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (tab == TARGETS && data.targets().size() > tRows) {
            targetScroll = (int) Math.max(0, Math.min(data.targets().size() - tRows, targetScroll - Math.signum(delta)));
            return true;
        }
        if (tab == OVERVIEW && overviewHeight > 0) {
            int view = textBottom - contentTop();
            scroll = (int) Math.max(0, Math.min(Math.max(0, overviewHeight - view), scroll - delta * 18));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ------------------------------------------------------------------ helpers

    /** Wrapped text from y down, stopping before `bottom`. */
    private void lines(GuiGraphics g, String text, int x, int y, int w, int bottom, int color) {
        for (FormattedCharSequence line : this.font.split(Component.literal(text), w)) {
            if (y + 9 > bottom) break;
            g.drawString(this.font, line, x, y, color, false);
            y += 9;
        }
    }

    /** The text cut to the width, with a dot where it was cut. */
    private String fit(String s, int w) {
        if (this.font.width(s) <= w) return s;
        return this.font.plainSubstrByWidth(s, Math.max(0, w - this.font.width("."))) + ".";
    }

    private Button button(String label, String tip, Runnable press) {
        return Button.builder(Component.literal(label), b -> press.run()).tooltip(Tooltip.create(Component.literal(tip))).size(MIN_BUTTON, BH).build();
    }

    /** Buttons in rows across the width, as many to a row as fit at a readable width, from y down. Returns the y below them. */
    private int layDown(List<Button> row, int x, int y, int w) {
        int perRow = perRow(row.size(), w);
        int bw = (w - 2 * (perRow - 1)) / perRow;
        for (int i = 0; i < row.size(); i++) {
            Button b = row.get(i);
            b.setX(x + (i % perRow) * (bw + 2));
            b.setY(y + (i / perRow) * STEP);
            b.setWidth(bw);
            this.addRenderableWidget(b);
        }
        return y + ((row.size() + perRow - 1) / perRow) * STEP;
    }

    /** The same, with the last row at `bottom` and any others above it. Returns the top of the first row. */
    private int layUp(List<Button> row, int x, int bottom, int w) {
        int rows = (row.size() + perRow(row.size(), w) - 1) / perRow(row.size(), w);
        int y = bottom - (rows - 1) * STEP;
        layDown(row, x, y, w);
        return y;
    }

    private int perRow(int n, int w) {
        int perRow = Math.max(1, n);
        while (perRow > 1 && (w - 2 * (perRow - 1)) / perRow < 76) perRow--;
        return perRow;
    }

    /** A number between a minus and a plus; each sends the new value. */
    private void stepper(int x, int y, int value, int max, java.util.function.IntConsumer send, String what) {
        Button minus = Button.builder(Component.literal("-"), b -> send.accept(Math.max(0, value - 1)))
                .tooltip(Tooltip.create(Component.literal("One fewer " + what))).bounds(x, y, 16, 16).build();
        minus.active = value > 0;
        this.addRenderableWidget(minus);
        Button plus = Button.builder(Component.literal("+"), b -> send.accept(Math.min(max, value + 1)))
                .tooltip(Tooltip.create(Component.literal("One more " + what + " Up to " + max + "."))).bounds(x + 40, y, 16, 16).build();
        plus.active = value < max;
        this.addRenderableWidget(plus);
        this.addRenderableOnly((g, mX, mY, pt) -> g.drawCenteredString(this.font, String.valueOf(value), x + 28, y + 4, 0xFFFFFF));
    }

    private void keepTyped() {
        HoldingInfo h = current();
        UUID id = h != null ? h.id() : null;
        if (id == null || !id.equals(typedFor)) {
            typed = null;
            typedFor = id;
            ax = az = sx = sy = sz = null;
            return;
        }
        String[] t = typed != null ? typed : new String[5];
        EditBox[] boxes = {ax, az, sx, sy, sz};
        for (int i = 0; i < 5; i++) if (boxes[i] != null) t[i] = boxes[i].getValue();
        typed = t;
        ax = az = sx = sy = sz = null;
    }

    private int typedOr(int i, int fallback) {
        if (typed != null && typed[i] != null) {
            try {
                return Integer.parseInt(typed[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    /** The most pressing line of a place's status, for the header. */
    private static String headline(HoldingInfo h) {
        String s = h.status();
        if (s.isEmpty()) return "All quiet.";
        for (String key : new String[]{"Under attack", "Illager warband", "Raid expected", "War party out", "Column", "Scouts out"}) {
            int i = s.indexOf(key);
            if (i >= 0) {
                int j = s.indexOf(". ", i);
                return s.substring(i, j < 0 ? s.length() : j + 1).trim();
            }
        }
        int j = s.indexOf(". ");
        return j < 0 ? s : s.substring(0, j + 1);
    }

    private static String stanceText(String mode) {
        return switch (mode) {
            case "PRODUCE" -> "Produce: the stores fill with a day's goods, rents run a quarter higher and the people make half again as much. "
                    + "Lost men come back slower and raids hit harder.";
            case "ATTACK" -> "Attack: while a target is set, a war party marches on it at once and again every five minutes. The people make "
                    + "a quarter less.";
            default -> "Defend: lost men are replaced twice as fast and the walls hold raids much harder while you're away. A war party "
                    + "of this castle's that hasn't gone in yet turns for home.";
        };
    }

    private static BlockPos playerPos() {
        var p = Minecraft.getInstance().player;
        return p != null ? p.blockPosition() : BlockPos.ZERO;
    }

    private static int kitLevel(HoldingInfo h) {
        var m = java.util.regex.Pattern.compile("Kit level (\\d)").matcher(h.labour());
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static int fortifyStage(HoldingInfo h) {
        var m = java.util.regex.Pattern.compile("Fortified, stage (\\d)").matcher(h.status());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String etaFrom(HoldingInfo h) {
        var p = Minecraft.getInstance().player;
        if (p == null) return "?";
        double dx = p.getX() - h.pos().getX(), dz = p.getZ() - h.pos().getZ();
        int secs = (int) (Math.sqrt(dx * dx + dz * dz) / Columns.SPEED);
        return secs >= 60 ? (secs / 60) + " min " + (secs % 60) + " s" : secs + " s";
    }

    private static boolean inCallRange(HoldingInfo h) {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        double dx = p.getX() - h.pos().getX(), dz = p.getZ() - h.pos().getZ();
        int r = Company.CALL_RANGE;
        return dx * dx + dz * dz <= (double) r * r;
    }

    /** Depot marked with a War Banner somewhere in the player's inventory. */
    @Nullable
    private static BlockPos markedDepot() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        for (var stack : player.getInventory().items) {
            if (stack.getItem() instanceof net.aiagent256.stormhold.item.WarBannerItem) {
                BlockPos d = net.aiagent256.stormhold.item.WarBannerItem.depot(stack);
                if (d != null) return d;
            }
        }
        return null;
    }

    private EditBox box(int x, int y, int w, int value) {
        EditBox b = new EditBox(this.font, x, y, w, 18, Component.empty());
        b.setValue(String.valueOf(value));
        b.setFilter(s -> s.isEmpty() || s.equals("-") || s.matches("-?\\d{0,8}"));
        this.addRenderableWidget(b);
        return b;
    }

    @Nullable
    private static BlockPos parse(EditBox x, @Nullable EditBox y, EditBox z) {
        try {
            int yy = y != null ? Integer.parseInt(y.getValue()) : 64;
            return new BlockPos(Integer.parseInt(x.getValue()), yy, Integer.parseInt(z.getValue()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String s, int n) {
        n = Math.max(4, n);
        return s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
