package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.net.HoldingInfo;
import net.aiagent256.stormhold.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Command every castle you hold: stance, march orders, supply lines, mustering. */
public class WarTableScreen extends Screen {
    private static final int W = 430, H = 358;
    private Net.WarTableData data;
    private int selected;
    private int page;
    private EditBox ax, az, sx, sy, sz;

    public WarTableScreen(Net.WarTableData data) {
        super(Component.literal("War Table"));
        this.data = data;
    }

    public void update(Net.WarTableData d) {
        this.data = d;
        if (selected >= d.holdings().size()) selected = Math.max(0, d.holdings().size() - 1);
        this.rebuildWidgets();
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

    @Override
    protected void init() {
        int x0 = left(), y0 = top();
        List<HoldingInfo> l = data.holdings();
        int perPage = 8;
        int start = page * perPage;
        for (int i = start; i < Math.min(l.size(), start + perPage); i++) {
            final int idx = i;
            HoldingInfo h = l.get(i);
            String label = (idx == selected ? "> " : "") + h.name();
            this.addRenderableWidget(Button.builder(Component.literal(trim(label, 17)), b -> {
                selected = idx;
                rebuildWidgets();
            }).bounds(x0 + 6, y0 + 22 + (i - start) * 22, 112, 20).build());
        }
        if (l.size() > perPage) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = Math.max(0, page - 1);
                rebuildWidgets();
            }).bounds(x0 + 6, y0 + H - 26, 54, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = Math.min((l.size() - 1) / perPage, page + 1);
                rebuildWidgets();
            }).bounds(x0 + 64, y0 + H - 26, 54, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Realm"), b -> Net.sendAction(new java.util.UUID(0, 0), Net.OPEN_REALM, BlockPos.ZERO, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("The map of every castle you know, the houses and how they stand with you, and the realm's record.")))
                .bounds(x0 + W - 60, y0 + 4, 54, 14).build());
        HoldingInfo h = current();
        if (h == null) return;
        int rx = x0 + 128;
        int y = y0 + 128;
        this.addRenderableWidget(Button.builder(Component.literal("Expand"), b -> Net.sendAction(h.id(), Net.EXPAND_FIEF, BlockPos.ZERO, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("The people raise one more building (a pasture, a mine, a mill, "
                        + "then more cottages) for 32 bread and 8 iron from the stores. More folk, more produce.")))
                .bounds(rx + 236, y0 + 96, 54, 16).build());
        String[] modes = {"DEFEND", "PRODUCE", "ATTACK"};
        String[] labels = {"Defend", "Produce", "Attack"};
        for (int i = 0; i < 3; i++) {
            final int act = i;
            boolean on = modes[i].equals(h.mode());
            this.addRenderableWidget(Button.builder(Component.literal((on ? "[" : "") + labels[i] + (on ? "]" : "")),
                    b -> Net.sendAction(h.id(), act, BlockPos.ZERO, 0)).bounds(rx + i * 98, y, 94, 20).build());
        }
        BlockPos me = Minecraft.getInstance().player != null ? Minecraft.getInstance().player.blockPosition() : BlockPos.ZERO;
        y += 34;
        ax = box(rx + 66, y, h.attack() != null ? h.attack().getX() : me.getX());
        az = box(rx + 122, y, h.attack() != null ? h.attack().getZ() : me.getZ());
        this.addRenderableWidget(Button.builder(Component.literal("Here"), b -> {
            ax.setValue(String.valueOf(me.getX()));
            az.setValue(String.valueOf(me.getZ()));
        }).bounds(rx + 170, y, 32, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Set"), b -> {
            BlockPos p = parse(ax, null, az);
            if (p != null) Net.sendAction(h.id(), Net.SET_ATTACK, p, 0);
        }).bounds(rx + 204, y, 28, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("March now"), b -> Net.sendAction(h.id(), Net.MARCH_NOW, BlockPos.ZERO, 0))
                .bounds(rx + 234, y, 56, 18).build());
        y += 30;
        BlockPos depot = markedDepot();
        BlockPos sup = h.supply() != null ? h.supply() : depot != null ? depot : me.below();
        sx = box(rx + 66, y, sup.getX());
        sy = box(rx + 122, y, sup.getY());
        sz = box(rx + 178, y, sup.getZ());
        this.addRenderableWidget(Button.builder(Component.literal("Set"), b -> {
            BlockPos p = parse(sx, sy, sz);
            if (p != null) Net.sendAction(h.id(), Net.SET_SUPPLY, p, 0);
        }).bounds(rx + 226, y, 28, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Clear"), b -> Net.sendAction(h.id(), Net.CLEAR_SUPPLY, BlockPos.ZERO, 0))
                .bounds(rx + 256, y, 34, 18).build());
        y += 30;
        // ---- your company: how many ride with you
        int[] c = h.counts();
        int[] max = {Math.max(0, c[1] - 1), Math.max(0, c[2] - 1), Math.max(0, c[3] - 1)};
        int[] acts = {Net.MUSTER_KNIGHTS, Net.MUSTER_ARCHERS, Net.MUSTER_CROSSBOWMEN};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            want[k] = Math.min(want[k], max[k]);
            int bx = rx + 52 + i * 70;
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                want[k] = Math.max(0, want[k] - (hasShiftDown() ? 5 : 1));
                rebuildWidgets();
            }).bounds(bx, y, 14, 18).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                want[k] = Math.min(max[k], want[k] + (hasShiftDown() ? 5 : 1));
                rebuildWidgets();
            }).bounds(bx + 40, y, 14, 18).build());
        }
        boolean near = inCallRange(h);
        Button muster = Button.builder(Component.literal("Muster"), b -> {
            for (int i = 0; i < 3; i++) if (want[i] > 0) Net.sendAction(h.id(), acts[i], BlockPos.ZERO, want[i]);
        }).bounds(rx + 250, y, 48, 18).build();
        muster.active = near && (want[0] + want[1] + want[2]) > 0;
        this.addRenderableWidget(muster);
        y += 24;
        Button dismiss = Button.builder(Component.literal("Dismiss here"), b -> Net.sendAction(h.id(), Net.DISMISS, BlockPos.ZERO, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Every man of yours within 24 blocks joins the garrison of "
                        + h.name() + ". You must be within " + net.aiagent256.stormhold.holding.Company.CALL_RANGE + " blocks of it.")))
                .bounds(rx, y, 84, 18).build();
        dismiss.active = near;
        this.addRenderableWidget(dismiss);
        Button send = Button.builder(Component.literal("Send to me"), b -> Net.sendAction(h.id(), Net.SEND_COLUMN,
                new BlockPos(want[0], want[1], want[2]), 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("The chosen men leave " + h.name()
                        + " now as a column and ride to you on the clock, " + (int) net.aiagent256.stormhold.holding.Columns.SPEED
                        + " blocks a second (about " + etaFrom(h) + " from here). They join your company when they arrive. "
                        + "A castle of a house that hates you near the road will take a bite out of them.")))
                .bounds(rx + 88, y, 72, 18).build();
        send.active = (want[0] + want[1] + want[2]) > 0;
        this.addRenderableWidget(send);
        boolean supply = h.status().contains("Supply line");
        this.addRenderableWidget(Button.builder(Component.literal(supply ? "Supply: on" : "Supply line"), b -> Net.sendAction(h.id(), Net.KEEP_WITH_ME,
                BlockPos.ZERO, supply ? 0 : Math.max(1, want[0] + want[1] + want[2])))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(supply
                        ? "Click to cancel the standing order."
                        : "Standing order: keep " + Math.max(1, want[0] + want[1] + want[2]) + " men (the counts above) with you. "
                        + "Whenever your company runs short, " + h.name() + " sends a column, at most one every five minutes.")))
                .bounds(rx + 164, y, 70, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> Net.sendAction(h.id(), Net.REFRESH, BlockPos.ZERO, 0))
                .bounds(rx + 238, y, 52, 18).build());
        // ---- armoury: kit for the men around you
        y += 32;
        String[] kits = {"Iron", "Diamond", "Netherite"};
        for (int i = 0; i < 3; i++) {
            final int tier = i + 1;
            this.addRenderableWidget(Button.builder(Component.literal(kits[i]),
                    b -> Net.sendAction(h.id(), Net.ARM, new BlockPos(enchant ? 1 : 0, 0, 0), tier))
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Full " + kits[i].toLowerCase()
                            + " armour and a weapon for every man of yours within 24 blocks who's wearing worse. Paid from your pack, then "
                            + h.name() + "'s stores when you're within " + net.aiagent256.stormhold.holding.Company.CALL_RANGE
                            + " blocks. Old kit comes back to you.")))
                    .bounds(rx + 52 + i * 64, y, 60, 18).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal(enchant ? "[Enchanted]" : "Enchanted"), b -> {
            enchant = !enchant;
            rebuildWidgets();
        }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Protection and Sharpness/Power II-IV by metal, Unbreaking III; "
                + "fire and piercing on netherite. " + net.aiagent256.stormhold.holding.Armoury.ENCHANT_LEVELS + " experience levels per man.")))
                .bounds(rx + 246, y, 56, 18).build());
        // ---- works: the master builder (below the armoury notes)
        y = y0 + 332;
        boolean busy = h.status().contains("Builder ");
        Button repair = Button.builder(Component.literal("Repair walls"), b -> Net.sendAction(h.id(), Net.REPAIR, BlockPos.ZERO, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("The Master Builder puts back every block of the castle's "
                        + "plans that's missing: walls, gate, portcullis, roofs. A stone from the stores per block keeps the pace; without stone it's slow. "
                        + "Your own additions are left alone.")))
                .bounds(rx + 52, y, 90, 18).build();
        repair.active = !busy;
        this.addRenderableWidget(repair);
        int stage = Math.min(3, fortifyStage(h) + 1);
        int[] cost = net.aiagent256.stormhold.holding.Builder.fortifyCost(stage);
        Button fort = Button.builder(Component.literal(fortifyStage(h) >= 3 ? "Fortified" : "Fortify " + "I".repeat(stage)),
                b -> Net.sendAction(h.id(), Net.FORTIFY, BlockPos.ZERO, 0))
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Stage " + stage + ": "
                        + net.aiagent256.stormhold.holding.Builder.stageName(stage) + ". Costs " + cost[0] + " stone and " + cost[1]
                        + " iron from the stores. Each stage lets the castle hold 15% more men.")))
                .bounds(rx + 146, y, 90, 18).build();
        fort.active = !busy && fortifyStage(h) < 3;
        this.addRenderableWidget(fort);
    }

    private static int fortifyStage(HoldingInfo h) {
        var m = java.util.regex.Pattern.compile("Fortified, stage (\\d)").matcher(h.status());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private boolean enchant;

    /** How many of each (knights, archers, crossbowmen) the player wants to take; kept between refreshes. */
    private final int[] want = {4, 2, 0};

    private static String etaFrom(HoldingInfo h) {
        var p = Minecraft.getInstance().player;
        if (p == null) return "?";
        double dx = p.getX() - h.pos().getX(), dz = p.getZ() - h.pos().getZ();
        int secs = (int) (Math.sqrt(dx * dx + dz * dz) / net.aiagent256.stormhold.holding.Columns.SPEED);
        return secs >= 60 ? (secs / 60) + " min " + (secs % 60) + " s" : secs + " s";
    }

    private static boolean inCallRange(HoldingInfo h) {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        double dx = p.getX() - h.pos().getX(), dz = p.getZ() - h.pos().getZ();
        int r = net.aiagent256.stormhold.holding.Company.CALL_RANGE;
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

    private EditBox box(int x, int y, int value) {
        EditBox b = new EditBox(this.font, x, y, 46, 18, Component.empty());
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
        return s.length() <= n ? s : s.substring(0, n - 1) + ".";
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g);
        int x0 = left(), y0 = top();
        g.fill(x0, y0, x0 + W, y0 + H, 0xE0241c14);
        g.fill(x0 + 2, y0 + 2, x0 + W - 2, y0 + H - 2, 0xE03a2e20);
        g.fill(x0 + 122, y0 + 18, x0 + 123, y0 + H - 6, 0xFF6b5a40);
        g.drawString(this.font, "War Table", x0 + 8, y0 + 7, 0xF0D8A0, false);
        g.drawString(this.font, "build " + net.aiagent256.stormhold.Stormhold.buildId(), x0 + 8, y0 + H - 12, 0x7C6C4C, false);
        HoldingInfo h = current();
        int rx = x0 + 128;
        if (h == null) {
            g.drawWordWrap(this.font, Component.literal("You hold no castles. Kill a castle's lord, then raise a War Banner on "
                    + "its banner stand and hold it until the garrison yields."), rx, y0 + 26, W - 136, 0xE0D0B0);
        } else {
            String tier = net.aiagent256.stormhold.holding.Holding.tierName(h.tier());
            g.drawString(this.font, h.name(), rx, y0 + 7, 0xFFE8B0, false);
            g.drawString(this.font, tier + " at " + h.pos().getX() + ", " + h.pos().getZ(), rx, y0 + 22, 0xC8B898, false);
            int[] c = h.counts(), cap = h.capacity();
            g.drawString(this.font, "Knights " + c[1] + "/" + cap[1] + "  Archers " + c[2] + "/" + cap[2]
                    + "  Crossbows " + c[3] + "/" + cap[3] + "  Captains " + c[0], rx, y0 + 36, 0xE0D0B0, false);
            g.drawString(this.font, "Stores: " + trim(h.stock(), 56), rx, y0 + 50, 0xC8B898, false);
            g.drawString(this.font, trim(h.realm(), 58), rx, y0 + 60, 0xA8B8C8, false);
            g.drawWordWrap(this.font, Component.literal(h.status().isEmpty() ? "Quiet." : h.status()), rx, y0 + 72, W - 136, 0xD8A070);
            g.drawWordWrap(this.font, Component.literal(h.fief()), rx, y0 + 96, W - 136 - 60, 0xB8C8A0);
            g.drawString(this.font, "Stance", rx, y0 + 118, 0xF0D8A0, false);
            g.drawString(this.font, "March on", rx, y0 + 165, 0xE0D0B0, false);
            g.drawString(this.font, "x", rx + 58, y0 + 165, 0xE0D0B0, false);
            g.drawString(this.font, "z", rx + 114, y0 + 165, 0xE0D0B0, false);
            g.drawString(this.font, "Supply to", rx, y0 + 195, 0xE0D0B0, false);
            g.drawString(this.font, "x", rx + 58, y0 + 195, 0xE0D0B0, false);
            g.drawString(this.font, "y", rx + 114, y0 + 195, 0xE0D0B0, false);
            g.drawString(this.font, "z", rx + 170, y0 + 195, 0xE0D0B0, false);
            g.drawString(this.font, "Company", rx, y0 + 225, 0xF0D8A0, false);
            String[] roles = {"Knights", "Archers", "Crossbows"};
            int[] c2 = h.counts();
            int[] have = {c2[1], c2[2], c2[3]};
            for (int i = 0; i < 3; i++) {
                int bx = rx + 52 + i * 70;
                g.drawString(this.font, roles[i] + " (" + have[i] + ")", bx, y0 + 211, 0xC8B898, false);
                g.drawCenteredString(this.font, String.valueOf(want[i]), bx + 27, y0 + 225, 0xFFFFFF);
            }
            boolean near = inCallRange(h);
            g.drawString(this.font, near ? "Men you call appear at your side." + (data.recruits() ? " (Recruits)" : "")
                    : "Too far: get within " + net.aiagent256.stormhold.holding.Company.CALL_RANGE + " blocks of " + trim(h.name(), 14) + ".",
                    rx, y0 + 265, near ? 0x9C8C6C : 0xD88060, false);
            g.drawString(this.font, "Armoury", rx, y0 + 281, 0xF0D8A0, false);
            var mc = Minecraft.getInstance().player;
            int iron = mc != null ? mc.getInventory().countItem(net.minecraft.world.item.Items.IRON_INGOT) : 0;
            int dia = mc != null ? mc.getInventory().countItem(net.minecraft.world.item.Items.DIAMOND) : 0;
            int neth = mc != null ? mc.getInventory().countItem(net.minecraft.world.item.Items.NETHERITE_INGOT) : 0;
            int lvl = mc != null ? mc.experienceLevel : 0;
            g.drawString(this.font, "Each: 26 iron | 26 diamond | 26 diamond + 5 netherite", rx, y0 + 300, 0x9C8C6C, false);
            g.drawString(this.font, "Pack: " + iron + " iron, " + dia + " diamond, " + neth + " netherite, " + lvl + " lvl", rx, y0 + 310, 0x9C8C6C, false);
            g.drawString(this.font, h.supply() == null ? "No supply line." : "Couriers take surplus to " + h.supply().toShortString() + " daily.", rx, y0 + 320, 0x9C8C6C, false);
            g.drawString(this.font, "Works", rx, y0 + 337, 0xF0D8A0, false);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
