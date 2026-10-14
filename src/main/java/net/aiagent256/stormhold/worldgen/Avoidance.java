package net.aiagent256.stormhold.worldgen;

import net.aiagent256.stormhold.Stormhold;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps castles out of villages, towns and other castles. Structure sets don't know about each other
 * beyond a single exclusion zone, so before siting a castle we work out where any structure in
 * {@code #stormhold:castle_avoid} could start nearby (same spread maths and biome check vanilla uses)
 * and steer clear of it. Smaller castles yield to bigger ones; equal tiers can't meet anyway.
 */
public final class Avoidance {
    public static final TagKey<Structure> AVOID = TagKey.create(Registries.STRUCTURE, Stormhold.id("castle_avoid"));
    /** How far a village's buildings reach from its start chunk. */
    public static final int VILLAGE_REACH = 72;

    /** One structure set worth watching: its placement, the biomes its avoided structures need, and how far they reach. */
    public record Watch(String name, RandomSpreadStructurePlacement placement, List<HolderSet<Biome>> biomes, int reach,
                        int castleSize) {
    }

    /** A place something could start, and how much room it needs. */
    public record Threat(String name, int x, int z, int reach, int castleSize) {
    }

    private static volatile RegistryAccess cachedFor;
    private static volatile List<Watch> cached = List.of();

    public static List<Watch> watches(RegistryAccess access) {
        if (cachedFor == access) return cached;
        List<Watch> out = new ArrayList<>();
        var sets = access.registryOrThrow(Registries.STRUCTURE_SET);
        for (var e : sets.entrySet()) {
            StructureSet set = e.getValue();
            if (!(set.placement() instanceof RandomSpreadStructurePlacement rs)) continue;
            List<HolderSet<Biome>> biomes = new ArrayList<>();
            int reach = 0, castle = 0;
            for (StructureSet.StructureSelectionEntry s : set.structures()) {
                Holder<Structure> h = s.structure();
                if (!h.is(AVOID)) continue;
                biomes.add(h.value().biomes());
                if (h.value() instanceof CastleStructure c) {
                    reach = Math.max(reach, c.maxHalfSize() + 24);
                    castle = Math.max(castle, c.maxHalfSize());
                } else {
                    reach = Math.max(reach, VILLAGE_REACH);
                }
            }
            if (!biomes.isEmpty()) out.add(new Watch(e.getKey().location().toString(), rs, biomes, reach, castle));
        }
        // An empty list means tags aren't bound yet; don't cache that.
        if (!out.isEmpty()) {
            cached = out;
            cachedFor = access;
        }
        return out;
    }

    /**
     * Everything that could start within {@code radius} blocks of (x, z) that a castle should keep clear of.
     * Castle sets no bigger than {@code yieldBelow} (the asking castle's own biggest layout) are ignored, so
     * small castles make way for big ones and never the other way round. The asker's own chunk is skipped.
     */
    public static List<Threat> near(Structure.GenerationContext ctx, int x, int z, int radius, int yieldBelow) {
        List<Threat> out = new ArrayList<>();
        long seed = ctx.seed();
        ChunkPos self = ctx.chunkPos();
        for (Watch w : watches(ctx.registryAccess())) {
            if (w.castleSize() > 0 && w.castleSize() <= yieldBelow) continue;
            RandomSpreadStructurePlacement rs = w.placement();
            int spacing = Math.max(1, rs.spacing());
            int c0x = SectionPos.blockToSectionCoord(x - radius), c1x = SectionPos.blockToSectionCoord(x + radius);
            int c0z = SectionPos.blockToSectionCoord(z - radius), c1z = SectionPos.blockToSectionCoord(z + radius);
            for (int rx = Math.floorDiv(c0x, spacing); rx <= Math.floorDiv(c1x, spacing); rx++) {
                for (int rz = Math.floorDiv(c0z, spacing); rz <= Math.floorDiv(c1z, spacing); rz++) {
                    ChunkPos c = rs.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (c.equals(self)) continue;
                    int bx = c.getMiddleBlockX(), bz = c.getMiddleBlockZ();
                    long dx = bx - x, dz = bz - z;
                    if (dx * dx + dz * dz > (long) radius * radius) continue;
                    if (biomeAllows(ctx, w, bx, bz)) out.add(new Threat(w.name(), bx, bz, w.reach(), w.castleSize()));
                }
            }
        }
        return out;
    }

    /** The first threat a castle of half-size {@code ownSize} centred at (x, z) would crowd, or null. */
    @org.jetbrains.annotations.Nullable
    public static Threat crowds(List<Threat> threats, int x, int z, int ownSize) {
        for (Threat t : threats) {
            long dx = t.x() - x, dz = t.z() - z;
            long need = ownSize + t.reach();
            if (dx * dx + dz * dz < need * need) return t;
        }
        return null;
    }

    private static boolean biomeAllows(Structure.GenerationContext ctx, Watch w, int bx, int bz) {
        int y = ctx.chunkGenerator().getFirstOccupiedHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(),
                ctx.randomState());
        Holder<Biome> b = ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(y), QuartPos.fromBlock(bz),
                ctx.randomState().sampler());
        for (HolderSet<Biome> hs : w.biomes()) if (hs.contains(b)) return true;
        return false;
    }

    private Avoidance() {
    }
}
