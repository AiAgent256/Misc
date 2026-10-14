package net.aiagent256.stormhold.block;

import net.aiagent256.stormhold.holding.HoldingEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Where a castle's standard flies. Raise a War Banner here to claim it. */
public class BannerStandBlock extends Block {
    public BannerStandBlock(Properties props) {
        super(props);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            HoldingEvents.useBannerStand(sl, sp, pos, player.getItemInHand(hand));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
