package net.aiagent256.stormhold.registry;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.block.BannerStandBlock;
import net.aiagent256.stormhold.block.WarTableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Stormhold.MODID);

    public static final RegistryObject<Block> PORTCULLIS = BLOCKS.register("portcullis", () -> new net.aiagent256.stormhold.block.PortcullisBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(25.0F, 6.0F)
                    .requiresCorrectToolForDrops().sound(SoundType.CHAIN).noOcclusion()));

    public static final RegistryObject<Block> BANNER_STAND = BLOCKS.register("banner_stand", () -> new BannerStandBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(-1.0F, 3600000.0F)
                    .sound(SoundType.LODESTONE).noLootTable()));

    public static final RegistryObject<Block> WAR_TABLE = BLOCKS.register("war_table", () -> new WarTableBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD)));

    public static final RegistryObject<Block> MUSTER_POST = BLOCKS.register("muster_post", () -> new net.aiagent256.stormhold.block.MusterPostBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noLootTable()));

    public static final RegistryObject<Block> NOTICE_BOARD = BLOCKS.register("notice_board", () -> new net.aiagent256.stormhold.block.NoticeBoardBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(-1.0F, 3600000.0F).sound(SoundType.WOOD).noLootTable().noOcclusion()));

    private ModBlocks() {
    }
}
