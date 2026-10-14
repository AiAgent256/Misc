package net.aiagent256.stormhold;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Stormhold.MODID)
public class Stormhold {
    public static final String MODID = "stormhold";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Stormhold() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Stormhold loaded. The banners are raised.");
    }
}
