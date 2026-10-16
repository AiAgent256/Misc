package net.aiagent256.stormhold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A Stormhold castle. Picks a layout, then sites it the way a lord would: among several nearby
 * candidates it prefers high ground with a flat enough top and water in view, and never builds in a lake.
 */
public class CastleStructure extends Structure {
    public record Variant(String name, int sizeX, int sizeZ, int ground, int weight) {
        public static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Variant::name),
                Codec.INT.fieldOf("size_x").forGetter(Variant::sizeX),
                Codec.INT.fieldOf("size_z").forGetter(Variant::sizeZ),
                Codec.INT.fieldOf("ground").forGetter(Variant::ground),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Variant::weight)
        ).apply(i, Variant::new));
    }

    public static final Codec<CastleStructure> CODEC = RecordCodecBuilder.create(i -> i.group(
            settingsCodec(i),
            Variant.CODEC.listOf().fieldOf("layouts").forGetter(s -> s.variants),
            Codec.STRING.optionalFieldOf("theme", "north").forGetter(s -> s.theme),
            Codec.INT.optionalFieldOf("max_slope", 14).forGetter(s -> s.maxSlope),
            Codec.BOOL.optionalFieldOf("nether", false).forGetter(s -> s.nether)
    ).apply(i, CastleStructure::new));

    /** Game tests run in a flat world where terrain scoring is meaningless. */
    public static volatile boolean ignoreTerrain;
    public static volatile String lastRejection = "";

    private final List<Variant> variants;
    private final String theme;
    private final int maxSlope;
    /** Sited on the floor of the Nether (under its roof, above the lava) instead of on the world surface. */
    private final boolean nether;

    public CastleStructure(StructureSettings settings, List<Variant> variants, String theme, int maxSlope) {
        this(settings, variants, theme, maxSlope, false);
    }

    public CastleStructure(StructureSettings settings, List<Variant> variants, String theme, int maxSlope, boolean nether) {
        super(settings);
        this.variants = variants;
        this.theme = theme;
        this.maxSlope = maxSlope;
        this.nether = nether;
    }

    public boolean nether() {
        return nether;
    }

    public String theme() {
        return theme;
    }

    /** Half the longest side of the biggest layout this structure can pick. */
    public int maxHalfSize() {
        int m = 0;
        for (Variant v : variants) m = Math.max(m, Math.max(v.sizeX(), v.sizeZ()) / 2);
        return m;
    }

    private record Site(int cx, int cz, int y, double score) {
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        if (variants.isEmpty()) return Optional.empty();
        int total = 0;
        for (Variant v : variants) total += Math.max(1, v.weight());
        int roll = ctx.random().nextInt(total);
        Variant chosen = variants.get(0);
        for (Variant v : variants) {
            roll -= Math.max(1, v.weight());
            if (roll < 0) {
                chosen = v;
                break;
            }
        }
        ChunkPos cp = ctx.chunkPos();
        int own = Math.max(chosen.sizeX(), chosen.sizeZ()) / 2;
        List<Avoidance.Threat> threats = ignoreTerrain ? List.of()
                : Avoidance.near(ctx, cp.getMiddleBlockX(), cp.getMiddleBlockZ(), 46 + own + 110, maxHalfSize());
        Site best = null;
        // chunks only reference structure starts within 8 chunks, so a 256-block layout can shift at most one chunk
        boolean huge = Math.max(chosen.sizeX(), chosen.sizeZ()) > 200;
        int shift = huge ? 0 : 32;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (huge && (i != 0 || j != 0)) continue;
                int sx = cp.getMiddleBlockX() + i * shift, sz = cp.getMiddleBlockZ() + j * shift;
                Avoidance.Threat t = Avoidance.crowds(threats, sx, sz, own);
                if (t != null) {
                    lastRejection = "too close to " + t.name() + " at " + t.x() + ", " + t.z();
                    continue;
                }
                Site s = evaluate(ctx, sx, sz, chosen);
                if (s != null && (best == null || s.score > best.score)) best = s;
            }
        }
        if (best == null) return Optional.empty();
        final Variant v = chosen;
        // best.y is the top solid block; the layout's ground floor (first air, y = ground) goes one above it
        final BlockPos origin = new BlockPos(best.cx - v.sizeX() / 2, best.y + 1 - v.ground(), best.cz - v.sizeZ() / 2);
        BlockPos center = new BlockPos(best.cx, best.y, best.cz);
        return Optional.of(new GenerationStub(center, b -> addPieces(b, ctx.structureTemplateManager(), v, origin)));
    }

    private Site evaluate(GenerationContext ctx, int cx, int cz, Variant v) {
        if (nether) return evaluateNether(ctx, cx, cz, v);
        if (ignoreTerrain) {
            return new Site(cx, cz, height(ctx, cx, cz, Heightmap.Types.WORLD_SURFACE_WG), 0);
        }
        int hx = v.sizeX() / 2 - 4, hz = v.sizeZ() / 2 - 4;
        int[] hs = new int[9];
        int k = 0;
        int wet = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = cx + a * hx, z = cz + b * hz;
                int surface = height(ctx, x, z, Heightmap.Types.WORLD_SURFACE_WG);
                int floor = height(ctx, x, z, Heightmap.Types.OCEAN_FLOOR_WG);
                if (surface - floor > 1) wet++;
                hs[k++] = surface;
            }
        }
        if (wet > 1) {
            lastRejection = "water x" + wet;
            return null;
        }
        int[] sorted = hs.clone();
        Arrays.sort(sorted);
        int slope = sorted[8] - sorted[0];
        if (slope > maxSlope) {
            lastRejection = "slope " + slope;
            return null;
        }
        int median = sorted[4];
        int sea = ctx.chunkGenerator().getSeaLevel();
        if (median <= sea + 1) {
            lastRejection = "median " + median + " vs sea " + sea;
            return null;
        }
        // prominence over the surrounding land and water in view
        double ring = 0;
        int ringWater = 0;
        int rx = (int) (v.sizeX() * 1.4), rz = (int) (v.sizeZ() * 1.4);
        for (int t = 0; t < 8; t++) {
            double ang = t * Math.PI / 4;
            int x = cx + (int) (Math.cos(ang) * rx), z = cz + (int) (Math.sin(ang) * rz);
            int surface = height(ctx, x, z, Heightmap.Types.WORLD_SURFACE_WG);
            int floor = height(ctx, x, z, Heightmap.Types.OCEAN_FLOOR_WG);
            if (surface - floor > 1) ringWater++;
            ring += surface;
        }
        ring /= 8.0;
        double prominence = median - ring;
        double score = Math.max(-12, Math.min(30, prominence)) * 3.0 - slope * 1.5
                + (ringWater > 0 && ringWater < 6 ? 12 : 0) - ringWater * 1.5;
        return new Site(cx, cz, median, score);
    }

    /** Headroom a Nether castle needs over its floor: the holdfast plans are about forty tall. */
    private static final int NETHER_HEADROOM = 44, NETHER_LOW = 32, NETHER_HIGH = 100;

    /**
     * The Nether has no surface: the floor is the lowest ledge above the lava sea with enough open air over it
     * for the walls and towers. -1 where there is none (lava, or a cave too low for the castle).
     */
    private static int netherFloor(GenerationContext ctx, int x, int z) {
        NoiseColumn col = ctx.chunkGenerator().getBaseColumn(x, z, ctx.heightAccessor(), ctx.randomState());
        for (int y = NETHER_LOW; y <= NETHER_HIGH; y++) {
            BlockState below = col.getBlock(y - 1), here = col.getBlock(y);
            if (!here.isAir() || below.isAir() || !below.getFluidState().isEmpty()) continue;
            int clear = 0;
            while (clear < NETHER_HEADROOM && y + clear < 126 && col.getBlock(y + clear).isAir()) clear++;
            if (clear >= NETHER_HEADROOM) return y - 1;
            y += clear;
        }
        return -1;
    }

    private Site evaluateNether(GenerationContext ctx, int cx, int cz, Variant v) {
        int hx = Math.max(4, v.sizeX() / 2 - 4), hz = Math.max(4, v.sizeZ() / 2 - 4);
        int[] hs = new int[9];
        int k = 0;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int y = netherFloor(ctx, cx + a * hx, cz + b * hz);
                if (y < 0) {
                    lastRejection = "no floor under the roof at " + (cx + a * hx) + ", " + (cz + b * hz);
                    return null;
                }
                hs[k++] = y;
            }
        }
        int[] sorted = hs.clone();
        Arrays.sort(sorted);
        int slope = sorted[8] - sorted[0];
        if (slope > maxSlope) {
            lastRejection = "nether slope " + slope;
            return null;
        }
        return new Site(cx, cz, sorted[4], -slope);
    }

    private static int height(GenerationContext ctx, int x, int z, Heightmap.Types type) {
        return ctx.chunkGenerator().getFirstOccupiedHeight(x, z, type, ctx.heightAccessor(), ctx.randomState());
    }

    private void addPieces(StructurePiecesBuilder builder, StructureTemplateManager mgr, Variant v, BlockPos origin) {
        // every tile of the grid is exported, so nothing is read here: reading a city's templates to find out
        // whether they exist is what made /locate stall the server
        int nx = (v.sizeX() + 31) / 32, nz = (v.sizeZ() + 31) / 32;
        for (int tx = 0; tx < nx; tx++) {
            for (int tz = 0; tz < nz; tz++) {
                builder.addPiece(new CastleTilePiece(mgr, v.name(), theme, tx, tz, origin, v.ground()));
            }
        }
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.CASTLE.get();
    }
}
