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
    public final BlockPos lord, bannerStand, warTable, gate, rally;
    public final EnumMap<Role, List<BlockPos>> posts = new EnumMap<>(Role.class);
    public final List<BlockPos> banners = new ArrayList<>();

    private Layout(JsonObject o) {
        name = o.get("name").getAsString();
        tier = o.get("tier").getAsInt();
        JsonArray size = o.getAsJsonArray("size");
        sizeX = size.get(0).getAsInt();
        sizeY = size.get(1).getAsInt();
        sizeZ = size.get(2).getAsInt();
        ground = o.get("ground").getAsInt();
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
                continue;
            }
            List<BlockPos> list = new ArrayList<>();
            for (JsonElement el : p.getAsJsonArray(k)) list.add(pos(el));
            posts.put(role, list);
        }
        for (JsonElement el : o.getAsJsonArray("banners")) banners.add(pos(el));
    }

    private static BlockPos pos(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static Layout parse(JsonObject o) {
        return new Layout(o);
    }
}
