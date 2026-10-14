package net.aiagent256.stormhold.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Planted on a fallen castle's banner stand to claim it. Sneak-use to change your colour. */
public class WarBannerItem extends Item {
    public WarBannerItem(Properties props) {
        super(props);
    }

    public static DyeColor color(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains("Color") ? DyeColor.byId(stack.getTag().getInt("Color")) : DyeColor.BLUE;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            DyeColor next = DyeColor.byId((color(stack).getId() + 1) % 16);
            stack.getOrCreateTag().putInt("Color", next.getId());
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("Your colours: " + next.getName().replace('_', ' ')), true);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Colours: " + color(stack).getName().replace('_', ' ')).withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Use on a castle's banner stand once its lord is dead.").withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.literal("Sneak + use to change colours.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
