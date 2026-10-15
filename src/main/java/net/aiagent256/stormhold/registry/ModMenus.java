package net.aiagent256.stormhold.registry;

import net.aiagent256.stormhold.Stormhold;
import net.aiagent256.stormhold.menu.SoldierMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, Stormhold.MODID);

    public static final RegistryObject<MenuType<SoldierMenu>> SOLDIER = MENUS.register("soldier",
            () -> IForgeMenuType.create(SoldierMenu::fromNetwork));

    private ModMenus() {
    }
}
