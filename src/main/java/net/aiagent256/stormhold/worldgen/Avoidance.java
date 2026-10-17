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
            if (w.castleSize() > 0 && (yieldBelow == Integer.MAX_VALUE || w.castleSize() <= yieldBelow)) continue;
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
        return biomeAllows(ctx.chunkGenerator(), ctx.biomeSource(), ctx.randomState(), ctx.heightAccessor(), w, bx, bz);
    }

    private static boolean biomeAllows(net.minecraft.world.level.chunk.ChunkGenerator gen, net.minecraft.world.level.biome.BiomeSource src,
                                       net.minecraft.world.level.levelgen.RandomState rs, net.minecraft.world.level.LevelHeightAccessor h,
                                       Watch w, int bx, int bz) {
        // the biome a few blocks over sea level: surface biomes don't change with height, and finding the real
        // surface would mean computing a whole noise column for every candidate, which is what made /locate crawl
        int y = gen.getSeaLevel() + 4;
        Holder<Biome> b = src.getNoiseBiome(QuartPos.fromBlock(bx), QuartPos.fromBlock(y), QuartPos.fromBlock(bz), rs.sampler());
        for (HolderSet<Biome> hs : w.biomes()) if (hs.contains(b)) return true;
        return false;
    }

    /** How far a castle's site can wander from its start chunk's centre while it looks for level ground. */
    public static final int SITE_SHIFT = 32;

    /**
     * The other way round: would a structure of another mod, starting in {@code chunk}, land on a castle? Castles
     * are planned deterministically, so we can tell where one could stand before any chunk is generated. Rooms
     * for the other structure's own footprint come from {@code reach}.
     */
    public static boolean onCastleGround(RegistryAccess access, long seed, net.minecraft.world.level.chunk.ChunkGenerator gen,
                                         net.minecraft.world.level.biome.BiomeSource src, net.minecraft.world.level.levelgen.RandomState rs,
                                         net.minecraft.world.level.LevelHeightAccessor heights, ChunkPos chunk, int reach) {
        int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
        for (Watch w : watches(access)) {
            if (w.castleSize() <= 0) continue;
            int radius = w.castleSize() + SITE_SHIFT + reach;
            RandomSpreadStructurePlacement p = w.placement();
            int spacing = Math.max(1, p.spacing());
            int c0x = SectionPos.blockToSectionCoord(x - radius), c1x = SectionPos.blockToSectionCoord(x + radius);
            int c0z = SectionPos.blockToSectionCoord(z - radius), c1z = SectionPos.blockToSectionCoord(z + radius);
            for (int rx = Math.floorDiv(c0x, spacing); rx <= Math.floorDiv(c1x, spacing); rx++) {
                for (int rz = Math.floorDiv(c0z, spacing); rz <= Math.floorDiv(c1z, spacing); rz++) {
                    ChunkPos c = p.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    long dx = c.getMiddleBlockX() - x, dz = c.getMiddleBlockZ() - z;
                    if (dx * dx + dz * dz > (long) radius * radius) continue;
                    if (biomeAllows(gen, src, rs, heights, w, c.getMiddleBlockX(), c.getMiddleBlockZ())) return true;
                }
            }
        }
        return false;
    }

    private Avoidance() {
    }
}
