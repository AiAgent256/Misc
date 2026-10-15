package net.aiagent256.stormhold.holding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One of the commoners' buildings, as written by tools/castlegen/fief.py. */
public final class FiefPiece {
    public final String name, kind, template;
    @Nullable public final String profession;
    public final int sizeX, sizeY, sizeZ, ground;
    public final List<BlockPos> beds = new ArrayList<>(), stations = new ArrayList<>();
    public final BlockPos door;
    @Nullable public final BlockPos animalSpot;
    public final List<String> animals = new ArrayList<>();
    public final boolean water;

    private FiefPiece(JsonObject o) {
        name = o.get("name").getAsString();
        kind = o.get("kind").getAsString();
        template = o.get("template").getAsString();
        profession = o.has("profession") && !o.get("profession").isJsonNull() ? o.get("profession").getAsString() : null;
        JsonArray s = o.getAsJsonArray("size");
        sizeX = s.get(0).getAsInt();
        sizeY = s.get(1).getAsInt();
        sizeZ = s.get(2).getAsInt();
        ground = o.get("ground").getAsInt();
        for (var e : o.getAsJsonArray("beds")) beds.add(pos(e.getAsJsonArray()));
        for (var e : o.getAsJsonArray("stations")) stations.add(pos(e.getAsJsonArray()));
        door = pos(o.getAsJsonArray("door"));
        animalSpot = o.has("animal_spot") && !o.get("animal_spot").isJsonNull() ? pos(o.getAsJsonArray("animal_spot")) : null;
        for (var e : o.getAsJsonArray("animals")) animals.add(e.getAsString());
        water = o.has("water") && o.get("water").getAsBoolean();
    }

    private static BlockPos pos(JsonArray a) {
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    public static FiefPiece parse(JsonObject o) {
        return new FiefPiece(o);
    }
}
