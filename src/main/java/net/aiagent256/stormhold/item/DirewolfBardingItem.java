package net.aiagent256.stormhold.item;

import net.aiagent256.stormhold.entity.DirewolfEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Barding for a direwolf: right-click your direwolf with it. Tier 1 iron, 2 diamond, 3 netherite. */
public class DirewolfBardingItem extends Item {
    private final int tier;

    public DirewolfBardingItem(int tier, Properties p) {
        super(p);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Right-click your direwolf with it: armour " + DirewolfEntity.ARMOR[tier]
                + (DirewolfEntity.TOUGHNESS[tier] > 0 ? ", toughness " + DirewolfEntity.TOUGHNESS[tier] : "") + ".").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Sneak and right-click it bare-handed to take it off.").withStyle(ChatFormatting.GRAY));
    }
}
