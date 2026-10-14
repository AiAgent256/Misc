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

    public CastleTilePiece(StructureTemplateManager manager, String layout, String theme, int tx, int tz, BlockPos origin) {
        super(ModStructures.CASTLE_TILE.get(), 0, manager, tileId(layout, tx, tz), tileId(layout, tx, tz).toString(),
                settings(theme), origin.offset(tx * 32, 0, tz * 32));
        this.layout = layout;
        this.theme = theme;
        this.castleOrigin = origin;
    }

    public CastleTilePiece(StructureTemplateManager manager, CompoundTag tag) {
        super(ModStructures.CASTLE_TILE.get(), tag, manager, rl -> settings(tag.getString("Theme")));
        this.layout = tag.getString("Layout");
        this.theme = tag.getString("Theme");
        this.castleOrigin = NbtUtils.readBlockPos(tag.getCompound("CastleOrigin"));
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
    }

    @Override
    protected void handleDataMarker(String name, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box) {
    }
}
