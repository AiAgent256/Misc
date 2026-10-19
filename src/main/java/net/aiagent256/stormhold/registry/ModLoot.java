package net.aiagent256.stormhold.registry;

import com.mojang.serialization.Codec;
import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.holding.FrontierLoot;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModLoot {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Stormhold.MODID);

    public static final RegistryObject<Codec<FrontierLoot>> FRONTIER_KIT = MODIFIERS.register("frontier_kit", FrontierLoot.CODEC);

    private ModLoot() {
    }
}
