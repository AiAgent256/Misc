package net.aiagent256.stormhold.mixin;

import net.aiagent256.stormhold.Config;
import net.aiagent256.stormhold.worldgen.Avoidance;
import net.aiagent256.stormhold.worldgen.CastleStructure;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/**
 * Other mods' structure sets don't know about castles, so a village, a tower or a dungeon's entrance can be
 * planted right on top of one. Castles are planned deterministically, so before any surface structure
 * starts we ask whether a castle could stand there and, if so, the other structure gives way.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
    /** Jigsaw structures (villages, towns, outposts) sprawl; one-piece structures mostly don't. */
    private static final int JIGSAW_REACH = 80, PLAIN_REACH = 40;

    @Inject(method = "generate", at = @At("HEAD"), cancellable = true)
    private void stormhold$keepOffCastles(RegistryAccess access, ChunkGenerator gen, BiomeSource biomes, RandomState random,
                                          StructureTemplateManager templates, long seed, ChunkPos chunk, int references,
                                          LevelHeightAccessor heights, Predicate<Holder<Biome>> biomeCheck,
                                          CallbackInfoReturnable<StructureStart> cir) {
        Structure self = (Structure) (Object) this;
        if (self instanceof CastleStructure || self.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) return;
        if (!Config.CASTLES_CLAIM_GROUND.get()) return;
        int reach = self instanceof JigsawStructure ? JIGSAW_REACH : PLAIN_REACH;
        if (Avoidance.onCastleGround(access, seed, gen, biomes, random, heights, chunk, reach)) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }
}
