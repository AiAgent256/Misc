package net.aiagent256.stormhold.worldgen;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.holding.DataRegistries;
import net.aiagent256.stormhold.holding.Layout;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * One 32x32 column of a castle template. The template itself is only read when the piece is placed: a
 * city is sixty-four of these and millions of blocks, and /locate, chunk loading and structure bookkeeping
 * all build pieces without ever placing them. The box comes from the layout's known size instead.
 */
public class CastleTilePiece extends StructurePiece {
    public static final int TILE = 32;

    private final String layout;
    private final String theme;
    private final int tx, tz;
    private final BlockPos castleOrigin;
    /** Layout y of the first air block above the castle's ground; everything below is foundation. */
    private final int ground;
    private final BlockPos templatePosition;

    public CastleTilePiece(StructureTemplateManager manager, String layout, String theme, int tx, int tz, BlockPos origin, int ground) {
        super(ModStructures.CASTLE_TILE.get(), 0, box(layout, tx, tz, origin));
        this.layout = layout;
        this.theme = theme;
        this.tx = tx;
        this.tz = tz;
        this.castleOrigin = origin;
        this.ground = ground;
        this.templatePosition = origin.offset(tx * TILE, 0, tz * TILE);
    }

    public CastleTilePiece(StructureTemplateManager manager, CompoundTag tag) {
        super(ModStructures.CASTLE_TILE.get(), tag);
        this.layout = tag.getString("Layout");
        this.theme = tag.getString("Theme");
        this.castleOrigin = NbtUtils.readBlockPos(tag.getCompound("CastleOrigin"));
        this.ground = tag.getInt("Ground");
        if (tag.contains("Tx")) {
            this.tx = tag.getInt("Tx");
            this.tz = tag.getInt("Tz");
        } else {
            // a piece saved by the old template-backed class: its tile is where it put the template
            this.tx = Math.floorDiv(tag.getInt("TPX") - castleOrigin.getX(), TILE);
            this.tz = Math.floorDiv(tag.getInt("TPZ") - castleOrigin.getZ(), TILE);
        }
        this.templatePosition = castleOrigin.offset(tx * TILE, 0, tz * TILE);
    }

    /** The tile's box from the layout's size; no template is read for it. */
    private static BoundingBox box(String layout, int tx, int tz, BlockPos origin) {
        Layout l = DataRegistries.layout(layout);
        int w = TILE, h = 128, d = TILE;
        if (l != null) {
            w = Math.min(TILE, l.sizeX - tx * TILE);
            d = Math.min(TILE, l.sizeZ - tz * TILE);
            h = l.sizeY;
        }
        BlockPos p = origin.offset(tx * TILE, 0, tz * TILE);
        return new BoundingBox(p.getX(), p.getY(), p.getZ(), p.getX() + w - 1, p.getY() + h - 1, p.getZ() + d - 1);
    }

    /**
     * The terrain smoother (beard_box) treats the bottom of this box as ground level: it fills land up to it and
     * carves land above it, inside the box and tapering off for a dozen blocks around. Our templates carry
     * {@code ground} layers of foundation below the real ground floor, so the box the smoother sees starts at
     * the floor; otherwise the land around the castle is dug down to the foundation and the gates end up on a
     * plinth above a trench. Block placement clips to the chunk, not to this box, so the foundation still lands.
     */
    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox b = super.getBoundingBox();
        if (ground <= 0) return b;
        return new BoundingBox(b.minX(), Math.min(b.maxY(), b.minY() + ground), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    public static ResourceLocation tileId(String layout, int tx, int tz) {
        return Stormhold.id(layout + "/tile_" + tx + "_" + tz);
    }

    private static StructurePlaceSettings settings(String theme) {
        StructurePlaceSettings s = new StructurePlaceSettings().setRotation(Rotation.NONE).setMirror(Mirror.NONE);
        if (theme != null && !theme.isEmpty() && !theme.equals("north")) s.addProcessor(new ThemeProcessor(theme));
        return s;
    }

    public String layout() {
        return layout;
    }

    public String theme() {
        return theme;
    }

    public BlockPos castleOrigin() {
        return castleOrigin;
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
        tag.putString("Layout", layout);
        tag.putString("Theme", theme);
        tag.putInt("Tx", tx);
        tag.putInt("Tz", tz);
        tag.put("CastleOrigin", NbtUtils.writeBlockPos(castleOrigin));
        tag.putInt("Ground", ground);
    }

    /** Now the template is needed: read it (the manager caches it) and set it down, clipped to the chunk. */
    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        Optional<StructureTemplate> t = level.getLevel().getStructureManager().get(tileId(layout, tx, tz));
        if (t.isEmpty()) {
            Stormhold.LOGGER.debug("Stormhold: no template for {} tile {},{}", layout, tx, tz);
            return;
        }
        StructurePlaceSettings settings = settings(theme).setBoundingBox(box);
        t.get().placeInWorld(level, templatePosition, pivot, settings, random, 2);
    }
}
