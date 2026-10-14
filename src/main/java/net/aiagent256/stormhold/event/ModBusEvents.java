package net.aiagent256.stormhold.event;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.entity.Role;
import net.aiagent256.stormhold.registry.ModEntities;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Stormhold.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModBusEvents {
    @SubscribeEvent
    public static void attributes(EntityAttributeCreationEvent e) {
        e.put(ModEntities.HOUSE_LORD.get(), GarrisonEntity.attributes(Role.LORD).build());
        e.put(ModEntities.HOUSE_CAPTAIN.get(), GarrisonEntity.attributes(Role.CAPTAIN).build());
        e.put(ModEntities.HOUSE_KNIGHT.get(), GarrisonEntity.attributes(Role.KNIGHT).build());
        e.put(ModEntities.HOUSE_ARCHER.get(), GarrisonEntity.attributes(Role.ARCHER).build());
        e.put(ModEntities.HOUSE_CROSSBOWMAN.get(), GarrisonEntity.attributes(Role.CROSSBOWMAN).build());
        e.put(ModEntities.RETAINER_CAPTAIN.get(), GarrisonEntity.attributes(Role.CAPTAIN).build());
        e.put(ModEntities.RETAINER_KNIGHT.get(), GarrisonEntity.attributes(Role.KNIGHT).build());
        e.put(ModEntities.RETAINER_ARCHER.get(), GarrisonEntity.attributes(Role.ARCHER).build());
        e.put(ModEntities.RETAINER_CROSSBOWMAN.get(), GarrisonEntity.attributes(Role.CROSSBOWMAN).build());
        e.put(ModEntities.COURIER.get(), GarrisonEntity.attributes(Role.COURIER).build());
    }

    private ModBusEvents() {
    }
}
