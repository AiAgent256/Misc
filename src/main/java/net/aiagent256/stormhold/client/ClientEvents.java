package net.aiagent256.stormhold.client;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.registry.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Stormhold.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.HOUSE_LORD.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.HOUSE_CAPTAIN.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.HOUSE_KNIGHT.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.HOUSE_ARCHER.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.HOUSE_CROSSBOWMAN.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.RETAINER_CAPTAIN.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.RETAINER_KNIGHT.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.RETAINER_ARCHER.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.RETAINER_CROSSBOWMAN.get(), GarrisonRenderer::new);
        e.registerEntityRenderer(ModEntities.COURIER.get(), GarrisonRenderer::new);
    }

    @SubscribeEvent
    public static void setup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent e) {
        e.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                net.aiagent256.stormhold.registry.ModMenus.SOLDIER.get(), SoldierScreen::new));
    }

    private ClientEvents() {
    }
}
