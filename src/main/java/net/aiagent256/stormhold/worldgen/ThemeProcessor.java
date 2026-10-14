package net.aiagent256.stormhold.worldgen;

import com.mojang.serialization.Codec;
import net.aiagent256.stormhold.holding.DataRegistries;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/** Swaps the northern palette for a regional one, keeping block properties. */
public class ThemeProcessor extends StructureProcessor {
    public static final Codec<ThemeProcessor> CODEC =
            Codec.STRING.fieldOf("theme").xmap(ThemeProcessor::new, p -> p.theme).codec();

    private final String theme;

    public ThemeProcessor(String theme) {
        this.theme = theme;
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pos,
                                                             StructureTemplate.StructureBlockInfo original,
                                                             StructureTemplate.StructureBlockInfo current,
                                                             StructurePlaceSettings settings) {
        Map<Block, DataRegistries.Weighted> map = DataRegistries.theme(theme);
        if (map.isEmpty()) return current;
        BlockState state = current.state();
        DataRegistries.Weighted w = map.get(state.getBlock());
        if (w == null) return current;
        Block to = w.pick(Mth.getSeed(current.pos()));
        return new StructureTemplate.StructureBlockInfo(current.pos(), copy(state, to), current.nbt());
    }

    public static BlockState copy(BlockState from, Block to) {
        BlockState s = to.defaultBlockState();
        for (Property<?> p : from.getProperties()) {
            if (s.hasProperty(p)) s = copyProp(from, s, p);
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState copyProp(BlockState from, BlockState to, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructures.THEME.get();
    }
}
