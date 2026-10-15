package net.aiagent256.stormhold.menu;

import net.aiagent256.stormhold.entity.GarrisonEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A soldier's kit, as a nine-slot container straight onto the man: helmet, chestplate, leggings, boots,
 * main hand, off hand, then his three-slot food pouch. Changes land on him as they're made.
 */
public class SoldierKit implements Container {
    public static final int SIZE = 9;
    public static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private final GarrisonEntity man;

    public SoldierKit(GarrisonEntity man) {
        this.man = man;
    }

    /** What may go in a slot: the right armour piece, anything in the hands, food or drink in the pouch. */
    public static boolean allowed(int slot, ItemStack s) {
        if (s.isEmpty()) return true;
        if (slot < 4) return GarrisonEntity.gearSlot(s) == GEAR[slot];
        if (slot < 6) return true;
        return s.isEdible() || GarrisonEntity.isDrinkable(s);
    }

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < SIZE; i++) if (!getItem(i).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int i) {
        if (i < 0 || i >= SIZE) return ItemStack.EMPTY;
        return i < 6 ? man.getItemBySlot(GEAR[i]) : man.pouch().get(i - 6);
    }

    @Override
    public ItemStack removeItem(int i, int n) {
        ItemStack s = getItem(i);
        if (s.isEmpty() || n <= 0) return ItemStack.EMPTY;
        ItemStack out = s.split(n);
        setItem(i, s);
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int i) {
        ItemStack s = getItem(i);
        setItem(i, ItemStack.EMPTY);
        return s;
    }

    @Override
    public void setItem(int i, ItemStack s) {
        if (i < 0 || i >= SIZE) return;
        if (i < 6) {
            man.setItemSlot(GEAR[i], s);
            man.setDropChance(GEAR[i], 0.0F);
        } else {
            man.pouch().set(i - 6, s);
        }
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player p) {
        return man.isAlive() && p.distanceToSqr(man) < 64 && p.getUUID().equals(man.owner());
    }

    @Override
    public void clearContent() {
    }
}
