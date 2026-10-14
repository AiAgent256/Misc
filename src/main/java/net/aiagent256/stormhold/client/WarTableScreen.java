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
    private static final int W = 430, H = 236;
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
        HoldingInfo h = current();
        if (h == null) return;
        int rx = x0 + 128;
        int y = y0 + 92;
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
        if (data.recruits()) {
            this.addRenderableWidget(Button.builder(Component.literal("Muster 5 to me (Recruits)"),
                    b -> Net.sendAction(h.id(), Net.MUSTER, BlockPos.ZERO, 5)).bounds(rx, y, 160, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> Net.sendAction(h.id(), Net.REFRESH, BlockPos.ZERO, 0))
                .bounds(rx + 166, y, 60, 20).build());
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
        HoldingInfo h = current();
        int rx = x0 + 128;
        if (h == null) {
            g.drawWordWrap(this.font, Component.literal("You hold no castles. Kill a castle's lord, then raise a War Banner on "
                    + "its banner stand and hold it until the garrison yields."), rx, y0 + 26, W - 136, 0xE0D0B0);
        } else {
            String tier = h.tier() == 3 ? "Great castle" : h.tier() == 2 ? "Holdfast" : "Watchtower";
            g.drawString(this.font, h.name(), rx, y0 + 7, 0xFFE8B0, false);
            g.drawString(this.font, tier + " at " + h.pos().getX() + ", " + h.pos().getZ(), rx, y0 + 22, 0xC8B898, false);
            int[] c = h.counts(), cap = h.capacity();
            g.drawString(this.font, "Knights " + c[1] + "/" + cap[1] + "  Archers " + c[2] + "/" + cap[2]
                    + "  Crossbows " + c[3] + "/" + cap[3] + "  Captains " + c[0], rx, y0 + 36, 0xE0D0B0, false);
            g.drawString(this.font, "Stores: " + trim(h.stock(), 56), rx, y0 + 50, 0xC8B898, false);
            g.drawWordWrap(this.font, Component.literal(h.status().isEmpty() ? "Quiet." : h.status()), rx, y0 + 64, W - 136, 0xD8A070);
            g.drawString(this.font, "Stance", rx, y0 + 82, 0xF0D8A0, false);
            g.drawString(this.font, "March on", rx, y0 + 131, 0xE0D0B0, false);
            g.drawString(this.font, "x", rx + 58, y0 + 131, 0xE0D0B0, false);
            g.drawString(this.font, "z", rx + 114, y0 + 131, 0xE0D0B0, false);
            g.drawString(this.font, "Supply to", rx, y0 + 161, 0xE0D0B0, false);
            g.drawString(this.font, "x", rx + 58, y0 + 161, 0xE0D0B0, false);
            g.drawString(this.font, "y", rx + 114, y0 + 161, 0xE0D0B0, false);
            g.drawString(this.font, "z", rx + 170, y0 + 161, 0xE0D0B0, false);
            g.drawString(this.font, h.supply() == null ? "No supply line." : "Couriers take surplus to " + h.supply().toShortString() + " daily.", rx, y0 + 214, 0x9C8C6C, false);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
