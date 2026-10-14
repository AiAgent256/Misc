package net.aiagent256.stormhold.registry;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.item.WarBannerItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Stormhold.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Stormhold.MODID);

    public static final RegistryObject<Item> WAR_BANNER = ITEMS.register("war_banner",
            () -> new WarBannerItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> PORTCULLIS = ITEMS.register("portcullis",
            () -> new BlockItem(ModBlocks.PORTCULLIS.get(), new Item.Properties()));
    public static final RegistryObject<Item> BANNER_STAND = ITEMS.register("banner_stand",
            () -> new BlockItem(ModBlocks.BANNER_STAND.get(), new Item.Properties()));
    public static final RegistryObject<Item> WAR_TABLE = ITEMS.register("war_table",
            () -> new BlockItem(ModBlocks.WAR_TABLE.get(), new Item.Properties()));

    public static final List<RegistryObject<Item>> EGGS = new ArrayList<>();

    static {
        egg("house_lord", ModEntities.HOUSE_LORD, 0x2b2b2b, 0xd4af37);
        egg("house_captain", ModEntities.HOUSE_CAPTAIN, 0x2b2b2b, 0x9c9c9c);
        egg("house_knight", ModEntities.HOUSE_KNIGHT, 0x5a5a5a, 0xb02020);
        egg("house_archer", ModEntities.HOUSE_ARCHER, 0x5a5a5a, 0x2f6b2f);
        egg("house_crossbowman", ModEntities.HOUSE_CROSSBOWMAN, 0x5a5a5a, 0x7a4a1a);
        egg("retainer_captain", ModEntities.RETAINER_CAPTAIN, 0x2040a0, 0x9c9c9c);
        egg("retainer_knight", ModEntities.RETAINER_KNIGHT, 0x2040a0, 0xb0b0b0);
        egg("retainer_archer", ModEntities.RETAINER_ARCHER, 0x2040a0, 0x2f6b2f);
        egg("retainer_crossbowman", ModEntities.RETAINER_CROSSBOWMAN, 0x2040a0, 0x7a4a1a);
        egg("courier", ModEntities.COURIER, 0x8a6a3a, 0xe0d0a0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void egg(String name, RegistryObject type, int a, int b) {
        EGGS.add(ITEMS.register(name + "_spawn_egg", () -> new ForgeSpawnEggItem(type, a, b, new Item.Properties())));
    }

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.stormhold"))
            .icon(() -> new ItemStack(WAR_BANNER.get()))
            .displayItems((params, out) -> {
                out.accept(WAR_BANNER.get());
                out.accept(WAR_TABLE.get());
                out.accept(PORTCULLIS.get());
                out.accept(BANNER_STAND.get());
                for (RegistryObject<Item> e : EGGS) out.accept(e.get());
            }).build());

    private ModItems() {
    }
}
