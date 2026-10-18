package net.aiagent256.stormhold.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A castle left to the weather for a hundred years. Walls stand to ragged heights that rise and fall
 * along their length (whole runs down to the footings in places, towers broken off halfway), roofs are
 * mostly gone, the stone is mossed and cracked, the glass, doors, beds and lamps are long since carried
 * off, ivy climbs what's left, the tops are grown over, and grass and flowers have taken the yard.
 * Deterministic in the castle's position, so every tile of one ruin agrees and the Master Builder's
 * plans (the intact castle) show exactly what was lost.
 */
public class RuinProcessor extends StructureProcessor {
    public static final Codec<RuinProcessor> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("seed").forGetter(p -> p.seed),
            Codec.INT.fieldOf("ground").forGetter(p -> p.groundY),
            Codec.INT.fieldOf("height").forGetter(p -> p.height)
    ).apply(i, RuinProcessor::new));

    private final long seed;
    /** World y of the castle's first open floor; everything below is footings and stays. */
    private final int groundY;
    /** How tall the plan stands above its floor. */
    private final int height;

    public RuinProcessor(long seed, int groundY, int height) {
        this.seed = seed;
        this.groundY = groundY;
        this.height = Math.max(8, height);
    }

    // ------------------------------------------------------------ noise

    private double hash(int x, int z, long salt) {
        long h = seed ^ salt;
        h ^= x * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 27) * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h = Long.rotateLeft(h, 31) * 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise in [0,1] on a grid of {@code cell} blocks. */
    private double noise(int x, int z, int cell, long salt) {
        int gx = Math.floorDiv(x, cell), gz = Math.floorDiv(z, cell);
        double fx = (x - gx * cell) / (double) cell, fz = (z - gz * cell) / (double) cell;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(gx, gz, salt), b = hash(gx + 1, gz, salt), c = hash(gx, gz + 1, salt), d = hash(gx + 1, gz + 1, salt);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private double chance(BlockPos p, long salt) {
        long s = Mth.getSeed(p) ^ seed ^ salt;
        return ((s >>> 16) & 0xFFFF) / 65536.0;
    }

    /** How high the masonry still stands over this column, in blocks above the floor. */
    public int standing(int x, int z) {
        double broad = noise(x, z, 11, 0x51L), fine = noise(x, z, 4, 0x52L);
        double f = 0.62 * broad + 0.38 * fine;
        // a few long breaches right down to the footings
        if (noise(x, z, 17, 0x53L) < 0.16) f *= 0.15;
        return 1 + (int) Math.round(Math.pow(f, 1.25) * (height * 0.95));
    }

    // ------------------------------------------------------------ one block at a time

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                             StructureTemplate.StructureBlockInfo original,
                                                             StructureTemplate.StructureBlockInfo current,
                                                             StructurePlaceSettings settings) {
        BlockState s = current.state();
        BlockPos p = current.pos();
        if (s.isAir()) return current;
        int hy = p.getY() - groundY;
        if (s.getBlock() instanceof net.aiagent256.stormhold.block.WarTableBlock || isStormhold(s)) return current;
        // what was carried off or rotted away
        if (gone(s)) return null;
        if (hy < 0) return weather(current, s, p);
        int top = standing(p.getX(), p.getZ());
        if (hy > top) return null;
        // roofs fall in first
        if (hy >= 5 && (s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_STAIRS))
                && chance(p, 0x61L) < 0.75) return null;
        // the weather picks holes in what stands
        if (hy >= 2 && chance(p, 0x62L) < 0.05 + 0.10 * hy / height) return null;
        // the broken top grows over
        if (hy == top && hy >= 2 && s.isSolidRender(level, p) && chance(p, 0x63L) < 0.3)
            return new StructureTemplate.StructureBlockInfo(p, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), null);
        return weather(current, s, p);
    }

    private static boolean isStormhold(BlockState s) {
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        return id != null && id.getNamespace().equals("stormhold") && !id.getPath().contains("portcullis");
    }

    /** Things a ruin hasn't got. */
    private static boolean gone(BlockState s) {
        if (s.is(BlockTags.DOORS) || s.is(BlockTags.BEDS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.CANDLES)
                || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.WOOL) || s.is(Blocks.BELL)) return true;
        if (s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.CHAIN)
                || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SHROOMLIGHT) || s.is(Blocks.GLOWSTONE)) return true;
        var id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(s.getBlock());
        if (id == null) return false;
        String path = id.getPath();
        return path.contains("glass") || path.contains("portcullis") || path.equals("hay_block") || path.contains("bookshelf")
                || path.contains("flower_pot") || path.contains("potted_") || path.contains("trapdoor");
    }

    /** Moss and cracks: the stone a century outdoors. */
    private StructureTemplate.StructureBlockInfo weather(StructureTemplate.StructureBlockInfo current, BlockState s, BlockPos p) {
        double r = chance(p, 0x71L);
        double damp = noise(p.getX(), p.getZ(), 6, 0x72L);
        Block to = null;
        if (s.is(Blocks.STONE_BRICKS)) to = r < 0.25 + 0.3 * damp ? Blocks.MOSSY_STONE_BRICKS : r > 0.75 ? Blocks.CRACKED_STONE_BRICKS : null;
        else if (s.is(Blocks.COBBLESTONE)) to = r < 0.3 + 0.35 * damp ? Blocks.MOSSY_COBBLESTONE : null;
        else if (s.is(Blocks.STONE_BRICK_STAIRS)) to = r < 0.35 * (1 + damp) ? Blocks.MOSSY_STONE_BRICK_STAIRS : null;
        else if (s.is(Blocks.STONE_BRICK_SLAB)) to = r < 0.35 * (1 + damp) ? Blocks.MOSSY_STONE_BRICK_SLAB : null;
        else if (s.is(Blocks.STONE_BRICK_WALL)) to = r < 0.35 * (1 + damp) ? Blocks.MOSSY_STONE_BRICK_WALL : null;
        else if (s.is(Blocks.COBBLESTONE_STAIRS)) to = r < 0.35 * (1 + damp) ? Blocks.MOSSY_COBBLESTONE_STAIRS : null;
        else if (s.is(Blocks.COBBLESTONE_WALL)) to = r < 0.35 * (1 + damp) ? Blocks.MOSSY_COBBLESTONE_WALL : null;
        else if (s.is(Blocks.DEEPSLATE_BRICKS)) to = r < 0.3 ? Blocks.CRACKED_DEEPSLATE_BRICKS : null;
        else if (s.is(Blocks.POLISHED_BLACKSTONE_BRICKS)) to = r < 0.3 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS : null;
        else if (s.is(Blocks.NETHER_BRICKS)) to = r < 0.3 ? Blocks.CRACKED_NETHER_BRICKS : null;
        if (to == null) return current;
        return new StructureTemplate.StructureBlockInfo(p, ThemeProcessor.copy(s, to), current.nbt());
    }

    // ------------------------------------------------------------ the whole tile at once: ivy and grass

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset, BlockPos pos,
                                                                       List<StructureTemplate.StructureBlockInfo> originals,
                                                                       List<StructureTemplate.StructureBlockInfo> processed,
                                                                       StructurePlaceSettings settings) {
        Map<BlockPos, BlockState> at = new HashMap<>();
        for (var b : processed) at.put(b.pos(), b.state());
        List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed);
        var box = settings.getBoundingBox();
        for (var b : processed) {
            BlockState s = b.state();
            BlockPos p = b.pos();
            if (s.isAir() || !s.canOcclude()) continue;
            int hy = p.getY() - groundY;
            // grass, ferns and flowers on the yard and the floors open to the sky
            if (hy == -1 || (hy >= 0 && hy <= 1 && s.is(BlockTags.DIRT))) {
                BlockPos up = p.above();
                BlockState over = at.get(up);
                if ((over == null || over.isAir()) && (box == null || box.isInside(up))) {
                    double r = chance(up, 0x81L), green = noise(p.getX(), p.getZ(), 5, 0x82L);
                    BlockState plant = null;
                    if (r < 0.06 * green) plant = r < 0.03 * green ? Blocks.DANDELION.defaultBlockState() : Blocks.OXEYE_DAISY.defaultBlockState();
                    else if (r < 0.35 * green) plant = chance(up, 0x83L) < 0.7 ? Blocks.GRASS.defaultBlockState() : Blocks.FERN.defaultBlockState();
                    else if (r < 0.45 * green) plant = Blocks.MOSS_CARPET.defaultBlockState();
                    if (plant != null) {
                        out.add(new StructureTemplate.StructureBlockInfo(up, plant, null));
                        at.put(up, plant);
                    }
                }
            }
            // ivy up the standing walls, in patches
            if (hy >= 1 && noise(p.getX(), p.getZ(), 7, 0x91L) > 0.55) {
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos q = p.relative(d);
                    BlockState there = at.get(q);
                    if (there != null && !there.isAir()) continue;
                    if (box != null && !box.isInside(q)) continue;
                    if (chance(q, 0x92L + d.ordinal()) > 0.5) continue;
                    // the vine hangs on the face of the wall: its side towards the wall is set
                    BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
                    out.add(new StructureTemplate.StructureBlockInfo(q, vine, null));
                    at.put(q, vine);
                }
            }
        }
        return out;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructures.RUIN.get();
    }
}
