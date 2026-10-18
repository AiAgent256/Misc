package net.aiagent256.stormhold.registry;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.worldgen.CastleStructure;
import net.aiagent256.stormhold.worldgen.CastleTilePiece;
import net.aiagent256.stormhold.worldgen.ThemeProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, Stormhold.MODID);
    public static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, Stormhold.MODID);
    public static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, Stormhold.MODID);

    public static final RegistryObject<StructureType<CastleStructure>> CASTLE =
            STRUCTURE_TYPES.register("castle", () -> () -> CastleStructure.CODEC);
    public static final RegistryObject<StructurePieceType> CASTLE_TILE =
            PIECES.register("castle_tile", () -> (StructurePieceType.StructureTemplateType) CastleTilePiece::new);
    public static final RegistryObject<StructureProcessorType<ThemeProcessor>> THEME =
            PROCESSORS.register("theme", () -> () -> ThemeProcessor.CODEC);
    public static final RegistryObject<StructureProcessorType<net.aiagent256.stormhold.worldgen.RuinProcessor>> RUIN =
            PROCESSORS.register("ruin", () -> () -> net.aiagent256.stormhold.worldgen.RuinProcessor.CODEC);

    private ModStructures() {
    }
}
