package net.aiagent256.stormhold.holding;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.aiagent256.stormhold.entity.Role;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Garrison posts and key points of a Stormhold castle template, relative to its origin. */
public class Layout {
    public final String name;
    public final int tier;
    public final int sizeX, sizeY, sizeZ, ground;
    /** Beds in the castle: the garrison it can sleep. 0 for layouts without the count. */
    public final int beds;
    public final BlockPos lord, bannerStand, warTable, gate, rally;
    public final EnumMap<Role, List<BlockPos>> posts = new EnumMap<>(Role.class);
    public final List<BlockPos> banners = new ArrayList<>();
    /** Non-garrison markers: barracks, notice_board, house, inn, smithy. */
    public final java.util.Map<String, List<BlockPos>> extras = new java.util.HashMap<>();
    /** Each portcullis plane as {x0, y0, z0, x1, y1, z1}, so a lost gate can be put back. */
    public final List<int[]> gatePlanes = new ArrayList<>();
    /** The columns the castle stands on (anything but structure void at ground level), row by row; null if unknown. */
    private final java.util.BitSet footprint;

    private Layout(JsonObject o) {
        name = o.get("name").getAsString();
        tier = o.get("tier").getAsInt();
        JsonArray size = o.getAsJsonArray("size");
        sizeX = size.get(0).getAsInt();
        sizeY = size.get(1).getAsInt();
        sizeZ = size.get(2).getAsInt();
        ground = o.get("ground").getAsInt();
        beds = o.has("beds") ? o.get("beds").getAsInt() : 0;
        lord = pos(o.get("lord"));
        bannerStand = pos(o.get("banner_stand"));
        warTable = pos(o.get("war_table"));
        gate = pos(o.get("gate"));
        rally = pos(o.get("rally"));
        JsonObject p = o.getAsJsonObject("posts");
        for (String k : p.keySet()) {
            Role role;
            try {
                role = Role.valueOf(k.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                List<BlockPos> list = new ArrayList<>();
                for (JsonElement el : p.getAsJsonArray(k)) list.add(pos(el));
                extras.put(k, list);
                continue;
            }
            List<BlockPos> list = new ArrayList<>();
            for (JsonElement el : p.getAsJsonArray(k)) list.add(pos(el));
            posts.put(role, list);
        }
        for (JsonElement el : o.getAsJsonArray("banners")) banners.add(pos(el));
        if (o.has("gate_planes")) {
            for (JsonElement el : o.getAsJsonArray("gate_planes")) {
                JsonArray a = el.getAsJsonArray();
                int[] box = new int[6];
                for (int i = 0; i < 6; i++) box[i] = a.get(i).getAsInt();
                gatePlanes.add(box);
            }
        }
        if (o.has("footprint")) {
            footprint = new java.util.BitSet(sizeX * sizeZ);
            JsonArray rows = o.getAsJsonArray("footprint");
            for (int z = 0; z < Math.min(sizeZ, rows.size()); z++) {
                String row = rows.get(z).getAsString();
                for (int x = 0; x < Math.min(sizeX, row.length()); x++) if (row.charAt(x) == '1') footprint.set(z * sizeX + x);
            }
        } else {
            footprint = null;
        }
    }

    /** Whether the castle stands on this column of the layout, so the land above its floor there is the castle's to clear. */
    public boolean inside(int x, int z) {
        if (footprint == null || x < 0 || z < 0 || x >= sizeX || z >= sizeZ) return false;
        return footprint.get(z * sizeX + x);
    }

    private static BlockPos pos(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static Layout parse(JsonObject o) {
        return new Layout(o);
    }
}
