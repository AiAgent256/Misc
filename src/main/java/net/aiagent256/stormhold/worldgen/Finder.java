package net.aiagent256.stormhold.worldgen;

import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Where the nearest castle of a kind will stand, worked out from the placement maths and our own siting
 * instead of /locate, which generates structure starts chunk by chunk, every mod's sets included, and
 * crawls when the thing it wants is far away. This never touches a chunk.
 */
public final class Finder {
    /** The structure sets by the names a player would use. */
    public static final Map<String, String> KINDS = Map.of(
            "city", "stormhold:great_cities",
            "castle", "stormhold:great_castles",
            "holdfast", "stormhold:holdfasts",
            "watchtower", "stormhold:watchtowers",
            "town", "stormhold:towns",
            "nether", "stormhold:nether_holdfasts",
            "ruin", "stormhold:ruins",
            "temple", "stormhold:temple_ruins");

    public record Found(String what, int x, int z, boolean known) {
        public double dist(BlockPos from) {
            double dx = x - from.getX(), dz = z - from.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    /** The nearest holding already in the world of the kind, if any. */
    @Nullable
    public static Found known(ServerLevel level, BlockPos from, String kind) {
        HoldingManager mgr = HoldingManager.get(level);
        Found best = null;
        for (Holding h : mgr.holdings.values()) {
            if (!matches(h, kind)) continue;
            Found f = new Found(h.name, h.center().getX(), h.center().getZ(), true);
            if (best == null || f.dist(from) < best.dist(from)) best = f;
        }
        return best;
    }

    private static boolean matches(Holding h, String kind) {
        return switch (kind) {
            case "city" -> h.tier >= 4;
            case "castle" -> h.tier == 3 && !h.isTown() && !h.ruined;
            case "holdfast" -> h.tier == 2 && !h.isNether() && !h.ruined;
            case "watchtower" -> h.tier == 1 && !h.isTown() && !h.ruined;
            case "town" -> h.isTown();
            case "nether" -> h.isNether();
            case "ruin" -> h.ruined;
            default -> false;
        };
    }

    /**
     * Search outward from {@code from}, cell by cell of the set's grid, for the first place a castle of the set
     * would generate. Stops at {@code maxRings} cells out or after {@code budgetMs}; null if nothing in reach.
     */
    @Nullable
    public static Found planned(ServerLevel level, BlockPos from, String kind, int maxRings, long budgetMs) {
        String setId = KINDS.get(kind);
        if (setId == null) return null;
        var sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        StructureSet set = sets.get(new ResourceLocation(setId));
        if (set == null || !(set.placement() instanceof RandomSpreadStructurePlacement rs)) return null;
        var gen = level.getChunkSource().getGenerator();
        var src = gen.getBiomeSource();
        var rstate = level.getChunkSource().randomState();
        long seed = level.getSeed();
        int spacing = Math.max(1, rs.spacing());
        int cx = Math.floorDiv(from.getX() >> 4, spacing), cz = Math.floorDiv(from.getZ() >> 4, spacing);
        long end = System.currentTimeMillis() + budgetMs;
        int biomeY = gen.getSeaLevel() + 4;
        for (int ring = 0; ring <= maxRings; ring++) {
            for (int i = -ring; i <= ring; i++) {
                for (int j = -ring; j <= ring; j++) {
                    if (Math.abs(i) != ring && Math.abs(j) != ring) continue;
                    if (System.currentTimeMillis() > end) return null;
                    ChunkPos cp = rs.getPotentialStructureChunk(seed, (cx + i) * spacing, (cz + j) * spacing);
                    int bx = cp.getMiddleBlockX(), bz = cp.getMiddleBlockZ();
                    Holder<Biome> biome = src.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(biomeY), QuartPos.fromBlock(bz), rstate.sampler());
                    // the set's structures in this biome, by weight order: the first that sites itself wins, as at worldgen
                    for (StructureSet.StructureSelectionEntry e : set.structures()) {
                        Structure s = e.structure().value();
                        if (!s.biomes().contains(biome)) continue;
                        var ctx = new Structure.GenerationContext(level.registryAccess(), gen, src, rstate, level.getStructureManager(),
                                seed, cp, level, b -> true);
                        var point = s.findValidGenerationPoint(ctx);
                        if (point.isPresent()) {
                            BlockPos p = point.get().position();
                            String what = s instanceof CastleStructure c ? kind + " (" + c.theme() + ")" : kind;
                            return new Found(what, p.getX(), p.getZ(), false);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Every place a castle, town or city of ours would generate within {@code radius} of {@code centre}, sited
     * the way worldgen sites them (so where it would really stand). Stops when the time runs out.
     */
    public static List<Found> within(ServerLevel level, BlockPos centre, int radius, long budgetMs) {
        List<Found> out = new java.util.ArrayList<>();
        var sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        var gen = level.getChunkSource().getGenerator();
        var src = gen.getBiomeSource();
        var rstate = level.getChunkSource().randomState();
        long seed = level.getSeed();
        long end = System.currentTimeMillis() + budgetMs;
        int biomeY = gen.getSeaLevel() + 4;
        for (var k : KINDS.entrySet()) {
            StructureSet set = sets.get(new ResourceLocation(k.getValue()));
            if (set == null || !(set.placement() instanceof RandomSpreadStructurePlacement rs)) continue;
            int spacing = Math.max(1, rs.spacing());
            int c0x = Math.floorDiv((centre.getX() - radius) >> 4, spacing), c1x = Math.floorDiv((centre.getX() + radius) >> 4, spacing);
            int c0z = Math.floorDiv((centre.getZ() - radius) >> 4, spacing), c1z = Math.floorDiv((centre.getZ() + radius) >> 4, spacing);
            for (int i = c0x; i <= c1x; i++) {
                for (int j = c0z; j <= c1z; j++) {
                    if (System.currentTimeMillis() > end) return out;
                    ChunkPos cp = rs.getPotentialStructureChunk(seed, i * spacing, j * spacing);
                    int bx = cp.getMiddleBlockX(), bz = cp.getMiddleBlockZ();
                    long dx = bx - centre.getX(), dz = bz - centre.getZ();
                    if (dx * dx + dz * dz > (long) (radius + 64) * (radius + 64)) continue;
                    Holder<Biome> biome = src.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(biomeY), QuartPos.fromBlock(bz), rstate.sampler());
                    for (StructureSet.StructureSelectionEntry e : set.structures()) {
                        Structure st = e.structure().value();
                        if (!st.biomes().contains(biome)) continue;
                        var ctx = new Structure.GenerationContext(level.registryAccess(), gen, src, rstate, level.getStructureManager(),
                                seed, cp, level, b -> true);
                        var point = st.findValidGenerationPoint(ctx);
                        if (point.isPresent()) {
                            BlockPos p = point.get().position();
                            out.add(new Found(st instanceof CastleStructure c ? k.getKey() + " (" + c.theme() + ")" : k.getKey(), p.getX(), p.getZ(), false));
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private Finder() {
    }
}
