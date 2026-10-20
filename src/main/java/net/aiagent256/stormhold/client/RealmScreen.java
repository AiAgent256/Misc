package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.holding.Chronicle;
import net.aiagent256.stormhold.net.Net;
import net.aiagent256.stormhold.net.RealmData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The Realm page: a map of every castle you've seen, coloured by house and by how they stand with
 * you, armies on the march drawn between them; beside it your holdings, the houses, and the record.
 */
public class RealmScreen extends Screen {
    /** The frame fits the window, down to the smallest GUI: set in init. */
    private int W = 440, H = 340, MAP_W = 262;
    private static final int BG = 0xE0241c14, PANEL = 0xE03a2e20, MAP_BG = 0xFF151a12, LINE = 0xFF6b5a40;
    private RealmData data;
    private int tab;                 // 0 holdings, 1 houses, 2 record
    private int scroll;
    // map transform
    private double scale;
    private int ox, oz;              // world coords at the map's top-left
    /** Fit to your own castles (true) or to every castle you know. Kept across reopenings. */
    private static boolean mine = true;
    /** A castle picked from the list, ringed on the map for a few seconds. */
    @org.jetbrains.annotations.Nullable private java.util.UUID picked;
    private long pickedAt;
    /** Where the holdings list drew each castle's name, for clicks: id, top, bottom. */
    private final List<Object[]> rows = new ArrayList<>();

    public RealmScreen(RealmData data) {
        super(Component.literal("Realm"));
        this.data = data;
    }

    public void update(RealmData d) {
        this.data = d;
        rebuildWidgets();
    }

    private int left() {
        return (this.width - W) / 2;
    }

    private int top() {
        return (this.height - H) / 2;
    }

