package net.aiagent256.stormhold.item;

import net.aiagent256.stormhold.holding.Direwolves;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A bone whistle: calls your direwolf to you from wherever it is, or sends it off into the hills when it's at your side. */
public class DirewolfWhistleItem extends Item {
    public DirewolfWhistleItem(Properties p) {
        super(p);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        level.playSound(player, player.blockPosition(), SoundEvents.NOTE_BLOCK_FLUTE.value(), SoundSource.PLAYERS, 1.0F, 1.6F);
        if (level instanceof ServerLevel sl) {
            Direwolves.whistle(sl, player);
            player.getCooldowns().addCooldown(this, 20);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Calls your direwolf to you from wherever it is,").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("or sends it off when it's at your side.").withStyle(ChatFormatting.GRAY));
    }
}
