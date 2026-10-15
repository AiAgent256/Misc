package net.aiagent256.stormhold;

import com.mojang.logging.LogUtils;
import net.aiagent256.stormhold.net.Net;
import net.aiagent256.stormhold.registry.ModBlocks;
import net.aiagent256.stormhold.registry.ModEntities;
import net.aiagent256.stormhold.registry.ModItems;
import net.aiagent256.stormhold.registry.ModStructures;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Stormhold.MODID)
public class Stormhold {
    public static final String MODID = "stormhold";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static String buildId;

    /** The commit and time this jar was built from, written into the jar by Gradle. */
    public static String buildId() {
        if (buildId == null) {
            try (var in = Stormhold.class.getClassLoader().getResourceAsStream("stormhold-build.txt")) {
                buildId = in == null ? "unknown" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (java.io.IOException e) {
                buildId = "unknown";
            }
        }
        return buildId;
    }

    public Stormhold() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModItems.TABS.register(bus);
        ModEntities.ENTITIES.register(bus);
        net.aiagent256.stormhold.registry.ModMenus.MENUS.register(bus);
        ModStructures.STRUCTURE_TYPES.register(bus);
        ModStructures.PIECES.register(bus);
        ModStructures.PROCESSORS.register(bus);
        bus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(Net::register);
        LOGGER.info("Stormhold {} loaded. The banners are raised.", buildId());
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }
}