    @Override
    protected void init() {
        W = Math.min(440, Math.max(360, this.width - 12));
        H = Math.min(340, Math.max(214, this.height - 12));
        MAP_W = W * 262 / 440;
        int x0 = left(), y0 = top();
        int rx = x0 + MAP_W + 8;
        String[] tabs = {"Holdings", "Houses", "Record"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            this.addRenderableWidget(Button.builder(Component.literal((tab == i ? "[" : "") + tabs[i] + (tab == i ? "]" : "")), b -> {
                tab = t;
                scroll = 0;
                rebuildWidgets();
            }).bounds(rx + i * ((W - MAP_W - 16) / 3), y0 + 20, (W - MAP_W - 16) / 3 - 2, 16).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("War Table"), b -> Net.sendAction(new java.util.UUID(0, 0), Net.REFRESH, BlockPos.ZERO, 0))
                .bounds(x0 + W - 66, y0 + 4, 60, 14).build());
        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> Net.sendAction(new java.util.UUID(0, 0), Net.OPEN_REALM, BlockPos.ZERO, 0))
                .bounds(x0 + W - 130, y0 + 4, 60, 14).build());
        Button fit = Button.builder(Component.literal(mine ? "Show all" : "My castles"), b -> {
            mine = !mine;
            rebuildWidgets();
        }).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(mine
                ? "Fit the map to every castle you know of."
                : "Fit the map to the castles you hold. Click one of yours to open it on the War Table.")))
                .bounds(x0 + W - 204, y0 + 4, 70, 14).build();
        fit.active = !data.own().isEmpty() || !mine;
        this.addRenderableWidget(fit);
        fitMap();
    }

    private void fitMap() {
        int minX = data.px(), maxX = data.px(), minZ = data.pz(), maxZ = data.pz();
        boolean own = mine && data.castles().stream().anyMatch(c -> c.attitude() == 0);
        for (RealmData.Castle c : data.castles()) {
            if (own && c.attitude() != 0) continue;
            minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z());
        }
        int pad = own ? 240 : 160;
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;
        double sx = (MAP_W - 8) / (double) Math.max(1, maxX - minX);
        double sz = (H - 48) / (double) Math.max(1, maxZ - minZ);
        scale = Math.min(sx, sz);
        // centre the fitted area
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        ox = cx - (int) ((MAP_W - 8) / 2 / scale);
        oz = cz - (int) ((H - 48) / 2 / scale);
    }

    private int mx(int x) {
        return left() + 4 + (int) ((x - ox) * scale);
    }

    private int mz(int z) {
        return top() + 20 + (int) ((z - oz) * scale);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g);
        mouseXNow = mouseX;
        mouseYNow = mouseY;
        chips.clear();
        chipTips.clear();
        chipTip = null;
        int x0 = left(), y0 = top();
        g.fill(x0, y0, x0 + W, y0 + H, BG);
        g.fill(x0 + 2, y0 + 2, x0 + W - 2, y0 + H - 2, PANEL);
        g.drawString(this.font, "The Realm, day " + data.day(), x0 + 8, y0 + 7, 0xF0D8A0, false);
        // ---- the map
        int mx0 = x0 + 4, my0 = y0 + 20, mx1 = x0 + 4 + MAP_W - 8, my1 = y0 + H - 28;
        g.fill(mx0, my0, mx1, my1, MAP_BG);
        g.enableScissor(mx0, my0, mx1, my1);
        // a faint grid every 512 blocks
        int gx = Math.floorDiv(ox, 512) * 512;
        for (int x = gx; mx(x) < mx1; x += 512) g.fill(mx(x), my0, mx(x) + 1, my1, 0x30FFFFFF);
        int gz = Math.floorDiv(oz, 512) * 512;
        for (int z = gz; mz(z) < my1; z += 512) g.fill(mx0, mz(z), mx1, mz(z) + 1, 0x30FFFFFF);
        // your roads, faint and brown under everything else
        for (RealmData.Line l : data.roads()) line(g, mx(l.x1()), mz(l.z1()), mx(l.x2()), mz(l.z2()), 0xB0A08050);
        // armies on the march
        for (RealmData.Line l : data.lines()) {
            int c = (l.raid() ? 0xFF000000 : 0xC0000000) | RealmData.rgb(l.color());
            line(g, mx(l.x1()), mz(l.z1()), mx(l.x2()), mz(l.z2()), c);
            int ax = mx(l.x2()), az = mz(l.z2());
            g.fill(ax - 2, az - 2, ax + 3, az + 3, l.raid() ? 0xFFFF4040 : 0xFFFFFFFF);
        }
        // castles
        RealmData.Castle hover = null;
        for (RealmData.Castle c : data.castles()) {
            int px = mx(c.x()), pz = mz(c.z());
            int r = c.tier() >= 4 ? 5 : c.tier() == 3 ? 4 : c.tier() == 2 ? 3 : 2;
            int fill = 0xFF000000 | RealmData.rgb(c.color());
            int edge = c.attitude() == 0 ? 0xFFFFFFFF : c.attitude() == 1 ? 0xFF60E060 : c.attitude() == 2 ? 0xFFC0C0A0
                    : c.attitude() == 3 ? 0xFFE04040 : 0xFF808080;
            g.fill(px - r - 1, pz - r - 1, px + r + 2, pz + r + 2, edge);
            g.fill(px - r, pz - r, px + r + 1, pz + r + 1, fill);
            if (c.seat()) g.fill(px - 1, pz - r - 4, px + 2, pz - r - 1, 0xFFFFD700);       // a crown over the seat
            if (c.underAttack()) g.fill(px + r + 2, pz - r - 3, px + r + 5, pz - r, 0xFFFF2020);
            if (!c.lordAlive() && c.attitude() != 0) g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFF000000);
            if (c.id().equals(picked) && System.currentTimeMillis() - pickedAt < 4000) {
                int q = r + 4;
                g.fill(px - q - 1, pz - q - 1, px + q + 2, pz - q, 0xFFFFD700);
                g.fill(px - q - 1, pz + q + 1, px + q + 2, pz + q + 2, 0xFFFFD700);
                g.fill(px - q - 1, pz - q, px - q, pz + q + 1, 0xFFFFD700);
                g.fill(px + q + 1, pz - q, px + q + 2, pz + q + 1, 0xFFFFD700);
            }
            // your own always carry their names; the great ones too, and every one once you zoom in
            if (c.attitude() == 0) g.drawString(this.font, c.name(), px + r + 3, pz - 4, 0xFFFFFFFF, true);
            else if (c.tier() >= 3 || scale >= 0.35) g.drawString(this.font, c.name(), px + r + 3, pz - 4, 0xFFE8D0A0, false);
            if (mouseX >= px - r - 2 && mouseX <= px + r + 3 && mouseY >= pz - r - 2 && mouseY <= pz + r + 3) hover = c;
        }
        // dragons: a red diamond, dim once dead, with the bounty beside it
        RealmData.Beast beast = null;
        for (RealmData.Beast d : data.beasts()) {
            int px = mx(d.x()), pz = mz(d.z());
            int c = d.dead() ? 0xFF604040 : 0xFFFF3030;
            for (int i = -3; i <= 3; i++) {
                int w = 3 - Math.abs(i);
                g.fill(px - w, pz + i, px + w + 1, pz + i + 1, c);
            }
            if (!d.dead() && d.bounty() > 0) g.drawString(this.font, d.bounty() + "e", px + 5, pz - 4, 0xFF80D0FF, false);
            if (mouseX >= px - 4 && mouseX <= px + 4 && mouseY >= pz - 4 && mouseY <= pz + 4) beast = d;
        }
        // you
        int ppx = mx(data.px()), ppz = mz(data.pz());
        g.fill(ppx - 1, ppz - 3, ppx + 2, ppz + 4, 0xFFFFFFFF);
        g.fill(ppx - 3, ppz - 1, ppx + 4, ppz + 2, 0xFFFFFFFF);
        g.disableScissor();
        g.drawString(this.font, "N up. 1 grid = 512 blocks. Scroll to zoom, drag to move" + (data.own().isEmpty() ? "." : ", hover any for the odds, click to command."),
                mx0 + 2, my1 + 4, 0x9C8C6C, false);
        // ---- the panel
        int rx = x0 + MAP_W + 8;
        g.fill(x0 + MAP_W + 2, y0 + 18, x0 + MAP_W + 3, y0 + H - 6, LINE);
        int ry = y0 + 42;
        int rw = W - MAP_W - 16;
        g.enableScissor(rx, ry, rx + rw, y0 + H - 6);
        int y = ry - scroll;
        switch (tab) {
            case 0 -> y = holdings(g, rx, y, rw);
            case 1 -> y = houses(g, rx, y, rw);
            default -> y = record(g, rx, y, rw);
        }
        g.disableScissor();
        maxScroll = Math.max(0, (y + scroll) - (y0 + H - 10));
        super.render(g, mouseX, mouseY, partial);
        if (chipTip != null) {
            List<FormattedCharSequence> tip = this.font.split(Component.literal(chipTip), 220);
            g.renderTooltip(this.font, tip, mouseX, mouseY);
        }
        if (hover != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hover.name()));
            tip.add(Component.literal(tierWord(hover.tier()) + ", " + hover.owner()));
            tip.add(Component.literal("Renown " + hover.renown() + ". " + (hover.attitude() == 0 ? "Yours." : hover.attitude() == 1 ? "Friendly."
                    : hover.attitude() == 2 ? "Wary of you." : hover.attitude() == 3 ? "Hostile." : "")));
            if (!hover.lordAlive() && hover.attitude() != 0) tip.add(Component.literal("Its lord is dead."));
            if (hover.underAttack()) tip.add(Component.literal("Under attack."));
            int dx = hover.x() - data.px(), dz = hover.z() - data.pz();
            tip.add(Component.literal((int) Math.sqrt(dx * dx + dz * dz) + " blocks " + way(dx, dz) + ", at " + hover.x() + ", " + hover.z()));
            if (hover.attitude() == 0) tip.add(Component.literal("Click to open it on the War Table.").withStyle(net.minecraft.ChatFormatting.GRAY));
            else if (!hover.odds().isEmpty()) {
                for (FormattedCharSequence l : this.font.split(Component.literal(hover.odds()), 220))
                    tip.add(Component.literal(toString(l)).withStyle(net.minecraft.ChatFormatting.AQUA));
                if (!data.own().isEmpty())
                    tip.add(Component.literal("Click to make it the target of all your castles.").withStyle(net.minecraft.ChatFormatting.GRAY));
            }
            g.renderComponentTooltip(this.font, tip, mouseX, mouseY);
        } else if (beast != null) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(cap(beast.kind()) + (beast.dead() ? ", slain" : "")));
            tip.add(Component.literal(beast.daysAgo() == 0 ? "Seen today" : beast.daysAgo() == 1 ? "Seen yesterday" : "Seen " + beast.daysAgo() + " days ago"));
            if (!beast.dead() && beast.bounty() > 0) tip.add(Component.literal(beast.bounty() + " emeralds on its head"));
            int dx = beast.x() - data.px(), dz = beast.z() - data.pz();
            tip.add(Component.literal((int) Math.sqrt(dx * dx + dz * dz) + " blocks away, at " + beast.x() + ", " + beast.z()));
            g.renderComponentTooltip(this.font, tip, mouseX, mouseY);
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private int maxScroll;

    private int holdings(GuiGraphics g, int rx, int y, int rw) {
        if (data.own().isEmpty()) {
            g.drawString(this.font, "You hold no castle yet.", rx, y, 0xC0B090, false);
            return y + 12;
        }
        rows.clear();
        for (RealmData.Own o : data.own()) {
            int top = y;
            g.drawString(this.font, o.name(), rx, y, o.underAttack() ? 0xFF6060 : 0xFFE8B0, false);
            RealmData.Castle at = castle(o.id());
            if (at != null) {
                int dx = at.x() - data.px(), dz = at.z() - data.pz();
                String where = (int) Math.sqrt(dx * dx + dz * dz) + " " + way(dx, dz);
                g.drawString(this.font, where, rx + rw - this.font.width(where), y, 0x9C8C6C, false);
            }
            y += 10;
            g.drawString(this.font, tierWord(o.tier()) + ", " + o.mode() + ". " + o.troops() + "/" + o.capacity() + " men, " + o.fiefPop() + " folk.", rx, y, 0xC0B090, false);
            y += 10;
            String raid = o.underAttack() ? "Under attack!" : o.raidDays() < 0 ? "No raid expected." : o.raidDays() == 0 ? "Raid today."
                    : "Raid in " + o.raidDays() + (o.raidDays() == 1 ? " day." : " days.");
            g.drawString(this.font, "Builder " + o.builder() + ". " + raid, rx, y, o.underAttack() || o.raidDays() <= 1 && o.raidDays() >= 0 ? 0xFF8080 : 0xC0B090, false);
            y += 10;
            for (FormattedCharSequence l : this.font.split(Component.literal(o.stock().isEmpty() ? "Stores empty." : o.stock()), rw)) {
                g.drawString(this.font, l, rx, y, 0x9C8C6C, false);
                y += 9;
            }
            rows.add(new Object[]{o.id(), top, y});
            y += 5;
        }
        return y;
    }

    private int houses(GuiGraphics g, int rx, int y, int rw) {
        if (data.houses().isEmpty()) {
            g.drawString(this.font, "You've met no house yet.", rx, y, 0xC0B090, false);
            return y + 12;
        }
        for (RealmData.HouseMark h : data.houses()) {
            g.fill(rx, y, rx + 7, y + 7, 0xFF000000 | RealmData.rgb(h.color()));
            g.drawString(this.font, "House " + h.name(), rx + 10, y, 0xFFE8B0, false);
            y += 10;
            int s = h.standing();
            String word = net.aiagent256.stormhold.house.Standing.word(s);
            g.drawString(this.font, word + " (" + (s > 0 ? "+" : "") + s + "), " + h.castles() + (h.castles() == 1 ? " castle" : " castles")
                    + (h.seatKnown() ? ", seat known" : ""), rx, y, s >= 25 ? 0x80E080 : s <= -10 ? 0xFF8080 : 0xC0B090, false);
            y += 10;
            if (!h.atWar().isEmpty()) {
                for (FormattedCharSequence l : this.font.split(Component.literal("At war with " + h.atWar() + "."), rw)) {
                    g.drawString(this.font, l, rx, y, 0xFF9060, false);
                    y += 9;
                }
            }
            if (!h.claims().isEmpty()) {
                for (FormattedCharSequence l : this.font.split(Component.literal(h.claims()), rw)) {
                    g.drawString(this.font, l, rx, y, 0xFFB060, false);
                    y += 9;
                }
            }
            if (!h.truce().isEmpty()) {
                g.drawString(this.font, h.truce(), rx, y, 0x80E080, false);
                y += 10;
            } else if (h.trucePrice() >= 0 && !data.own().isEmpty()) {
                // two things to click: a truce bought, or one given a hostage for
                String pay = "[Truce: " + h.trucePrice() + " emeralds]", hostage = "[Truce: a hostage]";
                int wp = this.font.width(pay), wh = this.font.width(hostage);
                int hx = rx + Math.min(wp + 6, rw - wh);
                int hy = wp + 6 + wh > rw ? y + 10 : y;
                chip(g, pay, rx, y, h.id(), 0, "Three days without raids from House " + h.name() + ", its gates open to you and its men leave yours "
                        + "alone. " + h.trucePrice() + " emeralds from your pack and then the stores.");
                chip(g, hostage, hy == y ? hx : rx, hy, h.id(), 1, "The same three days, paid with one of your knights sent to them as a hostage (from "
                        + "your seat, or the castle with the most). He comes home when it's over; break the truce and they hang him.");
                y = hy + 10;
            } else if (!h.truceNo().isEmpty() && h.standing() < 25) {
                g.drawString(this.font, h.truceNo(), rx, y, 0x9C8C6C, false);
                y += 10;
            }
            y += 5;
        }
        return y;
    }

    /** Where each house's truce offers were drawn, for clicks: x0, y0, x1, y1, house id hash, 0 pay / 1 hostage; and the tip. */
    private final List<int[]> chips = new ArrayList<>();
    private final List<String> chipTips = new ArrayList<>();
    private int mouseXNow, mouseYNow;
    @org.jetbrains.annotations.Nullable private String chipTip;

    private void chip(GuiGraphics g, String text, int x, int y, String house, int mode, String tip) {
        int w = this.font.width(text);
        boolean over = mouseXNow >= x && mouseXNow < x + w && mouseYNow >= y - 1 && mouseYNow < y + 9;
        g.drawString(this.font, text, x, y, over ? 0xFFFFE080 : 0xFFD8B060, false);
        chips.add(new int[]{x, y - 1, x + w, y + 9, house.hashCode(), mode});
        chipTips.add(tip);
        if (over) chipTip = tip;
    }

    private int record(GuiGraphics g, int rx, int y, int rw) {
        if (data.notes().isEmpty()) {
            g.drawString(this.font, "Nothing written yet.", rx, y, 0xC0B090, false);
            return y + 12;
        }
        long lastDay = Long.MIN_VALUE;
        for (int i = data.notes().size() - 1; i >= 0; i--) {
            RealmData.Note n = data.notes().get(i);
            if (n.day() >= 0 && n.day() != lastDay) {
                lastDay = n.day();
                g.drawString(this.font, "Day " + n.day(), rx, y, 0xF0D8A0, false);
                y += 10;
            } else if (n.day() < 0 && n.day() != lastDay) {
                lastDay = n.day();
                g.drawString(this.font, n.day() == -2 ? "Waiting on your word" : "Open offers", rx, y, 0xF0D8A0, false);
                y += 10;
            }
            int color = n.day() == -2 ? 0xFFE080 : n.kind() == Chronicle.WAR ? 0xFF8080 : n.kind() == Chronicle.HOUSES ? 0x80D0FF
                    : n.kind() == Chronicle.GROWTH ? 0x90E090 : 0xE0D8C8;
            for (FormattedCharSequence l : this.font.split(Component.literal(n.text()), rw)) {
                g.drawString(this.font, l, rx, y, color, false);
                y += 9;
            }
            y += 3;
        }
        return y;
    }

    private static String tierWord(int tier) {
        return net.aiagent256.stormhold.holding.Holding.tierName(tier);
    }

    /** A one-pixel line: Bresenham with fills, good enough for a map. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = 0;
        while (guard++ < 4000) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static String toString(FormattedCharSequence seq) {
        StringBuilder b = new StringBuilder();
        seq.accept((i, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        return b.toString();
    }

    @org.jetbrains.annotations.Nullable
    private RealmData.Castle castle(java.util.UUID id) {
        for (RealmData.Castle c : data.castles()) if (c.id().equals(id)) return c;
        return null;
    }

    /** "north-east" and the like, from you to there. North is -z. */
    private static String way(int dx, int dz) {
        if (dx * dx + dz * dz < 64) return "here";
        String[] w = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        return w[(int) Math.floor(((ang + 360 + 22.5) % 360) / 45)];
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;
        int x0 = left(), y0 = top();
        // a truce offered on the Houses page
        if (tab == 1 && mouseX >= x0 + MAP_W + 8 && mouseY >= y0 + 42 && mouseY < y0 + H - 6) {
            for (int[] c : chips) {
                if (mouseX >= c[0] && mouseX < c[2] && mouseY >= c[1] && mouseY < c[3]) {
                    Net.sendAction(new java.util.UUID(0, 0), Net.TRUCE, new BlockPos(c[5], 0, 0), c[4]);
                    return true;
                }
            }
        }
        // one of your castles on the map: command it
        if (mouseX < x0 + MAP_W && mouseY > y0 + 20 && mouseY < y0 + H - 28) {
            for (RealmData.Castle c : data.castles()) {
                int px = mx(c.x()), pz = mz(c.z()), r = 5;
                if (!(mouseX >= px - r - 2 && mouseX <= px + r + 3 && mouseY >= pz - r - 2 && mouseY <= pz + r + 3)) continue;
                if (c.attitude() == 0) {
                    WarTableScreen.select(c.id());
                    Net.sendAction(new java.util.UUID(0, 0), Net.REFRESH, BlockPos.ZERO, 0);
                    return true;
                }
                if (!data.own().isEmpty()) {
                    // someone else's: every castle of yours takes it for its target, and the table opens on Orders
                    WarTableScreen.openOrders();
                    Net.sendAction(new java.util.UUID(0, 0), Net.TARGET_ALL, new BlockPos(c.x(), 64, c.z()), 0);
                    return true;
                }
            }
            return false;
        }
        // a castle in the holdings list: find it on the map
        if (tab == 0 && mouseX >= x0 + MAP_W + 8) {
            for (Object[] row : rows) {
                if (mouseY >= (int) row[1] && mouseY < (int) row[2]) {
                    RealmData.Castle c = castle((java.util.UUID) row[0]);
                    if (c == null) return false;
                    scale = Math.max(scale, 0.4);
                    ox = c.x() - (int) ((MAP_W - 8) / 2 / scale);
                    oz = c.z() - (int) ((H - 48) / 2 / scale);
                    picked = c.id();
                    pickedAt = System.currentTimeMillis();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int x0 = left();
        if (mouseX >= x0 + MAP_W + 8) {
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - delta * 18));
            return true;
        }
        // zoom the map
        scale = Math.max(0.02, Math.min(2.0, scale * (delta > 0 ? 1.25 : 0.8)));
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (mouseX < left() + MAP_W) {
            ox -= (int) (dragX / scale);
            oz -= (int) (dragY / scale);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
