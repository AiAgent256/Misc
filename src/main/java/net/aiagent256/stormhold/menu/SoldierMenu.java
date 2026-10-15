package net.aiagent256.stormhold.menu;

import com.mojang.datafixers.util.Pair;
import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.aiagent256.stormhold.registry.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** The kit screen for one of the player's soldiers: his six gear slots and pouch over the player's inventory. */
public class SoldierMenu extends AbstractContainerMenu {
    private static final ResourceLocation[] EMPTY = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, null, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD};

    private final Container kit;
    @Nullable private final GarrisonEntity man;

    public SoldierMenu(int id, Inventory inv, Container kit, @Nullable GarrisonEntity man) {
        super(ModMenus.SOLDIER.get(), id);
        checkContainerSize(kit, SoldierKit.SIZE);
        this.kit = kit;
        this.man = man;
        kit.startOpen(inv.player);
        for (int i = 0; i < SoldierKit.SIZE; i++) {
            final int k = i;
            this.addSlot(new Slot(kit, i, 8 + i * 18, 18) {
                @Override
                public boolean mayPlace(ItemStack s) {
                    return SoldierKit.allowed(k, s);
                }

                @Override
                public int getMaxStackSize() {
                    return k < 6 ? 1 : super.getMaxStackSize();
                }

                @Nullable
                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return k < 6 && EMPTY[k] != null ? Pair.of(InventoryMenu.BLOCK_ATLAS, EMPTY[k]) : null;
                }
            });
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 49 + r * 18));
        }
        for (int c = 0; c < 9; c++) this.addSlot(new Slot(inv, c, 8 + c * 18, 107));
    }

    public static SoldierMenu fromNetwork(int id, Inventory inv, FriendlyByteBuf buf) {
        var e = inv.player.level().getEntity(buf.readVarInt());
        return new SoldierMenu(id, inv, new SimpleContainer(SoldierKit.SIZE), e instanceof GarrisonEntity g ? g : null);
    }

    @Nullable
    public GarrisonEntity man() {
        return man;
    }

    @Override
    public boolean stillValid(Player p) {
        return kit.stillValid(p);
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        kit.stopOpen(p);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack s = slot.getItem();
        ItemStack copy = s.copy();
        int kitEnd = SoldierKit.SIZE, end = this.slots.size();
        if (index < kitEnd) {
            if (!this.moveItemStackTo(s, kitEnd, end, true)) return ItemStack.EMPTY;
        } else {
            // into the first kit slot that takes it: gear to its place, food to the pouch
            boolean moved = false;
            for (int i = 0; i < kitEnd && !s.isEmpty(); i++) {
                Slot k = this.slots.get(i);
                if (!k.mayPlace(s)) continue;
                if (i < 6) {
                    if (!k.hasItem()) {
                        k.set(s.split(1));
                        moved = true;
                    }
                } else if (this.moveItemStackTo(s, i, i + 1, false)) {
                    moved = true;
                }
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (s.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
}
