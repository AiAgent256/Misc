package net.aiagent256.stormhold.block;

import net.aiagent256.stormhold.net.Net;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Opens the command screen for every castle the player holds. */
public class WarTableBlock extends Block {
    public WarTableBlock(Properties props) {
        super(props);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) Net.openWarTable(sp);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
