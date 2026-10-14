package net.aiagent256.stormhold.block;

import net.aiagent256.stormhold.holding.City;
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

/** The captain of the watch's table at a city's barracks. Use it holding emeralds to hire soldiers. */
public class MusterPostBlock extends Block {
    public MusterPostBlock(Properties props) {
        super(props);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) City.hire(sl, sp, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
