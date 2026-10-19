package net.aiagent256.stormhold.item;

import net.aiagent256.stormhold.net.Net;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A War Table you carry. Sounding it opens the command screen anywhere, so you can set stances, send
 * war parties, and above all call men out of a nearby castle to ride with you, or send them back in.
 */
public class WarHornItem extends Item {
    public WarHornItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (player.isShiftKeyDown()) {
                var mgr = net.aiagent256.stormhold.holding.HoldingManager.get(sp.serverLevel());
                // looking at a castle's walls far off: a trebuchet; otherwise a siege tower where you stand
                var target = net.aiagent256.stormhold.holding.Engines.aimedAt(sp.serverLevel(), mgr, sp);
                if (target != null) net.aiagent256.stormhold.holding.Engines.raise(sp.serverLevel(), mgr, sp, target);
                else net.aiagent256.stormhold.holding.Siege.raise(sp.serverLevel(), mgr, sp);
                player.getCooldowns().addCooldown(this, 40);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.6F, 1.1F);
            Net.openWarTable(sp);
            player.getCooldowns().addCooldown(this, 20);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable("item.stormhold.war_horn.tip"));
    }
}
