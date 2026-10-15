package net.aiagent256.stormhold.worldgen;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** One 32x32 column of a castle template. */
public class CastleTilePiece extends TemplateStructurePiece {
    private final String layout;
    private final String theme;
    private final BlockPos castleOrigin;
    /** Layout y of the first air block above the castle's ground; everything below is foundation. */
    private final int ground;

    public CastleTilePiece(StructureTemplateManager manager, String layout, String theme, int tx, int tz, BlockPos origin, int ground) {
        super(ModStructures.CASTLE_TILE.get(), 0, manager, tileId(layout, tx, tz), tileId(layout, tx, tz).toString(),
                settings(theme), origin.offset(tx * 32, 0, tz * 32));
        this.layout = layout;
        this.theme = theme;
        this.castleOrigin = origin;
        this.ground = ground;
    }

    public CastleTilePiece(StructureTemplateManager manager, CompoundTag tag) {
        super(ModStructures.CASTLE_TILE.get(), tag, manager, rl -> settings(tag.getString("Theme")));
        this.layout = tag.getString("Layout");
        this.theme = tag.getString("Theme");
        this.castleOrigin = NbtUtils.readBlockPos(tag.getCompound("CastleOrigin"));
        this.ground = tag.getInt("Ground");
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
        super.addAdditionalSaveData(ctx, tag);
        tag.putString("Layout", layout);
        tag.putString("Theme", theme);
        tag.put("CastleOrigin", NbtUtils.writeBlockPos(castleOrigin));
        tag.putInt("Ground", ground);
    }

    @Override
    protected void handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box) {
    }
}
