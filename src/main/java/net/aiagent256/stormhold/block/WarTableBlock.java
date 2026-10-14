package net.aiagent256.stormhold.block;

import net.aiagent256.stormhold.holding.Founding;
import net.aiagent256.stormhold.holding.Holding;
import net.aiagent256.stormhold.holding.HoldingManager;
import net.aiagent256.stormhold.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Opens the command screen for every castle the player holds. Use it holding supplies to put them in
 * that castle's stores. Placing one inside a region marked with a War Banner founds a new holding.
 */
public class WarTableBlock extends Block {
    /** Items a castle knows what to do with. Anything else stays in your hand. */
    public static final Set<String> SUPPLIES = Set.of("minecraft:bread", "minecraft:iron_ingot", "minecraft:arrow",
            "minecraft:gold_ingot", "minecraft:emerald", "minecraft:wheat", "minecraft:coal", "minecraft:leather",
            "minecraft:diamond", "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:baked_potato");

    public WarTableBlock(Properties props) {
        super(props);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        ItemStack held = player.getItemInHand(hand);
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (!held.isEmpty() && id != null && SUPPLIES.contains(id.toString())) {
            ServerLevel sl = (ServerLevel) level;
            HoldingManager mgr = HoldingManager.get(sl);
            Holding h = mgr.at(pos, 2);
            if (h == null || !h.owner.equals(net.aiagent256.stormhold.Sides.player(player.getUUID()))) {
                player.displayClientMessage(Component.literal("This table commands no castle of yours.").withStyle(ChatFormatting.RED), true);
                return InteractionResult.sidedSuccess(false);
            }
            int n = held.getCount();
            String name = held.getHoverName().getString();
            h.addStock(id, n);
            held.shrink(n);
            mgr.setDirty();
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6F, 0.9F);
            player.displayClientMessage(Component.literal(n + " " + name + " added to the stores of " + h.name
                    + " (bread " + h.stock(new ResourceLocation("minecraft:bread")) + ", iron "
                    + h.stock(new ResourceLocation("minecraft:iron_ingot")) + ").").withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.sidedSuccess(false);
        }
        if (player instanceof ServerPlayer sp) Net.openWarTable(sp);
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel sl && placer instanceof Player p) Founding.tryFound(sl, p, pos);
    }
}
