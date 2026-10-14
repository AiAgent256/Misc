package net.aiagent256.stormhold.holding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.aiagent256.stormhold.Stormhold;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads castle layouts and regional themes from data packs. */
public final class DataRegistries {
    private static final Gson GSON = new GsonBuilder().create();
    private static volatile Map<String, Layout> layouts = Map.of();
    private static volatile Map<String, Map<Block, Weighted>> themes = Map.of();

    public record Weighted(Block[] blocks, int[] cumulative, int total) {
        public Block pick(long hash) {
            if (blocks.length == 1) return blocks[0];
            int h = (int) Math.floorMod(hash, (long) total);
            for (int i = 0; i < blocks.length; i++) if (h < cumulative[i]) return blocks[i];
            return blocks[blocks.length - 1];
        }
    }

    public static Layout layout(String name) {
        return layouts.get(name);
    }

    public static Map<Block, Weighted> theme(String name) {
        return themes.getOrDefault(name, Map.of());
    }

    public static class LayoutListener extends SimpleJsonResourceReloadListener {
        public LayoutListener() {
            super(GSON, "castle_layouts");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller p) {
            Map<String, Layout> out = new HashMap<>();
            map.forEach((id, json) -> {
                try {
                    Layout l = Layout.parse(json.getAsJsonObject());
                    out.put(l.name, l);
                } catch (Exception e) {
                    Stormhold.LOGGER.error("Bad castle layout {}", id, e);
                }
            });
            layouts = Map.copyOf(out);
            Stormhold.LOGGER.info("Stormhold: {} castle layouts", out.size());
        }
    }

    public static class ThemeListener extends SimpleJsonResourceReloadListener {
        public ThemeListener() {
            super(GSON, "castle_themes");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> map, ResourceManager rm, ProfilerFiller p) {
            Map<String, Map<Block, Weighted>> out = new HashMap<>();
            map.forEach((id, json) -> {
                Map<Block, Weighted> m = new HashMap<>();
                JsonObject o = json.getAsJsonObject().getAsJsonObject("map");
                for (String src : o.keySet()) {
                    Block from = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(src));
                    if (from == null || from == Blocks.AIR) continue;
                    JsonArray arr = o.getAsJsonArray(src);
                    List<Block> bl = new ArrayList<>();
                    List<Integer> ws = new ArrayList<>();
                    for (JsonElement e : arr) {
                        JsonArray pair = e.getAsJsonArray();
                        Block to = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(pair.get(0).getAsString()));
                        if (to == null || to == Blocks.AIR) continue;
                        bl.add(to);
                        ws.add(pair.get(1).getAsInt());
                    }
                    if (bl.isEmpty()) continue;
                    int[] cum = new int[ws.size()];
                    int t = 0;
                    for (int i = 0; i < ws.size(); i++) {
                        t += ws.get(i);
                        cum[i] = t;
                    }
                    m.put(from, new Weighted(bl.toArray(new Block[0]), cum, t));
                }
                out.put(id.getPath(), Map.copyOf(m));
            });
            themes = Map.copyOf(out);
            Stormhold.LOGGER.info("Stormhold: {} castle themes", out.size());
        }
    }

    private DataRegistries() {
    }
}
