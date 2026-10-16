package net.aiagent256.stormhold.house;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatterns;

/** An NPC house: a name and a coat of arms. */
public class House {
    @SuppressWarnings("unchecked")
    private static final ResourceKey<BannerPattern>[] EMBLEMS = new ResourceKey[]{
            BannerPatterns.STRIPE_CENTER, BannerPatterns.STRAIGHT_CROSS, BannerPatterns.CROSS,
            BannerPatterns.RHOMBUS_MIDDLE, BannerPatterns.CIRCLE_MIDDLE, BannerPatterns.TRIANGLE_TOP,
            BannerPatterns.TRIANGLE_BOTTOM, BannerPatterns.HALF_HORIZONTAL, BannerPatterns.DIAGONAL_LEFT,
            BannerPatterns.FLOWER, BannerPatterns.SKULL, BannerPatterns.GLOBE, BannerPatterns.STRIPE_DOWNRIGHT,
            BannerPatterns.TRIANGLES_TOP, BannerPatterns.HALF_VERTICAL, BannerPatterns.STRIPE_TOP,
            BannerPatterns.GRADIENT, BannerPatterns.BRICKS
    };

    public final String id;
    public final String name;
    public final DyeColor base;
    public final DyeColor accent;
    public final int emblem;
    public final boolean border;
    /** Base attitude toward strangers; see {@link Standing}. Friendly houses start at 40, wary ones at 0. */
    public int disposition;
    /** The holding that is this house's seat (its Great City), if one has been found. */
    @org.jetbrains.annotations.Nullable public java.util.UUID capital;
    /** When the house next does something of its own (see {@link Politics}). */
    public long nextMove;

    public House(String id, String name, DyeColor base, DyeColor accent, int emblem, boolean border) {
        this.id = id;
        this.name = name;
        this.base = base;
        this.accent = accent;
        this.emblem = Math.floorMod(emblem, EMBLEMS.length);
        this.border = border;
    }

    public String title() {
        return "House " + name;
    }

    public ListTag patterns() {
        BannerPattern.Builder b = new BannerPattern.Builder().addPattern(EMBLEMS[emblem], accent);
        if (border) b.addPattern(BannerPatterns.BORDER, accent);
        return b.toListTag();
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("Id", id);
        t.putString("Name", name);
        t.putInt("Base", base.getId());
        t.putInt("Accent", accent.getId());
        t.putInt("Emblem", emblem);
        t.putBoolean("Border", border);
        t.putInt("Disposition", disposition);
        if (capital != null) t.putUUID("Capital", capital);
        t.putLong("NextMove", nextMove);
        return t;
    }

    public static House load(CompoundTag t) {
        House h = new House(t.getString("Id"), t.getString("Name"), DyeColor.byId(t.getInt("Base")),
                DyeColor.byId(t.getInt("Accent")), t.getInt("Emblem"), t.getBoolean("Border"));
        h.disposition = t.getInt("Disposition");
        if (t.hasUUID("Capital")) h.capital = t.getUUID("Capital");
        h.nextMove = t.getLong("NextMove");
        return h;
    }

}
